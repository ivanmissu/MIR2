package com.mir2.shadowdiff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the CLI's value-less switches. This is a regression guard, not decoration: while
 * bringing up {@code --ai} the flag was not registered, so the parser treated it as a
 * key expecting a value, ate the following token and rejected
 * {@code --ai --monsters 4} with "unexpected argument: 4".
 */
class ShadowDiffMainArgsTest {

  @Test
  void valuelessFlagsDoNotSwallowTheFollowingArgument() {
    Map<String, String> options = ShadowDiffMain.Args.parse(
        new String[] {"--embedded", "--ai", "--monsters", "4", "--strict-messages"});

    assertEquals("true", options.get("ai"));
    assertEquals("true", options.get("embedded"));
    assertEquals("true", options.get("strict-messages"));
    assertEquals("4", options.get("monsters"), "--monsters must keep its own value");
  }

  @Test
  void everySwitchTheRunnerTreatsAsAFlagIsRegisteredAsOne() {
    // containsKey() checks in run()/runEmbedded() only work for registered flags.
    assertTrue(ShadowDiffMain.Args.FLAGS.containsAll(
        java.util.List.of("embedded", "help", "pve", "ai", "strict-messages")));
  }

  @Test
  void keyValueOptionsStillRequireAValue() {
    Map<String, String> options = ShadowDiffMain.Args.parse(
        new String[] {"--seed", "20260923", "--right-seed", "99999"});
    assertEquals("20260923", options.get("seed"));
    assertEquals("99999", options.get("right-seed"), "the negative control keeps its seed");

    assertThrows(IllegalArgumentException.class,
        () -> ShadowDiffMain.Args.parse(new String[] {"--seed"}));
    assertThrows(IllegalArgumentException.class,
        () -> ShadowDiffMain.Args.parse(new String[] {"garbage"}));
  }
}
