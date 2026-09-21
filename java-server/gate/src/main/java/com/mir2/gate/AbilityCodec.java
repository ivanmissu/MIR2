package com.mir2.gate;

import com.mir2.protocol.SixBitCodec;
import com.mir2.world.Ability;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Wire codec for the packed Delphi {@code TAbility} record (Common/Grobal2.pas:734), the body
 * of {@code SM_ABILITY}.
 *
 * <p>The record is {@code packed} and the source annotates it as 50 bytes: one Word level,
 * five DWord stat ranges, four Words of HP/MP, two DWords of experience and six Words of
 * weight limits. Delphi stores AC/MAC/DC/MC/SC as {@code MakeLong(min, max)} — low word is
 * the lower bound, high word the upper.
 */
public final class AbilityCodec {
  /** {@code SizeOf(TAbility)} as annotated in Grobal2.pas. */
  public static final int ABILITY_BYTES = 50;

  private AbilityCodec() {}

  public static String encode(Ability ability) {
    return new String(SixBitCodec.encode(bytes(ability)), StandardCharsets.ISO_8859_1);
  }

  /** Serialises the exact little-endian field order of the packed record. */
  static byte[] bytes(Ability ability) {
    ByteBuffer buffer = ByteBuffer.allocate(ABILITY_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putShort((short) ability.level());
    buffer.putInt(packRange(ability.minAc(), ability.maxAc()));
    // MAC/MC/SC are not modelled by the current combat slice and stay zero.
    buffer.putInt(0);
    buffer.putInt(packRange(ability.minDc(), ability.maxDc()));
    buffer.putInt(0);
    buffer.putInt(0);
    buffer.putShort((short) ability.hp());
    buffer.putShort((short) ability.mp());
    buffer.putShort((short) ability.maxHp());
    buffer.putShort((short) ability.maxMp());
    buffer.putInt((int) Math.min(ability.experience(), 0xFFFFFFFFL));
    // MaxExp belongs to the level table, which has not been migrated yet.
    buffer.putInt(0);
    // Weight/MaxWeight/WearWeight/MaxWearWeight/HandWeight/MaxHandWeight are reported
    // separately through SM_WEIGHTCHANGED, so this body leaves them zero.
    buffer.putShort((short) 0);
    buffer.putShort((short) 0);
    buffer.putShort((short) 0);
    buffer.putShort((short) 0);
    buffer.putShort((short) 0);
    buffer.putShort((short) 0);
    return buffer.array();
  }

  /** {@code MakeLong(min, max)}: low word lower bound, high word upper bound. */
  private static int packRange(int min, int max) {
    return ((max & 0xffff) << 16) | (min & 0xffff);
  }
}
