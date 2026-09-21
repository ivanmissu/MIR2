package com.mir2.world;

import java.util.List;

/**
 * Minimal standard-item catalog for the W04 slice: exactly the items referenced by the
 * shipped monster drop tables (chicken/orc) plus the small healing potion used for manual
 * gameplay checks. It is deliberately not a full StdItems.DB import — that lands with the
 * P2 item work.
 *
 * <p>Category evidence: Client/ClFunc.pas {@code GetTakeOnPosition} (5/6 weapon, 10/11
 * dress, 15/16 helmet, 19-21 necklace, 22/23 ring, 24/26 armring), ObjBase.pas:17359
 * {@code ClientUseItems} (0-3 eatable, 4 book, 31 unbind), FState.pas tooltip cases
 * (0 potion restores AC/MAC, 5/6 weapon shows LoWord-..HiWord DC ranges).
 */
public final class StdItems {
  private StdItems() {}

  public static List<StdItem> defaults() {
    return List.of(chickenMeat(), deerMeat(), woodenSword(), smallHealingPotion(), revivalRing());
  }

  /**
   * 复活戒指: the classic Shape 114 revival ring (StdMode 22 → U_RINGL/U_RINGR). Each
   * activation of the death-defying branch drains 1000 durability from every worn
   * revival-capable item ({@code ItemDamageRevivalRing}), so DuraMax 5000 is five
   * deaths' worth of protection. Listed in the catalog for manual gameplay checks and
   * the future NPC shop slice; nothing drops it yet.
   */
  // TODO(verify): real template values (Looks/price/AC) must be confirmed against the
  // StdItems.DB import.
  public static StdItem revivalRing() {
    return new StdItem("复活戒指", 22, 114, 1, 0, 0, 0, 217, 5000, 0, 0, 0, 0, 0, 0, 0, 3000);
  }

  /**
   * 鸡肉: guaranteed chicken drop. Meat is a plain sellable/eatable misc item; its
   * quality lives in the per-instance durability, so the template keeps a nominal
   * DuraMax of 10.
   */
  // TODO(verify): classic StdMode/price for meat must be confirmed against the real
  // StdItems.DB import; 1 is the "weight-only" misc class from the FState tooltip.
  public static StdItem chickenMeat() {
    return new StdItem("鸡肉", 1, 0, 2, 0, 0, 0, 41, 10, 0, 0, 0, 0, 0, 0, 0, 2);
  }

  /** 鹿肉: the orc's common drop, same semantics as 鸡肉 but slightly heavier. */
  // TODO(verify): same data-source caveat as 鸡肉.
  public static StdItem deerMeat() {
    return new StdItem("鹿肉", 1, 0, 3, 0, 0, 0, 42, 10, 0, 0, 0, 0, 0, 0, 0, 3);
  }

  /**
   * 木剑: the classic starter weapon (StdMode 5, one-handed). DC 2-5 packed as
   * {@code MakeLong(2, 5)}, DuraMax 20, level requirement 0, Looks 1 = the Items.WIL
   * icon index already used by the orc drop table.
   */
  public static StdItem woodenSword() {
    return new StdItem("木剑", 5, 0, 2, 0, 0, 0, 1, 20, 0, 0,
        StdItem.packedRange(2, 5), 0, 0, 0, 0, 400);
  }

  /**
   * 金创药(小量): StdMode 0 potion, Shape 0 = normal (non-instant) restore. The AC dword
   * carries the HP restore amount for StdMode 0 items per the FState tooltip.
   */
  // TODO(verify): restore amount and price must be re-checked against StdItems.DB.
  public static StdItem smallHealingPotion() {
    return new StdItem("金创药(小量)", 0, 0, 1, 0, 0, 0, 40, 0, 30, 0, 0, 0, 0, 0, 0, 30);
  }
}
