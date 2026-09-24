package com.mir2.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mir2.world.LearnedMagic;
import com.mir2.world.MagicCatalog;
import com.mir2.world.PlayerSkill;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;

class MagicCodecTest {
  @Test
  void encodesTheAlignedEightyFourByteTClientMagicLayout() {
    PlayerSkill skill = new PlayerSkill(1, 2, 35, 'F');
    byte[] encoded = MagicCodec.encode(
        new LearnedMagic(skill, MagicCatalog.defaults().require(1)));
    ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);

    assertEquals(84, encoded.length);
    assertEquals('F', Byte.toUnsignedInt(buffer.get(0)));
    assertEquals(2, Byte.toUnsignedInt(buffer.get(1)));
    assertEquals(0, buffer.getShort(2), "CurTrain is aligned to an Integer boundary");
    assertEquals(35, buffer.getInt(4));
    assertEquals(1, Short.toUnsignedInt(buffer.getShort(8)), "TMagic.wMagicID");
    int nameLength = Byte.toUnsignedInt(buffer.get(10));
    assertEquals("火球术", new String(encoded, 11, nameLength, Charset.forName("GBK")));
    assertEquals(1, Byte.toUnsignedInt(buffer.get(23)), "btEffectType");
    assertEquals(1, Byte.toUnsignedInt(buffer.get(24)), "btEffect");
    assertEquals(4, Short.toUnsignedInt(buffer.getShort(26)), "wSpell");
    assertEquals(8, Short.toUnsignedInt(buffer.getShort(28)), "wPower");
    assertEquals(7, Byte.toUnsignedInt(buffer.get(30)), "TrainLevel[0]");
    assertEquals(20, buffer.getInt(36), "MaxTrain[0]");
    assertEquals(3, Byte.toUnsignedInt(buffer.get(52)), "btTrainLv");
    assertEquals(1, Byte.toUnsignedInt(buffer.get(53)), "btJob");
    assertEquals(60, buffer.getInt(56), "dwDelayTime");
    assertEquals(1, Byte.toUnsignedInt(buffer.get(60)), "btDefSpell");
    assertEquals(2, Byte.toUnsignedInt(buffer.get(61)), "btDefPower");
    assertEquals(8, Short.toUnsignedInt(buffer.getShort(62)), "wMaxPower");
    assertEquals(2, Byte.toUnsignedInt(buffer.get(64)), "btDefMaxPower");
    assertTrue(buffer.get(65) >= 0, "String[15] description begins at the fixed offset");
  }
}
