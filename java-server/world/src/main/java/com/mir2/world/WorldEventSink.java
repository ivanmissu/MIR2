package com.mir2.world;

/** Boundary implemented by a gate session; callbacks always originate on the world owner thread. */
@FunctionalInterface
public interface WorldEventSink {
  void send(WorldEvent event);
}
