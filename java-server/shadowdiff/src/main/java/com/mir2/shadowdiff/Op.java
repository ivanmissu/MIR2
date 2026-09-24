package com.mir2.shadowdiff;

import com.mir2.world.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One step of a deterministic shadow-comparison operation stream.
 *
 * <p>The op set deliberately mirrors what the real {@code mir2.exe} client can emit over the
 * wire — turn/walk/run/attack/pickup/bag/say/drop/eat/takeon/takeoff/opendoor plus the W26
 * group protocol — and the two harness-level controls {@code sleep} (fixed pacing so
 * action-interval checks resolve the same way on both servers) and {@code relog} (exercises
 * the persistence round trip). One text line is one op, so the same script file can later be
 * pointed at a Delphi server without recompiling anything.
 *
 * <p>W30 adds the duo prefixes: a line may start with {@code p1 } or {@code p2 } to name the
 * acting session ({@link DuoHarness} boots one session per account). Unprefixed lines keep
 * the solo meaning — the primary session — so every pre-existing script parses unchanged.
 * Duo scripts also support the {@code {p1}} / {@code {p2}} name placeholders that
 * {@code ShadowDiffMain} substitutes with the configured account names, so a fixed script
 * stays valid when the accounts are renamed.
 */
public record Op(Kind kind, Direction direction, String text, long millis, String actor) {

  public enum Kind {
    /** {@code CM_TURN}. */
    TURN,
    /** {@code CM_WALK}, one cell. */
    WALK,
    /** {@code CM_RUN}, two cells. */
    RUN,
    /** {@code CM_HIT}. */
    HIT,
    /** {@code CM_HEAVYHIT}. */
    HEAVYHIT,
    /** {@code CM_BIGHIT}. */
    BIGHIT,
    /** {@code CM_PICKUP} on the current cell. */
    PICKUP,
    /** {@code CM_QUERYBAGITEMS}; silent on an empty bag, so the drain window matters. */
    BAG,
    /** {@code CM_SAY} with the literal text (chat, whisper, shout or @/​/ commands). */
    SAY,
    /** {@code CM_DROPITEM} by item name; resolves the MakeIndex from the tracked bag. */
    DROP,
    /** {@code CM_EAT} by item name; resolves the MakeIndex from the tracked bag. */
    EAT,
    /** {@code CM_TAKEONITEM} by item name (slot comes from the wire echo of the server). */
    TAKEON,
    /** {@code CM_TAKEOFFITEM} by item name. */
    TAKEOFF,
    /**
     * {@code CM_GROUPMODE} (658): param 1 allows group invitations, param 0 refuses them
     * (and leaves the current party). W26.
     */
    GROUPMODE,
    /** {@code CM_CREATEGROUP} (659): body carries the invited player's name. W26. */
    GROUPCREATE,
    /** {@code CM_ADDGROUPMEMBER} (660): body carries the invited player's name. W26. */
    GROUPADD,
    /** {@code CM_DELGROUPMEMBER} (661): body carries the member to remove. W26. */
    GROUPDEL,
    /** Harness pause; keeps action intervals deterministic across both servers. */
    SLEEP,
    /**
     * Advances a MANUAL world clock by N ticks ({@code @tick N} over {@code CM_SAY}).
     * Unlike {@link #SLEEP}, which lets both hosts' wall clocks run and therefore only
     * approximately agrees, this makes world time itself the scripted quantity: both
     * servers run exactly N tick bodies, so a monster's Nth AI decision is reproducible.
     */
    TICK,
    /** Close the GAME socket and run the full login → select → enter chain again. */
    RELOG
  }

  /** The two sessions a duo script can drive, in script-prefix form. */
  public static final String PRIMARY_ACTOR = "p1";
  public static final String PARTNER_ACTOR = "p2";

  public Op {
    if (kind == null) throw new IllegalArgumentException("op kind is required");
  }

  /** Compatibility constructor for callers predating the duo prefix (solo scripts). */
  public Op(Kind kind, Direction direction, String text, long millis) {
    this(kind, direction, text, millis, null);
  }

  static Op of(Kind kind) {
    return new Op(kind, null, null, 0, null);
  }

  static Op directional(Kind kind, Direction direction) {
    return new Op(kind, direction, null, 0, null);
  }

  static Op withText(Kind kind, String text) {
    return new Op(kind, null, text, 0, null);
  }

  static Op sleep(long millis) {
    return new Op(Kind.SLEEP, null, null, millis, null);
  }

  /** {@code tick N}: advance a manual world clock by N ticks. */
  static Op tick(long ticks) {
    if (ticks < 0) throw new IllegalArgumentException("tick count must not be negative");
    return new Op(Kind.TICK, null, null, ticks, null);
  }

  /** True when this op names the partner session ({@code p2}); unprefixed ops are primary. */
  public boolean isPartnerOp() {
    return PARTNER_ACTOR.equals(actor);
  }

  /** Renders the op back to its one-line script form. */
  public String describe() {
    StringBuilder line = new StringBuilder();
    if (actor != null) line.append(actor).append(' ');
    line.append(kind.name().toLowerCase(Locale.ROOT));
    if (direction != null) line.append(' ').append(direction.code());
    if (text != null) line.append(' ').append(text);
    if (kind == Kind.SLEEP || kind == Kind.TICK) line.append(' ').append(millis);
    return line.toString();
  }

  // ------------------------------------------------------------ script parsing

  /**
   * Parses a script: one op per line, {@code #} starts a comment, blank lines ignored.
   * Directions are the Delphi {@code DR_*} codes 0..7. A line may carry a {@code p1}/{@code p2}
   * actor prefix (W30 duo scripts); the prefix is optional for the primary session.
   */
  public static List<Op> parseScript(String script) {
    List<Op> ops = new ArrayList<>();
    int lineNumber = 0;
    for (String rawLine : script.split("\n", -1)) {
      lineNumber++;
      String line = rawLine.strip();
      int comment = line.indexOf('#');
      if (comment >= 0) line = line.substring(0, comment).strip();
      if (line.isEmpty()) continue;
      try {
        ops.add(parseLine(line));
      } catch (RuntimeException error) {
        throw new IllegalArgumentException(
            "script line " + lineNumber + " is invalid: " + rawLine.strip(), error);
      }
    }
    return List.copyOf(ops);
  }

  private static Op parseLine(String line) {
    String actor = null;
    String lower = line.toLowerCase(Locale.ROOT);
    if (lower.startsWith(PRIMARY_ACTOR + " ")) {
      actor = PRIMARY_ACTOR;
      line = line.substring(3).strip();
    } else if (lower.startsWith(PARTNER_ACTOR + " ")) {
      actor = PARTNER_ACTOR;
      line = line.substring(3).strip();
    }
    Op op = parseAction(line);
    return actor == null ? op : new Op(op.kind(), op.direction(), op.text(), op.millis(), actor);
  }

  private static Op parseAction(String line) {
    String[] parts = line.split("\\s+", 2);
    String keyword = parts[0].toLowerCase(Locale.ROOT);
    String argument = parts.length > 1 ? parts[1].strip() : null;
    return switch (keyword) {
      case "turn" -> directional(Kind.TURN, parseDirection(argument));
      case "walk" -> directional(Kind.WALK, parseDirection(argument));
      case "run" -> directional(Kind.RUN, parseDirection(argument));
      case "hit" -> directional(Kind.HIT, parseDirection(argument));
      case "heavyhit" -> directional(Kind.HEAVYHIT, parseDirection(argument));
      case "bighit" -> directional(Kind.BIGHIT, parseDirection(argument));
      case "pickup" -> of(Kind.PICKUP);
      case "bag" -> of(Kind.BAG);
      case "say" -> withText(Kind.SAY, requireText(argument, "say"));
      case "drop" -> withText(Kind.DROP, requireText(argument, "drop"));
      case "eat" -> withText(Kind.EAT, requireText(argument, "eat"));
      case "takeon" -> withText(Kind.TAKEON, requireText(argument, "takeon"));
      case "takeoff" -> withText(Kind.TAKEOFF, requireText(argument, "takeoff"));
      case "groupmode" -> withText(Kind.GROUPMODE, requireGroupMode(argument));
      case "groupcreate" -> withText(Kind.GROUPCREATE, requireText(argument, "groupcreate"));
      case "groupadd" -> withText(Kind.GROUPADD, requireText(argument, "groupadd"));
      case "groupdel" -> withText(Kind.GROUPDEL, requireText(argument, "groupdel"));
      case "sleep" -> sleep(Long.parseLong(requireText(argument, "sleep")));
      case "tick" -> tick(Long.parseLong(requireText(argument, "tick")));
      case "relog" -> of(Kind.RELOG);
      default -> throw new IllegalArgumentException("unknown op: " + keyword);
    };
  }

  private static String requireGroupMode(String argument) {
    String value = requireText(argument, "groupmode");
    if (!value.equals("0") && !value.equals("1"))
      throw new IllegalArgumentException("groupmode requires 0 or 1");
    return value;
  }

  private static Direction parseDirection(String argument) {
    if (argument == null) throw new IllegalArgumentException("direction 0..7 is required");
    return Direction.fromCode(Integer.parseInt(argument));
  }

  private static String requireText(String argument, String keyword) {
    if (argument == null || argument.isEmpty())
      throw new IllegalArgumentException(keyword + " requires an argument");
    return argument;
  }

  /**
   * The built-in deterministic smoke script: an empty-bag query, a fixed patrol square
   * (turns, walks, a run), attacks into empty cells spaced beyond the shared CM_HIT
   * interval, a pickup on a bare cell, chat with the online-count command, a drop of an
   * item the character cannot own yet (deterministic {@code SM_DROPITEM_FAIL}) and one
   * relog that proves the persisted state restores identically on both servers.
   */
  public static List<Op> defaultScript() {
    return parseScript("""
        # --- entry state ---
        bag
        say @who
        # --- patrol square (walk 4 cells, face each heading first) ---
        turn 2
        walk 2
        walk 2
        turn 4
        walk 4
        walk 4
        turn 6
        walk 6
        walk 6
        turn 0
        walk 0
        walk 0
        run 2
        # --- melee into empty cells; sleeps clear the shared CM_HIT interval ---
        hit 2
        sleep 1200
        heavyhit 4
        sleep 1200
        bighit 6
        sleep 1200
        # --- pickup on a bare cell (deterministic +FAIL) ---
        pickup
        # --- item path without any items: deterministic SM_DROPITEM_FAIL ---
        drop 木剑
        say hello-shadow
        # --- persistence round trip ---
        relog
        bag
        walk 4
        turn 0
        """);
  }

  /**
   * The PvE comparison script: walk onto a stationary trainer dummy and hit it repeatedly.
   *
   * <p>This only produces a meaningful verdict when both servers run the same world seed
   * (see {@code WorldRandom}): each blow's damage is drawn from the seeded DAMAGE stream, so
   * two correctly-matched servers must report the identical {@code SM_STRUCK} damage
   * sequence and the identical remaining HP on the dummy. The dummy neither moves nor
   * retaliates, so nothing here depends on the wall clock; the sleeps only clear the shared
   * {@code CM_HIT} action interval (900ms).
   *
   * <p>Assumes the harness booted the world with {@code --monsters N --monster-kind trainer}.
   * {@code Mir2Server.spawnMonsters} rings the spawn cell at radius 2 scanning {@code dx}
   * then {@code dy} from -2 upward, so with spawn (20,20) the first dummies land on the
   * x=18 column — i.e. two cells to the <em>west</em>. The script therefore steps west once
   * to (19,20) and beats on the dummy standing at (18,20).
   */
  public static List<Op> pveScript() {
    return parseScript("""
        # --- close on the dummy ring (spawn 20,20; first dummies sit on the x=18 column) ---
        bag
        turn 6
        walk 6
        # --- melee the dummy now standing due west; sleeps clear the 900ms CM_HIT interval ---
        hit 6
        sleep 1000
        hit 6
        sleep 1000
        hit 6
        sleep 1000
        heavyhit 6
        sleep 1000
        bighit 6
        sleep 1000
        hit 6
        sleep 1000
        # --- state must survive the persistence round trip identically ---
        relog
        bag
        """);
  }

  /**
   * The moving-monster comparison script (W23). Unlike {@link #pveScript()}, whose trainer
   * dummy is inert, this drives live AI: the monsters around the spawn acquire the player,
   * chase and attack.
   *
   * <p>Everything hinges on the {@code tick} ops. Both servers must be booted with a
   * {@link com.mir2.world.WorldClock.Mode#MANUAL} clock, so world time — and therefore every
   * {@code TMonster.Run} interval check, every {@code RegenMonsters} pass, every HP/MP
   * regeneration step — advances only when the script says so. Twenty ticks at the shipped
   * 50 ms interval is one second of world time; a chicken's walk interval is DB-derived and
   * several ticks long, so a chase unfolds over the pumped ticks at exactly the same rate on
   * both sides.
   *
   * <p>The observation surface is the snapshot's {@code near=} census (the cell and facing of
   * every actor in view) plus {@code worldTime=}: identical AI decisions produce identical
   * censuses at identical world times. A single divergent step is a STATE failure.
   */
  public static List<Op> aiScript() {
    return parseScript("""
        # --- entry census: the monster ring must look the same on both servers ---
        bag
        tick 1
        # --- let the AI run: acquisition, chase, first blows ---
        tick 20
        tick 20
        tick 20
        # --- the player acts between pumps; the ack and the AI reaction share the bucket ---
        turn 2
        tick 10
        hit 2
        tick 20
        walk 4
        tick 20
        hit 6
        tick 20
        # --- a long pump exercises regeneration, corpse timers and respawn cadence ---
        tick 60
        # --- state (and the pumped world time) must survive the persistence round trip ---
        relog
        bag
        tick 20
        """);
  }

  /**
   * The W30 interaction regression script: the whole party loop over the real wire with two
   * sessions. Requires {@link DuoHarness} worlds booted with a MANUAL clock and live
   * chickens ({@code --duo party} configures both), because the kills that drive the
   * experience split need a monster that drops meat.
   *
   * <p>Coverage, in order (W27 plan §4):
   * <ol>
   *   <li>the four CM group messages including the {@code -4} refusal when the invitee has
   *       not opened group mode yet (a fresh character refuses invitations —
   *       {@code m_boAllowGroup := False}, ObjBase.pas:1270);</li>
   *   <li>a party kill of chicken #1 next to both members — the 1.2× pool split by level
   *       ({@code SM_WINEXP} to both) and the guaranteed 鸡肉 drop, which p2 walks over and
   *       picks up ({@code SM_ADDITEM});</li>
   *   <li>the share-range boundary: p2 walks east beyond the 12-cell square, so chicken
   *       #2's experience goes to p1 alone at full value;</li>
   *   <li>the leader kicking p2 ({@code SM_GROUPCANCEL} to p2, roster loss on both sides);</li>
   *   <li>a relog per player proving gold/bag/worn/experience all restore identically.</li>
   * </ol>
   *
   * <p>World geometry the script relies on (all deterministic at the default seed): with
   * safe-zone 0 the two chickens take ring cells (19,19) and (21,21) around the spawn
   * (20,20); p2's {@code enterPlayerNear} lands on (19,20). Chicken #1 stays at (19,19)
   * pecking p2 until p1's UP_LEFT (7) swings finish it, and its 鸡肉 drops on that same
   * cell. Chicken #2 chases, follows p2 north when p2 steps onto the drop, and parks at
   * (19,20) when p2 goes east; p1 finishes it with LEFT (6) swings. The 鸡肉 pickup waits
   * out the 5 s (100-tick) corpse timer first, because a corpse keeps blocking its cell;
   * p2 then walks east fourteen times to (33,19), outside the 12-cell share square and out
   * of everyone's view — which is what makes the second kill's full-experience verdict
   * meaningful.
   *
   * <p>Every kill needs 900 ms of world time between blows, i.e. ≥18 pumped ticks; the
   * chicken (walk 1400 ms, attack 3000 ms per the Monster.DB row) moves only on pumped
   * ticks, so both servers replay the identical chase.
   */
  public static List<Op> duoPartyScript() {
    return parseScript("""
        # --- party protocol over the wire: allow, invite (refused first), roster ---
        p1 groupmode 1
        p1 groupcreate {p2}
        p2 groupmode 1
        p1 groupcreate {p2}
        p1 bag
        p2 bag
        tick 20
        # --- chicken #1 stands at (19,19), pecking p2; p1 swings UP_LEFT ---
        p1 hit 7
        tick 20
        p1 hit 7
        tick 20
        p1 hit 7
        tick 20
        p1 hit 7
        tick 20
        p1 hit 7
        tick 40
        p1 bag
        p2 bag
        # --- p2 collects the 鸡肉 the kill dropped (it lies on the corpse cell (19,19),
        #     one step north of p2). The pump first outlives the corpse timer — 5 s =
        #     100 ticks after death — because a corpse keeps blocking its cell. ---
        tick 40
        p2 turn 0
        p2 walk 0
        p2 pickup
        tick 5
        p2 bag
        # --- boundary: p2 leaves the 12-cell share square — fourteen cells east along
        #     y=19 from the pickup cell, past p1's row and out of everyone's view ---
        p2 turn 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        p2 walk 2
        tick 20
        # --- chicken #2 followed p2 north and parked at (19,20) when p2 went east; it
        #     stands idle next to p1 now, so p1 swings LEFT for the solo kill ---
        p1 hit 6
        tick 20
        p1 hit 6
        tick 20
        p1 hit 6
        tick 20
        p1 hit 6
        tick 20
        p1 hit 6
        tick 40
        p1 bag
        p2 bag
        # --- leader kicks p2: SM_GROUPCANCEL to p2, roster gone on both sides ---
        p1 groupdel {p2}
        tick 5
        p1 bag
        p2 bag
        # --- both players persist through a relog ---
        p1 relog
        p1 bag
        p2 relog
        p2 bag
        """);
  }

  /**
   * The W30 death/PK regression script (W27 plan §4, 场景 2+4): p2 murders p1 in cold blood
   * over the wire and both sides of the aftermath are compared.
   *
   * <p>Coverage: the murder itself (every {@code SM_STRUCK} between players sets the
   * aggressor's PK flag and repaints the name via {@code SM_CHANGENAMECOLOR}), the death
   * broadcast, <b>死亡自动退队</b> — {@code handleDeath} runs {@code leaveGroup}, so the
   * party disbands and both members are told — the +100 {@code m_nPkPoint} murder penalty
   * with the classic GBK notices, and the relogin paths: the dead victim stands back up on
   * the restored cell with the classic 14 HP, while the murderer's PK point survives the
   * round trip in {@code character_state}.
   *
   * <p>No monsters: the world is booted with {@code --monsters 0}; the only combat is the
   * scripted PvP. Blows need ≥18 pumped ticks between them (900 ms CM_HIT interval).
   */
  public static List<Op> duoDeathPkScript() {
    return parseScript("""
        # --- the doomed party ---
        p1 groupmode 1
        p2 groupmode 1
        p1 groupcreate {p2}
        p1 bag
        p2 bag
        tick 20
        # --- p2 (spawned at the cell north-west of p1) turns on p1: every blow sets the
        #     aggressor flag and repaints the name ---
        p2 hit 3
        tick 20
        p2 hit 3
        tick 20
        p2 hit 3
        tick 20
        p2 hit 3
        tick 20
        p2 hit 3
        tick 20
        p2 hit 3
        tick 20
        p2 hit 3
        tick 20
        p2 hit 3
        tick 20
        p2 hit 3
        tick 20
        p2 hit 3
        tick 20
        p2 hit 3
        tick 40
        p1 bag
        p2 bag
        # --- the victim stands back up; the murderer's PK point survives the round trip ---
        p1 relog
        p1 bag
        p2 relog
        p2 bag
        """);
  }

  /**
   * The W30 persistence regression script (W27 plan §4, 场景 3): a solo character whose bag
   * and wallet the harness seeded before boot (木剑 / 布衣(男) / 金创药(小量) plus 3000 gold)
   * runs the full item lifecycle over the wire, then relogs and must restore byte-identical
   * state: gold, the worn set and the bag.
   *
   * <p>Coverage: equipping both wearable slots ({@code SM_TAKEON_OK}), a ground-item round
   * trip ({@code CM_DROPITEM} → {@code CM_PICKUP} on the same cell), consuming the potion
   * ({@code CM_EAT} → the bag shrinks on the next refresh), taking the weapon back off —
   * including Delphi's every-success-carries-a-FAIL-packet quirk (ObjBase.pas:17294) — and
   * the relog restore. Runs on a SYSTEM clock with no monsters — nothing here depends on
   * world time.
   *
   * <p>The potion is eaten while the dress is still in the bag, on purpose: a
   * {@code CM_QUERYBAGITEMS} is silent on an <em>empty</em> bag, so the refresh after the
   * eat needs a non-empty bag to be observable.
   */
  public static List<Op> persistenceScript() {
    return parseScript("""
        # --- the seeded entry state: three items plus a wallet ---
        bag
        say @who
        # --- weapon on, then a full ground-item round trip on one cell ---
        takeon 木剑
        drop 金创药(小量)
        pickup
        bag
        # --- consume the potion while the bag still holds the dress ---
        eat 金创药(小量)
        bag
        # --- dress on (the male wearer passes the gender lock), weapon off ---
        takeon 布衣(男)
        bag
        takeoff 木剑
        bag
        # --- the relog must restore gold, worn and bag identically ---
        relog
        bag
        turn 0
        walk 4
        """);
  }

  /**
   * The W30 equipment-lock regression script (W27 plan §4, 场景 5): the harness boots the
   * world with a {@code DisableTakeOffList} naming 木剑 and seeds the character with that
   * very sword. Equipping still works (the Delphi list gates {@code ClientTakeOffItems} and
   * {@code DropUseItems}, never {@code ClientTakeOnItems}), but taking it off must be
   * refused observably: {@code SM_TAKEOFF_FAIL} plus the W26 {@code SM_SYSMESSAGE}
   * 「无法取下物品」 — and the item must stay in the worn set through a relog.
   */
  public static List<Op> lockScript() {
    return parseScript("""
        bag
        takeon 木剑
        bag
        # --- the lock: SM_TAKEOFF_FAIL + SM_SYSMESSAGE 无法取下物品 ---
        takeoff 木剑
        bag
        # --- the lock and the worn slot both survive the round trip ---
        relog
        bag
        takeoff 木剑
        bag
        """);
  }
}
