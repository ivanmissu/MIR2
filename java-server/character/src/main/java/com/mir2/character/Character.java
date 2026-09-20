package com.mir2.character;

import java.util.Objects;
import java.util.UUID;

/** Durable character-selection data and the fields needed to build Delphi's human Feature value. */
public record Character(
    UUID id,
    String account,
    String name,
    int job,
    int level,
    int gender,
    int hair,
    int dressShape,
    int weaponShape) {

  /** Compatibility constructor for callers that create a default male, bald, unequipped character. */
  public Character(UUID id, String account, String name, int job, int level) {
    this(id, account, name, job, level, 0, 0, 0, 0);
  }

  public Character {
    Objects.requireNonNull(id, "id");
    if (account == null || account.isBlank()) throw new IllegalArgumentException("invalid account");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("invalid character name");
    if (job < 0 || job > 2) throw new IllegalArgumentException("job must be 0..2");
    if (level < 1 || level > 255) throw new IllegalArgumentException("level must be 1..255");
    if (gender < 0 || gender > 1) throw new IllegalArgumentException("gender must be 0 or 1");
    requireShape("hair", hair);
    requireShape("dress", dressShape);
    requireShape("weapon", weaponShape);
  }

  /**
   * Mirrors {@code GetFeature}/{@code MakeHumanFeature} in ObjBase.pas and Grobal2.pas.
   * Each human appearance index is doubled and its low bit carries gender.
   */
  public int feature() {
    int dress = dressShape * 2 + gender;
    int weapon = weaponShape * 2 + gender;
    int hairAppearance = hair * 2 + gender;
    return (dress << 24) | (hairAppearance << 16) | (weapon << 8);
  }

  private static void requireShape(String field, int value) {
    if (value < 0 || value > 127) {
      throw new IllegalArgumentException(field + " shape must be 0..127");
    }
  }
}
