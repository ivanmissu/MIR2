package com.mir2.world;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Standard-item catalog facade. As of W18 the catalog is the <b>authoritative full import</b>
 * of the official 1.76 {@code StdItems.DB} (GEEM2 baseline dump) exposed through
 * {@link StdItemsDb} — 686 rows / 684 unique names — replacing the W04 hand-picked five-item
 * placeholder catalog. Values for food, potions, weapons, jewellery, quest items and the
 * special rings are the original ones now; economy interactions may rely on them
 * ({@code DropUseItems} still waits on the PK slice as documented in WorldEngine).
 *
 * <p>Category evidence: Client/ClFunc.pas {@code GetTakeOnPosition} (5/6 weapon, 10/11
 * dress, 15/16 helmet, 19-21 necklace, 22/23 ring, 24/26 armring), ObjBase.pas:17359
 * {@code ClientUseItems} (0-3 eatable, 4 book, 31 unbind), FState.pas tooltip cases
 * (0 potion restores AC/MAC, 5/6 weapon shows LoWord-..HiWord DC ranges, 40 meat quality).
 */
public final class StdItems {
  private StdItems() {}

  /** The full imported catalog (duplicate names collapsed to the first row, Delphi first-hit). */
  public static List<StdItem> defaults() {
    return StdItemsDb.all();
  }

  /** Exact-name lookup with a hard failure — for bootstrap wiring and tests. */
  public static StdItem require(String name) {
    return StdItemsDb.byName(name)
        .orElseThrow(() -> new NoSuchElementException("StdItems.DB has no item named: " + name));
  }

  /** 鸡肉: guaranteed chicken drop (1/1). Meat is StdMode 40 in the real DB: sellable, not eatable. */
  public static StdItem chickenMeat() {
    return require("鸡肉");
  }

  /** 木剑: the classic starter weapon (StdMode 5, one-handed, DC 2-5, DuraMax 4000, needs level 1). */
  public static StdItem woodenSword() {
    return require("木剑");
  }

  /** 金创药(小量): StdMode 0 potion; AC carries the HP restore amount (30). */
  public static StdItem smallHealingPotion() {
    return require("金创药(小量)");
  }

  /**
   * 复活戒指: the classic Shape 114 revival ring (StdMode 22 → U_RINGL/U_RINGR). Each
   * activation of the death-defying branch drains 1000 durability from every worn
   * revival-capable item ({@code ItemDamageRevivalRing}), so DuraMax 5000 is five
   * deaths' worth of protection.
   */
  public static StdItem revivalRing() {
    return require("复活戒指");
  }
}
