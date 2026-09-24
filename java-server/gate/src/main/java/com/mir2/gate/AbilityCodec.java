package com.mir2.gate;

import com.mir2.protocol.SixBitCodec;
import com.mir2.world.Ability;
import com.mir2.world.LevelAbilities;
import com.mir2.world.WeightLimits;
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
    return encode(ability, WeightLimits.forLevel(LevelAbilities.JOB_WARRIOR, ability.level()));
  }

  public static String encode(Ability ability, WeightLimits weights) {
    return new String(SixBitCodec.encode(bytes(ability, weights)), StandardCharsets.ISO_8859_1);
  }

  static byte[] bytes(Ability ability) {
    return bytes(ability, WeightLimits.forLevel(LevelAbilities.JOB_WARRIOR, ability.level()));
  }

  /** Serialises the exact little-endian field order of the packed record. */
  static byte[] bytes(Ability ability, WeightLimits weights) {
    ByteBuffer buffer = ByteBuffer.allocate(ABILITY_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putShort((short) ability.level());
    buffer.putInt(packRange(ability.minAc(), ability.maxAc()));
    buffer.putInt(packRange(ability.minMac(), ability.maxMac()));
    buffer.putInt(packRange(ability.minDc(), ability.maxDc()));
    buffer.putInt(packRange(ability.minMc(), ability.maxMc()));
    buffer.putInt(packRange(ability.minSc(), ability.maxSc()));
    buffer.putShort((short) ability.hp());
    buffer.putShort((short) ability.mp());
    buffer.putShort((short) ability.maxHp());
    buffer.putShort((short) ability.maxMp());
    buffer.putInt((int) Math.min(ability.experience(), 0xFFFFFFFFL));
    // MaxExp = GetLevelExp(Level) (ObjBase.pas:19184). The shipped table tops out at
    // 4,000,000,000, which is an unsigned DWord on the wire and overflows a signed int —
    // the cast below reproduces the same 32 bits Delphi writes.
    buffer.putInt((int) (ability.maxExperience() & 0xFFFFFFFFL));
    // SM_WEIGHTCHANGED only refreshes the three current totals (ClMain.pas:4473), so the
    // maxima can only reach the client here. They must not stay zero: FState.pas:3646 hides
    // the bottom status bars unless (MaxExp > 0) and (MaxWeight > 0), and Actor.pas:2633
    // divides by MaxWeight/MaxWearWeight/MaxHandWeight to shade the overload icons.
    buffer.putShort((short) clampWord(weights.weight()));
    buffer.putShort((short) clampWord(weights.maxWeight()));
    buffer.putShort((short) clampWord(weights.wearWeight()));
    buffer.putShort((short) clampWord(weights.maxWearWeight()));
    buffer.putShort((short) clampWord(weights.handWeight()));
    buffer.putShort((short) clampWord(weights.maxHandWeight()));
    return buffer.array();
  }

  /** {@code MakeLong(min, max)}: low word lower bound, high word upper bound. */
  private static int packRange(int min, int max) {
    return ((max & 0xffff) << 16) | (min & 0xffff);
  }

  /** The weight fields are {@code Word}s; Delphi saturates rather than wrapping. */
  private static int clampWord(int value) {
    return Math.max(0, Math.min(value, 0xffff));
  }
}
