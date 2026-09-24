package com.mir2.world;

/**
 * The observable result of a {@code CM_MERCHANTDLGSELECT} label selection
 * ({@link WorldEngine#selectMerchantLabel}). Delphi's {@code TMerchant.UserSelect} answers no
 * {@code +GOOD}/{@code +FAIL} for the merchant family, so this value never reaches the wire; it
 * exists purely so callers (tests, shadowdiff, logs) can tell the four cases apart instead of the
 * old opaque boolean.
 */
public enum MerchantSelectOutcome {
  /** {@code @repair} / {@code @s_repair} opened the repair dialog ({@code SM_SENDUSERREPAIR}). */
  REPAIR_DIALOG,
  /** {@code @exit} closed the merchant dialog ({@code SM_MERCHANTDLGCLOSE}). */
  DIALOG_CLOSED,
  /**
   * A recognized-but-deferred or entirely unknown label — rejected observably via
   * {@link WorldEvent.MerchantActionRejected} instead of being silently stored.
   */
  REJECTED,
  /**
   * Not an {@code @}-prefixed label, or the player is dead — {@code UserSelect} is a no-op, exactly
   * as in Delphi. No event, no state change.
   */
  IGNORED
}
