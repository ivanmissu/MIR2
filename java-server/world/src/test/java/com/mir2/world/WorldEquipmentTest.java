package com.mir2.world;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W12 item slice: take-on/take-off, eating and dropping, mirroring
 * {@code ClientTakeOnItems}/{@code ClientTakeOffItems}/{@code ClientUseItems}/
 * {@code ClientDropItem} in ObjBase.pas.
 */
class WorldEquipmentTest {
  private final AtomicLong now = new AtomicLong();

  /** 布衣(男): StdMode 10, male-only dress, Shape 1 so the Feature word visibly changes. */
  private static StdItem clothMale() {
    return new StdItem("布衣(男)", 10, 1, 5, 0, 0, 0, 100, 1000,
        StdItem.packedRange(1, 4), 0, 0, 0, 0, 0, 0, 100);
  }

  /** 木剑: the shipped starter weapon, StdMode 5. */
  private static StdItem woodenSword() {
    return StdItems.woodenSword();
  }

  /** A dress that demands level 40, to exercise the CheckTakeOnItems Need=0 branch. */
  private static StdItem highLevelCloth() {
    return new StdItem("天尊道袍(男)", 10, 7, 5, 0, 0, 0, 130, 1000,
        StdItem.packedRange(4, 9), 0, 0, 0, 0, /*need*/ 0, /*needLevel*/ 40, 5000);
  }

  /** Reserved bit 2/4 item: mirrors 赤血魔剑-style cannot-take-off behaviour. */
  private static StdItem lockedSword() {
    return new StdItem("锁定剑", 5, 1, 1, 0, 0,
        /*reserved*/ 4, /*needIdentify*/ 0, 31, 1000,
        0, 0, StdItem.packedRange(1, 1), 0, 0, 0, 1, 10);
  }

  @Test
  void takeOnMovesTheItemIntoItsSlotAppliesStatsAndChangesTheFeature() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), clothMale(), woodenSword())) {
      WorldObjectSnapshot player = enterWith(world, events, clothMale(), woodenSword());
      Ability naked = player.ability();
      events.clear();

      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.DRESS.index(), 1, "布衣(男)")));

      // The dress left the bag and now occupies U_DRESS.
      PlayerState state = run(world, world.playerState(player.id()));
      assertEquals(List.of("木剑"), state.backpack().stream().map(BackpackItem::name).toList());
      assertEquals("布衣(男)", state.equipment().at(EquipmentSlot.DRESS).orElseThrow().name());

      // ApplyItemParameters ITEM_ARMOR adds the packed AC range on top of the base defence.
      Ability dressed = run(world, world.snapshot(player.id())).ability();
      assertEquals(naked.minAc() + 1, dressed.minAc());
      assertEquals(naked.maxAc() + 4, dressed.maxAc());

      // GetFeature rebuilds the dress byte from the worn Shape: Shape 1, gender 0 -> 2.
      int dressByte = (run(world, world.snapshot(player.id())).feature() >>> 24) & 0xff;
      assertEquals(2, dressByte);

      WorldEvent.ItemEquipped equipped = single(events, WorldEvent.ItemEquipped.class);
      assertSame(EquipmentSlot.DRESS, equipped.slot());
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.AbilityChanged));
      // WeightChanged reports the dress under WearWeight, not HandWeight.
      WorldEvent.WeightChanged weight = single(events, WorldEvent.WeightChanged.class);
      assertEquals(5, weight.wearWeight());
      assertEquals(0, weight.handWeight());
    }
  }

  @Test
  void weaponWeightCountsAgainstHandWeightAndTakeOffReturnsItemToTheBag() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), clothMale(), woodenSword())) {
      WorldObjectSnapshot player = enterWith(world, events, woodenSword());
      events.clear();
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.WEAPON.index(), 1, "木剑")));

      WorldEvent.WeightChanged worn = single(events, WorldEvent.WeightChanged.class);
      assertEquals(woodenSword().weight(), worn.handWeight(), "weapon weight belongs to HandWeight");
      assertEquals(0, worn.wearWeight());
      assertEquals(0, worn.weight(), "the bag is empty once the sword is worn");

      events.clear();
      assertTrue(run(world, world.unequip(player.id(), EquipmentSlot.WEAPON.index(), 1, "木剑")));
      PlayerState state = run(world, world.playerState(player.id()));
      assertTrue(state.equipment().isEmpty());
      assertEquals(List.of("木剑"), state.backpack().stream().map(BackpackItem::name).toList());
      WorldEvent.ItemUnequipped off = single(events, WorldEvent.ItemUnequipped.class);
      assertSame(EquipmentSlot.WEAPON, off.slot());
    }
  }

  @Test
  void reservedLockBitsRefuseTakingOffAndSwappingTheWornItem() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), lockedSword(), woodenSword())) {
      WorldObjectSnapshot player = enterWith(world, events, lockedSword(), woodenSword());
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.WEAPON.index(), 1, "锁定剑")));

      events.clear();
      assertFalse(run(world, world.unequip(player.id(), EquipmentSlot.WEAPON.index(), 1, "锁定剑")));
      assertSame(WorldEvent.UnequipRejection.CANNOT_TAKE_OFF,
          single(events, WorldEvent.UnequipRejected.class).detail());
      assertEquals("锁定剑", run(world, world.equipment(player.id()))
          .at(EquipmentSlot.WEAPON).orElseThrow().name());

      events.clear();
      assertFalse(run(world, world.equip(player.id(), EquipmentSlot.WEAPON.index(), 2, "木剑")));
      assertSame(WorldEvent.EquipRejection.CANNOT_TAKE_OFF_EXISTING,
          single(events, WorldEvent.EquipRejected.class).detail());
      assertEquals(List.of("木剑"), run(world, world.playerState(player.id()))
          .backpack().stream().map(BackpackItem::name).toList());
    }
  }

  @Test
  void takeOnIntoAWrongSlotOrBelowTheLevelRequirementIsRejected() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(
        GameMap.empty("0", "PoC", 20, 20), clothMale(), woodenSword(), highLevelCloth())) {
      WorldObjectSnapshot player = enterWith(world, events, clothMale(), highLevelCloth());
      events.clear();

      // CheckUserItems: a StdMode 10 dress may not go into the weapon slot.
      assertFalse(run(world, world.equip(player.id(), EquipmentSlot.WEAPON.index(), 1, "布衣(男)")));
      assertSame(WorldEvent.EquipRejection.SLOT_MISMATCH,
          single(events, WorldEvent.EquipRejected.class).detail());

      // CheckTakeOnItems Need=0: the level-40 robe is refused for a level-1 character.
      events.clear();
      assertFalse(run(world, world.equip(player.id(), EquipmentSlot.DRESS.index(), 2, "天尊道袍(男)")));
      assertSame(WorldEvent.EquipRejection.REQUIREMENT_NOT_MET,
          single(events, WorldEvent.EquipRejected.class).detail());

      // Nothing moved out of the bag.
      assertEquals(2, run(world, world.playerState(player.id())).backpack().size());
    }
  }

  @Test
  void takeOnIntoAnOccupiedSlotSwapsTheOldItemBackIntoTheBag() {
    StdItem otherCloth = new StdItem("战神盔甲(男)", 10, 3, 6, 0, 0, 0, 110, 1000,
        StdItem.packedRange(2, 6), 0, 0, 0, 0, 0, 0, 900);
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), clothMale(), otherCloth)) {
      WorldObjectSnapshot player = enterWith(world, events, clothMale(), otherCloth);
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.DRESS.index(), 1, "布衣(男)")));

      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.DRESS.index(), 2, "战神盔甲(男)")));
      PlayerState state = run(world, world.playerState(player.id()));
      assertEquals("战神盔甲(男)", state.equipment().at(EquipmentSlot.DRESS).orElseThrow().name());
      assertEquals(List.of("布衣(男)"), state.backpack().stream().map(BackpackItem::name).toList());
    }
  }

  @Test
  void eatingAPotionRestoresHealthRemovesTheItemAndRefreshesWeight() {
    List<WorldEvent> events = new ArrayList<>();
    StdItem potion = StdItems.smallHealingPotion();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), potion)) {
      WorldObjectSnapshot player = enterWith(world, events, potion);
      // Take a hit first so the restore has room to work.
      WorldObjectSnapshot orc = run(world,
          world.spawnMonster(MonsterTemplate.orc(), "0", new Position(6, 5), Direction.LEFT));
      // The orc's attack interval is the imported Monster.DB ATTACK_SPD (2500 ms).
      advance(MonsterTemplate.orc().attackIntervalMillis() + 500);
      world.tickOnce();
      int damaged = run(world, world.snapshot(player.id())).ability().hp();
      assertTrue(damaged < 100, "the orc must land a hit before the potion is drunk");
      events.clear();

      assertTrue(run(world, world.useItem(player.id(), 1, "金创药(小量)")));
      assertTrue(run(world, world.playerState(player.id())).backpack().isEmpty());
      assertTrue(run(world, world.snapshot(player.id())).ability().hp() > damaged);
      assertEquals(1, events.stream().filter(e -> e instanceof WorldEvent.ItemUsed).count());
      assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.WeightChanged));
      assertFalse(orc.name().isBlank());
    }
  }

  @Test
  void droppingPutsTheItemOnTheGroundAndSafeZonesRefuse() {
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), woodenSword())) {
      WorldObjectSnapshot player = enterWith(world, events, woodenSword());
      events.clear();

      assertTrue(run(world, world.dropItem(player.id(), 1, "木剑")));
      assertTrue(run(world, world.playerState(player.id())).backpack().isEmpty());
      List<GroundItem> ground = run(world, world.itemsAt("0", new Position(5, 5)));
      assertEquals(List.of("木剑"), ground.stream().map(GroundItem::name).toList());
      assertEquals(1, events.stream().filter(e -> e instanceof WorldEvent.ItemDropped).count());
    }

    // The same drop inside a SAFE map is refused before the bag is touched.
    MapFlags safe = new MapFlags(true, false, false, false, false, false, false, "",
        false, true, false, -1, -1, false, false);
    try (WorldEngine world = engine(
        GameMap.empty("0", "比奇省", 20, 20, safe), woodenSword())) {
      List<WorldEvent> safeEvents = new ArrayList<>();
      WorldObjectSnapshot player = enterWith(world, safeEvents, woodenSword());
      safeEvents.clear();
      assertFalse(run(world, world.dropItem(player.id(), 1, "木剑")));
      assertSame(WorldEvent.DropRejection.SAFE_ZONE,
          single(safeEvents, WorldEvent.DropItemRejected.class).reason());
      assertEquals(1, run(world, world.playerState(player.id())).backpack().size());
    }
  }

  @Test
  void successfulMeleeHitWearsAndDestroysAZeroDurabilityWeapon() {
    StdItem fragile = new StdItem("练习剑", 5, 0, 1, 0, 0, 0, 77, 1, 0, 0,
        StdItem.packedRange(100, 100), 0, 0, 0, 0, 1);
    List<WorldEvent> events = new ArrayList<>();
    try (WorldEngine world = engine(GameMap.empty("0", "PoC", 20, 20), fragile)) {
      WorldObjectSnapshot player = enterWith(world, events, fragile);
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.WEAPON.index(), 1, "练习剑")));
      run(world, world.spawnMonster(MonsterTemplate.orc(), "0",
          new Position(6, 5), Direction.LEFT));
      events.clear();
      advance(1_000);

      AttackResult hit = run(world,
          world.attack(player.id(), new Position(5, 5), Direction.RIGHT, AttackKind.HIT));

      assertTrue(hit.damage() > 0);
      assertTrue(run(world, world.equipment(player.id())).at(EquipmentSlot.WEAPON).isEmpty());
      WorldEvent.ItemDurabilityChanged wear =
          single(events, WorldEvent.ItemDurabilityChanged.class);
      assertEquals(0, wear.dura());
      assertTrue(wear.broken());
      assertEquals(1, wear.makeIndex());
    }
  }

  @Test
  void wornEquipmentSurvivesRelogAndItsBonusIsReapplied() {
    RecordingStore store = new RecordingStore();
    java.util.UUID characterId = java.util.UUID.randomUUID();
    Ability dressedAbility;
    try (WorldEngine world = engine(store, GameMap.empty("0", "PoC", 20, 20), clothMale())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "战士", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, events::add));
      giveItem(world, player.id(), clothMale(), 1);
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.DRESS.index(), 1, "布衣(男)")));
      dressedAbility = run(world, world.snapshot(player.id())).ability();
      run(world, world.leavePlayer(player.id()));
    }

    try (WorldEngine world = engine(store, GameMap.empty("0", "PoC", 20, 20), clothMale())) {
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "战士", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, ignored -> {}));
      PlayerState state = run(world, world.playerState(player.id()));
      assertEquals("布衣(男)", state.equipment().at(EquipmentSlot.DRESS).orElseThrow().name());
      // The AC bonus is re-derived by RecalcAbilitys, not read back from the database.
      assertEquals(dressedAbility.minAc(), player.ability().minAc());
      assertEquals(dressedAbility.maxAc(), player.ability().maxAc());
    }
  }

  @Test
  void loginAnnouncesTheWornSetButStaysSilentForAnUnequippedCharacter() {
    RecordingStore store = new RecordingStore();
    java.util.UUID characterId = java.util.UUID.randomUUID();
    try (WorldEngine world = engine(store, GameMap.empty("0", "PoC", 20, 20), clothMale())) {
      List<WorldEvent> events = new ArrayList<>();
      WorldObjectSnapshot player = run(world, world.enterPlayer(characterId, "战士", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, events::add));
      // Nothing is worn yet, so the login sequence carries no SM_SENDUSEITEMS.
      assertFalse(events.stream().anyMatch(e -> e instanceof WorldEvent.EquipmentSent));

      giveItem(world, player.id(), clothMale(), 1);
      assertTrue(run(world, world.equip(player.id(), EquipmentSlot.DRESS.index(), 1, "布衣(男)")));
      run(world, world.leavePlayer(player.id()));
    }

    try (WorldEngine world = engine(store, GameMap.empty("0", "PoC", 20, 20), clothMale())) {
      List<WorldEvent> events = new ArrayList<>();
      run(world, world.enterPlayer(characterId, "战士", "0",
          new Position(5, 5), Direction.DOWN, 0, 0, events::add));
      // ObjBase.pas:16572 RM_SENDUSEITEMS: the restored dress is announced on login.
      WorldEvent.EquipmentSent sent = single(events, WorldEvent.EquipmentSent.class);
      assertEquals("布衣(男)", sent.equipment().at(EquipmentSlot.DRESS).orElseThrow().name());
    }
  }

  /** In-memory {@link PlayerStateStore} so a relog can be simulated without SQLite. */
  private static final class RecordingStore implements PlayerStateStore {
    private final java.util.Map<java.util.UUID, PlayerState> saved = new java.util.HashMap<>();

    @Override
    public java.util.Optional<PlayerState> load(java.util.UUID characterId) {
      return java.util.Optional.ofNullable(saved.get(characterId));
    }

    @Override
    public void save(PlayerState state) {
      saved.put(state.characterId(), state);
    }
  }

  private WorldObjectSnapshot enterWith(
      WorldEngine world, List<WorldEvent> events, StdItem... items) {
    WorldObjectSnapshot player = run(world,
        world.enterPlayer("战士", "0", new Position(5, 5), Direction.DOWN, events::add));
    for (int index = 0; index < items.length; index++) {
      giveItem(world, player.id(), items[index], index + 1);
    }
    return player;
  }

  /** Drops a template on the player's cell and picks it up, so the bag entry is engine-made. */
  private void giveItem(WorldEngine world, int playerId, StdItem template, int expectedMakeIndex) {
    Position cell = run(world, world.snapshot(playerId)).position();
    run(world, world.spawnGroundItem(template.name(), template.looks(), "0", cell));
    assertTrue(run(world, world.pickUp(playerId, cell)));
    PlayerState state = run(world, world.playerState(playerId));
    assertEquals(expectedMakeIndex, state.backpack().getLast().makeIndex());
  }

  private static <T extends WorldEvent> T single(List<WorldEvent> events, Class<T> type) {
    List<T> matches = events.stream().filter(type::isInstance).map(type::cast).toList();
    assertEquals(1, matches.size(), "expected exactly one " + type.getSimpleName() + " in " + events);
    return matches.getFirst();
  }

  private WorldEngine engine(GameMap map, StdItem... catalog) {
    return engine(PlayerStateStore.none(), map, catalog);
  }

  private WorldEngine engine(PlayerStateStore store, GameMap map, StdItem... catalog) {
    WorldEngine.Config config =
        new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000, 900, 5_000, 180_000);
    List<StdItem> items = new ArrayList<>(List.of(catalog));
    for (StdItem shipped : StdItems.defaults()) {
      if (items.stream().noneMatch(item -> item.name().equals(shipped.name()))) items.add(shipped);
    }
    return new WorldEngine(config, List.of(map), now::get, new Random(20020522L),
        store, ItemDatabase.of(items));
  }

  private void advance(long millis) {
    now.addAndGet(millis);
  }

  private <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }
}
