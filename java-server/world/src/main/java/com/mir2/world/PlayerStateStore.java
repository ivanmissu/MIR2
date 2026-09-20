package com.mir2.world;

import java.util.Optional;
import java.util.UUID;

/** Persistence port kept in the world module so the deterministic engine does not depend on JDBC. */
public interface PlayerStateStore {
  Optional<PlayerState> load(UUID characterId);

  /** Saves ability and the complete ordered backpack as one durable unit. */
  void save(PlayerState state);

  /**
   * Highest per-instance item make index ever persisted, so the engine can seed its
   * allocator and keep indexes unique across restarts. Transient stores return 0.
   */
  default long itemMakeIndexHighWater() {
    return 0L;
  }

  /** Used by isolated world tests and deployments that deliberately disable durable character state. */
  static PlayerStateStore none() {
    return new PlayerStateStore() {
      @Override
      public Optional<PlayerState> load(UUID characterId) {
        return Optional.empty();
      }

      @Override
      public void save(PlayerState state) {
        // Intentionally transient.
      }
    };
  }
}
