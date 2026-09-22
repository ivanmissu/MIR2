package com.mir2.gate;

import com.mir2.world.Ability;
import com.mir2.world.LevelAbilities;
import com.mir2.world.WeightLimits;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six weight {@code Word}s at the tail of the packed {@code TAbility} record
 * (Grobal2.pas:733). They used to ship as zeroes, which blanked the client's bottom status
 * bars: FState.pas:3646 only draws them when {@code (MaxExp > 0) and (MaxWeight > 0)}.
 */
class AbilityWeightBlockTest {
  private static final int WEIGHT_OFFSET = 38;
  private static final int MAX_WEIGHT_OFFSET = 40;
  private static final int WEAR_WEIGHT_OFFSET = 42;
  private static final int MAX_WEAR_WEIGHT_OFFSET = 44;
  private static final int HAND_WEIGHT_OFFSET = 46;
  private static final int MAX_HAND_WEIGHT_OFFSET = 48;

  @Test
  void theWeightBlockIsSerialisedAtTheDelphiOffsets() {
    ByteBuffer body = ByteBuffer
        .wrap(AbilityCodec.bytes(Ability.defaultPlayer(), new WeightLimits(7, 60, 5, 18, 3, 14)))
        .order(ByteOrder.LITTLE_ENDIAN);

    assertEquals(50, AbilityCodec.ABILITY_BYTES, "SizeOf(TAbility)");
    assertEquals(7, body.getShort(WEIGHT_OFFSET), "TAbility.Weight");
    assertEquals(60, body.getShort(MAX_WEIGHT_OFFSET), "TAbility.MaxWeight");
    assertEquals(5, body.getShort(WEAR_WEIGHT_OFFSET), "TAbility.WearWeight");
    assertEquals(18, body.getShort(MAX_WEAR_WEIGHT_OFFSET), "TAbility.MaxWearWeight");
    assertEquals(3, body.getShort(HAND_WEIGHT_OFFSET), "TAbility.HandWeight");
    assertEquals(14, body.getShort(MAX_HAND_WEIGHT_OFFSET), "TAbility.MaxHandWeight");
  }

  @Test
  void aFreshWarriorReportsTheShippedLevelOneLimits() {
    // RecalcLevelAbilitys, jWarr, level 1: MaxWeight = 50 + Round(1/3), MaxWearWeight =
    // 15 + Round(1/20), MaxHandWeight = 12 + Round(1/13) — i.e. the bare 50/15/12.
    WeightLimits limits = WeightLimits.forLevel(LevelAbilities.JOB_WARRIOR, 1);
    assertEquals(50, limits.maxWeight());
    assertEquals(15, limits.maxWearWeight());
    assertEquals(12, limits.maxHandWeight());
    assertEquals(0, limits.weight());

    ByteBuffer body = ByteBuffer.wrap(AbilityCodec.bytes(Ability.defaultPlayer()))
        .order(ByteOrder.LITTLE_ENDIAN);
    assertTrue(body.getShort(MAX_WEIGHT_OFFSET) > 0,
        "a zero MaxWeight hides the client's bottom status bars (FState.pas:3646)");
    assertTrue(body.getShort(MAX_WEAR_WEIGHT_OFFSET) > 0);
    assertTrue(body.getShort(MAX_HAND_WEIGHT_OFFSET) > 0,
        "Actor.pas:2641 divides by MaxHandWeight");
  }

  @Test
  void theHealthPoolsStillOccupyTheirOwnOffsets() {
    // Regression guard: the weight block must not have shifted HP/MP, which the client reads
    // to draw the red and blue globes (FState.pas:3608 needs MaxHP > 0 and MaxMP > 0).
    ByteBuffer body = ByteBuffer.wrap(AbilityCodec.bytes(Ability.defaultPlayer()))
        .order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(15, body.getShort(22), "TAbility.HP");
    assertEquals(15, body.getShort(24), "TAbility.MP");
    assertEquals(15, body.getShort(26), "TAbility.MaxHP");
    assertEquals(15, body.getShort(28), "TAbility.MaxMP");
  }

  @Test
  void oversizedLimitsSaturateInsteadOfWrappingTheWord() {
    ByteBuffer body = ByteBuffer
        .wrap(AbilityCodec.bytes(Ability.defaultPlayer(),
            new WeightLimits(0, 70_000, 0, 70_000, 0, 70_000)))
        .order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0xffff, Short.toUnsignedInt(body.getShort(MAX_WEIGHT_OFFSET)));
    assertEquals(0xffff, Short.toUnsignedInt(body.getShort(MAX_WEAR_WEIGHT_OFFSET)));
    assertEquals(0xffff, Short.toUnsignedInt(body.getShort(MAX_HAND_WEIGHT_OFFSET)));
  }
}
