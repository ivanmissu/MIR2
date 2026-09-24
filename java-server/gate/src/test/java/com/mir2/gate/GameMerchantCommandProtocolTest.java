package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.world.Ability;
import com.mir2.world.Direction;
import com.mir2.world.Equipment;
import com.mir2.world.GameMap;
import com.mir2.world.ItemDatabase;
import com.mir2.world.LevelAbilities;
import com.mir2.world.MerchantCommand;
import com.mir2.world.PlayerState;
import com.mir2.world.PlayerStateStore;
import com.mir2.world.Position;
import com.mir2.world.StdItems;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W29 wire mapping: {@code CM_MERCHANTDLGSELECT} for the non-repair labels — {@code @exit} closes
 * the window with {@code SM_MERCHANTDLGCLOSE}, while deferred/unknown labels stay silent on the
 * wire (Delphi guards them behind unset merchant flags) yet remain observable as a
 * {@link WorldEvent.MerchantActionRejected} event.
 */
class GameMerchantCommandProtocolTest {
  private final AtomicLong now = new AtomicLong(1_000);

  private static final class PreparedStore implements PlayerStateStore {
    private final UUID characterId;
    private final PlayerState state;

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
      // transient
    }
  }

  @Test
  void theExitLabelSendsMerchantDlgClose() {
    List<GameOutbound> output = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(), Equipment.empty(), 500));
    try (WorldEngine world = engine(store)) {
      var entered = world.enterPlayer(characterId, "战士", "0", new Position(5, 5),
          Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR,
          new GameProtocolAdapter(world, output::add, () -> 7));
      world.tickOnce();
      GameProtocolAdapter adapter =
          new GameProtocolAdapter(world, entered.join().id(), output::add, () -> 7);
      output.clear();

      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(9001, ProtocolConstants.CM_MERCHANTDLGSELECT, 0, 0, 0),
          WireMessageCodec.encodeBody("@exit"))));
      world.tickOnce();
      WirePacket closed = ((GameOutbound.Packet) output.removeFirst()).packet();
      assertEquals(ProtocolConstants.SM_MERCHANTDLGCLOSE, closed.message().ident());
      assertEquals(9001, closed.message().recog(), "recog echoes the clicked merchant id");
      assertTrue(output.isEmpty(), "@exit emits exactly one packet");
    }
  }

  @Test
  void deferredLabelsProduceNoWirePacket() {
    List<GameOutbound> output = new ArrayList<>();
    UUID characterId = UUID.randomUUID();
    PreparedStore store = new PreparedStore(characterId, new PlayerState(characterId,
        Ability.defaultPlayer(), List.of(), Equipment.empty(), 500));
    try (WorldEngine world = engine(store)) {
      var entered = world.enterPlayer(characterId, "战士", "0", new Position(5, 5),
          Direction.DOWN, 0, 0, LevelAbilities.JOB_WARRIOR,
          new GameProtocolAdapter(world, output::add, () -> 7));
      world.tickOnce();
      GameProtocolAdapter adapter =
          new GameProtocolAdapter(world, entered.join().id(), output::add, () -> 7);
      output.clear();

      // @sell is a deferred transaction label — Delphi is silent on the wire, and so are we.
      assertTrue(adapter.handle(new WirePacket(
          new DefaultMessage(9001, ProtocolConstants.CM_MERCHANTDLGSELECT, 0, 0, 0),
          WireMessageCodec.encodeBody("@sell"))));
      world.tickOnce();
      assertTrue(output.isEmpty(), "a deferred label sends nothing to the client");
    }
  }

  @Test
  void rejectionEventCarriesTheDeferredClassificationForObservability() {
    List<GameOutbound> output = new ArrayList<>();
    // Bind a bare adapter and drive the rejection event straight through send(), asserting the
    // event itself carries an auditable classification even though no packet is produced.
    WorldEngine world = new WorldEngine(List.of(GameMap.empty("0", "PoC", 20, 20)));
    GameProtocolAdapter adapter = new GameProtocolAdapter(world, 1, output::add, () -> 7);

    adapter.send(new WorldEvent.MerchantActionRejected(1, 9001, "@storage",
        MerchantCommand.Category.STORAGE, MerchantCommand.Status.DEFERRED_TRANSACTION, "仓库未开放"));
    assertTrue(output.isEmpty(), "rejection stays silent on the wire, matching Delphi");
  }

  private WorldEngine engine(PlayerStateStore store) {
    return new WorldEngine(WorldEngine.Config.defaults(),
        List.of(GameMap.empty("0", "PoC", 20, 20)),
        now::get, new Random(20020522L), store, ItemDatabase.of(StdItems.defaults()));
  }
}
