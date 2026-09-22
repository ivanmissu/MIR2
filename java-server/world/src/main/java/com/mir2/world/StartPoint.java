package com.mir2.world;

import java.util.Objects;

/**
 * One {@code StartPoint.txt} entry: {@code pTStartPoint} in M2Share.pas, loaded by
 * {@code TLocalDB.LoadStartPoint} (LocalDB.pas:1579) into the global {@code g_StartPoint} list.
 *
 * <p>The file's columns are {@code map x y noChat range halo pkZone pkFire}; {@code range} is
 * the safe-zone radius around the point. Delphi falls back to {@code g_Config.nSafeZoneSize}
 * (10, !Setup.txt) when the column is absent.
 *
 * <p>Only the fields the engine currently enforces are modelled: the position and the radius
 * that {@code TBaseObject.InSafeZone} tests against.
 */
public record StartPoint(Position position, int safeZoneSize) {

  /** {@code g_Config.nSafeZoneSize}: the shipped default radius (!Setup.txt SafeZoneSize=10). */
  public static final int DEFAULT_SAFE_ZONE_SIZE = 10;

  public StartPoint {
    Objects.requireNonNull(position, "position");
    if (safeZoneSize < 0) throw new IllegalArgumentException("safe zone size must not be negative");
  }

  public StartPoint(Position position) {
    this(position, DEFAULT_SAFE_ZONE_SIZE);
  }
}
