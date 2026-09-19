package com.mir2.world;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Ability and bag contents that must survive a GAME socket reconnect. */
public record PlayerState(UUID characterId, Ability ability, List<BackpackItem> backpack) {
  /** Delphi {@code MAXBAGITEM}; equipped items are stored separately. */
  public static final int MAX_BACKPACK_ITEMS = 46;

  public PlayerState {
    Objects.requireNonNull(characterId, "characterId");
    Objects.requireNonNull(ability, "ability");
    if (ability.level() < 1) throw new IllegalArgumentException("player level must be at least one");
    backpack = List.copyOf(backpack);
    if (backpack.size() > MAX_BACKPACK_ITEMS) {
      throw new IllegalArgumentException("backpack exceeds " + MAX_BACKPACK_ITEMS + " items");
    }
    if (backpack.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("backpack must not contain null items");
    }
  }

  public static PlayerState initial(UUID characterId) {
    return new PlayerState(characterId, Ability.defaultPlayer(), List.of());
  }
}
