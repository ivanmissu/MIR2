package com.mir2.world;

import java.util.Optional;
import java.util.UUID;

/** Persistence port kept in the world module so the deterministic engine does not depend on JDBC. */
public interface PlayerStateStore {
  Optional<PlayerState> load(UUID characterId);

  /** Saves ability and the complete ordered backpack as one durable unit. */
  void save(PlayerState state);

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
