package com.mir2.world;

/**
 * Melee attack variants of the W03 slice.
 *
 * <p>Delphi validates all of {@code CM_HIT/CM_HEAVYHIT/CM_BIGHIT/CM_POWERHIT/CM_WIDEHIT/CM_FIREHIT}
 * against the single {@code CM_HIT} action interval (ObjBase.pas), so the interval key is shared
 * while the response ident differs per kind.
 */
public enum AttackKind {
  /** Normal swing (CM_HIT → SM_HIT). */
  HIT,
  /** Heavy weapon swing (CM_HEAVYHIT → SM_HEAVYHIT). */
  HEAVY_HIT,
  /** Wide swing (CM_BIGHIT → SM_BIGHIT). */
  BIG_HIT,
  /**
   * 攻杀剑术 swing (CM_POWERHIT → SM_SPELL2). {@code TPlayObject.ClientAttack} maps
   * {@code CM_POWERHIT} to {@code wHitMode = 3}, and {@code AttackDir} only answers
   * {@code RM_SPELL2} when {@code m_boPowerHit} was armed by the cadence — an unarmed
   * CM_POWERHIT is broadcast as a plain {@link #HIT}.
   */
  POWER_HIT,
  /**
   * 刺杀剑术 swing (CM_LONGHIT → SM_LONGHIT). {@code ClientAttack} maps {@code CM_LONGHIT}
   * to {@code wHitMode = 4}, whose {@code _Attack} branch runs {@code SwordLongAttack} against
   * the cell two tiles ahead (ObjBase.pas:22005). {@code AttackDir} only answers
   * {@code RM_LONGHIT} when {@code m_MagicErgumSkill <> nil} (the book was read); an unlearned
   * CM_LONGHIT degrades to a plain {@link #HIT}.
   */
  LONG_HIT,
  /**
   * 半月弯刀 swing (CM_WIDEHIT → SM_WIDEHIT). {@code ClientAttack} maps {@code CM_WIDEHIT}
   * to {@code wHitMode = 5}, whose {@code _Attack} branch runs {@code SwordWideAttack} across the
   * {@code g_Config.WideAttack} fan (ObjBase.pas:22028). {@code AttackDir} spends mana up front
   * ({@code DamageSpell}) and only answers {@code RM_WIDEHIT} when the skill is learned and
   * {@code m_WAbil.MP > 0}; a manaless or unlearned CM_WIDEHIT degrades to a plain {@link #HIT}.
   */
  WIDE_HIT,
  /**
   * 烈火剑法 swing (CM_FIREHIT → SM_FIREHIT). {@code ClientAttack} maps {@code CM_FIREHIT} to
   * {@code wHitMode = 7} (ObjBase.pas:8857). Unlike 刺杀/半月 it has no extra geometry: the
   * {@code _Attack} branch (ObjBase.pas:22128) consumes the armed {@code m_boFireHitSkill},
   * restamps {@code m_dwLatestFireHitTick} and raises the single front blow by
   * {@code Round(nPower / 100 * (m_nHitDouble * 10))}. {@code AttackDir} only answers
   * {@code RM_FIREHIT} when the flag was armed at swing time; an unarmed CM_FIREHIT degrades to
   * a plain {@link #HIT} (and still burns the flag if one was set — Jacky's 防止砍空刀刀烈火
   * guard in the no-target branch, ObjBase.pas:22152).
   */
  FIRE_HIT;

  /** All melee variants share one cooldown in {@code TPlayObject.CheckActionInterval}. */
  public boolean sharesHitInterval() {
    return true;
  }
}
