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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W15: merchant repair — {@code TMerchant.UserSelect}'s label state machine,
 * {@code ClientQueryRepairCost} and {@code ClientRepairItem} (ObjNpc.pas), plus the gold
 * model that pays for it ({@code m_nGold}, the {@code nTestGold} login floor).
 */
class WorldRepairTest {
  private final AtomicLong now = new AtomicLong();

  /**
   * 测试剑: StdMode 5 weapon, DuraMax 100, price 101. The awkward price is deliberate —
   * 101 is not a multiple of three, so the special-repair quote (3 × the truncated base)
   * and the special-repair charge (the un-truncated base) genuinely disagree by one coin.
   */
  private static StdItem testSword() {
    return new StdItem("测试剑", 5, 0, 2, 0, 0, 0, 1, 100, 0, 0,
        StdItem.packedRange(2, 5), 0, 0, 0, 0, 101);
  }

  /** 宝石: StdMode 43 is the one category ClientRepairItem refuses outright. */
  private static StdItem gem() {
    return new StdItem("测试宝石", 43, 0, 1, 0, 0, 0, 50, 100, 0, 0, 0, 0, 0, 0, 0, 500);
  }

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
  void theRepairLabelOpensTheDialogAndNormalRepairLowersDuraMax() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(),
        List.of(new BackpackItem(testSword(), 3, 40, 100)),
        Equipment.empty(), 500));
    try (WorldEngine world = engine(store, 0)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      events.clear();

      // CM_MERCHANTDLGSELECT with '@repair': TMerchant.UserSelect stores the label and
      // answers SM_SENDUSERREPAIR so the client opens its repair dialog.
      assertEquals(MerchantSelectOutcome.REPAIR_DIALOG,
          run(world, world.selectMerchantLabel(player.id(), 9001, "@repair")));
      WorldEvent.MerchantRepairDialog dialog =
          single(events, WorldEvent.MerchantRepairDialog.class);
      assertEquals(9001, dialog.merchantId());

      // ClientQueryRepairCost: Round(101 div 3 / 100 * 60) = Round(19.8) = 20.
      events.clear();
      assertEquals(20, run(world, world.queryRepairCost(player.id(), 3, "测试剑")));
      assertEquals(20, single(events, WorldEvent.RepairCostResolved.class).cost());

      // ClientRepairItem: gold 500 - 20 = 480; the lost 60 durability costs DuraMax
      // 60 div 30 = 2, so the sword returns as 98/98 — the classic repair decay.
      events.clear();
      assertTrue(run(world, world.repairItem(player.id(), 3, "测试剑")));
      WorldEvent.ItemRepaired repaired = single(events, WorldEvent.ItemRepaired.class);
      assertEquals(480, repaired.gold());
      assertEquals(98, repaired.dura());
      assertEquals(98, repaired.duraMax());

      PlayerState state = run(world, world.playerState(player.id()));
      assertEquals(480, state.gold());
      BackpackItem sword = state.backpack().getFirst();
      assertEquals(98, sword.dura());
      assertEquals(98, sword.duraMax());
      // The store saw the same thing a save would write.
      assertEquals(480, store.state.gold());
      assertEquals(98, store.state.backpack().getFirst().duraMax());
    }
  }

  @Test
  void specialRepairKeepsDuraMaxAndChargesOneMoreThanTheQuote() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(),
        List.of(new BackpackItem(testSword(), 3, 40, 100)),
        Equipment.empty(), 500));
    try (WorldEngine world = engine(store, 0)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      assertEquals(MerchantSelectOutcome.REPAIR_DIALOG,
          run(world, world.selectMerchantLabel(player.id(), 9001, "@s_repair")));

      // The quote triples the truncated base: 3 * 20 = 60...
      assertEquals(60, run(world, world.queryRepairCost(player.id(), 3, "测试剑")));

      // ...but the charge triples nPrice *before* the div 3, which cancels out and lands on
      // the un-truncated base: Round(101 / 100 * 60) = Round(60.6) = 61. The one-coin gap
      // between the two is the original engine's behaviour, reproduced on purpose.
      events.clear();
      assertTrue(run(world, world.repairItem(player.id(), 3, "测试剑")));
      WorldEvent.ItemRepaired repaired = single(events, WorldEvent.ItemRepaired.class);
      assertEquals(500 - 61, repaired.gold());
      assertEquals(100, repaired.dura());
      assertEquals(100, repaired.duraMax(), "special repair never lowers DuraMax");

      PlayerState state = run(world, world.playerState(player.id()));
      assertEquals(100, state.backpack().getFirst().duraMax());
    }
  }

  @Test
  void theLabelModeComparisonIsCaseSensitiveWhileTheDialogOpenIsNot() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(),
        List.of(new BackpackItem(testSword(), 3, 40, 100)),
        Equipment.empty(), 500));
    try (WorldEngine world = engine(store, 0)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);

      // CompareText dispatch opens the repair dialog even in upper case...
      assertEquals(MerchantSelectOutcome.REPAIR_DIALOG,
          run(world, world.selectMerchantLabel(player.id(), 9001, "@S_REPAIR")));
      assertEquals(1, events.stream()
          .filter(WorldEvent.MerchantRepairDialog.class::isInstance).count());
      // ...but the mode check is Delphi's '=' on m_sScriptLable, which is case-sensitive:
      // '@S_REPAIR' != '@s_repair', so this is still a *normal* repair.
      events.clear();
      assertTrue(run(world, world.repairItem(player.id(), 3, "测试剑")));
      assertEquals(98, single(events, WorldEvent.ItemRepaired.class).duraMax());
    }
  }

  @Test
  void aWalletThatCannotCoverTheChargeFailsWithoutTouchingTheItem() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(),
        List.of(new BackpackItem(testSword(), 3, 40, 100)),
        Equipment.empty(), 10));
    try (WorldEngine world = engine(store, 0)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);

      events.clear();
      assertFalse(run(world, world.repairItem(player.id(), 3, "测试剑")));
      assertEquals(1, events.stream()
          .filter(WorldEvent.RepairRejected.class::isInstance).count());
      PlayerState state = run(world, world.playerState(player.id()));
      assertEquals(10, state.gold(), "DecGold failed, nothing was deducted");
      assertEquals(40, state.backpack().getFirst().dura());
      assertEquals(100, state.backpack().getFirst().duraMax());
    }
  }

  @Test
  void fullDurabilityAndGemsAreRefusedAndMissingItemsStaySilent() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(),
        List.of(
            new BackpackItem(testSword(), 3, 100, 100),
            new BackpackItem(gem(), 4, 40, 100)),
        Equipment.empty(), 500));
    try (WorldEngine world = engine(store, 0)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);

      // DuraMax > Dura fails: the quote is Delphi's -1 "??? 金币" answer.
      events.clear();
      assertEquals(-1, run(world, world.queryRepairCost(player.id(), 3, "测试剑")));
      assertEquals(-1, single(events, WorldEvent.RepairCostResolved.class).cost());
      events.clear();
      assertFalse(run(world, world.repairItem(player.id(), 3, "测试剑")));
      assertEquals(1, events.stream()
          .filter(WorldEvent.RepairRejected.class::isInstance).count());

      // StdMode 43 (宝石) is refused outright by ClientRepairItem.
      events.clear();
      assertTrue(run(world, world.queryRepairCost(player.id(), 4, "测试宝石")) > 0);
      events.clear();
      assertFalse(run(world, world.repairItem(player.id(), 4, "测试宝石")));
      assertEquals(1, events.stream()
          .filter(WorldEvent.RepairRejected.class::isInstance).count());

      // A MakeIndex/name that matches nothing in the bag gets no reply at all — Delphi
      // simply exits ClientRepairItem without sending RM_USERREPAIRITEM_FAIL.
      events.clear();
      assertNull(run(world, world.queryRepairCost(player.id(), 999, "测试剑")));
      assertNull(run(world, world.repairItem(player.id(), 999, "测试剑")));
      assertNull(run(world, world.repairItem(player.id(), 3, "不存在")));
      assertTrue(events.isEmpty(), "silent paths must not emit anything");

      // Labels that do not start with '@' are ignored entirely.
      assertEquals(MerchantSelectOutcome.IGNORED,
          run(world, world.selectMerchantLabel(player.id(), 9001, "repair")));
      assertEquals(MerchantSelectOutcome.IGNORED,
          run(world, world.selectMerchantLabel(player.id(), 9001, "")));
    }
  }

  @Test
  void theTestGoldFloorTopsUpTheWalletOnLogin() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(), Equipment.empty(), 120));
    try (WorldEngine world = engine(store, 300)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);

      // UserLogon's boTestServer block: m_nGold < nTestGold tops the wallet up.
      assertEquals(300, run(world, world.playerState(player.id())).gold());
      assertEquals(1, events.stream()
          .filter(WorldEvent.GoldChanged.class::isInstance).count());
      assertEquals(300, events.stream()
          .filter(WorldEvent.GoldChanged.class::isInstance)
          .map(WorldEvent.GoldChanged.class::cast).findFirst().orElseThrow().gold());

      // A wallet already at or above the floor is left alone and hears nothing: leave and
      // re-enter with the store now holding the floored 300.
      run(world, world.leavePlayer(player.id()));
      events.clear();
      WorldObjectSnapshot again = enter(world, characterId, events::add);
      assertEquals(300, run(world, world.playerState(again.id())).gold());
      assertTrue(events.stream().noneMatch(WorldEvent.GoldChanged.class::isInstance),
          "300 is not below the floor, no top-up happens");
    }
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID characterId, WorldEventSink sink) {
    return run(world, world.enterPlayer(
        characterId, "战士", "0", new Position(5, 5), Direction.DOWN, 0, 0,
        LevelAbilities.JOB_WARRIOR, sink));
  }

  private WorldEngine engine(PlayerStateStore store, long testGold) {
    WorldEngine.Config config = new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000,
        900, 5_000, 180_000, 200, 10 * 60 * 1000, testGold);
    return new WorldEngine(config, List.of(GameMap.empty("0", "PoC", 20, 20)), now::get,
        new Random(20020522L), store, ItemDatabase.of(StdItems.defaults()));
  }

  private <T> T run(WorldEngine world, CompletableFuture<T> future) {
    world.tickOnce();
    return future.join();
  }

  private static <T> T single(List<WorldEvent> events, Class<T> type) {
    List<T> found = events.stream().filter(type::isInstance).map(type::cast).toList();
    assertEquals(1, found.size(), "expected exactly one " + type.getSimpleName());
    return found.getFirst();
  }
}
