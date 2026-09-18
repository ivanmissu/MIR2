package com.mir2.character;

import java.util.UUID;

public record Character(UUID id, String account, String name, int job, int level) {
  public Character { if (name == null || name.isBlank() || level < 1) throw new IllegalArgumentException("invalid character"); }
}
