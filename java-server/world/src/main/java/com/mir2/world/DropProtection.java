package com.mir2.world;

import java.util.Map;

/**
 * The three worn-gear flags that suppress the death penalties, recalculated by
 * {@code TBaseObject.RecalcAbilitys} (ObjBase.pas:2818) exactly like {@code m_boRevival}:
 *
 * <ul>
 *   <li>{@code m_boAngryRing} — 护身 / 幸运项链类「祝福」效果; blocks <em>both</em>
 *       {@code ScatterBagItems} and {@code DropUseItems} outright (ObjBase.pas:15498, 26660).</li>
 *   <li>{@code m_boNoDropItem} — blocks the bag scatter only (ObjBase.pas:26660).</li>
 *   <li>{@code m_boNoDropUseItem} — blocks the equipment drop only (ObjBase.pas:15498).</li>
 * </ul>
 *
 * <p>Slot asymmetry is reproduced verbatim. The weapon, right-hand and dress slots take an
 * early {@code Continue} in the Delphi loop, so those three read the flags off
 * {@code StdItem.AniCount} (ObjBase.pas:2973/3010-3012); every other slot reads them off
 * {@code StdItem.Shape} (ObjBase.pas:3158-3160). Shape/AniCount 117 sets 护身 as well, but the
 * 117 form only exists for the hand/dress branch and the two ring slots (ObjBase.pas:3274).
 * Items at zero durability are skipped, like every other flag in {@code RecalcAbilitys}.
 */
public record DropProtection(boolean angryRing, boolean noDropItem, boolean noDropUseItem) {

  public static final DropProtection NONE = new DropProtection(false, false, false);

  /** {@code AniCount/Shape = 170}: the second 护身戒指 encoding. */
  private static final int SHAPE_ANGRY_RING = 170;

  /** {@code AniCount/Shape = 117}: the classic 护身戒指 encoding (hand/dress and ring slots). */
  private static final int SHAPE_ANGRY_RING_CLASSIC = 117;

  /** {@code AniCount/Shape = 171}: 不掉包裹物品. */
  private static final int SHAPE_NO_DROP_ITEM = 171;

  /** {@code AniCount/Shape = 172}: 不掉身上装备. */
  private static final int SHAPE_NO_DROP_USE_ITEM = 172;

  /** Recomputes the three flags from a worn set, in {@code RecalcAbilitys} order. */
  public static DropProtection of(Equipment equipment) {
    boolean angryRing = false;
    boolean noDropItem = false;
    boolean noDropUseItem = false;
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : equipment.inSlotOrder()) {
      BackpackItem worn = entry.getValue();
      // ObjBase.pas:2945 — a worn-out item grants nothing.
      if (worn.dura() <= 0) continue;
      EquipmentSlot slot = entry.getKey();
      StdItem item = worn.item();
      boolean handOrDress = slot == EquipmentSlot.WEAPON
          || slot == EquipmentSlot.RIGHT_HAND
          || slot == EquipmentSlot.DRESS;
      int code = handOrDress ? item.aniCount() : item.shape();
      boolean ringSlot = slot == EquipmentSlot.RING_LEFT || slot == EquipmentSlot.RING_RIGHT;
      if (code == SHAPE_ANGRY_RING) angryRing = true;
      // The 117 form is only read on the hand/dress branch (AniCount) and the two ring slots.
      if (code == SHAPE_ANGRY_RING_CLASSIC && (handOrDress || ringSlot)) angryRing = true;
      if (code == SHAPE_NO_DROP_ITEM) noDropItem = true;
      if (code == SHAPE_NO_DROP_USE_ITEM) noDropUseItem = true;
    }
    return new DropProtection(angryRing, noDropItem, noDropUseItem);
  }

  /** {@code if m_boAngryRing or m_boNoDropItem ... then Exit} in {@code ScatterBagItems}. */
  public boolean blocksBagScatter() {
    return angryRing || noDropItem;
  }

  /** {@code if m_boAngryRing or m_boNoDropUseItem then Exit} in {@code DropUseItems}. */
  public boolean blocksEquipmentDrop() {
    return angryRing || noDropUseItem;
  }
}
