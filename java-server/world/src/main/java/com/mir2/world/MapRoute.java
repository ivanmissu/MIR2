package com.mir2.world;

import java.util.Objects;

/**
 * A one-way map transition placed at one source cell.
 *
 * <p>This is the Java counterpart of {@code TMapManager.AddMapRoute}: when a player finishes a
 * move on {@link #sourcePosition()}, the world transfers it to {@link #destinationMapId()} and
 * {@link #destinationPosition()}. Door checks are deliberately performed by {@link WorldEngine},
 * because Delphi's {@code TBaseObject.Walk} checks {@code ArroundDoorOpened} at the source cell
 * immediately before entering the destination environment.
 */
public record MapRoute(
    String sourceMapId,
    Position sourcePosition,
    String destinationMapId,
    Position destinationPosition) {

  public MapRoute {
    sourceMapId = requireMapId(sourceMapId, "source map id");
    destinationMapId = requireMapId(destinationMapId, "destination map id");
    Objects.requireNonNull(sourcePosition, "source position");
    Objects.requireNonNull(destinationPosition, "destination position");
  }

  private static String requireMapId(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    return value.trim();
  }
}
