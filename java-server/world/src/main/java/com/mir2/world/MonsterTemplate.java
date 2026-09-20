package com.mir2.world;

import java.util.List;
import java.util.Objects;

/**
 * Static definition of one melee monster, mirroring the fields of {@code TMonInfo} that the W03
 * combat slice actually consumes.
 *
 * <p>{@code feature} is the packed Delphi {@code MakeMonsterFeature(raceImage, weapon, appearance)}
 * value; the client uses it to pick the sprite, so it is stored pre-packed and never recomputed.
 */
public record MonsterTemplate(
    String name,
    int feature,
    Ability ability,
    int viewRange,
    long walkIntervalMillis,
    long attackIntervalMillis,
    long experience,
    List<ItemDrop> drops) {

  public MonsterTemplate {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("monster name must not be blank");
    Objects.requireNonNull(ability, "ability");
    if (viewRange < 1) throw new IllegalArgumentException("view range must be positive");
    if (walkIntervalMillis < 1 || attackIntervalMillis < 1)
      throw new IllegalArgumentException("action intervals must be positive");
    if (experience < 0) throw new IllegalArgumentException("experience must not be negative");
    drops = List.copyOf(drops);
  }

  /** Resolves the templates currently supported by the Java combat slice. */
  public static MonsterTemplate forName(String name) {
    if (name.equalsIgnoreCase("chicken") || name.equals("鸡")) return chicken();
    if (name.equalsIgnoreCase("orc") || name.equals("半兽人")) return orc();
    throw new IllegalArgumentException("unsupported monster template: " + name);
  }

  /** Packs a monster appearance the same way {@code MakeMonsterFeature} does in ObjBase.pas. */
  public static int packFeature(int raceImage, int weapon, int appearance) {
    if (raceImage < 0 || raceImage > 0xff) throw new IllegalArgumentException("race image must be a byte");
    if (weapon < 0 || weapon > 0xff) throw new IllegalArgumentException("weapon must be a byte");
    if (appearance < 0 || appearance > 0xffff) throw new IllegalArgumentException("appearance must be a word");
    return (appearance << 16) | (weapon << 8) | raceImage;
  }

  /** 鸡 (chicken): the weakest melee target, used for the first kill/drop/pickup loop. */
  public static MonsterTemplate chicken() {
    return new MonsterTemplate("鸡", packFeature(4, 0, 0), Ability.monster(6, 1, 2, 0, 0),
        6, 800, 1200, 6, List.of(new ItemDrop("鸡肉", 41, 1)));
  }

  /** 半兽人 (orc): a melee monster strong enough to damage a level-one player. */
  public static MonsterTemplate orc() {
    return new MonsterTemplate("半兽人", packFeature(21, 0, 0), Ability.monster(45, 4, 9, 0, 2),
        8, 600, 1000, 60, List.of(new ItemDrop("鹿肉", 42, 2), new ItemDrop("木剑", 1, 20)));
  }
}
