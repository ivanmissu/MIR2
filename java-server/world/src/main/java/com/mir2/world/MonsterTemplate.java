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
    List<ItemDrop> drops) {

  public MonsterTemplate {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("monster name must not be blank");
    Objects.requireNonNull(ability, "ability");
    Objects.requireNonNull(behavior, "behavior");
    if (viewRange < 1) throw new IllegalArgumentException("view range must be positive");
    if (walkIntervalMillis < 1 || attackIntervalMillis < 1)
      throw new IllegalArgumentException("action intervals must be positive");
    if (experience < 0) throw new IllegalArgumentException("experience must not be negative");
    drops = List.copyOf(drops);
  }

  /** Compatibility constructor for the W03 melee slice: everything defaults to aggressive AI. */
  public MonsterTemplate(String name, int feature, Ability ability, int viewRange,
      long walkIntervalMillis, long attackIntervalMillis, long experience, List<ItemDrop> drops) {
    this(name, feature, ability, viewRange, walkIntervalMillis, attackIntervalMillis, experience,
        MonsterBehavior.AGGRESSIVE, drops);
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
      Map.entry("木桩", MonsterTemplate::trainer));

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

  // ------------------------------------------------------------------ first-ten templates
  // All numeric stats below the two shipped W03 templates are TODO(verify) placeholders in
  // classic Monster.DB magnitude; the real import lands with the P2 data work and must be
  // confirmed by the byte-level client comparison before being treated as canonical.

  /** 鸡 (chicken): the weakest melee target, used for the first kill/drop/pickup loop. */
  // TODO(verify): Delphi TChickenDeer is a fleeing animal; the W03 slice shipped it as an
  // aggressive target because the whole kill/drop/pickup loop (tests, bot-swarm, CI) hunts it.
  // Flip to PASSIVE_FLEE once the client comparison fixture validates the flee broadcasts.
  public static MonsterTemplate chicken() {
    return new MonsterTemplate("鸡", packFeature(4, 0, 0), Ability.monster(6, 1, 2, 0, 0),
        6, 800, 1200, 6, MonsterBehavior.AGGRESSIVE, List.of(new ItemDrop("鸡肉", 41, 1)));
  }

  /** 鹿 (deer): TChickenDeer flee AI — never attacks, walks away from the nearest player. */
  public static MonsterTemplate deer() {
    // TChickenDeer.Create sets m_nViewRange := 5.
    return new MonsterTemplate("鹿", packFeature(5, 0, 0), Ability.monster(30, 0, 1, 0, 0),
        5, 800, 2000, 8, MonsterBehavior.PASSIVE_FLEE, List.of(new ItemDrop("鹿肉", 42, 1)));
  }

  /** 稻草人 (scarecrow): the first aggressive melee mob outside the farm animals. */
  public static MonsterTemplate scarecrow() {
    return new MonsterTemplate("稻草人", packFeature(1, 0, 0), Ability.monster(15, 2, 4, 0, 0),
        7, 700, 1100, 12, MonsterBehavior.AGGRESSIVE, List.of(new ItemDrop("金创药(小量)", 40, 8)));
  }

  /** 多钩猫 (hook cat): faster melee chaser of the Bichon outskirts. */
  public static MonsterTemplate hookCat() {
    return new MonsterTemplate("多钩猫", packFeature(2, 0, 0), Ability.monster(32, 4, 7, 0, 1),
        8, 600, 1000, 26, MonsterBehavior.AGGRESSIVE, List.of(new ItemDrop("金创药(小量)", 40, 6)));
  }

  /** 钉耙猫 (rake cat): tougher sibling of the hook cat. */
  public static MonsterTemplate rakeCat() {
    return new MonsterTemplate("钉耙猫", packFeature(3, 0, 0), Ability.monster(45, 5, 9, 0, 2),
        8, 600, 1000, 44, MonsterBehavior.AGGRESSIVE, List.of(new ItemDrop("金创药(小量)", 40, 5)));
  }

  /** 洞蛆 (cave maggot): slow, tanky cave dweller (TSlowATMonster pacing). */
  public static MonsterTemplate caveMaggot() {
    return new MonsterTemplate("洞蛆", packFeature(6, 0, 0), Ability.monster(60, 4, 8, 2, 5),
        5, 1400, 1800, 50, MonsterBehavior.AGGRESSIVE, List.of(new ItemDrop("金创药(小量)", 40, 5)));
  }

  /** 蝎子 (scorpion): TScorpion melee, hits noticeably harder than the cats. */
  public static MonsterTemplate scorpion() {
    return new MonsterTemplate("蝎子", packFeature(7, 0, 0), Ability.monster(70, 10, 14, 2, 4),
        8, 700, 1100, 100, MonsterBehavior.AGGRESSIVE, List.of(new ItemDrop("金创药(小量)", 40, 4)));
  }

  /** 半兽人 (orc): a melee monster strong enough to damage a level-one player. */
  public static MonsterTemplate orc() {
    return new MonsterTemplate("半兽人", packFeature(21, 0, 0), Ability.monster(45, 4, 9, 0, 2),
        8, 600, 1000, 60, MonsterBehavior.AGGRESSIVE,
        List.of(new ItemDrop("鹿肉", 42, 2), new ItemDrop("木剑", 1, 20)));
  }

  /** 半兽勇士 (orc warrior): mid-tier orc cave melee. */
  public static MonsterTemplate orcWarrior() {
    return new MonsterTemplate("半兽勇士", packFeature(22, 0, 0), Ability.monster(80, 8, 13, 1, 3),
        8, 600, 1000, 90, MonsterBehavior.AGGRESSIVE,
        List.of(new ItemDrop("木剑", 1, 15), new ItemDrop("金创药(小量)", 40, 4)));
  }

  /** 半兽战士 (orc fighter): strongest of the first-ten batch. */
  public static MonsterTemplate orcFighter() {
    return new MonsterTemplate("半兽战士", packFeature(23, 0, 0), Ability.monster(110, 12, 18, 2, 5),
        9, 550, 950, 140, MonsterBehavior.AGGRESSIVE,
        List.of(new ItemDrop("木剑", 1, 10), new ItemDrop("金创药(小量)", 40, 3)));
  }

  /**
   * 木桩 (training dummy): the Delphi {@code TRAINER} (M2Share.pas:152 → {@code TTrainer},
   * ObjNpc.pas:2626) — the damage-test object that stands still, never retaliates and
   * reports 破坏力/平均值 for every blow it absorbs.
   *
   * <p>It is modelled here as a {@link MonsterBehavior#STATIONARY} monster rather than an
   * NPC because the NPC/script engine is still behind the red line: the observable wire
   * behaviour needed (stand, take damage, die, drop nothing) is fully covered by the monster
   * object, and no script hook is introduced.
   *
   * <p>This is the one target whose behaviour is independent of the wall clock, so it is
   * what the shadow-comparison harness attacks when 对拍'ing PvE damage across two servers.
   * Its HP is deliberately large enough to survive a scripted melee sequence, and it drops
   * nothing so a comparison run cannot be perturbed by loot timing.
   */
  // TODO(verify): Delphi builds the trainer from Monster.DB row 55, so HP/AC are whatever
  // that row carries; the values below are placeholders chosen to be a stable punching bag
  // until the real Monster.DB import lands.
  public static MonsterTemplate trainer() {
    return new MonsterTemplate("木桩", packFeature(55, 0, 0), Ability.monster(5_000, 0, 0, 0, 0),
        1, 1_000, 1_000, 0, MonsterBehavior.STATIONARY, List.of());
  }
}
