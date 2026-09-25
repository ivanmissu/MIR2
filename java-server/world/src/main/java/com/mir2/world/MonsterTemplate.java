package com.mir2.world;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Static definition of one monster, mirroring the fields of {@code TMonInfo} that the Java
 * combat slice actually consumes.
 *
 * <p>{@code feature} is the packed Delphi {@code MakeMonsterFeature(raceImage, weapon, appearance)}
 * value; the client uses it to pick the sprite, so it is stored pre-packed and never recomputed.
 *
 * <p>{@code behavior} selects the AI family: {@code AGGRESSIVE} is the {@code TATMonster}
 * chase-and-attack loop, {@code PASSIVE_FLEE} the {@code TChickenDeer} run-away mode.
 *
 * <p><b>W18 — stats are now data-verified, not placeholders.</b> Every template below is
 * built from the official 1.76 {@code Monster.DB} row imported in {@code db/MonsterDb.tsv}
 * (GEEM2 baseline dump; the same provenance that already backed the RaceImg/Appr
 * correction): HP, DC range, AC/MAC, level, fight experience and the {@code WALK_SPD} /
 * {@code ATTACK_SPD} action intervals all come from {@link MonsterDb}, with the loader's
 * 200&nbsp;ms floor applied ({@code LoadMonsterDB} clamps both). The kill drop tables are
 * the imported {@code MonItems/<name>.txt} rows (see {@code db/MonItems/}); ordinary item rows
 * enter {@link #drops()}, while {@code 金币} rows enter {@link #goldDrops()} for the ground-gold
 * pile path.
 *
 * <p>What the DB does <em>not</em> carry: the AI view range (Delphi sets it per monster
 * subclass in {@code ObjMon*.pas} constants), the Java-side behaviour family selection and
 * the monster's walk step logic. The view-range figures below remain code-side estimates to
 * be audited against the subclass constructors in a later slice ({@code TODO(verify)}).
 */
public record MonsterTemplate(
    String name,
    int feature,
    Ability ability,
    int viewRange,
    long walkIntervalMillis,
    long attackIntervalMillis,
    long experience,
    MonsterBehavior behavior,
    List<ItemDrop> drops,
    List<MonsterDropTable.GoldDrop> goldDrops,
    boolean undead) {

  public MonsterTemplate {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("monster name must not be blank");
    Objects.requireNonNull(ability, "ability");
    Objects.requireNonNull(behavior, "behavior");
    if (viewRange < 1) throw new IllegalArgumentException("view range must be positive");
    if (walkIntervalMillis < 1 || attackIntervalMillis < 1)
      throw new IllegalArgumentException("action intervals must be positive");
    if (experience < 0) throw new IllegalArgumentException("experience must not be negative");
    drops = List.copyOf(drops);
    goldDrops = List.copyOf(goldDrops);
  }

  /** Compatibility constructor for templates built before the {@code undead} column landed (W32). */
  public MonsterTemplate(String name, int feature, Ability ability, int viewRange,
      long walkIntervalMillis, long attackIntervalMillis, long experience, MonsterBehavior behavior,
      List<ItemDrop> drops, List<MonsterDropTable.GoldDrop> goldDrops) {
    this(name, feature, ability, viewRange, walkIntervalMillis, attackIntervalMillis, experience,
        behavior, drops, goldDrops, false);
  }

  /** Compatibility constructor for templates that have ordinary item drops but no gold rows. */
  public MonsterTemplate(String name, int feature, Ability ability, int viewRange,
      long walkIntervalMillis, long attackIntervalMillis, long experience, MonsterBehavior behavior,
      List<ItemDrop> drops) {
    this(name, feature, ability, viewRange, walkIntervalMillis, attackIntervalMillis, experience,
        behavior, drops, List.of(), false);
  }

  /** Compatibility constructor for the W03 melee slice: everything defaults to aggressive AI. */
  public MonsterTemplate(String name, int feature, Ability ability, int viewRange,
      long walkIntervalMillis, long attackIntervalMillis, long experience, List<ItemDrop> drops) {
    this(name, feature, ability, viewRange, walkIntervalMillis, attackIntervalMillis, experience,
        MonsterBehavior.AGGRESSIVE, drops, List.of(), false);
  }

  /**
   * The first-ten spawn list of the P2 plan (ObjMon.pas subclasses). Keys accept both the
   * client-visible Chinese name (used by MonGen.txt) and an ASCII alias for configuration.
   */
  private static final Map<String, Supplier<MonsterTemplate>> CATALOG = Map.ofEntries(
      Map.entry("chicken", MonsterTemplate::chicken),
      Map.entry("鸡", MonsterTemplate::chicken),
      Map.entry("deer", MonsterTemplate::deer),
      Map.entry("鹿", MonsterTemplate::deer),
      Map.entry("scarecrow", MonsterTemplate::scarecrow),
      Map.entry("稻草人", MonsterTemplate::scarecrow),
      Map.entry("hookcat", MonsterTemplate::hookCat),
      Map.entry("多钩猫", MonsterTemplate::hookCat),
      Map.entry("rakecat", MonsterTemplate::rakeCat),
      Map.entry("钉耙猫", MonsterTemplate::rakeCat),
      Map.entry("cavemaggot", MonsterTemplate::caveMaggot),
      Map.entry("洞蛆", MonsterTemplate::caveMaggot),
      Map.entry("scorpion", MonsterTemplate::scorpion),
      Map.entry("蝎子", MonsterTemplate::scorpion),
      Map.entry("orc", MonsterTemplate::orc),
      Map.entry("半兽人", MonsterTemplate::orc),
      Map.entry("orcwarrior", MonsterTemplate::orcWarrior),
      Map.entry("半兽勇士", MonsterTemplate::orcWarrior),
      Map.entry("orcfighter", MonsterTemplate::orcFighter),
      Map.entry("半兽战士", MonsterTemplate::orcFighter),
      Map.entry("trainer", MonsterTemplate::trainer),
      Map.entry("木桩", MonsterTemplate::trainer),
      Map.entry("练功师", MonsterTemplate::trainer));

  /** Resolves the templates currently supported by the Java combat slice. */
  public static MonsterTemplate forName(String name) {
    Supplier<MonsterTemplate> supplier = CATALOG.get(name.toLowerCase(Locale.ROOT));
    if (supplier == null) throw new IllegalArgumentException("unsupported monster template: " + name);
    return supplier.get();
  }

  /** Packs a monster appearance the same way {@code MakeMonsterFeature} does in ObjBase.pas. */
  public static int packFeature(int raceImage, int weapon, int appearance) {
    if (raceImage < 0 || raceImage > 0xff) throw new IllegalArgumentException("race image must be a byte");
    if (weapon < 0 || weapon > 0xff) throw new IllegalArgumentException("weapon must be a byte");
    if (appearance < 0 || appearance > 0xffff) throw new IllegalArgumentException("appearance must be a word");
    return (appearance << 16) | (weapon << 8) | raceImage;
  }

  // ------------------------------------------------------------------ DB-driven builders
  //
  // The wire feature carries the DB's RaceImg (not the server-side Race!) plus Appr: the
  // client decodes RACEfeature(feature)=byte0 as RaceImg to pick its actor class and action
  // table (PlayScn.pas NewActor / Actor.pas GetRaceByPM), and APPRfeature(feature)=high word
  // as Appr to pick the sprite: GetMonImg(appr) opens Data\Mon(appr div 10 + 1).wil and
  // GetOffset(appr) selects the (appr mod 10) frame block.

  private static final ItemDatabase DEFAULT_CATALOG = ItemDatabase.of(StdItemsDb.all());

  /**
   * Builds a template from the imported Monster.DB row and MonItems drop table
   * ({@code UsrEngn.pas:2581} field mapping; race/CoolEye/SPEED/HIT have no engine consumer
   * yet and stay readable through {@link MonsterDb}). {@code Undead} feeds
   * {@code m_btLifeAttrib = LA_UNDEAD} (W32: {@code SKILL_LIGHTENING}'s 1.5x multiplier).
   */
  private static MonsterTemplate fromDb(String dbName, String displayName, int viewRange,
      MonsterBehavior behavior) {
    MonsterDb.Row row = MonsterDb.byName(dbName)
        .orElseThrow(() -> new IllegalStateException("MonsterDb is missing row: " + dbName));
    // UsrEngn.pas:2581 — AC := MakeLong(wAC, wAC) (flat, not a range); the engine has no
    // MAC consumer yet (melee defense rolls the AC range), so MAC stays on MonsterDb.Row.
    // Monsters enter with MP := 0 / MaxMP := wMP by Delphi; the engine uses neither.
    Ability ability = new Ability(
        row.hp(), row.hp(), 0, 0, row.dc(), row.dcMax(), row.ac(), row.ac(), row.level(), 0);
    MonsterDropTable.Result drops = MonsterDropTable.load(dbName, DEFAULT_CATALOG);
    return new MonsterTemplate(displayName, packFeature(row.raceImg(), row.monsterWeapon(), row.appr()),
        ability, viewRange,
        MonsterDb.clampActionInterval(row.walkSpd()),
        MonsterDb.clampActionInterval(row.attackSpd()),
        row.exp(), behavior, drops.drops(), drops.goldDrops(), row.undead() != 0);
  }

  /** The wallet-gold rows of a template; exposed for docs/tests alongside ordinary drops. */
  public static List<MonsterDropTable.GoldDrop> goldDropsOf(String dbName) {
    return MonsterDropTable.load(dbName, DEFAULT_CATALOG).goldDrops();
  }

  // ------------------------------------------------------------------ first-ten templates

  /** 鸡 (chicken): the weakest melee target, used for the first kill/drop/pickup loop. */
  // TODO(verify): Delphi TChickenDeer is a fleeing animal; the W03 slice shipped it as an
  // aggressive target because the whole kill/drop/pickup loop (tests, bot-swarm, CI) hunts it.
  // Flip to PASSIVE_FLEE once the client comparison fixture validates the flee broadcasts.
  // TODO(verify): view range is a code-side constant (ObjMon subclass), not a DB column.
  public static MonsterTemplate chicken() {
    return fromDb("鸡", "鸡", 6, MonsterBehavior.AGGRESSIVE);
  }

  /** 鹿 (deer): TChickenDeer flee AI — never attacks, walks away from the nearest player. */
  // TChickenDeer.Create sets m_nViewRange := 5.
  public static MonsterTemplate deer() {
    return fromDb("鹿", "鹿", 5, MonsterBehavior.PASSIVE_FLEE);
  }

  /** 稻草人 (scarecrow): the first aggressive melee mob outside the farm animals. */
  public static MonsterTemplate scarecrow() {
    return fromDb("稻草人", "稻草人", 7, MonsterBehavior.AGGRESSIVE);
  }

  /** 多钩猫 (hook cat): faster melee chaser of the Bichon outskirts. */
  public static MonsterTemplate hookCat() {
    return fromDb("多钩猫", "多钩猫", 8, MonsterBehavior.AGGRESSIVE);
  }

  /** 钉耙猫 (rake cat): tougher sibling of the hook cat. */
  public static MonsterTemplate rakeCat() {
    return fromDb("钉耙猫", "钉耙猫", 8, MonsterBehavior.AGGRESSIVE);
  }

  /** 洞蛆 (cave maggot): slow, tanky cave dweller (TSlowATMonster pacing). */
  public static MonsterTemplate caveMaggot() {
    return fromDb("洞蛆", "洞蛆", 5, MonsterBehavior.AGGRESSIVE);
  }

  /** 蝎子 (scorpion): TScorpion melee, hits noticeably harder than the cats. */
  public static MonsterTemplate scorpion() {
    return fromDb("蝎子", "蝎子", 8, MonsterBehavior.AGGRESSIVE);
  }

  /** 半兽人 (orc): a melee monster strong enough to damage a level-one player. */
  public static MonsterTemplate orc() {
    return fromDb("半兽人", "半兽人", 8, MonsterBehavior.AGGRESSIVE);
  }

  /** 半兽勇士 (orc warrior): mid-tier orc cave melee. */
  public static MonsterTemplate orcWarrior() {
    return fromDb("半兽勇士", "半兽勇士", 8, MonsterBehavior.AGGRESSIVE);
  }

  /** 半兽战士 (orc fighter): strongest of the first-ten batch. */
  public static MonsterTemplate orcFighter() {
    return fromDb("半兽战士", "半兽战士", 9, MonsterBehavior.AGGRESSIVE);
  }

  /**
   * 木桩 (training dummy): the Delphi {@code TRAINER} (M2Share.pas:152 → {@code TTrainer},
   * ObjNpc.pas:2626) — the damage-test object that stands still, never retaliates and
   * reports 破坏力/平均值 for every blow it absorbs. Built from the Monster.DB row
   * {@code 练功师} (race 55) as of W18; 「木桩」 is the hand-picked display name (the
   * Delphi sources and ini files carry no Chinese for it). The row's 1-exp kill credit is
   * kept verbatim; the dummy still drops nothing (it has no MonItems file upstream).
   *
   * <p>It is modelled here as a {@link MonsterBehavior#STATIONARY} monster rather than an
   * NPC because the NPC/script engine is still behind the red line: the observable wire
   * behaviour needed (stand, take damage, die, drop nothing) is fully covered by the monster
   * object, and no script hook is introduced. This is the one target whose behaviour is
   * independent of the wall clock, so it is what the shadow-comparison harness attacks when
   * 对拍'ing PvE damage across two servers.
   */
  public static MonsterTemplate trainer() {
    return fromDb("练功师", "木桩", 1, MonsterBehavior.STATIONARY);
  }
}
