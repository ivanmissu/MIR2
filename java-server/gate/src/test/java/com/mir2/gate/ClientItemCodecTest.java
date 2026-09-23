package com.mir2.gate;

import com.mir2.protocol.SixBitCodec;
import com.mir2.world.BackpackItem;
import com.mir2.world.StdItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Byte-exact checks of the TClientItem wire layout against the Delphi field list in
 * Grobal2.pas (packed 66-byte TStdItem inside an unpacked record, MakeIndex aligned to
 * offset 68, total 76 bytes).
 */
class ClientItemCodecTest {

  @Test
  void encodesThe76ByteDelphiLayoutByteForByte() {
    StdItem sword = com.mir2.world.StdItems.woodenSword();
    BackpackItem item = new BackpackItem(sword, 0x1234, 20, 20);

    byte[] bytes = ClientItemCodec.bytes(item);
    assertEquals(ClientItemCodec.CLIENT_ITEM_BYTES, bytes.length);

    // String[20] slot: length byte, GBK payload (木=C4BE 剑=BDA3), zero padding to 21.
    assertEquals(4, bytes[0]);
    assertEquals((byte) 0xC4, bytes[1]);
    assertEquals((byte) 0xBE, bytes[2]);
    assertEquals((byte) 0xBD, bytes[3]);
    assertEquals((byte) 0xA3, bytes[4]);
    for (int pad = 5; pad < 21; pad++) assertEquals(0, bytes[pad], "name slot padding at " + pad);

    // Field expectations are the authoritative 1.76 StdItems.DB row for 木剑 (W18 import):
    // StdMode 5, Shape 1, Weight 4, AniCount 0, Looks 30, DuraMax 4000, DC 2-5, NeedLevel 1, Price 50.
    assertEquals(5, u8(bytes, ClientItemCodec.STD_MODE_OFFSET));      // StdMode: weapon
    assertEquals(1, u8(bytes, ClientItemCodec.SHAPE_OFFSET));
    assertEquals(4, u8(bytes, ClientItemCodec.WEIGHT_OFFSET));
    assertEquals(0, u8(bytes, ClientItemCodec.ANI_COUNT_OFFSET));
    assertEquals(0, bytes[ClientItemCodec.SOURCE_OFFSET]);
    assertEquals(0, u8(bytes, ClientItemCodec.RESERVED_OFFSET));
    assertEquals(0, u8(bytes, ClientItemCodec.NEED_IDENTIFY_OFFSET));
    assertEquals(30, u16(bytes, ClientItemCodec.LOOKS_OFFSET));
    assertEquals(4000, u32(bytes, ClientItemCodec.DURA_MAX_OFFSET));   // template DuraMax dword
    assertEquals(0, u32(bytes, ClientItemCodec.AC_OFFSET));
    assertEquals(0, u32(bytes, ClientItemCodec.MAC_OFFSET));
    // DC is packed MakeLong(min=2, max=5) the way ItmUnit.pas GetItemAddValue does.
    assertEquals(0x0005_0002L, u32(bytes, ClientItemCodec.DC_OFFSET));
    assertEquals(0, u32(bytes, ClientItemCodec.MC_OFFSET));
    assertEquals(0, u32(bytes, ClientItemCodec.SC_OFFSET));
    assertEquals(0, u32(bytes, ClientItemCodec.NEED_OFFSET));
    assertEquals(1, u32(bytes, ClientItemCodec.NEED_LEVEL_OFFSET));
    assertEquals(50, u32(bytes, ClientItemCodec.PRICE_OFFSET));
    // Alignment padding between the packed TStdItem and the aligned MakeIndex Integer.
    assertEquals(0, bytes[66]);
    assertEquals(0, bytes[67]);
    assertEquals(0x1234, i32(bytes, ClientItemCodec.MAKE_INDEX_OFFSET));
    assertEquals(20, u16(bytes, ClientItemCodec.DURA_OFFSET));
    assertEquals(20, u16(bytes, ClientItemCodec.DURA_MAX_INSTANCE_OFFSET));
  }

  @Test
  void roundTripsThroughTheSixBitEncodedBody() {
    BackpackItem item = new BackpackItem(com.mir2.world.StdItems.woodenSword(), 77, 7, 20);
    BackpackItem decoded = ClientItemCodec.decode(ClientItemCodec.encode(item));
    assertEquals(item, decoded);
  }

  @Test
  void packsAFullTwentyByteGbkNameWithoutSplittingACharacter() {
    // Ten CJK characters fill the String[20] slot completely; the model never allows more,
    // and the codec's fixedGbk guard would cut at the character boundary if it ever did.
    StdItem fullNamed = new StdItem("一一一一一一一一一一", 1, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    BackpackItem item = new BackpackItem(fullNamed, 1, 0, 0);
    byte[] bytes = ClientItemCodec.bytes(item);
    assertEquals(20, bytes[0]);
    // A 20-byte name fills the payload bytes 1..20 completely (一 in GBK is D2BB).
    assertEquals((byte) 0xBB, bytes[20], "the last name byte ends the slot");
    assertEquals(1, u8(bytes, ClientItemCodec.STD_MODE_OFFSET), "StdMode starts right after the slot");
    assertEquals(fullNamed.name(), ClientItemCodec.decode(new String(
        SixBitCodec.encode(bytes), StandardCharsets.ISO_8859_1)).name());
  }

  @Test
  void truncatesInstanceDuraMaxToTheTemplateWord() {
    // Delphi assigns Dura/DuraMax from the dword template with an implicit Word truncation.
    StdItem huge = new StdItem("神兵", 1, 0, 1, 0, 0, 0, 0, 0x1_0000 + 9, 0, 0, 0, 0, 0, 0, 0, 0);
    BackpackItem item = BackpackItem.of(huge, 5);
    assertEquals(9, item.duraMax());
    assertEquals(9, ClientItemCodec.decode(ClientItemCodec.encode(item)).duraMax());
  }

  @Test
  void joinsBagBodiesWithTheDelphiTrailingSeparator() {
    BackpackItem first = new BackpackItem(com.mir2.world.StdItems.woodenSword(), 1, 20, 20);
    BackpackItem second = BackpackItem.of(com.mir2.world.StdItems.chickenMeat(), 2);
    assertEquals(
        ClientItemCodec.encode(first) + "/" + ClientItemCodec.encode(second) + "/",
        ClientItemCodec.encodeBag(List.of(first, second)));
    assertEquals("", ClientItemCodec.encodeBag(List.of()));
  }

  @Test
  void rejectsBodiesThatDoNotDecodeTo76Bytes() {
    assertThrows(IllegalArgumentException.class,
        () -> ClientItemCodec.decode(ClientItemCodec.encode(new BackpackItem(
            com.mir2.world.StdItems.woodenSword(), 1, 0, 0)).substring(0, 8)));
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
