package com.mir2.world;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Pure, deterministic resolution for a group heal after range/relationship filtering. */
public final class AreaHealing {
  private AreaHealing() {}

  public record Result(int objectId, int amount, int hpAfter) {
    public Result {
      if (objectId < 0 || amount < 0 || hpAfter < 0) throw new IllegalArgumentException();
    }
  }

  /**
   * Resolves one heal per legal target. Full-health targets are omitted, while every other target
   * is clamped to max HP. Keeping this pure lets the world layer emit one event per result.
   */
  public static <T extends AreaTargetSelector.AreaTarget> List<Result> resolve(
      Position center, int radius, int power, Iterable<T> candidates,
      Predicate<? super T> legalTarget, java.util.function.ToIntFunction<T> hp,
      java.util.function.ToIntFunction<T> maxHp) {
    Objects.requireNonNull(hp, "hp");
    Objects.requireNonNull(maxHp, "maxHp");
    if (power < 0) throw new IllegalArgumentException("power must not be negative");
    return AreaTargetSelector.square(center, radius, candidates, legalTarget).stream()
        .map(target -> {
          int current = hp.applyAsInt(target);
          int maximum = maxHp.applyAsInt(target);
          if (current < 0 || maximum < 1 || current > maximum)
            throw new IllegalArgumentException("invalid target health");
          int amount = Math.min(power, maximum - current);
          return new Result(target.objectId(), amount, current + amount);
        })
        .filter(result -> result.amount() > 0)
        .toList();
  }
}
