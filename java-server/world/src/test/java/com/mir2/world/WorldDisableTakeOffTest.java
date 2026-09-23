package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The 禁止取下物品列表 gate wired into the engine: {@code InDisableTakeOffList} blocks
 * {@code ClientTakeOffItems} (ObjBase.pas:17144/17259) and skips a slot inside
 * {@code DropUseItems} (ObjBase.pas:15532).
 */
class WorldDisableTakeOffTest {
  private final AtomicLong now = new AtomicLong();

  /** A plain weapon, StdMode 5, that is not reserved-locked — only the list may lock it. */
  private static StdItem listedSword() {
    return new StdItem("霸者之刃", 5, 1, 10, 0, 0, 0, 0, 1000,
        0, 0, StdItem.packedRange(1, 1), 0, 0, 0, 1, 10);
  }

  @Test
  void listedItemCannotBeTakenOff() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), listedSword())) {
      WorldObjectSnapshot player = enterWith(world, events, listedSword());
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.WEAPON.index(), 1, "霸者之刃")));

      // Before the list is loaded the weapon comes off freely.
      assertTrue(run(world, world.unequip(player.id(), EquipmentSlot.WEAPON.index(), 1, "霸者之刃")));
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.WEAPON.index(), 1, "霸者之刃")));

      // Load the list; now the take-off is refused with CANNOT_TAKE_OFF.
      run(world, world.setDisableTakeOffList(DisableTakeOffList.of(Set.of("霸者之刃"))));
      events.clear();
      assertFalse(run(world, world.unequip(player.id(), EquipmentSlot.WEAPON.index(), 1, "霸者之刃")));
      assertSame(WorldEvent.UnequipRejection.CANNOT_TAKE_OFF,
          single(events, WorldEvent.UnequipRejected.class).detail());
      assertEquals("霸者之刃", run(world, world.equipment(player.id()))
          .at(EquipmentSlot.WEAPON).orElseThrow().name());
    }
  }

  @Test
  void reloadingWithAnEmptyListUnlocksTheItemAgain() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), listedSword())) {
      WorldObjectSnapshot player = enterWith(world, events, listedSword());
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.WEAPON.index(), 1, "霸者之刃")));

      run(world, world.setDisableTakeOffList(DisableTakeOffList.of(Set.of("霸者之刃"))));
      assertFalse(run(world, world.unequip(player.id(), EquipmentSlot.WEAPON.index(), 1, "霸者之刃")));

      // A subsequent load of an empty list (e.g. after editing DisableTakeOffList.txt) clears
      // the gate, exactly like LoadDisableTakeOffList's g_DisableTakeOffList.Clear.
      run(world, world.setDisableTakeOffList(DisableTakeOffList.empty()));
      assertTrue(run(world, world.unequip(player.id(), EquipmentSlot.WEAPON.index(), 1, "霸者之刃")));
    }
  }

  // --- harness (mirrors WorldEquipmentTest) ---

  private WorldObjectSnapshot enterWith(
      WorldEngine world, List<WorldEvent> events, StdItem... items) {
    WorldObjectSnapshot player = run(world,
        world.enterPlayer("战士", "0", new Position(5, 5), Direction.DOWN, events::add));
    for (int index = 0; index < items.length; index++) {
      giveItem(world, player.id(), items[index], index + 1);
    }
    return player;
  }

  private void giveItem(WorldEngine world, int playerId, StdItem template, int expectedMakeIndex) {
    Position cell = run(world, world.snapshot(playerId)).position();
    run(world, world.spawnGroundItem(template.name(), template.looks(), "0", cell));
    assertTrue(run(world, world.pickUp(playerId, cell)));
  }

  private static <T extends WorldEvent> T single(List<WorldEvent> events, Class<T> type) {
    List<T> matches = events.stream().filter(type::isInstance).map(type::cast).toList();
    assertEquals(1, matches.size(), "expected exactly one " + type.getSimpleName() + " in " + events);
    return matches.getFirst();
  }

  private WorldEngine engine(GameMap map, StdItem... catalog) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    List<StdItem> items = new ArrayList<>(List.of(catalog));
    for (StdItem shipped : StdItems.defaults()) {
      if (items.stream().noneMatch(item -> item.name().equals(shipped.name()))) items.add(shipped);
    }
    return new WorldEngine(config, List.of(map), now::get, new Random(20020522L),
        PlayerStateStore.none(), ItemDatabase.of(items));
  }

  private <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }
}
