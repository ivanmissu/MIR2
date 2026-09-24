package com.mir2.shadowdiff;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The W30 two-player shadow runner: drives one server with <em>two</em> simultaneous client
 * sessions — the same three-gate chain twice, one account each — so the party protocol,
 * shared kills, PvP and death-side group cleanup can finally be scripted over the real wire.
 *
 * <p>Both sessions observe every op. The acting session's bucket holds its acks, the
 * messages the server pushed at it and its own snapshot; the other session contributes a
 * passive bucket ({@link ShadowSession#observe}) with the interest broadcasts it received
 * during the very same op. The result is two index-aligned observation streams — one per
 * player — each of which is fed to {@link ShadowDiff#compare} against the same player's
 * stream from the other server. A verdict requires both players to match; anything either
 * client could have noticed has to agree.
 *
 * <p>The two enter observations get the same treatment: "enter p1" produces a real bucket
 * for p1 and an empty pre-connect bucket for p2 (identical on every server by construction),
 * while "enter p2" gives p2 its entry bucket and p1 the drain of p2's appearance storm.
 */
final class DuoHarness {

  /** Two per-player observation streams, one entry per op (plus the two enters), aligned. */
  record DuoRun(List<OpObservation> primary, List<OpObservation> partner) {
    DuoRun {
      primary = List.copyOf(primary);
      partner = List.copyOf(partner);
      if (primary.size() != partner.size())
        throw new IllegalArgumentException("duo streams must stay index-aligned");
    }
  }

  /**
   * Runs the whole script against one server with two sessions and returns the aligned
   * per-player observation streams.
   *
   * @param account1  the primary account (p1, unprefixed script lines); its character is
   *                  named after the account, exactly like the solo harness
   * @param account2  the partner account (p2-prefixed lines)
   */
  static DuoRun observe(WireTarget target, List<Op> script, Duration settle,
      String account1, String password1, String account2, String password2, String serverName)
      throws IOException {
    List<OpObservation> primary = new ArrayList<>(script.size() + 2);
    List<OpObservation> partner = new ArrayList<>(script.size() + 2);
    try (ShadowSession p1 = new ShadowSession(target, account1, password1,
            account1, serverName, settle);
        ShadowSession p2 = new ShadowSession(target, account2, password2,
            account2, serverName, settle)) {
      // --- the two enters: every op (these included) produces one bucket per player ---
      primary.add(relabel(p1.enter(), "enter p1"));
      partner.add(p2.observe("enter p1")); // not connected yet: an empty, identical bucket
      primary.add(p1.observe("enter p2")); // p1 watches p2's appearance storm
      partner.add(relabel(p2.enter(), "enter p2"));

      for (Op op : script) {
        ShadowSession actor = op.isPartnerOp() ? p2 : p1;
        ShadowSession other = op.isPartnerOp() ? p1 : p2;
        OpObservation actorBucket = actor.perform(op);
        OpObservation otherBucket = other.observe(op.describe());
        if (op.isPartnerOp()) {
          partner.add(actorBucket);
          primary.add(otherBucket);
        } else {
          primary.add(actorBucket);
          partner.add(otherBucket);
        }
      }
    }
    return new DuoRun(primary, partner);
  }

  private static OpObservation relabel(OpObservation observation, String op) {
    return new OpObservation(op, observation.acks(), observation.messages(),
        observation.state());
  }

  private DuoHarness() {}
}
