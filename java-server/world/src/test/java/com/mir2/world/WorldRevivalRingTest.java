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
 * W15: the revival ring — {@code TBaseObject.Run}'s HP=0 branch (ObjBase.pas:3750) and
 * {@code ItemDamageRevivalRing} (ObjBase.pas:3625).
 */
class WorldRevivalRingTest {
  private final AtomicLong now = new AtomicLong();

  /** 复活戒指: StdMode 22 ring, Shape 114 — the classic revival shape. */
  private static StdItem revivalRing() {
    return StdItems.revivalRing();
  }

  /** A ring with a different revival-capable shape (Shape 162 also grants m_boRevival). */
  private static StdItem otherRevivalRing() {
    return new StdItem("护身戒指(异)", 22, 162, 1, 0, 0, 0, 218, 4000, 0, 0, 0, 0, 0, 0, 0, 500);
  }

  /** A dress with AniCount 114: it grants m_boRevival but is never consumed — Delphi quirk. */
  private static StdItem revivalDress() {
    return new StdItem("复活羽衣", 10, 3, 5, 114, 0, 0, 110, 2000,
        StdItem.packedRange(1, 4), 0, 0, 0, 0, 0, 0, 300);
  }

  /** A ring whose Shape 144 sets m_boUnRevival on its wearer (ObjBase.pas:3139). */
  private static StdItem unRevivalRing() {
    return new StdItem("克复戒指", 22, 144, 1, 0, 0, 0, 219, 2000, 0, 0, 0, 0, 0, 0, 0, 100);
  }

  private static BackpackItem worn(StdItem template, int makeIndex, int dura) {
    return new BackpackItem(template, makeIndex, dura, (int) template.duraMax());
  }

  /** In-memory store that hands the prepared state to its owner and remembers saves. */
  private static final class PreparedStore implements PlayerStateStore {
    private final UUID characterId;
    PlayerState state;

    PreparedStore(UUID characterId, PlayerState state) {
      this.characterId = characterId;
      this.state = state;
    }

    @Override
    public Optional<PlayerState> load(UUID id) {
      return characterId.equals(id) ? Optional.of(state) : Optional.empty();
    }

    @Override
    public void save(PlayerState newState) {
      if (characterId.equals(newState.characterId())) state = newState;
    }
  }

  @Test
  void aRevivalRingEatsTheLethalBlowAndPaysOneThousandDurability() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.RING_LEFT, worn(revivalRing(), 7, 5000))), 0));
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      // m_dwRevivalTick starts at zero, so the first 60 seconds of world uptime are still
      // inside the cooldown — Delphi has the same property relative to GetTickCount. Give the
      // clock a head start like a machine that has been up for a while.
      advance(120_000);

      events.clear();
      spawnOrc(world);
      assertTrue(waitForRevival(world, player.id(), events),
          "the ring must eat the first lethal blow");

      // The killing blow never became a death: the branch refilled HP instead.
      assertTrue(run(world, world.snapshot(player.id())).ability().alive());
      assertEquals(run(world, world.snapshot(player.id())).ability().maxHp(),
          run(world, world.snapshot(player.id())).ability().hp());
      assertNull(singleOrNull(events, WorldEvent.ObjectDied.class));
      assertNull(singleOrNull(events, WorldEvent.ItemsRemoved.class));

      // The ring paid ItemDamageRevivalRing's flat 1000.
      BackpackItem ring = run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.RING_LEFT).orElseThrow();
      assertEquals(4000, ring.dura());

      // 5000 -> 4000 crosses the thousands boundary, so RM_DURACHANGE went out.
      WorldEvent.ItemDurabilityChanged dura = single(events, WorldEvent.ItemDurabilityChanged.class);
      assertEquals(4000, dura.dura());
      assertEquals(5000, dura.duraMax());
      assertFalse(dura.broken());

      // The green hint (g_sRevivalRecoverMsg) is a system message to the survivor only.
      WorldEvent.SystemMessage hint = single(events, WorldEvent.SystemMessage.class);
      assertEquals("复活戒指生效，体力恢复.", hint.message());
    }
  }

  @Test
  void withinSixtySecondsOfTheLastRevivalTheRingStaysSilentAndThePlayerDies() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.RING_LEFT, worn(revivalRing(), 7, 5000))), 0));
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      advance(120_000);
      spawnOrc(world);
      assertTrue(waitForRevival(world, player.id(), events));

      // The second lethal blow lands well inside dwRevivalTime: Die runs this time.
      events.clear();
      waitForDeath(world, player.id(), events);
      assertFalse(run(world, world.snapshot(player.id())).ability().alive());
      assertNotNull(single(events, WorldEvent.ObjectDied.class));
      // And the ring only ever paid for the first revival.
      assertEquals(4000, run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.RING_LEFT).orElseThrow().dura());
    }
  }

  @Test
  void afterTheCooldownTheRingFiresAgain() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.RING_LEFT, worn(revivalRing(), 7, 5000))), 0));
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      advance(120_000);
      spawnOrc(world);
      assertTrue(waitForRevival(world, player.id(), events));

      // dwRevivalTime = 60s; the comparison is strictly greater-than.
      advance(60_001);
      assertTrue(waitForRevival(world, player.id(), events),
          "after the cooldown the ring fires again");
      assertEquals(3000, run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.RING_LEFT).orElseThrow().dura());
    }
  }

  @Test
  void aRingDrainedToZeroIsDestroyedAndItsBonusesDisappear() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    // A ring with only 800 durability and a DC bonus: the 1000-point drain destroys it.
    StdItem weakRing = new StdItem("残破复活戒", 22, 114, 1, 0, 0, 0, 216, 800,
        0, 0, StdItem.packedRange(2, 2), 0, 0, 0, 0, 100);
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.RING_RIGHT,
            new BackpackItem(weakRing, 9, 800, 800))), 0));
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      Ability dressed = run(world, world.snapshot(player.id())).ability();
      assertEquals(Ability.defaultPlayer().minDc() + 2, dressed.minDc());
      advance(120_000);

      events.clear();
      spawnOrc(world);
      assertTrue(waitForRevival(world, player.id(), events));

      // SendDelItems announced the destroyed ring before the slot was cleared.
      WorldEvent.ItemsRemoved removed = single(events, WorldEvent.ItemsRemoved.class);
      assertEquals(1, removed.items().size());
      assertEquals("残破复活戒", removed.items().getFirst().name());
      assertEquals(9, removed.items().getFirst().makeIndex());

      // RecalcAbilitys ran inside the destruction: the ring's DC bonus is gone.
      assertTrue(run(world, world.playerState(player.id())).equipment()
          .at(EquipmentSlot.RING_RIGHT).isEmpty());
      Ability naked = run(world, world.snapshot(player.id())).ability();
      assertEquals(Ability.defaultPlayer().minDc(), naked.minDc());

      // The durability change itself is still reported (800 -> 0 crosses the boundary).
      WorldEvent.ItemDurabilityChanged dura = single(events, WorldEvent.ItemDurabilityChanged.class);
      assertEquals(0, dura.dura());
      assertTrue(dura.broken());
    }
  }

  @Test
  void everyMatchingItemPaysForOneRevivalNotJustTheFirst() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(
            EquipmentSlot.RING_LEFT, worn(revivalRing(), 7, 2000),
            EquipmentSlot.RING_RIGHT, worn(otherRevivalRing(), 8, 1500))), 0));
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      advance(120_000);
      spawnOrc(world);
      assertTrue(waitForRevival(world, player.id(), events));
      // The Delphi loop has no break: both rings drained 1000 each.
      assertEquals(1000, run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.RING_LEFT).orElseThrow().dura());
      assertEquals(500, run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.RING_RIGHT).orElseThrow().dura());
    }
  }

  @Test
  void theDurabilityChangeOnlyFiresWhenTheThousandthsBucketMoves() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    // 2500 -> 1500: Round(2.5) = 2 and Round(1.5) = 2 under banker's rounding, so Delphi
    // sends no RM_DURACHANGE for this drain even though 1000 durability evaporated.
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.RING_LEFT, worn(revivalRing(), 7, 2500))), 0));
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      advance(120_000);

      events.clear();
      spawnOrc(world);
      assertTrue(waitForRevival(world, player.id(), events));
      assertEquals(1500, run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.RING_LEFT).orElseThrow().dura());
      assertNull(singleOrNull(events, WorldEvent.ItemDurabilityChanged.class),
          "2500 -> 1500 stays in bucket 2 under banker's rounding: no SM_DURACHANGE");

      // 1500 -> 500 crosses from bucket 2 to bucket 0 (Round(1.5) = 2, Round(0.5) = 0).
      advance(60_001);
      events.clear();
      assertTrue(waitForRevival(world, player.id(), events));
      assertEquals(500, run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.RING_LEFT).orElseThrow().dura());
      assertNotNull(single(events, WorldEvent.ItemDurabilityChanged.class),
          "1500 -> 500 moves the bucket: SM_DURACHANGE goes out");
    }
  }

  @Test
  void aDressWithRevivalAniCountGrantsTheFlagButIsNeverConsumed() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.DRESS, worn(revivalDress(), 5, 2000))), 0));
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      advance(120_000);

      events.clear();
      spawnOrc(world);
      assertTrue(waitForRevival(world, player.id(), events),
          "AniCount 114 on the dress sets m_boRevival");
      // ... but ItemDamageRevivalRing only consumes Shape matches (any slot) and AniCount
      // matches (hand slots) — the dress is neither, so it never pays the flat 1000. The
      // ordinary struck-wear from the fight is the only durability it loses.
      int dressDura = run(world, world.playerState(player.id()))
          .equipment().at(EquipmentSlot.DRESS).orElseThrow().dura();
      assertTrue(dressDura > 1000, "the dress must not pay the ring's 1000-point drain: "
          + dressDura);
      assertNull(singleOrNull(events, WorldEvent.ItemsRemoved.class));
    }
  }

  @Test
  void anAttackerWearingShape144SuppressesTheVictimsRevival() {
    List<WorldEvent> events = new ArrayList<>();
    UUID victimId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(victimId, new PlayerState(victimId,
        Ability.defaultPlayer(), List.of(),
        new Equipment(Map.of(EquipmentSlot.RING_LEFT, worn(revivalRing(), 7, 5000))), 0));
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot victim = enter(world, victimId, event -> {});
      // The killer enters adjacent and dons a Shape 144 ring: m_boUnRevival on the last
      // hitter is what TBaseObject.Run checks before it honours the victim's ring.
      WorldObjectSnapshot killer = run(world, world.enterPlayer(UUID.randomUUID(), "杀意", "0",
          new Position(5, 6), Direction.UP, 0, 0, LevelAbilities.JOB_WARRIOR, events::add));
      run(world, world.spawnGroundItem("克复戒指", 219, "0", new Position(5, 6)));
      assertTrue(run(world, world.pickUp(killer.id(), new Position(5, 6))));
      assertTrue(run(world, world.equip(
          killer.id(), EquipmentSlot.RING_RIGHT.index(), 1, "克复戒指")));

      advance(120_000);
      // A level-40 arm shortens the fight; every blow goes through applyDamage.
      run(world, world.setLevel(killer.id(), 40));
      for (int swing = 0; swing < 400; swing++) {
        advance(1_000);
        world.tickOnce();
        run(world, world.attack(killer.id(), new Position(5, 6), Direction.UP, AttackKind.HIT));
        if (!run(world, world.snapshot(victim.id())).ability().alive()) break;
      }
      assertFalse(run(world, world.snapshot(victim.id())).ability().alive(),
          "Shape 144 on the killer keeps the ring silent");
      assertEquals(5000, run(world, world.playerState(victim.id()))
          .equipment().at(EquipmentSlot.RING_LEFT).orElseThrow().dura(),
          "the ring pays nothing when the revival is suppressed");
    }
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID characterId, WorldEventSink sink) {
    return run(world, world.enterPlayer(
        characterId, "战士", "0", new Position(5, 5), Direction.DOWN, 0, 0,
        LevelAbilities.JOB_WARRIOR, sink));
  }

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    List<StdItem> catalog = new ArrayList<>(StdItems.defaults());
    // 克复戒指 is only spawned through the pickup path, which resolves templates by name.
    catalog.add(unRevivalRing());
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)), now::get,
        new Random(20020522L), store, ItemDatabase.of(catalog));
  }

  private void advance(long millis) {
    now.addAndGet(millis);
  }

  /**
   * Runs the world (with an orc already harassing the player) until the ring fires — observed
   * as the revival hint reaching the player's own sink — or the player actually dies, which
   * fails the wait.
   */
  private boolean waitForRevival(WorldEngine world, int playerId, List<WorldEvent> events) {
    int seen = events.size();
    for (int tick = 0; tick < 300; tick++) {
      advance(1_000);
      world.tickOnce();
      boolean revived = events.subList(seen, events.size())
          .stream().anyMatch(event -> event instanceof WorldEvent.SystemMessage message
              && "复活戒指生效，体力恢复.".equals(message.message()));
      if (revived) return true;
      if (events.subList(seen, events.size()).stream().anyMatch(
          event -> event instanceof WorldEvent.ObjectDied died
              && died.victim().id() == playerId)) return false;
    }
    return false;
  }

  private void spawnOrc(WorldEngine world) {
    run(world, world.spawnMonster(MonsterTemplate.orc(), "0", new Position(6, 5), Direction.LEFT));
  }

  private void waitForDeath(WorldEngine world, int playerId, List<WorldEvent> events) {
    int seen = events.size();
    for (int tick = 0; tick < 300; tick++) {
      advance(1_000);
      world.tickOnce();
      if (events.subList(seen, events.size()).stream().anyMatch(
          event -> event instanceof WorldEvent.ObjectDied died
              && died.victim().id() == playerId)) return;
    }
    throw new AssertionError("the orc failed to kill the player");
  }

  private <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  private static <T> T single(List<WorldEvent> events, Class<T> type) {
    T found = singleOrNull(events, type);
    assertNotNull(found, "expected one " + type.getSimpleName());
    return found;
  }

  private static <T> T singleOrNull(List<WorldEvent> events, Class<T> type) {
    List<T> found = events.stream().filter(type::isInstance).map(type::cast).toList();
    return found.size() == 1 ? found.getFirst() : null;
  }
}
