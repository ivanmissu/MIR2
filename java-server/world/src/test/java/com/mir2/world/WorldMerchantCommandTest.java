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
 * W29: the Market_Def merchant-label safe boundary. {@code TMerchant.UserSelect} (ObjNpc.pas:1419)
 * dispatches on a fixed set of labels; this engine implements {@code @repair}/{@code @s_repair}
 * (W15) and {@code @exit}, and <em>observably rejects</em> every other label instead of storing it
 * silently — the W29 red line ("未实现脚本不得静默成功").
 */
class WorldMerchantCommandTest {
  private final AtomicLong now = new AtomicLong();

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
  void theExitLabelClosesTheMerchantDialog() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = newStore(characterId);
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      events.clear();

      assertEquals(MerchantSelectOutcome.DIALOG_CLOSED,
          run(world, world.selectMerchantLabel(player.id(), 9001, "@exit")));
      WorldEvent.MerchantDialogClosed closed =
          single(events, WorldEvent.MerchantDialogClosed.class);
      assertEquals(9001, closed.merchantId(), "the echoed merchant id closes the window");
      assertFalse(events.stream().anyMatch(WorldEvent.MerchantActionRejected.class::isInstance),
          "an implemented label is not a rejection");
    }
  }

  @Test
  void deferredTransactionLabelsAreRejectedObservablyWithTheirCategory() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = newStore(characterId);
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      events.clear();

      // @buy is a real Delphi label but needs stock/gold/transaction tests before it opens.
      assertEquals(MerchantSelectOutcome.REJECTED,
          run(world, world.selectMerchantLabel(player.id(), 9001, "@buy")));
      WorldEvent.MerchantActionRejected buy =
          single(events, WorldEvent.MerchantActionRejected.class);
      assertEquals("@buy", buy.label());
      assertEquals(MerchantCommand.Category.TRADE, buy.category());
      assertEquals(MerchantCommand.Status.DEFERRED_TRANSACTION, buy.status());
      assertFalse(buy.unknown(), "@buy is a known-but-deferred label, not an unknown one");
    }
  }

  @Test
  void scriptCallbackLabelsAreRejectedAsDeferredScript() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = newStore(characterId);
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      events.clear();

      assertEquals(MerchantSelectOutcome.REJECTED,
          run(world, world.selectMerchantLabel(player.id(), 9001, "~@repair")));
      WorldEvent.MerchantActionRejected rejected =
          single(events, WorldEvent.MerchantActionRejected.class);
      assertEquals(MerchantCommand.Category.SCRIPT_CALLBACK, rejected.category());
      assertEquals(MerchantCommand.Status.DEFERRED_SCRIPT, rejected.status());
    }
  }

  @Test
  void itemNamingLabelMatchesByPrefixLikeCompareLStr() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = newStore(characterId);
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      events.clear();

      // Delphi CompareLStr(sLabel, sUSEITEMNAME, Length(sUSEITEMNAME)) — a prefix match.
      assertEquals(MerchantSelectOutcome.REJECTED,
          run(world, world.selectMerchantLabel(player.id(), 9001, "@@useitemname屠龙")));
      WorldEvent.MerchantActionRejected rejected =
          single(events, WorldEvent.MerchantActionRejected.class);
      assertEquals(MerchantCommand.Category.ITEM_NAMING, rejected.category());
      assertFalse(rejected.unknown(), "the prefix still resolves to a catalogued command");
    }
  }

  @Test
  void anUnknownLabelIsRejectedAsUnknownWithoutCategory() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = newStore(characterId);
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      events.clear();

      assertEquals(MerchantSelectOutcome.REJECTED,
          run(world, world.selectMerchantLabel(player.id(), 9001, "@definitelynotalabel")));
      WorldEvent.MerchantActionRejected rejected =
          single(events, WorldEvent.MerchantActionRejected.class);
      assertTrue(rejected.unknown(), "a label absent from the catalog is unknown");
      assertNull(rejected.category());
      assertNull(rejected.status());
    }
  }

  @Test
  void nonAtLabelsAndDeadPlayersAreIgnoredWithoutAnyEvent() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = newStore(characterId);
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);
      events.clear();

      // Labels that do not start with '@' are a no-op in Delphi UserSelect.
      assertEquals(MerchantSelectOutcome.IGNORED,
          run(world, world.selectMerchantLabel(player.id(), 9001, "buy")));
      assertEquals(MerchantSelectOutcome.IGNORED,
          run(world, world.selectMerchantLabel(player.id(), 9001, "")));
      assertTrue(events.isEmpty(), "ignored selections emit nothing");
    }
  }

  @Test
  void reSelectingANonRepairLabelDropsBackToNormalRepairMode() {
    List<WorldEvent> events = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = newStore(characterId);
    try (WorldEngine world = engine(store)) {
      WorldObjectSnapshot player = enter(world, characterId, events::add);

      // Pick special repair, then bounce off @exit: Delphi re-assigns m_sScriptLable every
      // @-selection, so the special-repair mode must not survive the @exit.
      run(world, world.selectMerchantLabel(player.id(), 9001, "@s_repair"));
      run(world, world.selectMerchantLabel(player.id(), 9001, "@exit"));
      events.clear();
      assertEquals(MerchantSelectOutcome.REPAIR_DIALOG,
          run(world, world.selectMerchantLabel(player.id(), 9001, "@repair")));
      assertEquals(1, events.stream()
          .filter(WorldEvent.MerchantRepairDialog.class::isInstance).count());
    }
  }

  private PreparedStore newStore(UUID characterId) {
    return new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(), Equipment.empty(), 500));
  }

  private WorldObjectSnapshot enter(WorldEngine world, UUID characterId, WorldEventSink sink) {
    return run(world, world.enterPlayer(
        characterId, "战士", "0", new Position(5, 5), Direction.DOWN, 0, 0,
        LevelAbilities.JOB_WARRIOR, sink));
  }

  private WorldEngine engine(PlayerStateStore store) {
    WorldEngine.Config config = new WorldEngine.Config(Duration.ofMillis(50), 12, 1_000,
        900, 5_000, 180_000, 200, 10 * 60 * 1000, 0);
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
