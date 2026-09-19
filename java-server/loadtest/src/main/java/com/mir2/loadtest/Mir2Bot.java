package com.mir2.loadtest;

import com.mir2.gate.CharacterDescription;
import com.mir2.gate.WireMessageCodec;
import com.mir2.gate.WirePacket;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.Direction;
import com.mir2.world.Position;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;

/**
 * One scripted {@code mir2.exe} stand-in that drives the full three-gate flow over real TCP:
 * login, server select, character create/select, RunLogin, movement, melee, pickup and
 * voluntary relogs.
 *
 * <p>The behaviour is deliberately simple: chase and hit monster-looking objects, pick up
 * visible ground items, flee when badly wounded, wander around the spawn area, and relog
 * every {@code relogEvery} to exercise the SQLite restore path. Objects whose TCharDesc
 * feature looks like a human appearance (nonzero bits above 24, as {@code MakeHumanFeature}
 * always packs the dress shape there) or matches the bot's own look are never attacked, so
 * the swarm stays peaceful towards players and itself.
 */
final class Mir2Bot implements Runnable {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration ACK_TIMEOUT = Duration.ofSeconds(4);
  private static final int LOGIN_ATTEMPTS = 3;
  private static final int ENTER_ATTEMPTS = 12;
  private static final Duration ENTER_RETRY_DELAY = Duration.ofMillis(300);
  private static final long ATTACK_COOLDOWN_MILLIS = 1_100;
  private static final int MAX_CONSECUTIVE_FAILURES = 25;
  private static final int ANCHOR_BOX = 15;
  /** Delphi client constants echoed by ClMain.pas SendRunLogin. */
  private static final int CLIENT_VERSION = 120040918;
  private static final int LOGIN_CODE = 9;

  private final BotSwarm.Spec spec;
  private final int index;
  private final BotMetrics metrics;
  private final Random random;
  private final IntConsumer inWorldDelta;
  private final IntConsumer finished;
  private final String account;
  private final String characterName;

  // --- session state, owned by the bot's main thread; reader only completes pendingAck ---
  private BotWireClient game;
  private Thread readerThread;
  private volatile boolean closing;
  private volatile boolean stopped;
  private volatile CompletableFuture<Boolean> pendingAck;
  private boolean enteredOnce;

  // --- world state, guarded by stateLock ---
  private final Object stateLock = new Object();
  private Position position = new Position(0, 0);
  private Position anchor;
  private Direction direction = Direction.DOWN;
  private int selfId = -1;
  private int selfFeature = -1;
  private int hp = -1;
  private int maxHp = -1;
  private final Map<Integer, TrackedObject> objects = new HashMap<>();
  private final Map<Integer, Position> groundItems = new HashMap<>();
  private long lastAttackNanos;
  private int consecutiveFailures;

  Mir2Bot(BotSwarm.Spec spec, int index, BotMetrics metrics, Random random,
      IntConsumer inWorldDelta, IntConsumer finished) {
    this.spec = spec;
    this.index = index;
    this.metrics = metrics;
    this.random = random;
    this.inWorldDelta = inWorldDelta;
    this.finished = finished;
    this.account = spec.botAccount(index);
    this.characterName = account;
  }

  String account() {
    return account;
  }

  /** Asks the bot to wind down; its current session is closed without counting errors. */
  void requestStop() {
    stopped = true;
  }

  @Override
  public void run() {
    try {
      long runDeadline = System.nanoTime() + spec.duration().toNanos();
      while (System.nanoTime() < runDeadline && !stopped) {
        try {
          enterWorld(runDeadline);
        } catch (SessionFailure fatal) {
          metrics.count(fatal.key());
          sleepQuiet(Duration.ofSeconds(1).plusMillis(random.nextInt(500)));
          continue;
        }

        long sessionDeadline =
            Math.min(runDeadline, System.nanoTime() + spec.relogEvery().toNanos());
        SessionEnd end;
        try {
          end = playUntil(sessionDeadline);
        } finally {
          closeSession();
          metrics.count(BotMetrics.Key.SESSIONS_ENDED);
        }
        switch (end) {
          case RELOG_DUE -> {
            // Only count a relog when another session actually follows; the final
            // session of the run simply ends on schedule.
            if (System.nanoTime() < runDeadline && !stopped) {
              metrics.count(BotMetrics.Key.RELOGS_COMPLETED);
            }
          }
          case DEAD -> {
            metrics.count(BotMetrics.Key.PLAYERS_DIED);
            return;
          }
          case STOPPED -> {
            return;
          }
          case BROKEN -> {
            // Errors were already counted where they happened; reconnect and carry on.
          }
        }
      }
    } catch (Stop ignored) {
      // Swarm shutdown; nothing to record.
    } finally {
      finished.accept(index);
    }
  }

  // ------------------------------------------------------------ login flow

  /** Runs login → select → RunLogin and leaves a live reader thread behind. */
  private void enterWorld(long runDeadline) throws Stop, SessionFailure {
    LoginRoute route = loginUntilCertified();
    String gameEndpoint = selectCharacterUntilPlaying(route);

    IOException lastFailure = null;
    for (int attempt = 0; attempt < ENTER_ATTEMPTS; attempt++) {
      if (stopped || System.nanoTime() >= runDeadline) throw new Stop();
      try {
        openGameSession(gameEndpoint, route.certification());
        return;
      } catch (IOException | EnterRetry error) {
        if (error instanceof IOException ioException) lastFailure = ioException;
        metrics.count(BotMetrics.Key.ENTER_RETRIES);
        sleepQuiet(ENTER_RETRY_DELAY);
      }
    }
    metrics.count(BotMetrics.Key.ERR_ENTER);
    throw new SessionFailure(BotMetrics.Key.ERR_ENTER, lastFailure);
  }

  private LoginRoute loginUntilCertified() throws Stop, SessionFailure {
    for (int attempt = 0; attempt < LOGIN_ATTEMPTS; attempt++) {
      if (stopped) throw new Stop();
      try {
        return loginOnce();
      } catch (IOException | ProtocolMismatch error) {
        sleepQuiet(Duration.ofSeconds(1));
      }
    }
    metrics.count(BotMetrics.Key.ERR_LOGIN);
    throw new SessionFailure(BotMetrics.Key.ERR_LOGIN);
  }

  /** LOGIN gate: {@code CM_PROTOCOL → CM_IDPASSWORD → CM_SELECTSERVER}. */
  private LoginRoute loginOnce() throws IOException, ProtocolMismatch {
    try (BotWireClient login = BotWireClient.connect(spec.host(), spec.loginPort(),
        CONNECT_TIMEOUT)) {
      login.setReadTimeout(READ_TIMEOUT);
      login.sendPacket(message(ProtocolConstants.CM_PROTOCOL, 0, 0, 0, 0), "");
      expect(login, "certification", ProtocolConstants.SM_CERTIFICATION_SUCCESS);

      login.sendPacket(message(ProtocolConstants.CM_IDPASSWORD, 0, 0, 0, 0),
          account + "/" + spec.accountPassword());
      WirePacket passOk = expect(login, "login",
          ProtocolConstants.SM_PASSOK_SELECTSERVER, ProtocolConstants.SM_PASSWD_FAIL);
      if (passOk.message().ident() == ProtocolConstants.SM_PASSWD_FAIL)
        throw new ProtocolMismatch("credentials rejected");

      login.sendPacket(message(ProtocolConstants.CM_SELECTSERVER, 0, 0, 0, 0),
          spec.serverName());
      WirePacket route = expect(login, "server select",
          ProtocolConstants.SM_SELECTSERVER_OK, ProtocolConstants.SM_STARTFAIL);
      if (route.message().ident() == ProtocolConstants.SM_STARTFAIL)
        throw new ProtocolMismatch("server select refused");
      // body = advertisedHost/selectPort/certification; --select-port overrides the port.
      String[] fields = WireMessageCodec.decodeBody(route.encodedBody()).split("/", -1);
      if (fields.length != 3) throw new ProtocolMismatch("malformed select-server route");
      int certification = Integer.parseInt(fields[2]);
      String endpoint = spec.selectPortOverride() > 0
          ? spec.host() + "/" + spec.selectPortOverride() : fields[0] + "/" + fields[1];
      return new LoginRoute(certification, endpoint);
    }
  }

  private record LoginRoute(int certification, String selectEndpoint) {}

  private String selectCharacterUntilPlaying(LoginRoute route) throws Stop, SessionFailure {
    for (int attempt = 0; attempt < LOGIN_ATTEMPTS; attempt++) {
      if (stopped) throw new Stop();
      try {
        return selectCharacterOnce(route);
      } catch (IOException | ProtocolMismatch error) {
        sleepQuiet(Duration.ofSeconds(1));
      }
    }
    metrics.count(BotMetrics.Key.ERR_LOGIN);
    throw new SessionFailure(BotMetrics.Key.ERR_LOGIN);
  }

  /** SELECT gate: {@code CM_QUERYCHR → CM_NEWCHR (once) → CM_SELCHR}. */
  private String selectCharacterOnce(LoginRoute route)
      throws IOException, ProtocolMismatch {
    String[] endpoint = route.selectEndpoint().split("/", -1);
    try (BotWireClient select = BotWireClient.connect(endpoint[0],
        Integer.parseInt(endpoint[1]), CONNECT_TIMEOUT)) {
      select.setReadTimeout(READ_TIMEOUT);
      select.sendPacket(message(ProtocolConstants.CM_QUERYCHR, 0, 0, 0, 0),
          account + "/" + route.certification());
      WirePacket list = expect(select, "character list",
          ProtocolConstants.SM_QUERYCHR, ProtocolConstants.SM_OUTOFCONNECTION);
      if (list.message().ident() == ProtocolConstants.SM_OUTOFCONNECTION)
        throw new ProtocolMismatch("character list refused");
      if (!listedCharacters(list).contains(characterName)) {
        select.sendPacket(message(ProtocolConstants.CM_NEWCHR, 0, 0, 0, 0),
            account + "/" + characterName + "/2/0/1");
        WirePacket created = expect(select, "character create",
            ProtocolConstants.SM_NEWCHR_SUCCESS, ProtocolConstants.SM_NEWCHR_FAIL);
        if (created.message().ident() == ProtocolConstants.SM_NEWCHR_FAIL)
          throw new ProtocolMismatch("character create refused");
      }

      select.sendPacket(message(ProtocolConstants.CM_SELCHR, 0, 0, 0, 0),
          account + "/" + characterName);
      WirePacket play = expect(select, "character select",
          ProtocolConstants.SM_STARTPLAY, ProtocolConstants.SM_OUTOFCONNECTION);
      if (play.message().ident() == ProtocolConstants.SM_OUTOFCONNECTION)
        throw new ProtocolMismatch("character select refused");
      // body = advertisedHost/gamePort; --game-port overrides the port.
      String[] fields = WireMessageCodec.decodeBody(play.encodedBody()).split("/", -1);
      if (fields.length != 2) throw new ProtocolMismatch("malformed start-play route");
      return spec.gamePortOverride() > 0
          ? spec.host() + "/" + spec.gamePortOverride() : fields[0] + "/" + fields[1];
    }
  }

  private static List<String> listedCharacters(WirePacket list) {
    // body = name/job/hair/level/gender/ per character
    String[] fields = WireMessageCodec.decodeBody(list.encodedBody()).split("/", -1);
    List<String> names = new ArrayList<>();
    for (int index = 0; index + 4 < fields.length; index += 5) names.add(fields[index]);
    return names;
  }

  // ------------------------------------------------------------ game session

  private void openGameSession(String endpoint, int certification)
      throws IOException, EnterRetry {
    String[] fields = endpoint.split("/", -1);
    BotWireClient client = BotWireClient.connect(fields[0], Integer.parseInt(fields[1]),
        CONNECT_TIMEOUT);
    try {
      client.setReadTimeout(READ_TIMEOUT);
      client.sendRunLogin(account, characterName, certification, CLIENT_VERSION, LOGIN_CODE);

      WirePacket newMap = readGamePacket(client);
      if (newMap == null || newMap.message().ident() != ProtocolConstants.SM_NEWMAP)
        throw new EnterRetry("no SM_NEWMAP");
      handlePacket(newMap);

      WirePacket logon = readGamePacket(client);
      if (logon == null || logon.message().ident() != ProtocolConstants.SM_LOGON)
        throw new EnterRetry("no SM_LOGON");
      handlePacket(logon);

      WirePacket description = readGamePacket(client);
      if (description == null || description.message().ident()
          != ProtocolConstants.SM_MAPDESCRIPTION) {
        throw new EnterRetry("no SM_MAPDESCRIPTION");
      }
      handlePacket(description);
    } catch (EnterRetry | IOException error) {
      client.close();
      throw error;
    }

    this.game = client;
    this.closing = false;
    this.pendingAck = null;
    client.setReadTimeout(Duration.ZERO);
    startReader(client);
    if (!enteredOnce) {
      enteredOnce = true;
      metrics.count(BotMetrics.Key.BOTS_ENTERED);
    }
    metrics.count(BotMetrics.Key.GAME_ENTRIES);
    inWorldDelta.accept(1);

    // The real client queries the bag right after SM_LOGON (ClMain.pas:3860). If this
    // final send fails the session is half-open, so unwind it before the caller retries.
    try {
      game.sendPacket(message(ProtocolConstants.CM_QUERYBAGITEMS, 0, 0, 0, 0), "");
      metrics.count(BotMetrics.Key.BAG_QUERIES_SENT);
    } catch (IOException error) {
      closeSession();
      throw error;
    }
  }

  private WirePacket readGamePacket(BotWireClient client) throws IOException {
    byte[] frame = client.readFrame();
    if (frame == null) return null;
    if (BotWireClient.isStatusFrame(frame)) throw new IOException("unexpected ack during enter");
    return BotWireClient.parsePacket(frame);
  }

  private void startReader(BotWireClient client) {
    readerThread = Thread.ofVirtual().name("bot-" + account + "-reader").start(() -> {
      try {
        while (true) {
          byte[] frame = client.readFrame();
          if (frame == null) {
            if (!closing) {
              metrics.count(BotMetrics.Key.UNEXPECTED_DISCONNECTS);
              failPendingAck(BotMetrics.Key.UNEXPECTED_DISCONNECTS);
            }
            return;
          }
          if (BotWireClient.isStatusFrame(frame)) completePendingAck(frame);
          else handlePacket(BotWireClient.parsePacket(frame));
        }
      } catch (IOException error) {
        if (!closing) {
          metrics.count(BotMetrics.Key.SOCKET_ERRORS);
          failPendingAck(BotMetrics.Key.SOCKET_ERRORS);
        }
      }
    });
  }

  private void completePendingAck(byte[] statusFrame) {
    CompletableFuture<Boolean> ack = pendingAck;
    if (ack == null) {
      metrics.count(BotMetrics.Key.UNMATCHED_ACKS);
      return;
    }
    pendingAck = null;
    ack.complete(BotWireClient.statusAccepted(statusFrame));
  }

  private void failPendingAck(BotMetrics.Key reason) {
    CompletableFuture<Boolean> ack = pendingAck;
    if (ack == null) return;
    pendingAck = null;
    ack.completeExceptionally(new SessionFailure(reason));
  }

  private void closeSession() {
    BotWireClient client = game;
    game = null;
    closing = true;
    if (client != null) {
      client.close();
      inWorldDelta.accept(-1);
    }
    Thread reader = readerThread;
    readerThread = null;
    if (reader != null) {
      try {
        reader.join(Duration.ofSeconds(2));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    CompletableFuture<Boolean> ack = pendingAck;
    if (ack != null) {
      pendingAck = null;
      ack.cancel(false);
    }
  }

  // ------------------------------------------------------------ behaviour

  private enum SessionEnd {RELOG_DUE, STOPPED, DEAD, BROKEN}

  private SessionEnd playUntil(long sessionDeadline) {
    while (true) {
      if (stopped || Thread.currentThread().isInterrupted()) return SessionEnd.STOPPED;
      if (System.nanoTime() >= sessionDeadline) return SessionEnd.RELOG_DUE;
      synchronized (stateLock) {
        if (hp == 0) return SessionEnd.DEAD;
      }
      try {
        act();
      } catch (Stop stop) {
        return SessionEnd.STOPPED;
      } catch (SessionFailure failure) {
        return SessionEnd.BROKEN;
      }
      sleepQuiet(nextThinkTime());
    }
  }

  private void act() throws Stop, SessionFailure {
    Decision decision;
    synchronized (stateLock) {
      decision = decideLocked();
    }
    switch (decision.kind()) {
      case TURN -> turn(decision.direction());
      case MOVE -> move(decision.direction(), decision.steps());
      case HIT -> hit(decision.direction());
      case PICKUP -> pickup();
    }
  }

  private record Decision(Kind kind, Direction direction, int steps) {
    static Decision turn(Direction direction) {
      return new Decision(Kind.TURN, direction, 0);
    }

    static Decision move(Direction direction, int steps) {
      return new Decision(Kind.MOVE, direction, steps);
    }

    static Decision hit(Direction direction) {
      return new Decision(Kind.HIT, direction, 0);
    }

    static Decision pickup() {
      return new Decision(Kind.PICKUP, null, 0);
    }

    enum Kind {TURN, MOVE, HIT, PICKUP}
  }

  private Decision decideLocked() {
    // 1. A ground item on our own cell always wins.
    for (Position item : groundItems.values()) {
      if (item.equals(position)) return Decision.pickup();
    }

    // 2. Badly wounded and threatened: run away from the nearest monster.
    TrackedObject threat = nearestMonster(3);
    if (threat != null && maxHp > 0 && hp >= 0 && hp * 100 < maxHp * 35) {
      return Decision.move(towardOrRandom(threat.position(), position), 2);
    }

    // 3. Monster in melee range: hit it (or face it while the cooldown runs).
    if (threat != null && distance(threat.position()) <= 1) {
      Direction facing = towardOrRandom(position, threat.position());
      if (System.nanoTime() - lastAttackNanos >= ATTACK_COOLDOWN_MILLIS * 1_000_000L) {
        return Decision.hit(facing);
      }
      return Decision.turn(facing);
    }

    // 4. Then chase nearby loot.
    Position item = nearestGroundItem(3);
    if (item != null) return Decision.move(towardOrRandom(position, item), 1);

    // 5. Then chase the nearest monster within patrol range.
    TrackedObject target = nearestMonster(10);
    if (target != null) {
      int distance = distance(target.position());
      return Decision.move(towardOrRandom(position, target.position()), distance >= 5 ? 2 : 1);
    }

    // 6. Otherwise wander around the spawn anchor so mutual visibility stays dense.
    Direction heading;
    if (anchor != null && distance(anchor) >= ANCHOR_BOX) {
      heading = towardOrRandom(position, anchor);
    } else {
      heading = Direction.values()[random.nextInt(Direction.values().length)];
    }
    int roll = random.nextInt(100);
    if (roll < 12) return Decision.turn(heading);
    if (roll < 72) return Decision.move(heading, 1);
    return Decision.move(heading, 2);
  }

  private TrackedObject nearestMonster(int range) {
    TrackedObject best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (TrackedObject object : objects.values()) {
      if (!looksLikeMonster(object.feature())) continue;
      int distance = distance(object.position());
      if (distance <= range && distance < bestDistance) {
        best = object;
        bestDistance = distance;
      }
    }
    return best;
  }

  private Position nearestGroundItem(int range) {
    Position best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (Position item : groundItems.values()) {
      int distance = distance(item);
      if (distance <= range && distance < bestDistance) {
        best = item;
        bestDistance = distance;
      }
    }
    return best;
  }

  private int distance(Position other) {
    return Math.max(Math.abs(other.x() - position.x()), Math.abs(other.y() - position.y()));
  }

  /**
   * Monster features only use the low 24 bits ({@code MakeMonsterFeature}), while human
   * appearances always pack the dress shape into bits 24+. Fellow swarm bots (identical
   * CM_NEWCHR looks) are spared as well, so the swarm never fights players or itself.
   */
  private boolean looksLikeMonster(int feature) {
    if (feature < 0 || feature == selfFeature) return false;
    return (feature >>> 24) == 0;
  }

  // ------------------------------------------------------------ actions

  private void turn(Direction facing) throws Stop, SessionFailure {
    Position claimed;
    synchronized (stateLock) {
      claimed = position;
    }
    perform(BotMetrics.ActionKind.TURN,
        message(ProtocolConstants.CM_TURN, packed(claimed), 0, facing.code(), 0));
  }

  private void move(Direction heading, int steps) throws Stop, SessionFailure {
    Position target;
    synchronized (stateLock) {
      target = position.translate(heading, steps);
      if (!inUnsignedBounds(target) && anchor != null) {
        // A step outside the 16-bit cell space is impossible; walk back towards the anchor.
        heading = towardOrRandom(position, anchor);
        steps = 1;
        target = position.translate(heading, 1);
      }
    }
    if (!inUnsignedBounds(target)) {
      turn(heading);
      return;
    }
    int ident = steps >= 2 ? ProtocolConstants.CM_RUN : ProtocolConstants.CM_WALK;
    BotMetrics.ActionKind kind =
        steps >= 2 ? BotMetrics.ActionKind.RUN : BotMetrics.ActionKind.WALK;
    if (perform(kind, message(ident, packed(target), 0, heading.code(), 0))) {
      // The server only sends +GOOD after the move actually landed on the target cell.
      synchronized (stateLock) {
        position = target;
        direction = heading;
      }
    }
  }

  private static boolean inUnsignedBounds(Position cell) {
    return cell.x() >= 0 && cell.y() >= 0 && cell.x() <= 0xffff && cell.y() <= 0xffff;
  }

  /** {@link Direction#toward} that tolerates coinciding cells instead of throwing. */
  private Direction towardOrRandom(Position source, Position target) {
    if (source.equals(target))
      return Direction.values()[random.nextInt(Direction.values().length)];
    return Direction.toward(source, target);
  }

  private void hit(Direction heading) throws Stop, SessionFailure {
    int ident = switch (random.nextInt(10)) {
      case 8 -> ProtocolConstants.CM_HEAVYHIT;
      case 9 -> ProtocolConstants.CM_BIGHIT;
      default -> ProtocolConstants.CM_HIT;
    };
    Position claimed;
    synchronized (stateLock) {
      claimed = position;
    }
    if (perform(BotMetrics.ActionKind.HIT,
        message(ident, packed(claimed), 0, heading.code(), 0))) {
      synchronized (stateLock) {
        lastAttackNanos = System.nanoTime();
      }
    }
  }

  private void pickup() throws Stop, SessionFailure {
    Position cell;
    synchronized (stateLock) {
      cell = position;
    }
    perform(BotMetrics.ActionKind.PICKUP,
        message(ProtocolConstants.CM_PICKUP, 0, cell.x(), cell.y(), 0));
  }

  /**
   * Sends one action and waits for its {@code +GOOD/+FAIL} acknowledgement. Any accepted
   * action proves the position belief is still in sync; a long enough run of failures means
   * it has drifted and only a fresh SM_NEWMAP can recover it.
   */
  private boolean perform(BotMetrics.ActionKind kind, DefaultMessage message)
      throws Stop, SessionFailure {
    CompletableFuture<Boolean> ack = new CompletableFuture<>();
    pendingAck = ack;
    metrics.sent(kind);
    try {
      game.sendPacket(message, "");
    } catch (IOException error) {
      pendingAck = null;
      metrics.count(BotMetrics.Key.SOCKET_ERRORS);
      throw new SessionFailure(BotMetrics.Key.SOCKET_ERRORS, error);
    }
    long started = System.nanoTime();
    try {
      Boolean accepted = ack.get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      metrics.acknowledged(kind, accepted, System.nanoTime() - started);
      synchronized (stateLock) {
        if (accepted) {
          consecutiveFailures = 0;
        } else if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
          metrics.count(BotMetrics.Key.POSITION_RESYNCS);
          throw new SessionFailure(BotMetrics.Key.POSITION_RESYNCS);
        }
      }
      return accepted;
    } catch (TimeoutException timeout) {
      metrics.ackTimeout(kind);
      metrics.count(BotMetrics.Key.ACK_TIMEOUTS);
      throw new SessionFailure(BotMetrics.Key.ACK_TIMEOUTS);
    } catch (ExecutionException execution) {
      Throwable cause = execution.getCause();
      if (cause instanceof SessionFailure failure) throw failure;
      throw new SessionFailure(BotMetrics.Key.SOCKET_ERRORS, cause);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new Stop();
    }
  }

  // ------------------------------------------------------------ server packets

  private void handlePacket(WirePacket packet) {
    int ident = packet.message().ident();
    int recog = packet.message().recog();
    int param = packet.message().param();
    int tag = packet.message().tag();
    int series = packet.message().series();
    metrics.received(ident);
    switch (ident) {
      case ProtocolConstants.SM_NEWMAP -> {
        synchronized (stateLock) {
          selfId = recog;
          position = new Position(param, tag);
          if (anchor == null) anchor = position;
          objects.clear();
          groundItems.clear();
          hp = -1;
          maxHp = -1;
          consecutiveFailures = 0;
        }
      }
      case ProtocolConstants.SM_LOGON -> {
        int feature = decodeFeature(packet);
        if (feature >= 0) {
          synchronized (stateLock) {
            selfFeature = feature;
          }
        }
      }
      case ProtocolConstants.SM_TURN, ProtocolConstants.SM_WALK, ProtocolConstants.SM_RUN -> {
        int feature = decodeFeature(packet);
        synchronized (stateLock) {
          if (recog != selfId) {
            objects.merge(recog, new TrackedObject(new Position(param, tag), feature),
                (existing, next) -> new TrackedObject(next.position(),
                    next.feature() >= 0 ? next.feature() : existing.feature()));
          }
        }
      }
      case ProtocolConstants.SM_HIT, ProtocolConstants.SM_HEAVYHIT, ProtocolConstants.SM_BIGHIT -> {
        synchronized (stateLock) {
          if (recog != selfId) {
            objects.merge(recog, new TrackedObject(new Position(param, tag), -1),
                (existing, next) -> new TrackedObject(next.position(), existing.feature()));
          }
        }
      }
      case ProtocolConstants.SM_DISAPPEAR -> {
        synchronized (stateLock) {
          objects.remove(recog);
        }
      }
      case ProtocolConstants.SM_DEATH -> {
        if (recog == selfId) {
          synchronized (stateLock) {
            hp = 0;
          }
        } else {
          synchronized (stateLock) {
            objects.remove(recog);
          }
          metrics.count(BotMetrics.Key.OBSERVED_DEATHS);
        }
      }
      case ProtocolConstants.SM_HEALTHSPELLCHANGED -> {
        if (recog == selfId) {
          synchronized (stateLock) {
            hp = param;
            maxHp = series;
          }
        }
      }
      case ProtocolConstants.SM_WINEXP -> metrics.count(BotMetrics.Key.EXPERIENCE_UPDATES);
      case ProtocolConstants.SM_ITEMSHOW -> {
        synchronized (stateLock) {
          groundItems.put(recog, new Position(param, tag));
        }
        metrics.count(BotMetrics.Key.GROUND_ITEMS_SEEN);
      }
      case ProtocolConstants.SM_ITEMHIDE -> {
        synchronized (stateLock) {
          groundItems.remove(recog);
        }
      }
      case ProtocolConstants.SM_ADDITEM -> metrics.count(BotMetrics.Key.ITEMS_PICKED_UP);
      case ProtocolConstants.SM_BAGITEMS -> metrics.count(BotMetrics.Key.BAG_LISTS_RECEIVED);
      default -> {
        // Everything else (struck, others' health, map description...) is load, not state.
      }
    }
  }

  private static int decodeFeature(WirePacket packet) {
    try {
      return CharacterDescription.decode(packet.encodedBody()).feature();
    } catch (RuntimeException malformed) {
      return -1;
    }
  }

  // ------------------------------------------------------------ plumbing

  private static DefaultMessage message(int ident, int recog, int param, int tag, int series) {
    return new DefaultMessage(recog, ident, param, tag, series);
  }

  /** Packs a cell the way ClMain.pas sends it: {@code (y shl 16) or x}. */
  private static int packed(Position cell) {
    return (cell.y() << 16) | cell.x();
  }

  private static WirePacket expect(BotWireClient client, String phase, int... expectedIdents)
      throws IOException, ProtocolMismatch {
    byte[] frame = client.readFrame();
    if (frame == null) throw new ProtocolMismatch(phase + ": connection closed");
    if (BotWireClient.isStatusFrame(frame)) throw new ProtocolMismatch(phase + ": unexpected ack");
    WirePacket packet = BotWireClient.parsePacket(frame);
    for (int ident : expectedIdents) {
      if (packet.message().ident() == ident) return packet;
    }
    throw new ProtocolMismatch(phase + ": unexpected ident " + packet.message().ident());
  }

  private Duration nextThinkTime() {
    long min = spec.thinkMin().toMillis();
    long max = Math.max(min, spec.thinkMax().toMillis());
    return Duration.ofMillis(min + (max > min ? random.nextLong(max - min + 1) : 0));
  }

  private void sleepQuiet(Duration duration) throws Stop {
    try {
      Thread.sleep(Math.max(1, duration.toMillis()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new Stop();
    }
  }

  private record TrackedObject(Position position, int feature) {}

  /** Unwinds without counting an error; used for swarm shutdown and phase cancellation. */
  private static final class Stop extends RuntimeException {
    Stop() {
      super(null, null, false, false);
    }
  }

  /** A phase failure that has already been counted and should abort or retry the session. */
  private static final class SessionFailure extends RuntimeException {
    private final BotMetrics.Key key;

    SessionFailure(BotMetrics.Key key) {
      this(key, null);
    }

    SessionFailure(BotMetrics.Key key, Throwable cause) {
      super(key.toString(), cause, false, false);
      this.key = key;
    }

    BotMetrics.Key key() {
      return key;
    }
  }

  /** Signals a retryable game-enter failure, e.g. the leave-race after a fast relog. */
  private static final class EnterRetry extends RuntimeException {
    EnterRetry(String reason) {
      super(reason, null, false, false);
    }
  }

  /** A protocol surprise during the login/select phases; retried, then fatal. */
  private static final class ProtocolMismatch extends RuntimeException {
    ProtocolMismatch(String reason) {
      super(reason, null, false, false);
    }
  }
}
