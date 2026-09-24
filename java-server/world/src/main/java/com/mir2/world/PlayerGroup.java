package com.mir2.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An active player party container (ObjBase.pas: {@code m_GroupMembers} and {@code m_GroupOwner}).
 *
 * <p>Delphi limits the party size to {@code GROUPMAX = 11} additional members plus the leader,
 * making the capacity 12 (ObjBase.pas:15564 {@code bonus: array[0..GROUPMAX] of Real}).
 */
public final class PlayerGroup {
  public static final int MAX_MEMBERS = 12;

  private int leaderId;
  private final List<Integer> memberIds;

  public PlayerGroup(int leaderId) {
    this.leaderId = leaderId;
    this.memberIds = new ArrayList<>();
    this.memberIds.add(leaderId);
  }

  public int leaderId() {
    return leaderId;
  }

  public void setLeaderId(int leaderId) {
    this.leaderId = leaderId;
  }

  public List<Integer> memberIds() {
    return Collections.unmodifiableList(memberIds);
  }

  public int size() {
    return memberIds.size();
  }

  public boolean contains(int playerId) {
    return memberIds.contains(playerId);
  }

  public boolean isLeader(int playerId) {
    return this.leaderId == playerId;
  }

  public boolean add(int playerId) {
    if (memberIds.size() >= MAX_MEMBERS || memberIds.contains(playerId)) {
      return false;
    }
    memberIds.add(playerId);
    return true;
  }

  public boolean remove(int playerId) {
    return memberIds.remove(Integer.valueOf(playerId));
  }
}
