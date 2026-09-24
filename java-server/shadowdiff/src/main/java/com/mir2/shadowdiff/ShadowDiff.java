package com.mir2.shadowdiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compares the two observation streams op by op. Three severities per op:
 *
 * <ul>
 *   <li><b>STATE</b> — the post-op {@link StateSnapshot}s differ. This is the harness's
 *       reason to exist: the same operation stream produced different player-observable
 *       state on the two servers.</li>
 *   <li><b>ACKS</b> — the +GOOD/+FAIL verdict sequences differ. State may still converge
 *       (e.g. both refuse a move but one also re-syncs), yet a verdict flip is exactly the
 *       kind of behavioural drift 对拍 must surface.</li>
 *   <li><b>MESSAGES</b> — only the server-message ident multiset differs (order is
 *       normalised because interest broadcasts interleave nondeterministically). Reported
 *       as informational unless {@code strictMessages} upgrades it to a failure.</li>
 * </ul>
 */
public final class ShadowDiff {

  public enum Severity {MATCH, MESSAGES, ACKS, STATE}

  /** One op's comparison verdict. */
  public record Entry(
      int index,
      String op,
      Severity severity,
      List<String> details,
      OpObservation left,
      OpObservation right) {
    public Entry {
      details = List.copyOf(details);
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(right, "right");
    }
  }

  public record Result(
      String leftLabel, String rightLabel, List<Entry> entries, boolean strictMessages) {
    public Result {
      entries = List.copyOf(entries);
    }

    public long count(Severity severity) {
      return entries.stream().filter(entry -> entry.severity() == severity).count();
    }

    /** PASS = no state or ack drift, and no message drift when strict. */
    public boolean passed() {
      return count(Severity.STATE) == 0 && count(Severity.ACKS) == 0
          && (!strictMessages || count(Severity.MESSAGES) == 0);
    }
  }

  private ShadowDiff() {}

  public static Result compare(String leftLabel, String rightLabel,
      List<OpObservation> left, List<OpObservation> right, boolean strictMessages) {
    if (left.size() != right.size()) {
      throw new IllegalArgumentException("observation streams differ in length: "
          + left.size() + " vs " + right.size() + " — the harness must replay one script");
    }
    List<Entry> entries = new ArrayList<>(left.size());
    for (int index = 0; index < left.size(); index++) {
      entries.add(compareOp(index, left.get(index), right.get(index)));
    }
    return new Result(leftLabel, rightLabel, entries, strictMessages);
  }

  private static Entry compareOp(int index, OpObservation left, OpObservation right) {
    List<String> details = new ArrayList<>();

    boolean stateDiffers = !left.state().equals(right.state());
    if (stateDiffers) describeStateDiff(left.state(), right.state(), details);

    boolean acksDiffer = !left.acks().equals(right.acks());
    if (acksDiffer) {
      details.add("acks: " + left.acks() + " vs " + right.acks());
    }

    boolean messagesDiffer = !sortedCopy(left.messages()).equals(sortedCopy(right.messages()));
    if (messagesDiffer) {
      details.add("messages: " + left.messages() + " vs " + right.messages());
    }

    Severity severity = stateDiffers ? Severity.STATE
        : acksDiffer ? Severity.ACKS
        : messagesDiffer ? Severity.MESSAGES
        : Severity.MATCH;
    return new Entry(index, left.op(), severity, details, left, right);
  }

  private static void describeStateDiff(StateSnapshot a, StateSnapshot b, List<String> out) {
    if (!a.mapId().equals(b.mapId())) out.add("map: " + a.mapId() + " vs " + b.mapId());
    if (a.x() != b.x() || a.y() != b.y()) {
      out.add("cell: (" + a.x() + "," + a.y() + ") vs (" + b.x() + "," + b.y() + ")");
    }
    if (a.direction() != b.direction()) {
      out.add("direction: " + a.direction() + " vs " + b.direction());
    }
    if (a.hp() != b.hp() || a.maxHp() != b.maxHp()) {
      out.add("hp: " + a.hp() + "/" + a.maxHp() + " vs " + b.hp() + "/" + b.maxHp());
    }
    if (a.mp() != b.mp() || a.maxMp() != b.maxMp()) {
      out.add("mp: " + a.mp() + "/" + a.maxMp() + " vs " + b.mp() + "/" + b.maxMp());
    }
    if (a.level() != b.level()) out.add("level: " + a.level() + " vs " + b.level());
    if (a.experience() != b.experience()) {
      out.add("exp: " + a.experience() + " vs " + b.experience());
    }
    if (a.gold() != b.gold()) out.add("gold: " + a.gold() + " vs " + b.gold());
    if (!a.neighbours().equals(b.neighbours())) {
      out.add("near: " + a.neighbours() + " vs " + b.neighbours());
    }
    if (a.worldTime() != b.worldTime()) {
      out.add("worldTime: " + a.worldTime() + " vs " + b.worldTime());
    }
    if (!a.combat().equals(b.combat())) {
      out.add("combat: " + a.combat() + " vs " + b.combat());
    }
    if (!a.bagItems().equals(b.bagItems())) {
      out.add("bag: " + a.bagItems() + " vs " + b.bagItems());
    }
    if (!a.wornItems().equals(b.wornItems())) {
      out.add("worn: " + a.wornItems() + " vs " + b.wornItems());
    }
    if (!a.groupMembers().equals(b.groupMembers())) {
      out.add("group: " + a.groupMembers() + " vs " + b.groupMembers());
    }
    if (a.nameColor() != b.nameColor()) {
      out.add("nameColor: " + a.nameColor() + " vs " + b.nameColor());
    }
    if (!a.groundItems().equals(b.groundItems())) {
      out.add("ground: " + a.groundItems() + " vs " + b.groundItems());
    }
  }

  private static List<String> sortedCopy(List<String> values) {
    return values.stream().sorted().toList();
  }
}
