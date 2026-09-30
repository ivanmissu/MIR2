package com.mir2.world;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Deterministic geometry shared by area spells.
 *
 * <p>The selector deliberately knows nothing about players, monsters or NPCs. Callers provide
 * the legal-target predicate, so an area heal cannot accidentally reuse hostile-spell filtering.
 * Results are stable across the two shadowdiff worlds: distance first, then object id.
 */
public final class AreaTargetSelector {
  private AreaTargetSelector() {}

  /** Selects objects in the inclusive Chebyshev square around {@code center}. */
  public static <T extends AreaTarget> List<T> square(
      Position center, int radius, Iterable<T> candidates, Predicate<? super T> legalTarget) {
    Objects.requireNonNull(center, "center");
    Objects.requireNonNull(candidates, "candidates");
    Objects.requireNonNull(legalTarget, "legalTarget");
    if (radius < 0) throw new IllegalArgumentException("radius must not be negative");

    return java.util.stream.StreamSupport.stream(candidates.spliterator(), false)
        .filter(Objects::nonNull)
        .filter(legalTarget)
        .filter(target -> target.position().distanceTo(center) <= radius)
        .sorted(Comparator.comparingInt((T target) -> target.position().distanceTo(center))
            .thenComparingInt(AreaTarget::objectId))
        .toList();
  }

  /** Minimal view required by range selection; production objects can implement this directly. */
  public interface AreaTarget {
    int objectId();
    Position position();
  }
}
