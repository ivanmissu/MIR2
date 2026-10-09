package com.mir2.world;

import java.util.Objects;

/**
 * One map event object, the Java model of Delphi's {@code TFireBurnEvent} (Event.pas:61-66) as
 * spawned by {@code MagMakeFireCross} (Magic.pas:1135): a {@code SKILL_EARTHFIRE} (22 火墙)
 * cross arm that burns every living proper target standing on its cell.
 *
 * <p>Lifecycle mirrors the Delphi class: created with the caster as owner and a per-arm duration,
 * ticked by the engine's periodic pass (damage every {@code FIRE_WALL_TICK_MILLIS}, first tick
 * immediately because Delphi's redeclared {@code m_dwRunTick} starts at zero), expired when
 * {@code now - createdAt} passes the duration (strictly greater, Event.pas:278), and detached
 * from damage when the owner dies or leaves (Event.pas:282 clears {@code m_OwnBaseObject} on
 * ghost/death — the flames keep rendering until expiry, exactly as in Delphi).
 *
 * <p>Events are pure in-memory state: Delphi's {@code g_EventManager} never persists them either.
 */
public final class FireWallEvent {
  private final int id;
  private final GameMap map;
  private final Position position;
  /** {@code m_nDamage}: the shared per-tick damage of the whole cross (Magic.pas:1148-1164). */
  private final int damage;
  /** {@code m_OwnBaseObject}: the caster's object id; 0 once the owner died or left the world. */
  private int ownerId;
  /** {@code m_dwOpenStartTick}. */
  private final long createdAt;
  /** {@code m_dwContinueTime}: {@code nHTime * 1000} from MagMakeFireCross. */
  private final long durationMillis;
  /**
   * {@code m_dwRunTick} of the redeclared TFireBurnEvent field: 0 until the first damage tick,
   * which is what makes the first burn land on the first engine pass after creation.
   */
  private long lastDamageAt;

  public FireWallEvent(int id, GameMap map, Position position, int damage, int ownerId,
      long createdAt, long durationMillis) {
    if (id <= 0) throw new IllegalArgumentException("event id must be positive");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(position, "position");
    if (damage < 0) throw new IllegalArgumentException("damage must not be negative");
    if (ownerId <= 0) throw new IllegalArgumentException("owner id must be positive");
    if (createdAt < 0) throw new IllegalArgumentException("createdAt must not be negative");
    if (durationMillis < 0) throw new IllegalArgumentException("duration must not be negative");
    this.id = id;
    this.map = map;
    this.position = position;
    this.damage = damage;
    this.ownerId = ownerId;
    this.createdAt = createdAt;
    this.durationMillis = durationMillis;
  }

  public int id() {
    return id;
  }

  public GameMap map() {
    return map;
  }

  public Position position() {
    return position;
  }

  public int damage() {
    return damage;
  }

  public int ownerId() {
    return ownerId;
  }

  public long createdAt() {
    return createdAt;
  }

  public long durationMillis() {
    return durationMillis;
  }

  public long lastDamageAt() {
    return lastDamageAt;
  }

  /** {@code TEvent.Run}'s owner sweep: a dead or departed owner stops the fire from hurting. */
  public void clearOwner() {
    ownerId = 0;
  }

  /** Stamps the {@code TFireBurnEvent.Run} damage tick (Event.pas:241). */
  public void tickDamage(long now) {
    lastDamageAt = now;
  }
}
