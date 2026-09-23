package com.mir2.world;

import java.nio.charset.Charset;

/**
 * Full client-visible item template, mirroring the packed Delphi {@code TStdItem}
 * (Common/Grobal2.pas:540, Client/Grobal2.pas:536).
 *
 * <p>The Delphi record is {@code packed} and occupies 66 bytes on the wire: a 21-byte
 * {@code String[20]} ShortString name slot (one length byte plus 20 GBK payload bytes),
 * seven single-byte fields, the {@code Looks} word and nine dwords. The "//60 bytes"
 * comment in the Delphi source is stale — it predates the name growing from
 * {@code String[14]} to {@code String[20]}.
 *
 * <p>The nine dword fields are kept as unsigned 32-bit values. Stats such as DC are packed
 * ranges in the classic {@code MakeLong(min, max)} layout produced by
 * {@code TItem.GetItemAddValue} (ItmUnit.pas): low word = lower bound, high word = upper
 * bound. For potions the AC/MAC dwords are plain restore amounts instead (FState.pas
 * tooltip prints them with {@code IntToStr}).
 *
 * @param stdMode item category: 0-3 eatable, 4 book, 5/6 weapon, 10/11 dress, ... see
 *     Client/ClFunc.pas {@code GetTakeOnPosition} and ObjBase.pas {@code ClientUseItems}
 * @param reserved server-side bit flags copied into {@code TStdItem.Reserved}; the official
 *     database uses bit 3 for the 祈祷/赤血 family and bit 2 on 赤血魔剑
 */
public record StdItem(
    String name,
    int stdMode,
    int shape,
    int weight,
    int aniCount,
    int source,
    int reserved,
    int needIdentify,
    int looks,
    long duraMax,
    long ac,
    long mac,
    long dc,
    long mc,
    long sc,
    long need,
    long needLevel,
    long price) {

  /** Delphi {@code String[20]} payload capacity in GBK bytes. */
  public static final int MAX_NAME_BYTES = 20;

  /** {@code ITEM_WEAPON} (Common/Grobal2.pas:77). */
  public static final int ITEM_WEAPON = 0;
  /** {@code ITEM_ARMOR} (Common/Grobal2.pas:78). */
  public static final int ITEM_ARMOR = 1;
  /** {@code ITEM_ACCESSORY} (Common/Grobal2.pas:79). */
  public static final int ITEM_ACCESSORY = 2;
  /** {@code ITEM_ETC}: everything the LocalDB classifier does not recognise. */
  public static final int ITEM_ETC = 3;

  private static final Charset GBK = Charset.forName("GBK");
  private static final long UINT_MAX = 0xFFFF_FFFFL;

  public StdItem {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("item name must not be blank");
    if (name.getBytes(GBK).length > MAX_NAME_BYTES) {
      throw new IllegalArgumentException("item name must fit " + MAX_NAME_BYTES + " GBK bytes");
    }
    requireU8("stdMode", stdMode);
    requireU8("shape", shape);
    requireU8("weight", weight);
    requireU8("aniCount", aniCount);
    if (source < -128 || source > 127) throw new IllegalArgumentException("source must be a signed byte");
    requireU8("reserved", reserved);
    requireU8("needIdentify", needIdentify);
    requireU16("looks", looks);
    requireU32("duraMax", duraMax);
    requireU32("ac", ac);
    requireU32("mac", mac);
    requireU32("dc", dc);
    requireU32("mc", mc);
    requireU32("sc", sc);
    requireU32("need", need);
    requireU32("needLevel", needLevel);
    requireU32("price", price);
  }

  /**
   * Compatibility constructor for call sites that predate the explicit {@code Reserved} byte.
   * The old model only carried {@code NeedIdentify}; real GEEM2 catalogue rows use
   * {@code NeedIdentify = 0}, while {@link StdItemsDb} calls the canonical constructor to keep
   * the separate {@code Reserved} flags (祈祷套 / 赤血魔剑) intact.
   */
  public StdItem(String name, int stdMode, int shape, int weight, int aniCount, int source,
      int needIdentify, int looks, long duraMax, long ac, long mac, long dc, long mc, long sc,
      long need, long needLevel, long price) {
    this(name, stdMode, shape, weight, aniCount, source, 0, needIdentify, looks, duraMax,
        ac, mac, dc, mc, sc, need, needLevel, price);
  }

  /**
   * Classifies the template the way {@code TLocalDB.LoadItemsDB} (LocalDB.pas:307-310) does
   * when it fills {@code TItem.ItemType}. The category, not the slot, selects which branch
   * of {@code ApplyItemParameters} a worn item takes.
   */
  public int itemType() {
    return switch (stdMode) {
      case 5, 6 -> ITEM_WEAPON;
      case 10, 11 -> ITEM_ARMOR;
      case 15, 19, 20, 21, 22, 23, 24, 26, 51, 52, 53, 54, 62, 63, 64 -> ITEM_ACCESSORY;
      default -> ITEM_ETC;
    };
  }

  /** Low word of the packed AC dword — Delphi {@code TItem.AC}. */
  public int acMin() {
    return low(ac);
  }

  /** High word of the packed AC dword — Delphi {@code TItem.AC2}. */
  public int acMax() {
    return high(ac);
  }

  public int macMin() {
    return low(mac);
  }

  public int macMax() {
    return high(mac);
  }

  public int dcMin() {
    return low(dc);
  }

  public int dcMax() {
    return high(dc);
  }

  public int mcMin() {
    return low(mc);
  }

  public int mcMax() {
    return high(mc);
  }

  public int scMin() {
    return low(sc);
  }

  public int scMax() {
    return high(sc);
  }

  private static int low(long packed) {
    return (int) (packed & 0xffffL);
  }

  private static int high(long packed) {
    return (int) ((packed >>> 16) & 0xffffL);
  }

  /**
   * Packs a min/max stat range the way {@code TItem.GetItemAddValue} does:
   * {@code MakeLong(min, max)} — low word lower bound, high word upper bound.
   */
  public static long packedRange(int min, int max) {
    if (min < 0 || max < min || max > 0xFFFF) {
      throw new IllegalArgumentException("invalid stat range: " + min + ".." + max);
    }
    return ((long) max << 16) | min;
  }

  /**
   * Zero-stat template for names that predate the catalog (W03 rows) or have no entry.
   * Keeps the bag entry durable and visible instead of dropping it, matching how the
   * Delphi bag only skips items whose template lookup fails entirely.
   */
  public static StdItem placeholder(String name, int looks) {
    // TODO(verify): StdMode 1 is the "weight-only" misc class; the real value must come
    // from the StdItems.DB import before economy interactions (sell/eat) go live.
    return new StdItem(name, 1, 0, 1, 0, 0, 0, looks, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  private static void requireU8(String field, int value) {
    if (value < 0 || value > 0xff) throw new IllegalArgumentException(field + " must be an unsigned byte");
  }

  private static void requireU16(String field, int value) {
    if (value < 0 || value > 0xffff) throw new IllegalArgumentException(field + " must be an unsigned word");
  }

  private static void requireU32(String field, long value) {
    if (value < 0 || value > UINT_MAX) throw new IllegalArgumentException(field + " must be an unsigned dword");
  }
}
