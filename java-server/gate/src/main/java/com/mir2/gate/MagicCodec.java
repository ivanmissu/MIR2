package com.mir2.gate;

import com.mir2.world.LearnedMagic;
import com.mir2.world.MagicDefinition;
import com.mir2.world.PlayerSkill;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.List;

/** Exact Delphi-6, 4-byte-aligned {@code TClientMagic} encoder ({@code SizeOf = 84}). */
public final class MagicCodec {
  public static final int CLIENT_MAGIC_SIZE = 84;
  private static final Charset GBK = Charset.forName("GBK");

  private MagicCodec() {}

  public static byte[] encode(LearnedMagic learned) {
    PlayerSkill skill = learned.skill();
    MagicDefinition magic = learned.definition();
    ByteBuffer buffer = ByteBuffer.allocate(CLIENT_MAGIC_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    buffer.put(0, (byte) skill.key());                    // Char Key
    buffer.put(1, (byte) skill.level());                  // Byte Level
    buffer.putInt(4, skill.trainingPoints());             // Integer CurTrain

    int base = 8;                                         // TMagic Def
    buffer.putShort(base, (short) magic.id());             // Word wMagicID
    putShortString(buffer, base + 2, 12, magic.name());    // String[12]
    buffer.put(base + 15, (byte) magic.effectType());
    buffer.put(base + 16, (byte) magic.effect());
    buffer.putShort(base + 18, (short) magic.spell());
    buffer.putShort(base + 20, (short) magic.power());
    List<Integer> levels = magic.trainLevels();
    buffer.put(base + 22, (byte) levels.get(0).intValue());
    buffer.put(base + 23, (byte) levels.get(1).intValue());
    buffer.put(base + 24, (byte) levels.get(2).intValue());
    buffer.put(base + 25, (byte) levels.get(2).intValue()); // terminal level repeats NeedL3
    List<Integer> training = magic.maxTrain();
    buffer.putInt(base + 28, training.get(0));
    buffer.putInt(base + 32, training.get(1));
    buffer.putInt(base + 36, training.get(2));
    buffer.putInt(base + 40, training.get(2));
    buffer.put(base + 44, (byte) MagicDefinition.MAX_SKILL_LEVEL);
    buffer.put(base + 45, (byte) magic.job());
    buffer.putInt(base + 48, (int) magic.delayMillis());
    buffer.put(base + 52, (byte) magic.defSpell());
    buffer.put(base + 53, (byte) magic.defPower());
    buffer.putShort(base + 54, (short) magic.maxPower());
    buffer.put(base + 56, (byte) magic.defMaxPower());
    putShortString(buffer, base + 57, 15, magic.description());
    return buffer.array();
  }

  private static void putShortString(ByteBuffer buffer, int offset, int capacity, String value) {
    byte[] encoded = value.getBytes(GBK);
    int length = Math.min(capacity, encoded.length);
    buffer.put(offset, (byte) length);
    for (int index = 0; index < length; index++) buffer.put(offset + 1 + index, encoded[index]);
  }
}
