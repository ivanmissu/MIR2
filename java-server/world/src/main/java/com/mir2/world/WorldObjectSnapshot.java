package com.mir2.world;

import java.util.Objects;

/**
 * Immutable object state safe to pass from the world thread to transport threads.
 *
 * @param light the actor's light radius ({@code TBaseObject.m_nLight}, ObjBase.pas:169):
 *     0 for everything except a player wearing a right-hand item with durability left,
 *     which lights at 3. Carried in the high byte of the {@code Series} word the
 *     RM_TURN/RM_WALK/RM_RUN/SM_LOGON messages ship to the client
 *     ({@code MakeWord(btDir, m_nLight)}), where it sizes the fog hole the client
 *     punches around the actor.
 */
public record WorldObjectSnapshot(
    int id,
    String name,
    WorldObjectType type,
    String mapId,
    Position position,
    Direction direction,
    int feature,
    int status,
    int light,
    Ability ability) {

  public WorldObjectSnapshot(
      int id, String name, WorldObjectType type, String mapId, Position position, Direction direction) {
    this(id, name, type, mapId, position, direction, 0, 0, 0, Ability.defaultPlayer());
  }

  public WorldObjectSnapshot(
      int id, String name, WorldObjectType type, String mapId, Position position, Direction direction,
      int feature, int status) {
    this(id, name, type, mapId, position, direction, feature, status, 0, Ability.defaultPlayer());
  }

  public WorldObjectSnapshot(
      int id, String name, WorldObjectType type, String mapId, Position position, Direction direction,
      int feature, int status, Ability ability) {
    this(id, name, type, mapId, position, direction, feature, status, 0, ability);
  }

  public WorldObjectSnapshot {
    if (id <= 0) throw new IllegalArgumentException("object id must be positive");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("object name must not be blank");
    Objects.requireNonNull(type, "type");
    if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("map id must not be blank");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(ability, "ability");
    if (light < 0 || light > 0xff) throw new IllegalArgumentException("light must be a byte value");
  }

  public boolean alive() {
    return ability.alive();
  }
}
