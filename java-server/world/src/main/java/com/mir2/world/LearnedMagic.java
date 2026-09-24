package com.mir2.world;

import java.util.Objects;

/** A durable player-skill row paired with its immutable Magic.DB definition for outbound events. */
public record LearnedMagic(PlayerSkill skill, MagicDefinition definition) {
  public LearnedMagic {
    Objects.requireNonNull(skill, "skill");
    Objects.requireNonNull(definition, "definition");
    if (skill.magicId() != definition.id())
      throw new IllegalArgumentException("skill and definition ids differ");
  }
}
