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
    /** {@code CM_SPELL}: real client spell request, encoded from script operands. */
    SPELL,
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
      case "spell" -> withText(Kind.SPELL, requireSpell(argument));
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

  private static String requireSpell(String argument) {
    String value = requireText(argument, "spell");
    String[] fields = value.split("\\s+");
    if (fields.length < 3 || fields.length > 4)
      throw new IllegalArgumentException("spell requires <magicId> <x> <y> [targetId]");
    for (int index = 0; index < fields.length; index++) {
      long number = Long.parseLong(fields[index]);
      long maximum = index < 3 ? 0xffffL : 0xffff_ffffL;
      if (number < 0 || number > maximum)
        throw new IllegalArgumentException(index < 3
            ? "magicId/x/y must be unsigned 16-bit integers"
            : "targetId must be an unsigned 32-bit integer");
    }
    return value;
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
   * The W41 spell-shadow script. The embedded runner seeds a level-31 Taoist on (20,20) with
   * an 80-HP deficit and a learned 群体治愈术 (29) row. The real CM_SPELL request is followed by
   * exactly sixteen MANUAL-clock ticks: 16 × 50 ms reaches the 800 ms RM_MAGHEALING delay
   * without consulting wall time. This makes the MP spend, HP recovery and skill-training
   * packet all reproducible comparison surfaces.
   */
  public static List<Op> areaHealingScript() {
    return parseScript("""
        # --- seeded level-31 Taoist, wounded at the fixed spawn cell (20,20) ---
        bag
        # --- CM_SPELL: un-targeted ground click, Recog=MakeLong(20,20), Tag=29 ---
        spell 29 20 20
        # --- RM_MAGHEALING lands after 800 ms = 16 shipped 50 ms world ticks ---
        tick 16
        # --- the learned-magic row and recovered ability must persist through relog ---
        relog
        bag
        """);
  }

  /**
   * The W51 火墙 script ({@code SKILL_EARTHFIRE}=22). The embedded runner seeds a level-25
   * Wizard at (20,20) with a learned 火墙 row and a ring of eight stationary dummies. The
   * ground-click cast is centred on a dummy's cell, so the five-cell cross engulfs it.
   *
   * <p>Timeline, all on the MANUAL clock: the cast lands on tick 0; {@code TFireBurnEvent}
   * burns on the first manager pass and then every 3000 ms (60 ticks), while the flames
   * expire strictly after their rolled duration. The 301 pumped ticks therefore cover the
   * immediate burn, several periodic burns and the expiry of a level-0 wall, and every burn
   * is a MAGIC-stream damage roll that a wrong {@code --right-seed} must perturb.
   */
  public static List<Op> fireWallScript() {
    return parseScript("""
        # --- seeded level-25 Wizard at the fixed spawn cell (20,20) ---
        bag
        # --- CM_SPELL: ground click on the dummy cell (Recog=cell, Tag=22, no target id) ---
        spell 22 18 20
        # --- first burn lands on the first manager pass, then one burn per 3000 ms ---
        tick 1
        tick 60
        tick 60
        tick 120
        tick 60
        # --- flames expired and the burn tally must survive the persistence round trip ---
        relog
        bag
        """);
  }

  /**
   * The W51 瞬息移动 script ({@code SKILL_SPACEMOVE}=21). The embedded runner seeds a
   * level-25 Wizard at (20,20) with a learned 瞬息移动 row. The ground-click cast on the
   * caster's own cell is legal and un-targeted; the Random(11) success gate and the
   * MapRandomMove landing cell are both drawn from the seeded SPACE_MOVE stream.
   *
   * <p>The cast frame (SM_MAGICFIRE) leaves before the caster moves, the departure
   * (SM_SPACEMOVE_HIDE2) and arrival (SM_SPACEMOVE_SHOW2) frames bracket the relocation, and
   * a failed gate leaves the caster in place with only the cast frame and the mana spend.
   * The relog proves the landing cell is what the next login restores.
   */
  public static List<Op> spaceMoveScript() {
    return parseScript("""
        # --- seeded level-25 Wizard at the fixed spawn cell (20,20) ---
        bag
        # --- CM_SPELL: un-targeted ground click on the caster's own cell (Recog=cell, Tag=21) ---
        spell 21 20 20
        # --- the gate outcome and any relocation are already on the wire; let the clock settle ---
        tick 1
        tick 20
        # --- the landing cell must persist through the relog ---
        relog
        bag
        """);
  }

  /**
   * The W52 爆裂火焰 script ({@code SKILL_FIREBOOM}=23, {@code MagBigExplosion},
   * Magic.pas:510 → 1170). The embedded runner seeds a level-25 Wizard at (20,20) with a
   * learned 爆裂火焰 row and the same eight-dummy ring the 火墙 case uses. The spell is a
   * ground-target square of {@code MIR2_FIREBOOM_RANGE} cells (default 1, a 3×3), so clicking
   * the north arm of the ring engulfs five dummies and clicking the south arm engulfs the
   * other five — two independent {@code GetAttackPower} rolls, one shared by every target of
   * its cast.
   *
   * <p>Unlike 火墙 there is no delayed burn event: {@code RM_MAGSTRUCK} is delivered inline,
   * so the {@code struck other dmg=… hp=…} combat census of the cast op itself is the
   * comparison surface, together with the immediate MP spend and the SM_MAGIC_LVEXP training
   * line. The dummies are stationary trainers with 9999 HP, so nothing dies and the only
   * randomness in the whole script is the two seeded damage rolls — exactly what
   * {@code --right-seed} must perturb.
   *
   * <p>The two casts are 29 pumped ticks (1450 ms) apart, clearing the shared
   * 1350 ms {@code CM_SPELL} interval plus the row's 60 ms delay without consulting wall
   * time.
   */
  public static List<Op> fireBoomScript() {
    return parseScript("""
        # --- seeded level-25 Wizard at the fixed spawn cell (20,20) ---
        bag
        # --- CM_SPELL: ground click on the ring's north arm (3x3 square, radius 1) ---
        spell 23 20 19
        # --- the blast is instant: the five struck reports are already in this op's bucket ---
        tick 1
        # --- clear the 1350 ms spell interval (29 x 50 ms), then blow the south arm too:
        #     a second, independent shared-power roll ---
        tick 29
        spell 23 20 21
        tick 1
        # --- MP, training and the bag must survive the persistence round trip ---
        relog
        bag
        """);
  }

  /**
   * The W52 地狱雷光 script ({@code SKILL_LIGHTFLOWER}=24, {@code MagElecBlizzard},
   * Magic.pas:518 → 1191). The embedded runner seeds a level-31 Wizard at (20,20) — the row's
   * {@code NeedL1} is 30 — with a learned 地狱雷光 row and a ring of eight <em>scarecrows</em>
   * instead of trainers.
   *
   * <p>The dummy kind is the point: 地狱雷光 is the engine's only {@code LA_UNDEAD} branch, so
   * the undead scarecrow ({@code Monster.DB} {@code Undead}=1) takes the full rolled power
   * while any living target would take {@code power div 10} — a division that would erase the
   * low bits the seeded damage stream is supposed to expose. The blast is centred on the
   * caster (the click only rides along on the trailing {@code RM_MAGICFIRE} frame), so one
   * ground click on the caster's own cell strikes every dummy in the radius-2 square at once.
   *
   * <p>Scarecrows have 20 HP, so the cast kills the whole ring: the bucket then also carries
   * the {@code SM_DEATH} frames, the {@code SM_WINEXP} line and the seeded drop rolls that
   * land on the ground. The second cast lands on an empty square — the row still spends its
   * mana and rolls its power, but no target means no strikes and no training points, which is
   * Delphi's {@code boTrain} signal reproduced on the wire.
   */
  public static List<Op> lightFlowerScript() {
    return parseScript("""
        # --- seeded level-31 Wizard at (20,20), ringed by eight undead scarecrows ---
        bag
        # --- CM_SPELL: the click rides on RM_MAGICFIRE; the blast is centred on the caster ---
        spell 24 20 20
        # --- the whole ring dies to the LA_UNDEAD full-power strike in this same bucket ---
        tick 1
        # --- second cast after the 1350 ms interval + 60 ms delay: an empty square, so MP is
        #     spent and the power still rolls, but nothing is struck and nothing trains ---
        tick 29
        spell 24 20 20
        tick 1
        # --- experience, ground drops and training must survive the persistence round trip ---
        relog
        bag
        """);
  }

  /**
   * The W52 冰咆哮 script ({@code SKILL_SNOWWIND}=33, {@code MagBigExplosion} again,
   * Magic.pas:578). Identical in shape to {@link #fireBoomScript()} — same shared-power rule,
   * same instant {@code RM_MAGSTRUCK} delivery, same stationary ring — but a different
   * Magic.DB row: {@code NeedL1}=35, so the embedded runner seeds a level-35 Wizard, and the
   * blast radius comes from {@code MIR2_SNOW_WIND_RANGE} rather than
   * {@code MIR2_FIREBOOM_RANGE}. Keeping both rows in the gate is what pins the two config
   * fields to their own skill.
   */
  public static List<Op> snowWindScript() {
    return parseScript("""
        # --- seeded level-35 Wizard at the fixed spawn cell (20,20) ---
        bag
        # --- CM_SPELL: ground click on the ring's north arm (3x3 square, radius 1) ---
        spell 33 20 19
        tick 1
        # --- clear the 1350 ms spell interval + the row's 60 ms delay, then the south arm ---
        tick 29
        spell 33 20 21
        tick 1
        # --- MP, training and the bag must survive the persistence round trip ---
        relog
        bag
        """);
  }

  /**
   * The W53 魔法盾 script ({@code SKILL_MAGIC_SHIELD}=31, {@code MagBubbleDefenceUp},
   * Magic.pas:568 → ObjBase.pas:24331). The embedded runner seeds a level-31 Wizard at
   * (20,20) — {@code NeedL1}=31 — with a learned 魔法盾 row.
   *
   * <p>The ground-click cast on the caster's own cell spends 20 MP, rolls a duration of
   * 18–19 seconds (derived from MC and {@code GetPower(RPow(MC) + 15)}) from the MAGIC
   * stream, raises {@code STATE_BUBBLEDEFENCEUP} and emits the green hint
   * "魔法盾已生效，持续N秒".
   *
   * <p>A second cast after 29 pumped ticks (1450 ms, clearing the spell interval) while the
   * shield is still active is rejected deterministically with
   * {@code BUFF_ALREADY_ACTIVE} ("魔法盾效果仍在持续") without spending mana or training.
   *
   * <p>Pumping across the shield's expiry deadline allows fresh casts: the third cast rolls
   * duration again. A fourth cast at the 18.25-second boundary (365 ticks) exercises the
   * duration difference between 18s and 19s rolls under different seeds (negative control).
   * The relog proves the training and mana persist.
   */
  public static List<Op> shieldScript() {
    return parseScript("""
        # --- seeded level-31 Wizard at the fixed spawn cell (20,20) ---
        bag
        # --- cast #1: activates shield, spends 20 MP, rolls duration and trains ---
        spell 31 20 20
        tick 1
        # --- clear the 1350 ms spell interval (29 x 50 ms), cast #2 while active:
        #     deterministic BUFF_ALREADY_ACTIVE refusal without spending mana ---
        tick 29
        spell 31 20 20
        tick 1
        # --- advance 370 ticks (18.5 s) to clear the first 19s shield duration ---
        tick 370
        # --- cast #3 after expiry: rolls duration again, spends mana and trains ---
        spell 31 20 20
        tick 1
        # --- advance 400 ticks (20 s) to clear the second 19s shield duration ---
        tick 400
        # --- cast #4: rolls third duration from MAGIC stream (19s vs 18s) ---
        spell 31 20 20
        tick 1
        # --- pump 365 ticks (18.25 s): under 18s roll (seed 99999) shield has expired;
        #     under 19s roll (seed 20260922) shield is still active ---
        tick 365
        spell 31 20 20
        tick 1
        # --- MP, training and bag must survive the persistence round trip ---
        relog
        bag
        """);
  }

  /**
   * The W53 圣言术 script ({@code SKILL_KILLUNDEAD}=32, {@code MagTurnUndead},
   * Magic.pas:572 → 901). The embedded runner seeds a level-39 Wizard at (20,20) —
   * with a level-1 圣言术 row and a ring of eight undead scarecrows.
   *
   * <p>Unlike the area spells, 圣言术 is a single-target hostile spell requiring an explicit
   * target object ID: the client targets scarecrow #2 standing at (20,19) on the north arm.
   * The level-10 scarecrow passes both level gates (38 > 10 and 10 < 50), rolls the 51%
   * instant-kill chance from the {@code TURN_UNDEAD} stream: on the first cast under seed
   * 20260922 (k=87) the roll fails, leaving the monster provoked; on the second cast against
   * scarecrow #6 at (20,21) (k=50) the roll succeeds, instantly killing the scarecrow with
   * {@code SM_MAGICFIRE}, {@code SM_DEATH}, {@code SM_WINEXP +15}, ground drops and
   * {@code SM_MAGIC_LVEXP} training.
   *
   * <p>The second cast strikes scarecrow #6 at (20,21) on the south arm after clearing
   * the 1350 ms spell interval + 120 ms row delay (30 ticks = 1500 ms).
   */
  public static List<Op> killUndeadScript() {
    return parseScript("""
        # --- seeded level-39 Wizard at (20,20), ringed by eight undead scarecrows ---
        bag
        # --- CM_SPELL: target scarecrow #2 at (20,19) on the ring's north arm ---
        spell 32 20 19 2
        # --- instant-kill outcome, mana spend and any training land on this tick ---
        tick 1
        # --- clear the 1350 ms spell interval + 120 ms delay (30 x 50 ms = 1500 ms),
        #     then strike scarecrow #6 at (20,21) on the south arm ---
        tick 30
        spell 32 20 21 6
        tick 1
        # --- experience, drops and training must survive the persistence round trip ---
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
