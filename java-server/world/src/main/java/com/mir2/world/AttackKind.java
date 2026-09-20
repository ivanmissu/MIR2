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
  BIG_HIT;

  /** All melee variants share one cooldown in {@code TPlayObject.CheckActionInterval}. */
  public boolean sharesHitInterval() {
    return true;
  }
}
