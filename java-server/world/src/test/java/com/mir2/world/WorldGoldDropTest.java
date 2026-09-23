package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P3 ground-gold slice: MonItems 金币 rows become TMapItem piles and fill the wallet on pickup. */
class WorldGoldDropTest {
  private final AtomicLong now = new AtomicLong();

  @Test
  void groundGoldPilesMergeByCellUpdateShapeAndPickupToWallet() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(PlayerStateStore.none())) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));

      GroundItem first = run(world, world.spawnGroundGold(25, "0", new Position(5, 5)));
      assertEquals(25, first.count());
      assertEquals(112, first.looks());

      GroundItem merged = run(world, world.spawnGroundGold(50, "0", new Position(5, 5)));
      assertEquals(first.id(), merged.id(), "gold on the same cell merges up to 2000 coins");
      assertEquals(75, merged.count());
      assertEquals(114, merged.looks(), "GetGoldShape: >=70 uses shape 114");
      assertEquals(List.of(merged), run(world, world.itemsAt("0", new Position(5, 5))));

      events.clear();
      assertTrue(run(world, world.pickUp(player.id(), new Position(5, 5))));

      WorldEvent.GoldPickedUp pickedUp = events.stream()
          .filter(WorldEvent.GoldPickedUp.class::isInstance)
          .map(WorldEvent.GoldPickedUp.class::cast)
          .findFirst().orElseThrow();
      assertEquals(75, pickedUp.item().count());
      assertEquals(75, pickedUp.gold());
      assertTrue(events.stream().anyMatch(WorldEvent.ItemDisappeared.class::isInstance));
      assertTrue(run(world, world.itemsAt("0", new Position(5, 5))).isEmpty());
      assertEquals(75, run(world, world.playerState(player.id())).gold());
      assertTrue(run(world, world.playerState(player.id())).backpack().isEmpty(),
          "gold pickup must not create an SM_ADDITEM/backpack entry");
    }
  }

  @Test
  void monsterGoldRowsRollCoinAmountAndLandAsGoldPiles() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(PlayerStateStore.none())) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer("战士", "0", new Position(5, 5), Direction.RIGHT, events::add));
      MonsterTemplate goldDummy = new MonsterTemplate(
          "金币木桩", MonsterTemplate.packFeature(55, 0, 0),
          new Ability(1, 1, 0, 0, 0, 0, 0, 0, 1, 0),
          1, 10_000, 10_000, 0, MonsterBehavior.STATIONARY,
          List.of(), List.of(new MonsterDropTable.GoldDrop(1, 100)));
      run(world, world.spawnMonster(goldDummy, "0", new Position(6, 5), Direction.LEFT));

      now.addAndGet(5_000);
      AttackResult result = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
      assertTrue(result.victimSnapshot().orElseThrow().ability().hp() <= 0);

      WorldEvent.ItemAppeared appeared = events.stream()
          .filter(WorldEvent.ItemAppeared.class::isInstance)
          .map(WorldEvent.ItemAppeared.class::cast)
          .filter(event -> event.item().gold())
          .findFirst().orElseThrow();
      GroundItem pile = appeared.item();
      assertEquals(GroundItem.GOLD_NAME, pile.name());
      assertEquals(new Position(6, 5), pile.position());
      assertTrue(pile.count() >= 50 && pile.count() < 150,
          "UsrEngn.MonGetRandomItems uses Count div 2 + Random(Count)");
      assertEquals(expectedGoldShape(pile.count()), pile.looks());
    }
  }

  @Test
  void goldPickupRefusesPilesThatWouldExceedWalletCap() {
    UUID characterId = UUID.randomUUID();
    InMemoryStore store = new InMemoryStore(new PlayerState(
        characterId, Ability.defaultPlayer(), List.of(), Equipment.empty(), PlayerState.MAX_GOLD - 10));
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = run(world,
          world.enterPlayer(characterId, "战士", "0", new Position(5, 5), Direction.RIGHT,
              0, 0, events::add));
      GroundItem pile = run(world, world.spawnGroundGold(11, "0", new Position(5, 5)));

      events.clear();
      assertFalse(run(world, world.pickUp(player.id(), new Position(5, 5))));
      WorldEvent.PickupRejected rejected = assertInstanceOf(WorldEvent.PickupRejected.class, events.getFirst());
      assertEquals(WorldEvent.PickupRejection.WALLET_FULL, rejected.reason());
      assertEquals(List.of(pile), run(world, world.itemsAt("0", new Position(5, 5))));
      assertEquals(PlayerState.MAX_GOLD - 10, run(world, world.playerState(player.id())).gold());
    }
  }

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config = new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000,
        900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)), now::get,
        new Random(20020522L), store, ItemDatabase.of(StdItems.defaults()));
  }

  private int expectedGoldShape(int gold) {
    if (gold >= 1000) return 116;
    if (gold >= 300) return 115;
    if (gold >= 70) return 114;
    if (gold >= 30) return 113;
    return 112;
  }

  private <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  private static final class InMemoryStore implements PlayerStateStore {
    private PlayerState state;

    private InMemoryStore(PlayerState state) {
      this.state = state;
    }

    @Override
    public Optional<PlayerState> load(UUID characterId) {
      return state != null && state.characterId().equals(characterId) ? Optional.of(state) : Optional.empty();
    }

    @Override
    public void save(PlayerState state) {
      this.state = state;
    }
  }
}
