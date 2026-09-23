package com.mir2.shadowdiff;

import com.mir2.gate.ClientItemCodec;
import com.mir2.gate.WireMessageCodec;
import com.mir2.gate.WirePacket;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.BackpackItem;
import com.mir2.world.Direction;
import com.mir2.world.EquipmentSlot;
import com.mir2.world.Position;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One scripted client session against one server: full three-gate login (the same exchange
 * {@code mir2.exe} performs), then op-by-op execution with a quiet-window drain after every
 * op so each op's observable packets land in its own bucket.
 *
 * <p>The session tracks exactly what a real client could know from the wire — cell, facing,
 * pools, level, gold, bag and worn set — and exposes it as {@link StateSnapshot}s that the
 * differ compares across servers.
 */
final class ShadowSession implements AutoCloseable {
  /** Delphi client constants echoed by ClMain.pas SendRunLogin. */
  private static final int CLIENT_VERSION = 120040918;
  private static final int LOGIN_CODE = 9;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration LOGIN_READ_TIMEOUT = Duration.ofSeconds(5);
  private static final int ENTER_ATTEMPTS = 12;
  private static final Duration ENTER_RETRY_DELAY = Duration.ofMillis(300);

  private final WireTarget target;
  private final String account;
  private final String password;
  private final String characterName;
  private final String serverName;
  private final Duration settleWindow;

  private ShadowWireClient game;

  // --- wire-derived player state ---
  private String mapId = "";
  private Position position = new Position(0, 0);
  private Direction direction = Direction.DOWN;
  private int selfId = -1;
  private int hp = -1;
  private int maxHp = -1;
  private int mp = -1;
  private int maxMp = -1;
  private int level = -1;
  private long experience = -1;
  private long gold = -1;
  /**
   * Combat facts observed during the current op's drain window, normalised to be
   * server-independent: object ids are replaced by "self" / "other" because each server
   * allocates its own. Cleared at the start of every op so each bucket stands alone.
   */
  private final List<String> combat = new ArrayList<>();
  /**
   * Every other actor the client currently believes is in view, keyed by its server-local
   * object id and carrying the last cell/facing broadcast for it. This is the observation
   * surface for 会动的怪对拍: a monster that took a different step on one server lands on a
   * different cell here. Ids never leave this map — {@link #neighbourLines()} renders the
   * census by cell so it is comparable across processes that number objects differently.
   */
  private final Map<Integer, String> neighbours = new LinkedHashMap<>();
  /**
   * The last world time echoed by the {@code @tick} pump, or -1 on servers running an
   * ordinary clock. Compared across servers so an unequal number of pumped ticks is caught
   * directly instead of only through its downstream effects.
   */
  private long worldTime = -1;
  private final Map<Integer, BackpackItem> bag = new LinkedHashMap<>();
  private final Map<Integer, BackpackItem> worn = new LinkedHashMap<>();

  // --- pending equipment bookkeeping (applied on SM_TAKEON_OK / SM_TAKEOFF_OK) ---
  private Integer pendingTakeOnMakeIndex;
  private Integer pendingTakeOnSlot;
  private Integer pendingTakeOffSlot;

  ShadowSession(WireTarget target, String account, String password, String characterName,
      String serverName, Duration settleWindow) {
    this.target = Objects.requireNonNull(target, "target");
    this.account = Objects.requireNonNull(account, "account");
    this.password = Objects.requireNonNull(password, "password");
    this.characterName = Objects.requireNonNull(characterName, "characterName");
    this.serverName = Objects.requireNonNull(serverName, "serverName");
    this.settleWindow = Objects.requireNonNull(settleWindow, "settleWindow");
  }

  /**
   * Runs the full login → select → RunLogin chain and returns the entry observation
   * (the six-packet RM_LOGON bootstrap SM_NEWMAP/SM_CHANGELIGHT/SM_LOGON/
   * SM_FEATURECHANGED/SM_USERNAME/SM_MAPDESCRIPTION plus everything inside the settle
   * window). Like the real client, a {@code CM_QUERYBAGITEMS} follows immediately after
   * entry.
   */
  OpObservation enter() throws IOException {
    combat.clear();
    LoginRoute route = login();
    String gameEndpoint = selectCharacter(route);

    IOException lastFailure = null;
    for (int attempt = 0; attempt < ENTER_ATTEMPTS; attempt++) {
      try {
        List<String> messages = openGameSession(gameEndpoint, route.certification());
        // ClMain.pas:3860 — the real client queries the bag right after SM_LOGON.
        game.sendPacket(message(ProtocolConstants.CM_QUERYBAGITEMS, 0, 0, 0, 0), "");
        Drained drained = drain();
        messages.addAll(drained.messages());
        return new OpObservation("enter", drained.acks(), List.copyOf(messages), snapshot());
      } catch (EnterRetry | IOException error) {
        if (error instanceof IOException ioError) lastFailure = ioError;
        closeGame();
        sleep(ENTER_RETRY_DELAY);
      }
    }
    throw lastFailure != null ? lastFailure : new IOException("could not enter the world");
  }

  /** Executes one op and returns its observation bucket. */
  OpObservation perform(Op op) throws IOException {
    Objects.requireNonNull(op, "op");
    combat.clear();
    switch (op.kind()) {
      case SLEEP -> {
        sleep(Duration.ofMillis(op.millis()));
        Drained drained = drain();
        return new OpObservation(op.describe(), drained.acks(), drained.messages(), snapshot());
      }
      case TICK -> {
        // The determinism pump: `@tick N` over CM_SAY runs exactly N tick bodies on a
        // MANUAL world. Everything the monsters then do lands in this op's bucket, so a
        // divergent AI step is attributed to the tick that produced it.
        game.sendPacket(message(ProtocolConstants.CM_SAY, 0, 0, 0, 0), "@tick " + op.millis());
        Drained drained = drain();
        return new OpObservation(op.describe(), drained.acks(), drained.messages(), snapshot());
      }
      case RELOG -> {
        closeGame();
        return relabel(enter(), op.describe());
      }
      case TURN -> {
        pendingTurnDirection = op.direction();
        sendAction(message(ProtocolConstants.CM_TURN,
            packed(position), 0, op.direction().code(), 0));
      }
      case WALK -> sendMove(op.direction(), 1);
      case RUN -> sendMove(op.direction(), 2);
      case HIT -> sendAction(message(ProtocolConstants.CM_HIT,
          packed(position), 0, op.direction().code(), 0));
      case HEAVYHIT -> sendAction(message(ProtocolConstants.CM_HEAVYHIT,
          packed(position), 0, op.direction().code(), 0));
      case BIGHIT -> sendAction(message(ProtocolConstants.CM_BIGHIT,
          packed(position), 0, op.direction().code(), 0));
      case PICKUP -> sendAction(message(ProtocolConstants.CM_PICKUP,
          0, position.x(), position.y(), 0));
      case BAG -> sendAction(message(ProtocolConstants.CM_QUERYBAGITEMS, 0, 0, 0, 0));
      case SAY -> game.sendPacket(message(ProtocolConstants.CM_SAY, 0, 0, 0, 0), op.text());
      case DROP -> {
        BackpackItem item = findInBag(op.text());
        if (item == null) {
          // Delphi cannot even send the packet without the item; the harness sends a
          // deliberately unresolvable MakeIndex so the server's not-found path runs.
          game.sendPacket(message(ProtocolConstants.CM_DROPITEM, 0, 0, 0, 0), op.text());
        } else {
          game.sendPacket(message(ProtocolConstants.CM_DROPITEM,
              item.makeIndex(), 0, 0, 0), op.text());
        }
      }
      case EAT -> {
        BackpackItem item = findInBag(op.text());
        int makeIndex = item == null ? 0 : item.makeIndex();
        game.sendPacket(message(ProtocolConstants.CM_EAT, makeIndex, 0, 0, 0), op.text());
      }
      case TAKEON -> {
        BackpackItem item = findInBag(op.text());
        int makeIndex = item == null ? 0 : item.makeIndex();
        int slot = item == null ? 0 : chooseSlot(item);
        pendingTakeOnMakeIndex = makeIndex;
        pendingTakeOnSlot = slot;
        game.sendPacket(message(ProtocolConstants.CM_TAKEONITEM,
            makeIndex, slot, 0, 0), op.text());
      }
      case TAKEOFF -> {
        Integer slot = findWornSlot(op.text());
        int makeIndex = slot == null ? 0 : worn.get(slot).makeIndex();
        pendingTakeOffSlot = slot == null ? -1 : slot;
        game.sendPacket(message(ProtocolConstants.CM_TAKEOFFITEM,
            makeIndex, slot == null ? 0 : slot, 0, 0), op.text());
      }
    }
    Drained drained = drain();
    return new OpObservation(op.describe(), drained.acks(), drained.messages(), snapshot());
  }

  StateSnapshot snapshot() {
    return new StateSnapshot(mapId, position.x(), position.y(), direction.code(),
        hp, maxHp, mp, maxMp, level, experience, gold, itemLines(bag), itemLines(worn),
        List.copyOf(combat), neighbourLines(), worldTime);
  }

  /**
   * The in-view actor census, rendered id-free and sorted so two servers can be compared.
   * An entry is {@code cell dir=N}; identical monster AI decisions produce identical lines.
   */
  private List<String> neighbourLines() {
    return neighbours.values().stream().sorted(Comparator.naturalOrder()).toList();
  }

  @Override
  public void close() {
    closeGame();
  }

  // ------------------------------------------------------------ login chain

  private record LoginRoute(int certification, String selectEndpoint) {}

  private LoginRoute login() throws IOException {
    try (ShadowWireClient login = ShadowWireClient.connect(
        target.host(), target.loginPort(), CONNECT_TIMEOUT)) {
      login.setReadTimeout(LOGIN_READ_TIMEOUT);
      login.sendPacket(message(ProtocolConstants.CM_PROTOCOL, 0, 0, 0, 0), "");
      expect(login, "certification", ProtocolConstants.SM_CERTIFICATION_SUCCESS);

      login.sendPacket(message(ProtocolConstants.CM_IDPASSWORD, 0, 0, 0, 0),
          account + "/" + password);
      WirePacket passOk = expect(login, "login",
          ProtocolConstants.SM_PASSOK_SELECTSERVER, ProtocolConstants.SM_PASSWD_FAIL);
      if (passOk.message().ident() == ProtocolConstants.SM_PASSWD_FAIL)
        throw new IOException(target.label() + ": credentials rejected");

      login.sendPacket(message(ProtocolConstants.CM_SELECTSERVER, 0, 0, 0, 0), serverName);
      WirePacket route = expect(login, "server select",
          ProtocolConstants.SM_SELECTSERVER_OK, ProtocolConstants.SM_STARTFAIL);
      if (route.message().ident() == ProtocolConstants.SM_STARTFAIL)
        throw new IOException(target.label() + ": server select refused");
      String[] fields = WireMessageCodec.decodeBody(route.encodedBody()).split("/", -1);
      if (fields.length != 3)
        throw new IOException(target.label() + ": malformed select-server route");
      int certification = Integer.parseInt(fields[2]);
      String endpoint = target.selectPortOverride() > 0
          ? target.host() + "/" + target.selectPortOverride() : fields[0] + "/" + fields[1];
      return new LoginRoute(certification, endpoint);
    }
  }

  private String selectCharacter(LoginRoute route) throws IOException {
    String[] endpoint = route.selectEndpoint().split("/", -1);
    try (ShadowWireClient select = ShadowWireClient.connect(endpoint[0],
        Integer.parseInt(endpoint[1]), CONNECT_TIMEOUT)) {
      select.setReadTimeout(LOGIN_READ_TIMEOUT);
      select.sendPacket(message(ProtocolConstants.CM_QUERYCHR, 0, 0, 0, 0),
          account + "/" + route.certification());
      WirePacket list = expect(select, "character list",
          ProtocolConstants.SM_QUERYCHR, ProtocolConstants.SM_OUTOFCONNECTION);
      if (list.message().ident() == ProtocolConstants.SM_OUTOFCONNECTION)
        throw new IOException(target.label() + ": character list refused");
      if (!listedCharacters(list).contains(characterName)) {
        // Warrior, hair 0, male — matching the bot swarm's CM_NEWCHR literal.
        select.sendPacket(message(ProtocolConstants.CM_NEWCHR, 0, 0, 0, 0),
            account + "/" + characterName + "/2/0/1");
        WirePacket created = expect(select, "character create",
            ProtocolConstants.SM_NEWCHR_SUCCESS, ProtocolConstants.SM_NEWCHR_FAIL);
        if (created.message().ident() == ProtocolConstants.SM_NEWCHR_FAIL)
          throw new IOException(target.label() + ": character create refused");
      }

      select.sendPacket(message(ProtocolConstants.CM_SELCHR, 0, 0, 0, 0),
          account + "/" + characterName);
      WirePacket play = expect(select, "character select",
          ProtocolConstants.SM_STARTPLAY, ProtocolConstants.SM_OUTOFCONNECTION);
      if (play.message().ident() == ProtocolConstants.SM_OUTOFCONNECTION)
        throw new IOException(target.label() + ": character select refused");
      String[] fields = WireMessageCodec.decodeBody(play.encodedBody()).split("/", -1);
      if (fields.length != 2)
        throw new IOException(target.label() + ": malformed start-play route");
      return target.gamePortOverride() > 0
          ? target.host() + "/" + target.gamePortOverride() : fields[0] + "/" + fields[1];
    }
  }

  private static List<String> listedCharacters(WirePacket list) {
    String[] fields = WireMessageCodec.decodeBody(list.encodedBody()).split("/", -1);
    List<String> names = new ArrayList<>();
    for (int index = 0; index + 4 < fields.length; index += 5) names.add(fields[index]);
    return names;
  }

  /** Opens the GAME socket and consumes the three entry packets. */
  private List<String> openGameSession(String endpoint, int certification) throws IOException {
    String[] fields = endpoint.split("/", -1);
    ShadowWireClient client = ShadowWireClient.connect(fields[0],
        Integer.parseInt(fields[1]), CONNECT_TIMEOUT);
    try {
      client.setReadTimeout(LOGIN_READ_TIMEOUT);
      client.sendRunLogin(account, characterName, certification, CLIENT_VERSION, LOGIN_CODE);

      // The recorded entry signature pins the exact RM_LOGON order
      // (ObjBase.pas:5618): SM_NEWMAP → SM_CHANGELIGHT → SendLogon's SM_LOGON +
      // SM_FEATURECHANGED → the proactive SM_USERNAME → SM_MAPDESCRIPTION. Any drift
      // in the bootstrap sequence shows up as a shadow diff, which is the point of
      // the harness.
      List<String> messages = new ArrayList<>();
      messages.add(expectGame(client, ProtocolConstants.SM_NEWMAP));
      messages.add(expectGame(client, ProtocolConstants.SM_CHANGELIGHT));
      messages.add(expectGame(client, ProtocolConstants.SM_LOGON));
      messages.add(expectGame(client, ProtocolConstants.SM_FEATURECHANGED));
      messages.add(expectGame(client, ProtocolConstants.SM_USERNAME));
      messages.add(expectGame(client, ProtocolConstants.SM_MAPDESCRIPTION));
      this.game = client;
      return messages;
    } catch (EnterRetry | IOException error) {
      client.close();
      throw error;
    }
  }

  private String expectGame(ShadowWireClient client, int expectedIdent) throws IOException {
    byte[] frame = client.readFrame();
    if (frame == null) throw new EnterRetry("connection closed during enter");
    if (ShadowWireClient.isStatusFrame(frame))
      throw new EnterRetry("unexpected ack during enter");
    WirePacket packet = ShadowWireClient.parsePacket(frame);
    if (packet.message().ident() != expectedIdent)
      throw new EnterRetry("expected ident " + expectedIdent
          + ", got " + packet.message().ident());
    handlePacket(packet);
    return IdentNames.serverName(packet.message().ident());
  }

  // ------------------------------------------------------------ op plumbing

  private void sendAction(DefaultMessage message) throws IOException {
    game.sendPacket(message, "");
  }

  private void sendMove(Direction heading, int steps) throws IOException {
    Position stepTarget = position.translate(heading, steps);
    int ident = steps >= 2 ? ProtocolConstants.CM_RUN : ProtocolConstants.CM_WALK;
    game.sendPacket(message(ident, packed(stepTarget), 0, heading.code(), 0), "");
    // The +GOOD in the drain window confirms the move; handleAck moves the local belief.
    pendingMoveTarget = stepTarget;
    pendingMoveHeading = heading;
  }

  private Position pendingMoveTarget;
  private Direction pendingMoveHeading;
  private DefaultMessage lastActionSent;

  private record Drained(List<String> acks, List<String> messages) {}

  /** Reads until the socket stays quiet for the settle window; classifies every frame. */
  private Drained drain() throws IOException {
    List<String> acks = new ArrayList<>();
    List<String> messages = new ArrayList<>();
    if (game == null) return new Drained(acks, messages);
    game.setReadTimeout(settleWindow);
    try {
      while (true) {
        byte[] frame;
        try {
          frame = game.readFrame();
        } catch (SocketTimeoutException quiet) {
          return new Drained(acks, messages);
        }
        if (frame == null) {
          messages.add("<EOF>");
          return new Drained(acks, messages);
        }
        if (ShadowWireClient.isStatusFrame(frame)) {
          boolean accepted = ShadowWireClient.statusAccepted(frame);
          acks.add(accepted ? "+GOOD" : "+FAIL");
          handleAck(accepted);
        } else {
          WirePacket packet = ShadowWireClient.parsePacket(frame);
          messages.add(IdentNames.serverName(packet.message().ident()));
          handlePacket(packet);
        }
      }
    } finally {
      if (game != null) game.setReadTimeout(LOGIN_READ_TIMEOUT);
    }
  }

  private void handleAck(boolean accepted) {
    if (accepted && pendingMoveTarget != null) {
      position = pendingMoveTarget;
      direction = pendingMoveHeading;
    }
    if (accepted && pendingTurnDirection != null) {
      direction = pendingTurnDirection;
    }
    pendingMoveTarget = null;
    pendingMoveHeading = null;
    pendingTurnDirection = null;
  }

  private Direction pendingTurnDirection;

  // ------------------------------------------------------------ server packets

  private void handlePacket(WirePacket packet) {
    DefaultMessage message = packet.message();
    switch (message.ident()) {
      case ProtocolConstants.SM_NEWMAP -> {
        selfId = message.recog();
        position = new Position(message.param(), message.tag());
        mapId = WireMessageCodec.decodeBody(packet.encodedBody());
        bag.clear();
        worn.clear();
        // A fresh map wipes the client's actor list (ClMain.pas clears the scene), so the
        // census starts empty and is rebuilt from the SM_TURN storm that follows.
        neighbours.clear();
      }
      // The appearance/movement family (ObjBase.pas:5296/5316/5446): recog=actor,
      // param/tag=cell, series=MakeWord(direction, light). Everything except the player's
      // own actor is a neighbour whose cell and facing the AI comparison watches.
      case ProtocolConstants.SM_TURN, ProtocolConstants.SM_WALK,
          ProtocolConstants.SM_RUN, ProtocolConstants.SM_BACKSTEP -> {
        if (message.recog() != selfId) {
          neighbours.put(message.recog(), String.format(Locale.ROOT, "(%d,%d) dir=%d",
              message.param(), message.tag(), message.series() & 0x7));
        }
      }
      case ProtocolConstants.SM_DISAPPEAR -> neighbours.remove(message.recog());
      // The @tick pump answers through SM_SYSMESSAGE with "@tick N -> T ticks, now=M".
      // Capturing M makes "both worlds ran the same number of ticks" a compared fact.
      case ProtocolConstants.SM_SYSMESSAGE -> {
        String body = WireMessageCodec.decodeBody(packet.encodedBody());
        int marker = body.indexOf("now=");
        if (body.startsWith("@tick ") && marker >= 0) {
          try {
            worldTime = Long.parseLong(body.substring(marker + 4).strip());
          } catch (NumberFormatException ignored) {
            // Leave the previous value; the differ will surface the drift either way.
          }
        }
      }
      // A corpse stays on the map but stops acting; MakeGhost later removes it via
      // SM_DISAPPEAR. Recording the death cell keeps the census honest in between.
      case ProtocolConstants.SM_CLEAROBJECTS -> neighbours.clear();
      case ProtocolConstants.SM_LOGON -> {
        if (message.recog() == selfId) {
          position = new Position(message.param(), message.tag());
          direction = Direction.fromCode(message.series() & 0x7);
        }
      }
      case ProtocolConstants.SM_CHANGEMAP -> {
        if (message.recog() == selfId) {
          position = new Position(message.param(), message.tag());
          mapId = WireMessageCodec.decodeBody(packet.encodedBody());
        }
      }
      case ProtocolConstants.SM_HEALTHSPELLCHANGED -> {
        if (message.recog() == selfId) {
          hp = message.param();
          mp = message.tag();
          maxHp = message.series();
        }
      }
      case ProtocolConstants.SM_ABILITY -> {
        gold = Integer.toUnsignedLong(message.recog());
        applyAbility(packet.encodedBody());
      }
      case ProtocolConstants.SM_GOLDCHANGED -> gold = Integer.toUnsignedLong(message.recog());
      case ProtocolConstants.SM_LEVELUP -> {
        level = message.param();
        experience = Integer.toUnsignedLong(message.tag());
      }
      // SM_STRUCK: recog=victim, param=HP, tag=MaxHP, series=damage. The victim id is
      // server-local, so it is reduced to self/other; the damage and the resulting pools
      // are exactly what the seeded damage stream must reproduce on both servers.
      case ProtocolConstants.SM_STRUCK -> {
        boolean self = message.recog() == selfId;
        combat.add("struck " + (self ? "self" : "other")
            + " dmg=" + message.series() + " hp=" + message.param() + "/" + message.tag());
        if (self) {
          hp = message.param();
          maxHp = message.tag();
        }
      }
      // SM_DEATH: recog=victim, param/tag=cell. Reported without the id for the same reason.
      case ProtocolConstants.SM_DEATH -> combat.add("death "
          + (message.recog() == selfId ? "self" : "other")
          + " at=(" + message.param() + "," + message.tag() + ")");
      // SM_WINEXP: recog=total experience, param/tag=low/high word of the gained amount.
      case ProtocolConstants.SM_WINEXP -> {
        experience = Integer.toUnsignedLong(message.recog());
        long gained = (Integer.toUnsignedLong(message.tag()) << 16)
            | Integer.toUnsignedLong(message.param());
        combat.add("exp +" + gained + " total=" + experience);
      }
      case ProtocolConstants.SM_BAGITEMS -> {
        bag.clear();
        for (BackpackItem item : decodeItemBlocks(packet.encodedBody())) {
          bag.put(item.makeIndex(), item);
        }
      }
      case ProtocolConstants.SM_ADDITEM -> {
        BackpackItem item = ClientItemCodec.decode(packet.encodedBody());
        bag.put(item.makeIndex(), item);
      }
      case ProtocolConstants.SM_DELITEMS -> {
        String[] fields = WireMessageCodec.decodeBody(packet.encodedBody()).split("/", -1);
        for (int index = 0; index + 1 < fields.length; index += 2) {
          try {
            int makeIndex = Integer.parseInt(fields[index + 1]);
            bag.remove(makeIndex);
            worn.values().removeIf(item -> item.makeIndex() == makeIndex);
          } catch (NumberFormatException ignored) {
            // A malformed pair ends the well-formed prefix; Delphi tolerates this too.
          }
        }
      }
      case ProtocolConstants.SM_DROPITEM_SUCCESS -> bag.remove(message.recog());
      case ProtocolConstants.SM_EAT_OK -> {
        // ClientUseItems consumed the pending item; ClMain removes it by the echoed
        // request. The engine's SM_EAT_OK carries no identifiers, so the session re-queries
        // on the next `bag` op instead of guessing here.
      }
      case ProtocolConstants.SM_TAKEON_OK -> {
        if (pendingTakeOnMakeIndex != null && pendingTakeOnSlot != null) {
          BackpackItem item = bag.remove(pendingTakeOnMakeIndex);
          if (item != null) worn.put(pendingTakeOnSlot, item);
        }
        pendingTakeOnMakeIndex = null;
        pendingTakeOnSlot = null;
      }
      case ProtocolConstants.SM_TAKEON_FAIL -> {
        pendingTakeOnMakeIndex = null;
        pendingTakeOnSlot = null;
      }
      case ProtocolConstants.SM_TAKEOFF_OK -> {
        if (pendingTakeOffSlot != null && pendingTakeOffSlot >= 0) {
          worn.remove(pendingTakeOffSlot);
          // The freed item re-enters the bag via the SM_ADDITEM that follows.
        }
        pendingTakeOffSlot = null;
      }
      case ProtocolConstants.SM_SENDUSEITEMS -> {
        worn.clear();
        String body = packet.encodedBody();
        // slot '/' encodedItem '/' repeated (ObjBase.pas:16898).
        String[] parts = body.split("/", -1);
        for (int index = 0; index + 1 < parts.length; index += 2) {
          if (parts[index].isEmpty()) continue;
          try {
            worn.put(Integer.parseInt(parts[index]), ClientItemCodec.decode(parts[index + 1]));
          } catch (RuntimeException ignored) {
            // Tolerate blocks this codec revision cannot parse; the diff will surface it.
          }
        }
      }
      default -> {
        // Observed and reported by ident name; no state to track.
      }
    }
  }

  /** Decodes the 50-byte packed TAbility body (AbilityCodec's exact layout). */
  private void applyAbility(String encodedBody) {
    byte[] bytes;
    try {
      bytes = com.mir2.protocol.SixBitCodec.decodeString(encodedBody);
    } catch (RuntimeException malformed) {
      return;
    }
    if (bytes.length < 50) return;
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    level = buffer.getShort() & 0xffff;
    buffer.getInt(); // AC
    buffer.getInt(); // MAC
    buffer.getInt(); // DC
    buffer.getInt(); // MC
    buffer.getInt(); // SC
    hp = buffer.getShort() & 0xffff;
    mp = buffer.getShort() & 0xffff;
    maxHp = buffer.getShort() & 0xffff;
    maxMp = buffer.getShort() & 0xffff;
    experience = Integer.toUnsignedLong(buffer.getInt());
  }

  private static List<BackpackItem> decodeItemBlocks(String encodedBody) {
    List<BackpackItem> items = new ArrayList<>();
    for (String block : encodedBody.split("/", -1)) {
      if (block.isEmpty()) continue;
      try {
        items.add(ClientItemCodec.decode(block));
      } catch (RuntimeException ignored) {
        // Unknown block shape: skipped here, surfaced by the item-list diff.
      }
    }
    return items;
  }

  // ------------------------------------------------------------ helpers

  private BackpackItem findInBag(String itemName) {
    for (BackpackItem item : bag.values()) {
      if (item.name().equalsIgnoreCase(itemName)) return item;
    }
    return null;
  }

  private Integer findWornSlot(String itemName) {
    for (Map.Entry<Integer, BackpackItem> entry : worn.entrySet()) {
      if (entry.getValue().name().equalsIgnoreCase(itemName)) return entry.getKey();
    }
    return null;
  }

  /** First accepting slot, preferring an empty one — mirroring the client's slot choice. */
  private int chooseSlot(BackpackItem item) {
    Integer firstAccepting = null;
    for (EquipmentSlot slot : EquipmentSlot.values()) {
      if (!slot.accepts(item.item())) continue;
      if (firstAccepting == null) firstAccepting = slot.index();
      if (!worn.containsKey(slot.index())) return slot.index();
    }
    return firstAccepting != null ? firstAccepting : 0;
  }

  /**
   * Stable, MakeIndex-free item lines: {@code name dura/duraMax}, sorted. Item identity on
   * both servers is the template plus the wear state; instance numbering is server-local.
   */
  private static List<String> itemLines(Map<Integer, BackpackItem> items) {
    return items.values().stream()
        .map(item -> String.format(Locale.ROOT, "%s %d/%d",
            item.name(), item.dura(), item.duraMax()))
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  private static OpObservation relabel(OpObservation observation, String op) {
    return new OpObservation(op, observation.acks(), observation.messages(),
        observation.state());
  }

  private void closeGame() {
    if (game != null) {
      game.close();
      game = null;
    }
  }

  private static WirePacket expect(ShadowWireClient client, String phase, int... expectedIdents)
      throws IOException {
    byte[] frame = client.readFrame();
    if (frame == null) throw new IOException(phase + ": connection closed");
    if (ShadowWireClient.isStatusFrame(frame)) throw new IOException(phase + ": unexpected ack");
    WirePacket packet = ShadowWireClient.parsePacket(frame);
    for (int ident : expectedIdents) {
      if (packet.message().ident() == ident) return packet;
    }
    throw new IOException(phase + ": unexpected ident " + packet.message().ident());
  }

  private static DefaultMessage message(int ident, int recog, int param, int tag, int series) {
    return new DefaultMessage(recog, ident, param, tag, series);
  }

  /** Packs a cell the way ClMain.pas does: {@code (y shl 16) or x}. */
  private static int packed(Position cell) {
    return (cell.y() << 16) | cell.x();
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(Math.max(1, duration.toMillis()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Retryable enter failure (fast-relog leave race), mirroring the bot's EnterRetry. */
  private static final class EnterRetry extends RuntimeException {
    EnterRetry(String reason) {
      super(reason, null, false, false);
    }
  }
}
