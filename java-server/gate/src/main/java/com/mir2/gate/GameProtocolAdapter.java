package com.mir2.gate;

import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import com.mir2.protocol.SixBitCodec;
import com.mir2.world.AttackKind;
import com.mir2.world.BackpackItem;
import com.mir2.world.Direction;
import com.mir2.world.GroundItem;
import com.mir2.world.LearnedMagic;
import com.mir2.world.MovementKind;
import com.mir2.world.Position;
import com.mir2.world.WorldEngine;
import com.mir2.world.WorldEvent;
import com.mir2.world.WorldEventSink;
import com.mir2.world.WorldObjectSnapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Converts the movement, melee combat, chat, doors, day/night and map-entry between legacy packets and the world. */
public final class GameProtocolAdapter implements WorldEventSink {
  private static final Logger LOG = Logger.getLogger(GameProtocolAdapter.class.getName());
  private final WorldEngine world;
  private final Consumer<GameOutbound> output;
  private final LongSupplier serverTick;
  private volatile int playerId;

  /** Creates an unbound adapter; its player id is bound by the first MapEntered callback. */
  public GameProtocolAdapter(WorldEngine world, Consumer<GameOutbound> output) {
    this(world, 0, output, () -> System.nanoTime() / 1_000_000L);
  }

  public GameProtocolAdapter(WorldEngine world, int playerId, Consumer<GameOutbound> output) {
    this(world, playerId, output, () -> System.nanoTime() / 1_000_000L);
  }

  GameProtocolAdapter(WorldEngine world, Consumer<GameOutbound> output, LongSupplier serverTick) {
    this(world, 0, output, serverTick);
  }

  GameProtocolAdapter(
      WorldEngine world, int playerId, Consumer<GameOutbound> output, LongSupplier serverTick) {
    this.world = Objects.requireNonNull(world, "world");
    if (playerId < 0) throw new IllegalArgumentException("playerId must not be negative");
    this.playerId = playerId;
    this.output = Objects.requireNonNull(output, "output");
    this.serverTick = Objects.requireNonNull(serverTick, "serverTick");
  }

  /** Returns false for unhandled messages outside the supported gameplay subset. */
  public boolean handle(WirePacket packet) {
    Objects.requireNonNull(packet, "packet");
    DefaultMessage message = packet.message();
    if (!isSupported(message.ident())) return false;
    try {
      int boundPlayer = requirePlayerId();
      switch (message.ident()) {
        case ProtocolConstants.CM_TURN -> reportExceptionalFailure(
            world.turn(boundPlayer, unpackPosition(message.recog()), Direction.fromCode(message.tag())));
        case ProtocolConstants.CM_WALK -> reportExceptionalFailure(world.move(boundPlayer,
            unpackPosition(message.recog()), Direction.fromCode(message.tag()), MovementKind.WALK));
        case ProtocolConstants.CM_RUN -> reportExceptionalFailure(world.move(boundPlayer,
            unpackPosition(message.recog()), Direction.fromCode(message.tag()), MovementKind.RUN));
        case ProtocolConstants.CM_HIT, ProtocolConstants.CM_HEAVYHIT, ProtocolConstants.CM_BIGHIT,
             ProtocolConstants.CM_POWERHIT ->
            reportExceptionalFailure(world.attack(boundPlayer, unpackPosition(message.recog()),
                Direction.fromCode(message.tag()), attackKind(message.ident())));
        // CM_SPELL: Recog=MakeLong(X,Y), Param/Series=target id words, Tag=MagicId.
        case ProtocolConstants.CM_SPELL -> reportExceptionalFailure(world.castSpell(
            boundPlayer, message.tag(), unpackPosition(message.recog()),
            (message.param() & 0xffff) | (message.series() << 16)));
        // CM_MAGICKEYCHANGE: Recog=MagicId, Param=the new key byte; there is no reply.
        case ProtocolConstants.CM_MAGICKEYCHANGE -> reportExceptionalFailure(
            world.changeMagicKey(boundPlayer, message.recog(), message.param() & 0xff));
        // The client sends its own cell in param/tag, not a packed Recog (ClMain.pas:CM_PICKUP).
        case ProtocolConstants.CM_PICKUP -> reportExceptionalFailure(
            world.pickUp(boundPlayer, new Position(message.param(), message.tag())));
        // Sent by CheckDoorAction when the player clicks a closed door cell; the Delphi
        // server answers silently — success shows up as a SM_OPENDOOR_OK broadcast.
        case ProtocolConstants.CM_OPENDOOR -> reportExceptionalFailure(
            world.openDoor(boundPlayer, new Position(message.param(), message.tag())));
        // Sent by the client right after SM_LOGON (ClMain.pas:3860) and whenever the bag
        // window needs a refresh; no +GOOD/+FAIL ack is part of the Delphi exchange.
        case ProtocolConstants.CM_QUERYBAGITEMS -> reportExceptionalFailure(
            world.playerState(boundPlayer)
                .thenAccept(state -> sendBagItems(state.backpack())));
        // CM_TAKEONITEM: recog=MakeIndex, param=slot, body=item name (ClMain.pas:3058).
        case ProtocolConstants.CM_TAKEONITEM -> reportExceptionalFailure(world.equip(
            boundPlayer, message.param(), message.recog(),
            WireMessageCodec.decodeBody(packet.encodedBody())));
        // CM_TAKEOFFITEM carries the same layout as CM_TAKEONITEM (ClMain.pas:3066).
        case ProtocolConstants.CM_TAKEOFFITEM -> reportExceptionalFailure(world.unequip(
            boundPlayer, message.param(), message.recog(),
            WireMessageCodec.decodeBody(packet.encodedBody())));
        // CM_EAT: recog=MakeIndex, body=item name; no slot (ClMain.pas:3074).
        case ProtocolConstants.CM_EAT -> reportExceptionalFailure(world.useItem(
            boundPlayer, message.recog(), WireMessageCodec.decodeBody(packet.encodedBody())));
        // CM_DROPITEM: recog=MakeIndex, body=item name (ClMain.pas:3042).
        case ProtocolConstants.CM_DROPITEM -> reportExceptionalFailure(world.dropItem(
            boundPlayer, message.recog(), WireMessageCodec.decodeBody(packet.encodedBody())));
        // CM_MERCHANTDLGSELECT: recog=merchant, body=label (ClMain.pas:3094). Delphi answers
        // no +GOOD/+FAIL for the merchant family, so like CM_SAY the reply is event-driven.
        case ProtocolConstants.CM_MERCHANTDLGSELECT -> reportExceptionalFailure(
            world.selectMerchantLabel(
                boundPlayer, message.recog(), WireMessageCodec.decodeBody(packet.encodedBody())));
        // CM_MERCHANTQUERYREPAIRCOST: recog=merchant, param/tag = MakeIndex words,
        // body=item name (ClMain.pas:3120). No such bag item stays silent, as in Delphi.
        case ProtocolConstants.CM_MERCHANTQUERYREPAIRCOST -> reportExceptionalFailure(
            world.queryRepairCost(boundPlayer, unpackMakeIndex(message),
                WireMessageCodec.decodeBody(packet.encodedBody())));
        // CM_USERREPAIRITEM: same layout as the cost query (ClMain.pas:3136).
        case ProtocolConstants.CM_USERREPAIRITEM -> reportExceptionalFailure(
            world.repairItem(boundPlayer, unpackMakeIndex(message),
                WireMessageCodec.decodeBody(packet.encodedBody())));
        // CM_GROUPMODE (ObjBase.pas:4777): param!=0 enables allowGroup, param==0 disallows.
        case ProtocolConstants.CM_GROUPMODE -> reportExceptionalFailure(
            world.setAllowGroup(boundPlayer, message.param() != 0));
        // CM_CREATEGROUP (ObjBase.pas:4784): body=target player name.
        case ProtocolConstants.CM_CREATEGROUP -> reportExceptionalFailure(
            world.createGroup(boundPlayer, WireMessageCodec.decodeBody(packet.encodedBody())));
        // CM_ADDGROUPMEMBER (ObjBase.pas:4788): body=target player name.
        case ProtocolConstants.CM_ADDGROUPMEMBER -> reportExceptionalFailure(
            world.addGroupMember(boundPlayer, WireMessageCodec.decodeBody(packet.encodedBody())));
        // CM_DELGROUPMEMBER (ObjBase.pas:4792): body=target player name.
        case ProtocolConstants.CM_DELGROUPMEMBER -> reportExceptionalFailure(
            world.delGroupMember(boundPlayer, WireMessageCodec.decodeBody(packet.encodedBody())));
        // Chat from client: Delphi sends no +GOOD/+FAIL acknowledgement for CM_SAY.
        case ProtocolConstants.CM_SAY -> {
          String text = WireMessageCodec.decodeBody(packet.encodedBody());
          if (!text.isBlank()) {
            reportExceptionalFailure(world.say(boundPlayer, text));
          }
        }
        // CM_SOFTCLOSE (ObjBase.pas:4751): the client's 退出到选人 button. Delphi only
        // raises m_boSoftClose/m_boReconnection — no ack is ever sent; the ghosting happens
        // on the object's next Operate tick and the client closes the socket itself ~2s
        // later. Answering here (even with a status frame) would desynchronise the client's
        // soft-close timer, so the world call is fire-and-forget like the Delphi flag set.
        case ProtocolConstants.CM_SOFTCLOSE -> reportExceptionalFailure(
            world.softClose(boundPlayer));
        // CM_QUERYUSERNAME (ClMain.pas:3601): recog=target, param/tag=the cell the client
        // believes the target stands on. ObjBase.pas:4663 answers SM_USERNAME when the
        // target is in the 3x3 block around that cell, SM_GHOST otherwise; no status frame.
        case ProtocolConstants.CM_QUERYUSERNAME -> {
          int queriedId = message.recog();
          int queriedX = message.param();
          int queriedY = message.tag();
          reportExceptionalFailure(world.queryUserName(boundPlayer, queriedId, queriedX, queriedY)
              .thenAccept(answer -> sendUserName(answer, queriedX, queriedY)));
        }
        default -> throw new AssertionError("supported ident set changed after validation");
      }
      return true;
    } catch (IllegalArgumentException | IllegalStateException error) {
      sendStatus(false);
      return true;
    }
  }

  @Override
  public void send(WorldEvent event) {
    Objects.requireNonNull(event, "event");
    switch (event) {
      case WorldEvent.MapEntered entered -> sendMapEntered(entered);
      case WorldEvent.MoveAccepted accepted -> {
        if (accepted.player().id() == playerId) sendStatus(true);
      }
      case WorldEvent.MoveRejected rejected -> {
        if (rejected.playerId() == playerId) sendStatus(false);
      }
      case WorldEvent.TurnAccepted accepted -> {
        if (accepted.player().id() == playerId) sendStatus(true);
      }
      case WorldEvent.TurnRejected rejected -> {
        if (rejected.playerId() == playerId) sendStatus(false);
      }
      case WorldEvent.AttackAccepted accepted -> {
        if (accepted.attacker().id() == playerId) sendStatus(true);
      }
      case WorldEvent.AttackRejected rejected -> {
        if (rejected.playerId() == playerId) sendStatus(false);
      }
      case WorldEvent.SkillLearned learned -> {
        if (learned.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ADDMAGIC,
              0, 0, 0, 0, encodeMagic(learned.magic()))));
        }
      }
      case WorldEvent.SkillsSent sent -> {
        if (sent.playerId() == playerId) sendSkills(sent);
      }
      case WorldEvent.SpellAccepted accepted -> {
        if (accepted.playerId() == playerId) sendStatus(true);
      }
      // SendSocket(nil, '+PWR') — a bare tag frame on the +GOOD/+FAIL channel that arms the
      // client's g_boNextTimePowerHit so its next swing goes out as CM_POWERHIT.
      case WorldEvent.PowerHitReady ready -> {
        if (ready.playerId() == playerId) output.accept(GameOutbound.Signal.POWER_HIT);
      }
      case WorldEvent.SpellRejected rejected -> {
        if (rejected.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_MAGICFIRE_FAIL,
              playerId, 0, 0, 0, "")));
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_SYSMESSAGE,
              0, 0xFF, 0, 1, WireMessageCodec.encodeBody(rejected.message()))));
          sendStatus(false);
        }
      }
      case WorldEvent.ObjectSpellCast cast -> {
        // Delphi suppresses RM_SPELL for the caster; the local client already animated it.
        if (cast.caster().id() != playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_SPELL,
              cast.caster().id(), cast.target().x(), cast.target().y(), cast.magic().effect(),
              Integer.toString(cast.magic().id()))));
        }
      }
      case WorldEvent.MagicFired fired -> output.accept(new GameOutbound.Packet(packet(
          ProtocolConstants.SM_MAGICFIRE, fired.casterId(), fired.target().x(), fired.target().y(),
          (fired.magic().effectType() & 0xff) | ((fired.magic().effect() & 0xff) << 8),
          encodeInteger(fired.targetId()))));
      case WorldEvent.PickupRejected rejected -> {
        if (rejected.playerId() == playerId) sendStatus(false);
      }
      case WorldEvent.ObjectAppeared appeared -> sendObjectAction(
          ProtocolConstants.SM_TURN, appeared.object());
      case WorldEvent.ObjectMoved moved -> sendMovement(moved.object(), moved.movement());
      case WorldEvent.ObjectTurned turned -> sendObjectAction(ProtocolConstants.SM_TURN, turned.object());
      case WorldEvent.ObjectAttacked attacked -> sendAttack(attacked);
      case WorldEvent.ObjectStruck struck -> sendStruck(struck);
      case WorldEvent.ObjectDied died -> sendDeath(died);
      case WorldEvent.ObjectRevived revived -> sendAlive(revived.object());
      case WorldEvent.LevelUp levelUp -> sendLevelUp(levelUp);
      case WorldEvent.ItemsRemoved removed -> sendDeletedItems(removed);
      case WorldEvent.HealthChanged changed -> sendHealth(changed.object());
      case WorldEvent.ExperienceGained gained -> sendExperience(gained);
      case WorldEvent.ItemAppeared appeared -> sendItemShow(appeared.item());
      case WorldEvent.ItemDisappeared disappeared -> sendItemHide(disappeared.item());
      case WorldEvent.ItemPickedUp pickedUp -> sendItemPickedUp(pickedUp);
      case WorldEvent.GoldPickedUp pickedUp -> sendGoldPickedUp(pickedUp);
      case WorldEvent.ObjectDisappeared disappeared -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_DISAPPEAR, disappeared.objectId(), 0, 0, 0, "")));
      case WorldEvent.DoorOpened opened -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_OPENDOOR_OK, 0, opened.position().x(), opened.position().y(), 0, "")));
      case WorldEvent.DoorClosed closed -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_CLOSEDOOR, 0, closed.position().x(), closed.position().y(), 0, "")));
      case WorldEvent.PlayerMapChanged changed -> sendMapChanged(changed);
      case WorldEvent.ChatHeard chat -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_HEAR, chat.speakerId(), 0, 0, 1,
              WireMessageCodec.encodeBody(chat.speakerName() + ":" + chat.message()))));
      case WorldEvent.Whisper whisper -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_WHISPER, whisper.senderId(), 0x38FF, 0, 1,
              WireMessageCodec.encodeBody(whisper.senderName() + "=> " + whisper.message()))));
      case WorldEvent.Shout shout -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_HEAR, shout.speakerId(), 0x0097, 0, 1,
              WireMessageCodec.encodeBody("(!)" + shout.speakerName() + ": " + shout.message()))));
      case WorldEvent.SystemMessage sysMsg -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_SYSMESSAGE, 0, 0xFF, 0, 1,
              WireMessageCodec.encodeBody(sysMsg.message()))));
      // RM_CHANGENAMECOLOR (ObjBase.pas:5607): recog = the object, param = GetCharColor.
      case WorldEvent.NameColorChanged recolored -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_CHANGENAMECOLOR, recolored.objectId(),
              recolored.nameColor(), 0, 0, "")));
      case WorldEvent.ItemEquipped equipped -> sendTakeOnOk(equipped);
      case WorldEvent.EquipRejected rejected -> {
        if (rejected.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_TAKEON_FAIL, rejected.reason(), 0, 0, 0, "")));
        }
      }
      case WorldEvent.ItemUnequipped unequipped -> sendTakeOffOk(unequipped);
      case WorldEvent.UnequipRejected rejected -> {
        if (rejected.playerId() == playerId) {
          if (rejected.detail() == WorldEvent.UnequipRejection.CANNOT_TAKE_OFF) {
            output.accept(new GameOutbound.Packet(
                packet(ProtocolConstants.SM_SYSMESSAGE, 0, 0xFF, 0, 1,
                    WireMessageCodec.encodeBody("无法取下物品"))));
          }
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_TAKEOFF_FAIL, rejected.reason(), 0, 0, 0, "")));
        }
      }
      case WorldEvent.GroupModeChanged changed -> {
        if (changed.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_GROUPMODECHANGED, 0, changed.allowGroup() ? 1 : 0, 0, 0, "")));
        }
      }
      case WorldEvent.GroupCreated created -> {
        if (created.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_CREATEGROUP_OK, 0, 0, 0, 0, "")));
        }
      }
      case WorldEvent.GroupCreateFailed failed -> {
        if (failed.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_CREATEGROUP_FAIL, failed.reason(), 0, 0, 0, "")));
        }
      }
      case WorldEvent.GroupMemberAdded added -> {
        if (added.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_GROUPADDMEM_OK, 0, 0, 0, 0, "")));
        }
      }
      case WorldEvent.GroupAddMemberFailed failed -> {
        if (failed.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_GROUPADDMEM_FAIL, failed.reason(), 0, 0, 0, "")));
        }
      }
      case WorldEvent.GroupMemberDeleted deleted -> {
        if (deleted.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_GROUPDELMEM_OK, 0, 0, 0, 0,
                  WireMessageCodec.encodeBody(deleted.memberName()))));
        }
      }
      case WorldEvent.GroupDelMemberFailed failed -> {
        if (failed.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_GROUPDELMEM_FAIL, failed.reason(), 0, 0, 0, "")));
        }
      }
      case WorldEvent.GroupMembersChanged changed -> {
        if (changed.playerId() == playerId) {
          StringBuilder sb = new StringBuilder();
          for (String m : changed.members()) {
            sb.append(m).append('/');
          }
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_GROUPMEMBERS, 0, 0, 0, 0,
                  WireMessageCodec.encodeBody(sb.toString()))));
        }
      }
      case WorldEvent.GroupCancelled cancelled -> {
        if (cancelled.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_GROUPCANCEL, 0, 0, 0, 0, "")));
        }
      }
      case WorldEvent.ItemUsed used -> {
        if (used.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_EAT_OK, 0, 0, 0, 0, "")));
        }
      }
      case WorldEvent.UseItemRejected rejected -> {
        if (rejected.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(
              packet(ProtocolConstants.SM_EAT_FAIL, 0, 0, 0, 0, "")));
        }
      }
      case WorldEvent.ItemDropped dropped -> {
        if (dropped.playerId() == playerId) {
          // SM_DROPITEM_SUCCESS: recog=MakeIndex, body=item name (ClMain.pas SM handler).
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_DROPITEM_SUCCESS,
              dropped.item().makeIndex(), 0, 0, 0,
              WireMessageCodec.encodeBody(dropped.item().name()))));
        }
      }
      case WorldEvent.DropItemRejected rejected -> {
        if (rejected.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_DROPITEM_FAIL,
              rejected.makeIndex(), 0, 0, 0, WireMessageCodec.encodeBody(rejected.itemName()))));
        }
      }
      case WorldEvent.WeightChanged weight -> {
        if (weight.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_WEIGHTCHANGED,
              weight.weight(), weight.wearWeight(), weight.handWeight(), 0, "")));
        }
      }
      case WorldEvent.AbilityChanged changed -> {
        if (changed.playerId() == playerId) sendAbility(changed);
      }
      case WorldEvent.SubAbilityChanged changed -> {
        if (changed.playerId() == playerId) sendSubAbility(changed);
      }
      case WorldEvent.EquipmentSent sent -> {
        if (sent.playerId() == playerId) sendWornSet(sent.equipment());
      }
      case WorldEvent.ItemDurabilityChanged changed -> {
        if (changed.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_DURACHANGE,
              changed.dura(), changed.slot().index(), changed.duraMax() & 0xffff,
              (changed.duraMax() >>> 16) & 0xffff, "")));
        }
      }
      case WorldEvent.GoldChanged changed -> {
        if (changed.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_GOLDCHANGED,
              (int) changed.gold(), 0, 0, 0, "")));
        }
      }
      case WorldEvent.MerchantRepairDialog opened -> {
        if (opened.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_SENDUSERREPAIR,
              opened.merchantId(), 0, 0, 0, "")));
        }
      }
      // RM_MERCHANTDLGCLOSE (ObjBase.pas:5846): recog=merchant, rest zero. The @exit label.
      case WorldEvent.MerchantDialogClosed closed -> {
        if (closed.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_MERCHANTDLGCLOSE,
              closed.merchantId(), 0, 0, 0, "")));
        }
      }
      // A deferred/unknown merchant label. Delphi is silent on the wire (the arm is guarded by an
      // unset m_boXXX flag), so we send no packet — but we log it, keeping the refusal observable
      // instead of a silent no-op (W29). The WorldEvent itself is the auditable rejection record.
      case WorldEvent.MerchantActionRejected rejected -> {
        if (rejected.playerId() == playerId) {
          LOG.fine(() -> "merchant label refused: '" + rejected.label() + "' ("
              + (rejected.unknown() ? "unknown" : rejected.status()) + ") — " + rejected.reason());
        }
      }
      case WorldEvent.RepairCostResolved resolved -> {
        if (resolved.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_SENDREPAIRCOST,
              resolved.cost(), 0, 0, 0, "")));
        }
      }
      case WorldEvent.ItemRepaired repaired -> {
        if (repaired.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_USERREPAIRITEM_OK,
              repaired.gold(), repaired.dura(), repaired.duraMax(), 0, "")));
        }
      }
      case WorldEvent.RepairRejected rejected -> {
        if (rejected.playerId() == playerId) {
          output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_USERREPAIRITEM_FAIL,
              0, 0, 0, 0, "")));
        }
      }
      case WorldEvent.DayChanging dayChanging -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_DAYCHANGING, 0, dayChanging.gameTime(), dayChanging.dayBright(), 0, "")));
      // RM_CHANGELIGHT (ObjBase.pas:5948): recog=object, param=the new m_nLight. Tag stays
      // 0 — Delphi ships g_Config.nClientKey there but the 1.76 client never reads it.
      case WorldEvent.LightChanged relit -> output.accept(new GameOutbound.Packet(
          packet(ProtocolConstants.SM_CHANGELIGHT, relit.objectId(), relit.light(), 0, 0, "")));
      default -> {
        // MapLeft has no client packet; socket closure already ends the local session.
      }
    }
  }

  static Position unpackPosition(int packed) {
    return new Position(packed & 0xffff, (packed >>> 16) & 0xffff);
  }

  private static boolean isSupported(int ident) {
    return ident == ProtocolConstants.CM_TURN
        || ident == ProtocolConstants.CM_WALK
        || ident == ProtocolConstants.CM_RUN
        || ident == ProtocolConstants.CM_HIT
        || ident == ProtocolConstants.CM_HEAVYHIT
        || ident == ProtocolConstants.CM_BIGHIT
        || ident == ProtocolConstants.CM_POWERHIT
        || ident == ProtocolConstants.CM_SPELL
        || ident == ProtocolConstants.CM_MAGICKEYCHANGE
        || ident == ProtocolConstants.CM_PICKUP
        || ident == ProtocolConstants.CM_OPENDOOR
        || ident == ProtocolConstants.CM_QUERYBAGITEMS
        || ident == ProtocolConstants.CM_QUERYUSERNAME
        || ident == ProtocolConstants.CM_SAY
        || ident == ProtocolConstants.CM_TAKEONITEM
        || ident == ProtocolConstants.CM_TAKEOFFITEM
        || ident == ProtocolConstants.CM_EAT
        || ident == ProtocolConstants.CM_DROPITEM
        || ident == ProtocolConstants.CM_MERCHANTDLGSELECT
        || ident == ProtocolConstants.CM_MERCHANTQUERYREPAIRCOST
        || ident == ProtocolConstants.CM_USERREPAIRITEM
        || ident == ProtocolConstants.CM_GROUPMODE
        || ident == ProtocolConstants.CM_CREATEGROUP
        || ident == ProtocolConstants.CM_ADDGROUPMEMBER
        || ident == ProtocolConstants.CM_DELGROUPMEMBER
        || ident == ProtocolConstants.CM_SOFTCLOSE;
  }

  /**
   * Delphi {@code MakeLong(LoWord, HiWord)}: the client packs an item's MakeIndex across the
   * Param/Tag words so the low word lands in Param and the high word in Tag.
   */
  private static int unpackMakeIndex(DefaultMessage message) {
    return (message.param() & 0xffff) | (message.tag() << 16);
  }

  /** {@code TPlayObject.ClientAttack} (ObjBase.pas:8838) ident → {@code wHitMode} mapping. */
  private static AttackKind attackKind(int ident) {
    return switch (ident) {
      case ProtocolConstants.CM_HEAVYHIT -> AttackKind.HEAVY_HIT;
      case ProtocolConstants.CM_BIGHIT -> AttackKind.BIG_HIT;
      case ProtocolConstants.CM_POWERHIT -> AttackKind.POWER_HIT;
      default -> AttackKind.HIT;
    };
  }

  /**
   * {@code TPlayObject.AttackDir} (ObjBase.pas:18841) {@code wHitMode} → broadcast ident.
   * {@code wHitMode = 3} only answers {@code RM_SPELL2} when the swing actually consumed an
   * armed {@code m_boPowerHit}; the world already folded that decision into the event's
   * {@link AttackKind}, so an unarmed 攻杀 arrives here as {@link AttackKind#HIT}.
   */
  private static int attackIdent(AttackKind attack) {
    return switch (attack) {
      case HEAVY_HIT -> ProtocolConstants.SM_HEAVYHIT;
      case BIG_HIT -> ProtocolConstants.SM_BIGHIT;
      case POWER_HIT -> ProtocolConstants.SM_SPELL2;
      case HIT -> ProtocolConstants.SM_HIT;
    };
  }

  /**
   * The RM_LOGON bootstrap (ObjBase.pas:5618) in wire order:
   * SM_NEWMAP → RM_CHANGELIGHT → SendLogon (SM_LOGON + SM_FEATURECHANGED) →
   * SendServerConfig (skipped: m_nSoftVersionDateEx = 0 for the legacy client, the
   * Delphi handler exits before sending anything) → ClientQueryUserName → RefUserState →
   * SendMapDescription → SendGoldInfo (the world emits it as the trailing AbilityChanged).
   *
   * <p>The light packets matter: with fog on (a dark hour, or a DARK map) the client
   * punches a fog hole sized by the actor's light around every actor — including the
   * local one (PlayScn.pas:1345 always AddLights g_MySelf) — using the high byte of the
   * Series word ({@code MakeWord(btDir, m_nLight)}) and SM_CHANGELIGHT. Omitting them
   * leaves the freshly entered character under the fog overlay.
   */
  private void sendMapEntered(WorldEvent.MapEntered entered) {
    WorldObjectSnapshot player = entered.player();
    if (playerId == 0) playerId = player.id();
    if (playerId != player.id()) throw new IllegalStateException("adapter received another player's MapEntered");
    Position position = player.position();

    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_NEWMAP, player.id(),
        position.x(), position.y(), entered.dayBright(), WireMessageCodec.encodeBody(entered.map().id()))));
    // RM_CHANGELIGHT (ObjBase.pas:5620 → 5948): recog=object, param=m_nLight. Delphi also
    // ships g_Config.nClientKey in tag; the 1.76 client only reads recog/param
    // (ClMain.pas:4624), so it stays 0 here.
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_CHANGELIGHT, player.id(),
        player.light(), 0, 0, "")));
    // SendLogon (ObjBase.pas:16780): Series=MakeWord(direction, light), body=TMessageBodyWL
    // (feature, char status, group flag/featureEx, reserved).
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_LOGON, player.id(),
        position.x(), position.y(), makeWord(player.direction().code(), player.light()),
        logonBody(player))));
    // SendLogon's tail (ObjBase.pas:16794): SM_FEATURECHANGED with the feature long split
    // across param/tag and GetFeatureEx in series. The client applies it through
    // actor.FeatureChanged (PlayScn.pas:2454) to finalise its own avatar.
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_FEATURECHANGED, player.id(),
        player.feature() & 0xffff, (player.feature() >>> 16) & 0xffff, 0, "")));
    // ClientQueryUserName(Self, x, y) (ObjBase.pas:5623): the server proactively names the
    // player for itself; palette byte 255 is TBaseObject's white default (ObjBase.pas:1223).
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_USERNAME, player.id(),
        255, 0, 0, WireMessageCodec.encodeBody(player.name()))));
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_MAPDESCRIPTION, -1,
        0, 0, 0, WireMessageCodec.encodeBody(entered.map().title()))));
    for (WorldObjectSnapshot visible : entered.visibleObjects()) {
      sendObjectAction(ProtocolConstants.SM_TURN, visible);
    }
    for (GroundItem item : entered.visibleItems()) {
      sendItemShow(item);
    }
  }

  /**
   * {@code MakeWord} (Common/Grobal2.pas): the low byte is the direction, the high byte
   * the actor's light radius — the packing every RM_TURN/RM_WALK/RM_RUN/SM_LOGON series
   * word uses (ObjBase.pas:5296/5316/5446/16785).
   */
  private static int makeWord(int low, int high) {
    return (low & 0xff) | ((high & 0xff) << 8);
  }

  /** SM_USERNAME / SM_GHOST answer for CM_QUERYUSERNAME (ObjBase.pas:2638). */
  private void sendUserName(WorldEngine.UserNameQuery answer, int quotedX, int quotedY) {
    if (!answer.present()) {
      output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_GHOST, answer.objectId(),
          quotedX, quotedY, 0, "")));
      return;
    }
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_USERNAME, answer.objectId(),
        answer.nameColor(), 0, 0, WireMessageCodec.encodeBody(answer.name()))));
  }

  /**
   * RM_CHANGEMAP sequence (ObjBase.pas): SM_CLEAROBJECTS first (the client flags
   * g_boMapMoving), then SM_CHANGEMAP carrying the destination name so the client can swap
   * its map file, then the fresh SM_MAPDESCRIPTION. Appearance events refill the scene.
   */
  private void sendMapChanged(WorldEvent.PlayerMapChanged changed) {
    WorldObjectSnapshot player = changed.player();
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_CLEAROBJECTS, player.id(),
        0, 0, 0, "")));
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_CHANGEMAP, player.id(),
        player.position().x(), player.position().y(), changed.dayBright(),
        WireMessageCodec.encodeBody(changed.map().id()))));
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_MAPDESCRIPTION, -1,
        0, 0, 0, WireMessageCodec.encodeBody(changed.map().title()))));
  }

  private void sendMovement(WorldObjectSnapshot object, MovementKind movement) {
    int ident = movement == MovementKind.RUN ? ProtocolConstants.SM_RUN : ProtocolConstants.SM_WALK;
    sendObjectAction(ident, object);
  }

  private void sendObjectAction(int ident, WorldObjectSnapshot object) {
    Position position = object.position();
    String body = new CharacterDescription(object.feature(), object.status()).encode();
    // Series = MakeWord(direction, light) for the appearance/movement family
    // (ObjBase.pas:5296/5316/5446) — the client reads the high byte into
    // actor.m_nChrLight (PlayScn.pas:2444) to size the actor's fog hole.
    output.accept(new GameOutbound.Packet(packet(
        ident, object.id(), position.x(), position.y(),
        makeWord(object.direction().code(), object.light()), body)));
  }

  /** {@code RM_HIT} carries only the attacker id, cell and direction; the body stays empty. */
  private void sendAttack(WorldEvent.ObjectAttacked attacked) {
    WorldObjectSnapshot attacker = attacked.attacker();
    Position position = attacker.position();
    output.accept(new GameOutbound.Packet(packet(attackIdent(attacked.attack()), attacker.id(),
        position.x(), position.y(), attacker.direction().code(), "")));
  }

  /** {@code SM_STRUCK}: recog=victim, param=HP, tag=MaxHP, series=damage, body=TMessageBodyWL. */
  private void sendStruck(WorldEvent.ObjectStruck struck) {
    WorldObjectSnapshot victim = struck.victim();
    String body = messageBodyWl(victim.feature(), victim.status(), struck.attackerId(), 0);
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_STRUCK, victim.id(),
        victim.ability().hp(), victim.ability().maxHp(), struck.damage(), body)));
  }

  /** {@code SM_DEATH}: recog=victim, param/tag=cell, series=damage, body=TCharDesc. */
  private void sendDeath(WorldEvent.ObjectDied died) {
    WorldObjectSnapshot victim = died.victim();
    Position position = victim.position();
    String body = new CharacterDescription(victim.feature(), victim.status()).encode();
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_DEATH, victim.id(),
        position.x(), position.y(), victim.direction().code(), body)));
  }

  /**
   * {@code RM_ALIVE} -> {@code SM_ALIVE} (ObjBase.pas:6066): recog=object, param/tag=cell,
   * series=direction, body=TCharDesc. The client reads param/tag as HP/MaxHP in its own
   * handler comment (ClMain.pas:4192) but only uses them to resurrect the sprite, and the
   * server sends the {@code SendRefMsg(RM_ALIVE, m_btDirection, m_nCurrX, m_nCurrY, 0, '')}
   * cell triple — so the cell is what actually goes on the wire.
   */
  private void sendAlive(WorldObjectSnapshot object) {
    Position position = object.position();
    String body = new CharacterDescription(object.feature(), object.status()).encode();
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ALIVE, object.id(),
        position.x(), position.y(), object.direction().code(), body)));
  }

  /**
   * {@code RM_LEVELUP} -> {@code SM_LEVELUP} (ObjBase.pas:5584): recog=total experience,
   * param=level, empty body. The Delphi branch immediately follows it with the full
   * {@code SM_ABILITY} block, which the world emits as a separate AbilityChanged event.
   */
  private void sendLevelUp(WorldEvent.LevelUp levelUp) {
    if (levelUp.playerId() != playerId) return;
    int experience = (int) Math.min(levelUp.experience(), 0xFFFFFFFFL);
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_LEVELUP,
        experience, levelUp.level(), 0, 0, "")));
  }

  /**
   * {@code TPlayObject.SendDelItemList} (ObjBase.pas:22857) -> {@code SM_DELITEMS}:
   * series=item count, body={@code <name>/<MakeIndex>/} repeated.
   */
  private void sendDeletedItems(WorldEvent.ItemsRemoved removed) {
    if (removed.playerId() != playerId) return;
    StringBuilder body = new StringBuilder();
    for (com.mir2.world.ItemRemoval item : removed.items()) {
      body.append(item.name()).append('/').append(item.makeIndex()).append('/');
    }
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_DELITEMS, 0, 0, 0,
        removed.items().size(), WireMessageCodec.encodeBody(body.toString()))));
  }

  /** {@code SM_HEALTHSPELLCHANGED}: recog=object, param=HP, tag=MP, series=MaxHP, empty body. */
  private void sendHealth(WorldObjectSnapshot object) {
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_HEALTHSPELLCHANGED, object.id(),
        object.ability().hp(), object.ability().mp(), object.ability().maxHp(), "")));
  }

  /** {@code SM_WINEXP}: recog=total experience, param/tag=low/high word of the gained amount. */
  private void sendExperience(WorldEvent.ExperienceGained gained) {
    if (gained.playerId() != playerId) return;
    int total = (int) Math.min(gained.total(), Integer.MAX_VALUE);
    int amount = (int) Math.min(gained.gained(), Integer.MAX_VALUE);
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_WINEXP, total,
        amount & 0xffff, (amount >>> 16) & 0xffff, 0, "")));
  }

  /** {@code SM_ITEMSHOW}: recog=item, param/tag=cell, series=looks, body=item name. */
  private void sendItemShow(GroundItem item) {
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ITEMSHOW, item.id(),
        item.position().x(), item.position().y(), item.looks(),
        WireMessageCodec.encodeBody(item.name()))));
  }

  private void sendItemHide(GroundItem item) {
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ITEMHIDE, item.id(),
        item.position().x(), item.position().y(), 0, "")));
  }

  private void sendItemPickedUp(WorldEvent.ItemPickedUp pickedUp) {
    if (pickedUp.playerId() != playerId) return;
    sendStatus(true);
    // Full 76-byte TClientItem body, as SendAddItem does (ObjBase.pas:2145).
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ADDITEM, pickedUp.playerId(),
        0, 0, 1, ClientItemCodec.encode(pickedUp.backpackItem()))));
    sendItemHide(pickedUp.item());
  }

  private void sendGoldPickedUp(WorldEvent.GoldPickedUp pickedUp) {
    if (pickedUp.playerId() != playerId) return;
    sendStatus(true);
    // Gold piles never become bag entries: ClientPickUpItem calls IncGold + GoldChanged.
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_GOLDCHANGED,
        (int) pickedUp.gold(), 0, 0, 0, "")));
  }

  /**
   * {@code SM_BAGITEMS}: recog=player, series=item count, body = '/'-terminated encoded
   * TClientItem blocks. ObjBase.pas:15952 stays silent for an empty bag, so we do too.
   */
  private void sendBagItems(List<BackpackItem> backpack) {
    if (backpack.isEmpty()) return;
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_BAGITEMS, requirePlayerId(),
        0, 0, backpack.size(), ClientItemCodec.encodeBag(backpack))));
  }

  private void sendSkills(WorldEvent.SkillsSent sent) {
    StringBuilder body = new StringBuilder();
    for (LearnedMagic magic : sent.magics()) body.append(encodeMagic(magic)).append('/');
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_SENDMYMAGIC,
        0, 0, 0, sent.magics().size(), body.toString())));
  }

  private static String encodeMagic(LearnedMagic magic) {
    return new String(SixBitCodec.encode(MagicCodec.encode(magic)), StandardCharsets.ISO_8859_1);
  }

  private static String encodeInteger(int value) {
    byte[] raw = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value).array();
    return new String(SixBitCodec.encode(raw), StandardCharsets.ISO_8859_1);
  }

  /**
   * {@code SM_TAKEON_OK}: recog carries {@code GetFeatureToLong} and param
   * {@code GetFeatureEx} (ObjBase.pas:17165), which the client applies to its own avatar.
   */
  private void sendTakeOnOk(WorldEvent.ItemEquipped equipped) {
    if (equipped.playerId() != playerId) return;
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_TAKEON_OK,
        equipped.feature(), equipped.featureEx(), 0, 0, "")));
  }

  /**
   * {@code SM_TAKEOFF_OK}, followed by the {@code SM_TAKEOFF_FAIL} that
   * {@code ClientTakeOffItems} also emits on success.
   *
   * <p>Quirk faithfully reproduced: the Delphi handler leaves its {@code n10} status at 0 on
   * the success path and its exit test is {@code if n10 <= 0 then SendDefMessage(
   * SM_TAKEOFF_FAIL, ...)} (ObjBase.pas:17294). Zero satisfies that test, so every successful
   * take-off is followed by a failure packet carrying recog 0. The 1.50 client tolerates it
   * because its SM_TAKEOFF_FAIL branch only restores {@code g_WaitingUseItem} when the pending
   * index is negative, which it is not after a successful take-off.
   */
  private void sendTakeOffOk(WorldEvent.ItemUnequipped unequipped) {
    if (unequipped.playerId() != playerId) return;
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_TAKEOFF_OK,
        unequipped.feature(), unequipped.featureEx(), 0, 0, "")));
    output.accept(new GameOutbound.Packet(
        packet(ProtocolConstants.SM_TAKEOFF_FAIL, 0, 0, 0, 0, "")));
    // The freed item reappears in the bag; SendAddItem carries the full TClientItem.
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ADDITEM,
        unequipped.playerId(), 0, 0, 1, ClientItemCodec.encode(unequipped.item()))));
  }

  /**
   * {@code RM_ABILITY -> SM_ABILITY} with the 50-byte packed TAbility body. The Delphi header
   * (ObjBase.pas:5685) carries the wallet in Recog and {@code MakeWord(btJob, 99)} in Param —
   * the client refreshes its gold display from every SM_ABILITY, so these cannot stay zero now
   * that gold exists.
   */
  private void sendAbility(WorldEvent.AbilityChanged changed) {
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_ABILITY,
        (int) changed.gold(),
        (changed.job() & 0xff) | (99 << 8),
        0, 0,
        AbilityCodec.encode(changed.ability(), changed.weights()))));
  }

  /**
   * {@code RM_SUBABILITY -> SM_SUBABILITY} (ObjBase.pas:5601). Four packed header words, empty
   * body: {@code Recog = MakeLong(MakeWord(m_nAntiMagic, 0), 0)},
   * {@code Param = MakeWord(m_btHitPoint, m_btSpeedPoint)},
   * {@code Tag = MakeWord(m_btAntiPoison, m_nPoisonRecover)} and
   * {@code Series = MakeWord(m_nHealthRecover, m_nSpellRecover)}. The byte packing is what the
   * client's 准确/敏捷 readout decodes, so both values are masked to a byte exactly like the
   * Delphi {@code Byte} fields they come from.
   */
  private void sendSubAbility(WorldEvent.SubAbilityChanged changed) {
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_SUBABILITY,
        changed.antiMagic() & 0xff,
        makeWord(changed.hitPoint(), changed.speedPoint()),
        makeWord(changed.antiPoison(), changed.poisonRecover()),
        makeWord(changed.healthRecover(), changed.spellRecover()),
        "")));
  }

  /** {@code SM_SENDUSEITEMS}; ObjBase.pas:16930 stays silent when nothing is worn. */
  private void sendWornSet(com.mir2.world.Equipment equipment) {
    if (equipment.isEmpty()) return;
    output.accept(new GameOutbound.Packet(packet(ProtocolConstants.SM_SENDUSEITEMS, 0, 0, 0, 0,
        ClientItemCodec.encodeWornSet(equipment.byIndex()))));
  }

  private void sendStatus(boolean accepted) {
    long tick = Math.floorMod(serverTick.getAsLong(), (long) Integer.MAX_VALUE);
    output.accept(new GameOutbound.Status(accepted, tick));
  }

  private void reportExceptionalFailure(CompletableFuture<?> operation) {
    operation.whenComplete((ignored, error) -> {
      if (error != null) sendStatus(false);
    });
  }

  private int requirePlayerId() {
    int id = playerId;
    if (id <= 0) throw new IllegalStateException("GAME adapter has not entered the world");
    return id;
  }

  private static String logonBody(WorldObjectSnapshot player) {
    // TMessageBodyWL: feature, status, group/featureEx, reserved.
    return messageBodyWl(player.feature(), player.status(), 0, 0);
  }

  private static String messageBodyWl(int param1, int param2, int tag1, int tag2) {
    byte[] bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(param1).putInt(param2).putInt(tag1).putInt(tag2).array();
    return new String(SixBitCodec.encode(bytes), StandardCharsets.ISO_8859_1);
  }

  private static WirePacket packet(
      int ident, int recog, int param, int tag, int series, String encodedBody) {
    return new WirePacket(new DefaultMessage(recog, ident, param, tag, series), encodedBody);
  }
}
