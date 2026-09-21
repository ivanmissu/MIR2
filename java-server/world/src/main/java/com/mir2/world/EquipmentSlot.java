package com.mir2.world;

/**
 * The thirteen equipment positions of {@code THumanUseItems}
 * (Common/Grobal2.pas:797 — {@code array[0..12] of TUserItem}).
 *
 * <p>The {@code U_*} constants live at Common/Grobal2.pas:29-55. The 1.50 client only ever
 * filled the first ten (nine plus the 道符 charm) and the three trailing slots are commented
 * out inside {@code CheckUserItems} (M2Share.pas:3529-3531), but the array itself and the
 * {@code btWhere < 13} bound in {@code ClientTakeOffItems} (ObjBase.pas:17223) are sized for
 * thirteen. The engine therefore keeps thirteen addressable slots and lets
 * {@link #accepts(StdItem)} decide which ones can actually receive an item.
 */
public enum EquipmentSlot {
  /** {@code U_DRESS} — 衣服; counts toward WearWeight. */
  DRESS(0),
  /** {@code U_WEAPON} — 武器; counts toward HandWeight. */
  WEAPON(1),
  /** {@code U_RIGHTHAND} — 右手 (lantern/mount); counts toward HandWeight and lights the player. */
  RIGHT_HAND(2),
  /** {@code U_NECKLACE} — 项链. */
  NECKLACE(3),
  /** {@code U_HELMET} — 头盔. */
  HELMET(4),
  /** {@code U_ARMRINGL} — 左手镯. */
  ARM_RING_LEFT(5),
  /** {@code U_ARMRINGR} — 右手镯. */
  ARM_RING_RIGHT(6),
  /** {@code U_RINGL} — 左戒指. */
  RING_LEFT(7),
  /** {@code U_RINGR} — 右戒指. */
  RING_RIGHT(8),
  /** {@code U_BUJUK} — 护身符/道符. */
  CHARM_AMULET(9),
  /** {@code U_BELT} — 腰带; disabled in the shipped CheckUserItems. */
  BELT(10),
  /** {@code U_BOOTS} — 鞋子; disabled in the shipped CheckUserItems. */
  BOOTS(11),
  /** {@code U_CHARM} — 宝石; disabled in the shipped CheckUserItems. */
  GEM(12);

  /** {@code High(THumanUseItems) + 1}. */
  public static final int COUNT = 13;

  private final int index;

  EquipmentSlot(int index) {
    this.index = index;
  }

  /** The {@code U_*} array index used on the wire and in persistence. */
  public int index() {
    return index;
  }

  public static EquipmentSlot fromIndex(int index) {
    for (EquipmentSlot slot : values()) {
      if (slot.index == index) return slot;
    }
    throw new IllegalArgumentException("invalid equipment slot: " + index);
  }

  /** True when {@code index} addresses a slot, matching the {@code btWhere < 13} guard. */
  public static boolean isValidIndex(int index) {
    return index >= 0 && index < COUNT;
  }

  /**
   * {@code CheckUserItems} (M2Share.pas:3513): decides whether a template's {@code StdMode}
   * may occupy this slot. The three commented-out cases (belt/boots/gem) accept nothing, so
   * those slots stay permanently empty exactly like the shipped server.
   */
  public boolean accepts(StdItem item) {
    int mode = item.stdMode();
    return switch (this) {
      case DRESS -> mode == 10 || mode == 11;
      case WEAPON -> mode == 5 || mode == 6;
      case RIGHT_HAND -> mode == 28 || mode == 29 || mode == 30;
      case NECKLACE -> mode == 19 || mode == 20 || mode == 21;
      case HELMET -> mode == 15;
      case ARM_RING_LEFT -> mode == 24 || mode == 25 || mode == 26;
      case ARM_RING_RIGHT -> mode == 24 || mode == 26;
      case RING_LEFT, RING_RIGHT -> mode == 22 || mode == 23;
      case CHARM_AMULET -> mode == 25 || mode == 51;
      // U_BELT (54/64), U_BOOTS (52/62) and U_CHARM (53/63) are commented out in
      // CheckUserItems, so the live server rejects every take-on into them.
      case BELT, BOOTS, GEM -> false;
    };
  }

  /**
   * {@code RecalcAbilitys} splits worn weight two ways: the dress adds to WearWeight while
   * the weapon and right hand add to HandWeight (ObjBase.pas:2950-2958). Accessories add to
   * neither; they only contribute to the total bag/body Weight.
   */
  public boolean countsTowardHandWeight() {
    return this == WEAPON || this == RIGHT_HAND;
  }

  public boolean countsTowardWearWeight() {
    return this == DRESS;
  }
}
