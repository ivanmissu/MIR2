package com.mir2.shadowdiff;

import java.util.List;
import java.util.Objects;

/**
 * What one op made observable on the wire: the acknowledgement verdicts (in order, tick
 * values dropped — they are wall-clock volatile), the server message idents that arrived
 * inside the settle window (names, in arrival order) and the state snapshot after the op.
 */
public record OpObservation(
    String op, List<String> acks, List<String> messages, StateSnapshot state) {

  public OpObservation {
    op = Objects.requireNonNull(op, "op");
    acks = List.copyOf(acks);
    messages = List.copyOf(messages);
    Objects.requireNonNull(state, "state");
  }
}
