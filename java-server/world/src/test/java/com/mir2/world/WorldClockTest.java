package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit contract of the three time sources (W23). */
class WorldClockTest {

  @Test
  void systemClockReadsTheHostClockAndIgnoresTickAdvances() {
    WorldClock clock = WorldClock.system();

    assertEquals(WorldClock.Mode.SYSTEM, clock.mode());
    assertFalse(clock.isVirtual());
    assertFalse(clock.advancesWithEngineTick());

    long before = System.currentTimeMillis();
    long observed = clock.millis();
    long after = System.currentTimeMillis();
    assertTrue(observed >= before && observed <= after,
        "a system clock must report host time, got " + observed);

    // Advancing is a no-op rather than an error: the engine calls it unconditionally.
    clock.advanceTicks(1_000);
    assertEquals(0, clock.ticks());
    assertTrue(clock.millis() >= observed);
  }

  @Test
  void virtualClockIsAPureFunctionOfTheTickCount() {
    WorldClock clock = WorldClock.virtual(5_000, 50);

    assertTrue(clock.isVirtual());
    assertTrue(clock.advancesWithEngineTick());
    assertEquals(5_000, clock.millis(), "an unticked virtual clock sits on its epoch");

    clock.advanceOneTick();
    assertEquals(5_050, clock.millis());

    clock.advanceTicks(19);
    assertEquals(20, clock.ticks());
    assertEquals(5_000 + 20 * 50, clock.millis());
  }

  @Test
  void twoVirtualClocksAgreeAfterTheSameNumberOfTicks() {
    // The whole premise of tick-derived time: two processes that have run the same number of
    // ticks read the same timestamp, no matter when or how fast they ran.
    WorldClock left = WorldClock.virtual(50);
    WorldClock right = WorldClock.virtual(50);

    for (int index = 0; index < 137; index++) left.advanceOneTick();
    right.advanceTicks(137);

    assertEquals(left.millis(), right.millis());
    assertEquals(left.ticks(), right.ticks());
  }

  @Test
  void manualClockDoesNotFollowTheEngineTick() {
    WorldClock clock = WorldClock.manual(50);

    assertTrue(clock.isVirtual(), "manual time is still tick-derived, not host time");
    assertFalse(clock.advancesWithEngineTick(),
        "the engine must never move a manual clock; the harness owns it");

    long frozen = clock.millis();
    assertEquals(frozen, clock.millis(), "time does not pass on its own");
    clock.advanceTicks(3);
    assertEquals(frozen + 150, clock.millis());
  }

  @Test
  void epochIsFarEnoughFromZeroToClearTheInitialCooldowns() {
    // TBaseObject.Initialize stamps its windows with the current tick and compares
    // `GetTickCount - stamp > interval`. A zero epoch would put world start inside every
    // cooldown at once, which is not what a real GetTickCount looks like.
    assertTrue(WorldClock.DEFAULT_EPOCH_MILLIS >= 60_000);
    assertEquals(WorldClock.DEFAULT_EPOCH_MILLIS, WorldClock.virtual(50).millis());
    assertEquals(WorldClock.DEFAULT_EPOCH_MILLIS, WorldClock.manual(50).millis());
  }

  @Test
  void invalidConfigurationsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> WorldClock.virtual(0));
    assertThrows(IllegalArgumentException.class, () -> WorldClock.manual(-1));
    assertThrows(IllegalArgumentException.class, () -> WorldClock.virtual(-1, 50));
    assertThrows(IllegalArgumentException.class, () -> WorldClock.virtual(50).advanceTicks(-1));
  }

  @Test
  void differentTickIntervalsProduceDifferentTimeForTheSameTickCount() {
    // Guards the pairing rule: both sides of a comparison must share the tick interval,
    // otherwise "same number of ticks" no longer means "same world time".
    WorldClock fast = WorldClock.virtual(10);
    WorldClock slow = WorldClock.virtual(50);
    fast.advanceTicks(10);
    slow.advanceTicks(10);
    assertNotEquals(fast.millis(), slow.millis());
  }
}
