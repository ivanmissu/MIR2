package com.mir2.gate;

import com.mir2.character.Character;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.Direction;
import com.mir2.world.Position;
import com.mir2.world.WorldEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Stateful socket handler for the PoC login and character-selection path. */
public final class LegacyGateHandler implements BiConsumer<ClientConnection, IOException> {
  public record Config(String advertisedHost, int selectPort, int gamePort, String serverName) {
    public Config {
      Objects.requireNonNull(advertisedHost, "advertisedHost");
      Objects.requireNonNull(serverName, "serverName");
      if (selectPort < 1 || selectPort > 65535 || gamePort < 1 || gamePort > 65535)
        throw new IllegalArgumentException("invalid advertised port");
      if (serverName.isBlank() || serverName.indexOf('/') >= 0) throw new IllegalArgumentException("invalid server name");
    }

    public static Config defaults() {
      return new Config("127.0.0.1", GatePorts.DEFAULT_SELECT, GatePorts.DEFAULT_GAME, "MIR2");
    }
  }

  public record WorldConfig(WorldEngine engine, String mapId, Position spawn, Direction direction) {
    public WorldConfig {
      Objects.requireNonNull(engine, "engine");
      Objects.requireNonNull(mapId, "mapId");
      Objects.requireNonNull(spawn, "spawn");
      Objects.requireNonNull(direction, "direction");
    }
  }

  private final SessionRouter router;
  private final GateSessionRegistry sessions;
  private final Config config;
  private final Consumer<Throwable> errorHandler;
  private final WorldConfig world;

  public LegacyGateHandler(SessionRouter router) {
    this(router, new GateSessionRegistry(), Config.defaults(), ignored -> {});
  }

  public LegacyGateHandler(SessionRouter router, GateSessionRegistry sessions, Config config,
      Consumer<Throwable> errorHandler) {
    this(router, sessions, config, errorHandler, null);
  }

  public LegacyGateHandler(SessionRouter router, GateSessionRegistry sessions, Config config,
      Consumer<Throwable> errorHandler, WorldConfig world) {
    this.router = Objects.requireNonNull(router);
    this.sessions = Objects.requireNonNull(sessions);
    this.config = Objects.requireNonNull(config);
    this.errorHandler = Objects.requireNonNull(errorHandler);
    this.world = world;
  }

  @Override
  public void accept(ClientConnection connection, IOException acceptError) {
    if (acceptError != null) {
      errorHandler.accept(acceptError);
      return;
    }
    try (connection) {
      ConnectionState state = new ConnectionState();
      if (connection.kind() == GateKind.GAME) {
        if (!authenticateGameConnection(connection, state)) return;
        if (world != null) {
          serveGame(connection, state);
          return;
        }
      }

      WirePacket packet;
      while ((packet = WireMessageCodec.readPacket(connection.input())) != null) {
        WirePacket response = dispatch(connection.kind(), state, packet);
        if (response != null) WireMessageCodec.writePacket(connection.output(), response);
      }
    } catch (IOException | RuntimeException error) {
      errorHandler.accept(error);
    }
  }

  private void serveGame(ClientConnection connection, ConnectionState state) throws IOException {
    GameProtocolAdapter adapter = new GameProtocolAdapter(world.engine(), outbound -> {
      try {
        WireMessageCodec.writeGameOutbound(connection.output(), outbound);
      } catch (IOException error) {
        connection.close();
        throw new UncheckedIOException(error);
      }
    });

    int playerId = 0;
    try {
      playerId = world.engine().enterPlayerNear(
          state.selectedCharacter.id(), state.selectedCharacter.name(), world.mapId(), world.spawn(),
          world.direction(), state.selectedCharacter.feature(), 0,
          // btJob selects the RecalcLevelAbilitys growth branch (warrior/wizard/taoist).
          state.selectedCharacter.job(), adapter).join().id();
      // The certification is a one-time admission ticket, mirroring the Delphi id-server's
      // single-use session id (M2Server/IdSrvClient.pas:DelSession). It is consumed only once
      // the world has actually admitted the player, not merely once RunLogin authenticated:
      // a transient world rejection (e.g. the previous session's leavePlayer race, "player is
      // already online") must leave the certification intact so LoadtestMain/Mir2Bot's
      // ENTER_ATTEMPTS retry can open a fresh GAME connection with the same certification.
      sessions.remove(state.certification);
      WirePacket packet;
      while ((packet = WireMessageCodec.readPacket(connection.input())) != null) {
        adapter.handle(packet);
      }
    } catch (RuntimeException error) {
      if (playerId == 0 && connection.isOpen()) {
        WireMessageCodec.writePacket(connection.output(), failure(ProtocolConstants.SM_STARTFAIL, 0));
      }
      throw error;
    } finally {
      // Disconnect cleanup mirrors the entry guard above: only a connection that actually
      // entered the world (playerId > 0) may have consumed the certification, so only that
      // case needs the idempotent safety-net removal here. A failed entry attempt (playerId
      // stays 0, e.g. the previous session's leavePlayer race reported "player is already
      // online") must leave the certification untouched — the retry loop in
      // Mir2Bot#openGameSession opens a brand new GAME connection with the very same
      // certification, and unconditionally removing it here would make every retry fail
      // authentication instead of eventually succeeding.
      if (playerId > 0) {
        world.engine().leavePlayer(playerId);
        sessions.remove(state.certification);
      }
    }
  }

  private boolean authenticateGameConnection(ClientConnection connection, ConnectionState state) throws IOException {
    final RunLogin login;
    try {
      login = WireMessageCodec.readRunLogin(connection.input());
    } catch (IOException malformed) {
      WireMessageCodec.writePacket(connection.output(), failure(ProtocolConstants.SM_STARTFAIL, 0));
      errorHandler.accept(malformed);
      return false;
    }
    if (login == null) return false;
    try {
      GateSessionRegistry.Session session = authenticateGame(login);
      state.bindGame(login.account(), session.authToken(), login.certification(),
          session.selectedCharacter(), login.clientVersion(), login.loginCode());
      return true;
    } catch (SecurityException error) {
      WireMessageCodec.writePacket(connection.output(), failure(ProtocolConstants.SM_STARTFAIL, 0));
      return false;
    }
  }

  GateSessionRegistry.Session authenticateGame(RunLogin login) {
    Objects.requireNonNull(login);
    return sessions.requireGame(login.account(), login.characterName(), login.certification());
  }

  WirePacket dispatch(GateKind kind, ConnectionState state, WirePacket packet) {
    return switch (kind) {
      case LOGIN -> dispatchLogin(state, packet);
      case SELECT -> dispatchSelect(state, packet);
      // GAME messages are connected to WorldEngine by the next W03 protocol-adapter step.
      case GAME -> failure(ProtocolConstants.SM_STARTFAIL, 0);
    };
  }

  private WirePacket dispatchLogin(ConnectionState state, WirePacket packet) {
    int ident = packet.message().ident();
    if (ident == ProtocolConstants.CM_PROTOCOL) {
      return response(ProtocolConstants.SM_CERTIFICATION_SUCCESS, 0, 0, 0, 0);
    }
    if (ident == ProtocolConstants.CM_IDPASSWORD) {
      String[] fields = fields(packet, 2);
      try {
        String token = router.login(fields[0], fields[1]);
        int certification = sessions.register(fields[0], token);
        state.bind(fields[0], token, certification);
        return responseWithBody(ProtocolConstants.SM_PASSOK_SELECTSERVER, 0, 0, 0, 1,
            config.serverName() + "/1/");
      } catch (SecurityException error) {
        return failure(ProtocolConstants.SM_PASSWD_FAIL, -1);
      }
    }
    if (ident == ProtocolConstants.CM_SELECTSERVER && state.authToken != null) {
      String requested = WireMessageCodec.decodeBody(packet.encodedBody());
      if (!requested.equals(config.serverName())) return failure(ProtocolConstants.SM_STARTFAIL, 0);
      return responseWithBody(ProtocolConstants.SM_SELECTSERVER_OK, 0, 0, 0, 0,
          config.advertisedHost() + "/" + config.selectPort() + "/" + state.certification);
    }
    return failure(ProtocolConstants.SM_CERTIFICATION_FAIL, 0);
  }

  private WirePacket dispatchSelect(ConnectionState state, WirePacket packet) {
    try {
      return switch (packet.message().ident()) {
        case ProtocolConstants.CM_QUERYCHR -> queryCharacters(state, packet);
        case ProtocolConstants.CM_NEWCHR -> createCharacter(state, packet);
        case ProtocolConstants.CM_DELCHR -> deleteCharacter(state, packet);
        case ProtocolConstants.CM_SELCHR -> selectCharacter(state, packet);
        default -> failure(ProtocolConstants.SM_QUERYCHR_FAIL, 0);
      };
    } catch (SecurityException error) {
      return failure(ProtocolConstants.SM_OUTOFCONNECTION, 0);
    } catch (IllegalArgumentException error) {
      return failure(ProtocolConstants.SM_NEWCHR_FAIL, 0);
    }
  }

  private WirePacket queryCharacters(ConnectionState state, WirePacket packet) {
    String[] fields = fields(packet, 2);
    bindCertification(state, fields[0], parseCertification(fields[1]));
    List<Character> characters = router.characters(state.authToken);
    StringBuilder body = new StringBuilder();
    for (Character character : characters.stream().limit(2).toList()) {
      body.append(character.name()).append('/').append(character.job()).append('/')
          .append(character.hair()).append('/').append(character.level()).append('/')
          .append(character.gender()).append('/');
    }
    return responseWithBody(ProtocolConstants.SM_QUERYCHR, characters.size(), 0, 1, 0, body.toString());
  }

  private WirePacket createCharacter(ConnectionState state, WirePacket packet) {
    String[] fields = fields(packet, 5);
    requireAccount(state, fields[0]);
    Character created = router.create(state.authToken, fields[1], parseSmallInt(fields[3]),
        parseSmallInt(fields[2]), parseSmallInt(fields[4]));
    return response(ProtocolConstants.SM_NEWCHR_SUCCESS, created.level(), 0, 0, 0);
  }

  private WirePacket deleteCharacter(ConnectionState state, WirePacket packet) {
    requireBound(state);
    String name = WireMessageCodec.decodeBody(packet.encodedBody());
    Character character = router.characters(state.authToken).stream()
        .filter(candidate -> candidate.name().equals(name)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("character not found"));
    router.delete(state.authToken, character.id());
    return response(ProtocolConstants.SM_DELCHR_SUCCESS, 0, 0, 0, 0);
  }

  private WirePacket selectCharacter(ConnectionState state, WirePacket packet) {
    String[] fields = fields(packet, 2);
    requireAccount(state, fields[0]);
    Character character = router.characters(state.authToken).stream()
        .filter(candidate -> candidate.name().equals(fields[1]))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("character not found"));
    GateSessionRegistry.Session selected =
        sessions.select(state.account, state.certification, character);
    state.selectedCharacter = selected.selectedCharacter();
    return responseWithBody(ProtocolConstants.SM_STARTPLAY, 0, 0, 0, 0,
        config.advertisedHost() + "/" + config.gamePort());
  }

  private void bindCertification(ConnectionState state, String account, int certification) {
    GateSessionRegistry.Session session = sessions.require(account, certification);
    state.bind(account, session.authToken(), certification);
  }

  private static void requireAccount(ConnectionState state, String account) {
    requireBound(state);
    if (!state.account.equals(account)) throw new SecurityException("account does not match session");
  }

  private static void requireBound(ConnectionState state) {
    if (state.authToken == null) throw new SecurityException("connection is not certified");
  }

  private static int parseCertification(String value) {
    try {
      int result = Integer.parseInt(value);
      if (result <= 0) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException error) {
      throw new SecurityException("invalid certification", error);
    }
  }

  private static int parseSmallInt(String value) {
    try {
      int result = Integer.parseInt(value);
      if (result < 0 || result > 255) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("invalid numeric field", error);
    }
  }

  private static String[] fields(WirePacket packet, int minimum) {
    String[] fields = WireMessageCodec.decodeBody(packet.encodedBody()).split("/", -1);
    if (fields.length < minimum) throw new IllegalArgumentException("missing packet fields");
    return fields;
  }

  private static WirePacket failure(int ident, int recog) {
    return response(ident, recog, 0, 0, 0);
  }

  private static WirePacket response(int ident, int recog, int param, int tag, int series) {
    return new WirePacket(new DefaultMessage(recog, ident, param, tag, series));
  }

  private static WirePacket responseWithBody(int ident, int recog, int param, int tag, int series, String body) {
    return new WirePacket(new DefaultMessage(recog, ident, param, tag, series), WireMessageCodec.encodeBody(body));
  }

  static final class ConnectionState {
    private String account;
    private String authToken;
    private int certification;
    private GateSessionRegistry.SelectedCharacter selectedCharacter;
    private int clientVersion;
    private int loginCode;

    private void bind(String account, String authToken, int certification) {
      this.account = account;
      this.authToken = authToken;
      this.certification = certification;
    }

    private void bindGame(String account, String authToken, int certification,
        GateSessionRegistry.SelectedCharacter selectedCharacter, int clientVersion, int loginCode) {
      bind(account, authToken, certification);
      this.selectedCharacter = Objects.requireNonNull(selectedCharacter);
      this.clientVersion = clientVersion;
      this.loginCode = loginCode;
    }
  }
}
