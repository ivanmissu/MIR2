package com.mir2.world;

/**
 * The two per-instance weapon "luck" bytes of Delphi's {@code TUserItem.btValue} that
 * {@code MakeWeaponUnlock} and the smithing NPC read: {@code btValue[3]} (幸运, luck points) and
 * {@code btValue[4]} (诅咒, curse points), see ObjBase.pas:2393 and ItmUnit.pas:124-126.
 *
 * <p>Only weapons carry these; every other slot leaves them zero. They fold into the
 * client-facing {@code TClientItem} through {@code TItem.GetItemAddValue} — for a weapon
 * {@code AC := MakeLong(AC + btValue[3], AC2 + btValue[5])} and
 * {@code MAC := MakeLong(MAC + btValue[4], ...)}, i.e. the luck point raises the item's AC-low
 * (read by {@code ApplyItemParameters} as {@code btLuck}) and the curse point raises its
 * MAC-low ({@code btUnLuck}). Crucially the server's {@code RecalcAbilitys} loop reads the
 * <em>base</em> catalog item ({@code GetStdItem}), not the folded instance, so these points do
 * not change server combat; they surface on the client item and in the NPC upgrade formula.
 *
 * <p>The record is immutable and defaults to {@link #NONE}; {@link #withLuck}/{@link #withCurse}
 * return the next state, matching the copy-on-write style of {@link BackpackItem}.
 */
public record WeaponPoints(int luck, int curse) {

  /** {@code btValue[4] < 10} guard in MakeWeaponUnlock caps accumulated curse points at 10. */
  public static final int MAX_CURSE = 10;

  /** A weapon with neither luck nor curse — the default for every freshly created item. */
  public static final WeaponPoints NONE = new WeaponPoints(0, 0);

  public WeaponPoints {
    if (luck < 0 || luck > 0xff) throw new IllegalArgumentException("luck must be a byte value");
    if (curse < 0 || curse > 0xff) throw new IllegalArgumentException("curse must be a byte value");
  }

  public boolean isNone() {
    return luck == 0 && curse == 0;
  }

  public WeaponPoints withLuck(int newLuck) {
    return new WeaponPoints(newLuck, curse);
  }

  public WeaponPoints withCurse(int newCurse) {
    return new WeaponPoints(luck, newCurse);
  }
}
