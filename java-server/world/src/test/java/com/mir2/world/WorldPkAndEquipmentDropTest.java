package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W20: the PK model ({@code PKLevel} / {@code IncPkPoint} / {@code DecPKPoint} /
 * {@code SetPKFlag} / {@code RefNameColor}) and {@code TPlayObject.DropUseItems}, including
 * the {@code Reserved and 8} destroy pass, the {@code Reserved and 10} keep-slot quirk and
 * {@code g_Config.boDieRedScatterBagAll}.
 */
class WorldPkAndEquipmentDropTest {
  private final AtomicLong now = new AtomicLong(1_000_000);

  /** 护身戒指: Shape 170 on a ring slot sets {@code m_boAngryRing}. */
  private static StdItem angryRing() {
    return new StdItem("护身戒指", 22, 170, 1, 0, 0, 0, 220, 5000, 0, 0, 0, 0, 0, 0, 0, 100);
  }

  /** Shape 171 — {@code m_boNoDropItem}: the bag survives, the gear does not. */
  private static StdItem noDropBagRing() {
    return new StdItem("守包戒指", 22, 171, 1, 0, 0, 0, 221, 5000, 0, 0, 0, 0, 0, 0, 0, 100);
  }

  /** Shape 172 — {@code m_boNoDropUseItem}: the gear survives, the bag does not. */
  private static StdItem noDropGearRing() {
    return new StdItem("守装戒指", 22, 172, 1, 0, 0, 0, 222, 5000, 0, 0, 0, 0, 0, 0, 0, 100);
  }

  /** An ordinary helmet with no Reserved bits: eligible for the 1-in-30 scatter. */
  private static StdItem plainHelmet() {
    return new StdItem("青铜头盔", 15, 0, 3, 0, 0, 0, 0, 205, 2000,
        StdItem.packedRange(2, 2), 0, 0, 0, 0, 0, 0, 500);
  }

  /** {@code Reserved and 8}: destroyed outright on death, never dropped. */
  private static StdItem selfDestructingNecklace() {
    return new StdItem("消散项链", 19, 0, 1, 0, 0, 0x08, 0, 206, 2000,
        0, 0, 0, 0, 0, 0, 0, 500);
  }

  /**
   * {@code Reserved and 10 <> 0}: the item lands on the floor but stays worn — the Delphi
   * duplication quirk. Delphi's {@code 10} is decimal, i.e. bits 8|2, and bit 8 is also the
   * destroy flag tested first, so only bit 2 can ever reach the keep-slot branch.
   */
  private static StdItem duplicatingBracelet() {
    return new StdItem("复生手镯", 26, 0, 1, 0, 0, 0x02, 0, 207, 2000,
        0, 0, 0, 0, 0, 0, 0, 500);
  }

  private static BackpackItem worn(StdItem template, int makeIndex) {
    int full = (int) (template.duraMax() & 0xffff);
    return new BackpackItem(template, makeIndex, full, full);
  }

  // ---------------------------------------------------------------- PK point model

  @Test
  void pkLevelIsTheCounterDividedByOneHundredAndDrivesTheNameColour() {
    // ObjBase.pas:2236 — PKLevel := m_nPkPoint div 100.
    assertEquals(0, PkLevel.of(0));
    assertEquals(0, PkLevel.of(99));
    assertEquals(1, PkLevel.of(100));
    assertEquals(2, PkLevel.of(200));
    assertEquals(3, PkLevel.of(399));
    assertFalse(PkLevel.isRed(199));
    assertTrue(PkLevel.isRed(200));

    // GetNamecolor / GetCharColor: white, 黄名, 红名 — and the PK flag tint, which only wins
    // while the level is still under 2.
    assertEquals(255, PkLevel.nameColor(0, false));
    assertEquals(0x2F, PkLevel.nameColor(0, true));
    assertEquals(0xFB, PkLevel.nameColor(100, false));
    assertEquals(0x2F, PkLevel.nameColor(100, true));
    assertEquals(0xF9, PkLevel.nameColor(200, false));
    assertEquals(0xF9, PkLevel.nameColor(200, true), "a red name stays red through a duel");
  }

  @Test
  void killingAnotherPlayerCostsOnePkLevelAndRepaintsTheMurderersName() {
    List<WorldEvent> killerEvents = new ArrayList<>();
    List<WorldEvent> victimEvents = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20))) {
      WorldObjectSnapshot killer = enter(world, "凶手", new Position(5, 5), killerEvents);
      WorldObjectSnapshot victim = enter(world, "良民", new Position(5, 6), victimEvents);
      run(world, world.setLevel(killer.id(), 40));

      killerEvents.clear();
      victimEvents.clear();
      beatToDeath(world, killer.id(), victim.id(), new Position(5, 5), Direction.DOWN);

      // g_Config.nKillHumanAddPKPoint = 100 — exactly one PK level per murder.
      assertEquals(100, run(world, world.pkPoint(killer.id())));
      assertEquals(1, run(world, world.playerState(killer.id())).pkLevel());

      // g_sYouMurderedMsg to the killer, g_sYouKilledByMsg to the corpse.
      assertTrue(killerEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.SystemMessage message
              && "你犯了谋杀罪...".equals(message.message())));
      assertTrue(victimEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.SystemMessage message
              && "你被凶手杀害了...".equals(message.message())));

      // IncPkPoint moved the level 0 -> 1, so RefNameColor broadcast to everyone. The byte on
      // the wire is $2F, not $FB: the fight also set m_boPKFlag, and GetCharColor lets the
      // flag tint win while PKLevel is still below 2 (ObjBase.pas:19024).
      WorldEvent.NameColorChanged colour = lastNameColor(killerEvents, killer.id());
      assertNotNull(colour, "a level change must repaint the name");
      assertEquals(0x2F, colour.nameColor());
      assertEquals(100, colour.pkPoint());
      // Once the 60-second flag lapses the underlying 黄名 shows through.
      assertEquals(0xFB, PkLevel.nameColor(colour.pkPoint(), false));
      assertTrue(victimEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.NameColorChanged changed
              && changed.objectId() == killer.id()),
          "observers see the murderer's new colour too");
    }
  }

  @Test
  void aSecondMurderMakesTheKillerRedAndTheVictimOfAFlaggedPlayerIsALawfulKill() {
    List<WorldEvent> killerEvents = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20))) {
      WorldObjectSnapshot killer = enter(world, "凶手", new Position(5, 5), killerEvents);
      run(world, world.setLevel(killer.id(), 40));
      run(world, world.addPkPoint(killer.id(), 200));
      assertTrue(PkLevel.isRed(run(world, world.pkPoint(killer.id()))));

      // A red name killing anyone is already past the Die branch (it only runs while
      // PKLevel < 2 on the victim); here the victim is clean, so the killer still gains.
      WorldObjectSnapshot victim = enter(world, "良民", new Position(5, 6), event -> {});
      killerEvents.clear();
      beatToDeath(world, killer.id(), victim.id(), new Position(5, 5), Direction.DOWN);
      assertEquals(300, run(world, world.pkPoint(killer.id())));
      // 2 -> 3 is still a level change, so RefNameColor fires; a red name ignores the flag.
      WorldEvent.NameColorChanged colour = lastNameColor(killerEvents, killer.id());
      assertNotNull(colour);
      assertEquals(0xF9, colour.nameColor());
    }
  }

  @Test
  void killingAFlaggedPlayerIsLawfulAndCostsNoPkPoints() {
    List<WorldEvent> defenderEvents = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20))) {
      // 挑衅者 swings first, which sets its own m_boPKFlag (SetPKFlag on the attacker).
      WorldObjectSnapshot aggressor = enter(world, "挑衅者", new Position(5, 5), event -> {});
      WorldObjectSnapshot defender = enter(world, "自卫者", new Position(5, 6), defenderEvents);
      run(world, world.setLevel(aggressor.id(), 10));
      run(world, world.setLevel(defender.id(), 40));
      landOneBlow(world, aggressor.id(), new Position(5, 5), Direction.DOWN, defender.id());

      defenderEvents.clear();
      beatToDeath(world, defender.id(), aggressor.id(), new Position(5, 6), Direction.UP);

      // IsGoodKilling: no PK points, and only the "protected by law" hint.
      assertEquals(0, run(world, world.pkPoint(defender.id())));
      assertTrue(defenderEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.SystemMessage message
              && "[--你受到正当规则保护--]".equals(message.message())));
      assertFalse(defenderEvents.stream().anyMatch(event ->
          event instanceof WorldEvent.SystemMessage message
              && "你犯了谋杀罪...".equals(message.message())));
    }
  }

  @Test
  void tradingBlowsSetsThePkFlagForSixtySecondsAndThenClearsIt() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20))) {
      WorldObjectSnapshot attacker = enter(world, "先手", new Position(5, 5), events);
      WorldObjectSnapshot target = enter(world, "后手", new Position(5, 6), event -> {});
      run(world, world.setLevel(attacker.id(), 30));

      events.clear();
      landOneBlow(world, attacker.id(), new Position(5, 5), Direction.DOWN, target.id());

      WorldEvent.NameColorChanged flagged = lastNameColor(events, attacker.id());
      assertNotNull(flagged, "SetPKFlag repaints the aggressor's name on the first blow");
      assertEquals(0x2F, flagged.nameColor());
      // The victim never gets the tint — only the aggressor does.
      assertNull(lastNameColor(events, target.id()));

      // dwPKFlagTime = 60s, checked by CheckPKStatus in TBaseObject.Run.
      events.clear();
      advance(59_000);
      world.tickOnce();
      assertNull(lastNameColor(events, attacker.id()), "the flag survives the first 59 seconds");
      advance(2_000);
      world.tickOnce();
      WorldEvent.NameColorChanged cleared = lastNameColor(events, attacker.id());
      assertNotNull(cleared, "CheckPKStatus clears the flag and repaints");
      assertEquals(255, cleared.nameColor());
    }
  }

  @Test
  void pkPointsBleedOffOnePerTwoMinutesAndTheColourFollowsTheLevel() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20))) {
      WorldObjectSnapshot player = enter(world, "悔过者", new Position(5, 5), events);
      run(world, world.addPkPoint(player.id(), 100));

      events.clear();
      // dwDecPkPointTime = 2 minutes, nDecPkPointCount = 1.
      advance(120_001);
      world.tickOnce();
      assertEquals(99, run(world, world.pkPoint(player.id())));
      // 1 -> 0 while the old level was in 1..2: DecPKPoint repaints (ObjBase.pas:18900).
      WorldEvent.NameColorChanged colour = lastNameColor(events, player.id());
      assertNotNull(colour);
      assertEquals(255, colour.nameColor());

      // Inside the same window nothing happens.
      events.clear();
      advance(60_000);
      world.tickOnce();
      assertEquals(99, run(world, world.pkPoint(player.id())));
    }
  }

  @Test
  void aDeepRedNameDroppingFromLevelThreeToTwoStaysSilent() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20))) {
      WorldObjectSnapshot player = enter(world, "深红", new Position(5, 5), events);
      run(world, world.addPkPoint(player.id(), 300));

      events.clear();
      advance(120_001);
      world.tickOnce();
      assertEquals(299, run(world, world.pkPoint(player.id())));
      // The level moved 3 -> 2, but DecPKPoint's guard is (nOldLevel > 0) and (nOldLevel <= 2):
      // a 深红 character repaints nothing on the way down.
      assertNull(lastNameColor(events, player.id()),
          "ObjBase.pas:18900 only repaints when the OLD level was 1 or 2");
    }
  }

  @Test
  void thePkPointCounterSurvivesALogoutAndLogin() {
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, null);
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20), store)) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "记仇", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, event -> {}));
      run(world, world.addPkPoint(player.id(), 150));
      run(world, world.leavePlayer(player.id()));
    }
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20), store)) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "记仇", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, event -> {}));
      // HumData.nPKPOINT travels with the character record; m_boPKFlag does not.
      assertEquals(150, run(world, world.pkPoint(player.id())));
      assertEquals(1, run(world, world.playerState(player.id())).pkLevel());
    }
  }

  // ------------------------------------------------------------- DropUseItems

  @Test
  void aMonsterKillScattersWornGearWithinTwoCellsAndReportsTheLoss() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    // Seed 3's DEATH_DROP_USE_ITEM stream opens with 0, so the first worn slot drops.
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.HELMET, worn(plainHelmet(), 11))), 0));
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20), store, 3L)) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "倒霉蛋", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, events::add));
      events.clear();
      killWithMonster(world, player.id());

      WorldEvent.ItemsRemoved removed = firstOf(events, WorldEvent.ItemsRemoved.class);
      assertNotNull(removed, "boKillByMonstDropUseItem is on: a monster kill scatters gear");
      assertEquals(1, removed.items().size());
      assertEquals("青铜头盔", removed.items().getFirst().name());
      assertEquals(11, removed.items().getFirst().makeIndex());
      assertTrue(run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.HELMET).isEmpty());

      // DropItemDown(..., 2, True): the helmet lands within DropWide = 2 of the corpse.
      WorldEvent.ItemAppeared appeared = events.stream()
          .filter(WorldEvent.ItemAppeared.class::isInstance)
          .map(WorldEvent.ItemAppeared.class::cast)
          .filter(event -> "青铜头盔".equals(event.item().name()))
          .findFirst().orElseThrow();
      assertTrue(Math.abs(appeared.item().position().x() - 5) <= 2
          && Math.abs(appeared.item().position().y() - 5) <= 2);
      // The client also gets a fresh RM_SENDUSEITEMS for the emptied slot.
      assertNotNull(firstOf(events, WorldEvent.EquipmentSent.class));
    }
  }

  @Test
  void aPlayerKillLeavesTheVictimsGearAlone() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.HELMET, worn(plainHelmet(), 11))), 0));
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20), store, 3L)) {
      WorldObjectSnapshot victim = run(world, world.enterPlayer(characterId, "被杀者", "0",
          new Position(5, 6), Direction.UP, 0, 0, LevelAbilities.JOB_WARRIOR, events::add));
      WorldObjectSnapshot killer = enter(world, "凶手", new Position(5, 5), event -> {});
      run(world, world.setLevel(killer.id(), 40));

      events.clear();
      beatToDeath(world, killer.id(), victim.id(), new Position(5, 5), Direction.DOWN);

      // g_Config.boKillByHumanDropUseItem is False in the shipped defaults.
      assertTrue(run(world, world.playerState(victim.id()))
          .equipment().at(EquipmentSlot.HELMET).isPresent(),
          "a PK kill must not scatter the victim's equipment");
    }
  }

  @Test
  void reservedBitEightDestroysTheItemOutrightAndReportsItWithoutAName() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    // Seed 1's stream opens 4,17,8,... — no slot wins the 1-in-30 roll, so only the
    // destroy pass can touch the worn set.
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(
            EquipmentSlot.NECKLACE, worn(selfDestructingNecklace(), 21),
            EquipmentSlot.HELMET, worn(plainHelmet(), 22))), 0));
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20), store, 1L)) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "倒霉蛋", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, events::add));
      events.clear();
      killWithMonster(world, player.id());

      WorldEvent.ItemsRemoved removed = firstOf(events, WorldEvent.ItemsRemoved.class);
      assertNotNull(removed);
      assertEquals(1, removed.items().size());
      // DelList.AddObject('', MakeIndex): the wire entry really is nameless.
      assertEquals("", removed.items().getFirst().name());
      assertEquals(21, removed.items().getFirst().makeIndex());
      assertTrue(run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.NECKLACE).isEmpty());
      // ... and the destroyed necklace never reaches the floor.
      assertFalse(events.stream().anyMatch(event ->
          event instanceof WorldEvent.ItemAppeared appeared
              && "消散项链".equals(appeared.item().name())));
      // The plain helmet lost its roll and stays worn.
      assertTrue(run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.HELMET).isPresent());
    }
  }

  @Test
  void reservedBitTenDropsTheItemOnTheFloorWhileTheWearerKeepsIt() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.ARM_RING_LEFT, worn(duplicatingBracelet(), 31))), 0));
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20), store, 3L)) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "倒霉蛋", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, events::add));
      events.clear();
      killWithMonster(world, player.id());

      // Reserved and 10 <> 0: DropItemDown succeeded, so the bracelet is on the ground...
      assertTrue(events.stream().anyMatch(event ->
          event instanceof WorldEvent.ItemAppeared appeared
              && "复生手镯".equals(appeared.item().name())),
          "the item still lands on the floor");
      // ... but the slot was never cleared and nothing was reported to the client.
      assertTrue(run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.ARM_RING_LEFT).isPresent(),
          "Reserved and 10 keeps the item worn — the original's duplication quirk");
      assertNull(firstOf(events, WorldEvent.ItemsRemoved.class));
    }
  }

  @Test
  void theProtectionRingsGateTheTwoDeathPenaltiesIndependently() {
    // m_boAngryRing blocks both; m_boNoDropItem only the bag; m_boNoDropUseItem only the gear.
    assertTrue(DropProtection.of(new Equipment(Map.of(
        EquipmentSlot.RING_LEFT, worn(angryRing(), 1)))).blocksBagScatter());
    assertTrue(DropProtection.of(new Equipment(Map.of(
        EquipmentSlot.RING_LEFT, worn(angryRing(), 1)))).blocksEquipmentDrop());

    DropProtection bagOnly = DropProtection.of(new Equipment(Map.of(
        EquipmentSlot.RING_LEFT, worn(noDropBagRing(), 2))));
    assertTrue(bagOnly.blocksBagScatter());
    assertFalse(bagOnly.blocksEquipmentDrop());

    DropProtection gearOnly = DropProtection.of(new Equipment(Map.of(
        EquipmentSlot.RING_LEFT, worn(noDropGearRing(), 3))));
    assertFalse(gearOnly.blocksBagScatter());
    assertTrue(gearOnly.blocksEquipmentDrop());

    // ObjBase.pas:2945 — a worn-out item grants nothing at all.
    assertFalse(DropProtection.of(new Equipment(Map.of(
        EquipmentSlot.RING_LEFT, new BackpackItem(angryRing(), 4, 0, 5000)))).blocksBagScatter());
  }

  @Test
  void aWornAngryRingKeepsBothTheBagAndTheGear() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(
            EquipmentSlot.RING_LEFT, worn(angryRing(), 41),
            EquipmentSlot.HELMET, worn(plainHelmet(), 42))), 0));
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20), store, 3L)) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "受庇护", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, events::add));
      for (int index = 0; index < 6; index++) {
        run(world, world.spawnGroundItem("鹿肉", 42, "0", new Position(5, 5)));
        assertTrue(run(world, world.pickUp(player.id(), new Position(5, 5))));
      }

      events.clear();
      killWithMonster(world, player.id());

      assertNull(firstOf(events, WorldEvent.ItemsRemoved.class),
          "m_boAngryRing exits both ScatterBagItems and DropUseItems before any roll");
      PlayerState state = run(world, world.playerState(player.id()));
      assertEquals(6, state.backpack().size());
      assertTrue(state.equipment().at(EquipmentSlot.HELMET).isPresent());
    }
  }

  // --------------------------------------------------- boDieRedScatterBagAll

  @Test
  void aRedNameDropsItsEntireBagInsteadOfOneThird() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "荒野", 20, 20))) {
      WorldObjectSnapshot player = enter(world, "红名", new Position(5, 5), events);
      for (int index = 0; index < 10; index++) {
        run(world, world.spawnGroundItem("鹿肉", 42, "0", new Position(5, 5)));
        assertTrue(run(world, world.pickUp(player.id(), new Position(5, 5))));
      }
      run(world, world.addPkPoint(player.id(), 200));
      assertTrue(PkLevel.isRed(run(world, world.pkPoint(player.id()))));

      events.clear();
      killWithMonster(world, player.id());

      WorldEvent.ItemsRemoved removed = firstOf(events, WorldEvent.ItemsRemoved.class);
      assertNotNull(removed);
      // g_Config.boDieRedScatterBagAll: PKLevel >= 2 means boDropall, not Random(3) = 0.
      assertEquals(10, removed.items().size());
      assertTrue(run(world, world.playerState(player.id())).backpack().isEmpty());
    }
  }

  @Test
  void aNoDropItemMapSuppressesTheBagScatterButNotTheEquipmentDrop() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    MapFlags noDrop = new MapFlags(false, false, false, false, false, false, false, "",
        false, true, false, -1, -1, false, false, true);
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.HELMET, worn(plainHelmet(), 51))), 0));
    try (WorldEngine world =
             engine(GameMap.empty("0", "无掉落区", 20, 20, noDrop), store, 3L)) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "试验者", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, events::add));
      for (int index = 0; index < 6; index++) {
        run(world, world.spawnGroundItem("鹿肉", 42, "0", new Position(5, 5)));
        assertTrue(run(world, world.pickUp(player.id(), new Position(5, 5))));
      }

      events.clear();
      killWithMonster(world, player.id());

      // ScatterBagItems checks m_PEnvir.Flag.boNODROPITEM ...
      assertEquals(6, run(world, world.playerState(player.id())).backpack().size());
      // ... DropUseItems does not, and Die's own guard is an OR that never blocks here.
      assertTrue(run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.HELMET).isEmpty(),
          "a NODROPITEM map does not protect worn gear in the original");
    }
  }

  @Test
  void mapInfoParsesNoDropItemSeparatelyFromNoThrowItem() {
    MapFlags both = MapInfoLoader.parseFlags(List.of("NODROPITEM", "NOTHROWITEM"));
    assertTrue(both.isNoDropItem());
    assertTrue(both.isNoThrowItem());
    MapFlags throwOnly = MapInfoLoader.parseFlags(List.of("NOTHROWITEM"));
    assertFalse(throwOnly.isNoDropItem());
    assertTrue(throwOnly.isNoThrowItem());
  }

  // ------------------------------------------------------------------ helpers

  private WorldObjectSnapshot enter(
      WorldEngine world, String name, Position position, List<WorldEvent> events) {
    return enter(world, name, position, events::add);
  }

  private WorldObjectSnapshot enter(
      WorldEngine world, String name, Position position, WorldEventSink sink) {
    return run(world, world.enterPlayer(UUID.randomUUID(), name, "0", position,
        Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR, sink));
  }

  /** Swings from {@code from} until the target is dead; fails the test if it survives. */
  /**
   * Swings until one blow actually lands. {@code SetPKFlag} only fires from the
   * {@code nPower > 0} branch of {@code applyDamage}, and since W33 a DEFHIT = 5 character
   * is dodged by a DEFSPEED = 15 one about three swings in five (ObjBase.pas:22240), so a
   * single scripted attack is no longer enough to guarantee the flag.
   */
  private void landOneBlow(
      WorldEngine world, int attackerId, Position from, Direction facing, int targetId) {
    int hp = run(world, world.snapshot(targetId)).ability().hp();
    for (int swing = 0; swing < 60; swing++) {
      advance(1_000);
      run(world, world.attack(attackerId, from, facing, AttackKind.HIT));
      int now = run(world, world.snapshot(targetId)).ability().hp();
      if (now < hp) return;
      hp = now;
    }
    throw new AssertionError("the attacker never landed a blow");
  }

  private void beatToDeath(
      WorldEngine world, int attackerId, int targetId, Position from, Direction facing) {
    for (int swing = 0; swing < 400; swing++) {
      advance(1_000);
      world.tickOnce();
      run(world, world.attack(attackerId, from, facing, AttackKind.HIT));
      if (!run(world, world.snapshot(targetId)).ability().alive()) return;
    }
    throw new AssertionError("the attacker failed to kill the target");
  }

  /** Lets an orc finish the player off — the boKillByMonstDropUseItem branch of Die. */
  private void killWithMonster(WorldEngine world, int playerId) {
    run(world, world.spawnMonster(MonsterTemplate.orc(), "0", new Position(6, 5), Direction.LEFT));
    for (int tick = 0; tick < 300; tick++) {
      advance(1_000);
      world.tickOnce();
      if (!run(world, world.snapshot(playerId)).ability().alive()) return;
    }
    throw new AssertionError("the orc failed to kill the player");
  }

  private static WorldEvent.NameColorChanged lastNameColor(List<WorldEvent> events, int objectId) {
    WorldEvent.NameColorChanged found = null;
    for (WorldEvent event : events) {
      if (event instanceof WorldEvent.NameColorChanged changed && changed.objectId() == objectId) {
        found = changed;
      }
    }
    return found;
  }

  private static <T> T firstOf(List<WorldEvent> events, Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
  }

  private WorldEngine engine(GameMap map) {
    return engine(map, PlayerStateStore.none());
  }

  private WorldEngine engine(GameMap map, PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(map), now::get, new Random(20020522L), store,
        ItemDatabase.of(StdItems.defaults()));
  }

  /** Split-stream variant so the DropUseItems rolls can be pinned independently. */
  private WorldEngine engine(GameMap map, PlayerStateStore store, long seed) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(map), now::get, WorldRandom.seeded(seed), store,
        ItemDatabase.of(StdItems.defaults()));
  }

  private void advance(long millis) {
    now.addAndGet(millis);
  }

  private <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  /** In-memory store seeded with a prepared state, remembering every save. */
  private static final class PreparedStore implements PlayerStateStore {
    private final UUID characterId;
    private PlayerState state;

    PreparedStore(UUID characterId, PlayerState state) {
      this.characterId = characterId;
      this.state = state;
    }

    @Override
    public Optional<PlayerState> load(UUID id) {
      return characterId.equals(id) ? Optional.ofNullable(state) : Optional.empty();
    }

    @Override
    public void save(PlayerState newState) {
      if (characterId.equals(newState.characterId())) state = newState;
    }
  }
}
