package com.mir2.world;

import java.util.Objects;

/**
 * One MapInfo.txt connection point, mirroring {@code pTGateObj}: standing (walking or running)
 * onto {@code source} of {@code sourceMapId} teleports a player to
 * {@code destination} on {@code destinationMapId}. Registration itself happens through
 * {@link WorldEngine#addRoute}, which mirrors {@code TMapManager.AddMapRoute} by linking the
 * gate object into the source map's cell index.
 */
public record TeleportRoute(
    String sourceMapId,
    Position source,
    String destinationMapId,
    Position destination) {

  public TeleportRoute {
    sourceMapId = requireText(sourceMapId, "source map id");
    Objects.requireNonNull(source, "source");
    destinationMapId = requireText(destinationMapId, "destination map id");
    Objects.requireNonNull(destination, "destination");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    return value;
  }
}
