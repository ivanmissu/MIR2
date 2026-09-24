package com.mir2.world;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGroupTest {

  @Test
  void createGroupSuccessfulAndDisallowModeLeaves() {
    List<WorldEvent> eventsA = new CopyOnWriteArrayList<>();
    List<WorldEvent> eventsB = new CopyOnWriteArrayList<>();
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 30, 30)))) {
      WorldObjectSnapshot a = run(world,
          world.enterPlayer("LeaderA", "0", new Position(5, 5), Direction.DOWN, eventsA::add));
      WorldObjectSnapshot b = run(world,
          world.enterPlayer("MemberB", "0", new Position(6, 5), Direction.DOWN, eventsB::add));
      int idA = a.id();
      int idB = b.id();
      eventsA.clear();
      eventsB.clear();

      // A fresh player refuses invitations (m_boAllowGroup := False, ObjBase.pas:1270):
      // the very first create must answer SM_CREATEGROUP_FAIL reason -4.
      assertFalse(run(world, world.allowGroup(idB)));
      assertFalse(run(world, world.createGroup(idA, "MemberB")));
      assertTrue(eventsA.stream().anyMatch(e -> e instanceof WorldEvent.GroupCreateFailed
          && ((WorldEvent.GroupCreateFailed) e).reason() == -4));

      // MemberB opens group mode; now the invitation is accepted
      run(world, world.setAllowGroup(idB, true));
      assertTrue(run(world, world.createGroup(idA, "MemberB")));

      assertTrue(run(world, world.isGroupLeader(idA)));
      assertFalse(run(world, world.isGroupLeader(idB)));
      assertEquals(List.of("LeaderA", "MemberB"), run(world, world.groupMembers(idA)));
      assertEquals(List.of("LeaderA", "MemberB"), run(world, world.groupMembers(idB)));

      // MemberB turns off group mode -> leaves group, group disbands because <= 1 member
      eventsA.clear();
      eventsB.clear();
      assertTrue(run(world, world.setAllowGroup(idB, false)));

      assertFalse(run(world, world.allowGroup(idB)));
      assertEquals(List.of(), run(world, world.groupMembers(idA)));
      assertEquals(List.of(), run(world, world.groupMembers(idB)));
      assertTrue(eventsA.stream().anyMatch(e -> e instanceof WorldEvent.GroupCancelled));
      assertTrue(eventsB.stream().anyMatch(e -> e instanceof WorldEvent.GroupCancelled));
    }
  }

  @Test
  void createGroupFailsWhenTargetRefusesOrNotFoundOrInGroup() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 30, 30)))) {
      WorldObjectSnapshot a = run(world,
          world.enterPlayer("LeaderA", "0", new Position(5, 5), Direction.DOWN, ignored -> {}));
      WorldObjectSnapshot b = run(world,
          world.enterPlayer("MemberB", "0", new Position(6, 5), Direction.DOWN, ignored -> {}));
      WorldObjectSnapshot c = run(world,
          world.enterPlayer("MemberC", "0", new Position(7, 5), Direction.DOWN, ignored -> {}));
      int idA = a.id();
      int idB = b.id();
      int idC = c.id();

      // Target doesn't exist -> reason -2
      assertFalse(run(world, world.createGroup(idA, "NoSuchPlayer")));

      // Target self -> reason -2
      assertFalse(run(world, world.createGroup(idA, "LeaderA")));

      // Target has allowGroup = false -> reason -4
      run(world, world.setAllowGroup(idB, false));
      assertFalse(run(world, world.createGroup(idA, "MemberB")));
      run(world, world.setAllowGroup(idB, true));

      // Create group with B
      assertTrue(run(world, world.createGroup(idA, "MemberB")));

      // Leader in group creating another group -> reason -1
      assertFalse(run(world, world.createGroup(idA, "MemberC")));

      // Target already in group -> reason -3
      assertFalse(run(world, world.createGroup(idC, "MemberB")));
    }
  }

  @Test
  void addGroupMemberUpToMaxCapacity() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 50, 50)))) {
      WorldObjectSnapshot leader = run(world,
          world.enterPlayer("Leader", "0", new Position(1, 1), Direction.DOWN, ignored -> {}));
      int leaderId = leader.id();

      List<Integer> memberIds = new ArrayList<>();
      for (int i = 1; i <= 11; i++) {
        WorldObjectSnapshot m = run(world,
            world.enterPlayer("Member" + i, "0", new Position(1 + i, 1), Direction.DOWN, ignored -> {}));
        memberIds.add(m.id());
        // Every invitee must permit invitations first (m_boAllowGroup starts False).
        run(world, world.setAllowGroup(m.id(), true));
      }

      // Create with Member1 -> 2 members
      assertTrue(run(world, world.createGroup(leaderId, "Member1")));

      // Add Member2..Member11 -> total 12 members (MAX)
      for (int i = 2; i <= 11; i++) {
        assertTrue(run(world, world.addGroupMember(leaderId, "Member" + i)));
      }
      assertEquals(12, run(world, world.groupMembers(leaderId)).size());

      // 13th member exceeds capacity (MAX 12) -> reason -5
      run(world, world.enterPlayer("MemberExtra", "0", new Position(20, 1), Direction.DOWN, ignored -> {}));
      assertFalse(run(world, world.addGroupMember(leaderId, "MemberExtra")));
    }
  }

  @Test
  void delGroupMemberKicksMemberAndDisbandsOnLast() {
    List<WorldEvent> eventsA = new CopyOnWriteArrayList<>();
    List<WorldEvent> eventsB = new CopyOnWriteArrayList<>();
    List<WorldEvent> eventsC = new CopyOnWriteArrayList<>();
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 30, 30)))) {
      WorldObjectSnapshot a = run(world,
          world.enterPlayer("LeaderA", "0", new Position(5, 5), Direction.DOWN, eventsA::add));
      WorldObjectSnapshot b = run(world,
          world.enterPlayer("MemberB", "0", new Position(6, 5), Direction.DOWN, eventsB::add));
      WorldObjectSnapshot c = run(world,
          world.enterPlayer("MemberC", "0", new Position(7, 5), Direction.DOWN, eventsC::add));
      int idA = a.id();
      int idB = b.id();
      int idC = c.id();

      // Invitees open group mode first (m_boAllowGroup starts False, ObjBase.pas:1270).
      run(world, world.setAllowGroup(idB, true));
      run(world, world.setAllowGroup(idC, true));
      assertTrue(run(world, world.createGroup(idA, "MemberB")));
      assertTrue(run(world, world.addGroupMember(idA, "MemberC")));
      assertEquals(List.of("LeaderA", "MemberB", "MemberC"), run(world, world.groupMembers(idA)));

      // Non-leader kicking fails with reason -1
      assertFalse(run(world, world.delGroupMember(idB, "MemberC")));

      // Leader kicks MemberB -> B receives GroupCancelled, A & C get updated list of 2
      eventsB.clear();
      assertTrue(run(world, world.delGroupMember(idA, "MemberB")));

      assertEquals(List.of(), run(world, world.groupMembers(idB)));
      assertEquals(List.of("LeaderA", "MemberC"), run(world, world.groupMembers(idA)));
      assertTrue(eventsB.stream().anyMatch(e -> e instanceof WorldEvent.GroupCancelled));

      // Leader kicks MemberC -> only Leader remains (<= 1) -> group disbands
      eventsA.clear();
      eventsC.clear();
      assertTrue(run(world, world.delGroupMember(idA, "MemberC")));

      assertEquals(List.of(), run(world, world.groupMembers(idA)));
      assertEquals(List.of(), run(world, world.groupMembers(idC)));
      assertTrue(eventsA.stream().anyMatch(e -> e instanceof WorldEvent.GroupCancelled));
      assertTrue(eventsC.stream().anyMatch(e -> e instanceof WorldEvent.GroupCancelled));
    }
  }

  @Test
  void playerLeaveAndDeathCleansUpGroup() {
    try (WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 30, 30)))) {
      WorldObjectSnapshot a = run(world,
          world.enterPlayer("LeaderA", "0", new Position(5, 5), Direction.DOWN, ignored -> {}));
      WorldObjectSnapshot b = run(world,
          world.enterPlayer("MemberB", "0", new Position(6, 5), Direction.DOWN, ignored -> {}));
      WorldObjectSnapshot c = run(world,
          world.enterPlayer("MemberC", "0", new Position(7, 5), Direction.DOWN, ignored -> {}));
      int idA = a.id();
      int idB = b.id();
      int idC = c.id();

      // Invitees open group mode first (m_boAllowGroup starts False, ObjBase.pas:1270).
      run(world, world.setAllowGroup(idB, true));
      run(world, world.setAllowGroup(idC, true));
      assertTrue(run(world, world.createGroup(idA, "MemberB")));
      assertTrue(run(world, world.addGroupMember(idA, "MemberC")));

      // MemberB leaves world (disconnect) -> group shrinks to [LeaderA, MemberC]
      run(world, world.leavePlayer(idB));
      assertEquals(List.of("LeaderA", "MemberC"), run(world, world.groupMembers(idA)));

      // Leader leaves world -> group disbands
      run(world, world.leavePlayer(idA));
      assertEquals(List.of(), run(world, world.groupMembers(idC)));
    }
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }
}
