package com.mir2.persistence;

import com.mir2.character.Character;
import com.mir2.world.AttackKind;
import com.mir2.world.AttackResult;
import com.mir2.world.BackpackItem;
import com.mir2.world.Direction;
import com.mir2.world.GameMap;
import com.mir2.world.MonsterTemplate;
import com.mir2.world.MoveResult;
import com.mir2.world.MovementKind;
import com.mir2.world.PlayerState;
import com.mir2.world.Position;
import com.mir2.world.StdItems;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldObjectSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G0 regression: kill, pick up, disconnect and enter again without losing combat or bag state. */
class WorldPersistenceIntegrationTest {
  @Test
  void combatAndBackpackStateAreRestoredAfterAStoreAndWorldRestart() throws Exception {
    Path file = Files.createTempFile("mir2-relogin-", ".db");
    String url = "jdbc:sqlite:" + file;
    UUID characterId = UUID.randomUUID();
    Character character = new Character(characterId, "hero", "持久战士", 0, 1, 1, 2, 0, 0);
    PlayerState beforeRestart;

    try (SqliteStore store = new SqliteStore(url)) {
      store.saveAccount("hero", new byte[] {1});
      store.save(character);
      AtomicLong now = new AtomicLong();
      try (WorldEngine world = engine(store, now)) {
        WorldObjectSnapshot player = run(world, world.enterPlayer(
            characterId, character.name(), "0", new Position(5, 5), Direction.RIGHT,
            character.feature(), 0, ignored -> {}));
        run(world, world.spawnMonster(
            MonsterTemplate.chicken(), "0", new Position(6, 5), Direction.LEFT));

        for (int swing = 0; swing < 20; swing++) {
          now.addAndGet(1_000);
          AttackResult result = run(world, world.attack(
              player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));
          if (result.victimSnapshot().isPresent()
              && !result.victimSnapshot().orElseThrow().alive()) break;
        }

        now.addAndGet(10_000);
        world.tickOnce();
        MoveResult moved = run(world, world.move(
            player.id(), new Position(6, 5), Direction.RIGHT, MovementKind.WALK));
        assertTrue(moved.moved());
        assertTrue(run(world, world.pickUp(player.id(), new Position(6, 5))));

        // An orc's minimum attack is above the player's maximum defence, guaranteeing durable HP loss.
        run(world, world.spawnMonster(
            MonsterTemplate.orc(), "0", new Position(6, 6), Direction.UP));
        // The orc's attack interval is the imported Monster.DB ATTACK_SPD (2500 ms).
        now.addAndGet(MonsterTemplate.orc().attackIntervalMillis() + 500);
        world.tickOnce();

        beforeRestart = run(world, world.playerState(player.id()));
        assertEquals(MonsterTemplate.chicken().experience(), beforeRestart.ability().experience());
        assertTrue(beforeRestart.ability().hp() < beforeRestart.ability().maxHp(),
            "the adjacent orc should have persisted at least one hit");
        assertEquals(List.of("鸡肉"),
            beforeRestart.backpack().stream().map(item -> item.name()).toList());
        BackpackItem pickedUp = beforeRestart.backpack().getFirst();
        assertTrue(pickedUp.makeIndex() > 0, "picked-up items need a stable make index");
        assertEquals(StdItems.chickenMeat(), pickedUp.item());
        assertEquals(pickedUp.dura(), pickedUp.duraMax());
        run(world, world.leavePlayer(player.id()));
      }
    }

    try (SqliteStore reopened = new SqliteStore(url)) {
      assertEquals(beforeRestart, reopened.load(characterId).orElseThrow());
      AtomicLong now = new AtomicLong(100_000);
      try (WorldEngine restartedWorld = engine(reopened, now)) {
        WorldObjectSnapshot restored = run(restartedWorld, restartedWorld.enterPlayer(
            characterId, character.name(), "0", new Position(5, 5), Direction.DOWN,
            character.feature(), 0, ignored -> {}));
        assertEquals(beforeRestart.ability(), restored.ability());
        PlayerState restoredPrivateState = run(
            restartedWorld, restartedWorld.playerState(restored.id()));
        assertEquals(beforeRestart, restoredPrivateState);
        assertFalse(restoredPrivateState.backpack().isEmpty());
        // The make index must survive the restart unchanged; the allocator continues past it.
        assertEquals(beforeRestart.backpack().getFirst().makeIndex(),
            restoredPrivateState.backpack().getFirst().makeIndex());
        assertEquals(StdItems.chickenMeat(), restoredPrivateState.backpack().getFirst().item());
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static WorldEngine engine(SqliteStore store, AtomicLong now) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)),
        now::get, new Random(20020522L), store, store.itemDatabase());
  }

  private static <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }
}
