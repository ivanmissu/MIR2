package com.mir2.world;

import java.util.Objects;
import java.util.Optional;

/** Completion result of a melee attack command executed on a world tick. */
public record AttackResult(
    boolean accepted,
    WorldObjectSnapshot attacker,
    WorldObjectSnapshot victim,
    int damage,
    WorldEvent.AttackRejection rejection) {

  public AttackResult {
    Objects.requireNonNull(attacker, "attacker");
    if (accepted == (rejection != null))
      throw new IllegalArgumentException("an attack must have either success or a rejection");
    if (damage < 0) throw new IllegalArgumentException("damage must not be negative");
    if (victim == null && damage != 0) throw new IllegalArgumentException("damage requires a victim");
  }

  AttackResult(boolean accepted, WorldObjectSnapshot attacker, WorldObjectSnapshot victim, int damage) {
    this(accepted, attacker, victim, damage, null);
  }

  /** True when the swing was allowed but no living object stood in front of the attacker. */
  public boolean hitNothing() {
    return accepted && victim == null;
  }

  public Optional<WorldObjectSnapshot> victimSnapshot() {
    return Optional.ofNullable(victim);
  }

  public Optional<WorldEvent.AttackRejection> rejectionReason() {
    return Optional.ofNullable(rejection);
  }

  static AttackResult missed(WorldObjectSnapshot attacker) {
    return new AttackResult(true, attacker, null, 0, null);
  }

  static AttackResult rejected(WorldObjectSnapshot attacker, WorldEvent.AttackRejection reason) {
    return new AttackResult(false, attacker, null, 0, Objects.requireNonNull(reason));
  }
}
