package com.mir2.gate;

import com.mir2.protocol.ByteStrings;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.BackpackItem;
import com.mir2.world.StdItem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Legacy {@code TClientItem} wire codec (Common/Grobal2.pas:562, Client/Grobal2.pas:558).
 *
 * <p>{@code TClientItem} embeds the packed 66-byte {@code TStdItem} and then — because the
 * record itself is <em>not</em> packed — aligns {@code MakeIndex: Integer} to offset 68,
 * leaving bytes 66..67 as padding, followed by the {@code Dura}/{@code DuraMax} words.
 * Both the Delphi client and server compile this same declaration, so the wire size is
 * {@value #CLIENT_ITEM_BYTES} bytes, not the 68 bytes the stale "60 bytes" TStdItem
 * comment suggests. Sizes are derived from the field list and must be confirmed by a
 * Delphi-captured golden vector.
 *
 * <p>Body framing follows ObjBase.pas: {@code SM_ADDITEM} carries one bare encoded block
 * ({@code SendAddItem}), while {@code SM_BAGITEMS} joins every block with a trailing
 * {@code '/'} separator ({@code ClientQueryBagItems}).
 */
public final class ClientItemCodec {
  /** Packed TStdItem: 21-byte ShortString name + 7 bytes + Looks word + 9 dwords. */
  public static final int STD_ITEM_BYTES = 66;
  /** sizeof(TClientItem): 66 + 2 alignment padding + MakeIndex + Dura + DuraMax. */
  public static final int CLIENT_ITEM_BYTES = 76;

  /** Offset of the StdMode byte inside TStdItem. */
  public static final int NAME_BYTES = 21;
  public static final int STD_MODE_OFFSET = 21;
  public static final int SHAPE_OFFSET = 22;
  public static final int WEIGHT_OFFSET = 23;
  public static final int ANI_COUNT_OFFSET = 24;
  public static final int SOURCE_OFFSET = 25;
  public static final int RESERVED_OFFSET = 26;
  public static final int NEED_IDENTIFY_OFFSET = 27;
  public static final int LOOKS_OFFSET = 28;
  public static final int DURA_MAX_OFFSET = 30;
  public static final int AC_OFFSET = 34;
  public static final int MAC_OFFSET = 38;
  public static final int DC_OFFSET = 42;
  public static final int MC_OFFSET = 46;
  public static final int SC_OFFSET = 50;
  public static final int NEED_OFFSET = 54;
  public static final int NEED_LEVEL_OFFSET = 58;
  public static final int PRICE_OFFSET = 62;
  public static final int MAKE_INDEX_OFFSET = 68;
  public static final int DURA_OFFSET = 72;
  public static final int DURA_MAX_INSTANCE_OFFSET = 74;

  /** Delphi joins bag bodies with a trailing '/' after every item, including the last. */
  public static final char SEPARATOR = '/';

  private ClientItemCodec() {}

  /** Single-item body used by {@code SM_ADDITEM} — no separator (ObjBase.pas:2145). */
  public static String encode(BackpackItem item) {
    return new String(SixBitCodec.encode(bytes(item)), StandardCharsets.ISO_8859_1);
  }

  /** {@code SM_BAGITEMS} body for a whole bag; empty string for an empty bag. */
  public static String encodeBag(List<BackpackItem> backpack) {
    StringBuilder body = new StringBuilder();
    for (BackpackItem item : backpack) {
      body.append(encode(item)).append(SEPARATOR);
    }
    return body.toString();
  }

  /**
   * {@code SM_SENDUSEITEMS} body built by {@code TPlayObject.SendUseitems} (ObjBase.pas:16897):
   * every occupied slot contributes {@code IntToStr(slot) + '/' + EncodeBuffer(item) + '/'}.
   * Empty slots are skipped entirely and an empty worn set produces no packet at all.
   */
  public static String encodeWornSet(Map<Integer, BackpackItem> wornBySlot) {
    StringBuilder body = new StringBuilder();
    for (Map.Entry<Integer, BackpackItem> entry : new TreeMap<>(wornBySlot).entrySet()) {
      body.append(entry.getKey()).append(SEPARATOR)
          .append(encode(entry.getValue())).append(SEPARATOR);
    }
    return body.toString();
  }

  public static BackpackItem decode(String encoded) {
    byte[] bytes = SixBitCodec.decodeString(encoded);
    if (bytes.length != CLIENT_ITEM_BYTES) {
      throw new IllegalArgumentException("TClientItem must decode to " + CLIENT_ITEM_BYTES
          + " bytes, got " + bytes.length);
    }
    int nameLength = bytes[0] & 0xff;
    if (nameLength > StdItem.MAX_NAME_BYTES) {
      throw new IllegalArgumentException("TClientItem name length byte out of range: " + nameLength);
    }
    StdItem std = new StdItem(
        ByteStrings.fromGbk(Arrays.copyOfRange(bytes, 1, 1 + nameLength)),
        u8(bytes, STD_MODE_OFFSET),
        u8(bytes, SHAPE_OFFSET),
        u8(bytes, WEIGHT_OFFSET),
        u8(bytes, ANI_COUNT_OFFSET),
        bytes[SOURCE_OFFSET],
        u8(bytes, NEED_IDENTIFY_OFFSET),
        u16(bytes, LOOKS_OFFSET),
        u32(bytes, DURA_MAX_OFFSET),
        u32(bytes, AC_OFFSET),
        u32(bytes, MAC_OFFSET),
        u32(bytes, DC_OFFSET),
        u32(bytes, MC_OFFSET),
        u32(bytes, SC_OFFSET),
        u32(bytes, NEED_OFFSET),
        u32(bytes, NEED_LEVEL_OFFSET),
        u32(bytes, PRICE_OFFSET));
    return new BackpackItem(std,
        i32(bytes, MAKE_INDEX_OFFSET),
        u16(bytes, DURA_OFFSET),
        u16(bytes, DURA_MAX_INSTANCE_OFFSET));
  }

  /** Serialises the exact little-endian 76-byte record layout. */
  static byte[] bytes(BackpackItem item) {
    StdItem std = item.item();
    ByteBuffer buffer = ByteBuffer.allocate(CLIENT_ITEM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    // String[20] ShortString slot: length byte, GBK payload, zero padding to offset 21.
    byte[] name = ByteStrings.fixedGbk(std.name(), StdItem.MAX_NAME_BYTES);
    buffer.put((byte) name.length);
    buffer.put(name);
    buffer.position(NAME_BYTES);
    buffer.put((byte) std.stdMode());
    buffer.put((byte) std.shape());
    buffer.put((byte) std.weight());
    buffer.put((byte) std.aniCount());
    buffer.put((byte) std.source());
    buffer.put((byte) 0); // reserved flag, only mutated by weapon upgrades
    buffer.put((byte) std.needIdentify());
    buffer.putShort((short) std.looks());
    buffer.putInt((int) std.duraMax());
    buffer.putInt((int) std.ac());
    buffer.putInt((int) std.mac());
    buffer.putInt((int) std.dc());
    buffer.putInt((int) std.mc());
    buffer.putInt((int) std.sc());
    buffer.putInt((int) std.need());
    buffer.putInt((int) std.needLevel());
    buffer.putInt((int) std.price());
    // Bytes 66..67 stay zero: alignment padding before the aligned Integer field.
    buffer.position(MAKE_INDEX_OFFSET);
    buffer.putInt(item.makeIndex());
    buffer.putShort((short) item.dura());
    buffer.putShort((short) item.duraMax());
    return buffer.array();
  }

  private static int u8(byte[] bytes, int offset) {
    return bytes[offset] & 0xff;
  }

  private static int u16(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
  }

  private static int i32(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
        | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
  }

  private static long u32(byte[] bytes, int offset) {
    return Integer.toUnsignedLong(i32(bytes, offset));
  }
}
