package com.mir2.world;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deterministic single-owner-thread game world.
 *
 * <p>Network threads only enqueue commands. A tick drains them in FIFO order, runs monster AI,
 * mutates maps and objects, and emits immutable events. This mirrors the non-reentrant Delphi
 * {@code TUserEngine} loop without sharing mutable world state with socket threads.
 */
public final class WorldEngine implements AutoCloseable {
  private static final Logger LOG = Logger.getLogger(WorldEngine.class.getName());

  /**
   * {@code TUserEngine.ProcessMapDoor} closes a door once it has been open for more than
   * 5000ms ({@code GetTickCount - dwOpenTick > 5000}).
   */
  private static final long DOOR_AUTO_CLOSE_MILLIS = 5_000;

  /**
   * {@code g_Config.nDieScatterBagRate} (M2Share.pas:2020): a non-red character drops one bag
   * entry in three when it dies. A red name ({@code PKLevel >= 2}) under
   * {@code boDieRedScatterBagAll} drops the whole bag instead — see {@link #scatterBagItems}.
   */
  private static final int DIE_SCATTER_BAG_RATE = 3;

  /**
   * {@code g_Config.boDieRedScatterBagAll} (M2Share.pas:2021) ships as {@code True}: a red
   * name loses its entire bag rather than the ordinary one third.
   */
  private static final boolean DIE_RED_SCATTER_BAG_ALL = true;

  /**
   * {@code g_Config.nDieDropUseItemRate} (M2Share.pas:2022) = 30: each worn slot has a 1-in-30
   * chance of falling to the ground when the wearer dies.
   */
  private static final int DIE_DROP_USE_ITEM_RATE = 30;

  /**
   * {@code g_Config.nDieRedDropUseItemRate} (M2Share.pas:2023) = 15: {@code PKLevel > 2}
   * (i.e. 深红, 300+ points) doubles the equipment-drop odds.
   */
  private static final int DIE_RED_DROP_USE_ITEM_RATE = 15;

  /** {@code DropUseItems} always scatters within {@code nScatterRange = 2} of the corpse. */
  private static final int DIE_DROP_USE_ITEM_RANGE = 2;

  /**
   * {@code g_Config.boKillByMonstDropUseItem} (M2Share.pas:2026) ships as {@code True} and
   * {@code boKillByHumanDropUseItem} (M2Share.pas:2025) as {@code False}: dying to a monster
   * scatters equipment, dying to another player does not.
   */
  private static final boolean KILL_BY_MONSTER_DROP_USE_ITEM = true;

  private static final boolean KILL_BY_HUMAN_DROP_USE_ITEM = false;

  /** {@code StdItem.Reserved and 8}: the item is deleted outright on death, never dropped. */
  private static final int RESERVED_DESTROY_ON_DEATH = 0x08;

  /**
   * {@code StdItem.Reserved and 10} ({@code = 8 or 2}): after a successful
   * {@code DropItemDown} the slot is only cleared when neither bit is set — the "bound" item
   * lands on the floor <em>and</em> stays worn, a duplication quirk kept verbatim.
   */
  private static final int RESERVED_KEEP_SLOT_ON_DROP = 0x0A;

  /** {@code g_Config.nMonOneDropGoldCount} (M2Share.pas:1959): one monster gold pile caps here. */
  private static final int MON_ONE_DROP_GOLD_COUNT = 2_000;

  /** {@code DropGoldDown} always scatters within range 3 around the corpse. */
  private static final int GOLD_DROP_RANGE = 3;

  /** {@code ScatterGolds} stops after 17 successful pile attempts. */
  private static final int MAX_GOLD_DROP_PILES = 17;

  /**
   * {@code g_Config.dwMakeGhostTime} (M2Share.pas:1796): a corpse becomes a ghost — i.e. it
   * leaves the map — three minutes after death.
   */
  private static final long MAKE_GHOST_MILLIS = 3 * 60 * 1000L;

  /** {@code g_Config.dwRevivalTime} (M2Share.pas) = 60 seconds between ring revivals. */
  private static final long REVIVAL_COOLDOWN_MILLIS = 60 * 1000L;

  /** {@code ItemDamageRevivalRing} drains exactly {@code Dec(nDura, 1000)} per item. */
  private static final int REVIVAL_RING_DURABILITY_COST = 1000;

  /** {@code g_sRevivalRecoverMsg} (M2Share.pas:3194): the green hint shown when a revival ring
   * fires — 「复活戒指生效，体力恢复.」 in the shipped GBK source.
   */
  private static final String REVIVAL_RECOVER_MESSAGE = "复活戒指生效，体力恢复.";

  /** {@code g_sYouMurderedMsg} (M2Share.pas:3236). */
  private static final String YOU_MURDERED_MESSAGE = "你犯了谋杀罪...";

  /** {@code g_sYouKilledByMsg} (M2Share.pas:3237), formatted with the killer's name. */
  private static final String YOU_KILLED_BY_MESSAGE = "你被%s杀害了...";

  /** {@code g_sYouProtectedByLawOfDefense} (M2Share.pas): a lawful kill, no PK points. */
  private static final String PROTECTED_BY_LAW_MESSAGE = "[--你受到正当规则保护--]";

  /** {@code g_sTheWeaponIsCursed} (M2Share.pas:3156): shown when MakeWeaponUnlock fires. */
  private static final String WEAPON_CURSED_MESSAGE = "你的武器被诅咒了";

  /** {@code g_Config.nKillHumanDecLuckPoint} (M2Share.pas) = 500: body luck lost per murder. */
  private static final int KILL_HUMAN_DEC_LUCK_POINT = 500;

  /**
   * {@code Random(5)} in the murder branch (ObjBase.pas:20950): a 1-in-5 chance to curse the
   * killer's weapon when the victim was wholly innocent ({@code PKLevel < 1}).
   */
  private static final int WEAPON_MAKE_UNLUCK_ON_MURDER = 5;

  /** {@code g_Config.nSuperRepairPriceRate} (M2Share.pas) = 3. */
  private static final int SUPER_REPAIR_PRICE_RATE = 3;

  /** {@code g_Config.nRepairItemDecDura} (M2Share.pas) = 30. */
  private static final int REPAIR_ITEM_DEC_DURA = 30;

  /** StdMode 43 (宝石) is the one category {@code ClientRepairItem} refuses outright. */
  private static final int REPAIR_REFUSED_STD_MODE = 43;

  /** {@code TBaseObject.Run} advances the HP/MP counters by {@code elapsed div 20}. */
  private static final long HP_MP_TICK_MILLIS = 20;

  /** {@code g_Config.nHealthFillTime} (M2Share.pas:1638) = 300 counter units = 6 seconds. */
  private static final long HEALTH_FILL_TICKS = 300;

  /** {@code g_Config.nSpellFillTime} (M2Share.pas:1639) = 800 counter units = 16 seconds. */
  private static final long SPELL_FILL_TICKS = 800;

  /**
   * {@code TUserEngine.AddPlayObject} (UsrEngn.pas:600): a character loaded at {@code HP <= 0}
   * is moved home and set to {@code m_Abil.HP := 14} before entering the world.
   */
  private static final int REVIVE_ON_LOGIN_HP = 14;

  /** Shipped g_Config values used by ClientSpellXY/MagicManager.DoSpell. */
  private static final int MAGIC_ATTACK_RANGE = 8;
  private static final long MAGIC_HIT_INTERVAL_MILLIS = 1_350;
  private static final long FIREBALL_IMPACT_DELAY_MILLIS = 600;
  private static final long HEAL_IMPACT_DELAY_MILLIS = 800;
  private static final int SKILL_FIREBALL = 1;
  private static final int SKILL_HEALING = 2;
  private static final int SKILL_FIREBALL2 = 5;
  private static final int SKILL_AMYOUNSUL = 6;
  private static final int SKILL_FIRE = 9;
  private static final int SKILL_SHOOTLIGHTEN = 10;
  private static final int SKILL_LIGHTENING = 11;
  private static final int SKILL_FIRECHARM = 13;
  private static final int SKILL_HANGMAJINBUB = 14;
  private static final int SKILL_DEJIWONHO = 15;
  private static final int SKILL_BIGHEALLING = 29;
  private static final int SKILL_FIREWIND = 8;
  private static final int SKILL_FIREBOOM = 23;
  private static final int SKILL_LIGHTFLOWER = 24;
  private static final int SKILL_MAGIC_SHIELD = 31;
  private static final int SKILL_SNOWWIND = 33;
  /** 心灵启示 {@code SKILL_SHOWHP}(28, Magic.pas:522): the Taoist reveal-target-HP spell. */
  private static final int SKILL_SHOWHP = 28;
  /** 圣言术 {@code SKILL_KILLUNDEAD}(32, Magic.pas:572): the Wizard instant-kill vs undead. */
  private static final int SKILL_KILLUNDEAD = 32;
  /** 火墙 {@code SKILL_EARTHFIRE}(22, Magic.pas:501): the Wizard cross of fire-burn events. */
  private static final int SKILL_EARTHFIRE = 22;
  /**
   * 瞬息移动 {@code SKILL_SPACEMOVE}(21, Magic.pas:495): the Wizard self-teleport onto a
   * random cell of the home map — the last learnable row of Magic.DB 1–33 and the engine's
   * first map-displacement subsystem.
   */
  private static final int SKILL_SPACEMOVE = 21;
  /** {@code ET_FIRE = 5} (Grobal2.pas:102): the {@code TFireBurnEvent} event type on the wire. */
  private static final int ET_FIRE = 5;
  /**
   * {@code TFireBurnEvent.Run}'s 3000 ms damage gate (Event.pas:241): the redeclared
   * {@code m_dwRunTick} starts at zero, so the first burn lands on the first engine pass.
   */
  private static final long FIRE_WALL_TICK_MILLIS = 3_000;
  /**
   * {@code sDisableInSafeZoneFireCross} (Magic.pas:1140): the safe-zone refusal hint, verbatim
   * including its three ASCII dots, sent with {@code c_Red, t_Notice} when
   * {@code g_Config.boDisableInSafeZoneFireCross} refuses the cast.
   */
  private static final String FIRE_CROSS_SAFE_ZONE_MESSAGE = "安全区不允许使用...";
  /**
   * {@code SendDelayMsg(TargeTBaseObject, RM_DOOPENHEALTH, ..., 1500)} (Magic.pas:527): the
   * 心灵启示 reveal lands 1.5 s after a successful cast.
   */
  private static final long SHOW_HP_REVEAL_DELAY_MILLIS = 1_500;
  /**
   * {@code m_dwRunAwayTime := 10 * 1000} (Magic.pas:909): how long 圣言术's run-away mode freezes
   * an aggressive monster that did not take the caster as its target.
   */
  private static final long TURN_UNDEAD_RUN_AWAY_MILLIS = 10_000L;
  /** {@code Random(11) < nLevel * 2 + 4} (Magic.pas:957): 瞬息移动's success gate bound. */
  private static final int SPACE_MOVE_GATE_BOUND = 11;
  /**
   * {@code if n14 >= 201 then Break} (ObjBase.pas:4384): how many cells {@code GetRandXY}
   * inspects before it gives up and {@code SpaceMove} restores the caster's old coordinates.
   */
  private static final int GET_RAND_XY_ATTEMPTS = 201;
  private static final int DEFAULT_FIREBOOM_RANGE = 1;
  private static final int MAX_FIREBOOM_RANGE = 12;
  /**
   * {@code g_Config.nElecBlizzardRange} (M2Share.pas:2079): the half-extent of
   * 地狱雷光's caster-centred square. It ships at two — the {@code FunctionConfig.dfm}
   * spin edit is only the form's initial value, not the runtime default.
   */
  private static final int DEFAULT_ELEC_BLIZZARD_RANGE = 2;
  /** {@code EditElecBlizzardRange.MaxValue} (FunctionConfig.dfm): 12, same cap as FireBoom. */
  private static final int MAX_ELEC_BLIZZARD_RANGE = 12;
  /**
   * {@code g_Config.nSnowWindRange} (M2Share.pas:2078): the half-extent of 冰咆哮's
   * click-centred square. It ships at one, which is also the {@code !Setup.txt} value
   * ({@code SnowWindRange=1}) — unlike ElecBlizzard, the runtime default and the shipped
   * config agree here.
   */
  private static final int DEFAULT_SNOW_WIND_RANGE = 1;
  /** {@code EditSnowWindRange.MaxValue} (FunctionConfig.dfm:2069): 12, shared with FireBoom. */
  private static final int MAX_SNOW_WIND_RANGE = 12;
  /**
   * {@code g_Config.nMagTurnUndeadLevel} (M2Share.pas:2080): 圣言术 only works on monsters
   * strictly below this level. It ships at 50.
   */
  private static final int DEFAULT_MAG_TURN_UNDEAD_LEVEL = 50;
  /** {@code EditMagTurnUndeadLevel.MaxValue} (FunctionConfig.dfm): 65535, MinValue 1. */
  private static final int MAX_MAG_TURN_UNDEAD_LEVEL = 65535;
  /**
   * {@code g_Config.boDisableInSafeZoneFireCross} (M2Share.pas:2070): when true, 火墙 refuses
   * to be laid on a safe-zone cell. Ships False in Delphi — safe-zone fire walls are allowed.
   */
  private static final boolean DEFAULT_DISABLE_FIRE_CROSS_IN_SAFE_ZONE = false;
  /**
   * {@code GetMapBaseObjects(m_PEnvir, nX, nY, 1, ...)} (Magic.pas:180): 群体治愈术 collects the
   * inclusive 3x3 square around the click — the constant is hard-coded, not a config value and
   * not scaled by the skill level.
   */
  private static final int BIG_HEALING_RANGE = 1;
  /**
   * {@code MagMakeDefenceArea(nTargetX, nTargetY, 3, nPower, btState)} (Magic.pas:453/458): the
   * 幽灵盾/神圣战甲术 square half-extent. Hard-coded in the source — no {@code g_Config} switch,
   * so this slice adds no environment variable.
   */
  private static final int DEFENCE_AREA_RANGE = 3;
  /**
   * {@code GetPower13(60)} (Magic.pas:452/457): the literal both defence buffs scale into
   * seconds — one third flat (20) plus two thirds scaling with the skill level.
   */
  private static final int DEFENCE_SECONDS_BASE = 60;
  /**
   * {@code RecalcAbilitys} (ObjBase.pas:3418-3421): while {@code STATE_DEFENCEUP} /
   * {@code STATE_MAGDEFENCEUP} is live the working ability gains {@code 2 + (m_Abil.Level div 7)}
   * on the <em>upper</em> AC/MAC bound only.
   */
  private static final int DEFENCE_UP_BASE_BONUS = 2;
  private static final int DEFENCE_UP_LEVEL_DIVISOR = 7;
  /** {@code g_sMagDefenceUpTime} (M2Share.pas:3142, String.ini:149). */
  private static final String MAG_DEFENCE_UP_MESSAGE = "魔法防御力增加%d秒";
  /** {@code g_sDefenceUpTime} (M2Share.pas:3141, String.ini:148). */
  private static final String DEFENCE_UP_MESSAGE = "防御力增加%d秒";
  /** The {@code STATE_MAGDEFENCEUP} expiry hint (ObjBase.pas:4181). */
  private static final String MAG_DEFENCE_UP_EXPIRED_MESSAGE = "魔法防御力恢复正常";
  /** The {@code STATE_DEFENCEUP} expiry hint (ObjBase.pas:4175). */
  private static final String DEFENCE_UP_EXPIRED_MESSAGE = "防御力恢复正常";
  /** Magic.pas:437 {@code TargeTBaseObject.m_btLifeAttrib = LA_UNDEAD} lightning multiplier. */
  private static final double LIGHTENING_UNDEAD_MULTIPLIER = 1.5;
  /** Beam reach of 地狱火: the {@code GetNextPosition(..., 5, ...)} end cell (Magic.pas:389). */
  private static final int FIRE_BEAM_REACH = 5;
  /** Beam reach of 疾光电影: the {@code GetNextPosition(..., 8, ...)} end cell (Magic.pas:399). */
  private static final int SHOOT_LIGHTEN_BEAM_REACH = 8;
  /** {@code for i := 0 to 12} walking cells per beam (ObjBase.pas:2539). */
  private static final int MAG_PASS_THROUGH_MAX_STEPS = 13;
  /** {@code SendDelayMsg(..., RM_MAGSTRUCK, ..., 600)} (ObjBase.pas:2554). */
  private static final long PIERCING_IMPACT_DELAY_MILLIS = 600;
  /** {@code magpwr := Round(magpwr * 1.5)} for 疾光电影 per proper target (ObjBase.pas:2550). */
  private static final double MAGSTRUCK_UNDEAD_MULTIPLIER = 1.5;
  /**
   * {@code RecalcAbilitys} seeds every player at {@code m_nAntiMagic := 1} before adding
   * equipment bonuses (ObjBase.pas:2855/3400). Monsters keep the {@code Initialize} default
   * zero unless a subclass writes something else; none of the currently wired first-ten mobs do.
   */
  private static final int PLAYER_ANTIMAGIC_BASE = 1;
  /** {@code SendDelayMsg(..., 1200)} ahead of 灵魂火符's {@code RM_DELAYMAGIC} (Magic.pas:439). */
  private static final long FIRECHARM_IMPACT_DELAY_MILLIS = 1_200;
  /** {@code SendDelayMsg(..., 1000)} ahead of 施毒术's {@code RM_POISON} (Magic.pas:333/345). */
  private static final long POISON_APPLY_DELAY_MILLIS = 1_000;
  /** {@code g_Config.dwPosionDecHealthTime} (M2Share.pas:2073): the 灰色药粉 damage cadence. */
  private static final long POISON_DECHEALTH_TICK_MILLIS = 2_500;
  /** {@code g_Config.nPosionDamagarmor} (M2Share.pas:2074): 12 / 10 = 1.2x incoming damage. */
  private static final double POISON_DAMAGEARMOR_MULTIPLIER = 1.2;
  /** {@code g_Config.nAmyOunsulPoint} (M2Share.pas:2069): divides the 施毒术 point formula. */
  private static final int AMYOUNSUL_POINT_DIVISOR = 10;
  /** {@code RecalcAbilitys} seeds {@code m_btAntiPoison := 0} before worn rings add to it. */
  private static final int PLAYER_ANTIPOISON_BASE = 0;
  /** {@code TStdItem.StdMode} for the 护身符/药粉 family consumed by {@code CheckAmulet}. */
  private static final int AMULET_STD_MODE = 25;
  /** {@code CheckAmulet}/{@code UseAmulet} charge unit: one charge costs 100 raw durability. */
  private static final int AMULET_CHARGE_UNITS = 100;
  /** {@code sYouPoisoned} (M2Share.pas:2972). */
  private static final String POISONED_MESSAGE = "你中毒了[时间:%d秒，点数:%d点].";

  /**
   * {@code g_Config.nSwordLongPowerRate} (M2Share.pas:2076): the percentage 刺杀剑术 keeps of
   * its computed secondary power ({@code SwordLongAttack}, ObjBase.pas:22012). Ships at 100, i.e.
   * a no-op, and {@code g_Config.boLimitSwordLong} ships {@code False} so a whiffed thrust never
   * cancels the swing — both defaults are reproduced verbatim.
   */
  private static final int SWORD_LONG_POWER_RATE = 100;

  /**
   * {@code AllowFireHitSkill}'s re-arm gate (ObjBase.pas:9784): {@code (GetTickCount -
   * m_dwLatestFireHitTick) > 10 * 1000}. The comparison is strict, so a press exactly 10 s later
   * still fails.
   */
  private static final long FIRE_SWORD_REARM_MILLIS = 10_000L;

  /** The 烈火剑法 charge lapses 20 s after it was armed ({@code TPlayObject.Run}, ObjBase.pas:6427). */
  private static final long FIRE_SWORD_EXPIRY_MILLIS = 20_000L;

  /** {@code (GetTickCount - m_dwDoMotaeboTick) > 3 * 1000} (ObjBase.pas:9114). */
  private static final long MOTAEBO_INTERVAL_MILLIS = 3_000L;

  /** {@code g_Config.WideAttack} (M2Share.pas:1645): 半月弯刀 sweeps dir-1, dir+1, dir+2. */
  private static final int[] WIDE_ATTACK_OFFSETS = {7, 1, 2};

  /**
   * 隐身术 {@code SKILL_CLOAK}(18) and 集体隐身术 {@code SKILL_BIGCLOAK}(19): the 护身符-gated
   * pair that only arms {@code m_wStatusTimeArr[STATE_TRANSPARENT]} (Magic.pas:476/481).
   */
  private static final int SKILL_CLOAK = 18;
  private static final int SKILL_BIGCLOAK = 19;
  /** {@code GetPower13(30)}'s base (Magic.pas:477/481): 15/20/25/30 at skill levels 0..3. */
  private static final int TRANSPARENT_SECONDS_BASE = 30;
  /**
   * {@code MagMakePrivateTransparent}'s {@code GetMapBaseObjects(..., 9, ...)} radius
   * (Magic.pas:745): the monsters that may drop their target when the cloak lands.
   */
  private static final int TRANSPARENT_DE_TARGET_RANGE = 9;
  /** {@code MagMakeGroupTransparent}'s {@code GetMapBaseObjects(nX, nY, 1, ...)} (Magic.pas:1292). */
  private static final int GROUP_TRANSPARENT_RANGE = 1;
  /** {@code SendDelayMsg(..., 800)} (Magic.pas:1300): the group cloak's own delivery delay. */
  private static final long GROUP_TRANSPARENT_DELAY_MILLIS = 800;
  /** {@code m_wStatusTimeArr[STATE_TRANSPARENT] := 1} on a moved step (ObjBase.pas:2061). */
  private static final long TRANSPARENT_MOVE_REVEAL_MILLIS = 1_000L;
  /** {@code STATE_TRANSPARENT = 8} (Common/Grobal2.pas:88). */
  private static final int STATE_TRANSPARENT = 8;
  /** {@code STATE_DEFENCEUP = 9}. */
  private static final int STATE_DEFENCEUP = 9;
  /** {@code STATE_MAGDEFENCEUP = 10}. */
  private static final int STATE_MAGDEFENCEUP = 10;
  /** {@code STATE_BUBBLEDEFENCEUP = 11} — the 魔法盾 slot. */
  private static final int STATE_BUBBLEDEFENCEUP = 11;
  /**
   * {@code STATE_OPENHEATH = $00000002} (Common/Grobal2.pas:96): 心灵启示's marker bit. Delphi
   * folds it into {@code m_nCharStatus} through {@code GetCharStatus} (ObjBase.pas:20087,
   * {@code (m_nCharStatusEx and $FFFFF) or nStatus}) but never repaints the status word from
   * {@code MakeOpenHealth}/{@code BreakOpenHealth} themselves — the client drives its HP bar
   * off {@code SM_OPENHEALTH}/{@code SM_CLOSEHEALTH} and only re-reads this bit when some
   * unrelated status change repaints the word (Actor.pas:1460).
   */
  private static final int STATE_OPENHEATH = 0x2;
  /** {@code POISON_DECHEALTH = 0} (Common/Grobal2.pas): {@code m_wStatusTimeArr} slot 0. */
  private static final int POISON_DECHEALTH_SLOT = 0;
  /** {@code POISON_DAMAGEARMOR = 1} (Common/Grobal2.pas): {@code m_wStatusTimeArr} slot 1. */
  private static final int POISON_DAMAGEARMOR_SLOT = 1;
  /**
   * {@code m_nHitSpeed} rides along as the {@code series} word of RM_CHARSTATUSCHANGED
   * (ObjBase.pas:20141/5968). It accumulates item attack-speed bonuses
   * ({@code m_AddAbil.nHitSpeed}, ObjBase.pas:3403); no Java item carries one yet, so the
   * modelled value is the zero a stocked character sends.
   */
  private static final int CHAR_STATUS_HIT_SPEED = 0;

  public record Config(
      Duration tickInterval,
      int viewRange,
      int maxCommandsPerTick,
      long hitIntervalMillis,
      long corpseLingerMillis,
      long itemLingerMillis,
      long regenIntervalMillis,
      long saveIntervalMillis,
      long testGold,
      int fireBoomRange,
      int elecBlizzardRange,
      int snowWindRange,
      int magTurnUndeadLevel,
      boolean disableFireCrossInSafeZone) {

    public Config {
      Objects.requireNonNull(tickInterval, "tickInterval");
      if (tickInterval.isZero() || tickInterval.isNegative() || tickInterval.toMillis() == 0)
        throw new IllegalArgumentException("tick interval must be at least one millisecond");
      if (viewRange < 1) throw new IllegalArgumentException("view range must be positive");
      if (maxCommandsPerTick < 1) throw new IllegalArgumentException("command limit must be positive");
      if (hitIntervalMillis < 0) throw new IllegalArgumentException("hit interval must not be negative");
      if (corpseLingerMillis < 0 || itemLingerMillis < 0)
        throw new IllegalArgumentException("linger durations must not be negative");
      if (regenIntervalMillis < 1)
        throw new IllegalArgumentException("regen interval must be at least one millisecond");
      if (saveIntervalMillis < 1)
        throw new IllegalArgumentException("save interval must be at least one millisecond");
      if (testGold < 0 || testGold > PlayerState.MAX_GOLD)
        throw new IllegalArgumentException("test gold must be within 0.." + PlayerState.MAX_GOLD);
      if (fireBoomRange < 1 || fireBoomRange > MAX_FIREBOOM_RANGE)
        throw new IllegalArgumentException("FireBoom range must be within 1.." + MAX_FIREBOOM_RANGE);
      if (elecBlizzardRange < 1 || elecBlizzardRange > MAX_ELEC_BLIZZARD_RANGE)
        throw new IllegalArgumentException(
            "ElecBlizzard range must be within 1.." + MAX_ELEC_BLIZZARD_RANGE);
      if (snowWindRange < 1 || snowWindRange > MAX_SNOW_WIND_RANGE)
        throw new IllegalArgumentException(
            "SnowWind range must be within 1.." + MAX_SNOW_WIND_RANGE);
      if (magTurnUndeadLevel < 1 || magTurnUndeadLevel > MAX_MAG_TURN_UNDEAD_LEVEL)
        throw new IllegalArgumentException("MagTurnUndead level must be within 1.."
            + MAX_MAG_TURN_UNDEAD_LEVEL);
    }

    /** Compatibility constructor retaining the pre-W49 canonical signature. */
    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick,
        long hitIntervalMillis, long corpseLingerMillis, long itemLingerMillis,
        long regenIntervalMillis, long saveIntervalMillis, long testGold, int fireBoomRange,
        int elecBlizzardRange, int snowWindRange, int magTurnUndeadLevel) {
      this(tickInterval, viewRange, maxCommandsPerTick, hitIntervalMillis, corpseLingerMillis,
          itemLingerMillis, regenIntervalMillis, saveIntervalMillis, testGold, fireBoomRange,
          elecBlizzardRange, snowWindRange, magTurnUndeadLevel,
          DEFAULT_DISABLE_FIRE_CROSS_IN_SAFE_ZONE);
    }

    /** Compatibility constructor retaining the pre-W48 canonical signature. */
    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick,
        long hitIntervalMillis, long corpseLingerMillis, long itemLingerMillis,
        long regenIntervalMillis, long saveIntervalMillis, long testGold, int fireBoomRange,
        int elecBlizzardRange, int snowWindRange) {
      this(tickInterval, viewRange, maxCommandsPerTick, hitIntervalMillis, corpseLingerMillis,
          itemLingerMillis, regenIntervalMillis, saveIntervalMillis, testGold, fireBoomRange,
          elecBlizzardRange, snowWindRange, DEFAULT_MAG_TURN_UNDEAD_LEVEL,
          DEFAULT_DISABLE_FIRE_CROSS_IN_SAFE_ZONE);
    }

    /** Compatibility constructor retaining the pre-W44 canonical signature. */
    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick,
        long hitIntervalMillis, long corpseLingerMillis, long itemLingerMillis,
        long regenIntervalMillis, long saveIntervalMillis, long testGold, int fireBoomRange,
        int elecBlizzardRange) {
      this(tickInterval, viewRange, maxCommandsPerTick, hitIntervalMillis, corpseLingerMillis,
          itemLingerMillis, regenIntervalMillis, saveIntervalMillis, testGold, fireBoomRange,
          elecBlizzardRange, DEFAULT_SNOW_WIND_RANGE);
    }

    /** Compatibility constructor retaining the pre-W43 canonical signature. */
    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick,
        long hitIntervalMillis, long corpseLingerMillis, long itemLingerMillis,
        long regenIntervalMillis, long saveIntervalMillis, long testGold, int fireBoomRange) {
      this(tickInterval, viewRange, maxCommandsPerTick, hitIntervalMillis, corpseLingerMillis,
          itemLingerMillis, regenIntervalMillis, saveIntervalMillis, testGold, fireBoomRange,
          DEFAULT_ELEC_BLIZZARD_RANGE);
    }

    /** Compatibility constructor retaining the pre-W42 canonical signature. */
    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick,
        long hitIntervalMillis, long corpseLingerMillis, long itemLingerMillis,
        long regenIntervalMillis, long saveIntervalMillis, long testGold) {
      this(tickInterval, viewRange, maxCommandsPerTick, hitIntervalMillis, corpseLingerMillis,
          itemLingerMillis, regenIntervalMillis, saveIntervalMillis, testGold,
          DEFAULT_FIREBOOM_RANGE);
    }

    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick,
        long hitIntervalMillis, long corpseLingerMillis, long itemLingerMillis,
        long regenIntervalMillis, long saveIntervalMillis) {
      this(tickInterval, viewRange, maxCommandsPerTick, hitIntervalMillis, corpseLingerMillis,
          itemLingerMillis, regenIntervalMillis, saveIntervalMillis, 0, DEFAULT_FIREBOOM_RANGE);
    }

    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick,
        long hitIntervalMillis, long corpseLingerMillis, long itemLingerMillis) {
      // g_Config.dwRegenMonstersTime defaults to 200ms and dwSaveHumanRcdTime to 10 minutes
      // (M2Share.pas defaults).
      this(tickInterval, viewRange, maxCommandsPerTick, hitIntervalMillis, corpseLingerMillis,
          itemLingerMillis, 200, 10 * 60 * 1000);
    }

    public Config(Duration tickInterval, int viewRange, int maxCommandsPerTick) {
      // TPlayObject.m_dwHitIntervalTime defaults to 900ms; corpses/items use the Delphi defaults.
      this(tickInterval, viewRange, maxCommandsPerTick, 900, 5_000, 180_000);
    }

    public static Config defaults() {
      // TPlayObject.m_nViewRange is 12 in ObjBase.pas.
      return new Config(Duration.ofMillis(50), 12, 10_000);
    }
  }

  private final Config config;
  private final Map<String, GameMap> maps = new LinkedHashMap<>();
  private final Map<Integer, Player> players = new HashMap<>();
  private final Map<String, Integer> playersByName = new HashMap<>();
  private final Map<Integer, Monster> monsters = new LinkedHashMap<>();
  private final Map<Integer, Npc> npcs = new LinkedHashMap<>();
  private final List<Spawner> spawners = new ArrayList<>();
  private final Map<Integer, GroundItem> groundItems = new LinkedHashMap<>();
  private final Map<Integer, Long> itemDropTimes = new HashMap<>();
  /**
   * The {@code g_EventManager} (Event.pas:68) slice this engine implements so far: the
   * {@code TFireBurnEvent} cross arms of 火墙 (Magic.pas:1135). Insertion-ordered, ticked by
   * {@link #tickFireWalls}; pure in-memory state, never persisted, exactly like Delphi.
   */
  private final Map<Integer, FireWallEvent> fireWalls = new LinkedHashMap<>();
  private final List<PendingMagicImpact> pendingMagicImpacts = new ArrayList<>();
  private final ConcurrentLinkedQueue<Pending<?>> commands = new ConcurrentLinkedQueue<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().name("mir2-world").factory());
  private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong tickCount = new AtomicLong();
  /**
   * {@code g_DisableTakeOffList} (M2Share.pas): items that can be neither taken off nor dropped
   * on death. Mutated only from the world thread through {@link #setDisableTakeOffList}; empty by
   * default, matching a server booted without a {@code DisableTakeOffList.txt}.
   */
  private DisableTakeOffList disableTakeOffList = DisableTakeOffList.empty();
  private final LongSupplier clock;
  /**
   * The {@link WorldClock} behind {@link #clock} when one was supplied, otherwise null (a
   * caller handed in a bare {@code LongSupplier}). Only the tick loop touches it, and only
   * to advance a {@link WorldClock.Mode#VIRTUAL} clock.
   */
  private final WorldClock worldClock;
  private final WorldRandom random;
  private final PlayerStateStore playerStateStore;
  private final ItemDatabase itemDatabase;
  private final MagicCatalog magicCatalog;
  private final IntSupplier hourSupplier;
  private int gameTime;
  private int nextObjectId = 1;
  private int nextItemMakeIndex = 1;

  public WorldEngine(Config config, Collection<GameMap> maps) {
    this(config, maps, System::currentTimeMillis, WorldRandom.unseeded(), PlayerStateStore.none(),
        ItemDatabase.empty());
  }

  public WorldEngine(Config config, Collection<GameMap> maps, PlayerStateStore playerStateStore) {
    this(config, maps, System::currentTimeMillis, WorldRandom.unseeded(), playerStateStore,
        ItemDatabase.empty());
  }

  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase) {
    this(config, maps, System::currentTimeMillis, WorldRandom.unseeded(), playerStateStore,
        itemDatabase);
  }

  /**
   * Production constructor with an explicit randomness policy. Pass
   * {@link WorldRandom#seeded(long)} to make the damage/loot streams reproducible across two
   * server processes — the precondition for PvE 影子对拍.
   */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase,
      WorldRandom random) {
    this(config, maps, System::currentTimeMillis, random, playerStateStore, itemDatabase);
  }

  /**
   * Production constructor with both determinism knobs: the randomness policy and the time
   * source. {@code WorldClock.system()} reproduces the historic behaviour exactly;
   * {@code WorldClock.virtual(tickMs)} makes every cadence tick-derived so two processes
   * can be 对拍'd with monsters that actually move.
   */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase,
      WorldRandom random,
      WorldClock worldClock) {
    this(config, maps, worldClock, random, playerStateStore, itemDatabase,
        defaultHourSupplier(worldClock));
  }

  /**
   * The day/night hour is the world's second wall-clock dependency ({@code GetGameTime} reads
   * the host time of day). A virtual world derives it from its own clock instead, so two
   * processes agree on the game time — and on the {@code SM_DAYCHANGING} broadcasts — no
   * matter when they were started. A system clock keeps reading the host's hour.
   */
  private static IntSupplier defaultHourSupplier(WorldClock worldClock) {
    Objects.requireNonNull(worldClock, "worldClock");
    return worldClock.isVirtual()
        ? () -> (int) Math.floorMod(worldClock.millis() / 3_600_000L, 24L)
        : () -> LocalTime.now().getHour();
  }

  public WorldEngine(Collection<GameMap> maps) {
    this(Config.defaults(), maps);
  }

  /** Deterministic constructor: tests inject a virtual clock and a seeded damage generator. */
  public WorldEngine(Config config, Collection<GameMap> maps, LongSupplier clock, Random random) {
    this(config, maps, clock, WorldRandom.of(random), PlayerStateStore.none(), ItemDatabase.empty());
  }

  /** Deterministic constructor with a durable player-state port. */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      Random random,
      PlayerStateStore playerStateStore) {
    this(config, maps, clock, WorldRandom.of(random), playerStateStore, ItemDatabase.empty());
  }

  /** Deterministic constructor with a durable player-state port and the standard-item catalog. */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      Random random,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase) {
    this(config, maps, clock, WorldRandom.of(random), playerStateStore, itemDatabase);
  }

  /** Deterministic constructor taking the split-stream randomness policy directly. */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      WorldRandom random,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase) {
    this(config, maps, clock, random, playerStateStore, itemDatabase, () -> LocalTime.now().getHour());
  }

  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      Random random,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase,
      IntSupplier hourSupplier) {
    this(config, maps, clock, WorldRandom.of(random), playerStateStore, itemDatabase, hourSupplier);
  }

  /**
   * Full constructor taking an explicit {@link WorldClock}. Pass
   * {@link WorldClock#virtual(long)} to make every cadence in the world (monster walk/attack
   * intervals, respawns, regeneration, PK decay, door sweeps) a pure function of the tick
   * counter instead of the host clock — the precondition for 对拍'ing <em>moving</em>
   * monsters across two processes. Production uses {@link WorldClock#system()}.
   */
  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      WorldClock worldClock,
      WorldRandom random,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase,
      IntSupplier hourSupplier) {
    this(config, maps, Objects.requireNonNull(worldClock, "worldClock").asSupplier(), random,
        playerStateStore, itemDatabase, hourSupplier, worldClock);
  }

  public WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      WorldRandom random,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase,
      IntSupplier hourSupplier) {
    this(config, maps, clock, random, playerStateStore, itemDatabase, hourSupplier, null);
  }

  private WorldEngine(
      Config config,
      Collection<GameMap> maps,
      LongSupplier clock,
      WorldRandom random,
      PlayerStateStore playerStateStore,
      ItemDatabase itemDatabase,
      IntSupplier hourSupplier,
      WorldClock worldClock) {
    this.config = Objects.requireNonNull(config);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.worldClock = worldClock;
    this.random = Objects.requireNonNull(random, "random");
    this.playerStateStore = Objects.requireNonNull(playerStateStore, "playerStateStore");
    this.itemDatabase = Objects.requireNonNull(itemDatabase, "itemDatabase");
    this.magicCatalog = MagicCatalog.defaults();
    this.hourSupplier = Objects.requireNonNull(hourSupplier, "hourSupplier");
    this.gameTime = gameTimeFromHour(hourSupplier.getAsInt());
    this.nextItemMakeIndex = seedMakeIndex(playerStateStore.itemMakeIndexHighWater());
    if (maps.isEmpty()) throw new IllegalArgumentException("at least one map is required");
    for (GameMap map : maps) {
      Objects.requireNonNull(map, "map");
      if (this.maps.putIfAbsent(map.id(), map) != null)
        throw new IllegalArgumentException("duplicate map id: " + map.id());
    }
  }

  /** Starts fixed-rate ticks. Callers may instead use {@link #tickOnce()} in deterministic tests. */
  public void start() {
    if (closed.get()) throw new IllegalStateException("world engine is closed");
    if (!started.compareAndSet(false, true)) throw new IllegalStateException("world engine already started");
    long interval = config.tickInterval().toMillis();
    scheduler.scheduleAtFixedRate(this::scheduledTick, 0, interval, TimeUnit.MILLISECONDS);
  }

  public boolean isRunning() {
    return started.get() && !closed.get() && !scheduler.isShutdown();
  }

  public long tickCount() {
    return tickCount.get();
  }

  /**
   * The engine's time source when one was supplied as a {@link WorldClock}, otherwise empty
   * (legacy callers pass a bare {@code LongSupplier}). Harnesses use this to tell whether a
   * world's cadences are tick-derived — i.e. whether a moving monster is comparable at all
   * across two processes.
   */
  public java.util.Optional<WorldClock> worldClock() {
    return java.util.Optional.ofNullable(worldClock);
  }

  /** Current world time in milliseconds, as every cadence check inside the engine reads it. */
  public long now() {
    return clock.getAsLong();
  }

  /**
   * Advances a {@link WorldClock.Mode#MANUAL} world by {@code ticks} ticks, on the world
   * thread, and returns the new world time.
   *
   * <p>This is the determinism pump behind 会动的怪对拍: with a manual clock the engine's
   * scheduler still runs, but every pass sees the same timestamp, so monsters, respawns and
   * regeneration stay frozen until a harness asks for time to pass. Pumping N ticks then
   * replays exactly N tick bodies at N successive timestamps, which two processes reproduce
   * identically regardless of their host load. Each pumped tick runs a full
   * {@link #tickOnce()} body so the ordering inside a tick is unchanged.
   *
   * <p>A no-op (returns the current time) on SYSTEM or VIRTUAL worlds, whose time is not the
   * caller's to move.
   */
  public CompletableFuture<Long> advanceTicks(int ticks) {
    if (ticks < 0) throw new IllegalArgumentException("tick count must not be negative");
    return submit(() -> {
      if (worldClock == null || worldClock.mode() != WorldClock.Mode.MANUAL) return now();
      for (int index = 0; index < ticks; index++) {
        worldClock.advanceOneTick();
        runTickBody();
      }
      return now();
    });
  }

  /**
   * The world seed when this engine draws from independent per-stream generators
   * ({@link WorldRandom#seeded(long)}), otherwise empty. Two engines reporting the same seed
   * produce the same damage and loot sequences, which is what makes PvE 影子对拍 meaningful.
   */
  public java.util.OptionalLong worldSeed() {
    return random.seed();
  }

  public CompletableFuture<WorldObjectSnapshot> enterPlayer(
      String name,
      String mapId,
      Position position,
      Direction direction,
      WorldEventSink sink) {
    return enterPlayer(name, mapId, position, direction, 0, 0, sink);
  }

  /** Transient compatibility overload used by isolated world tests. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayer(
      String name,
      String mapId,
      Position position,
      Direction direction,
      int feature,
      int status,
      WorldEventSink sink) {
    return enterPlayer(transientCharacterId(name), name, mapId, position, direction, feature, status, sink);
  }

  /** Enters a durable character and restores its ability and backpack before MapEntered is emitted. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayer(
      UUID characterId,
      String name,
      String mapId,
      Position position,
      Direction direction,
      int feature,
      int status,
      WorldEventSink sink) {
    return enterPlayer(characterId, name, mapId, position, direction, feature, status,
        LevelAbilities.JOB_WARRIOR, sink);
  }

  /** Job-aware variant; the job selects the {@code RecalcLevelAbilitys} growth branch. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayer(
      UUID characterId,
      String name,
      String mapId,
      Position position,
      Direction direction,
      int feature,
      int status,
      int job,
      WorldEventSink sink) {
    Objects.requireNonNull(characterId, "characterId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(sink, "sink");
    return submit(() -> enter(
        characterId, name, mapId, position, direction, feature, status, job, sink));
  }

  /** Enters at the requested spawn or the nearest currently available cell. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayerNear(
      String name,
      String mapId,
      Position preferredPosition,
      Direction direction,
      int feature,
      int status,
      WorldEventSink sink) {
    return enterPlayerNear(transientCharacterId(name), name, mapId, preferredPosition,
        direction, feature, status, sink);
  }

  /** Durable-character variant of {@link #enterPlayerNear(String, String, Position, Direction, int, int, WorldEventSink)}. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayerNear(
      UUID characterId,
      String name,
      String mapId,
      Position preferredPosition,
      Direction direction,
      int feature,
      int status,
      WorldEventSink sink) {
    return enterPlayerNear(characterId, name, mapId, preferredPosition, direction, feature,
        status, LevelAbilities.JOB_WARRIOR, sink);
  }

  /** Job-aware variant of the nearest-cell entry. */
  public CompletableFuture<WorldObjectSnapshot> enterPlayerNear(
      UUID characterId,
      String name,
      String mapId,
      Position preferredPosition,
      Direction direction,
      int feature,
      int status,
      int job,
      WorldEventSink sink) {
    Objects.requireNonNull(characterId, "characterId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(preferredPosition, "preferredPosition");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(sink, "sink");
    return submit(() -> {
      GameMap map = requireMap(mapId);
      if (map.flags().noReconnect() && !map.flags().noReconnectMap().isBlank()) {
        String redirectId = map.flags().noReconnectMap();
        if (maps.containsKey(redirectId)) {
          map = maps.get(redirectId);
        }
      }
      return enter(characterId, name, map.id(), nearestAvailable(map, preferredPosition),
          direction, feature, status, job, sink);
    });
  }

  public CompletableFuture<WorldObjectSnapshot> spawnMonster(
      MonsterTemplate template, String mapId, Position position, Direction direction) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    return submit(() -> spawn(template, mapId, position, direction));
  }

  /**
   * Places a static {@code TNormNpc}/{@code TMerchant} stand-in on the map — the visible
   * half of the NPC slice. The object never moves and never fights; it occupies its cell
   * (so nothing can stand on it), appears in every entering/moving viewer's sight through
   * the ordinary {@code SM_TURN} appearance flow, and answers {@code CM_QUERYUSERNAME}
   * with its name like any other actor.
   *
   * <p>{@code appearance} is the {@code Npc.wil} sprite index the client's
   * {@code TNpcActor} renders ({@code m_nBodyOffset := MERCHANTFRAME * m_wAppearance},
   * Actor.pas:2896), i.e. the high word of {@code MakeMonsterFeature(RC_NPC, 0, wAppr)}.
   * The low byte stays {@code RC_NPC = 50} so the client dispatches to {@code TNpcActor}
   * (PlayScn.pas NewActor). The Market_Def script engine, dialogues and trading stay
   * red-lined exactly as documented in docs/translation-map.md.
   *
   * @throws IllegalStateException when the cell is not walkable or already occupied
   */
  public CompletableFuture<WorldObjectSnapshot> spawnNpc(
      String name, String mapId, Position position, int appearance, Direction direction) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(direction, "direction");
    if (appearance < 0 || appearance > 0xffff) {
      throw new IllegalArgumentException("npc appearance must be a 16-bit value");
    }
    return submit(() -> {
      GameMap map = requireMap(mapId);
      if (!map.canWalk(position)) throw new IllegalStateException("spawn cell is not available: " + position);
      if (map.objectAt(position) != 0) throw new IllegalStateException("spawn cell is occupied: " + position);
      int id = allocateObjectId();
      Npc npc = new Npc(id, name, map, position, appearance, direction);
      map.place(id, position);
      npcs.put(id, npc);
      WorldEvent appeared = new WorldEvent.ObjectAppeared(npc.snapshot());
      for (int viewerId : visibleIds(map, position, id)) emit(players.get(viewerId), appeared);
      return npc.snapshot();
    });
  }

  /**
   * Registers a MonGen auto-respawn definition on {@code mapId}. The world evaluates spawners
   * every {@code regenIntervalMillis} (dwRegenMonstersTime, 200ms default) and replenishes
   * losses when their respawn window has elapsed, mirroring {@code TUserEngine.RegenMonsters}.
   */
  /**
   * Registers one parsed {@code MonGen.txt} row. The definition's own map name is only a
   * label from the file; {@code mapId} is the map the caller resolved it to.
   */
  public CompletableFuture<Void> addSpawner(
      MonsterTemplate template, String mapId, MonsterSpawnDefinition definition) {
    Objects.requireNonNull(definition, "definition");
    return addSpawner(template, mapId, new Position(definition.x(), definition.y()),
        definition.range(), definition.count(), Duration.ofMillis(definition.respawnMillis()));
  }

  public CompletableFuture<Void> addSpawner(MonsterTemplate template, String mapId,
      Position center, int radius, int count, Duration respawnInterval) {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(center, "center");
    Objects.requireNonNull(respawnInterval, "respawnInterval");
    if (count <= 0) throw new IllegalArgumentException("spawner count must be positive");
    return submit(() -> {
      GameMap map = requireMap(mapId);
      spawners.add(new Spawner(template, map, center, radius, count, respawnInterval.toMillis()));
      return null;
    });
  }

  public CompletableFuture<MoveResult> move(
      int playerId, Position target, Direction direction, MovementKind movement) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(movement, "movement");
    return submit(() -> movePlayer(playerId, target, direction, movement));
  }

  public CompletableFuture<Boolean> turn(int playerId, Position claimedPosition, Direction direction) {
    Objects.requireNonNull(claimedPosition, "claimedPosition");
    Objects.requireNonNull(direction, "direction");
    return submit(() -> turnPlayer(playerId, claimedPosition, direction));
  }

  /** Melee attack on the single cell in front of the player, as in {@code TBaseObject.AttackDir}. */
  public CompletableFuture<AttackResult> attack(
      int playerId, Position claimedPosition, Direction direction, AttackKind attack) {
    Objects.requireNonNull(claimedPosition, "claimedPosition");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(attack, "attack");
    return submit(() -> attackWith(playerId, claimedPosition, direction, attack));
  }

  /** CM_SPELL: target id is MakeLong(Param,Series), while Recog carries the target cell. */
  public CompletableFuture<Boolean> castSpell(
      int playerId, int magicId, Position target, int targetId) {
    Objects.requireNonNull(target, "target");
    if (magicId < 1) throw new IllegalArgumentException("magic id must be positive");
    if (targetId < 0) throw new IllegalArgumentException("target id must not be negative");
    return submit(() -> castPlayerSpell(playerId, magicId, target, targetId));
  }

  /** CM_MAGICKEYCHANGE changes durable TUserMagic.btKey and has no direct wire response. */
  public CompletableFuture<Boolean> changeMagicKey(int playerId, int magicId, int key) {
    if (magicId < 1) throw new IllegalArgumentException("magic id must be positive");
    if (key < 0 || key > 0xff) throw new IllegalArgumentException("magic key must be a byte");
    return submit(() -> changePlayerMagicKey(playerId, magicId, key));
  }

  public CompletableFuture<List<PlayerSkill>> skills(int playerId) {
    return submit(() -> List.copyOf(requirePlayer(playerId).skills.values()));
  }

  public CompletableFuture<Boolean> magicShieldActive(int playerId) {
    return submit(() -> requirePlayer(playerId).magicShieldUntil > clock.getAsLong());
  }

  /**
   * {@code CM_TAKEONITEM} -> {@code TPlayObject.ClientTakeOnItems} (ObjBase.pas:17072): moves
   * the bag item identified by {@code makeIndex} + {@code itemName} into {@code slotIndex}.
   */
  public CompletableFuture<Boolean> equip(
      int playerId, int slotIndex, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> equipItem(playerId, slotIndex, makeIndex, itemName));
  }

  /** {@code CM_TAKEOFFITEM} -> {@code ClientTakeOffItems} (ObjBase.pas:17221). */
  public CompletableFuture<Boolean> unequip(
      int playerId, int slotIndex, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> unequipItem(playerId, slotIndex, makeIndex, itemName));
  }

  /** {@code CM_EAT} -> {@code ClientUseItems} (ObjBase.pas:17300). */
  public CompletableFuture<Boolean> useItem(int playerId, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> consumeItem(playerId, makeIndex, itemName));
  }

  /** {@code CM_DROPITEM} -> {@code ClientDropItem} (ObjBase.pas:16213). */
  public CompletableFuture<Boolean> dropItem(int playerId, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> dropBagItem(playerId, makeIndex, itemName));
  }

  /**
   * {@code CM_MERCHANTDLGSELECT -> TMerchant.UserSelect} (ObjNpc.pas:1419), dispatched through the
   * {@link MerchantCommand} catalog. As in Delphi, {@code m_sScriptLable} is set to the raw label
   * for every {@code @}-prefixed selection (which is how the repair path later picks normal vs.
   * special mode), and the dispatch honours the labels this engine actually implements:
   *
   * <ul>
   *   <li>{@code @repair} / {@code @s_repair} answer {@code SM_SENDUSERREPAIR} so the client opens
   *       its repair dialog ({@link MerchantSelectOutcome#REPAIR_DIALOG}).</li>
   *   <li>{@code @exit} answers {@code SM_MERCHANTDLGCLOSE} to close the window
   *       ({@link MerchantSelectOutcome#DIALOG_CLOSED}).</li>
   * </ul>
   *
   * <p>Every other label — a deferred transaction/script label or one absent from the catalog —
   * is <b>rejected observably</b> ({@link WorldEvent.MerchantActionRejected} + a log line) rather
   * than silently stored, so an unimplemented script can never masquerade as success (W29 red
   * line). No spurious wire packet is sent on rejection: the real client would hear nothing from
   * Delphi either, since those arms are guarded behind merchant {@code m_boXXX} flags that no
   * loaded Market_Def script sets here.
   *
   * <p>The merchant proximity check ({@code FindMerchant}, same map, |Δ|&lt;15) needs full NPC
   * objects and is deferred to the NPC slice.
   *
   * @return the observable outcome of the selection
   */
  public CompletableFuture<MerchantSelectOutcome> selectMerchantLabel(
      int playerId, int merchantId, String label) {
    Objects.requireNonNull(label, "label");
    return submit(() -> {
      Player player = requirePlayer(playerId);
      if (!player.ability.alive()) return MerchantSelectOutcome.IGNORED;
      // UserSelect only reacts to labels that start with '@'; GetValidStr3 splits the input
      // at CR for the @@-input labels.
      String trimmed = label.strip();
      if (trimmed.isEmpty() || trimmed.charAt(0) != '@') return MerchantSelectOutcome.IGNORED;
      String firstToken = trimmed.split("\r", 2)[0];
      // Delphi always assigns m_sScriptLable := sData at the top, so re-selecting a non-repair
      // label correctly drops back to normal-repair mode on any later @repair.
      player.merchantLabel = trimmed;

      MerchantCommand command = MerchantCommand.resolve(firstToken).orElse(null);
      if (command != null && command.status() == MerchantCommand.Status.IMPLEMENTED) {
        switch (command.category()) {
          case REPAIR -> {
            emit(player, new WorldEvent.MerchantRepairDialog(player.id, merchantId));
            return MerchantSelectOutcome.REPAIR_DIALOG;
          }
          case DIALOG_NAVIGATION -> {
            // The only implemented navigation label is @exit.
            emit(player, new WorldEvent.MerchantDialogClosed(player.id, merchantId));
            return MerchantSelectOutcome.DIALOG_CLOSED;
          }
          default -> { /* fall through to rejection for any other implemented category */ }
        }
      }

      MerchantCommand.Category category = command == null ? null : command.category();
      MerchantCommand.Status status = command == null ? null : command.status();
      String reason = command == null
          ? "未知商人标签（不在 Market_Def 指令清单内）"
          : command.note();
      LOG.fine(() -> "rejecting merchant label '" + firstToken + "' for player " + player.id
          + " (" + (command == null ? "unknown" : status) + "): " + reason);
      emit(player, new WorldEvent.MerchantActionRejected(
          player.id, merchantId, firstToken, category, status, reason));
      return MerchantSelectOutcome.REJECTED;
    });
  }

  /**
   * {@code CM_MERCHANTQUERYREPAIRCOST -> TMerchant.ClientQueryRepairCost} (ObjNpc.pas:2385).
   * The quote is {@code Round(price div 3 / DuraMax * (DuraMax - Dura))} — note the integer
   * division before the real arithmetic — times three for a special repair. Like Delphi, the
   * quote only ever addresses bag items ({@code m_ItemList}); worn gear has to be taken off
   * first. An item without a price or one at full durability gets the Delphi "cannot repair"
   * answer of -1. A MakeIndex/name that matches nothing in the bag stays silent, exactly like
   * {@code ClientQueryRepairCost} exiting without a reply.
   *
   * @return the quoted cost, -1 when the merchant refuses, or {@code null} when Delphi would
   *     stay silent (no such bag item)
   */
  public CompletableFuture<Integer> queryRepairCost(int playerId, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> {
      Player player = requirePlayer(playerId);
      int bagIndex = findBagItem(player, makeIndex, itemName);
      if (bagIndex < 0) return null;
      BackpackItem item = player.backpack.get(bagIndex);
      int cost = repairQuote(item, isSpecialRepair(player));
      emit(player, new WorldEvent.RepairCostResolved(player.id, cost));
      return cost;
    });
  }

  /**
   * {@code CM_USERREPAIRITEM -> TMerchant.ClientRepairItem} (ObjNpc.pas:2423). Normal repair
   * lowers DuraMax by the wear's thirtieth before topping Dura up
   * ({@code Dec(DuraMax, (DuraMax - Dura) div 30); Dura := DuraMax}); special repair keeps
   * DuraMax and just refills. The charged price mirrors the Delphi quirk: the query multiplies
   * the normal quote by three, while the charge triples {@code nPrice} *before* the
   * {@code div 3} — so the two only agree when the item price is a multiple of three. A
   * wallet that cannot cover the charge fails without touching the item. {@code GotoLable}
   * (the script follow-up) does not exist here yet.
   *
   * @return true on SM_USERREPAIRITEM_OK, false on SM_USERREPAIRITEM_FAIL, or {@code null}
   *     when Delphi would stay silent (no such bag item)
   */
  public CompletableFuture<Boolean> repairItem(int playerId, int makeIndex, String itemName) {
    Objects.requireNonNull(itemName, "itemName");
    return submit(() -> {
      Player player = requirePlayer(playerId);
      int bagIndex = findBagItem(player, makeIndex, itemName);
      if (bagIndex < 0) return null;
      BackpackItem item = player.backpack.get(bagIndex);
      boolean special = isSpecialRepair(player);
      long price = item.item().price();
      if (special) price *= SUPER_REPAIR_PRICE_RATE;
      boolean canRepair = price > 0 && item.duraMax() > item.dura()
          && item.item().stdMode() != REPAIR_REFUSED_STD_MODE;
      int charge = canRepair ? repairCharge(price, item.duraMax(), item.dura()) : 0;
      if (!canRepair || player.gold < charge) {
        emit(player, new WorldEvent.RepairRejected(player.id));
        return false;
      }
      int duraMax = special ? item.duraMax()
          : item.duraMax() - (item.duraMax() - item.dura()) / REPAIR_ITEM_DEC_DURA;
      BackpackItem repaired = new BackpackItem(item.item(), item.makeIndex(), duraMax, duraMax);

      List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
      long previousGold = player.gold;
      player.backpack.set(bagIndex, repaired);
      player.gold -= charge;
      try {
        persist(player);
      } catch (RuntimeException failure) {
        player.backpack.clear();
        player.backpack.addAll(previousBackpack);
        player.gold = previousGold;
        throw failure;
      }
      emit(player, new WorldEvent.ItemRepaired(player.id, (int) player.gold, duraMax, duraMax));
      return true;
    });
  }

  /**
   * {@code PlayObject.m_sScriptLable = sSUPERREPAIR} ('@s_repair') selects the special repair;
   * anything else — including the empty label of a fresh login — repairs normally. Delphi
   * compares with {@code =}, which is case-sensitive.
   */
  private static boolean isSpecialRepair(Player player) {
    return "@s_repair".equals(player.merchantLabel);
  }

  /**
   * The quoted price: {@code Round(nPrice div 3 / DuraMax * (DuraMax - Dura))}, ×3 for the
   * special variant ({@code nSuperRepairPriceRate}). Returns -1 when there is nothing to
   * mend or the item has no price — Delphi's "???? 金币" answer.
   */
  private static int repairQuote(BackpackItem item, boolean special) {
    long price = item.item().price();
    if (price <= 0 || item.duraMax() <= item.dura()) return -1;
    if (item.duraMax() <= 0) return (int) Math.min(price, Integer.MAX_VALUE);
    int quote = (int) Math.rint(
        (price / 3) / (double) item.duraMax() * (item.duraMax() - item.dura()));
    return special ? quote * SUPER_REPAIR_PRICE_RATE : quote;
  }

  /**
   * The charged price. The special repair tripled {@code nPrice} *before* the {@code div 3},
   * so it cancels out and lands on the un-truncated base — the quote and the charge genuinely
   * disagree whenever {@code price mod 3 != 0}. Both formulas are reproduced on purpose:
   * {@code repairQuote} triples afterwards, this one divides the tripled price.
   */
  private static int repairCharge(long pricedForMode, int duraMax, int dura) {
    if (duraMax <= 0) return (int) Math.min(pricedForMode, Integer.MAX_VALUE);
    // Delphi: Round(nPrice div 3 / DuraMax * (DuraMax - Dura)); Round is banker's rounding.
    return (int) Math.rint((pricedForMode / 3) / (double) duraMax * (duraMax - dura));
  }

  /**
   * Places an item on the map without a monster having dropped it — the engine-side of
   * {@code TMapItem} creation used by tests and, later, by NPC and quest scripts.
   */
  public CompletableFuture<GroundItem> spawnGroundItem(
      String itemName, int looks, String mapId, Position position) {
    Objects.requireNonNull(itemName, "itemName");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    return submit(() -> {
      GameMap map = requireMap(mapId);
      int itemId = allocateObjectId();
      int resolvedLooks = itemDatabase.find(itemName).map(StdItem::looks).orElse(looks);
      GroundItem item = new GroundItem(itemId, itemName, resolvedLooks, map.id(), position);
      groundItems.put(itemId, item);
      itemDropTimes.put(itemId, clock.getAsLong());
      WorldEvent appeared = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(map, position, 0)) emit(players.get(viewerId), appeared);
      return item;
    });
  }

  /** Places a wallet-gold pile on the map; mainly used by protocol tests and future scripts. */
  public CompletableFuture<GroundItem> spawnGroundGold(int amount, String mapId, Position position) {
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    if (amount < 1) throw new IllegalArgumentException("gold amount must be positive");
    return submit(() -> {
      GameMap map = requireMap(mapId);
      GroundItem item = putGoldPile(map, position, amount);
      WorldEvent appeared = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(map, item.position(), 0)) emit(players.get(viewerId), appeared);
      return item;
    });
  }

  /** Current worn set, used by the adapter to answer with {@code SM_SENDUSEITEMS}. */
  public CompletableFuture<Equipment> equipment(int playerId) {
    return submit(() -> requirePlayer(playerId).equipment);
  }

  public CompletableFuture<Boolean> pickUp(int playerId, Position claimedPosition) {
    Objects.requireNonNull(claimedPosition, "claimedPosition");
    return submit(() -> pickUpItem(playerId, claimedPosition));
  }

  /**
   * Opens the door at {@code claimed} if its anchor matches {@code claimed} and it is currently
   * closed, broadcasting {@code SM_OPENDOOR_OK} to observers inside +/-12 cells (UsrEngn.OpenDoor).
   * Like Delphi, the server returns no direct acknowledgment packet to the opener.
   */
  public CompletableFuture<Boolean> openDoor(int playerId, Position claimed) {
    Objects.requireNonNull(claimed, "claimed");
    return submit(() -> openDoorAt(playerId, claimed));
  }

  /**
   * Installs an inter-map connection point, sourced from a {@code MapInfo.txt} route line.
   * Silently ignored when either map is not registered in this world engine instance,
   * mirroring {@code TMapManager.AddMapRoute}.
   */
  public CompletableFuture<Boolean> addRoute(TeleportRoute route) {
    Objects.requireNonNull(route, "route");
    return submit(() -> {
      GameMap source = maps.get(route.sourceMapId());
      GameMap destination = maps.get(route.destinationMapId());
      if (source == null || destination == null) return false;
      source.addGate(route);
      return true;
    });
  }

  /**
   * Installs the 禁止取下物品列表 loaded from {@code DisableTakeOffList.txt}
   * ({@code LoadDisableTakeOffList}, M2Share.pas:4578). Listed items can be neither taken off nor
   * dropped on death. Runs on the world thread so the list is never swapped mid-tick.
   */
  public CompletableFuture<Void> setDisableTakeOffList(DisableTakeOffList list) {
    Objects.requireNonNull(list, "list");
    return submit(() -> {
      disableTakeOffList = list;
      return null;
    });
  }

  /**
   * Brings a dead player back on the spot with full HP, mirroring the GM command
   * {@code CmdReAlive} (ObjBase.pas:13998). Returns false when the player was already alive.
   */
  public CompletableFuture<Boolean> revive(int playerId) {
    return submit(() -> revivePlayer(playerId));
  }

  /**
   * {@code TPlayObject.CmdChangeLevel} (ObjBase.pas:10848), the GM {@code @Level} command:
   * {@code m_Abil.Level := _MIN(MAXUPLEVEL, nLevel); HasLevelUp(1)}. The level is set outright,
   * the curve is rebuilt and the client receives the same {@code SM_LEVELUP} it would get from
   * an ordinary level-up.
   */
  public CompletableFuture<Integer> setLevel(int playerId, int level) {
    if (level < 1) throw new IllegalArgumentException("level must be at least one");
    return submit(() -> {
      Player player = requirePlayer(playerId);
      int capped = Math.min(LevelExperience.MAX_UP_LEVEL, level);
      Ability naked = player.baseAbility;
      Ability working = player.ability;
      EquipmentBonus bonusBefore = player.bonus;
      Ability relevelled = new Ability(naked.hp(), naked.maxHp(), naked.mp(), naked.maxMp(),
          naked.minDc(), naked.maxDc(), naked.minAc(), naked.maxAc(), capped,
          naked.experience(), LevelExperience.forLevel(capped));
      applyLevelUp(player, relevelled);
      try {
        persist(player);
      } catch (RuntimeException failure) {
        player.baseAbility = naked;
        player.ability = working;
        player.bonus = bonusBefore;
        throw failure;
      }
      announceLevelUp(player, player.ability);
      return capped;
    });
  }

  /**
   * {@code TPlayObject.CmdIncPkPoint} (ObjBase.pas:13211), the GM {@code @IncPkPoint} command:
   * {@code Inc(m_nPkPoint, nPoint); RefNameColor()}. Delphi always repaints, even when the
   * derived level did not move, so that is reproduced here. The counter is clamped at zero —
   * {@code DecPKPoint} does the same on its own path.
   */
  public CompletableFuture<Integer> addPkPoint(int playerId, int points) {
    return submit(() -> {
      Player player = requirePlayer(playerId);
      player.pkPoint = Math.max(0, player.pkPoint + points);
      broadcastNameColor(player);
      persist(player);
      return player.pkPoint;
    });
  }

  /** {@code TPlayObject.CmdPKPoint} (ObjBase.pas:13966), the GM {@code @PKPoint} query. */
  public CompletableFuture<Integer> pkPoint(int playerId) {
    return submit(() -> requirePlayer(playerId).pkPoint);
  }

  public CompletableFuture<Void> leavePlayer(int playerId) {
    return submit(() -> {
      leave(playerId);
      return null;
    });
  }

  /**
   * {@code CM_SOFTCLOSE} (ObjBase.pas:4751): the client's 退出到选人 button asks to leave
   * the world without a server acknowledgement — Delphi only raises {@code m_boSoftClose}
   * and the object turns into a ghost on its next {@code Operate} tick
   * (ObjBase.pas:6573). The socket is deliberately left open for the client to close
   * itself (~2s later); unlike a hard {@link #leavePlayer} this is idempotent, because
   * the gate's connection-teardown path will ask again once the client actually
   * disconnects.
   */
  public CompletableFuture<Void> softClose(int playerId) {
    return submit(() -> {
      if (players.containsKey(playerId)) leave(playerId);
      return null;
    });
  }

  /** {@code CM_GROUPMODE} (ObjBase.pas:4777). */
  public CompletableFuture<Boolean> setAllowGroup(int playerId, boolean allow) {
    return submit(() -> changeGroupMode(playerId, allow));
  }

  /** {@code CM_CREATEGROUP} (ObjBase.pas:17542). */
  public CompletableFuture<Boolean> createGroup(int playerId, String targetName) {
    Objects.requireNonNull(targetName, "targetName");
    return submit(() -> createPlayerGroup(playerId, targetName));
  }

  /** {@code CM_ADDGROUPMEMBER} (ObjBase.pas:17579). */
  public CompletableFuture<Boolean> addGroupMember(int playerId, String targetName) {
    Objects.requireNonNull(targetName, "targetName");
    return submit(() -> addPlayerGroupMember(playerId, targetName));
  }

  /** {@code CM_DELGROUPMEMBER} (ObjBase.pas:17620). */
  public CompletableFuture<Boolean> delGroupMember(int playerId, String targetName) {
    Objects.requireNonNull(targetName, "targetName");
    return submit(() -> delPlayerGroupMember(playerId, targetName));
  }

  public CompletableFuture<List<String>> groupMembers(int playerId) {
    return submit(() -> {
      Player p = players.get(playerId);
      if (p == null || p.group == null) return List.of();
      return p.group.memberIds().stream()
          .map(players::get)
          .filter(Objects::nonNull)
          .map(m -> m.name)
          .toList();
    });
  }

  public CompletableFuture<Boolean> isGroupLeader(int playerId) {
    return submit(() -> {
      Player p = players.get(playerId);
      return p != null && p.group != null && p.group.isLeader(playerId);
    });
  }

  public CompletableFuture<Boolean> allowGroup(int playerId) {
    return submit(() -> {
      Player p = players.get(playerId);
      return p != null && p.allowGroup;
    });
  }

  /**
   * Result of a {@code CM_QUERYUSERNAME}: either the actor's show name plus its
   * {@code GetCharColor} palette byte, or a ghost marker when the client asked about a
   * cell that no longer holds the actor.
   */
  public record UserNameQuery(int objectId, String name, int nameColor, boolean present) {
    public UserNameQuery {
      if (objectId <= 0) throw new IllegalArgumentException("object id must be positive");
      if (nameColor < 0 || nameColor > 0xFF) throw new IllegalArgumentException("name colour must be a byte");
    }

    static UserNameQuery ghost(int objectId) {
      return new UserNameQuery(objectId, "", 0, false);
    }
  }

  /**
   * {@code ClientQueryUserName} (ObjBase.pas:2638): answers {@code SM_USERNAME} when the
   * target stands within the 3×3 block around the cell the client quoted
   * ({@code CretInNearXY}, ObjBase.pas:16854), otherwise {@code SM_GHOST} so the client can
   * forget a stale actor. The palette byte is {@code GetCharColor}: white (255) for
   * monsters, NPCs and clean players, the PK colour model for players.
   */
  public CompletableFuture<UserNameQuery> queryUserName(int playerId, int targetId, int x, int y) {
    return submit(() -> {
      Player player = requirePlayer(playerId);
      WorldObject target = findObject(targetId);
      if (target == null || !target.map().id().equals(player.map.id())
          || Math.abs(target.position().x() - x) > 1 || Math.abs(target.position().y() - y) > 1) {
        return UserNameQuery.ghost(targetId);
      }
      int color = target instanceof Player queried
          ? PkLevel.nameColor(queried.pkPoint, queried.pkFlag)
          : 255;
      return new UserNameQuery(targetId, target.snapshot().name(), color, true);
    });
  }

  public CompletableFuture<WorldObjectSnapshot> snapshot(int objectId) {
    return submit(() -> requireObject(objectId).snapshot());
  }

  /** Returns the private durable state of an online player without exposing it to nearby observers. */
  public CompletableFuture<PlayerState> playerState(int playerId) {
    return submit(() -> requirePlayer(playerId).state());
  }

  public CompletableFuture<Integer> onlinePlayers() {
    return submit(players::size);
  }

  public CompletableFuture<Integer> liveMonsters() {
    return submit(() -> (int) monsters.values().stream().filter(monster -> monster.ability.alive()).count());
  }

  public CompletableFuture<List<GroundItem>> itemsAt(String mapId, Position position) {
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(position, "position");
    return submit(() -> itemsOn(mapId, position));
  }

  /**
   * Speaks a message from the specified player, mirroring {@code TPlayObject.ProcessUserLineMsg}.
   * Supports normal chat (12-cell sight broadcast), whisper (/target msg), shout (!msg),
   * and system queries (@who / /who).
   */
  public CompletableFuture<Boolean> say(int playerId, String message) {
    Objects.requireNonNull(message, "message");
    return submit(() -> processSay(playerId, message));
  }

  public CompletableFuture<Boolean> say(String characterName, String message) {
    Objects.requireNonNull(characterName, "characterName");
    Objects.requireNonNull(message, "message");
    return submit(() -> {
      Integer playerId = playersByName.get(characterName);
      if (playerId == null) return false;
      return processSay(playerId, message);
    });
  }

  public int gameTime() {
    return gameTime;
  }

  public int dayBright(GameMap map) {
    return calculateDayBright(map != null ? map.flags() : MapFlags.DEFAULT, this.gameTime);
  }

  public CompletableFuture<Void> setGameTime(int newGameTime) {
    return submit(() -> {
      updateGameTime(newGameTime);
      return null;
    });
  }

  /**
   * Maps an hour (0..23) to Delphi {@code g_nGameTime} (FrnEngn.pas:GetGameTime):
   * <ul>
   *   <li>5..10, 16..22: 1 (daytime)</li>
   *   <li>11, 23: 2 (twilight)</li>
   *   <li>4, 15: 0 (dawn / transition)</li>
   *   <li>0..3, 12..14: 3 (night)</li>
   * </ul>
   */
  public static int gameTimeFromHour(int hour) {
    int h = Math.floorMod(hour, 24);
    return switch (h) {
      case 5, 6, 7, 8, 9, 10, 16, 17, 18, 19, 20, 21, 22 -> 1;
      case 11, 23 -> 2;
      case 4, 15 -> 0;
      default -> 3; // 0, 1, 2, 3, 12, 13, 14
    };
  }

  /**
   * Delphi {@code TPlayObject.DayBright}:
   * 0 = Bright / Day, 1 = Dark / Night, 2 = Twilight.
   * Darkness map flag forces 1; DayLight map flag forces 0.
   */
  public static int calculateDayBright(MapFlags flags, int gameTime) {
    int bright;
    if (flags != null && flags.darkness()) {
      bright = 1;
    } else if (gameTime == 1) {
      bright = 0;
    } else if (gameTime == 3) {
      bright = 1;
    } else {
      bright = 2;
    }
    if (flags != null && flags.dayLight()) {
      bright = 0;
    }
    return bright;
  }

  /**
   * Executes one world tick on the current thread. The first caller becomes the permanent owner;
   * concurrent or cross-thread mutation is rejected.
   *
   * <p>A {@link WorldClock.Mode#VIRTUAL} clock is advanced <em>before</em> the tick body, so
   * everything inside this pass observes the same, already-incremented timestamp — the tick
   * index is the world's notion of "now". Delphi reads {@code GetTickCount} live inside the
   * pass; quantising to the tick boundary is the deliberate deviation virtual mode buys the
   * shadow harness (see {@link WorldClock}).
   */
  public void tickOnce() {
    if (closed.get()) throw new IllegalStateException("world engine is closed");
    claimOwnership();
    if (worldClock != null && worldClock.advancesWithEngineTick()) worldClock.advanceOneTick();
    for (int processed = 0; processed < config.maxCommandsPerTick(); processed++) {
      Pending<?> pending = commands.poll();
      if (pending == null) break;
      pending.execute();
    }
    // A MANUAL world's periodic half belongs to the pump alone. Running it here too would
    // still be time-frozen (and therefore mostly idempotent), but *when* it ran relative to
    // an inbound command would depend on the host's scheduler — so whether a monster's blow
    // landed in this op's observation bucket or the next one would become a race. Draining
    // commands stays unconditional: the client must keep being served between pumps.
    if (worldClock != null && worldClock.mode() == WorldClock.Mode.MANUAL) return;
    runTickBody();
  }

  /**
   * The periodic half of a tick: everything {@code TUserEngine.Run} does after the inbound
   * command queue is drained. Split out so {@link #advanceTicks(int)} can replay it once per
   * pumped tick without re-entering the command queue (it is already running inside a
   * command).
   */
  private void runTickBody() {
    resolvePendingMagicImpacts();
    expireSkillBuffs();
    tickPoison();
    tickFireWalls();
    regenSpawners();
    updateMonsters();
    decayPkPoints();
    regenerateHealthAndSpell();
    makeGhostsOfExpiredCorpses();
    expireGroundItems();
    closeDoorsPeriodically();
    savePlayersPeriodically();
    checkGameTime();
    tickCount.incrementAndGet();
  }

  private void checkGameTime() {
    int current = gameTimeFromHour(hourSupplier.getAsInt());
    if (current != this.gameTime) {
      updateGameTime(current);
    }
  }

  private void updateGameTime(int newGameTime) {
    if (this.gameTime != newGameTime) {
      this.gameTime = newGameTime;
      for (Player player : players.values()) {
        int bright = dayBright(player.map);
        emit(player, new WorldEvent.DayChanging(player.id, this.gameTime, bright));
      }
    }
  }

  private void scheduledTick() {
    try {
      tickOnce();
    } catch (Throwable error) {
      // Scheduled executors suppress all future invocations if a task lets an exception escape.
      LOG.log(Level.SEVERE, "world tick failed", error);
    }
  }

  private WorldObjectSnapshot enter(
      UUID characterId, String name, String mapId, Position position, Direction direction,
      int feature, int status, int job, WorldEventSink sink) {
    if (name.isBlank()) throw new IllegalArgumentException("player name must not be blank");
    if (playersByName.containsKey(name)) throw new IllegalStateException("player is already online: " + name);
    GameMap map = requireMap(mapId);
    if (map.flags().noReconnect() && !map.flags().noReconnectMap().isBlank()) {
      String redirectId = map.flags().noReconnectMap();
      if (maps.containsKey(redirectId)) {
        map = maps.get(redirectId);
        if (!map.canWalk(position)) {
          position = nearestAvailable(map, new Position(map.width() / 2, map.height() / 2));
        }
      }
    }
    if (!map.canWalk(position)) throw new IllegalStateException("spawn cell is not available: " + position);

    PlayerState restored = playerStateStore.load(characterId)
        .orElseGet(() -> PlayerState.initial(characterId));
    // W03 rows predate make indexes; stabilise them before the player becomes visible.
    restored = withStableMakeIndexes(restored);
    // Materialise defaults for characters created by an older schema before exposing the player.
    playerStateStore.save(restored);

    int id = allocateObjectId();
    List<Integer> visibleIds = visibleIds(map, position, 0);
    Player player = new Player(id, characterId, name, map, position, direction, feature, status,
        restored.ability(), restored.backpack(), restored.equipment(), job, sink);
    // m_sHomeMap: this engine's characters always re-enter on the configured spawn map, so the
    // login map *is* the home map that 瞬息移动's MapRandomMove targets (Magic.pas:962).
    player.homeMapId = map.id();
    for (PlayerSkill skill : restored.skills()) {
      // Unknown rows are retained in storage by SqliteStore but not exposed to a world whose
      // vetted Magic.DB intersection cannot resolve them.
      if (magicCatalog.find(skill.magicId()).isPresent()) player.skills.put(skill.magicId(), skill);
    }
    // UsrEngn.pas:2310 restores m_nGold from the character record (HumData.nGold).
    player.gold = restored.gold();
    // HumData.nPKPOINT (ObjBase.pas:24904) travels with the character record; m_boPKFlag does
    // not — it is a transient combat marker and always starts clear.
    player.pkPoint = restored.pkPoint();
    // UsrEngn.pas:2368 restores m_dBodyLuck from the record; the enter-map path then calls
    // AddBodyLuck(0) (ObjBase.pas:20110) purely to re-derive m_nBodyLuckLevel from it.
    player.bodyLuck = BodyLuck.ofAccumulator(restored.bodyLuck());
    // TBaseObject.Initialize (ObjBase.pas:1370) stamps the decay window at creation time.
    player.decPkPointTick = clock.getAsLong();
    // UserLogon's test-server block (ObjBase.pas:16360): under g_Config.boTestServer the
    // wallet is topped up to nTestGold (default 0, i.e. a no-op). Delphi does not notify the
    // client here; the notification is deferred until after MapEntered below so the client
    // has its actor before the wallet arrives.
    boolean goldFloored = player.gold < config.testGold();
    if (goldFloored) player.gold = config.testGold();
    // MaxExp and naked magical ranges are level-derived. W03-era rows have no MAC/MC/SC
    // columns, so rebuild those ranges on entry rather than treating migration zeroes as real.
    if (player.baseAbility.level() > 1) {
      player.baseAbility = LevelAbilities.forLevel(job, player.baseAbility.level(), player.baseAbility);
    } else {
      Ability base = player.baseAbility;
      Ability initial = Ability.defaultPlayer();
      player.baseAbility = new Ability(
          base.hp(), base.maxHp(), base.mp(), base.maxMp(),
          base.minDc(), base.maxDc(), base.minAc(), base.maxAc(),
          base.minMac(), base.maxMac(),
          base.minMc() == 0 && base.maxMc() == 0 ? initial.minMc() : base.minMc(),
          base.minMc() == 0 && base.maxMc() == 0 ? initial.maxMc() : base.maxMc(),
          base.minSc() == 0 && base.maxSc() == 0 ? initial.minSc() : base.minSc(),
          base.minSc() == 0 && base.maxSc() == 0 ? initial.maxSc() : base.maxSc(),
          base.level(), base.experience(), LevelExperience.forLevel(base.level()));
    }
    // RecalcAbilitys runs once at login so the restored gear is reflected before the client
    // receives its first ability packet. Current HP/MP are carried over untouched: Delphi
    // only refills them on revival, not on login.
    recalculateAbilities(player, false);
    // UsrEngn.pas:576-600 revives a character that was saved at zero HP before it re-enters
    // the world; the Delphi server relocates it home first, which the single-map PoC cannot
    // do, so the player simply stands up on the restored cell with the classic 14 HP.
    if (!player.ability.alive()) {
      player.ability = player.ability.withHp(Math.min(REVIVE_ON_LOGIN_HP, player.ability.maxHp()));
      player.baseAbility = player.rebase(player.ability);
    }
    // The enter itself just saved; the periodic pass starts counting from now.
    player.lastSavedAt = clock.getAsLong();
    map.place(id, position);
    players.put(id, player);
    playersByName.put(name, id);

    List<WorldObjectSnapshot> visible = visibleIds.stream()
        .map(this::findObject)
        .filter(Objects::nonNull)
        .map(WorldObject::snapshot)
        .toList();
    emit(player, new WorldEvent.MapEntered(
        player.snapshot(), map.info(), visible, visibleItems(map, position), dayBright(map)));
    // SearchViewRange's first sweep (ObjBase.pas:25764-25774) reports the fire walls already
    // burning in view as SM_SHOWEVENT frames right after the map bootstrap.
    for (FireWallEvent fire : visibleFireWalls(map, position)) {
      emit(player, new WorldEvent.EventAppeared(fire.id(), ET_FIRE, fire.position(), 0));
    }
    // TPlayObject login sequence (ObjBase.pas:16572) sends RM_SENDUSEITEMS so the client
    // knows what the character is wearing. RM_WEIGHTCHANGED is not part of that sequence —
    // the Delphi login path only refreshes weight when something actually changes it.
    if (!player.equipment.isEmpty()) {
      emit(player, new WorldEvent.EquipmentSent(player.id, player.equipment));
    }
    if (!player.skills.isEmpty()) {
      emit(player, new WorldEvent.SkillsSent(player.id, learnedMagics(player)));
    }
    // RM_ABILITY is part of the login refresh in the Delphi server. Sending the complete
    // packed ability immediately after the map bootstrap prevents a fresh client from
    // retaining placeholder HP/MP/level values until its first later mutation.
    emit(player, new WorldEvent.AbilityChanged(
        player.id, player.ability, player.gold, player.job, player.weights()));
    emitSubAbility(player);
    WorldEvent appeared = new WorldEvent.ObjectAppeared(player.snapshot());
    for (int viewerId : visibleIds) emit(players.get(viewerId), appeared);
    // Login re-enables 刺杀剑术 for a character that already knows it (ObjBase.pas:16602): the flag
    // is set and a bare +LNG tag is sent with no SysMsg hint. 半月弯刀 is deliberately NOT
    // re-enabled on login — only ReadBook turns it on — so the two shapes diverge here on purpose.
    if (player.skills.containsKey(HitSpeed.SKILL_ERGUM) && !player.useThrusting) {
      player.useThrusting = true;
      emit(player, new WorldEvent.WeaponSkillToggled(player.id, HitSpeed.SKILL_ERGUM, true));
    }
    // The test-gold floor is announced once the client can actually see itself.
    if (goldFloored) emit(player, new WorldEvent.GoldChanged(player.id, player.gold));
    return player.snapshot();
  }

  private WorldObjectSnapshot spawn(
      MonsterTemplate template, String mapId, Position position, Direction direction) {
    GameMap map = requireMap(mapId);
    if (!map.canWalk(position)) throw new IllegalStateException("spawn cell is not available: " + position);
    int id = allocateObjectId();
    // UsrEngn.pas:1950 (right after MonInitialize): `if Random(100) < Cert.m_btCoolEye then
    // Cert.m_boCoolEye := True`. A zero column never draws, so spawning the first-ten templates
    // (chicken/deer/... all carry CoolEye = 0 except 半兽勇士/洞蛆 = 1) leaves every existing
    // deterministic vector untouched.
    boolean coolEye = template.coolEyePercent() > 0
        && random.nextInt(WorldRandom.Stream.COOL_EYE, 100) < template.coolEyePercent();
    Monster monster = new Monster(id, template, map, position, direction, clock.getAsLong(), coolEye);
    map.place(id, position);
    monsters.put(id, monster);
    WorldEvent appeared = new WorldEvent.ObjectAppeared(monster.snapshot());
    for (int viewerId : visibleIds(map, position, id)) emit(players.get(viewerId), appeared);
    return monster.snapshot();
  }

  private MoveResult movePlayer(
      int playerId, Position target, Direction direction, MovementKind movement) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) return rejectMove(player, target, WorldEvent.MoveRejection.ACTOR_DEAD);
    Position source = player.position;
    Position expected = source.translate(direction, movement.steps());
    if (!expected.equals(target))
      return rejectMove(player, target, WorldEvent.MoveRejection.INVALID_TARGET);

    for (int step = 1; step <= movement.steps(); step++) {
      Position candidate = source.translate(direction, step);
      if (!player.map.contains(candidate))
        return rejectMove(player, target, WorldEvent.MoveRejection.OUT_OF_BOUNDS);
      if (!player.map.isTerrainWalkable(candidate))
        return rejectMove(player, target, WorldEvent.MoveRejection.BLOCKED_TERRAIN);
      if (player.map.objectAt(candidate) != 0)
        return rejectMove(player, target, WorldEvent.MoveRejection.OCCUPIED);
    }

    // TBaseObject.Walk fires map gates after the move lands; RunTo ends in the same
    // Walk(RM_RUN) call, so walk and run trigger connection points identically.
    TeleportRoute gate = player.map.routeAt(target);
    if (gate != null && player.map.aroundDoorOpened(target)) {
      // EnterAnotherMap refuses an unwalkable destination and WalkTo rolls the whole move
      // back instead of leaving the player standing on the gate cell.
      GameMap destination = maps.get(gate.destinationMapId());
      if (destination == null || !destination.canWalk(gate.destination())) {
        return rejectMove(player, target, WorldEvent.MoveRejection.GATE_TARGET_UNPASSABLE);
      }
      return teleportPlayer(player, source, direction, movement, gate, destination);
    }

    Set<Integer> visibleBefore = new LinkedHashSet<>(visibleIds(player.map, source, player.id));
    player.map.move(player.id, source, target);
    player.position = target;
    player.direction = direction;
    // ObjBase.pas:2061 (walk), 8947 (run) and 9560 (horse run): a successful step rewrites the
    // transparent slot to 1 second, so the cloak lapses on the next whole-second countdown tick
    // rather than the instant the step lands. Every further step refreshes that same second, so a
    // character who keeps walking stays cloaked (the counter never reaches zero in between).
    if (player.transparent && player.hideMode) {
      player.transparentUntil = clock.getAsLong() + TRANSPARENT_MOVE_REVEAL_MILLIS;
    }
    Set<Integer> visibleAfter = new LinkedHashSet<>(visibleIds(player.map, target, player.id));
    WorldObjectSnapshot movedPlayer = player.snapshot();

    emit(player, new WorldEvent.MoveAccepted(movedPlayer, source, movement));
    emitOwnVisibilityChanges(player, visibleBefore, visibleAfter);
    emitMovementToObservers(movedPlayer, source, movement, visibleBefore, visibleAfter);
    // TBaseObject.Walk's event scan (ObjBase.pas:20204-20229) runs as soon as the step lands:
    // stepping onto a fire wall cell hurts immediately, before the view sweep below reports
    // any event change (Delphi queues the RM_MAGSTRUCK_MINE and the next SearchViewRange
    // delivers SM_SHOWEVENT/SM_HIDEEVENT later still).
    struckByFireWallOnStep(player);
    emitItemVisibilityChanges(player, source, target);
    emitFireWallVisibilityChanges(player, source, target);
    return MoveResult.accepted(movedPlayer);
  }

  private boolean turnPlayer(int playerId, Position claimedPosition, Direction direction) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.TurnRejected(player.id, WorldEvent.TurnRejection.ACTOR_DEAD));
      return false;
    }
    if (!player.position.equals(claimedPosition)) {
      emit(player, new WorldEvent.TurnRejected(player.id, WorldEvent.TurnRejection.POSITION_MISMATCH));
      return false;
    }
    player.direction = direction;
    WorldObjectSnapshot snapshot = player.snapshot();
    emit(player, new WorldEvent.TurnAccepted(snapshot));
    WorldEvent turned = new WorldEvent.ObjectTurned(snapshot);
    for (int viewerId : visibleIds(player.map, player.position, player.id)) emit(players.get(viewerId), turned);
    return true;
  }

  private boolean changePlayerMagicKey(int playerId, int magicId, int key) {
    Player player = requirePlayer(playerId);
    PlayerSkill current = player.skills.get(magicId);
    if (current == null) return false;
    player.skills.put(magicId, current.withKey(key));
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.skills.put(magicId, current);
      throw failure;
    }
    return true;
  }

  /**
   * W28 minimum skill framework: fireball, healing and magic-shield lifecycle.
   * W32 added 大火球（{@code SKILL_FIREBALL2}, Magic.pas:280 shares the exact
   * {@code SKILL_FIREBALL} case branch verbatim）and 雷电术（{@code SKILL_LIGHTENING},
   * Magic.pas:392 — same single-target bolt shape, no adjacency-to-caster gate, and a
   * {@code LA_UNDEAD} 1.5x multiplier resolved against the live target at cast time）.
   * W38 added the two line-piercing bolts 地狱火（{@code SKILL_FIRE}, Magic.pas:387）and
   * 疾光电影（{@code SKILL_SHOOTLIGHTEN}, Magic.pas:397）via {@code MagPassThroughMagic}
   * (ObjBase.pas:2536) — the engine's first no-target, ground-click wizard skills.
   */
  private boolean castPlayerSpell(int playerId, int magicId, Position requestedTarget, int targetId) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive())
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.ACTOR_DEAD, "死亡状态无法施法");

    PlayerSkill skill = player.skills.get(magicId);
    if (skill == null)
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.UNKNOWN_SKILL, "尚未学习该技能");

    // ClientSpellXY (ObjBase.pas:9027): 基本剑术/精神力战法/攻杀剑术 share one case branch whose
    // whole body is `Result := True`. They are passive modifiers folded into RecalcHitSpeed, so
    // "casting" one only acknowledges the key press. MagicManager.DoSpell refuses them outright
    // (IsWarrSkill, Magic.pas:211), and because the same predicate also suppresses
    // CheckActionStatus and the m_dwMagicAttackInterval gate, the press costs no mana and is
    // never rejected as TOO_FAST. Delphi still stamps m_dwMagicAttackTick, which is modelled by
    // moving lastSpellAt — the three rows carry Delay = 0 in Magic.DB, so the next real spell is
    // only held for the shared 1350 ms interval.
    if (HitSpeed.isImplementedWarriorSkill(magicId)) {
      player.lastSpellAt = clock.getAsLong();
      emit(player, new WorldEvent.SpellAccepted(player.id, magicId));
      return true;
    }
    // 刺杀剑术/半月弯刀 (ObjBase.pas:9037-9073): the ClientSpellXY branch toggles an active
    // shape flag and echoes a +LNG/+WID tag before the caller sends +GOOD. IsWarrSkill still
    // suppresses the mana/cooldown gates, so the toggle costs nothing and never fails.
    if (HitSpeed.isToggledWeaponSkill(magicId)) return toggleWeaponSkill(player, magicId);

    MagicDefinition magic = magicCatalog.find(magicId).orElse(null);
    if (HitSpeed.isFireSwordSkill(magicId) && magic != null) return armFireSword(player, skill, magic);
    if (HitSpeed.isMotaeboSkill(magicId) && magic != null)
      return performMotaebo(player, skill, magic, requestedTarget, targetId);
    if (HitSpeed.isWarriorSkill(magicId))
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.UNSUPPORTED_SKILL, "该技能尚未开放");

    if (magic == null)
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.UNKNOWN_SKILL, "尚未学习该技能");

    if (magic.job() != MagicDefinition.ANY_JOB && magic.job() != player.job)
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.WRONG_JOB, "当前职业无法使用该技能");
    if (player.ability.level() < magic.requiredLevel(skill.level()))
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.LEVEL_TOO_LOW, "等级不足，无法使用该技能");
    if (!isDamageBolt(magicId) && !isLinePiercingSkill(magicId) && !isAreaHealingSkill(magicId)
        && !isAreaExplosionSkill(magicId) && !isElecBlizzardSkill(magicId)
        && !isPushArroundSkill(magicId) && !isDefenceBuffSkill(magicId)
        && !isCloakSkill(magicId)
        && !isShowHpSkill(magicId) && !isTurnUndeadSkill(magicId)
        && !isFireWallSkill(magicId) && !isSpaceMoveSkill(magicId)
        && magicId != SKILL_HEALING && magicId != SKILL_MAGIC_SHIELD)
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.UNSUPPORTED_SKILL, "该技能尚未开放");

    Position target = magicId == SKILL_MAGIC_SHIELD ? player.position : requestedTarget;
    if (chebyshev(player.position, target) > MAGIC_ATTACK_RANGE)
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.OUT_OF_RANGE, "施法距离过远");

    WorldObject targetObject;
    if (magicId == SKILL_MAGIC_SHIELD) {
      targetObject = player;
      targetId = player.id;
      if (player.magicShieldUntil > clock.getAsLong()) {
        return rejectSpell(player, magicId, WorldEvent.SpellRejection.BUFF_ALREADY_ACTIVE,
            "魔法盾效果仍在持续");
      }
    } else if (magicId == SKILL_HEALING && targetId == 0) {
      targetObject = player;
      targetId = player.id;
      target = player.position;
    } else {
      targetObject = findObject(targetId);
    }
    // 地狱火/疾光电影 need no target object at all (Magic.pas:387/397 only read the click
    // coordinates): the client may cast on empty ground with targetId = 0, so the shared
    // single-target gate is skipped and the beam itself filters objects on its cells.
    // 群体治愈术 shares the ground-click shape: MagBigHealing (Magic.pas:172) only reads
    // nTargetX/nTargetY, so a click on empty ground with targetId = 0 is a legal cast.
    // 地狱雷光 likewise reads neither TargeTBaseObject nor the click cell: MagElecBlizzard
    // (Magic.pas:1191) only takes the caster's own coordinates.
    // 抗拒火环 reads neither of them either — MagPushArround (Magic.pas:146) walks the caster's
    // own m_VisibleActors and centres each candidate comparison on the caster's cell — so an
    // empty-ground click with targetId = 0 is a legal cast here too.
    // 幽灵盾/神圣战甲术 (Magic.pas:451/456) only read the click coordinates as the centre of
    // MagMakeDefenceArea's square; TargeTBaseObject is never touched, so — as with 群体治愈术 —
    // an empty-ground click with targetId = 0 is a legal cast.
    // 隐身术 casts on the caster's own cell and 集体隐身术 on the group's cell; neither branch
    // reads TargeTBaseObject either (Magic.pas:476/481), so both accept an empty-ground click.
    // 火墙 (Magic.pas:501) likewise reads only the click coordinates — MagMakeFireCross never
    // touches TargeTBaseObject — so an empty-ground click with targetId = 0 is a legal cast.
    // 瞬息移动 (Magic.pas:495) reads neither: MagSaceMove only takes the caster, and the click
    // rides along on the RM_MAGICFIRE frame the branch broadcasts itself.
    if (!isLinePiercingSkill(magicId) && !isAreaHealingSkill(magicId)
        && !isAreaExplosionSkill(magicId) && !isElecBlizzardSkill(magicId)
        && !isPushArroundSkill(magicId) && !isDefenceBuffSkill(magicId)
        && !isCloakSkill(magicId) && !isFireWallSkill(magicId) && !isSpaceMoveSkill(magicId)
        && !validSpellTarget(player, targetObject, target, magicId))
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.INVALID_TARGET, "施法目标无效");

    long now = clock.getAsLong();
    if (now - player.lastSpellAt < MAGIC_HIT_INTERVAL_MILLIS + magic.delayMillis())
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.TOO_FAST, "技能冷却中");
    int mana = magic.manaCost(skill.level());
    if (player.ability.mp() < mana)
      return rejectSpell(player, magicId, WorldEvent.SpellRejection.NOT_ENOUGH_MANA, "魔法值不足");

    Ability before = player.ability;
    player.lastSpellAt = now;
    if (!player.position.equals(target)) player.direction = Direction.toward(player.position, target);
    player.setAbility(player.ability.withMp(player.ability.mp() - mana));
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.setAbility(before);
      player.lastSpellAt = Long.MIN_VALUE / 4;
      throw failure;
    }

    emit(player, new WorldEvent.SpellAccepted(player.id, magic.id()));
    emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
    WorldEvent cast = new WorldEvent.ObjectSpellCast(player.snapshot(), target, magic);
    for (int viewerId : visibleIds(player.map, player.position, player.id)) emit(players.get(viewerId), cast);

    // Magic.pas:415-497: the whole 灵魂火符..集体隐身术 case block gates RM_MAGICFIRE behind a
    // spent 护身符 charge — the RM_SPELL cast pose above already went out unconditionally, but a
    // caster with no charm never gets the projectile broadcast at all (see
    // castAmuletGatedSpell). W46 brings 幽灵盾/神圣战甲术 (the first two rows of that block that
    // need no summon, transparency or trap subsystem) onto the same gate.
    if (magicId == SKILL_FIRECHARM || magicId == SKILL_AMYOUNSUL || isDefenceBuffSkill(magicId)
        || isCloakSkill(magicId)) {
      castAmuletGatedSpell(player, magicId, skill, magic, target, targetId, targetObject, now);
      return true;
    }

    // Magic.pas:387/397: the two line-piercing bolts emit their own RM_MAGICFIRE with the beam
    // end Delphi wrote back into nTargetX/nTargetY (see castLinePiercingSpell).
    if (isLinePiercingSkill(magicId)) {
      castLinePiercingSpell(player, skill, magic, target, targetId, now);
      return true;
    }

    // Magic.pas:532 — 群体治愈术 heals a square of friends instead of a single target, so it
    // leaves the single-target resist/impact chain below entirely (see castAreaHealing).
    if (isAreaHealingSkill(magicId)) {
      castAreaHealing(player, skill, magic, target, targetId, now);
      return true;
    }

    // Magic.pas:510/578 — 爆裂火焰 and 冰咆哮 are ground-target area attacks that share one
    // MagBigExplosion body. Their one GetAttackPower roll is shared by every proper target;
    // RM_MAGSTRUCK has no anti-magic-resist roll and is delivered to the bound object rather
    // than re-checking the explosion cell on arrival. Only the configured square radius differs.
    if (isAreaExplosionSkill(magicId)) {
      if (magicId == SKILL_SNOWWIND) {
        castSnowWind(player, skill, magic, target, targetId, now);
      } else {
        castAreaExplosion(player, skill, magic, target, targetId, now);
      }
      return true;
    }

    // Magic.pas:518 — 地狱雷光 detonates on the caster's own cell. Its RM_MAGSTRUCK has no
    // delay, no SetTargetCreat and no anti-magic-resist roll (see castElecBlizzard).
    if (isElecBlizzardSkill(magicId)) {
      castElecBlizzard(player, skill, magic, target, targetId, now);
      return true;
    }

    // Magic.pas:384 — 抗拒火环 is a pure displacement branch: `if MagPushArround(PlayObject,
    // UserMagic.btLevel) > 0 then boTrain := True;`. It rolls no power, deals no damage and
    // never touches TargeTBaseObject; the click only rides along on the RM_MAGICFIRE frame
    // (see castPushArround).
    if (isPushArroundSkill(magicId)) {
      castPushArround(player, skill, magic, target, targetId);
      return true;
    }

    // Magic.pas:572 — 圣言术 is a synchronous single-target branch: no resist roll, no delayed
    // impact. The effect (aggro, possible fear freeze, possible instant kill) runs inline, and
    // RM_MAGICFIRE closes the cast — see castTurnUndead.
    if (isTurnUndeadSkill(magicId)) {
      castTurnUndead(player, skill, magic, target, targetId, targetObject);
      return true;
    }

    // Magic.pas:522 — 心灵启示 queues a single self-addressed RM_DOOPENHEALTH on the target and
    // trains when the reveal was accepted; RM_MAGICFIRE still fires unconditionally — see
    // castShowHp.
    if (isShowHpSkill(magicId)) {
      castShowHp(player, skill, magic, target, targetId, targetObject, now);
      return true;
    }

    // Magic.pas:501 — 火墙 lays the TFireBurnEvent cross on the clicked cell; the branch reads
    // neither TargeTBaseObject nor any target state, and RM_MAGICFIRE fires unconditionally at
    // DoSpell's tail even when the safe-zone gate refuses the cast — see castFireWall.
    if (isFireWallSkill(magicId)) {
      castFireWall(player, skill, magic, target, targetId, now);
      return true;
    }

    // Magic.pas:495 — 瞬息移动 teleports the caster itself: the branch broadcasts RM_MAGICFIRE
    // at its head (and clears boSpellFire so DoSpell's tail stays silent), then MagSaceMove
    // rolls the gate and MapRandomMove relocates the caster — see castSpaceMove.
    if (isSpaceMoveSkill(magicId)) {
      castSpaceMove(player, skill, magic, target, targetId);
      return true;
    }

    boolean resisted = (magicId == SKILL_FIREBALL || magicId == SKILL_FIREBALL2
        || magicId == SKILL_LIGHTENING) && !passesMagicResist(targetObject);
    emitToObserversAndSelf(player, new WorldEvent.MagicFired(
        player.id, target, resisted ? 0 : targetId, magic));
    if (resisted) return true;

    if (magicId == SKILL_FIREBALL || magicId == SKILL_FIREBALL2) {
      int power = rollFireballPower(player, skill, magic);
      pendingMagicImpacts.add(new PendingMagicImpact(
          now + FIREBALL_IMPACT_DELAY_MILLIS, MagicImpactKind.DAMAGE,
          player.id, targetId, target, power));
    } else if (magicId == SKILL_LIGHTENING) {
      int power = rollFireballPower(player, skill, magic);
      if (isUndead(targetObject))
        power = (int) Math.rint(power * LIGHTENING_UNDEAD_MULTIPLIER);
      pendingMagicImpacts.add(new PendingMagicImpact(
          now + FIREBALL_IMPACT_DELAY_MILLIS, MagicImpactKind.DAMAGE,
          player.id, targetId, target, power));
    } else if (magicId == SKILL_HEALING) {
      int power = rollHealingPower(player, skill, magic);
      pendingMagicImpacts.add(new PendingMagicImpact(
          now + HEAL_IMPACT_DELAY_MILLIS, MagicImpactKind.HEAL,
          player.id, targetId, target, power));
    } else {
      int seconds = rollMagicShieldSeconds(player, skill, magic);
      player.magicShieldLevel = skill.level();
      player.magicShieldUntil = now + Math.max(1, seconds) * 1_000L;
      emit(player, new WorldEvent.SystemMessage(player.id,
          "魔法盾已生效，持续" + Math.max(1, seconds) + "秒"));
    }
    return true;
  }

  /**
   * {@code ClientSpellXY}'s 刺杀剑术/半月弯刀 branch (ObjBase.pas:9037-9073): flip the shape's
   * toggle, emit the green {@code SysMsg} hint ({@code ThrustingOnOff}/{@code HalfMoonOnOff},
   * ObjBase.pas:9711/9723) and the raw {@code +LNG}/{@code +WID} tag frame, then return {@code
   * True} so the CM_SPELL handler still sends {@code +GOOD}. No mana, no cooldown, no target.
   */
  private boolean toggleWeaponSkill(Player player, int magicId) {
    player.lastSpellAt = clock.getAsLong();
    boolean on;
    String hint;
    if (magicId == HitSpeed.SKILL_ERGUM) {
      player.useThrusting = !player.useThrusting;
      on = player.useThrusting;
      hint = on ? "启用刺杀剑法" : "关闭刺杀剑法";
    } else {
      player.useHalfMoon = !player.useHalfMoon;
      on = player.useHalfMoon;
      hint = on ? "开启半月弯刀" : "关闭半月弯刀";
    }
    emit(player, new WorldEvent.SystemMessage(player.id, hint));
    emit(player, new WorldEvent.WeaponSkillToggled(player.id, magicId, on));
    emit(player, new WorldEvent.SpellAccepted(player.id, magicId));
    return true;
  }

  /**
   * {@code ClientSpellXY}'s 烈火剑法 branch (ObjBase.pas:9092) plus {@code AllowFireHitSkill}
   * (ObjBase.pas:9782):
   *
   * <pre>
   *   if m_MagicFireSwordSkill &lt;&gt; nil then
   *     if AllowFireHitSkill then begin            // &gt; 10s since the last arming
   *       nSpellPoint := GetSpellPoint(UserMagic); // Magic.DB id 26: wSpell 0 + btDefSpell 7
   *       if m_WAbil.MP &gt;= nSpellPoint then begin
   *         if nSpellPoint &gt; 0 then begin DamageSpell(nSpellPoint); HealthSpellChanged(); end;
   *         SendSocket(nil, '+FIR');
   *       end;
   *     end;
   *   Result := True;
   * </pre>
   *
   * <p>Two quirks are kept verbatim. {@code AllowFireHitSkill} sets {@code m_boFireHitSkill} and
   * restamps the tick <em>before</em> the mana test, so a broke character still arms the flag —
   * it just never learns about it, because the {@code +FIR} tag the client needs to switch to
   * {@code CM_FIREHIT} is inside the mana branch. And the whole case returns {@code True}
   * regardless, so a press during the 10 s cooldown is still answered {@code +GOOD} with only
   * the red 「召唤烈火精灵失败...」 hint to show for it. {@code IsWarrSkill} keeps the press out
   * of the mana/cooldown gates {@code DoSpell} would otherwise apply.
   */
  private boolean armFireSword(Player player, PlayerSkill skill, MagicDefinition magic) {
    player.lastSpellAt = clock.getAsLong();
    long now = clock.getAsLong();
    if (now - player.lastFireHitAt <= FIRE_SWORD_REARM_MILLIS) {
      emit(player, new WorldEvent.SystemMessage(player.id, "召唤烈火精灵失败..."));
      emit(player, new WorldEvent.SpellAccepted(player.id, magic.id()));
      return true;
    }
    player.lastFireHitAt = now;
    player.fireHitArmed = true;
    emit(player, new WorldEvent.SystemMessage(player.id, "召唤烈火精灵成功..."));
    int mana = magic.manaCost(skill.level());
    if (player.ability.mp() >= mana) {
      if (mana > 0) consumeSkillMana(player, magic.id());
      emit(player, new WorldEvent.WeaponSkillToggled(player.id, magic.id(), true));
    }
    emit(player, new WorldEvent.SpellAccepted(player.id, magic.id()));
    return true;
  }

  /**
   * {@code ClientSpellXY}'s 野蛮冲撞 branch (ObjBase.pas:9112-9145):
   * <pre>
   *   SKILL_MOOTEBO {27}:
   *     begin //野蛮冲撞
   *       Result := True;
   *       if (GetTickCount - m_dwDoMotaeboTick) > 3 * 1000 then
   *       begin
   *         m_dwDoMotaeboTick := GetTickCount();
   *         m_btDirection := nTargetX;
   *         nSpellPoint := GetSpellPoint(UserMagic);
   *         if m_WAbil.MP >= nSpellPoint then
   *         begin
   *           if nSpellPoint > 0 then
   *           begin
   *             DamageSpell(nSpellPoint);
   *             HealthSpellChanged();
   *           end;
   *           if DoMotaebo(m_btDirection, UserMagic.btLevel) then
   *           begin
   *             if UserMagic.btLevel < 3 then
   *             begin
   *               if UserMagic.MagicInfo.TrainLevel[UserMagic.btLevel] < m_Abil.Level then
   *               begin
   *                 TrainSkill(UserMagic, Random(3) + 1);
   *                 if not CheckMagicLevelup(UserMagic) then
   *                 begin
   *                   SendDelayMsg(Self, RM_MAGIC_LVEXP, 0, UserMagic.MagicInfo.wMagicId,
   *                     UserMagic.btLevel, UserMagic.nTranPoint, '', 1000);
   *                 end;
   *               end;
   *             end;
   *           end;
   *         end;
   *       end;
   *     end;
   * </pre>
   */
  private boolean performMotaebo(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget, int targetId) {
    long now = clock.getAsLong();
    player.lastSpellAt = now;
    emit(player, new WorldEvent.SpellAccepted(player.id, magic.id()));

    if (now - player.lastMotaeboAt <= MOTAEBO_INTERVAL_MILLIS) {
      return true;
    }
    player.lastMotaeboAt = now;

    Direction direction;
    if (requestedTarget.y() == 0 && requestedTarget.x() >= 0 && requestedTarget.x() <= 7) {
      direction = Direction.fromCode(requestedTarget.x());
    } else if (!requestedTarget.equals(player.position)) {
      try {
        direction = Direction.toward(player.position, requestedTarget);
      } catch (IllegalArgumentException e) {
        direction = player.direction;
      }
    } else {
      direction = player.direction;
    }
    player.direction = direction;

    int mana = magic.manaCost(skill.level());
    if (player.ability.mp() < mana) {
      return true;
    }
    if (mana > 0) {
      consumeSkillMana(player, magic.id());
    }

    if (doMotaebo(player, skill, magic, direction)) {
      trainMotaeboSkill(player, skill, magic);
    }
    return true;
  }

  private void trainMotaeboSkill(Player player, PlayerSkill current, MagicDefinition magic) {
    if (current.level() >= MagicDefinition.MAX_SKILL_LEVEL) return;
    if (player.ability.level() <= magic.requiredLevel(current.level())) return;
    int points = random.nextInt(WorldRandom.Stream.SKILL_TRAIN, 3) + 1;
    PlayerSkill trained = current.train(magic, player.ability.level(), points);
    if (trained.equals(current)) return;
    player.skills.put(magic.id(), trained);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.skills.put(magic.id(), current);
      throw failure;
    }
    emit(player, new WorldEvent.SkillTrainingChanged(
        player.id, new LearnedMagic(trained, magic)));
  }

  private boolean canMotaebo(Player player, WorldObject target, int magicLevel) {
    if (target == null || !target.ability().alive()) return false;
    if (target instanceof Npc) return false;
    if (player.ability.level() <= target.ability().level()) return false;
    if (player.map.isSafeZone(target.position())) return false;
    int nC = player.ability.level() - target.ability().level();
    int threshold = (magicLevel * 4) + 6 + nC;
    if (random.nextInt(WorldRandom.Stream.ACCURACY, 20) < threshold) {
      return isProperTarget(player, target);
    }
    return false;
  }

  private boolean isProperTarget(Player player, WorldObject target) {
    if (target == null || !target.ability().alive() || target.map() != player.map) return false;
    if (target.id() == player.id) return false;
    if (target instanceof Npc) return false;
    return true;
  }

  private int charPushed(WorldObject target, Direction direction) {
    Position nextPos = target.position().translate(direction, 1);
    if (!target.map().canWalk(nextPos)) {
      return 0;
    }
    Position oldPos = target.position();
    target.map().move(target.id(), oldPos, nextPos);
    Direction backDir = direction.opposite();
    if (target instanceof Player playerTarget) {
      playerTarget.position = nextPos;
      playerTarget.direction = backDir;
      emitObjectPushed(playerTarget, oldPos, backDir);
      try {
        persist(playerTarget);
      } catch (RuntimeException failure) {
        // Ignored or logged
      }
    } else if (target instanceof Monster monsterTarget) {
      monsterTarget.position = nextPos;
      monsterTarget.direction = backDir;
      monsterTarget.lastWalkAt = Math.max(monsterTarget.lastWalkAt, clock.getAsLong()) + 800;
      emitObjectPushed(monsterTarget, oldPos, backDir);
    }
    return 1;
  }

  private boolean doMotaebo(
      Player player, PlayerSkill skill, MagicDefinition magic, Direction direction) {
    int magicLevel = skill.level();
    int maxSteps = Math.max(2, magicLevel + 1) + 1;
    int n24 = magicLevel + 1;
    int n28 = n24;
    boolean bo35 = true;
    boolean result = false;
    WorldObject lastPushedTarget = null;

    Position initialFront = player.position.translate(direction, 1);
    WorldObject poseCreate = objectAt(player.map, initialFront);

    if (poseCreate != null) {
      for (int i = 0; i < maxSteps; i++) {
        Position currentFront = player.position.translate(direction, 1);
        WorldObject frontObj = objectAt(player.map, currentFront);
        if (frontObj == null) {
          break;
        }
        n28 = 0;
        if (!canMotaebo(player, frontObj, magicLevel)) {
          break;
        }
        if (magicLevel >= 3) {
          Position pos2 = player.position.translate(direction, 2);
          WorldObject secondObj = objectAt(player.map, pos2);
          if (secondObj != null && canMotaebo(player, secondObj, magicLevel)) {
            charPushed(secondObj, direction);
          }
        }
        lastPushedTarget = frontObj;
        if (charPushed(frontObj, direction) != 1) {
          break;
        }
        Position nextPos = player.position.translate(direction, 1);
        if (player.map.canWalk(nextPos)) {
          Position oldPos = player.position;
          player.map.move(player.id, oldPos, nextPos);
          player.position = nextPos;
          player.direction = direction;
          emitObjectRushed(player, oldPos, direction);
          emitItemVisibilityChanges(player, oldPos, nextPos);
          try {
            persist(player);
          } catch (RuntimeException failure) {
            // Ignored
          }
          bo35 = false;
          result = true;
        }
        n24--;
      }
    } else {
      bo35 = false;
      for (int i = 0; i < maxSteps; i++) {
        Position nextPos = player.position.translate(direction, 1);
        if (player.map.canWalk(nextPos)) {
          Position oldPos = player.position;
          player.map.move(player.id, oldPos, nextPos);
          player.position = nextPos;
          player.direction = direction;
          emitObjectRushed(player, oldPos, direction);
          emitItemVisibilityChanges(player, oldPos, nextPos);
          try {
            persist(player);
          } catch (RuntimeException failure) {
            // Ignored
          }
          result = true;
          n28--;
        } else {
          if (player.map.isTerrainWalkable(nextPos)) {
            n28 = 0;
          } else {
            bo35 = true;
          }
          break;
        }
      }
    }

    if (lastPushedTarget != null) {
      int clamped24 = Math.max(0, n24);
      int bound = (clamped24 + 1) * 10;
      int baseDmg = random.nextInt(WorldRandom.Stream.DAMAGE, bound) + bound;
      int def = randomBetween(lastPushedTarget.ability().minAc(), lastPushedTarget.ability().maxAc());
      int struckDmg = applyMagicShield(lastPushedTarget, Math.max(0, baseDmg - def));
      applyDamage(lastPushedTarget, player, struckDmg);
    }

    if (bo35) {
      Position front = player.position.translate(player.direction, 1);
      emitObjectRushFailed(player, front, player.direction);
      emit(player, new WorldEvent.SystemMessage(player.id, "冲撞力不够..."));
    }

    if (n28 > 0) {
      int clamped24 = Math.max(0, n24);
      int bound = clamped24 * 10;
      int rnd = bound > 0 ? random.nextInt(WorldRandom.Stream.DAMAGE, bound) : 0;
      int baseDmg = rnd + (clamped24 + 1) * 3;
      int def = randomBetween(player.ability.minAc(), player.ability.maxAc());
      int struckDmg = applyMagicShield(player, Math.max(0, baseDmg - def));
      applyDamage(player, player, struckDmg);
    }

    return result;
  }

  private void emitObjectRushed(WorldObject object, Position source, Direction direction) {
    Set<Integer> visibleBefore = new LinkedHashSet<>(visibleIds(object.map(), source, object.id()));
    Set<Integer> visibleAfter = new LinkedHashSet<>(visibleIds(object.map(), object.position(), object.id()));
    Set<Integer> observers = new LinkedHashSet<>(visibleBefore);
    observers.addAll(visibleAfter);
    for (int observerId : observers) {
      Player observer = players.get(observerId);
      if (observer == null) continue;
      if (visibleBefore.contains(observerId) && visibleAfter.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectRushed(object.snapshot(), source, direction));
      } else if (visibleBefore.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectDisappeared(object.id()));
      } else {
        emit(observer, new WorldEvent.ObjectAppeared(object.snapshot()));
      }
    }
    if (object instanceof Player player) {
      emit(player, new WorldEvent.ObjectRushed(player.snapshot(), source, direction));
    }
  }

  private void emitObjectPushed(WorldObject object, Position source, Direction direction) {
    Set<Integer> visibleBefore = new LinkedHashSet<>(visibleIds(object.map(), source, object.id()));
    Set<Integer> visibleAfter = new LinkedHashSet<>(visibleIds(object.map(), object.position(), object.id()));
    Set<Integer> observers = new LinkedHashSet<>(visibleBefore);
    observers.addAll(visibleAfter);
    for (int observerId : observers) {
      Player observer = players.get(observerId);
      if (observer == null) continue;
      if (visibleBefore.contains(observerId) && visibleAfter.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectPushed(object.snapshot(), source, direction));
      } else if (visibleBefore.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectDisappeared(object.id()));
      } else {
        emit(observer, new WorldEvent.ObjectAppeared(object.snapshot()));
      }
    }
    if (object instanceof Player player) {
      emit(player, new WorldEvent.ObjectPushed(player.snapshot(), source, direction));
      emitItemVisibilityChanges(player, source, player.position);
    }
  }

  private void emitObjectRushFailed(Player player, Position targetCell, Direction direction) {
    WorldEvent.ObjectRushFailed event =
        new WorldEvent.ObjectRushFailed(player.snapshot(), targetCell, direction);
    for (int viewerId : visibleIds(player.map, player.position, player.id)) {
      emit(players.get(viewerId), event);
    }
    emit(player, event);
  }

  private boolean validSpellTarget(
      Player caster, WorldObject target, Position claimed, int magicId) {
    if (target == null || !target.ability().alive() || target.map() != caster.map) return false;
    if (chebyshev(target.position(), claimed) > 1) return false;
    // 心灵启示 (Magic.pas:523) gates on `TargeTBaseObject <> nil` alone — no IsProperTarget, no
    // race check — and 圣言术's IsProperTarget (Magic.pas:573) accepts any proper hostile. Both
    // reduce to the same shape the damage bolts use: a live, non-self, non-NPC object; the
    // undead-only and not-already-revealed refinements live inside their case bodies instead.
    if (isDamageBolt(magicId) || isShowHpSkill(magicId) || isTurnUndeadSkill(magicId))
      return target.id() != caster.id && !(target instanceof Npc);
    return target instanceof Player;
  }

  /**
   * {@code SKILL_FIREBALL}/{@code SKILL_FIREBALL2}/{@code SKILL_LIGHTENING}/
   * {@code SKILL_FIRECHARM}/{@code SKILL_AMYOUNSUL}: every hostile single-target bolt, including
   * the two 护身符-gated skills — they still need a live, non-self, non-NPC target before the
   * amulet is even checked (Magic.pas's shared {@code IsProperTarget} gate).
   */
  private static boolean isDamageBolt(int magicId) {
    return magicId == SKILL_FIREBALL || magicId == SKILL_FIREBALL2 || magicId == SKILL_LIGHTENING
        || magicId == SKILL_FIRECHARM || magicId == SKILL_AMYOUNSUL;
  }

  /** {@code TargeTBaseObject.m_btLifeAttrib = LA_UNDEAD} (Monster.DB {@code Undead} column). */
  private static boolean isUndead(WorldObject target) {
    return target instanceof Monster monster && monster.template.undead();
  }

  private static int chebyshev(Position left, Position right) {
    return Math.max(Math.abs(left.x() - right.x()), Math.abs(left.y() - right.y()));
  }

  private boolean rejectSpell(
      Player player, int magicId, WorldEvent.SpellRejection reason, String message) {
    emit(player, new WorldEvent.SpellRejected(player.id, magicId, reason, message));
    return false;
  }

  private int rollFireballPower(Player player, PlayerSkill skill, MagicDefinition magic) {
    int base = getMagicPower(magic, skill.level(), rollExclusive(magic.power(), magic.maxPower()))
        + player.ability.minMc();
    return base + random.nextInt(WorldRandom.Stream.MAGIC,
        player.ability.maxMc() - player.ability.minMc() + 1);
  }

  /**
   * The {@code GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(m_WAbil.MC),
   * SmallInt(HiWord(m_WAbil.MC) - LoWord(m_WAbil.MC)) + 1)} call shared by 爆裂火焰
   * (Magic.pas:510) and 地狱雷光 (Magic.pas:519).
   */
  private int rollMcAttackPower(Player player, PlayerSkill skill, MagicDefinition magic) {
    int base = getMagicPower(magic, skill.level(), rollExclusive(magic.power(), magic.maxPower()))
        + player.ability.minMc();
    int spread = player.ability.maxMc() - player.ability.minMc() + 1;
    // GetAttackPower draws the inclusive 0..nPower range, so the source's +1 spread gives
    // HiMC-LoMC+2 possible offsets. Do not collapse this to the usual LoMC..HiMC fireball roll.
    return attackPower(base, base + spread, playerLuck(player));
  }

  private int rollHealingPower(Player player, PlayerSkill skill, MagicDefinition magic) {
    int base = getMagicPower(magic, skill.level(), rollExclusive(magic.power(), magic.maxPower()))
        + player.ability.minSc() * 2;
    return base + random.nextInt(WorldRandom.Stream.MAGIC,
        (player.ability.maxSc() - player.ability.minSc()) * 2 + 1);
  }

  /**
   * {@code GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(m_WAbil.SC), ...)}: 灵魂火符's
   * power roll (Magic.pas:439) is the fireball/healing shape with 道术 (SC) standing in for the
   * usual 魔法 (MC)/双倍 SC pairing — a single, un-doubled SC contribution.
   */
  private int rollFireCharmPower(Player player, PlayerSkill skill, MagicDefinition magic) {
    int base = getMagicPower(magic, skill.level(), rollExclusive(magic.power(), magic.maxPower()))
        + player.ability.minSc();
    return base + random.nextInt(WorldRandom.Stream.MAGIC,
        player.ability.maxSc() - player.ability.minSc() + 1);
  }

  /**
   * {@code GetPower13(40)}/{@code GetPower13(30)} plus {@code GetRPow(SC) * 2} (Magic.pas:335,
   * 340): 施毒术's power roll doubles as the poison's duration in seconds — a quirk kept
   * verbatim, see {@link #castAmyounsul}.
   */
  private int rollAmyounsulPower(Player player, PlayerSkill skill, MagicDefinition magic, int base) {
    int power = magic.scalePower13(base, skill.level())
        + rollExclusive(magic.defPower(), magic.defMaxPower());
    return power + random.between(WorldRandom.Stream.MAGIC,
        player.ability.minSc(), player.ability.maxSc()) * 2;
  }

  /** {@code CheckAmulet}'s result (Magic.pas:81): the slot and instance that will pay a charge. */
  private record AmuletCharge(EquipmentSlot slot, BackpackItem item) {
    /** The charm template's {@code Shape}: 5 for 护身符, 1/2 for 灰色/黄色药粉. */
    int shape() {
      return item.item().shape();
    }
  }

  /**
   * {@code CheckAmulet} (Magic.pas:81): scans {@code U_ARMRINGL} then {@code U_BUJUK} for a
   * {@code StdMode = 25} charm whose {@code Shape} matches {@code nType} (1 → 护身符 family,
   * Shape 5; 2 → 药粉 family, Shape &lt;= 2) and still has at least {@code nCount} charges —
   * {@code ROUND(Dura / 100) &gt;= nCount}, banker's rounding like every other Delphi
   * {@code Round}. The first eligible slot wins; charges are never combined across slots.
   */
  private Optional<AmuletCharge> findAmulet(Player player, int type, int count) {
    for (EquipmentSlot slot : List.of(EquipmentSlot.ARM_RING_LEFT, EquipmentSlot.CHARM_AMULET)) {
      BackpackItem worn = player.equipment.at(slot).orElse(null);
      if (worn == null || worn.item().stdMode() != AMULET_STD_MODE) continue;
      boolean shapeMatches = type == 1 ? worn.item().shape() == 5 : worn.item().shape() <= 2;
      if (!shapeMatches) continue;
      if (Math.rint(worn.dura() / (double) AMULET_CHARGE_UNITS) < count) continue;
      return Optional.of(new AmuletCharge(slot, worn));
    }
    return Optional.empty();
  }

  /**
   * {@code UseAmulet} (Magic.pas:134): spends {@code nCount} charges (100 durability apiece),
   * destroying the charm outright once it can no longer pay — {@link #damageEquipment} already
   * implements exactly this "drain to zero, then delete" shape for worn-item wear.
   */
  private void consumeAmulet(Player player, AmuletCharge amulet, int count) {
    damageEquipment(player, amulet.slot(), count * AMULET_CHARGE_UNITS);
  }

  /**
   * {@code SKILL_FIRECHARM}(13)/{@code SKILL_AMYOUNSUL}(6) — and, since W46,
   * {@code SKILL_HANGMAJINBUB}(14)/{@code SKILL_DEJIWONHO}(15) — share one 护身符 gate
   * (Magic.pas:415-497): {@code boSpellFail} starts {@code True} and only flips once
   * {@code CheckAmulet} finds a charge to spend. A caster with none never gets
   * {@code RM_MAGICFIRE} — the outer {@code ClientSpellXY} answers with {@code RM_MAGICFIREFAIL}
   * instead ({@link WorldEvent.SpellFizzled}) even though mana was already spent and the cast
   * pose already played.
   */
  private void castAmuletGatedSpell(Player player, int magicId, PlayerSkill skill,
      MagicDefinition magic, Position target, int targetId, WorldObject targetObject, long now) {
    // Magic.pas:359 asks CheckAmulet for nType = 2 (药粉) for 施毒术; the whole 13..19 block at
    // Magic.pas:428 — which now includes 幽灵盾/神圣战甲术 — asks for nType = 1 (护身符).
    int amuletType = magicId == SKILL_AMYOUNSUL ? 2 : 1;
    Optional<AmuletCharge> amulet = findAmulet(player, amuletType, 1);
    if (amulet.isEmpty()) {
      emit(player, new WorldEvent.SpellFizzled(player.id, magicId));
      return;
    }
    consumeAmulet(player, amulet.get(), 1);
    // 幽灵盾/神圣战甲术 apply their status immediately, and DoSpell broadcasts RM_MAGICFIRE only
    // after the case body returns (Magic.pas:716), so this branch owns the ordering itself: the
    // per-target hints and RM_ABILITY frames must reach the wire before the cast frame.
    if (isDefenceBuffSkill(magicId)) {
      castDefenceArea(player, skill, magic, target, targetId);
      return;
    }
    // 隐身术/集体隐身术 (Magic.pas:476/481) also run their case body before DoSpell's trailing
    // RM_MAGICFIRE, so the status frame (private) or the queued RM_TRANSPARENT deliveries (group)
    // keep Delphi's relative order to the cast frame. Both own their own broadcast below.
    if (magicId == SKILL_CLOAK) {
      castPrivateCloak(player, skill, magic, target, targetId);
      return;
    }
    if (magicId == SKILL_BIGCLOAK) {
      castGroupCloak(player, skill, magic, target, targetId);
      return;
    }
    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, target, targetId, magic));
    if (magicId == SKILL_FIRECHARM) {
      if (targetObject != null && passesMagicResist(targetObject)) {
        castFireCharm(player, skill, magic, target, targetId, now);
      }
    } else {
      castAmyounsul(player, skill, magic, amulet.get().shape(), target, targetId, targetObject, now);
    }
  }

  /**
   * {@code SKILL_FIRECHARM} (Magic.pas:436): once the charm is spent and the target has not
   * resisted through {@code m_nAntiMagic}, this is the same delayed single-target bolt shape as
   * fireball/lightning — adjacency-to-target and hostility were already enforced by {@link
   * #validSpellTarget} before the amulet check ran.
   */
  private void castFireCharm(
      Player player, PlayerSkill skill, MagicDefinition magic, Position target, int targetId, long now) {
    int power = rollFireCharmPower(player, skill, magic);
    pendingMagicImpacts.add(new PendingMagicImpact(
        now + FIRECHARM_IMPACT_DELAY_MILLIS, MagicImpactKind.DAMAGE,
        player.id, targetId, target, power));
  }

  /**
   * {@code SKILL_AMYOUNSUL} (Magic.pas:318): the charm's {@code Shape} selects which poison
   * lands — 1 (灰色药粉) is {@code POISON_DECHEALTH}, periodic HP drain; 2 (黄色药粉) is
   * {@code POISON_DAMAGEARMOR}, a flat incoming-damage multiplier. Both roll the same resist
   * gate first ({@code Random(m_btAntiPoison + 7) &lt;= 6}); on a resist, the charm is still
   * spent and {@code RM_MAGICFIRE} still fires (Delphi silently drops the {@code case} body),
   * so nothing distinguishes a resisted cast from one that simply missed on the wire.
   */
  private void castAmyounsul(Player player, PlayerSkill skill, MagicDefinition magic,
      int amuletShape, Position target, int targetId, WorldObject targetObject, long now) {
    if (targetObject == null || !passesPoisonResist(targetObject)) return;
    MagicImpactKind kind = amuletShape == 1
        ? MagicImpactKind.POISON_DECHEALTH : MagicImpactKind.POISON_DAMAGEARMOR;
    int base = amuletShape == 1 ? 40 : 30;
    int power = rollAmyounsulPower(player, skill, magic, base);
    int point = (int) Math.rint(skill.level() / 3.0 * (power / (double) AMYOUNSUL_POINT_DIVISOR));
    pendingMagicImpacts.add(new PendingMagicImpact(
        now + POISON_APPLY_DELAY_MILLIS, kind, player.id, targetId, target, power, point));
  }

  /**
   * {@code SKILL_HANGMAJINBUB}(14, 幽灵盾, Magic.pas:451) and {@code SKILL_DEJIWONHO}(15,
   * 神圣战甲术, Magic.pas:456): the two rows of the 护身符-gated 13..19 block that differ only in
   * the {@code btState} byte handed to {@code MagMakeDefenceArea} — 1 selects {@code
   * MagDefenceUp} ({@code STATE_MAGDEFENCEUP}, magic defence), 0 selects {@code DefenceUp}
   * ({@code STATE_DEFENCEUP}, physical defence).
   */
  private static boolean isDefenceBuffSkill(int magicId) {
    return magicId == SKILL_HANGMAJINBUB || magicId == SKILL_DEJIWONHO;
  }

  /**
   * The shared body of 幽灵盾/神圣战甲术:
   *
   * <pre>
   *   nPower := GetAttackPower(GetPower13(60) + LoWord(SC) * 10,
   *                            SmallInt(HiWord(SC) - LoWord(SC)) + 1);   // Magic.pas:452/457
   *   if MagMakeDefenceArea(nTargetX, nTargetY, 3, nPower, btState) &gt; 0 then boTrain := True;
   * </pre>
   *
   * <p>{@code MagMakeDefenceArea} (ObjBase.pas:24207) walks the inclusive
   * {@code (nX ± 3) x (nY ± 3)} square around the <em>clicked</em> cell — not around the caster —
   * and for every non-ghost moving object that {@code IsProperFriend} accepts calls
   * {@code DefenceUp}/{@code MagDefenceUp}, then {@code Inc(Result)} <em>outside</em> that call's
   * own return value. So a target whose remaining window already outlasts the new roll still
   * counts, and the skill trains as long as one friend was in the square.
   *
   * <p>Neither branch touches {@code TargeTBaseObject}: the click only supplies the square's
   * centre and rides along on the trailing RM_MAGICFIRE frame. {@code CretInNearXY}
   * (ObjBase.pas:16854) still snaps the click onto a referenced object within one cell, so the
   * square — and the broadcast — centre on that object's cell.
   */
  private void castDefenceArea(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId) {
    WorldObject clicked = targetId != 0 ? findObject(targetId) : null;
    boolean snapped = clicked != null && clicked.map() == player.map
        && chebyshev(clicked.position(), requestedTarget) <= 1;
    Position center = snapped ? clicked.position() : requestedTarget;
    int firedTargetId = snapped && clicked.ability().alive() ? clicked.id() : 0;

    boolean magical = magic.id() == SKILL_HANGMAJINBUB;
    // Rolled once per cast, before the square walk, exactly as MagBigExplosion does — an empty
    // square still pays for the roll and for the charm.
    int seconds = rollDefenceSeconds(player, skill, magic);
    int affected = 0;
    for (int id : player.map.objectsInSquare(center, DEFENCE_AREA_RANGE)) {
      WorldObject candidate = findObject(id);
      if (!isGroupFriend(player, candidate)) continue;
      applyDefenceUp((Player) candidate, seconds, magical);
      affected++;
    }

    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, center, firedTargetId, magic));
    if (affected > 0) trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code TBaseObject.DefenceUp} (ObjBase.pas:24255) / {@code MagDefenceUp} (ObjBase.pas:24309),
   * which are byte-for-byte the same routine against two different status slots. Both keep the
   * <em>longer</em> of the running window and the new roll, but refresh
   * {@code m_dwStatusArrTick} unconditionally — so the surviving window restarts its countdown
   * from now either way, which is what {@code Math.max} on the absolute timestamp reproduces.
   * The hint, {@code RecalcAbilitys} and {@code RM_ABILITY} are unconditional too: a target that
   * gained no time still gets repainted and told how long the (unchanged) window is.
   */
  private void applyDefenceUp(Player target, int seconds, boolean magical) {
    long candidate = clock.getAsLong() + Math.max(0, seconds) * 1_000L;
    if (magical) {
      target.magDefenceUpUntil = Math.max(target.magDefenceUpUntil, candidate);
    } else {
      target.defenceUpUntil = Math.max(target.defenceUpUntil, candidate);
    }
    emit(target, new WorldEvent.SystemMessage(target.id,
        String.format(magical ? MAG_DEFENCE_UP_MESSAGE : DEFENCE_UP_MESSAGE, seconds)));
    recalculateAbilities(target);
    // SendMsg(Self, RM_ABILITY, 0, 0, 0, 0, '') — and nothing else: unlike the equipment path
    // this does not drag RM_SUBABILITY or RM_WEIGHTCHANGED along.
    emit(target, new WorldEvent.AbilityChanged(
        target.id, target.ability, target.gold, target.job, target.weights()));
  }

  /**
   * {@code GetAttackPower(GetPower13(60) + LoWord(m_WAbil.SC) * 10,
   * SmallInt(HiWord(m_WAbil.SC) - LoWord(m_WAbil.SC)) + 1)} (Magic.pas:452/457): the roll doubles
   * as the buff's duration in <em>seconds</em>. Magic.DB gives both rows
   * {@code Power = MaxPower = DefPower = DefMaxPower = 0}, so {@code GetPower13(60)} reduces to
   * {@code ROUND(40 / 4 * (btLevel + 1) + 20)} — 30/40/50/60 at skill levels 0..3 — plus ten
   * seconds per point of minimum 道术, and the second argument is the usual power <em>spread</em>
   * (HiSC - LoSC + 1), which {@code GetAttackPower} turns into an inclusive {@code Random(n + 1)}
   * draw. Delphi's player-only {@code m_nPowerRate}/power-item/color modifiers stay outside the
   * Java player model, as in every earlier power roll.
   */
  private int rollDefenceSeconds(Player player, PlayerSkill skill, MagicDefinition magic) {
    int base = magic.scalePower13(DEFENCE_SECONDS_BASE, skill.level())
        + rollExclusive(magic.defPower(), magic.defMaxPower())
        + player.ability.minSc() * 10;
    int spread = player.ability.maxSc() - player.ability.minSc() + 1;
    return attackPower(base, base + spread, playerLuck(player));
  }

  /**
   * The {@code HAM_GROUP} arm of {@code IsProperFriend} (ObjBase.pas:24143) as both
   * {@code MagMakeDefenceArea} (W46) and {@code MagMakeGroupTransparent} (W47) reach it:
   * {@code cret = Self} or {@code IsGroupMember(cret)}, and only for {@code RC_PLAYOBJECT}.
   * Monsters are never friends here — a tamed slave would be one through {@code m_Master}, which
   * the Java object model does not carry yet, the same boundary W40 recorded for 群体治愈术.
   *
   * <p>Unlike {@link #isAreaHealFriend} this predicate carries no alive filter, because
   * {@code MagMakeDefenceArea} filters on {@code not m_boGhost} alone and {@code IsProperFriend}
   * never reads {@code m_boDeath}. The difference is unreachable rather than observable: a
   * character leaves its party the instant it dies (ObjBase.pas:21044, {@code 人物死亡立即退组}),
   * which {@link #handleDeath} reproduces through {@code leaveGroup}, and a dead caster cannot
   * start a cast at all — so no corpse can ever satisfy {@code cret = Self} or
   * {@code IsGroupMember(cret)} here.
   */
  private static boolean isGroupFriend(Player caster, WorldObject candidate) {
    if (!(candidate instanceof Player target) || target.map != caster.map) return false;
    if (target.id == caster.id) return true;
    return caster.group != null && caster.group == target.group && caster.group.contains(target.id);
  }

  /** Magic.DB rows 18/19 — 隐身术 and 集体隐身术, the last standalone pair of the 护身符 block. */
  private static boolean isCloakSkill(int magicId) {
    return magicId == SKILL_CLOAK || magicId == SKILL_BIGCLOAK;
  }

  /**
   * Magic.DB row 28 — 心灵启示 (Taoist, Magic.pas:522): the {@code RM_DOOPENHEALTH} reveal-spell
   * branch of W48. It reads only the clicked object, never re-rolls damage and needs no amulet.
   */
  private static boolean isShowHpSkill(int magicId) {
    return magicId == SKILL_SHOWHP;
  }

  /**
   * Magic.DB row 32 — 圣言术 (Wizard, Magic.pas:572): the {@code MagTurnUndead} instant-kill
   * branch of W48. Like the damage bolts it is a single-target hostile spell, but its effect is
   * resolved synchronously instead of through a delayed impact.
   */
  private static boolean isTurnUndeadSkill(int magicId) {
    return magicId == SKILL_KILLUNDEAD;
  }

  /**
   * Magic.DB row 22 — 火墙 (Wizard, Magic.pas:501 → {@code MagMakeFireCross},
   * Magic.pas:1135-1170): the last learnable row of the 1–33 block, and the engine's first
   * map-event (fire wall object) spell. The branch reads only the click coordinates; its effect
   * is a persistent {@code TFireBurnEvent} cross, not a delayed impact.
   */
  private static boolean isFireWallSkill(int magicId) {
    return magicId == SKILL_EARTHFIRE;
  }

  /**
   * Magic.DB row 21 — 瞬息移动 (Wizard, Magic.pas:495 → {@code MagSaceMove}, Magic.pas:951):
   * the caster teleports itself onto a random walkable cell of its home map. The branch reads
   * neither {@code TargeTBaseObject} nor the click cell for its effect — the coordinates only
   * ride along on the {@code RM_MAGICFIRE} frame the branch broadcasts itself.
   */
  private static boolean isSpaceMoveSkill(int magicId) {
    return magicId == SKILL_SPACEMOVE;
  }

  /**
   * {@code GetPower13(30) + GetRPow(PlayObject.m_WAbil.SC) * 3} (Magic.pas:477/481), in seconds.
   *
   * <p>{@code GetRPow} (Magic.pas:73) draws one value from {@code [LoWord(SC), HiWord(SC)]}
   * — inclusive, and just {@code LoWord} when the range is flat — and the literal 30 runs through
   * {@code GetPower13} (Magic.pas:238 → {@link MagicDefinition#scalePower13}), which yields
   * 15/20/25/30 at skill levels 0..3 with both rows' zero {@code DefPower}/{@code DefMaxPower}.
   * The engine reads the <em>working</em> ability's SC range, the same choice
   * {@link #rollDefenceSeconds} makes for the identical literal.
   */
  private int rollTransparentSeconds(Player player, PlayerSkill skill, MagicDefinition magic) {
    int minSc = player.ability.minSc();
    int maxSc = player.ability.maxSc();
    int rpow = maxSc > minSc
        ? minSc + random.nextInt(WorldRandom.Stream.MAGIC, maxSc - minSc + 1)
        : minSc;
    return magic.scalePower13(TRANSPARENT_SECONDS_BASE, skill.level())
        + rollExclusive(magic.defPower(), magic.defMaxPower())
        + rpow * 3;
  }

  /**
   * {@code GetCharStatus} (ObjBase.pas:20074): every {@code m_wStatusTimeArr} slot above zero
   * contributes {@code $80000000 shr slot} to the actor's status word. The engine models five of
   * the slots — the two poison timers, {@code STATE_TRANSPARENT}(8), {@code STATE_DEFENCEUP}(9),
   * {@code STATE_MAGDEFENCEUP}(10) and the 魔法盾 bubble slot (11) — so the word is rebuilt from
   * those and the rest stay zero, exactly like a character who never met a 石化/锁灵 status.
   */
  private static int charStatus(Player player, long now) {
    int status = 0;
    if (player.poison().decHealthUntil > now) status |= statusBit(POISON_DECHEALTH_SLOT);
    if (player.poison().damageArmorUntil > now) status |= statusBit(POISON_DAMAGEARMOR_SLOT);
    if (player.transparentUntil > now) status |= statusBit(STATE_TRANSPARENT);
    if (player.defenceUpUntil > now) status |= statusBit(STATE_DEFENCEUP);
    if (player.magDefenceUpUntil > now) status |= statusBit(STATE_MAGDEFENCEUP);
    if (player.magicShieldUntil > now) status |= statusBit(STATE_BUBBLEDEFENCEUP);
    // 心灵启示 (W48): GetCharStatus folds m_nCharStatusEx's low 20 bits into the word
    // (ObjBase.pas:20087), and MakeOpenHealth/BreakOpenHealth raise/clear STATE_OPENHEATH($2)
    // there without repainting — so the bit rides along on whatever repaint comes next.
    if (player.revealedHealth.active) status |= STATE_OPENHEATH;
    return status;
  }

  private static int statusBit(int slot) {
    return 0x8000_0000 >>> slot;
  }

  /**
   * {@code TBaseObject.StatusChanged} (ObjBase.pas:20139 → {@code SendRefMsg(RM_CHARSTATUSCHANGED,
   * m_nHitSpeed, m_nCharStatus, ...)}): the word goes to the actor and to every player inside the
   * ±12-cell broadcast square, which for the engine's smaller view range is the same set
   * {@link #emitToObserversAndSelf} walks. {@code m_nHitSpeed} has no Java source yet, so the
   * frame carries Delphi's initial zero.
   */
  private void emitCharacterStatusChanged(Player player) {
    emitToObserversAndSelf(player, new WorldEvent.CharacterStatusChanged(
        player.id, CHAR_STATUS_HIT_SPEED, player.status));
  }

  /**
   * {@code MagMakePrivateTransparent} (Magic.pas:734, 004930E8), the shared body of 隐身术 and of
   * the delayed {@code RM_TRANSPARENT} that 集体隐身术 queues for each friend:
   *
   * <pre>
   *   if m_wStatusTimeArr[STATE_TRANSPARENT] &gt; 0 then exit False;      // 已隐身，绝不刷新
   *   for 每个 ±9 格内的对象:                                            // GetMapBaseObjects(..., 9, ...)
   *     if (race &gt;= RC_ANIMAL) and (m_TargetCret = Self) then
   *       if (|dx| &gt; 1) or (|dy| &gt; 1) or (Random(2) = 0) then         // 近身只有一半概率
   *         m_TargetCret := nil;
   *   m_wStatusTimeArr[STATE_TRANSPARENT] := nHTime;
   *   m_nCharStatus := GetCharStatus();  StatusChanged();
   *   m_boHideMode := True;  m_boTransparent := True;
   * </pre>
   *
   * @return Delphi's {@code Result}: false when the target was already transparent (the caller
   *     then skips {@code boTrain}, though the 护身符 charge and MP were already spent)
   */
  private boolean applyPrivateTransparent(Player target, int seconds) {
    long now = clock.getAsLong();
    if (target.transparentUntil > now) return false;
    clearMonsterAggro(target);
    target.transparentUntil = now + Math.max(0, seconds) * 1_000L;
    target.transparent = true;
    target.hideMode = true;
    target.status = charStatus(target, now);
    emitCharacterStatusChanged(target);
    return true;
  }

  /**
   * The de-target sweep of {@code MagMakePrivateTransparent} (Magic.pas:744-753): every monster
   * within nine cells that is currently hunting the newly cloaked player drops its target —
   * always when it stands more than one cell away, and only on a {@code Random(2) = 0} coin flip
   * when it is adjacent. Objects of other races are never touched, and monsters further than nine
   * cells keep chasing (Delphi only clears what the sweep reaches).
   */
  private void clearMonsterAggro(Player target) {
    for (int id : target.map.objectsInSquare(target.position, TRANSPARENT_DE_TARGET_RANGE)) {
      Monster monster = monsters.get(id);
      if (monster == null || monster.targetId != target.id || !monster.ability().alive()) continue;
      boolean distant = Math.abs(monster.position.x() - target.position.x()) > 1
          || Math.abs(monster.position.y() - target.position.y()) > 1;
      if (distant || random.nextInt(WorldRandom.Stream.CLOAK_AGGRO, 2) == 0) monster.targetId = 0;
    }
  }

  /**
   * {@code SKILL_BIGCLOAK} (Magic.pas:481 → {@code MagMakeGroupTransparent}, Magic.pas:1286):
   * the inclusive 3x3 square around the (possibly snapped) click cell, filtered by the caster's
   * {@code IsProperFriend}, then one self-addressed 800 ms {@code RM_TRANSPARENT} per friend whose
   * transparent slot is idle. The delayed message re-runs {@link #applyPrivateTransparent} on the
   * recipient — which re-checks the slot and so never refreshes a window that started inside the
   * 800 ms — while the friendship itself is <em>not</em> re-checked at delivery (Delphi does not
   * either). {@code boTrain} follows the returned count: a square with no eligible friend trains
   * nothing even though the charm and MP are gone.
   */
  private void castGroupCloak(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId) {
    WorldObject clicked = targetId != 0 ? findObject(targetId) : null;
    boolean snapped = clicked != null && clicked.map() == player.map
        && chebyshev(clicked.position(), requestedTarget) <= 1;
    Position center = snapped ? clicked.position() : requestedTarget;
    int firedTargetId = snapped && clicked.ability().alive() ? clicked.id() : 0;

    long now = clock.getAsLong();
    int seconds = rollTransparentSeconds(player, skill, magic);
    int affected = 0;
    for (int id : player.map.objectsInSquare(center, GROUP_TRANSPARENT_RANGE)) {
      WorldObject candidate = findObject(id);
      if (!isGroupFriend(player, candidate)) continue;
      Player friend = (Player) candidate;
      if (friend.transparentUntil > now) continue;
      pendingMagicImpacts.add(new PendingMagicImpact(
          now + GROUP_TRANSPARENT_DELAY_MILLIS, MagicImpactKind.TRANSPARENT,
          player.id, friend.id(), friend.position(), seconds));
      affected++;
    }
    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, center, firedTargetId, magic));
    if (affected > 0) trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code SKILL_CLOAK} (Magic.pas:476 → {@code MagMakePrivateTransparent}). The result drives
   * {@code boTrain}: a recast while already invisible spends the charm and MP but neither trains
   * nor repaints the status word.
   */
  private void castPrivateCloak(
      Player player, PlayerSkill skill, MagicDefinition magic, Position target, int targetId) {
    int seconds = rollTransparentSeconds(player, skill, magic);
    boolean applied = applyPrivateTransparent(player, seconds);
    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, target, targetId, magic));
    if (applied) trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code SKILL_SHOWHP} (28, 心灵启示, Magic.pas:522-531): reveal the clicked object's HP bar to
   * everyone nearby.
   *
   * <pre>
   *   if (TargeTBaseObject &lt;&gt; nil) and not TargeTBaseObject.m_boShowHP then begin
   *     if Random(6) &lt;= (UserMagic.btLevel + 3) then begin
   *       TargeTBaseObject.m_dwShowHPTick := GetTickCount();
   *       TargeTBaseObject.m_dwShowHPInterval := GetPower13(GetRPow(SC) * 2 + 30) * 1000;
   *       TargeTBaseObject.SendDelayMsg(TargeTBaseObject, RM_DOOPENHEALTH, 0, 0, 0, 0, '', 1500);
   *       boTrain := True;
   *     end;
   *   end;
   * </pre>
   *
   * <p>Three quirks are kept verbatim. The branch never touches {@code boSpellFail}/{@code
   * boSpellFire}, so mana is already gone and {@code RM_MAGICFIRE} still broadcasts even when the
   * reveal gate fails or the target is already revealed. {@code m_dwShowHPTick}/{@code
   * m_dwShowHPInterval} are stamped on the <em>target</em> at cast time, so the visible window is
   * measured from the cast — 1.5 s before the bar actually appears (the delayed message is
   * self-addressed to the target, not to the caster). And {@code m_boShowHP} only flips at
   * delivery, so a second cast inside the 1.5 s window passes the {@code not m_boShowHP} gate
   * again and simply re-stamps the timings.
   */
  private void castShowHp(Player player, PlayerSkill skill, MagicDefinition magic,
      Position target, int targetId, WorldObject targetObject, long now) {
    boolean queued = false;
    if (targetObject != null && !targetObject.revealedHealth().active
        && random.nextInt(WorldRandom.Stream.SHOW_HP, 6) <= skill.level() + 3) {
      int seconds = rollShowHpIntervalSeconds(player, skill, magic);
      RevealedHealth health = targetObject.revealedHealth();
      health.castAt = now;
      health.intervalMillis = seconds * 1_000L;
      pendingMagicImpacts.add(new PendingMagicImpact(
          now + SHOW_HP_REVEAL_DELAY_MILLIS, MagicImpactKind.OPEN_HEALTH,
          player.id, targetId, targetObject.position(), 0));
      queued = true;
    }
    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, target, targetId, magic));
    if (queued) trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code m_dwShowHPInterval := GetPower13(GetRPow(PlayObject.m_WAbil.SC) * 2 + 30) * 1000}
   * (Magic.pas:526), in seconds. {@code GetRPow} (Magic.pas:73) draws one value from the working
   * ability's {@code [minSC, maxSC]} range — inclusive, and just {@code minSC} when flat — and the
   * literal runs through {@code GetPower13} (Magic.pas:238 → {@link MagicDefinition#scalePower13}),
   * so a bare SC(0) target reveals for 15/20/25/30 s at skill levels 0..3. The draw order matches
   * Delphi: the {@code Random(6)} gate first (in {@link #castShowHp}), this SC roll only on success.
   */
  private int rollShowHpIntervalSeconds(Player player, PlayerSkill skill, MagicDefinition magic) {
    int minSc = player.ability.minSc();
    int maxSc = player.ability.maxSc();
    int rpow = maxSc > minSc
        ? minSc + random.nextInt(WorldRandom.Stream.MAGIC, maxSc - minSc + 1)
        : minSc;
    return magic.scalePower13(rpow * 2 + 30, skill.level())
        + rollExclusive(magic.defPower(), magic.defMaxPower());
  }

  /**
   * {@code MakeOpenHealth} (ObjBase.pas:3606): the delayed {@code RM_DOOPENHEALTH} delivery.
   * Sets {@code m_boShowHP}, folds {@code STATE_OPENHEATH ($2)} into the status word (without
   * repainting it — Delphi does not call {@code StatusChanged} here either) and broadcasts
   * {@code RM_OPENHEALTH} with the target's HP/MaxHP to every player in range.
   */
  private void makeOpenHealth(WorldObject target) {
    target.revealedHealth().active = true;
    emitToObserversAndSelf(target, new WorldEvent.HealthRevealed(target.snapshot()));
  }

  /**
   * {@code BreakOpenHealth} (ObjBase.pas:3595): the expiry half — clears {@code m_boShowHP} and
   * broadcasts {@code RM_CLOSEHEALTH} to every player in range.
   */
  private void breakOpenHealth(WorldObject target) {
    target.revealedHealth().active = false;
    emitToObserversAndSelf(target, new WorldEvent.HealthConcealed(target.id()));
  }

  /**
   * The {@code m_boShowHP} arm of {@code TBaseObject.Run}'s status walk (ObjBase.pas:4029-4031):
   * strictly-greater comparison against the cast-time reference, so the bar vanishes one tick
   * after {@code m_dwShowHPTick + m_dwShowHPInterval}.
   */
  private void expireOpenHealth(WorldObject target, long now) {
    RevealedHealth health = target.revealedHealth();
    if (!health.active || now - health.castAt <= health.intervalMillis) return;
    breakOpenHealth(target);
  }

  /**
   * {@code SKILL_KILLUNDEAD} (32, 圣言术, Magic.pas:572-577): the wizard's instant-kill against
   * undead monsters. The branch is `if IsProperTarget(TargeTBaseObject) then if MagTurnUndead(...)
   * then boTrain := True` — no resist roll, no delayed impact, and {@code RM_MAGICFIRE} fires
   * unconditionally at the tail of {@code DoSpell}. The fire frame is emitted <em>before</em> the
   * effect runs so a kill's {@code SM_DEATH} lands after {@code SM_MAGICFIRE}, matching Delphi
   * where the monster's {@code Die} is processed on its own next tick.
   */
  private void castTurnUndead(
      Player player, PlayerSkill skill, MagicDefinition magic, Position target, int targetId,
      WorldObject targetObject) {
    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, target, targetId, magic));
    boolean killed = targetObject instanceof Monster monster
        && turnUndead(player, monster, skill.level());
    if (killed) trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code MagTurnUndead} (Magic.pas:901-925, 004926D4):
   *
   * <pre>
   *   if TargeTBaseObject.m_boSuperMan or not (TargeTBaseObject.m_btLifeAttrib = LA_UNDEAD) then exit;
   *   TAnimalObject(TargeTBaseObject).Struck(BaseObject);
   *   if TargeTBaseObject.m_TargetCret = nil then begin   // 恐惧冻结 10 秒
   *     m_boRunAwayMode := True;  m_dwRunAwayStart := GetTickCount();  m_dwRunAwayTime := 10 * 1000;
   *   end;
   *   BaseObject.SetTargetCreat(TargeTBaseObject);
   *   if (Random(2) + (casterLevel - 1)) &gt; targetLevel then
   *     if targetLevel &lt; g_Config.nMagTurnUndeadLevel then
   *       if Random(100) &lt; ((nLevel shl 3) - nLevel + 15 + (casterLevel - targetLevel)) then
   *         SetLastHiter;  m_WAbil.HP := 0;  Result := True;
   * </pre>
   *
   * <p>Players default to {@code m_btLifeAttrib := 0} (ObjBase.pas:1244), so the {@code LA_UNDEAD}
   * gate already refuses them; no wired monster is {@code m_boSuperMan}. {@code Struck} runs before
   * every gate, so even a failed cast leaves the monster angry (and possibly frozen). The kill is
   * {@code HP := 0} — death (drops, experience, corpse) goes through the same {@link #handleDeath}
   * chain as any other kill, per the W14 synchronous-death convention.
   *
   * @return Delphi's {@code Result}: true only when the instant kill landed (drives {@code boTrain})
   */
  private boolean turnUndead(Player caster, Monster target, int skillLevel) {
    long now = clock.getAsLong();
    if (!target.template.undead()) return false;
    monsterStruck(target, caster);
    // The run-away arm only fires when Struck left the monster without a target — typically the
    // caster stands in a safe zone, so the monster's own IsProperTarget(hiter) refused the retarget.
    if (target.targetId == 0) target.runAwayUntil = now + TURN_UNDEAD_RUN_AWAY_MILLIS;
    if (random.nextInt(WorldRandom.Stream.TURN_UNDEAD, 2)
        + (caster.ability.level() - 1) <= target.ability().level()) return false;
    if (target.ability().level() >= config.magTurnUndeadLevel()) return false;
    int levelGap = caster.ability.level() - target.ability().level();
    int killChance = (skillLevel << 3) - skillLevel + 15 + levelGap;
    if (random.nextInt(WorldRandom.Stream.TURN_UNDEAD, 100) >= killChance) return false;
    target.setAbility(target.ability().withHp(0));
    handleDeath(target, caster);
    return true;
  }

  /**
   * {@code TAnimalObject.Struck} (ObjBase.pas:2794-2816): the shared "this monster was poked"
   * path. Retargets to the attacker when it had no target, when its current target stands in its
   * 3x3 neighbourhood ({@code GetAttackDir}, ObjBase.pas:18449), or on a {@code Random(6) = 0} coin
   * otherwise — always provided the attacker is a proper target from the monster's side (alive,
   * not inside a safe zone). {@code m_dwHitTick := m_dwHitTick + (150 - _MIN(130, level * 4))}
   * gates the attack cadence, so the strike also buys a short flinch. The {@code m_nMeatQuality}
   * penalty for {@code m_boAnimal} is not modelled (no肉 system). No wire frame is emitted.
   */
  private void monsterStruck(Monster monster, Player attacker) {
    boolean retarget = monster.targetId == 0;
    if (!retarget) {
      WorldObject current = findObject(monster.targetId);
      if (current != null && !monster.position.equals(current.position())
          && chebyshev(monster.position, current.position()) <= 1) {
        retarget = true;
      } else {
        retarget = random.nextInt(WorldRandom.Stream.STRUCK_RETARGET, 6) == 0;
      }
    }
    if (retarget && isAttackTarget(attacker)) monster.targetId = attacker.id;
    monster.lastAttackAt += 150 - Math.min(130, monster.ability().level() * 4);
  }

  /**
   * {@code SKILL_EARTHFIRE}(22, 火墙, Magic.pas:501 → {@code MagMakeFireCross},
   * Magic.pas:1135-1170): the engine's first map-event spell. The branch reads neither
   * {@code TargeTBaseObject} nor any target state — the click coordinates alone steer the cross,
   * so an empty-ground cast with targetId = 0 is legal (like 抗拒火环/群体治愈术). Delphi
   * evaluates both argument expressions before {@code MagMakeFireCross}'s safe-zone gate runs,
   * so the power and duration rolls happen even on a refused cast; the refusal still burns the
   * mana (spent before DoSpell's case) and still broadcasts {@code RM_MAGICFIRE} (boSpellFire
   * is never cleared on this branch), but trains nothing.
   *
   * <p>Once past the gate, the cross is laid arm by arm in Delphi's order — (x, y-1), (x-1, y),
   * (x, y), (x+1, y), (x, y+1) — each arm skipped when the cell already carries any event
   * ({@code TEnvirnoment.GetEvent} has no type filter, Envir.pas:1420; only fire walls exist in
   * this engine, so "any event" = "a fire wall"). {@code Result := 1} is unconditional at the
   * tail, so a fully occupied cross still trains — and still spent the mana.
   *
   * <p>Visibility follows Delphi's {@code SearchViewRange} sweep (ObjBase.pas:25764-25774):
   * the client learns the flames <em>after</em> the cast frame, so the engine emits
   * {@code SM_MAGICFIRE} first and the {@link WorldEvent.EventAppeared} broadcasts second.
   * Arms landing outside the map are created in Delphi but never registered on a cell —
   * invisible and unable to hit anything — so this engine does not register them either.
   */
  private void castFireWall(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId, long now) {
    // CretInNearXY snapping as in every other ground-target spell (ObjBase.pas:16854): the
    // snapped cell is only what the RM_MAGICFIRE frame carries.
    WorldObject clicked = targetId != 0 ? findObject(targetId) : null;
    boolean snapped = clicked != null && clicked.map() == player.map
        && chebyshev(clicked.position(), requestedTarget) <= 1;
    Position center = snapped ? clicked.position() : requestedTarget;
    int firedTargetId = snapped && clicked.ability().alive() ? clicked.id() : 0;

    // Delphi evaluates the GetAttackPower(...) and GetPower(10) + ... arguments before
    // MagMakeFireCross's safe-zone gate, so both rolls precede the refusal as well.
    int damage = rollMcAttackPower(player, skill, magic);
    long durationMillis = rollFireWallSeconds(player, skill, magic) * 1_000L;

    if (config.disableFireCrossInSafeZone() && player.map.isSafeZone(center)) {
      // Magic.pas:1143-1146: SysMsg + exit with Result = 0 — no fire, no training, but the
      // mana is already spent and RM_MAGICFIRE still goes out at DoSpell's tail.
      emit(player, new WorldEvent.SystemMessage(player.id, FIRE_CROSS_SAFE_ZONE_MESSAGE));
      emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, center, firedTargetId, magic));
      return;
    }

    List<FireWallEvent> created = new ArrayList<>();
    // MagMakeFireCross's arm order (Magic.pas:1148-1164): up, left, center, right, down.
    for (Position cell : List.of(
        center.translate(Direction.UP, 1), center.translate(Direction.LEFT, 1), center,
        center.translate(Direction.RIGHT, 1), center.translate(Direction.DOWN, 1))) {
      if (!player.map.contains(cell) || fireWallAt(player.map, cell) != null) continue;
      FireWallEvent fire = new FireWallEvent(
          allocateObjectId(), player.map, cell, damage, player.id, now, durationMillis);
      fireWalls.put(fire.id(), fire);
      created.add(fire);
    }

    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, center, firedTargetId, magic));
    for (FireWallEvent fire : created) {
      WorldEvent appeared = new WorldEvent.EventAppeared(fire.id(), ET_FIRE, fire.position(), 0);
      for (int viewerId : visibleIds(fire.map(), fire.position(), 0)) {
        emit(players.get(viewerId), appeared);
      }
    }
    // MagMakeFireCross returns 1 unconditionally once the safe-zone gate passed, so the cast
    // trains even when every arm's cell was already occupied.
    trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code SKILL_SPACEMOVE}(21, 瞬息移动, Magic.pas:495 → {@code MagSaceMove}, Magic.pas:951-971) —
   * the engine's first map-displacement spell. The branch is the only one in Magic.DB 1–33 that
   * broadcasts {@code RM_MAGICFIRE} <em>itself</em> and then clears {@code boSpellFire}, so the
   * cast frame reaches the wire <em>before</em> the caster moves and {@code DoSpell}'s tail
   * (Magic.pas:713) stays silent.
   *
   * <p>Past the {@code Random(11) < nLevel * 2 + 4} gate — 4/11, 6/11, 8/11, 10/11 at skill
   * levels 0..3 — Delphi announces the departure ({@code RM_SPACEMOVE_FIRE2}), then hands the
   * caster to {@code MapRandomMove(m_sHomeMap, 1)}. {@code Result := True} sits inside the gate
   * but <em>outside</em> the move, so a missing home map or an exhausted {@code GetRandXY}
   * search still trains — and a failed gate still spent the mana (deducted before the case)
   * while showing the cast pose, training nothing and moving nobody.
   */
  private void castSpaceMove(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId) {
    // CretInNearXY snapping (ObjBase.pas:9227, applied in ClientSpellXY before DoSpell): the
    // snapped cell only rides along on the frame — MagSaceMove ignores the coordinates.
    WorldObject clicked = targetId != 0 ? findObject(targetId) : null;
    boolean snapped = clicked != null && clicked.map() == player.map
        && chebyshev(clicked.position(), requestedTarget) <= 1;
    Position center = snapped ? clicked.position() : requestedTarget;
    int firedTargetId = snapped && clicked.ability().alive() ? clicked.id() : 0;

    // Magic.pas:496 — the branch's own cast frame, ahead of everything else it does.
    emitToObserversAndSelf(player,
        new WorldEvent.MagicFired(player.id, center, firedTargetId, magic));

    // MagSaceMove's gate (Magic.pas:957): a failure leaves the caster exactly where it stands.
    if (random.nextInt(WorldRandom.Stream.SPACE_MOVE, SPACE_MOVE_GATE_BOUND)
        >= skill.level() * 2 + 4) {
      return;
    }

    // RM_SPACEMOVE_FIRE2 -> SM_SPACEMOVE_HIDE2 (Magic.pas:959), sent while the caster still
    // stands on the old cell, so the recipients are that cell's observers (plus the caster,
    // whose own client silently ignores the frame — ClMain.pas:4552).
    emitToObserversAndSelf(player, new WorldEvent.SpaceMoveHidden(player.id));

    // MapRandomMove (ObjBase.pas:9810): a home map that is not loaded, or a GetRandXY search
    // that never lands, simply leaves the caster in place — the training below still happens.
    GameMap home = maps.get(player.homeMapId);
    if (home != null) {
      Position landing = randomMapCell(home, mapRandomMoveStart(home));
      if (landing != null) relocatePlayer(player, home, landing);
    }
    trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code MapRandomMove}'s start cell (ObjBase.pas:9819-9827): a margin derived from the map's
   * <em>height</em> alone and applied to both axes, then two left-closed/right-open draws.
   * Delphi's {@code Random} yields 0 for a non-positive bound; {@link WorldRandom#nextInt}
   * insists on a positive one, so a degenerate map is clamped to the same observable value.
   */
  private Position mapRandomMoveStart(GameMap map) {
    int edge = map.height() < 150 ? (map.height() < 30 ? 2 : 20) : 50;
    int x = random.nextInt(WorldRandom.Stream.SPACE_MOVE,
        Math.max(1, map.width() - edge - 1)) + edge;
    int y = random.nextInt(WorldRandom.Stream.SPACE_MOVE,
        Math.max(1, map.height() - edge - 1)) + edge;
    return new Position(x, y);
  }

  /**
   * {@code SpaceMove}'s nested {@code GetRandXY} (ObjBase.pas:4360-4385): at most
   * {@link #GET_RAND_XY_ATTEMPTS} cells are inspected, each miss advancing x by 10 (3 on maps
   * narrower than 80 cells) and — once x runs past the right margin — wrapping through a fresh
   * {@code Random(wWidth)} while y advances by the same rule.
   *
   * <p>Delphi asks {@code CanWalk(x, y, True)}, whose {@code boFlag} explicitly ignores actors
   * standing on the cell (Envir.pas:392-427), so a random move may land on top of somebody.
   * This engine keeps one moving object per cell, so the test also requires a free cell; the
   * stepping rule, the wrap bounds and the attempt cap stay verbatim, which keeps both the draw
   * stream and the landing cell identical to Delphi wherever a walkable cell is also free.
   */
  private Position randomMapCell(GameMap map, Position start) {
    int step = map.width() < 80 ? 3 : 10;
    int margin = map.height() < 150 ? (map.height() < 50 ? 2 : 15) : 50;
    int x = start.x();
    int y = start.y();
    for (int attempt = 0; attempt < GET_RAND_XY_ATTEMPTS; attempt++) {
      Position candidate = new Position(x, y);
      if (map.isTerrainWalkable(candidate) && map.objectAt(candidate) == 0) return candidate;
      if (x < map.width() - margin - 1) {
        x += step;
      } else {
        x = random.nextInt(WorldRandom.Stream.SPACE_MOVE, Math.max(1, map.width()));
        if (y < map.height() - margin - 1) {
          y += step;
        } else {
          y = random.nextInt(WorldRandom.Stream.SPACE_MOVE, Math.max(1, map.height()));
        }
      }
    }
    return null;
  }

  /**
   * {@code TBaseObject.SpaceMove}'s same-server branch (ObjBase.pas:4410-4445): the caster is
   * unmapped, moved and remapped, then told to drop its scene ({@code RM_CLEAROBJECTS}) and load
   * the — possibly unchanged — map ({@code RM_CHANGEMAP}) before {@code RM_SPACEMOVE_SHOW2}
   * announces it to everyone in range of the landing cell. The {@code *2} variant is chosen over
   * {@code RM_SPACEMOVE_SHOW} because {@code MapRandomMove} passes {@code nInt = 1}
   * (ObjBase.pas:4431).
   *
   * <p>Delphi lets the periodic {@code SearchViewRange} sweep refill both the mover's scene and
   * the observers'. The Java engine is event-driven instead, so — exactly as the gate teleport
   * in {@link #teleportPlayer} — the appearances, ground items and fire walls of the landing
   * cell are pushed explicitly, and the observers of the cell being left additionally receive
   * {@code SM_DISAPPEAR} on top of the hide frame.
   */
  private void relocatePlayer(Player player, GameMap destination, Position landing) {
    GameMap origin = player.map;
    Position source = player.position;
    List<Integer> originObservers = visibleIds(origin, source, player.id);
    origin.remove(player.id, source);
    player.map = destination;
    player.position = landing;
    destination.place(player.id, landing);
    WorldObjectSnapshot snapshot = player.snapshot();

    // Self: SM_CLEAROBJECTS + SM_CHANGEMAP + SM_MAPDESCRIPTION (ObjBase.pas:4427-4428).
    emit(player,
        new WorldEvent.PlayerMapChanged(snapshot, destination.info(), dayBright(destination)));
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(player.id);
    for (int viewerId : originObservers) emit(players.get(viewerId), disappeared);
    // Self + the landing cell's observers: SM_SPACEMOVE_SHOW2 (ObjBase.pas:4432).
    emitToObserversAndSelf(player, new WorldEvent.SpaceMoveShown(snapshot));
    List<Integer> destinationObservers = visibleIds(destination, landing, player.id);
    WorldEvent appeared = new WorldEvent.ObjectAppeared(snapshot);
    for (int viewerId : destinationObservers) emit(players.get(viewerId), appeared);
    for (int objectId : destinationObservers) {
      WorldObject other = findObject(objectId);
      if (other != null) emit(player, new WorldEvent.ObjectAppeared(other.snapshot()));
    }
    for (GroundItem item : visibleItems(destination, landing)) {
      emit(player, new WorldEvent.ItemAppeared(item));
    }
    for (FireWallEvent fire : visibleFireWalls(destination, landing)) {
      emit(player, new WorldEvent.EventAppeared(fire.id(), ET_FIRE, fire.position(), 0));
    }
  }

  /**
   * The {@code GetPower(10) + (Word(GetRPow(PlayObject.m_WAbil.MC)) shr 1)} duration argument
   * (Magic.pas:506), in seconds — {@code GetPower(10)} is {@link #getMagicPower} with the
   * literal 10 (row 22's DefPower/DefMaxPower = 3/3 make it 5/8/11/13 s at skill levels 0..3),
   * and {@code GetRPow} (Magic.pas:73) draws one inclusive value from the working ability's
   * MC range — the same shape {@link #rollTransparentSeconds} already uses for SC — halved by
   * the unsigned {@code shr 1}.
   */
  private int rollFireWallSeconds(Player player, PlayerSkill skill, MagicDefinition magic) {
    int minMc = player.ability.minMc();
    int maxMc = player.ability.maxMc();
    int rpow = random.between(WorldRandom.Stream.MAGIC, minMc, maxMc);
    return getMagicPower(magic, skill.level(), 10) + (rpow >>> 1);
  }

  /**
   * The periodic half of the {@code g_EventManager} slice (Event.pas:102/239/277): per fire
   * wall, in insertion order —
   *
   * <ol>
   *   <li>the owner sweep: a dead or departed caster stops the fire from hurting anyone, while
   *       the flames keep rendering until expiry (Event.pas:282 clears {@code m_OwnBaseObject}
   *       on ghost/death; Delphi re-checks every 500 ms, this engine folds the window into the
   *       damage tick per the W14 synchronous-death convention);
   *   <li>the damage gate: every {@link #FIRE_WALL_TICK_MILLIS} the fire burns every living
   *       proper target standing on its cell — {@code GeTBaseObjects(cell, True)} collects the
   *       cell's moving objects, and {@code IsProperTarget} filters them (alive, same map,
   *       not the caster, not an NPC). The gate's {@code lastDamageAt} starts at 0, so the
   *       first tick after creation burns immediately, exactly like Delphi's zero-initialised
   *       redeclared {@code m_dwRunTick};
   *   <li>expiry: strictly past the duration the fire closes — removed from the registry and
   *       {@code SM_HIDEEVENT} broadcast to the cell's viewers (Delphi's {@code Close} unmaps
   *       the event and the next {@code SearchViewRange} sweep reports the disappearance).
   * </ol>
   */
  private void tickFireWalls() {
    long now = clock.getAsLong();
    List<FireWallEvent> expired = new ArrayList<>();
    for (FireWallEvent fire : fireWalls.values()) {
      if (fire.ownerId() != 0) {
        WorldObject owner = findObject(fire.ownerId());
        if (owner == null || !owner.ability().alive()) fire.clearOwner();
      }
      if (fire.ownerId() != 0 && now - fire.lastDamageAt() > FIRE_WALL_TICK_MILLIS) {
        fire.tickDamage(now);
        WorldObject owner = findObject(fire.ownerId());
        for (int id : fire.map().objectsInSquare(fire.position(), 0)) {
          WorldObject target = findObject(id);
          if (owner instanceof Player player && isProperTarget(player, target)) {
            applyFireWallDamage(owner, target, fire.damage());
          }
        }
      }
      if (now - fire.createdAt() > fire.durationMillis()) expired.add(fire);
    }
    for (FireWallEvent fire : expired) {
      fireWalls.remove(fire.id());
      WorldEvent disappeared = new WorldEvent.EventDisappeared(fire.id(), fire.position());
      for (int viewerId : visibleIds(fire.map(), fire.position(), 0)) {
        emit(players.get(viewerId), disappeared);
      }
    }
  }

  /**
   * {@code RM_MAGSTRUCK_MINE} (ObjBase.pas:4501-4520, Grobal2.pas:8030): the fire wall's damage
   * chain. Unlike {@code RM_MAGSTRUCK} it carries no walk stagger; {@code GetMagStruckDamage}
   * (ObjBase.pas:22441) rolls the victim's inclusive MAC range and applies the 魔法盾 branch
   * with no attacker-side undead bonus ({@code BaseObject = nil}); the result then flows through
   * the shared {@link #applyDamage} path — poison-armor multiplier, PK flag, armour wear,
   * {@code SM_STRUCK} with lTag2 = 1 (magical), revival ring and the ordinary death chain, so a
   * monster killed by fire credits its experience and loot to the caster.
   */
  private void applyFireWallDamage(WorldObject owner, WorldObject target, int rawDamage) {
    int defence = random.between(WorldRandom.Stream.MAGIC,
        target.ability().minMac(), target.ability().maxMac());
    int damage = applyMagicShield(target, Math.max(0, rawDamage - defence));
    applyDamage(target, owner, damage, true);
  }

  /**
   * {@code TBaseObject.Walk}'s event scan (ObjBase.pas:20204-20229): after any base object —
   * player or monster — lands on a cell carrying a damaging event with a live owner that
   * {@code IsProperTarget}s the walker, the walker takes one immediate {@code RM_MAGSTRUCK_MINE}.
   * The step is never blocked and the caster never burns himself ({@code IsProperTarget} excludes
   * self). Teleports bypass {@code Walk} in Delphi, so they never trigger this.
   */
  private void struckByFireWallOnStep(WorldObject walker) {
    FireWallEvent fire = fireWallAt(walker.map(), walker.position());
    if (fire == null || fire.ownerId() == 0) return;
    if (!(findObject(fire.ownerId()) instanceof Player owner)) return;
    if (!isProperTarget(owner, walker)) return;
    applyFireWallDamage(owner, walker, fire.damage());
  }

  /** {@code TEnvirnoment.GetEvent} (Envir.pas:1420): the fire wall standing on {@code cell}. */
  private FireWallEvent fireWallAt(GameMap map, Position cell) {
    for (FireWallEvent fire : fireWalls.values()) {
      if (fire.map() == map && fire.position().equals(cell)) return fire;
    }
    return null;
  }

  /** The active fire walls inside {@code center}'s view square, in registration order. */
  private List<FireWallEvent> visibleFireWalls(GameMap map, Position center) {
    List<FireWallEvent> result = new ArrayList<>();
    for (FireWallEvent fire : fireWalls.values()) {
      if (fire.map() == map && fire.position().distanceTo(center) <= config.viewRange()) {
        result.add(fire);
      }
    }
    return List.copyOf(result);
  }

  /**
   * The event half of {@code SearchViewRange}'s sweep (ObjBase.pas:25764-25774): after a move,
   * fire walls that left the player's view square get {@code SM_HIDEEVENT} and the newcomers
   * get {@code SM_SHOWEVENT} — the same diff shape {@link #emitItemVisibilityChanges} uses for
   * ground items. Delphi delivers these on its periodic rescan; the engine reports them
   * immediately on the move instead, which is observably equivalent.
   */
  private void emitFireWallVisibilityChanges(Player player, Position source, Position target) {
    List<FireWallEvent> before = visibleFireWalls(player.map, source);
    List<FireWallEvent> after = visibleFireWalls(player.map, target);
    for (FireWallEvent hidden : before) {
      if (!after.contains(hidden)) {
        emit(player, new WorldEvent.EventDisappeared(hidden.id(), hidden.position()));
      }
    }
    for (FireWallEvent shown : after) {
      if (!before.contains(shown)) {
        emit(player, new WorldEvent.EventAppeared(shown.id(), ET_FIRE, shown.position(), 0));
      }
    }
  }

  /** {@code Random(10) >= target.m_nAntiMagic}: true when a hostile magic effect lands. */
  private boolean passesMagicResist(WorldObject target) {
    return target != null && random.nextInt(WorldRandom.Stream.MAGIC_RESIST, 10) >= target.antiMagic();
  }

  /** {@code Random(target.m_btAntiPoison + 7) <= 6}: true when 施毒术 is not resisted. */
  private boolean passesPoisonResist(WorldObject target) {
    return target != null
        && random.nextInt(WorldRandom.Stream.POISON_RESIST, target.antiPoison() + 7) <= 6;
  }

  /** Magic.DB row 29 — 群体治愈术: the engine's first area-of-effect spell (W40). */
  private static boolean isAreaHealingSkill(int magicId) {
    return magicId == SKILL_BIGHEALLING;
  }

  /**
   * Magic.DB rows 23/33 — 爆裂火焰 and 冰咆哮: both call {@code MagBigExplosion} and differ only
   * in which configured square radius they pass as {@code nRage} — 爆裂火焰 reads
   * {@code g_Config.nFireBoomRage} (Magic.pas:515), 冰咆哮 reads
   * {@code g_Config.nSnowWindRange} (Magic.pas:583). The two are independent config fields:
   * SnowWind never falls back onto the FireBoom radius.
   */
  private static boolean isAreaExplosionSkill(int magicId) {
    return magicId == SKILL_FIREBOOM || magicId == SKILL_SNOWWIND;
  }

  /** Magic.DB row 24 — 地狱雷光, the caster-centred {@code LA_UNDEAD} blizzard of W43. */
  private static boolean isElecBlizzardSkill(int magicId) {
    return magicId == SKILL_LIGHTFLOWER;
  }

  /**
   * Magic.DB row 8 — 抗拒火环, the wizard's pure-displacement {@code MagPushArround} branch of
   * W45. {@code SKILL_ENERGYREPULSOR}(37, 气功波, Magic.pas:676) calls the very same routine but
   * the GEEM2 baseline has no row 37, so its job is still uncalibrated and it deliberately stays
   * out of this slice.
   */
  private static boolean isPushArroundSkill(int magicId) {
    return magicId == SKILL_FIREWIND;
  }

  /**
   * {@code SKILL_BIGHEALLING}(29, 群体治愈术, Magic.pas:532 → {@code MagBigHealing},
   * Magic.pas:172):
   *
   * <pre>
   *   nPower := GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(SC) * 2,
   *                            SmallInt(HiWord(SC) - LoWord(SC)) * 2 + 1);
   *   GetMapBaseObjects(m_PEnvir, nX, nY, 1, BaseObjectList);   // inclusive 3x3 square
   *   for i := 0 to BaseObjectList.Count - 1 do
   *     if IsProperFriend(BaseObject) then
   *       if BaseObject.m_WAbil.HP &lt; BaseObject.m_WAbil.MaxHP then
   *         BaseObject.SendDelayMsg(PlayObject, RM_MAGHEALING, 0, nPower, 0, 0, '', 800);
   * </pre>
   *
   * <p>The power roll is the single-target 治愈术 formula ({@link #rollHealingPower}), rolled
   * <em>once</em> and shared by every target — targets do not re-roll. Full-health objects are
   * skipped at cast time (which is also what makes {@code boTrain} false when nobody needed the
   * heal), and the actual HP only moves 800 ms later, when {@code RM_MAGHEALING} arrives.
   *
   * <p>Two deliberate scope decisions, both documented in the W40 plan:
   *
   * <ul>
   *   <li>{@code IsProperFriend} (ObjBase.pas:24091) branches on the caster's attack mode, which
   *       this port does not model yet. The engine therefore uses the strictest arm —
   *       {@code HAM_GROUP}: the caster plus live party members, never a stranger, a monster or
   *       an NPC. Widening it needs CM_CHANGEATTACKMODE first.
   *   <li>Delphi never re-checks the relationship when the delayed message lands. Because the
   *       friend rule here <em>is</em> party membership, which can change inside those 800 ms,
   *       {@link MagicImpactKind#AREA_HEAL} re-confirms it on arrival instead of healing a
   *       player who already left the party.
   * </ul>
   *
   * <p>{@code CretInNearXY} (ObjBase.pas:16854) still snaps the click onto the named object when
   * it stands within one cell, so the square — and the trailing RM_MAGICFIRE — center on the
   * object's cell rather than the raw click.
   */
  private void castAreaHealing(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId, long now) {
    WorldObject clicked = targetId != 0 ? findObject(targetId) : null;
    boolean snapped = clicked != null && clicked.map() == player.map
        && chebyshev(clicked.position(), requestedTarget) <= 1;
    Position center = snapped ? clicked.position() : requestedTarget;
    int firedTargetId = snapped && clicked.ability().alive() ? clicked.id() : 0;

    int power = rollHealingPower(player, skill, magic);
    List<WorldObject> candidates = new ArrayList<>();
    for (int id : player.map.objectsInSquare(center, BIG_HEALING_RANGE)) {
      WorldObject candidate = findObject(id);
      if (candidate != null) candidates.add(candidate);
    }
    List<AreaHealing.Result> healed = AreaHealing.resolve(center, BIG_HEALING_RANGE, power,
        candidates, target -> isAreaHealFriend(player, target),
        target -> target.ability().hp(), target -> target.ability().maxHp());
    for (AreaHealing.Result result : healed) {
      WorldObject target = findObject(result.objectId());
      if (target == null) continue;
      pendingMagicImpacts.add(new PendingMagicImpact(
          now + HEAL_IMPACT_DELAY_MILLIS, MagicImpactKind.AREA_HEAL,
          player.id, target.id(), target.position(), power));
    }

    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, center, firedTargetId, magic));
    // boTrain is only set when at least one friend was actually below max HP (Magic.pas:186).
    if (!healed.isEmpty()) trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code SKILL_FIREBOOM}(23, 爆裂火焰, Magic.pas:510 → {@code MagBigExplosion}, Magic.pas:1170):
   * the click is optionally snapped to a nearby named object, then every live proper target in
   * the inclusive square gets one ordinary (non-delayed) {@code RM_MAGSTRUCK} carrying the same
   * power. There is no per-target anti-magic-resist roll, and delivery stays bound to the object
   * even if it moves out of the square. The configured radius defaults to one and ranges from 1
   * through 12, matching {@code Setup.FireBoomRage}.
   *
   * <p>The shared roll is {@code GetAttackPower(GetPower(MPow) + LoWord(MC),
   * SmallInt(HiWord(MC)-LoWord(MC)) + 1)}. The second argument is a <em>power spread</em>, not
   * the high MC endpoint; the extra inclusive point is intentional. Luck/UnLuck use the existing
   * {@link #attackPower(int, int, int)} port. Delphi's player-only power-rate and color modifiers
   * are not modeled by the current player state and remain outside this slice.
   *
   * <p>{@code IsProperTarget} uses the current hostility slice: alive, same map, not the caster,
   * and not an NPC. Attack/protection modes, slave ownership and an explicit ghost flag are not
   * represented by the Java object model; dead objects are rejected and ghosts are absent from
   * map occupancy.
   *
   * <p>W44 shares this whole shape with {@code SKILL_SNOWWIND}; only {@code nRage} differs, so
   * both branches funnel into {@link #castBigExplosion}.
   */
  private void castAreaExplosion(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId, long now) {
    castBigExplosion(player, skill, magic, requestedTarget, targetId, now,
        config.fireBoomRange());
  }

  /**
   * {@code SKILL_SNOWWIND}(33, 冰咆哮, Magic.pas:578 → {@code MagBigExplosion}): the same case
   * branch as 爆裂火焰 — it evaluates the identical {@code GetAttackPower(...)} expression and
   * passes the click coordinates straight through — and the only difference in the Delphi source
   * is the last argument, {@code g_Config.nSnowWindRange} instead of
   * {@code g_Config.nFireBoomRage}. Everything 爆裂火焰's slice established therefore carries
   * over verbatim: {@code CretInNearXY} snapping, one shared power roll, {@code SetTargetCreat}
   * before the object-bound non-delayed {@code RM_MAGSTRUCK}, no anti-magic-resist gate, and
   * {@code boTrain} set by any proper target even when MAC later absorbs the hit.
   *
   * <p>{@code nSnowWindRange} ships at one (M2Share.pas:2078, {@code !Setup.txt:296}), with the
   * {@code FunctionConfig.dfm} spin edit bounded to 1–12 — the same limits as FireBoom, so a
   * 3x3 square is the default blast.
   */
  private void castSnowWind(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId, long now) {
    castBigExplosion(player, skill, magic, requestedTarget, targetId, now,
        config.snowWindRange());
  }

  /**
   * The shared {@code MagBigExplosion} body (Magic.pas:1170) used by 爆裂火焰 and 冰咆哮: the
   * click is optionally snapped to a nearby named object, then every live proper target in the
   * inclusive square of the given radius gets one ordinary (non-delayed) {@code RM_MAGSTRUCK}
   * carrying the same power. Delivery stays bound to the object even if it walks out of the
   * square afterwards.
   */
  private void castBigExplosion(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId, long now, int range) {
    WorldObject clicked = targetId != 0 ? findObject(targetId) : null;
    boolean snapped = clicked != null && clicked.map() == player.map
        && chebyshev(clicked.position(), requestedTarget) <= 1;
    Position center = snapped ? clicked.position() : requestedTarget;
    int firedTargetId = snapped && clicked.ability().alive() ? clicked.id() : 0;

    // MagBigExplosion evaluates nPower before it searches the square, even when nobody is hit.
    int power = rollMcAttackPower(player, skill, magic);
    int hits = 0;
    for (int id : player.map.objectsInSquare(center, range)) {
      WorldObject candidate = findObject(id);
      if (!isProperTarget(player, candidate)) continue;
      // MagBigExplosion calls SetTargetCreat before SendMsg(RM_MAGSTRUCK).
      if (candidate instanceof Monster monster) monster.targetId = player.id;
      pendingMagicImpacts.add(new PendingMagicImpact(
          now, MagicImpactKind.AREA_DAMAGE, player.id, candidate.id(), candidate.position(), power));
      hits++;
    }

    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, center, firedTargetId, magic));
    // DoSpell's boTrain is the MagBigExplosion result: any proper target trains even when MAC
    // later absorbs the entire hit.
    if (hits > 0) trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code SKILL_LIGHTFLOWER}(24, 地狱雷光, Magic.pas:518 → {@code MagElecBlizzard},
   * Magic.pas:1191): a square of {@code Setup.ElecBlizzardRange} cells around the
   * <em>caster</em> — the click never selects the blast cell, it only travels on the trailing
   * {@code RM_MAGICFIRE} frame. Every proper target takes one non-delayed {@code RM_MAGSTRUCK}
   * whose damage is the single rolled nPower for {@code LA_UNDEAD} targets and {@code nPower
   * div 10} for everything else. Unlike FireBoom there is no {@code SetTargetCreat} (the call
   * is commented out in the source) and no resist roll; the low-level animal walk pause still
   * applies because it lives inside the {@code RM_MAGSTRUCK} handler.
   *
   * <p>The power is rolled once per cast, before the square walk, even when the square is
   * empty, and any proper target sets Delphi's {@code Result := True} — the {@code boTrain}
   * signal — regardless of whether the target's MAC later absorbs the whole hit.
   */
  private void castElecBlizzard(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId, long now) {
    // ClientSpellXY's CretInNearXY (ObjBase.pas:16854) snaps the click onto a referenced object
    // within one cell before DoSpell runs; the snap steers the RM_MAGICFIRE broadcast only.
    WorldObject clicked = targetId != 0 ? findObject(targetId) : null;
    boolean snapped = clicked != null && clicked.map() == player.map
        && chebyshev(clicked.position(), requestedTarget) <= 1;
    Position firedAt = snapped ? clicked.position() : requestedTarget;
    int firedTargetId = snapped && clicked.ability().alive() ? clicked.id() : 0;

    int power = rollMcAttackPower(player, skill, magic);
    int hits = 0;
    for (int id : player.map.objectsInSquare(player.position, config.elecBlizzardRange())) {
      WorldObject candidate = findObject(id);
      if (!isProperTarget(player, candidate)) continue;
      int struck = isUndead(candidate) ? power : power / 10;
      pendingMagicImpacts.add(new PendingMagicImpact(
          now, MagicImpactKind.AREA_DAMAGE, player.id, candidate.id(), candidate.position(),
          struck));
      hits++;
    }

    emitToObserversAndSelf(player, new WorldEvent.MagicFired(
        player.id, firedAt, firedTargetId, magic));
    if (hits > 0) trainSpellSkill(player, skill, magic);
  }

  /**
   * {@code SKILL_FIREWIND}(8, 抗拒火环, Magic.pas:384 → {@code MagPushArround}, Magic.pas:146):
   * the first wizard skill that neither damages nor heals anything. The whole case body is
   * {@code if MagPushArround(PlayObject, UserMagic.btLevel) > 0 then boTrain := True;}, so the
   * click coordinates and the bound {@code TargeTBaseObject} are never read — they only ride
   * along on the trailing RM_MAGICFIRE frame exactly as {@code ClientSpellXY} left them (the
   * skill does not clear {@code boSpellFire}).
   *
   * <p>{@code MagPushArround} walks {@code PlayObject.m_VisibleActors} — not the map square —
   * and applies the following filter chain per object, in source order:
   *
   * <pre>
   *   (abs(dx) &lt;= 1) and (abs(dy) &lt;= 1)                      // inclusive 3x3 around the caster
   *   and (not m_boDeath) and (BaseObject &lt;&gt; PlayObject)
   *   and (PlayObject.m_Abil.Level &gt; BaseObject.m_Abil.Level) // strict; the level gap feeds
   *   and (not BaseObject.m_boStickMode)                       //   the random gate below
   *   and (Random(20) &lt; 6 + nPushLevel * 3 + levelgap)         // rolled *before* IsProperTarget
   *   and PlayObject.IsProperTarget(BaseObject)
   * then push := 1 + _MAX(0, nPushLevel - 1) + Random(2);
   *      CharPushed(GetNextDirection(caster, target), push); Inc(Result);
   * </pre>
   *
   * <p>Two quirks are kept verbatim. The gate roll is drawn <em>before</em> the proper-target
   * test, so an NPC (never a proper target) still consumes one draw once it cleared the level
   * gate. And {@code Inc(Result)} sits outside {@code CharPushed}'s own return value, so a push
   * that immediately hits a wall still counts as a push — and therefore still trains the skill —
   * even though the victim never moved.
   */
  private void castPushArround(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId) {
    // CretInNearXY snapping as in every other ground-target spell (ObjBase.pas:16854): the
    // snapped cell is only what the RM_MAGICFIRE frame carries.
    WorldObject clicked = targetId != 0 ? findObject(targetId) : null;
    boolean snapped = clicked != null && clicked.map() == player.map
        && chebyshev(clicked.position(), requestedTarget) <= 1;
    Position firedAt = snapped ? clicked.position() : requestedTarget;
    int firedTargetId = snapped && clicked.ability().alive() ? clicked.id() : 0;

    int skillLevel = skill.level();
    int pushed = 0;
    // m_VisibleActors, not GetMapBaseObjects: only objects already visible to the caster are
    // candidates, and `visibleIds` excludes the caster itself (BaseObject <> PlayObject).
    for (int id : visibleIds(player.map, player.position, player.id)) {
      WorldObject candidate = findObject(id);
      if (candidate == null) continue;
      if (Math.abs(player.position.x() - candidate.position().x()) > 1
          || Math.abs(player.position.y() - candidate.position().y()) > 1) continue;
      if (!candidate.ability().alive()) continue;
      // Delphi also requires `not BaseObject.m_boStickMode`; the Java object model has no such
      // field yet, so nothing is currently pinned — a documented boundary, not a behaviour.
      int levelGap = player.ability.level() - candidate.ability().level();
      if (levelGap <= 0) continue;
      if (random.nextInt(WorldRandom.Stream.PUSH_GATE, 20) >= 6 + skillLevel * 3 + levelGap)
        continue;
      if (!isProperTarget(player, candidate)) continue;
      int steps = 1 + Math.max(0, skillLevel - 1)
          + random.nextInt(WorldRandom.Stream.PUSH_DISTANCE, 2);
      Direction direction = Direction.getNextDirection(player.position, candidate.position());
      // CharPushed breaks out of its own step loop on the first blocked cell; the push has
      // already counted for boTrain by then (Delphi's Inc(Result) is unconditional).
      for (int step = 0; step < steps; step++) {
        if (charPushed(candidate, direction) != 1) break;
      }
      pushed++;
    }

    emitToObserversAndSelf(player, new WorldEvent.MagicFired(
        player.id, firedAt, firedTargetId, magic));
    // DoSpell's boTrain is `MagPushArround(...) > 0`; an empty 3x3 still costs the mana that
    // ClientSpellXY already spent before DoSpell ran.
    if (pushed > 0) trainSpellSkill(player, skill, magic);
  }

  /**
   * The {@code HAM_GROUP} arm of {@code IsProperFriend} (ObjBase.pas:24143): {@code cret = Self}
   * or {@code IsGroupMember(cret)}, and only for {@code RC_PLAYOBJECT} — a monster or NPC on the
   * square is never a friend, and neither is an unaffiliated player.
   */
  private static boolean isAreaHealFriend(Player caster, WorldObject candidate) {
    if (!(candidate instanceof Player target) || !target.ability.alive()
        || target.map != caster.map) return false;
    if (target.id == caster.id) return true;
    return caster.group != null && caster.group == target.group && caster.group.contains(target.id);
  }

  /** Magic.DB rows 9/10 — 地狱火/疾光电影: the two line-piercing wizard bolts of W38. */
  private static boolean isLinePiercingSkill(int magicId) {
    return magicId == SKILL_FIRE || magicId == SKILL_SHOOTLIGHTEN;
  }

  /**
   * {@code SKILL_FIRE}(9, 地狱火, Magic.pas:387) and {@code SKILL_SHOOTLIGHTEN}(10, 疾光电影,
   * Magic.pas:397): both branches share one shape — walk from one step ahead of the caster to
   * the beam end (5 cells for 地狱火, 8 for 疾光电影) through {@link #magPassThroughMagic},
   * rolling the exact fireball power formula
   * ({@code GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(MC), HiWord(MC)-LoWord(MC)+1)},
   * which {@link #rollFireballPower} already ports). The branches write the beam end back into
   * {@code nTargetX}/{@code nTargetY} (var parameters), so the trailing RM_MAGICFIRE broadcast
   * carries the <em>end of the beam</em> rather than the click — a mutation kept verbatim here.
   *
   * <p>{@code ClientSpellXY}'s {@code CretInNearXY} (ObjBase.pas:16854) first snaps the click
   * onto the referenced object when it stands within one cell; a snapped-but-dead object still
   * steers the beam, yet {@code DoSpell} nils it before the RM_MAGICFIRE target id is encoded
   * (Magic.pas:273), so the packet then carries 0.
   */
  private void castLinePiercingSpell(
      Player player, PlayerSkill skill, MagicDefinition magic, Position requestedTarget,
      int targetId, long now) {
    int reach = magic.id() == SKILL_FIRE ? FIRE_BEAM_REACH : SHOOT_LIGHTEN_BEAM_REACH;
    boolean undeadAttack = magic.id() == SKILL_SHOOTLIGHTEN;

    WorldObject clicked = targetId != 0 ? findObject(targetId) : null;
    boolean snapped = clicked != null && clicked.map() == player.map
        && chebyshev(clicked.position(), requestedTarget) <= 1;
    Position target = snapped ? clicked.position() : requestedTarget;
    int firedTargetId = snapped && clicked.ability().alive() ? clicked.id() : 0;

    Direction beam = Direction.getNextDirection(player.position, target);
    Optional<Position> first = nextQuirkyPosition(player.map, player.position, beam, 1);
    if (first.isPresent()) {
      // A foreshortened GetNextPosition leaves Delphi's var parameters untouched, so the
      // mutated pair only advances as far as the edge of the map allows.
      Position end = nextQuirkyPosition(player.map, player.position, beam, reach).orElse(target);
      if (magPassThroughMagic(player, first.get(), end,
          rollFireballPower(player, skill, magic), undeadAttack, now) > 0) {
        trainSpellSkill(player, skill, magic);
      }
      target = end;
    }
    emitToObserversAndSelf(player, new WorldEvent.MagicFired(player.id, target, firedTargetId, magic));
  }

  /**
   * {@code TBaseObject.MagPassThroughMagic} (ObjBase.pas:2536): walks cell by cell from
   * {@code start} (one step ahead of the caster) toward {@code end}, striking the first living
   * moving object on each cell — with no wall check and no stop-on-hit; the walk only ends on
   * the beam end, after {@link #MAG_PASS_THROUGH_MAX_STEPS} iterations, or when {@link
   * #nextQuirkyPosition} refuses to leave the map. Each proper victim answers the
   * {@code Random(10) >= m_nAntiMagic} resist and is then queued a 600 ms-delayed {@code RM_MAGSTRUCK}
   * bound to the <em>object</em> — Delphi has no “walked out of the beam in time” escape, which
   * is exactly what {@link MagicImpactKind#PIERCING_DAMAGE} models. For 疾光电影
   * ({@code undeadAttack}) the power variable itself is multiplied by 1.5 <em>once per proper
   * target</em> and stays mutated for every later cell (ObjBase.pas:2550) — a quirk reproduced
   * verbatim here.
   *
   * @return the Delphi {@code tCount}: how many targets survived the resist roll — the
   *     {@code boTrain} signal of the calling case branch.
   */
  private int magPassThroughMagic(
      Player caster, Position start, Position end, int power, boolean undeadAttack, long now) {
    int hits = 0;
    int cellPower = power;
    Position current = start;
    for (int step = 0; step < MAG_PASS_THROUGH_MAX_STEPS; step++) {
      WorldObject victim = objectAt(caster.map, current);
      // IsProperTarget: the same hostility slice this port keeps everywhere — alive, not self,
      // and never an NPC (IsAttackTarget is False for TNormNpc/TMerchant, ObjNpc.pas).
      if (victim != null && !(victim instanceof Npc) && victim.id() != caster.id
          && victim.ability().alive()
          && passesMagicResist(victim)) {
        if (undeadAttack) cellPower = (int) Math.rint(cellPower * MAGSTRUCK_UNDEAD_MULTIPLIER);
        pendingMagicImpacts.add(new PendingMagicImpact(
            now + PIERCING_IMPACT_DELAY_MILLIS, MagicImpactKind.PIERCING_DAMAGE,
            caster.id(), victim.id(), victim.position(), cellPower));
        hits++;
      }
      if (current.equals(end)) break;
      Optional<Position> next = nextQuirkyPosition(
          caster.map, current, Direction.getNextDirection(current, end), 1);
      if (next.isEmpty()) break;
      current = next.get();
    }
    return hits;
  }

  /**
   * {@code TEnvirnoment.GetNextPosition} (Envir.pas:1110): pure geometry with map-edge failure,
   * never a walkability check — a beam keeps punching through blocked terrain. The original
   * {@code case} carries copy-paste bound mixups that stay observable on non-square maps and
   * are kept verbatim: {@code DR_DOWN} bounds Y by the map <em>width</em> (not height), and
   * {@code DR_UPRIGHT}/{@code DR_DOWNLEFT} each test the two swapped edges.
   */
  private static Optional<Position> nextQuirkyPosition(
      GameMap map, Position from, Direction direction, int steps) {
    int x = from.x();
    int y = from.y();
    switch (direction) {
      case UP -> { if (y > steps - 1) y -= steps; }
      case DOWN -> { if (y < map.width() - steps) y += steps; }
      case LEFT -> { if (x > steps - 1) x -= steps; }
      case RIGHT -> { if (x < map.width() - steps) x += steps; }
      case UP_LEFT -> { if (x > steps - 1 && y > steps - 1) { x -= steps; y -= steps; } }
      case UP_RIGHT -> { if (x > steps - 1 && y < map.height() - steps) { x += steps; y -= steps; } }
      case DOWN_LEFT -> { if (x < map.width() - steps && y > steps - 1) { x -= steps; y += steps; } }
      case DOWN_RIGHT -> { if (x < map.width() - steps && y < map.height() - steps) { x += steps; y += steps; } }
    }
    return x == from.x() && y == from.y() ? Optional.empty() : Optional.of(new Position(x, y));
  }

  /**
   * The DoSpell training tail (Magic.pas:700): with {@code btLevel < 3} and at least one struck
   * target ({@code boTrain}), Delphi runs {@code TrainSkill(Random(3) + 1)} plus one
   * {@code CheckMagicLevelup} pass. {@link PlayerSkill#train} applies the
   * {@code TrainLevel[btLevel] <= Level} gate and keeps the remainder in {@code nTranPoint};
   * the event follows the W33 semantic shape, leaving the RM_MAGIC_LVEXP wire timing to the gate.
   */
  private void trainSpellSkill(Player player, PlayerSkill current, MagicDefinition magic) {
    if (current.level() >= MagicDefinition.MAX_SKILL_LEVEL) return;
    int points = random.nextInt(WorldRandom.Stream.SKILL_TRAIN, 3) + 1;
    PlayerSkill trained = current.train(magic, player.ability.level(), points);
    if (trained.equals(current)) return;
    player.skills.put(magic.id(), trained);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.skills.put(magic.id(), current);
      throw failure;
    }
    emit(player, new WorldEvent.SkillTrainingChanged(
        player.id, new LearnedMagic(trained, magic)));
  }

  private int rollMagicShieldSeconds(Player player, PlayerSkill skill, MagicDefinition magic) {
    int mc = random.between(WorldRandom.Stream.MAGIC,
        player.ability.minMc(), player.ability.maxMc());
    return getMagicPower(magic, skill.level(), mc + 15);
  }

  private int getMagicPower(MagicDefinition magic, int skillLevel, int rawPower) {
    return magic.scalePower(rawPower, skillLevel)
        + rollExclusive(magic.defPower(), magic.defMaxPower());
  }

  /** Delphi Random(max-min) excludes max and consumes no draw for a flat range. */
  private int rollExclusive(int min, int max) {
    return max <= min ? min : min + random.nextInt(WorldRandom.Stream.MAGIC, max - min);
  }

  private AttackResult attackWith(
      int playerId, Position claimedPosition, Direction direction, AttackKind attack) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) return rejectAttack(player, WorldEvent.AttackRejection.ACTOR_DEAD);
    if (!player.position.equals(claimedPosition))
      return rejectAttack(player, WorldEvent.AttackRejection.POSITION_MISMATCH);
    long now = clock.getAsLong();
    if (now - player.lastAttackAt < config.hitIntervalMillis())
      return rejectAttack(player, WorldEvent.AttackRejection.TOO_FAST);

    player.lastAttackAt = now;
    player.direction = direction;
    WorldObjectSnapshot attacker = player.snapshot();
    emit(player, new WorldEvent.AttackAccepted(attacker, attack));
    // AttackDir (ObjBase.pas:18826) snapshots m_boPowerHit *before* _Attack consumes it and
    // maps wHitMode 3 to RM_SPELL2 only when the flag was armed; an unarmed CM_POWERHIT
    // broadcasts a plain RM_HIT. The consumption itself happens inside _Attack for both the
    // "hit something" and the "swung at air" branches (ObjBase.pas:22122 / 22145).
    boolean powerHit = attack == AttackKind.POWER_HIT && player.powerHit;
    if (attack == AttackKind.POWER_HIT) player.powerHit = false;
    // 烈火剑法 (ObjBase.pas:22128 / 22152): both _Attack branches clear m_boFireHitSkill and
    // restamp m_dwLatestFireHitTick, so a swing at air burns the charge too (禁止双烈火) — only
    // the branch that actually found a target adds the damage. AttackDir snapshots the flag
    // beforehand for the wIdent choice, exactly like m_boPowerHit.
    boolean fireHit = attack == AttackKind.FIRE_HIT && player.fireHitArmed;
    if (fireHit) {
      player.fireHitArmed = false;
      player.lastFireHitAt = now;
    }
    // 刺杀剑术/半月弯刀 only drive their shape when the book is read (and, for 半月, MP > 0);
    // otherwise AttackDir degrades wHitMode to RM_HIT (ObjBase.pas:18790/18845) and the swing is
    // an ordinary hit. A LONG/WIDE ident never arms/consumes m_boPowerHit (that is wHitMode 3).
    WeaponShape shape = activeShape(player, attack);
    AttackKind broadcast = broadcastKind(attack, powerHit, fireHit, shape);
    WorldEvent swing = new WorldEvent.ObjectAttacked(attacker, broadcast);
    for (int viewerId : visibleIds(player.map, player.position, player.id)) emit(players.get(viewerId), swing);

    Position front = player.position.translate(direction, 1);
    WorldObject target = objectAt(player.map, front);
    // IsAttackTarget is False for TNormNpc/TMerchant (ObjNpc.pas), so a swing at an
    // NPC's cell connects with nothing — the minimal NPC slice keeps them decorative.
    boolean livingTarget = !(target == null || target instanceof Npc || !target.ability().alive());

    if (shape == null) {
      // ---- W03 single-cell melee (HIT/HEAVY/BIG/POWER): unchanged draw sequence ----
      if (!livingTarget) {
        advancePowerHitCadence(player);
        return AttackResult.missed(attacker);
      }
      // m_nLuck = sum of worn Luck minus UnLuck (RecalcAbilitys, ObjBase.pas:3401); with no gear
      // it is zero and rollDamage draws exactly as before.
      int damage = rollDamage(player.ability, target.ability(), playerLuck(player),
          powerHit ? player.hitPlus : 0, fireHit ? player.hitDouble : 0, player, target);
      damage = applyMagicShield(target, damage);
      applyDamage(target, player, damage);
      if (damage > 0) {
        // ObjBase.pas:22283 trains the active passive weapon skill only after a penetrating hit.
        trainPassiveMeleeSkill(player);
        // ObjBase.pas:22354 trains 烈火剑法 by a flat point on any wHitMode 7 swing that landed,
        // whether or not the burst itself was armed — the guard is the hit mode, not the flag.
        if (attack == AttackKind.FIRE_HIT) trainActiveShapeSkill(player, HitSpeed.SKILL_FIRESWORD);
      }
      // ObjBase.pas:22252 rolls `nWeaponDamage := Random(5) + 2` inside the *pre-AC* `nPower > 0`
      // block, so Delphi also wears the weapon on a blow that AC fully absorbs; the engine has
      // always tested the post-AC figure instead and that simplification is left alone here.
      // A dodged swing zeroes nPower ahead of both tests and therefore wears nothing at all.
      if (damage > 0) damageEquipment(player, EquipmentSlot.WEAPON,
          random.nextInt(WorldRandom.Stream.EQUIPMENT_WEAR, 5) + 2);
      advancePowerHitCadence(player);
      return new AttackResult(true, attacker, target.snapshot(), damage);
    }

    // ---- 刺杀剑术/半月弯刀 special attack shape (_Attack, ObjBase.pas:22169-22200) ----
    // AttackDir spends 半月's mana before _Attack runs (DamageSpell + HealthSpellChanged); the
    // thrust has no mana cost. Both happen even on a swing at air, matching Delphi.
    if (shape.consumesMana) consumeSkillMana(player, shape.magicId);
    // nPower is rolled once at the top of _Attack and shared by the secondary shape (nSecPwr) and
    // the primary front target — a single GetAttackPower draw, exactly as Delphi does.
    int power = attackPower(player.ability.minDc(), player.ability.maxDc(), playerLuck(player));
    int skillLevel = player.skills.get(shape.magicId).level();
    applyShapeSecondaries(player, direction, shape, shape.secondaryPower(power, skillLevel));

    if (!livingTarget) {
      advancePowerHitCadence(player);
      return AttackResult.missed(attacker);
    }
    // Primary front target: the dodge check and AC roll of an ordinary swing, reusing the shared
    // nPower instead of drawing a second one (Delphi: GetHitStruckDamage(Self, nPower)).
    boolean evaded = meleeEvaded(player, target);
    int defence = randomBetween(target.ability().minAc(), target.ability().maxAc());
    int damage = evaded ? 0 : Math.max(0, power - defence);
    damage = applyMagicShield(target, damage);
    applyDamage(target, player, damage);
    if (damage > 0) {
      // A penetrating primary hit trains the passive 准确 skill (Random(3)+1) *and* the active
      // shape (flat +1, gated on wHitMode) (ObjBase.pas:22283/22319).
      trainPassiveMeleeSkill(player);
      trainActiveShapeSkill(player, shape.magicId);
      damageEquipment(player, EquipmentSlot.WEAPON,
          random.nextInt(WorldRandom.Stream.EQUIPMENT_WEAR, 5) + 2);
    }
    advancePowerHitCadence(player);
    return new AttackResult(true, attacker, target.snapshot(), damage);
  }

  /**
   * The two active weapon-skill shapes of this slice (ObjBase.pas:22169-22200). {@code
   * divisorOffset} is the {@code +2}/{@code +10} added to {@code btTrainLv} in the {@code nSecPwr}
   * formula, and {@code consumesMana} marks 半月弯刀 whose {@code AttackDir} spends
   * {@code DamageSpell} before the swing. 双龙斩 (CrsWideAttack, SKILL_CROSSMOON=34) is deferred:
   * its Magic.DB row is outside the vetted 1..33 catalog this server loads.
   */
  private enum WeaponShape {
    THRUSTING(HitSpeed.SKILL_ERGUM, 2, false),
    HALF_MOON(HitSpeed.SKILL_BANWOL, 10, true);

    private final int magicId;
    private final int divisorOffset;
    private final boolean consumesMana;

    WeaponShape(int magicId, int divisorOffset, boolean consumesMana) {
      this.magicId = magicId;
      this.divisorOffset = divisorOffset;
      this.consumesMana = consumesMana;
    }

    /** {@code nSecPwr := Round(nPower / (btTrainLv + offset) * (btLevel + 2))}. */
    int secondaryPower(int power, int skillLevel) {
      int divisor = MagicDefinition.HARDCODED_TRAIN_LEVEL + divisorOffset;
      int base = (int) Math.rint((double) power / divisor * (skillLevel + 2));
      // SwordLongAttack then scales by nSwordLongPowerRate/100 (ObjBase.pas:22012); 半月 has no
      // such knob. Both collapse to base under the shipped 100% rate.
      return this == THRUSTING
          ? (int) Math.rint((double) base * SWORD_LONG_POWER_RATE / 100.0)
          : base;
    }
  }

  /**
   * The active weapon shape a swing resolves to, or {@code null} for an ordinary hit. 刺杀 needs
   * only the learned book; 半月 additionally needs {@code m_WAbil.MP > 0}, otherwise AttackDir
   * degrades wHitMode to RM_HIT (ObjBase.pas:18790).
   */
  private WeaponShape activeShape(Player player, AttackKind attack) {
    if (attack == AttackKind.LONG_HIT && player.skills.containsKey(HitSpeed.SKILL_ERGUM))
      return WeaponShape.THRUSTING;
    if (attack == AttackKind.WIDE_HIT && player.skills.containsKey(HitSpeed.SKILL_BANWOL)
        && player.ability.mp() > 0)
      return WeaponShape.HALF_MOON;
    return null;
  }

  /**
   * {@code AttackDir}'s wIdent selection (ObjBase.pas:18841): wHitMode 3 answers RM_SPELL2 only
   * while a power hit is armed, and the 4/5 special idents only when their skill drives the swing;
   * everything else broadcasts a plain RM_HIT.
   */
  private static AttackKind broadcastKind(
      AttackKind attack, boolean powerHit, boolean fireHit, WeaponShape shape) {
    return switch (attack) {
      case POWER_HIT -> powerHit ? AttackKind.POWER_HIT : AttackKind.HIT;
      // `7: if boFireHit then wIdent := RM_FIREHIT` — an unarmed 烈火 swing looks like a plain hit.
      case FIRE_HIT -> fireHit ? AttackKind.FIRE_HIT : AttackKind.HIT;
      case LONG_HIT, WIDE_HIT -> shape == null ? AttackKind.HIT : attack;
      default -> attack;
    };
  }

  /**
   * The geometry of {@code SwordLongAttack}/{@code SwordWideAttack} (ObjBase.pas:22005/22028):
   * 刺杀 strikes the single cell two tiles ahead, 半月 sweeps the {@code WideAttack} fan of three
   * cells (dir-1, dir+1, dir+2). Each cell is resolved with {@code DirectAttack}.
   */
  private void applyShapeSecondaries(
      Player player, Direction direction, WeaponShape shape, int secondaryPower) {
    if (secondaryPower <= 0) return;
    if (shape == WeaponShape.THRUSTING) {
      attackSecondaryCell(player, player.position.translate(direction, 2), secondaryPower);
      return;
    }
    for (int offset : WIDE_ATTACK_OFFSETS) {
      Direction swing = Direction.fromCode((direction.code() + offset) % 8);
      attackSecondaryCell(player, player.position.translate(swing, 1), secondaryPower);
    }
  }

  /**
   * {@code DirectAttack} (ObjBase.pas:21969): proper-target + dodge, then a no-AC
   * {@code StruckDamage}. The secondary power lands whole (no armour roll), and — unlike the
   * primary — there is no {@code if target.m_btHitPoint > 0} guard, so even a 0-准确 target rolls
   * the dodge.
   */
  private void attackSecondaryCell(Player player, Position cell, int secondaryPower) {
    WorldObject target = objectAt(player.map, cell);
    if (target == null || target instanceof Npc || target.id() == player.id
        || !target.ability().alive()) return;
    if (!directAttackHits(player, target)) return;
    applyDamage(target, player, secondaryPower);
  }

  /** {@code if Random(BaseObject.m_btSpeedPoint) < m_btHitPoint} (ObjBase.pas:21977). */
  private boolean directAttackHits(WorldObject attacker, WorldObject target) {
    int dodge = random.nextInt(WorldRandom.Stream.ACCURACY, Math.max(1, target.speedPoint()));
    return dodge < attacker.hitPoint();
  }

  /**
   * {@code DamageSpell(btDefSpell + GetMagicSpell)} + {@code HealthSpellChanged}
   * (ObjBase.pas:18790), which is exactly {@link MagicDefinition#manaCost(int)} clamped at zero.
   */
  private void consumeSkillMana(Player player, int magicId) {
    PlayerSkill skill = player.skills.get(magicId);
    MagicDefinition magic = magicCatalog.require(magicId);
    int mp = Math.max(0, player.ability.mp() - magic.manaCost(skill.level()));
    if (mp == player.ability.mp()) return;
    Ability before = player.ability;
    player.setAbility(player.ability.withMp(mp));
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.setAbility(before);
      throw failure;
    }
    emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
  }

  /**
   * {@code TrainSkill(skill, 1)} + one {@code CheckMagicLevelup} for the active shape after a
   * penetrating primary hit (ObjBase.pas:22319/22336): a flat single point, unlike the passive
   * skills' {@code Random(3)+1}. {@link PlayerSkill#train} applies the level &lt; 3 / TrainLevel
   * gates and keeps the remainder in {@code nTranPoint}.
   */
  private void trainActiveShapeSkill(Player player, int magicId) {
    PlayerSkill current = player.skills.get(magicId);
    if (current == null || current.level() >= MagicDefinition.MAX_SKILL_LEVEL) return;
    MagicDefinition definition = magicCatalog.require(magicId);
    PlayerSkill trained = current.train(definition, player.ability.level(), 1);
    if (trained.equals(current)) return;
    player.skills.put(magicId, trained);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.skills.put(magicId, current);
      throw failure;
    }
    emit(player, new WorldEvent.SkillTrainingChanged(
        player.id, new LearnedMagic(trained, definition)));
  }

  /**
   * The 攻杀剑术 cadence block of {@code TPlayObject.ClientAttack} (ObjBase.pas:8861), which
   * runs after {@code AttackDir} for every melee ident — a missed swing still advances it.
   *
   * <pre>
   *   if (m_MagicPowerHitSkill &lt;&gt; nil) and (m_UseItems[U_WEAPON].Dura &gt; 0) then begin
   *     Dec(m_btAttackSkillCount);
   *     if m_btAttackSkillPointCount = m_btAttackSkillCount then begin
   *       m_boPowerHit := True;
   *       SendSocket(nil, '+PWR');
   *     end;
   *     if m_btAttackSkillCount &lt;= 0 then begin
   *       m_btAttackSkillCount := 7 - m_MagicPowerHitSkill.btLevel;
   *       m_btAttackSkillPointCount := Random(m_btAttackSkillCount);
   *     end;
   *   end;
   * </pre>
   *
   * <p>The {@code '+PWR'} tag frame is what makes the 1.76 client send {@code CM_POWERHIT} on
   * its next swing ({@code g_boNextTimePowerHit}, ClMain.pas:3620 → 2128), so the whole feature
   * is invisible without it.
   */
  private void advancePowerHitCadence(Player player) {
    PlayerSkill yedo = player.skills.get(HitSpeed.SKILL_YEDO);
    if (yedo == null) return;
    // m_UseItems[U_WEAPON].Dura > 0: a broken (or absent) weapon suspends the cycle entirely.
    if (player.equipment.at(EquipmentSlot.WEAPON).map(BackpackItem::dura).orElse(0) <= 0) return;
    player.attackSkillCount--;
    if (player.attackSkillPointCount == player.attackSkillCount) {
      player.powerHit = true;
      emit(player, new WorldEvent.PowerHitReady(player.id));
    }
    if (player.attackSkillCount <= 0) {
      int cycle = HitSpeed.attackSkillCycle(yedo.level());
      player.attackSkillCount = cycle;
      player.attackSkillPointCount = random.nextInt(WorldRandom.Stream.POWER_HIT, cycle);
    }
  }

  private boolean pickUpItem(int playerId, Position claimedPosition) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.ACTOR_DEAD));
      return false;
    }
    if (!player.position.equals(claimedPosition)) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.NO_ITEM));
      return false;
    }
    GroundItem item = newestItemOn(player.map.id(), player.position);
    if (item == null) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.NO_ITEM));
      return false;
    }
    if (item.gold()) return pickUpGold(player, item);
    if (player.backpack.size() >= PlayerState.MAX_BACKPACK_ITEMS) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.BACKPACK_FULL));
      return false;
    }

    BackpackItem backpackItem = BackpackItem.of(
        itemDatabase.find(item.name()).orElseGet(() -> StdItem.placeholder(item.name(), item.looks())),
        allocateMakeIndex());
    player.backpack.add(backpackItem);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.removeLast();
      throw failure;
    }
    groundItems.remove(item.id());
    itemDropTimes.remove(item.id());
    emit(player, new WorldEvent.ItemPickedUp(player.id, item, backpackItem));
    WorldEvent hidden = new WorldEvent.ItemDisappeared(item);
    for (int viewerId : visibleIds(player.map, item.position(), 0)) emit(players.get(viewerId), hidden);
    return true;
  }

  /**
   * Gold piles are {@code TMapItem} rows named {@code 金币}. Picking one up calls Delphi's
   * {@code IncGold}: the whole pile is accepted only if it fits within {@code nHumanMaxGold};
   * otherwise the pile remains on the floor and the client action fails.
   */
  private boolean pickUpGold(Player player, GroundItem item) {
    long nextGold = player.gold + item.count();
    if (nextGold > PlayerState.MAX_GOLD) {
      emit(player, new WorldEvent.PickupRejected(player.id, WorldEvent.PickupRejection.WALLET_FULL));
      return false;
    }
    long previousGold = player.gold;
    player.gold = nextGold;
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.gold = previousGold;
      throw failure;
    }
    groundItems.remove(item.id());
    itemDropTimes.remove(item.id());
    emit(player, new WorldEvent.GoldPickedUp(player.id, item, player.gold));
    WorldEvent hidden = new WorldEvent.ItemDisappeared(item);
    for (int viewerId : visibleIds(player.map, item.position(), 0)) emit(players.get(viewerId), hidden);
    return true;
  }

  /**
   * {@code TPlayObject.ClientTakeOnItems} (ObjBase.pas:17072). The Delphi handler locates the
   * bag entry by MakeIndex <em>and</em> a case-insensitive name comparison, validates the slot
   * with {@code CheckUserItems} and the wearer with {@code CheckTakeOnItems}, then swaps any
   * item already in the slot back into the bag before recalculating abilities.
   */
  private boolean equipItem(int playerId, int slotIndex, int makeIndex, String itemName) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.EquipRejected(player.id, -1, WorldEvent.EquipRejection.ACTOR_DEAD));
      return false;
    }
    if (!EquipmentSlot.isValidIndex(slotIndex)) {
      emit(player, new WorldEvent.EquipRejected(player.id, -1, WorldEvent.EquipRejection.INVALID_SLOT));
      return false;
    }
    EquipmentSlot slot = EquipmentSlot.fromIndex(slotIndex);
    int bagIndex = findBagItem(player, makeIndex, itemName);
    if (bagIndex < 0) {
      emit(player, new WorldEvent.EquipRejected(player.id, -1, WorldEvent.EquipRejection.NO_SUCH_ITEM));
      return false;
    }
    BackpackItem candidate = player.backpack.get(bagIndex);
    if (!slot.accepts(candidate.item())) {
      emit(player, new WorldEvent.EquipRejected(player.id, -1, WorldEvent.EquipRejection.SLOT_MISMATCH));
      return false;
    }
    if (!EquipRequirement.check(slot, candidate.item(), requirementView(player),
        wornWeightExcluding(player, slot)).allowed()) {
      emit(player, new WorldEvent.EquipRejected(
          player.id, -1, WorldEvent.EquipRejection.REQUIREMENT_NOT_MET));
      return false;
    }
    // Delphi refuses the whole take-on when the occupant of the slot is locked (n18 = -4).
    BackpackItem displaced = player.equipment.at(slot).orElse(null);
    if (displaced != null && isLockedInPlace(displaced)) {
      emit(player, new WorldEvent.EquipRejected(
          player.id, -4, WorldEvent.EquipRejection.CANNOT_TAKE_OFF_EXISTING));
      return false;
    }
    // A swap needs the freed bag slot, so capacity can only be exceeded when nothing is
    // displaced — which cannot happen, the incoming item already occupies a bag slot.
    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
    Equipment previousEquipment = player.equipment;
    Ability previousAbility = player.ability;

    player.backpack.remove(bagIndex);
    if (displaced != null) player.backpack.add(displaced);
    player.equipment = player.equipment.with(slot, candidate);
    recalculateAbilities(player);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      player.equipment = previousEquipment;
      player.ability = previousAbility;
      throw failure;
    }
    emitEquipmentChange(player,
        new WorldEvent.ItemEquipped(player.id, slot, candidate, player.feature(), player.featureEx()));
    return true;
  }

  /**
   * {@code TPlayObject.ClientTakeOffItems} (ObjBase.pas:17221): the reverse move, gated on the
   * same lock checks plus bag capacity.
   */
  private boolean unequipItem(int playerId, int slotIndex, int makeIndex, String itemName) {
    Player player = requirePlayer(playerId);
    if (!EquipmentSlot.isValidIndex(slotIndex)) {
      emit(player, new WorldEvent.UnequipRejected(
          player.id, -1, WorldEvent.UnequipRejection.BUSY_OR_INVALID_SLOT));
      return false;
    }
    EquipmentSlot slot = EquipmentSlot.fromIndex(slotIndex);
    BackpackItem worn = player.equipment.at(slot).orElse(null);
    // Delphi reports an empty slot and a MakeIndex/name mismatch through the same path.
    if (worn == null || worn.makeIndex() != makeIndex || !worn.name().equalsIgnoreCase(itemName)) {
      emit(player, new WorldEvent.UnequipRejected(
          player.id, -2, WorldEvent.UnequipRejection.SLOT_EMPTY));
      return false;
    }
    if (isLockedInPlace(worn)) {
      emit(player, new WorldEvent.UnequipRejected(
          player.id, -4, WorldEvent.UnequipRejection.CANNOT_TAKE_OFF));
      return false;
    }
    if (player.backpack.size() >= PlayerState.MAX_BACKPACK_ITEMS) {
      emit(player, new WorldEvent.UnequipRejected(
          player.id, -3, WorldEvent.UnequipRejection.BACKPACK_FULL));
      return false;
    }

    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
    Equipment previousEquipment = player.equipment;
    Ability previousAbility = player.ability;

    player.equipment = player.equipment.without(slot);
    player.backpack.add(worn);
    recalculateAbilities(player);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      player.equipment = previousEquipment;
      player.ability = previousAbility;
      throw failure;
    }
    emitEquipmentChange(player,
        new WorldEvent.ItemUnequipped(player.id, slot, worn, player.feature(), player.featureEx()));
    return true;
  }

  /**
   * {@code TPlayObject.ClientUseItems} (ObjBase.pas:17300): drinkable StdModes 0-3 plus
   * StdMode 4 books through {@code ReadBook}. StdMode 31 unpacking remains deferred.
   */
  private boolean consumeItem(int playerId, int makeIndex, String itemName) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.UseItemRejected(player.id, WorldEvent.UseItemRejection.ACTOR_DEAD));
      return false;
    }
    int bagIndex = findBagItem(player, makeIndex, itemName);
    if (bagIndex < 0) {
      emit(player, new WorldEvent.UseItemRejected(player.id, WorldEvent.UseItemRejection.NO_SUCH_ITEM));
      return false;
    }
    BackpackItem item = player.backpack.get(bagIndex);
    int stdMode = item.item().stdMode();
    if (stdMode == 4) return readSkillBook(player, bagIndex, item);
    if (stdMode > 3) {
      emit(player, new WorldEvent.UseItemRejected(player.id, WorldEvent.UseItemRejection.NOT_CONSUMABLE));
      return false;
    }
    if (player.map.flags().isNoDrug()) {
      emit(player, new WorldEvent.UseItemRejected(
          player.id, WorldEvent.UseItemRejection.MAP_FORBIDS_DRUGS));
      return false;
    }

    // EatItems StdMode 0 Shape<>1/2: the AC/MAC dwords are the HP/MP restore amounts, applied
    // immediately by IncHealthSpell (ObjBase.pas:3615), which clamps at the maxima.
    int restoreHp = stdMode == 0 ? (int) Math.min(item.item().ac(), Integer.MAX_VALUE) : 0;
    int restoreMp = stdMode == 0 ? (int) Math.min(item.item().mac(), Integer.MAX_VALUE) : 0;
    Ability previousAbility = player.ability;
    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);

    player.backpack.remove(bagIndex);
    if (restoreHp > 0 || restoreMp > 0) {
      player.ability = player.ability
          .withHp(player.ability.hp() + restoreHp)
          .withMp(player.ability.mp() + restoreMp);
    }
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      player.ability = previousAbility;
      throw failure;
    }
    int healedHp = player.ability.hp() - previousAbility.hp();
    int healedMp = player.ability.mp() - previousAbility.mp();
    emit(player, new WorldEvent.ItemUsed(player.id, item, healedHp, healedMp));
    if (healedHp != 0 || healedMp != 0) {
      emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
    }
    // ClientUseItems ends in WeightChanged() because the bag just got lighter.
    emitWeight(player);
    return true;
  }

  /** {@code ReadBook}: learn by exact item/magic name, with job and NeedL1 validation. */
  private boolean readSkillBook(Player player, int bagIndex, BackpackItem book) {
    MagicDefinition definition = magicCatalog.find(book.name()).orElse(null);
    if (definition == null || player.skills.containsKey(definition.id())
        || (definition.job() != MagicDefinition.ANY_JOB && definition.job() != player.job)
        || player.ability.level() < definition.requiredLevel(0)) {
      emit(player, new WorldEvent.UseItemRejected(player.id, WorldEvent.UseItemRejection.NOT_CONSUMABLE));
      return false;
    }

    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
    player.backpack.remove(bagIndex);
    PlayerSkill skill = PlayerSkill.learned(definition.id());
    player.skills.put(skill.magicId(), skill);
    // ObjBase.pas:23466 — ReadBook calls RecalcAbilitys the moment the TUserMagic joins
    // m_MagicList, which is what makes a freshly read 基本剑术/攻杀剑术 raise 准确 and arm the
    // 攻杀 cadence without waiting for the next relog. Delphi sends no RM_ABILITY here, so
    // neither does this: only SendAddMagic follows.
    recalculateAbilities(player);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      player.skills.remove(skill.magicId());
      recalculateAbilities(player);
      throw failure;
    }
    emit(player, new WorldEvent.SkillLearned(player.id, new LearnedMagic(skill, definition)));
    emit(player, new WorldEvent.ItemUsed(player.id, book, 0, 0));
    // ReadBook auto-enables a freshly learned 刺杀剑术/半月弯刀 shape and echoes its +LNG/+WID tag
    // (ObjBase.pas:17377), so the client starts sending CM_LONGHIT/CM_WIDEHIT immediately.
    autoEnableWeaponSkill(player, definition.id());
    emitWeight(player);
    return true;
  }

  /**
   * Turn a learned shape skill on with its green hint + tag frame, as {@code ReadBook} does
   * (ObjBase.pas:17377): {@code ThrustingOnOff(True)}/{@code HalfMoonOnOff(True)} followed by the
   * raw tag. A no-op for any other skill or when the flag is already set.
   */
  private void autoEnableWeaponSkill(Player player, int magicId) {
    if (magicId == HitSpeed.SKILL_ERGUM && !player.useThrusting) {
      player.useThrusting = true;
      emit(player, new WorldEvent.SystemMessage(player.id, "启用刺杀剑法"));
      emit(player, new WorldEvent.WeaponSkillToggled(player.id, magicId, true));
    } else if (magicId == HitSpeed.SKILL_BANWOL && !player.useHalfMoon) {
      player.useHalfMoon = true;
      emit(player, new WorldEvent.SystemMessage(player.id, "开启半月弯刀"));
      emit(player, new WorldEvent.WeaponSkillToggled(player.id, magicId, true));
    }
  }

  /**
   * {@code TPlayObject.ClientDropItem} (ObjBase.pas:16213): safe-zone and map-flag gates, then
   * the item lands on the ground exactly like monster loot does.
   */
  private boolean dropBagItem(int playerId, int makeIndex, String itemName) {
    Player player = requirePlayer(playerId);
    if (!player.ability.alive()) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, itemName, makeIndex, WorldEvent.DropRejection.ACTOR_DEAD));
      return false;
    }
    // Delphi splits at the first space because mailed items append a use counter.
    String wantedName = itemName.indexOf(' ') >= 0
        ? itemName.substring(0, itemName.indexOf(' ')) : itemName;
    if (player.map.flags().isSafeZone()) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, wantedName, makeIndex, WorldEvent.DropRejection.SAFE_ZONE));
      return false;
    }
    if (player.map.flags().isNoThrowItem()) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, wantedName, makeIndex, WorldEvent.DropRejection.MAP_FORBIDS_DROP));
      return false;
    }
    int bagIndex = findBagItem(player, makeIndex, wantedName);
    if (bagIndex < 0) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, wantedName, makeIndex, WorldEvent.DropRejection.NO_SUCH_ITEM));
      return false;
    }
    Position dropPosition = findDropPosition(player.map, player.position);
    if (dropPosition == null) {
      emit(player, new WorldEvent.DropItemRejected(
          player.id, wantedName, makeIndex, WorldEvent.DropRejection.NO_SPACE));
      return false;
    }

    BackpackItem item = player.backpack.get(bagIndex);
    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
    player.backpack.remove(bagIndex);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      throw failure;
    }

    int itemId = allocateObjectId();
    GroundItem ground = new GroundItem(
        itemId, item.name(), item.looks(), player.map.id(), dropPosition);
    groundItems.put(itemId, ground);
    itemDropTimes.put(itemId, clock.getAsLong());
    emit(player, new WorldEvent.ItemDropped(player.id, item, ground));
    WorldEvent appeared = new WorldEvent.ItemAppeared(ground);
    for (int viewerId : visibleIds(player.map, dropPosition, 0)) emit(players.get(viewerId), appeared);
    emitWeight(player);
    return true;
  }

  /** Bag lookup by MakeIndex plus {@code CompareText}, as every item command does. */
  private static int findBagItem(Player player, int makeIndex, String itemName) {
    for (int index = 0; index < player.backpack.size(); index++) {
      BackpackItem item = player.backpack.get(index);
      if (item.makeIndex() == makeIndex && item.name().equalsIgnoreCase(itemName)) return index;
    }
    return -1;
  }

  /**
   * The "cannot take off" family of checks shared by take-on and take-off
   * (ObjBase.pas:17238-17262). With {@code m_boUserUnLockDurg = False} (the login default),
   * {@code Reserved & 2} locks an item until the unlock-potion slice lands; {@code Reserved & 4}
   * is an unconditional lock; {@code InDisableTakeOffList} (ObjBase.pas:17144/:17259) locks any
   * item named in the server's 禁止取下物品列表.
   */
  private boolean isLockedInPlace(BackpackItem item) {
    int reserved = item.item().reserved();
    return (reserved & 0x02) != 0 || (reserved & 0x04) != 0
        || disableTakeOffList.contains(item.item());
  }

  /**
   * {@code GetUserItemWeitht(nWhere)} (ObjBase.pas:23306): total weight of the worn set,
   * excluding the destination slot and — a Delphi quirk — both hand slots, whatever the
   * destination is.
   */
  private static int wornWeightExcluding(Player player, EquipmentSlot destination) {
    int total = 0;
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : player.equipment.inSlotOrder()) {
      EquipmentSlot slot = entry.getKey();
      if (slot == destination || slot.countsTowardHandWeight()) continue;
      total += entry.getValue().item().weight();
    }
    return total;
  }

  private static EquipRequirement.Character requirementView(Player player) {
    Ability ability = player.ability;
    return new EquipRequirement.Character(player.gender, player.job, ability.level(),
        ability.maxDc(), 0, 0, player.maxWearWeight(), player.maxHandWeight());
  }

  /**
   * {@code TBaseObject.RecalcAbilitys} (ObjBase.pas:2818): rebuilds the working ability from
   * the base ability plus the worn set. HP/MP survive the rebuild and are only re-clamped
   * when the new maxima are lower.
   */
  private void recalculateAbilities(Player player) {
    recalculateAbilities(player, true);
  }

  /**
   * The single {@code RecalcAbilitys} pass (ObjBase.pas:2818). {@code announceLightChange}
   * suppresses only the {@code RM_CHANGELIGHT} side effect: at login the RM_LOGON handler
   * itself sends the light right after {@code SM_NEWMAP} (ObjBase.pas:5620), before which
   * the client has no actor to attach it to yet, so the entry call stays silent and lets
   * {@link #sendMapEntered}'s packet carry the initial radius.
   */
  private void recalculateAbilities(Player player, boolean announceLightChange) {
    EquipmentBonus bonus = player.equipment.bonus();
    Ability base = player.baseAbility;
    int maxHp = clampWord(base.maxHp() + bonus.hp());
    int maxMp = clampWord(base.maxMp() + bonus.mp());
    // ObjBase.pas:3418-3421, straight after the worn-set addition and still inside the same
    // RecalcAbilitys pass: 神圣战甲术 (STATE_DEFENCEUP) lifts the upper AC bound and 幽灵盾
    // (STATE_MAGDEFENCEUP) the upper MAC bound, each by 2 + (m_Abil.Level div 7). The lower
    // bound is untouched, so the *minimum* roll of a buffed character is unchanged.
    long now = clock.getAsLong();
    int stateBonus = DEFENCE_UP_BASE_BONUS + base.level() / DEFENCE_UP_LEVEL_DIVISOR;
    int defenceUpBonus = player.defenceUpUntil > now ? stateBonus : 0;
    int magDefenceUpBonus = player.magDefenceUpUntil > now ? stateBonus : 0;
    // Remember exactly what this pass put on the working ability: Player.rebase has to take the
    // same amount back off before the naked m_Abil is persisted, or a timed buff would leak into
    // the character record (and compound on every following cast).
    player.appliedDefenceUpBonus = defenceUpBonus;
    player.appliedMagDefenceUpBonus = magDefenceUpBonus;
    player.ability = new Ability(
        Math.min(player.ability.hp(), maxHp),
        maxHp,
        Math.min(player.ability.mp(), maxMp),
        maxMp,
        base.minDc() + bonus.minDc(),
        base.maxDc() + bonus.maxDc(),
        base.minAc() + bonus.minAc(),
        base.maxAc() + bonus.maxAc() + defenceUpBonus,
        base.minMac() + bonus.minMac(),
        base.maxMac() + bonus.maxMac() + magDefenceUpBonus,
        base.minMc() + bonus.minMc(),
        base.maxMc() + bonus.maxMc(),
        base.minSc() + bonus.minSc(),
        base.maxSc() + bonus.maxSc(),
        base.level(),
        base.experience(),
        base.maxExperience());
    player.bonus = bonus;
    player.antiPoison = PLAYER_ANTIPOISON_BASE + bonus.antiPoison();
    player.poisonRecover = bonus.poisonRecover();
    player.healthRecover = bonus.healthRecover();
    player.spellRecover = bonus.spellRecover();
    player.antiMagic = PLAYER_ANTIMAGIC_BASE + bonus.antiMagic();
    recalculateHitSpeed(player, bonus);
    player.revival = equipmentGrantsRevival(player.equipment);
    // The same RecalcAbilitys pass rebuilds the three death-penalty flags.
    player.dropProtection = DropProtection.of(player.equipment);
    // ObjBase.pas:3387: the light radius comes solely from the right-hand slot — a worn
    // item with durability left lights the actor at 3, everything else is 0. (The dress
    // StdItem.Light branch inside the slot loop at ObjBase.pas:3129 writes m_nLight := 3
    // only to be unconditionally overwritten by this trailing if/else, so the effective
    // Delphi behaviour is exactly this rule. The TStdItem.Light flag is not part of the
    // W18 GEEM2 catalog import either.)
    int oldLight = player.light;
    player.light = player.equipment.at(EquipmentSlot.RIGHT_HAND)
        .filter(item -> item.dura() > 0)
        .map(ignored -> 3)
        .orElse(0);
    if (announceLightChange && oldLight != player.light) {
      WorldEvent relit = new WorldEvent.LightChanged(player.id, player.light);
      emit(player, relit);
      for (int viewerId : visibleIds(player.map, player.position, player.id)) {
        emit(players.get(viewerId), relit);
      }
    }
  }

  /**
   * The {@code RecalcHitSpeed} half of {@code RecalcAbilitys} (ObjBase.pas:3385 → 18551),
   * followed by the worn-set addition at ObjBase.pas:3394
   * ({@code Inc(m_btSpeedPoint, m_AddAbil.wSpeedPoint); Inc(m_btHitPoint, m_AddAbil.wHitPoint)}).
   *
   * <p>Delphi re-seeds the 攻杀剑术 cadence on every pass, so equipping a ring re-rolls which
   * swing of the cycle arms the next power hit. That draw only happens for a character who
   * actually knows 攻杀剑术, which is why no pre-W33 deterministic vector moves.
   */
  private void recalculateHitSpeed(Player player, EquipmentBonus bonus) {
    HitSpeed points = HitSpeed.of(player.job, player.skills.values());
    player.hitPoint = points.hitPoint() + bonus.hitPoint();
    player.speedPoint = points.speedPoint() + bonus.speedPoint();
    player.hitPlus = points.hitPlus();
    player.hitDouble = points.hitDouble();
    if (points.attackSkillCycle() > 0) {
      player.attackSkillCount = points.attackSkillCycle();
      player.attackSkillPointCount =
          random.nextInt(WorldRandom.Stream.POWER_HIT, points.attackSkillCycle());
    } else {
      player.attackSkillCount = 0;
      player.attackSkillPointCount = 0;
    }
  }

  /**
   * The {@code SM_SUBABILITY} that Delphi sends on the heels of every {@code SM_ABILITY}
   * (ObjBase.pas:5601): the anti-magic base value, 准确/敏捷 and every worn-set
   * resistance/recovery accumulator rebuilt by {@code RecalcAbilitys}.
   */
  private void emitSubAbility(Player player) {
    emit(player, new WorldEvent.SubAbilityChanged(
        player.id, player.antiMagic, player.hitPoint, player.speedPoint,
        player.antiPoison, player.poisonRecover, player.healthRecover, player.spellRecover));
  }

  /**
   * The {@code m_boRevival} half of {@code RecalcAbilitys} (ObjBase.pas:2866/2968/3218-3228/
   * 3271). The loop skips items at zero durability, weapon/right-hand/dress slots contribute
   * through {@code AniCount}, every other slot through {@code Shape}. The dress is included in
   * the flag branch even though {@code ItemDamageRevivalRing} never consumes from it — that
   * asymmetry is in the original and is kept here.
   */
  private static boolean equipmentGrantsRevival(Equipment equipment) {
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : equipment.inSlotOrder()) {
      if (entry.getValue().dura() <= 0) continue;
      StdItem item = entry.getValue().item();
      EquipmentSlot slot = entry.getKey();
      if (slot == EquipmentSlot.WEAPON || slot == EquipmentSlot.RIGHT_HAND
          || slot == EquipmentSlot.DRESS) {
        if (isRevivalShape(item.aniCount())) return true;
      } else if (isRevivalShape(item.shape())) {
        return true;
      }
    }
    return false;
  }

  /** {@code Shape/AniCount in [114, 160, 161, 162]} — the four revival-capable shapes. */
  private static boolean isRevivalShape(int value) {
    return value == 114 || value == 160 || value == 161 || value == 162;
  }

  /**
   * {@code StdItem.Shape = 144} sets {@code m_boUnRevival} on the wearer (ObjBase.pas:3139,
   * the accessory branch — the three hand/dress slots branch on AniCount instead and never
   * set this flag), and {@code TBaseObject.Run} refuses the ring revival when the last hitter
   * carries it. Monsters never wear gear, so only a player attacker can suppress a revival.
   */
  private static boolean preventsRevival(WorldObject attacker) {
    if (!(attacker instanceof Player killer)) return false;
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : killer.equipment.inSlotOrder()) {
      if (entry.getKey() == EquipmentSlot.WEAPON
          || entry.getKey() == EquipmentSlot.RIGHT_HAND
          || entry.getKey() == EquipmentSlot.DRESS) continue;
      if (entry.getValue().item().shape() == 144) return true;
    }
    return false;
  }

  private static int clampWord(int value) {
    return Math.max(1, Math.min(value, 0xffff));
  }

  /**
   * The common tail of take-on/take-off: RM_ABILITY, the SM_TAKEON_OK/SM_TAKEOFF_OK reply,
   * FeatureChanged to observers and the weight refresh.
   */
  private void emitEquipmentChange(Player player, WorldEvent change) {
    emit(player, change);
    emit(player, new WorldEvent.AbilityChanged(
        player.id, player.ability, player.gold, player.job, player.weights()));
    emitSubAbility(player);
    emitWeight(player);
    // FeatureChanged() broadcasts the new look to everyone who can see the player.
    WorldObjectSnapshot snapshot = player.snapshot();
    WorldEvent appearance = new WorldEvent.ObjectAppeared(snapshot);
    for (int viewerId : visibleIds(player.map, player.position, player.id)) {
      emit(players.get(viewerId), appearance);
    }
  }

  /** {@code TBaseObject.WeightChanged} -> RM_WEIGHTCHANGED -> {@code SM_WEIGHTCHANGED}. */
  private void emitWeight(Player player) {
    emit(player, new WorldEvent.WeightChanged(
        player.id, player.bagWeight(), player.bonus.wearWeight(), player.bonus.handWeight()));
  }

  private boolean openDoorAt(int playerId, Position claimed) {
    Player player = requirePlayer(playerId);
    // TPlayObject.ClientOpenDoor checks only the door record itself — castle doors aside, the
    // Delphi handler does not gate on zone or alive state. The bo01 (castle-taken) flag has no
    // castle subsystem behind it yet, so it stays false and every found door may open.
    DoorInfo door = player.map.doorAt(claimed);
    if (door == null || door.status().opened()) return false;
    door.status().open(clock.getAsLong());
    WorldEvent opened = new WorldEvent.DoorOpened(player.map.id(), door.anchor());
    for (int viewerId : playersInSquare(player.map, claimed)) emit(players.get(viewerId), opened);
    return true;
  }

  /** Players (the only door-status recipients) inside the +/-12 client square around center. */
  private List<Integer> playersInSquare(GameMap map, Position center) {
    List<Integer> result = new ArrayList<>();
    for (int objectId : map.objectsInSquare(center, config.viewRange())) {
      if (players.containsKey(objectId)) result.add(objectId);
    }
    return result;
  }

  /**
   * {@code TBaseObject.EnterAnotherMap}: switch the player to the gate's destination map. The
   * preconditions were verified by the caller (walk landed on the gate, no closed door nearby,
   * destination cell walkable), so this implementation cannot fail and never rolls back.
   */
  private MoveResult teleportPlayer(Player player, Position source, Direction direction,
      MovementKind movement, TeleportRoute gate, GameMap destination) {
    GameMap origin = player.map;
    List<Integer> originObservers = visibleIds(origin, source, player.id);
    origin.remove(player.id, source);
    player.map = destination;
    player.position = gate.destination();
    player.direction = direction;
    destination.place(player.id, gate.destination());
    WorldObjectSnapshot snapshot = player.snapshot();

    emit(player, new WorldEvent.MoveAccepted(snapshot, source, movement));
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(player.id);
    for (int viewerId : originObservers) emit(players.get(viewerId), disappeared);
    // SM_CLEAROBJECTS + SM_CHANGEMAP order is fixed: the client drops its scene first and
    // rebuilds it from the events that follow (appearances, items).
    emit(player, new WorldEvent.PlayerMapChanged(snapshot, destination.info(), dayBright(destination)));
    List<Integer> destinationObservers = visibleIds(destination, player.position, player.id);
    WorldEvent appeared = new WorldEvent.ObjectAppeared(snapshot);
    for (int viewerId : destinationObservers) emit(players.get(viewerId), appeared);
    for (int objectId : destinationObservers) {
      WorldObject other = findObject(objectId);
      if (other != null) emit(player, new WorldEvent.ObjectAppeared(other.snapshot()));
    }
    for (GroundItem item : visibleItems(destination, player.position)) {
      emit(player, new WorldEvent.ItemAppeared(item));
    }
    for (FireWallEvent fire : visibleFireWalls(destination, player.position)) {
      emit(player, new WorldEvent.EventAppeared(fire.id(), ET_FIRE, fire.position(), 0));
    }
    return MoveResult.accepted(snapshot);
  }

  /**
   * Handles {@code @tick [N]} from {@link #processSay}. Runs the periodic tick body N times
   * (default 1) at N successive virtual timestamps and answers with the resulting world time
   * so the harness can assert both sides advanced identically.
   *
   * <p>Already executing on the world thread inside a queued command, so it calls
   * {@link #runTickBody()} directly rather than re-queuing through
   * {@link #advanceTicks(int)}.
   */
  private boolean pumpTicks(Player speaker, String text) {
    if (worldClock == null || worldClock.mode() != WorldClock.Mode.MANUAL) {
      emit(speaker, new WorldEvent.SystemMessage(speaker.id, "@tick 仅在手动世界时钟下可用"));
      return true;
    }
    String argument = text.length() > 5 ? text.substring(5).strip() : "";
    int ticks;
    try {
      ticks = argument.isEmpty() ? 1 : Integer.parseInt(argument);
    } catch (NumberFormatException malformed) {
      emit(speaker, new WorldEvent.SystemMessage(speaker.id, "@tick 参数必须是整数"));
      return true;
    }
    if (ticks < 0 || ticks > MAX_PUMPED_TICKS) {
      emit(speaker, new WorldEvent.SystemMessage(speaker.id,
          "@tick 步数必须在 0.." + MAX_PUMPED_TICKS + " 之间"));
      return true;
    }
    for (int index = 0; index < ticks; index++) {
      worldClock.advanceOneTick();
      runTickBody();
    }
    // The acknowledgement carries the new world time, so a divergence in how many ticks
    // actually ran shows up as a state difference rather than silently drifting.
    emit(speaker, new WorldEvent.SystemMessage(speaker.id,
        "@tick " + ticks + " -> " + worldClock.ticks() + " ticks, now=" + now()));
    return true;
  }

  /**
   * Upper bound for one {@code @tick} pump. A pumped tick is a full tick body, so an
   * unbounded value would let one chat line block the world thread indefinitely; 100k ticks
   * is ~83 minutes of virtual time at the shipped 50 ms interval.
   */
  private static final int MAX_PUMPED_TICKS = 100_000;

  private boolean processSay(int playerId, String rawText) {
    Player speaker = requirePlayer(playerId);
    if (speaker.map.flags().noChat()) {
      emit(speaker, new WorldEvent.SystemMessage(speaker.id, "当前地图禁止发言"));
      return false;
    }
    String text = rawText.strip();
    if (text.isEmpty()) return false;

    if (text.startsWith("@")) {
      if (text.equalsIgnoreCase("@who") || text.equalsIgnoreCase("@在线") || text.equalsIgnoreCase("@total")) {
        emit(speaker, new WorldEvent.SystemMessage(speaker.id, "当前在线玩家: " + players.size() + " 人"));
        return true;
      }
      // @tick N — the determinism pump. No Delphi counterpart: it exists so a shadow harness
      // can advance a MANUAL world by an exact number of ticks over the ordinary wire, which
      // is what makes a *moving* monster's Nth decision reproducible across two processes.
      // Refused outright on SYSTEM/VIRTUAL worlds (including every production server), so
      // the command is inert unless the operator explicitly booted a manual clock.
      if (text.regionMatches(true, 0, "@tick", 0, 5)) return pumpTicks(speaker, text);
      return true;
    }

    if (text.startsWith("/")) {
      if (text.equalsIgnoreCase("/who") || text.equalsIgnoreCase("/total")) {
        emit(speaker, new WorldEvent.SystemMessage(speaker.id, "当前在线玩家: " + players.size() + " 人"));
        return true;
      }
      String targetAndMsg = text.substring(1).stripLeading();
      int space = targetAndMsg.indexOf(' ');
      if (space <= 0) return false;
      String targetName = targetAndMsg.substring(0, space).trim();
      String whisperMsg = targetAndMsg.substring(space + 1).trim();
      if (whisperMsg.isEmpty()) return false;
      Integer targetId = playersByName.get(targetName);
      if (targetId != null && players.containsKey(targetId)) {
        Player target = players.get(targetId);
        WorldEvent whisperEvent = new WorldEvent.Whisper(speaker.id, speaker.name, target.id, target.name, whisperMsg);
        emit(target, whisperEvent);
        if (target.id != speaker.id) {
          emit(speaker, whisperEvent);
        }
        return true;
      } else {
        emit(speaker, new WorldEvent.SystemMessage(speaker.id, targetName + " 当前不在线或不存在"));
        return false;
      }
    }

    if (text.startsWith("!")) {
      if (speaker.map.flags().quiz()) {
        emit(speaker, new WorldEvent.SystemMessage(speaker.id, "当前地图禁止大喊"));
        return false;
      }
      String shoutMsg = text.substring(1).trim();
      if (shoutMsg.isEmpty()) return false;
      WorldEvent shoutEvent = new WorldEvent.Shout(speaker.id, speaker.name, shoutMsg);
      for (Player p : players.values()) {
        if (p.map.id().equals(speaker.map.id())) {
          emit(p, shoutEvent);
        }
      }
      return true;
    }

    // Normal chat: broadcast to all observers inside 12-cell square (including speaker)
    WorldEvent chatEvent = new WorldEvent.ChatHeard(speaker.id, speaker.name, text);
    List<Integer> viewers = visibleIds(speaker.map, speaker.position, 0);
    for (int viewerId : viewers) {
      Player viewer = players.get(viewerId);
      if (viewer != null) emit(viewer, chatEvent);
    }
    return true;
  }

  private void leave(int playerId) {
    Player player = requirePlayer(playerId);
    leaveGroup(player);
    persist(player);
    List<Integer> visibleIds = visibleIds(player.map, player.position, player.id);
    player.map.remove(player.id, player.position);
    players.remove(playerId);
    playersByName.remove(player.name);
    // Delphi's SendDelayMsg queues the message on the receiver (ObjBase.pas:19374 adds it to
    // Self.m_MsgList), so a friend's pending RM_TRANSPARENT still fires after the caster is gone;
    // every other kind is a bolt the caster owns and dies with them.
    pendingMagicImpacts.removeIf(impact ->
        impact.targetId() == playerId
            || (impact.casterId() == playerId && impact.kind() != MagicImpactKind.TRANSPARENT));
    emit(player, new WorldEvent.MapLeft(playerId));
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(playerId);
    for (int viewerId : visibleIds) emit(players.get(viewerId), disappeared);
  }

  /**
   * {@code TrainSkill(Random(3) + 1)} followed by one {@code CheckMagicLevelup} pass for the
   * two W33 passive melee skills. The event is semantic; the gate turns it into
   * {@code SM_MAGIC_LVEXP} without letting the world layer know about sockets.
   */
  private void trainPassiveMeleeSkill(Player player) {
    int magicId = switch (player.job) {
      case LevelAbilities.JOB_WARRIOR -> HitSpeed.SKILL_ONESWORD;
      case LevelAbilities.JOB_TAOIST -> HitSpeed.SKILL_ILKWANG;
      default -> 0;
    };
    if (magicId == 0) return;
    PlayerSkill current = player.skills.get(magicId);
    if (current == null || current.level() >= MagicDefinition.MAX_SKILL_LEVEL) return;

    MagicDefinition definition = magicCatalog.require(magicId);
    int points = random.nextInt(WorldRandom.Stream.SKILL_TRAIN, 3) + 1;
    PlayerSkill trained = current.train(definition, player.ability.level(), points);
    if (trained.equals(current)) return;
    player.skills.put(magicId, trained);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.skills.put(magicId, current);
      throw failure;
    }
    emit(player, new WorldEvent.SkillTrainingChanged(
        player.id, new LearnedMagic(trained, definition)));
  }

  private int rollDamage(Ability attacker, Ability defender) {
    return rollDamage(attacker, defender, 0);
  }

  /**
   * {@code m_nLuck} (ObjBase.pas:3401): {@code Inc(m_nLuck, btLuck); Dec(m_nLuck, btUnLuck)} over
   * the worn set, which {@link EquipmentBonus} already accumulates. Only the sign and magnitude
   * matter to {@code GetAttackPower}.
   */
  private static int playerLuck(Player player) {
    EquipmentBonus bonus = player.bonus;
    return bonus.luck() - bonus.unLuck();
  }

  /**
   * {@code nPower := GetAttackPower(LoWord(DC), HiWord(DC) - LoWord(DC))} followed by the
   * defender's AC roll (ObjBase.pas:22121 → 2416). {@code luck} is the attacker's
   * {@code m_nLuck} — positive luck can force the maximum roll, negative luck ({@code UnLuck})
   * can force the minimum, exactly as {@code GetAttackPower} branches.
   *
   * <p>When {@code luck == 0} the method draws a single power roll, which is bit-for-bit the
   * previous {@code between(minDc, maxDc)} behaviour, so every existing deterministic vector is
   * unchanged. Non-zero luck only occurs once gear grants it.
   */
  private int rollDamage(Ability attacker, Ability defender, int luck) {
    int attack = attackPower(attacker.minDc(), attacker.maxDc(), luck);
    int defence = randomBetween(defender.minAc(), defender.maxAc());
    return Math.max(0, attack - defence);
  }

  /**
   * The full {@code _Attack} damage chain (ObjBase.pas:22121-22160) in Delphi's own order:
   * power roll → 攻杀 bonus → dodge check → AC roll.
   *
   * <pre>
   *   nPower := GetAttackPower(LoWord(m_WAbil.DC), HiWord(m_WAbil.DC) - LoWord(m_WAbil.DC));
   *   if (wHitMode = 3) and m_boPowerHit then begin m_boPowerHit := False; Inc(nPower, m_nHitPlus); end;
   *   if IsProperTarget(AttackTarget) then begin
   *     if AttackTarget.m_btHitPoint &gt; 0 then
   *       if (m_btHitPoint &lt; Random(AttackTarget.m_btSpeedPoint)) then nPower := 0;
   *   end else nPower := 0;
   *   if nPower &gt; 0 then nPower := AttackTarget.GetHitStruckDamage(Self, nPower);
   * </pre>
   *
   * <p>Two deliberate carry-overs from the pre-W33 engine: the AC roll is still taken even when
   * the blow already scored zero (Delphi skips it, but the engine has always drawn it and the
   * observable damage is 0 either way), and the dodge draw lives on its own
   * {@link WorldRandom.Stream#ACCURACY} stream so a seeded shadow run keeps its damage sequence.
   */
  private int rollDamage(Ability attacker, Ability defender, int luck, int hitPlus,
      int hitDouble, WorldObject attackerObject, WorldObject target) {
    int attack = attackPower(attacker.minDc(), attacker.maxDc(), luck);
    if (hitPlus > 0) attack += hitPlus;
    // `nPower := nPower + Round(nPower / 100 * (m_nHitDouble * 10))` (ObjBase.pas:22132): the
    // 烈火 burst is a percentage of the already-boosted roll, applied before the dodge check.
    if (hitDouble > 0) attack += (int) Math.rint(attack / 100.0 * (hitDouble * 10));
    if (meleeEvaded(attackerObject, target)) attack = 0;
    int defence = randomBetween(defender.minAc(), defender.maxAc());
    return Math.max(0, attack - defence);
  }

  /**
   * {@code if AttackTarget.m_btHitPoint > 0 then if (m_btHitPoint < Random(AttackTarget
   * .m_btSpeedPoint)) then nPower := 0} (ObjBase.pas:22240).
   *
   * <p>Two quirks are kept verbatim. The check is skipped entirely when the <em>target</em> has
   * no 准确 of its own — which is why 木桩 ({@code 练功师}, {@code HIT=0}) can never be missed and
   * stays a deterministic damage bench. And {@code Random(0)} is a Delphi no-op that still burns
   * a draw and yields 0, reproduced here by clamping the bound to 1.
   */
  private boolean meleeEvaded(WorldObject attacker, WorldObject target) {
    if (target.hitPoint() <= 0) return false;
    int dodge = random.nextInt(WorldRandom.Stream.ACCURACY, Math.max(1, target.speedPoint()));
    return attacker.hitPoint() < dodge;
  }

  /**
   * {@code TBaseObject.GetAttackPower} (ObjBase.pas:2416), the melee/base-power branch.
   *
   * <p>With {@code luck == 0} this collapses to {@link #randomBetween(int, int)}, i.e. the exact
   * draw the engine took before the luck model existed — including its no-draw short-circuit when
   * {@code min == max} — so every deterministic vector is unchanged. Only non-zero luck (granted
   * by gear) takes the extra branches.
   */
  private int attackPower(int minDc, int maxDc, int luck) {
    if (luck == 0) return randomBetween(minDc, maxDc);
    int power = Math.max(0, maxDc - minDc);
    if (luck > 0) {
      // A 1-in-(10 - min(9, luck)) chance to land the maximum, else a normal roll.
      if (random.nextInt(WorldRandom.Stream.DAMAGE, 10 - Math.min(9, luck)) == 0) {
        return minDc + power;
      }
      return minDc + random.nextInt(WorldRandom.Stream.DAMAGE, power + 1);
    }
    int result = minDc + random.nextInt(WorldRandom.Stream.DAMAGE, power + 1);
    // A 1-in-(10 - max(0, -luck)) chance the blow is reduced to the minimum.
    if (random.nextInt(WorldRandom.Stream.DAMAGE, 10 - Math.max(0, -luck)) == 0) {
      return minDc;
    }
    return result;
  }

  private int randomBetween(int min, int max) {
    if (min >= max) return min;
    return random.between(WorldRandom.Stream.DAMAGE, min, max);
  }

  /** Magic shield reduces a penetrating blow to (level+2)*8 percent and burns 3 seconds. */
  private int applyMagicShield(WorldObject victim, int damage) {
    if (!(victim instanceof Player player) || damage <= 0
        || player.magicShieldUntil <= clock.getAsLong()) return damage;
    int reduced = (int) Math.rint(damage / 100.0 * (player.magicShieldLevel + 2) * 8.0);
    long now = clock.getAsLong();
    player.magicShieldUntil = Math.max(now + 1_000, player.magicShieldUntil - 3_000);
    return Math.max(0, reduced);
  }

  private void resolvePendingMagicImpacts() {
    long now = clock.getAsLong();
    for (int index = pendingMagicImpacts.size() - 1; index >= 0; index--) {
      PendingMagicImpact impact = pendingMagicImpacts.get(index);
      if (impact.dueAt() > now) continue;
      pendingMagicImpacts.remove(index);
      WorldObject caster = findObject(impact.casterId());
      WorldObject target = findObject(impact.targetId());
      // RM_TRANSPARENT (ObjBase.pas:4619) is a self-addressed delayed message: the friend's queue
      // re-runs MagMakePrivateTransparent with the recorded duration, so a caster who died or left
      // inside the 800 ms window does not cancel it, and only the recipient's own transparent slot
      // (the >0 early exit) can still refuse.
      if (impact.kind() == MagicImpactKind.TRANSPARENT) {
        if (target instanceof Player cloaked) applyPrivateTransparent(cloaked, impact.power());
        continue;
      }
      // RM_DOOPENHEALTH (ObjBase.pas:4623) is the 心灵启示 twin of the same shape: self-addressed
      // to the target, so the caster is irrelevant at delivery, and the recipient always runs
      // MakeOpenHealth — the m_boShowHP gate was already checked at cast time.
      if (impact.kind() == MagicImpactKind.OPEN_HEALTH) {
        if (target != null) makeOpenHealth(target);
        continue;
      }
      // RM_MAGSTRUCK (ObjBase.pas:2554) binds its victim at cast time and is delivered to the
      // object directly — unlike the RM_DELAYMAGIC bolts, whose `abs(nTargetX-x) <= nRage`
      // arrival check (ObjBase.pas:4579) is what the one-cell escape below models (W28).
      // RM_MAGHEALING of 群体治愈术 is likewise addressed to the object (Magic.pas:185), so a
      // healed friend who stepped away inside the 800 ms window still receives it.
      boolean positionBound = impact.kind() != MagicImpactKind.PIERCING_DAMAGE
          && impact.kind() != MagicImpactKind.AREA_DAMAGE
          && impact.kind() != MagicImpactKind.AREA_HEAL;
      if (caster == null || target == null || !caster.ability().alive() || !target.ability().alive()
          || caster.map() != target.map()
          || (positionBound && chebyshev(target.position(), impact.target()) > 1)) continue;
      // The party relationship is re-confirmed on arrival — see castAreaHealing.
      if (impact.kind() == MagicImpactKind.AREA_HEAL
          && (!(caster instanceof Player healer) || !isAreaHealFriend(healer, target))) continue;
      if (impact.kind() == MagicImpactKind.DAMAGE || impact.kind() == MagicImpactKind.PIERCING_DAMAGE
          || impact.kind() == MagicImpactKind.AREA_DAMAGE) {
        if ((impact.kind() == MagicImpactKind.PIERCING_DAMAGE
            || impact.kind() == MagicImpactKind.AREA_DAMAGE)
            && target instanceof Monster monster && monster.ability().level() < 50) {
          // RM_MAGSTRUCK: low-level animals pause walking for 800 + Random(1000) ms before MAC.
          monster.lastWalkAt += 800 + random.nextInt(WorldRandom.Stream.MAGIC_STAGGER, 1_000);
        }
        int defence = random.between(WorldRandom.Stream.MAGIC,
            target.ability().minMac(), target.ability().maxMac());
        int damage = applyMagicShield(target, Math.max(0, impact.power() - defence));
        applyDamage(target, caster, damage, true);
      } else if (impact.kind() == MagicImpactKind.HEAL
          || impact.kind() == MagicImpactKind.AREA_HEAL) {
        Ability before = target.ability();
        Ability healed = before.withHp(before.hp() + impact.power());
        if (healed.equals(before)) continue;
        target.setAbility(healed);
        if (target instanceof Player player) {
          try {
            persist(player);
          } catch (RuntimeException failure) {
            player.setAbility(before);
            throw failure;
          }
        }
        emitToObserversAndSelf(target, new WorldEvent.HealthChanged(target.snapshot()));
      } else {
        applyPoisonStatus(target, caster, impact.kind(), impact.power(), impact.extra());
      }
    }
  }

  /**
   * {@code MakePosion} (ObjBase.pas:22730), applied {@link #POISON_APPLY_DELAY_MILLIS} after a
   * successful 施毒术 cast. Delphi keeps the <em>longer</em> of the remaining and incoming
   * duration rather than stacking them, and only a player victim gets the red 「你中毒了」 hint
   * (the {@code RC_PLAYOBJECT} gate at ObjBase.pas:22752) — a poisoned monster gets neither a
   * message nor any other visible cue beyond the periodic damage/multiplier itself.
   *
   * <p>{@code m_btGreenPoisoningPoint} is one shared field in Delphi, so a second poison of the
   * other shape landing on the same victim would silently overwrite the first one's point value.
   * This port keeps the two shapes' points independent instead — a deliberate simplification,
   * since replicating the cross-shape overwrite only matters if both are active on the same
   * target simultaneously.
   */
  private void applyPoisonStatus(
      WorldObject target, WorldObject caster, MagicImpactKind kind, int durationSeconds, int point) {
    PoisonStatus status = target.poison();
    long now = clock.getAsLong();
    long candidate = now + durationSeconds * 1_000L;
    if (kind == MagicImpactKind.POISON_DECHEALTH) {
      if (candidate > status.decHealthUntil) {
        status.decHealthUntil = candidate;
        status.decHealthPoint = point;
        status.decHealthCasterId = caster.id();
        status.nextDecHealthTickAt = now + POISON_DECHEALTH_TICK_MILLIS;
      }
    } else if (candidate > status.damageArmorUntil) {
      status.damageArmorUntil = candidate;
    }
    if (target instanceof Player player) {
      emit(player, new WorldEvent.SystemMessage(
          player.id, String.format(POISONED_MESSAGE, durationSeconds, point)));
    }
  }

  /**
   * The periodic half of 施毒术's 灰色药粉 shape: {@code DamageHealth(m_btGreenPoisoningPoint +
   * 1)} every {@link #POISON_DECHEALTH_TICK_MILLIS} while the timer is active (ObjBase.pas:4258).
   * Delphi's {@code DamageHealth} is a lighter path than {@code StruckDamage}/{@code applyDamage}
   * — no PK flag, no armour wear, no struck flinch broadcast — so this mirrors that instead of
   * reusing {@link #applyDamage}. If the original caster is no longer resolvable the tick is
   * simply skipped for this interval (the duration itself keeps counting down regardless).
   */
  private void tickPoisonDecHealth(WorldObject victim, long now) {
    PoisonStatus status = victim.poison();
    if (status.decHealthUntil <= now) {
      status.decHealthUntil = 0;
      return;
    }
    if (now < status.nextDecHealthTickAt) return;
    status.nextDecHealthTickAt = now + POISON_DECHEALTH_TICK_MILLIS;
    if (!victim.ability().alive()) return;
    WorldObject caster = findObject(status.decHealthCasterId);
    if (caster == null) return;
    applyPoisonDamage(victim, caster, status.decHealthPoint + 1);
  }

  /**
   * {@code TBaseObject.DamageHealth} (ObjBase.pas:2451), the HP-only half relevant once the
   * 幽灵盾 MP-absorption branch is out of scope (that skill is still unimplemented). On death the
   * last-known poison caster is credited as the killer, matching {@code m_LastHiter} having been
   * set once at the original {@code RM_POISON} dispatch.
   */
  private void applyPoisonDamage(WorldObject victim, WorldObject caster, int amount) {
    if (amount <= 0 || !victim.ability().alive()) return;
    Ability before = victim.ability();
    Ability updated = before.withHp(before.hp() - amount);
    victim.setAbility(updated);
    if (victim instanceof Player player) {
      try {
        persist(player);
      } catch (RuntimeException failure) {
        player.setAbility(before);
        throw failure;
      }
    }
    if (updated.alive()) {
      emitToObserversAndSelf(victim, new WorldEvent.HealthChanged(victim.snapshot()));
    } else {
      handleDeath(victim, caster);
    }
  }

  /**
   * Ticks every live player/monster's poison timers once per world tick. Split from {@link
   * #resolvePendingMagicImpacts} because a poison, once applied, outlives the pending-impact
   * queue entry that started it.
   */
  private void tickPoison() {
    long now = clock.getAsLong();
    for (Player player : players.values()) tickPoisonDecHealth(player, now);
    for (Monster monster : monsters.values()) tickPoisonDecHealth(monster, now);
    for (Player player : players.values()) expirePoisonDamageArmor(player, now);
    for (Monster monster : monsters.values()) expirePoisonDamageArmor(monster, now);
  }

  private void expirePoisonDamageArmor(WorldObject victim, long now) {
    PoisonStatus status = victim.poison();
    if (status.damageArmorUntil != 0 && status.damageArmorUntil <= now) status.damageArmorUntil = 0;
  }

  /**
   * {@code TPlayObject.Run}'s 烈火剑法 expiry (ObjBase.pas:6427): 20 seconds after the flag was
   * armed it lapses with a red hint and a {@code '+UFIR'} tag so the client stops sending
   * {@code CM_FIREHIT}. The same tick window in Delphi also expires 双龙斩/狂风斩, which this
   * server does not implement.
   */
  private void expireFireSword(Player player, long now) {
    if (!player.fireHitArmed || now - player.lastFireHitAt <= FIRE_SWORD_EXPIRY_MILLIS) return;
    player.fireHitArmed = false;
    emit(player, new WorldEvent.SystemMessage(player.id, "召唤烈火精灵结束..."));
    emit(player, new WorldEvent.WeaponSkillToggled(player.id, HitSpeed.SKILL_FIRESWORD, false));
  }

  private void expireSkillBuffs() {
    long now = clock.getAsLong();
    for (Player player : players.values()) {
      expireFireSword(player, now);
      if (player.magicShieldUntil != 0 && player.magicShieldUntil <= now) {
        player.magicShieldUntil = 0;
        player.magicShieldLevel = 0;
        emit(player, new WorldEvent.SystemMessage(player.id, "魔法盾效果已消失"));
      }
      // The status walk keeps Delphi's slot order (STATE_TRANSPARENT=8, STATE_DEFENCEUP=9,
      // STATE_MAGDEFENCEUP=10): each slot's hint goes out as the walk reaches it, the cloak's
      // status repaint follows every hint (boChg, ObjBase.pas:4236-4240), and the single shared
      // RM_ABILITY refresh lands last (boNeedRecalc, ObjBase.pas:4243-4247).
      boolean needRecalc = expireDefenceUp(player, now);
      if (expireTransparent(player, now)) emitCharacterStatusChanged(player);
      // 心灵启示's m_boShowHP walk (ObjBase.pas:4029): a revealed player's bar closes on expiry.
      expireOpenHealth(player, now);
      if (needRecalc) {
        emit(player, new WorldEvent.AbilityChanged(
            player.id, player.ability, player.gold, player.job, player.weights()));
      }
    }
  }

  /**
   * {@code STATE_TRANSPARENT}'s arm of the status countdown (ObjBase.pas:4168-4171): when the
   * slot reaches zero {@code m_boHideMode} drops and {@code boChg} repaints the status word.
   * {@code m_boTransparent} deliberately survives, exactly as in Delphi — {@code RecalcAbilitys}
   * reads the pair together ({@code transparent and slot > 0}) to decide whether to re-enter hide
   * mode, and a relog rebuilds both from scratch.
   *
   * @return {@code true} when the slot actually lapsed this tick
   */
  private boolean expireTransparent(Player player, long now) {
    if (player.transparentUntil == 0 || player.transparentUntil > now) return false;
    player.transparentUntil = 0;
    player.hideMode = false;
    player.status = charStatus(player, now);
    return true;
  }

  /**
   * {@code TBaseObject.Run}'s status-array countdown (ObjBase.pas:4155-4190): each live
   * {@code m_wStatusTimeArr} slot loses one per second, and the {@code STATE_DEFENCEUP} /
   * {@code STATE_MAGDEFENCEUP} arms raise {@code boNeedRecalc} plus their green hint when the
   * counter reaches zero. The recalculation itself is shared: one {@code RecalcAbilitys} and one
   * {@code RM_ABILITY} follow the whole array walk (ObjBase.pas:4243-4247), so two statuses
   * lapsing in the same second still produce a single ability refresh — but two hints.
   *
   * <p>Slots are walked in index order ({@code STATE_DEFENCEUP = 9} before
   * {@code STATE_MAGDEFENCEUP = 10}, Common/Grobal2.pas:91-92), which is the order the hints
   * arrive in.
   */
  private boolean expireDefenceUp(Player player, long now) {
    boolean needRecalc = false;
    if (player.defenceUpUntil != 0 && player.defenceUpUntil <= now) {
      player.defenceUpUntil = 0;
      emit(player, new WorldEvent.SystemMessage(player.id, DEFENCE_UP_EXPIRED_MESSAGE));
      needRecalc = true;
    }
    if (player.magDefenceUpUntil != 0 && player.magDefenceUpUntil <= now) {
      player.magDefenceUpUntil = 0;
      emit(player, new WorldEvent.SystemMessage(player.id, MAG_DEFENCE_UP_EXPIRED_MESSAGE));
      needRecalc = true;
    }
    if (needRecalc) recalculateAbilities(player);
    return needRecalc;
  }

  private void applyDamage(WorldObject victim, WorldObject attacker, int damage) {
    applyDamage(victim, attacker, damage, false);
  }

  private void applyDamage(WorldObject victim, WorldObject attacker, int damage, boolean magical) {
    // ObjBase.pas:22252-22262 gates StruckDamage *and* the RM_STRUCK broadcast behind
    // `if nPower > 0`, so a blow that was dodged (W33) or fully absorbed by AC is silent on
    // the wire: no flinch animation, no floating 0. Before W33 the engine broadcast a
    // zero-damage SM_STRUCK here, which only ever fired on the rare full-absorb case and is
    // now corrected — otherwise every dodged swing would repaint the victim.
    if (damage <= 0) return;
    // StruckDamage (ObjBase.pas:22472): while 施毒术's 黄色药粉 (POISON_DAMAGEARMOR) is active,
    // every hit the victim takes — melee or magic alike, since both funnel through this method —
    // is scaled up by g_Config.nPosionDamagarmor / 10.
    if (victim.poison().damageArmorUntil > clock.getAsLong()) {
      damage = (int) Math.rint(damage * POISON_DAMAGEARMOR_MULTIPLIER);
    }
    // RM_STRUCK handling (ObjBase.pas:5477) sets the attacker's PK flag before the damage is
    // applied, so even a non-lethal blow between players repaints the aggressor's name.
    setPkFlag(victim, attacker);
    Ability before = victim.ability();
    int nextHp = Math.max(0, before.hp() - damage);
    Ability updated = before.withHp(nextHp);
    victim.setAbility(updated);
    if (victim instanceof Player player) {
      try {
        persist(player);
      } catch (RuntimeException failure) {
        player.setAbility(before);
        throw failure;
      }
      wearArmorOnStruck(player);
    }
    broadcastStruck(victim, attacker.id(), damage, magical);
    if (updated.alive()) {
      WorldEvent health = new WorldEvent.HealthChanged(victim.snapshot());
      emitToObserversAndSelf(victim, health);
    } else if (victim instanceof Player player && tryRevivalRing(player, attacker)) {
      // TBaseObject.Run's revival branch fired before Die: the ring ate the blow, the player
      // never actually died and no death/scatter side effects ran. HealthChanged and the
      // green hint were emitted by the branch itself.
    } else {
      handleDeath(victim, attacker);
    }
  }

  /**
   * The HP=0 branch of {@code TBaseObject.Run} (ObjBase.pas:3750-3763). Delphi evaluates it
   * on the next tick after a lethal blow; this engine folds it into the damage pass because
   * death itself was already made synchronous in W14 — the observable message order
   * (struck → health restored) is identical either way.
   *
   * <p>Gates, in Delphi order: the last hitter's {@code m_boUnRevival} (worn Shape 144),
   * {@code m_boRevival} (recalculated from the worn set), and the {@code dwRevivalTime}
   * cooldown (60 seconds, strictly greater-than). On success the ring(s) pay 1000 durability
   * each, HP is refilled to MaxHP and the classic green hint goes out.
   */
  private boolean tryRevivalRing(Player player, WorldObject attacker) {
    if (preventsRevival(attacker)) return false;
    if (!player.revival) return false;
    long now = clock.getAsLong();
    if (now - player.revivalTick <= REVIVAL_COOLDOWN_MILLIS) return false;
    player.revivalTick = now;
    consumeRevivalRings(player);
    player.setAbility(player.ability.withHp(player.ability.maxHp()));
    emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
    emit(player, new WorldEvent.SystemMessage(player.id, REVIVAL_RECOVER_MESSAGE));
    // The damage pass already persisted HP=0; the ring outcome has to reach the store too,
    // but a store hiccup must not un-revive the player — the periodic save catches up.
    try {
      persist(player);
    } catch (RuntimeException error) {
      LOG.log(Level.WARNING, "revival save failed for " + player.name, error);
    }
    return true;
  }

  /**
   * {@code TBaseObject.ItemDamageRevivalRing} (ObjBase.pas:3625): every worn item whose
   * {@code Shape} is revival-capable — or, for the two hand slots only, whose
   * {@code AnniCount} is — pays exactly 1000 durability. The Delphi loop has no {@code break},
   * so two rings both pay for one revival.
   *
   * <p>Quirks kept verbatim: an item drained to zero is destroyed (deleted from the client
   * via SM_DELITEMS and its slot cleared) rather than returned to the bag; and the
   * RM_DURACHANGE follow-up only fires when the wear crossed a 1000-durability display
   * boundary — Delphi's {@code Round(nDura / 1000)} is banker's rounding, so e.g. 2500 → 1500
   * rounds to 2 on both sides and sends nothing.
   */
  private void consumeRevivalRings(Player player) {
    boolean anyDestroyed = false;
    for (EquipmentSlot slot : EquipmentSlot.values()) {
      BackpackItem worn = player.equipment.at(slot).orElse(null);
      if (worn == null) continue;
      StdItem item = worn.item();
      boolean consumes = isRevivalShape(item.shape())
          || ((slot == EquipmentSlot.WEAPON || slot == EquipmentSlot.RIGHT_HAND)
              && isRevivalShape(item.aniCount()));
      if (!consumes) continue;

      int nDura = worn.dura();
      int tDura = thousandsBucket(nDura);
      nDura -= REVIVAL_RING_DURABILITY_COST;
      if (nDura <= 0) {
        nDura = 0;
        // SendDelItems reads the slot before it is cleared, so the removal message still
        // names the item.
        emit(player, WorldEvent.ItemsRemoved.ofItems(player.id, List.of(worn)));
        player.equipment = player.equipment.without(slot);
        anyDestroyed = true;
      } else {
        player.equipment = player.equipment.with(slot, worn.withDura(nDura));
      }
      if (tDura != thousandsBucket(nDura)) {
        emit(player, new WorldEvent.ItemDurabilityChanged(
            player.id, slot, worn.makeIndex(), nDura, worn.duraMax(), nDura == 0));
      }
    }
    if (anyDestroyed) recalculateAbilities(player);
  }

  /** Delphi {@code Round(nDura / 1000)}: banker's rounding of the durability thousands. */
  private static int thousandsBucket(int dura) {
    return (int) Math.rint(dura / 1000.0);
  }

  /** {@code StruckDamage}: dress always wears; every occupied slot also has a 1/8 chance. */
  private void wearArmorOnStruck(Player player) {
    if (player.equipment.isEmpty()) return;
    int wear = random.nextInt(WorldRandom.Stream.EQUIPMENT_WEAR, 10) + 5;
    damageEquipment(player, EquipmentSlot.DRESS, wear);
    // Snapshot the slots because a zero-durability item is removed during iteration.
    List<EquipmentSlot> occupied = player.equipment.inSlotOrder().stream()
        .map(Map.Entry::getKey).toList();
    for (EquipmentSlot slot : occupied) {
      if (random.nextInt(WorldRandom.Stream.EQUIPMENT_WEAR, 8) == 0)
        damageEquipment(player, slot, wear);
    }
  }

  /**
   * Applies instance wear and persists it. At zero, Delphi SendDelItems clears wIndex: the
   * item is destroyed (not moved to the bag), its bonuses disappear, and appearance updates.
   */
  private void damageEquipment(Player player, EquipmentSlot slot, int amount) {
    if (amount <= 0) return;
    BackpackItem worn = player.equipment.at(slot).orElse(null);
    if (worn == null || worn.dura() <= 0) return;
    int nextDura = Math.max(0, worn.dura() - amount);
    Equipment previous = player.equipment;
    Ability previousAbility = player.ability;
    EquipmentBonus previousBonus = player.bonus;
    boolean broken = nextDura == 0;
    player.equipment = broken
        ? player.equipment.without(slot)
        : player.equipment.with(slot, worn.withDura(nextDura));
    if (broken) recalculateAbilities(player);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.equipment = previous;
      player.ability = previousAbility;
      player.bonus = previousBonus;
      throw failure;
    }
    emit(player, new WorldEvent.ItemDurabilityChanged(
        player.id, slot, worn.makeIndex(), nextDura, worn.duraMax(), broken));
    if (broken) {
      emit(player, new WorldEvent.AbilityChanged(
        player.id, player.ability, player.gold, player.job, player.weights()));
      emitSubAbility(player);
      emitWeight(player);
      WorldEvent appearance = new WorldEvent.ObjectAppeared(player.snapshot());
      for (int viewerId : visibleIds(player.map, player.position, player.id)) {
        emit(players.get(viewerId), appearance);
      }
    }
  }

  private void broadcastStruck(WorldObject victim, int attackerId, int damage, boolean magical) {
    WorldEvent struck = new WorldEvent.ObjectStruck(victim.snapshot(), attackerId, damage, magical);
    emitToObserversAndSelf(victim, struck);
  }

  private void emitToObserversAndSelf(WorldObject center, WorldEvent event) {
    if (center instanceof Player p) emit(p, event);
    for (int viewerId : visibleIds(center.map(), center.position(), center.id())) {
      emit(players.get(viewerId), event);
    }
  }

  private void handleDeath(WorldObject victim, WorldObject killer) {
    if (victim instanceof Player player) {
      leaveGroup(player);
      // TBaseObject.Die marks the object dead and stamps m_dwDeathTick before anything else,
      // because ScatterBagItems and the RM_DEATH broadcast both observe that state.
      player.diedAt = clock.getAsLong();
      // ObjBase.pas:20938 — the PK bookkeeping runs before the item penalties, because
      // ScatterBagItems reads the (possibly just raised) PKLevel of the victim, not the killer.
      applyMurderPenalty(player, killer);
      // ObjBase.pas:20999-21012, the RC_PLAYOBJECT branch of Die: DropUseItems runs first
      // (gated on who landed the kill), then the boDieScatterBag bag scatter.
      dropUseItems(player, killer);
      scatterBagItems(player);
      // ObjBase.pas:21014 — the dying player loses AddBodyLuck(-(50 - (50 - Level*5))), which
      // simplifies to -(Level*5). Applied after the item penalties, inside the same Die branch.
      player.bodyLuck = player.bodyLuck.add(-(player.ability.level() * 5.0));
    }
    WorldEvent death = new WorldEvent.ObjectDied(victim.snapshot(), killer.id());
    emitToObserversAndSelf(victim, death);
    if (victim instanceof Monster monster) {
      monster.diedAt = clock.getAsLong();
      monster.targetId = 0;
      dropLoot(monster);
      if (killer instanceof Player player) {
        distributeMonsterExperience(player, monster.template.experience());
      }
    }
  }

  /**
   * {@code TPlayObject.ScatterBagItems} (ObjBase.pas:26648) with {@code ItemOfCreat = nil},
   * the {@code g_Config.boDieScatterBag} path taken by {@code Die}: every bag entry has a
   * {@code 1 / nDieScatterBagRate} (default 3) chance to drop within {@code DropWide = 2}
   * cells, and the dropped set is reported back through {@code RM_SENDDELITEMLIST}.
   *
   * <p>W20 completes the two gates Delphi applies before the loop: the 护身 / 不掉物品 worn
   * flags ({@code m_boAngryRing or m_boNoDropItem}, see {@link DropProtection}) skip the
   * scatter entirely, and {@code g_Config.boDieRedScatterBagAll} makes a red name
   * ({@code PKLevel >= 2}) drop <em>everything</em> instead of one third. Worn gear is handled
   * separately by {@link #dropUseItems}.
   */
  private void scatterBagItems(Player player) {
    if (player.backpack.isEmpty()) return;
    // Delphi refuses the whole scatter on a NODROPITEM map (m_PEnvir.Flag.boNODROPITEM).
    if (player.map.flags().isNoDropItem()) return;
    // ObjBase.pas:26660 — 护身戒指 (m_boAngryRing) or a 不掉包裹 item exits before any roll.
    if (player.dropProtection.blocksBagScatter()) return;
    // ObjBase.pas:26663 — boDieRedScatterBagAll and PKLevel >= 2: the whole bag goes.
    boolean dropAll = DIE_RED_SCATTER_BAG_ALL && PkLevel.isRed(player.pkPoint);
    List<BackpackItem> previousBackpack = List.copyOf(player.backpack);
    List<BackpackItem> dropped = new ArrayList<>();
    List<GroundItem> landed = new ArrayList<>();
    // Delphi walks the bag backwards so removals do not disturb the remaining indexes.
    for (int index = player.backpack.size() - 1; index >= 0; index--) {
      if (!dropAll
          && random.nextInt(WorldRandom.Stream.DEATH_SCATTER, DIE_SCATTER_BAG_RATE) != 0) continue;
      BackpackItem item = player.backpack.get(index);
      Position cell = findDropPosition(player.map, player.position);
      if (cell == null) continue; // DropItemDown failed: the entry stays in the bag.
      int itemId = allocateObjectId();
      GroundItem ground = new GroundItem(itemId, item.name(), item.looks(), player.map.id(), cell);
      landed.add(ground);
      dropped.add(item);
      player.backpack.remove(index);
    }
    if (dropped.isEmpty()) return;
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.backpack.clear();
      player.backpack.addAll(previousBackpack);
      LOG.log(Level.WARNING, "death scatter rolled back for " + player.name, failure);
      return;
    }
    for (GroundItem ground : landed) {
      groundItems.put(ground.id(), ground);
      itemDropTimes.put(ground.id(), clock.getAsLong());
      WorldEvent appeared = new WorldEvent.ItemAppeared(ground);
      for (int viewerId : visibleIds(player.map, ground.position(), 0)) {
        emit(players.get(viewerId), appeared);
      }
    }
    emit(player, WorldEvent.ItemsRemoved.ofItems(player.id, dropped));
  }

  /**
   * {@code TPlayObject.DropUseItems} (ObjBase.pas:15487) — the equipment half of the death
   * penalty, reached from {@code Die} (ObjBase.pas:21006/21009).
   *
   * <p>Delphi runs two independent passes over the thirteen worn slots:
   *
   * <ol>
   *   <li><b>Destroy pass.</b> Any worn item whose {@code StdItem.Reserved and 8} is set is
   *       deleted outright — added to the {@code DelList} with an <em>empty name</em> and its
   *       slot cleared. It never reaches the floor. The empty name is not a bug on our side:
   *       {@code DelList.AddObject('', MakeIndex)} really does send {@code /MakeIndex/} to
   *       the client, which matches on MakeIndex alone.</li>
   *   <li><b>Scatter pass.</b> Every remaining slot rolls {@code Random(nRate) = 0} with
   *       {@code nRate = nDieDropUseItemRate} (30), or {@code nDieRedDropUseItemRate} (15)
   *       once {@code PKLevel > 2}. A winning slot calls {@code DropItemDown(..., 2, True)};
   *       the slot is cleared (and the loss reported) <em>only</em> when
   *       {@code Reserved and 10 = 0} — otherwise the item both lands on the floor and stays
   *       worn, which is the original's behaviour, quirk and all.</li>
   * </ol>
   *
   * <p>Gates before either pass: {@code m_boAngryRing or m_boNoDropUseItem} exits outright
   * (ObjBase.pas:15498) and, in {@code Die}, the kill has to qualify —
   * {@code boKillByMonstDropUseItem} is on and {@code boKillByHumanDropUseItem} is off in the
   * shipped defaults, so a monster kill scatters gear and a PK kill does not. A kill with no
   * attributed attacker ({@code AttackBaseObject = nil}) always scatters.
   *
   * <p>{@code InDisableTakeOffList} stays deferred: it is a server-config item list
   * ({@code DisableTakeOffList.txt}), not a catalogue column.
   */
  private void dropUseItems(Player player, WorldObject killer) {
    // ObjBase.pas:15498 — 护身戒指 / 不掉装备 exits before anything is examined.
    if (player.dropProtection.blocksEquipmentDrop()) return;
    if (player.equipment.isEmpty()) return;
    // NB: DropUseItems itself has no map gate — only ScatterBagItems checks boNODROPITEM.
    // The outer Die guard is "(not m_boNoItem) or (not Flag.boNODROPITEM)" (ObjBase.pas:21001),
    // an OR that only blocks when the corpse is both item-less and on a NODROPITEM map, so a
    // plain NODROPITEM map does NOT save your gear in the original. Reproduced verbatim.
    if (!killQualifiesForEquipmentDrop(killer)) return;

    Equipment previousEquipment = player.equipment;
    List<ItemRemoval> removalList = new ArrayList<>();
    List<GroundItem> landed = new ArrayList<>();

    // Pass 1: Reserved & 8 — destroyed, never dropped, reported with an empty name.
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : previousEquipment.inSlotOrder()) {
      if ((entry.getValue().item().reserved() & RESERVED_DESTROY_ON_DEATH) == 0) continue;
      removalList.add(ItemRemoval.unnamed(entry.getValue()));
      player.equipment = player.equipment.without(entry.getKey());
    }

    // Pass 2: the 1-in-nRate scatter over whatever is still worn.
    int rate = PkLevel.of(player.pkPoint) > 2
        ? DIE_RED_DROP_USE_ITEM_RATE : DIE_DROP_USE_ITEM_RATE;
    for (Map.Entry<EquipmentSlot, BackpackItem> entry : player.equipment.inSlotOrder()) {
      if (random.nextInt(WorldRandom.Stream.DEATH_DROP_USE_ITEM, rate) != 0) continue;
      // ObjBase.pas:15532 — a listed item is never dropped on death, even when it rolled a hit.
      if (disableTakeOffList.contains(entry.getValue().item())) continue;
      BackpackItem worn = entry.getValue();
      Position cell = findDropPosition(player.map, player.position, DIE_DROP_USE_ITEM_RANGE);
      if (cell == null) continue; // DropItemDown returned False: the slot is untouched.
      landed.add(new GroundItem(
          allocateObjectId(), worn.name(), worn.looks(), player.map.id(), cell));
      // Reserved & 10 <> 0: the item drops but the wearer keeps it — reproduced verbatim.
      if ((worn.item().reserved() & RESERVED_KEEP_SLOT_ON_DROP) != 0) continue;
      removalList.add(ItemRemoval.of(worn));
      player.equipment = player.equipment.without(entry.getKey());
    }

    if (removalList.isEmpty() && landed.isEmpty()) return;
    if (!removalList.isEmpty()) recalculateAbilities(player);
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.equipment = previousEquipment;
      recalculateAbilities(player);
      LOG.log(Level.WARNING, "equipment drop rolled back for " + player.name, failure);
      return;
    }
    for (GroundItem ground : landed) {
      groundItems.put(ground.id(), ground);
      itemDropTimes.put(ground.id(), clock.getAsLong());
      WorldEvent appeared = new WorldEvent.ItemAppeared(ground);
      for (int viewerId : visibleIds(player.map, ground.position(), 0)) {
        emit(players.get(viewerId), appeared);
      }
    }
    if (!removalList.isEmpty()) {
      emit(player, new WorldEvent.ItemsRemoved(player.id, removalList));
      emitEquipmentChange(player, new WorldEvent.EquipmentSent(player.id, player.equipment));
    }
  }

  /**
   * The {@code Die} gate around {@code DropUseItems} (ObjBase.pas:21002-21010): with no
   * attacker the gear always scatters, otherwise it depends on which of
   * {@code boKillByHumanDropUseItem} / {@code boKillByMonstDropUseItem} covers the killer.
   */
  private static boolean killQualifiesForEquipmentDrop(WorldObject killer) {
    if (killer == null) return true;
    return killer instanceof Player
        ? KILL_BY_HUMAN_DROP_USE_ITEM : KILL_BY_MONSTER_DROP_USE_ITEM;
  }

  /**
   * {@code TBaseObject.ReAlive} (ObjBase.pas:21199) plus the {@code CmdReAlive} tail
   * (ObjBase.pas:14019) that also refills HP and refreshes the ability block: the player
   * stands up on the same cell and every observer receives {@code SM_ALIVE}.
   */
  private boolean revivePlayer(int playerId) {
    Player player = requirePlayer(playerId);
    if (player.ability.alive()) return false;
    Ability before = player.ability;
    player.diedAt = 0;
    // CmdReAlive sets m_WAbil.HP := m_WAbil.MaxHP; MP is left where it was, as in Delphi.
    player.setAbility(player.ability.withHp(player.ability.maxHp()));
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.setAbility(before);
      throw failure;
    }
    emitToObserversAndSelf(player, new WorldEvent.ObjectRevived(player.snapshot()));
    emit(player, new WorldEvent.AbilityChanged(
        player.id, player.ability, player.gold, player.job, player.weights()));
    emitSubAbility(player);
    return true;
  }

  /**
   * The two PK timers of {@code TBaseObject.Run}, folded into one pass:
   *
   * <ul>
   *   <li>ObjBase.pas:4042 — every {@code dwDecPkPointTime} (2 minutes) a positive
   *       {@code m_nPkPoint} loses {@code nDecPkPointCount} (1). {@code DecPKPoint}
   *       re-broadcasts the name colour only when the derived {@code PKLevel} changed, and
   *       only while the old level was 1 or 2 ({@code (nC > 0) and (nC <= 2)}) — a 深红
   *       character dropping from 3 to 2 stays silently red until it reaches 黄名.</li>
   *   <li>ObjBase.pas:18868 {@code CheckPKStatus} — {@code m_boPKFlag} clears
   *       {@code dwPKFlagTime} (60s) after the last blow traded with another player, and the
   *       colour goes back out.</li>
   * </ul>
   */
  private void decayPkPoints() {
    long now = clock.getAsLong();
    for (Player player : players.values()) {
      if (now - player.decPkPointTick > PkLevel.DEC_PK_POINT_MILLIS) {
        player.decPkPointTick = now;
        if (player.pkPoint > 0) {
          int previousLevel = PkLevel.of(player.pkPoint);
          player.pkPoint = Math.max(0, player.pkPoint - PkLevel.DEC_PK_POINT_COUNT);
          if (PkLevel.of(player.pkPoint) != previousLevel
              && previousLevel > 0 && previousLevel <= 2) {
            broadcastNameColor(player);
          }
          try {
            persist(player);
          } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "pk point decay save failed for " + player.name, failure);
          }
        }
      }
      if (player.pkFlag && now - player.pkFlagTick > PkLevel.PK_FLAG_MILLIS) {
        player.pkFlag = false;
        broadcastNameColor(player);
      }
    }
  }

  /**
   * {@code TBaseObject.SetPKFlag} (ObjBase.pas:21220): trading blows with another player puts
   * the <em>attacker</em> into the 60-second PK colour, provided neither side is already red
   * and the fight is not inside a FIGHT zone. The flag is refreshed, not stacked.
   */
  private void setPkFlag(WorldObject victim, WorldObject attacker) {
    if (!(victim instanceof Player target) || !(attacker instanceof Player killer)) return;
    if (PkLevel.isRed(target.pkPoint) || PkLevel.isRed(killer.pkPoint)) return;
    if (target.map.flags().isFightZone()) return;
    if (target.pkFlag) return; // Delphi guards the whole block with "not m_boPKFlag" on Self.
    killer.pkFlagTick = clock.getAsLong();
    if (!killer.pkFlag) {
      killer.pkFlag = true;
      broadcastNameColor(killer);
    }
  }

  /**
   * The murder branch of {@code TBaseObject.Die} (ObjBase.pas:20938-20953) with the shipped
   * defaults ({@code boKillHumanWinLevel/Exp} both False, no guild war, no castle): the
   * killer gains {@code nKillHumanAddPKPoint} (100) — one whole PK level — unless the victim
   * was flagged, in which case {@code IsGoodKilling} makes it a lawful kill.
   *
   * <p>The two chat lines are the shipped GBK strings {@code g_sYouMurderedMsg} and
   * {@code g_sYouKilledByMsg} (M2Share.pas:3236-3237). The luck penalty
   * ({@code AddBodyLuck(-500)}) and {@code MakeWeaponUnlock} need the luck / weapon-lock model
   * and stay deferred.
   */
  private void applyMurderPenalty(Player victim, WorldObject killer) {
    if (!(killer instanceof Player murderer)) return;
    if (victim.map.flags().isFightZone()) return;
    if (PkLevel.isRed(victim.pkPoint)) return; // Die only enters the branch while PKLevel < 2.
    if (victim.pkFlag) {
      // IsGoodKilling (ObjBase.pas:21251): killing a flagged player is lawful.
      emit(murderer, new WorldEvent.SystemMessage(murderer.id, PROTECTED_BY_LAW_MESSAGE));
      return;
    }
    int previousLevel = PkLevel.of(murderer.pkPoint);
    murderer.pkPoint += PkLevel.KILL_HUMAN_ADD_PK_POINT;
    emit(murderer, new WorldEvent.SystemMessage(murderer.id, YOU_MURDERED_MESSAGE));
    emit(victim, new WorldEvent.SystemMessage(
        victim.id, String.format(YOU_KILLED_BY_MESSAGE, murderer.name)));
    // ObjBase.pas:20948 — a murder costs the killer nKillHumanDecLuckPoint (500) body luck.
    murderer.bodyLuck = murderer.bodyLuck.add(-KILL_HUMAN_DEC_LUCK_POINT);
    // ObjBase.pas:20949-20951 — killing a wholly innocent victim (PKLevel < 1) has a 1-in-5
    // chance to curse the killer's weapon. The unqualified PKLevel is the victim's.
    if (PkLevel.of(victim.pkPoint) < 1
        && random.nextInt(WorldRandom.Stream.WEAPON_UNLOCK, WEAPON_MAKE_UNLUCK_ON_MURDER) == 0) {
      makeWeaponUnlock(murderer);
    }
    // IncPkPoint (ObjBase.pas:2364) refreshes the colour whenever the level moved.
    if (PkLevel.of(murderer.pkPoint) != previousLevel) broadcastNameColor(murderer);
    try {
      persist(murderer);
    } catch (RuntimeException failure) {
      LOG.log(Level.WARNING, "pk point save failed for " + murderer.name, failure);
    }
  }

  /**
   * {@code TBaseObject.MakeWeaponUnlock} (ObjBase.pas:2393): the "weapon is cursed" penalty.
   * If the worn weapon still has luck points ({@code btValue[3] > 0}) one is burned off,
   * otherwise a curse point is added ({@code btValue[4]}, capped at 10). Either way the player
   * is told 「你的武器被诅咒了」 and the ability block is refreshed. A player with no weapon is
   * untouched ({@code wIndex <= 0 -> Exit}).
   *
   * <p>Because {@code RecalcAbilitys} reads the base catalog item — not the per-instance
   * {@code btValue} — these points never change the server-side combat luck; they only fold into
   * the client-facing {@code TClientItem} (via {@code GetItemAddValue}) and the NPC upgrade
   * formula. The recalc/ability refresh is nonetheless reproduced so the observable message and
   * client item stay faithful.
   */
  private void makeWeaponUnlock(Player player) {
    BackpackItem weapon = player.equipment.at(EquipmentSlot.WEAPON).orElse(null);
    if (weapon == null) return; // m_UseItems[U_WEAPON].wIndex <= 0 -> Exit.
    WeaponPoints points = weapon.weaponPoints();
    WeaponPoints cursed;
    if (points.luck() > 0) {
      cursed = points.withLuck(points.luck() - 1);
    } else if (points.curse() < WeaponPoints.MAX_CURSE) {
      cursed = points.withCurse(points.curse() + 1);
    } else {
      cursed = points; // Already fully cursed: still emits the message, changes nothing.
    }
    if (!cursed.equals(points)) {
      player.equipment = player.equipment.with(
          EquipmentSlot.WEAPON, weapon.withWeaponPoints(cursed));
    }
    emit(player, new WorldEvent.SystemMessage(player.id, WEAPON_CURSED_MESSAGE));
    // MakeWeaponUnlock ends with RecalcAbilitys + RM_ABILITY/RM_SUBABILITY for a player object.
    recalculateAbilities(player);
    emit(player, new WorldEvent.AbilityChanged(
        player.id, player.ability, player.gold, player.job,
        player.weightsAtLevel(player.ability.level())));
    emitSubAbility(player);
  }

  /**
   * {@code RefNameColor} (ObjBase.pas:2263) → {@code SendRefMsg(RM_CHANGENAMECOLOR)}: every
   * observer, and the player itself, re-reads the name colour from {@code GetCharColor}.
   */
  private void broadcastNameColor(Player player) {
    WorldEvent event = new WorldEvent.NameColorChanged(
        player.id, PkLevel.nameColor(player.pkPoint, player.pkFlag), player.pkPoint);
    emitToObserversAndSelf(player, event);
  }

  /**
   * {@code TBaseObject.Run} (ObjBase.pas:3718): HP and MP tick back up on their own clocks.
   * The Delphi counters advance by {@code (now - m_dwHPMPTick) div 20} per pass and fire when
   * they reach {@code nHealthFillTime} (300) / {@code nSpellFillTime} (800) — i.e. every
   * 6 seconds for HP and 16 seconds for MP — restoring {@code MaxHP div 75 + 1} and
   * {@code MaxMP div 18 + 1}. Dead objects regenerate nothing.
   */
  private void regenerateHealthAndSpell() {
    long now = clock.getAsLong();
    for (Player player : players.values()) {
      if (player.lastRegenAt == 0) {
        player.lastRegenAt = now;
        continue;
      }
      long elapsed = now - player.lastRegenAt;
      long units = elapsed / HP_MP_TICK_MILLIS;
      // Sub-20ms slivers are left on the clock so a fast tick interval still accumulates,
      // which is what Delphi's integer division against m_dwHPMPTick effectively does.
      if (units <= 0) continue;
      player.lastRegenAt = now - elapsed % HP_MP_TICK_MILLIS;
      if (!player.ability.alive()) continue;
      player.healthTicks += units;
      player.spellTicks += units;
      boolean changed = false;
      if (player.ability.hp() < player.ability.maxHp() && player.healthTicks >= HEALTH_FILL_TICKS) {
        int step = player.ability.maxHp() / 75 + 1;
        player.ability = player.ability.withHp(player.ability.hp() + step);
        player.baseAbility = player.rebase(player.ability);
        player.healthTicks = 0;
        changed = true;
      }
      if (player.ability.mp() < player.ability.maxMp() && player.spellTicks >= SPELL_FILL_TICKS) {
        int step = player.ability.maxMp() / 18 + 1;
        player.ability = player.ability.withMp(player.ability.mp() + step);
        player.baseAbility = player.rebase(player.ability);
        player.spellTicks = 0;
        changed = true;
      }
      // Delphi clears a counter that reached the threshold even when the pool was already full.
      if (player.healthTicks >= HEALTH_FILL_TICKS) player.healthTicks = 0;
      if (player.spellTicks >= SPELL_FILL_TICKS) player.spellTicks = 0;
      if (changed) {
        emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
      }
    }
  }

  /**
   * {@code TBaseObject.Run}'s dead branch (ObjBase.pas:3769): after
   * {@code g_Config.dwMakeGhostTime} (3 minutes) the corpse turns into a ghost, which for a
   * player object means it is removed from the map exactly like a disconnect would.
   */
  private void makeGhostsOfExpiredCorpses() {
    long now = clock.getAsLong();
    List<Player> expired = new ArrayList<>();
    for (Player player : players.values()) {
      if (player.ability.alive() || player.diedAt == 0) continue;
      if (now - player.diedAt > MAKE_GHOST_MILLIS) expired.add(player);
    }
    for (Player player : expired) {
      try {
        leave(player.id);
      } catch (RuntimeException error) {
        LOG.log(Level.WARNING, "ghosting failed for " + player.name, error);
      }
    }
  }

  private static final double[] GROUP_EXP_BONUS = {
      1.0, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2.0, 2.1, 2.2
  };

  /**
   * {@code TPlayObject.ClientGroupClose} / {@code CM_GROUPMODE} (ObjBase.pas:4777, 17522).
   */
  private boolean changeGroupMode(int playerId, boolean allow) {
    Player player = requirePlayer(playerId);
    player.allowGroup = allow;
    emit(player, new WorldEvent.GroupModeChanged(player.id, allow));
    if (!allow && player.group != null) {
      if (player.group.isLeader(player.id)) {
        // Delphi leader: SysMsg('If you want to withdraw from group, use function of (del member).', c_Red, t_Hint);
        emit(player, new WorldEvent.SystemMessage(player.id, "无法直接关闭队伍，请使用删除成员功能退出小组"));
      } else {
        leaveGroup(player);
      }
    }
    return true;
  }

  /**
   * {@code TPlayObject.ClientCreateGroup} (ObjBase.pas:17542).
   */
  private boolean createPlayerGroup(int playerId, String targetName) {
    Player leader = requirePlayer(playerId);
    if (leader.group != null) {
      emit(leader, new WorldEvent.GroupCreateFailed(playerId, -1));
      return false;
    }
    Player target = findPlayerByName(targetName);
    if (target == null || target.id == playerId || !target.ability.alive() || target.diedAt != 0) {
      emit(leader, new WorldEvent.GroupCreateFailed(playerId, -2));
      return false;
    }
    if (target.group != null) {
      emit(leader, new WorldEvent.GroupCreateFailed(playerId, -3));
      return false;
    }
    if (!target.allowGroup) {
      emit(leader, new WorldEvent.GroupCreateFailed(playerId, -4));
      return false;
    }

    PlayerGroup group = new PlayerGroup(leader.id);
    group.add(target.id);
    leader.group = group;
    target.group = group;
    leader.allowGroup = true;

    emit(leader, new WorldEvent.GroupCreated(leader.id));
    sendGroupText(group, String.format("%s 已加入小组", leader.name));
    sendGroupText(group, String.format("%s 已加入小组", target.name));
    broadcastGroupMembers(group);
    return true;
  }

  /**
   * {@code TPlayObject.ClientAddGroupMember} (ObjBase.pas:17579).
   */
  private boolean addPlayerGroupMember(int playerId, String targetName) {
    Player leader = requirePlayer(playerId);
    if (leader.group == null || !leader.group.isLeader(playerId)) {
      emit(leader, new WorldEvent.GroupAddMemberFailed(playerId, -1));
      return false;
    }
    PlayerGroup group = leader.group;
    if (group.size() >= PlayerGroup.MAX_MEMBERS) {
      emit(leader, new WorldEvent.GroupAddMemberFailed(playerId, -5));
      return false;
    }
    Player target = findPlayerByName(targetName);
    if (target == null || target.id == playerId || !target.ability.alive() || target.diedAt != 0) {
      emit(leader, new WorldEvent.GroupAddMemberFailed(playerId, -2));
      return false;
    }
    if (target.group != null) {
      emit(leader, new WorldEvent.GroupAddMemberFailed(playerId, -3));
      return false;
    }
    if (!target.allowGroup) {
      emit(leader, new WorldEvent.GroupAddMemberFailed(playerId, -4));
      return false;
    }

    group.add(target.id);
    target.group = group;
    emit(leader, new WorldEvent.GroupMemberAdded(leader.id));
    sendGroupText(group, String.format("%s 已加入小组", target.name));
    broadcastGroupMembers(group);
    return true;
  }

  /**
   * {@code TPlayObject.ClientDelGroupMember} (ObjBase.pas:17620).
   */
  private boolean delPlayerGroupMember(int playerId, String targetName) {
    Player actor = requirePlayer(playerId);
    if (actor.group == null || !actor.group.isLeader(playerId)) {
      emit(actor, new WorldEvent.GroupDelMemberFailed(playerId, -1));
      return false;
    }
    PlayerGroup group = actor.group;
    Player target = findPlayerByName(targetName);
    if (target == null) {
      emit(actor, new WorldEvent.GroupDelMemberFailed(playerId, -2));
      return false;
    }
    if (!group.contains(target.id)) {
      emit(actor, new WorldEvent.GroupDelMemberFailed(playerId, -3));
      return false;
    }

    if (target.id == actor.id) {
      // Leader deletes self -> disbands the party
      disbandGroup(group);
      return true;
    }

    group.remove(target.id);
    target.group = null;
    emit(target, new WorldEvent.GroupCancelled(target.id));
    emit(target, new WorldEvent.SystemMessage(target.id, String.format("%s 已退出小组", target.name)));
    emit(actor, new WorldEvent.GroupMemberDeleted(actor.id, target.name));

    if (group.size() <= 1) {
      disbandGroup(group);
    } else {
      sendGroupText(group, String.format("%s 已退出小组", target.name));
      broadcastGroupMembers(group);
    }
    return true;
  }

  /**
   * Member leaves group (ObjBase.pas:18947, 21636 {@code LeaveGroup}).
   */
  private void leaveGroup(Player member) {
    if (member.group == null) return;
    PlayerGroup group = member.group;
    member.group = null;
    group.remove(member.id);
    emit(member, new WorldEvent.GroupCancelled(member.id));
    emit(member, new WorldEvent.SystemMessage(member.id, String.format("%s 已退出小组", member.name)));

    if (group.isLeader(member.id) || group.size() <= 1) {
      disbandGroup(group);
    } else {
      sendGroupText(group, String.format("%s 已退出小组", member.name));
      broadcastGroupMembers(group);
    }
  }

  /**
   * Disbands the party (ObjBase.pas:21647 {@code CancelGroup}).
   */
  private void disbandGroup(PlayerGroup group) {
    List<Integer> members = List.copyOf(group.memberIds());
    for (int memberId : members) {
      Player p = players.get(memberId);
      if (p != null) {
        p.group = null;
        emit(p, new WorldEvent.GroupCancelled(p.id));
        emit(p, new WorldEvent.SystemMessage(p.id, "你的小组已解散"));
      }
    }
  }

  private void broadcastGroupMembers(PlayerGroup group) {
    List<String> names = new ArrayList<>();
    for (int memberId : group.memberIds()) {
      Player p = players.get(memberId);
      if (p != null) names.add(p.name);
    }
    for (int memberId : group.memberIds()) {
      Player p = players.get(memberId);
      if (p != null) {
        emit(p, new WorldEvent.GroupMembersChanged(p.id, names));
      }
    }
  }

  private void sendGroupText(PlayerGroup group, String text) {
    for (int memberId : group.memberIds()) {
      Player p = players.get(memberId);
      if (p != null) {
        emit(p, new WorldEvent.SystemMessage(p.id, text));
      }
    }
  }

  /**
   * {@code TPlayObject.GainExp} (ObjBase.pas:15557): If the player is in a party, find all
   * living members on the same map within 12 tiles. If more than one member qualifies, apply
   * the party size bonus and distribute experience proportional to level. Otherwise, award
   * full base experience directly to the killer.
   */
  private void distributeMonsterExperience(Player killer, long baseExp) {
    if (baseExp <= 0) return;
    if (killer.group == null) {
      awardExperience(killer, baseExp);
      return;
    }
    List<Player> eligible = new ArrayList<>();
    for (int memberId : killer.group.memberIds()) {
      Player member = players.get(memberId);
      if (member != null && member.ability.alive() && member.diedAt == 0
          && member.map == killer.map
          && Math.abs(member.position.x() - killer.position.x()) <= 12
          && Math.abs(member.position.y() - killer.position.y()) <= 12) {
        eligible.add(member);
      }
    }
    if (eligible.size() <= 1) {
      awardExperience(killer, baseExp);
      return;
    }
    int n = eligible.size();
    double bonusFactor = GROUP_EXP_BONUS[Math.min(n, GROUP_EXP_BONUS.length - 1)];
    long totalExp = Math.round(baseExp * bonusFactor);
    int sumLevel = 0;
    for (Player p : eligible) {
      sumLevel += p.ability.level();
    }
    for (Player p : eligible) {
      long share = sumLevel > 0
          ? Math.round((double) totalExp / sumLevel * p.ability.level())
          : Math.round((double) totalExp / n);
      awardExperience(p, share);
    }
  }

  private Player findPlayerByName(String name) {
    if (name == null || name.isBlank()) return null;
    String trimmed = name.trim();
    Integer id = playersByName.get(trimmed);
    if (id != null) return players.get(id);
    for (Player p : players.values()) {
      if (p.name.equalsIgnoreCase(trimmed)) return p;
    }
    return null;
  }

  /**
   * {@code TPlayObject.GetExp} (ObjBase.pas:1843): accumulate, announce, then level while the
   * threshold is met. Delphi only checks once per kill, but a single monster can never award
   * more than one level's worth, so the loop below is the same behaviour with a guard against
   * configured multipliers that could.
   */
  private void awardExperience(Player player, long experience) {
    if (experience <= 0) return;
    Ability before = player.ability;
    EquipmentBonus bonusBefore = player.bonus;
    long nextTotal = before.experience() + experience;
    player.setAbility(before.addExperience(experience));
    BodyLuck luckBefore = player.bodyLuck;
    // GetExp (ObjBase.pas:1848) grows body luck by 0.2% of the experience gained.
    player.bodyLuck = player.bodyLuck.add(experience * 0.002);
    List<Ability> reached = new ArrayList<>();
    // Delphi levels up inside GetExp, before the save; the same order is kept here so a
    // storage failure rolls back the level as well as the experience.
    while (player.ability.readyToLevel()) {
      int levelBefore = player.baseAbility.level();
      applyLevelUp(player, player.baseAbility.consumeLevelExperience());
      reached.add(player.ability);
      // GetExp (ObjBase.pas:1859) adds a flat 100 body luck each time it crosses a level.
      player.bodyLuck = player.bodyLuck.add(100);
      // A capped character keeps burning overflow experience without gaining levels; stop
      // once the level can no longer move so the loop always terminates.
      if (player.baseAbility.level() == levelBefore) break;
    }
    try {
      persist(player);
    } catch (RuntimeException failure) {
      player.setAbility(before);
      player.bonus = bonusBefore;
      player.bodyLuck = luckBefore;
      throw failure;
    }
    emit(player, new WorldEvent.ExperienceGained(player.id, experience, nextTotal));
    for (Ability level : reached) announceLevelUp(player, level);
  }

  /**
   * {@code TBaseObject.HasLevelUp} (ObjBase.pas:1943): refresh {@code MaxExp}, rebuild the
   * level-derived stats, re-apply equipment and top the pools up.
   */
  private void applyLevelUp(Player player, Ability relevelled) {
    player.baseAbility = LevelAbilities.forLevel(player.job, relevelled.level(), relevelled);
    // RecalcAbilitys re-derives the working ability (m_WAbil) from the new naked values.
    recalculateAbilities(player);
    // HasLevelUp ends with IncHealthSpell(2000, 2000), which clamps at the new maxima.
    player.ability = player.ability.withHp(player.ability.hp() + 2000)
        .withMp(player.ability.mp() + 2000);
    player.baseAbility = player.rebase(player.ability);
  }

  /** The {@code RM_LEVELUP} fan-out: SM_LEVELUP, the full ability block and the new pools. */
  private void announceLevelUp(Player player, Ability reached) {
    emit(player, new WorldEvent.LevelUp(
        player.id, reached.level(), reached.experience(), reached));
    // RM_LEVELUP's handler also refreshes the whole ability block (ObjBase.pas:5584).
    emit(player, new WorldEvent.AbilityChanged(
        player.id, reached, player.gold, player.job, player.weightsAtLevel(reached.level())));
    emitSubAbility(player);
    emitToObserversAndSelf(player, new WorldEvent.HealthChanged(player.snapshot()));
  }

  private void dropLoot(Monster monster) {
    for (ItemDrop drop : monster.template.drops()) {
      if (random.nextInt(WorldRandom.Stream.LOOT_DROP, drop.oneIn()) != 0) continue;
      Position dropPosition = findDropPosition(monster.map, monster.position);
      if (dropPosition == null) continue;
      int itemId = allocateObjectId();
      int looks = itemDatabase.find(drop.name())
          .map(StdItem::looks)
          .orElse(drop.looks());
      GroundItem item = new GroundItem(itemId, drop.name(), looks, monster.map.id(), dropPosition);
      groundItems.put(itemId, item);
      itemDropTimes.put(itemId, clock.getAsLong());
      monster.droppedItemIds.add(itemId);
      WorldEvent appeared = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(monster.map, dropPosition, 0)) {
        emit(players.get(viewerId), appeared);
      }
    }

    int gold = 0;
    for (MonsterDropTable.GoldDrop drop : monster.template.goldDrops()) {
      if (random.nextInt(WorldRandom.Stream.LOOT_DROP, drop.oneIn()) != 0) continue;
      gold += (drop.count() / 2) + random.nextInt(WorldRandom.Stream.LOOT_DROP, drop.count());
    }
    scatterGold(monster, gold);
  }

  private void scatterGold(Monster monster, int gold) {
    if (gold <= 0) return;
    int remaining = gold;
    for (int pile = 0; pile < MAX_GOLD_DROP_PILES && remaining > 0; pile++) {
      int amount = Math.min(remaining, MON_ONE_DROP_GOLD_COUNT);
      remaining -= amount;
      Position dropPosition = findDropPosition(monster.map, monster.position, GOLD_DROP_RANGE);
      if (dropPosition == null) break;
      GroundItem item = putGoldPile(monster.map, dropPosition, amount);
      monster.droppedItemIds.add(item.id());
      WorldEvent appeared = new WorldEvent.ItemAppeared(item);
      for (int viewerId : visibleIds(monster.map, item.position(), 0)) {
        emit(players.get(viewerId), appeared);
      }
    }
  }

  private GroundItem putGoldPile(GameMap map, Position position, int amount) {
    if (amount < 1) throw new IllegalArgumentException("gold amount must be positive");
    for (GroundItem item : groundItems.values()) {
      if (!item.gold() || !item.mapId().equals(map.id()) || !item.position().equals(position)) continue;
      int merged = item.count() + amount;
      if (merged > MON_ONE_DROP_GOLD_COUNT) continue;
      GroundItem updated = item.withCountAndLooks(merged, goldShape(merged));
      groundItems.put(updated.id(), updated);
      itemDropTimes.put(updated.id(), clock.getAsLong());
      return updated;
    }
    int itemId = allocateObjectId();
    GroundItem item = new GroundItem(itemId, GroundItem.GOLD_NAME, goldShape(amount), map.id(), position, amount);
    groundItems.put(itemId, item);
    itemDropTimes.put(itemId, clock.getAsLong());
    return item;
  }

  private static int goldShape(int gold) {
    int shape = 112;
    if (gold >= 30) shape = 113;
    if (gold >= 70) shape = 114;
    if (gold >= 300) shape = 115;
    if (gold >= 1000) shape = 116;
    return shape;
  }

  private Position findDropPosition(GameMap map, Position center) {
    return findDropPosition(map, center, 2);
  }

  private Position findDropPosition(GameMap map, Position center, int maxRadius) {
    if (map.isTerrainWalkable(center)) return center;
    for (int radius = 1; radius <= maxRadius; radius++) {
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dy = -radius; dy <= radius; dy++) {
          Position candidate = new Position(center.x() + dx, center.y() + dy);
          if (map.isTerrainWalkable(candidate)) return candidate;
        }
      }
    }
    return null;
  }

  /**
   * Scans every registered door on every loaded map and closes those whose 5-second
   * open duration has elapsed, mirroring {@code TUserEngine.ProcessMapDoor} (500ms timer).
   * Broadcasts {@code SM_CLOSEDOOR} to observers within +/-12 cells of the closed anchor.
   */
  private void closeDoorsPeriodically() {
    long now = clock.getAsLong();
    for (GameMap map : maps.values()) {
      for (DoorInfo door : map.doors()) {
        if (door.status().opened() && (now - door.status().openedAtMillis() >= DOOR_AUTO_CLOSE_MILLIS)) {
          door.status().close();
          WorldEvent closedEvent = new WorldEvent.DoorClosed(map.id(), door.anchor());
          for (int viewerId : playersInSquare(map, door.anchor())) {
            emit(players.get(viewerId), closedEvent);
          }
        }
      }
    }
  }

  /**
   * Processes registered MonGen spawners, replenishing missing monsters when the row's
   * respawn interval has elapsed, mirroring {@code TUserEngine.RegenMonsters}.
   */
  private void regenSpawners() {
    if (spawners.isEmpty()) return;
    long now = clock.getAsLong();
    for (Spawner spawner : spawners) {
      if (now - spawner.lastRegenAt < spawner.respawnIntervalMillis) continue;
      int alive = 0;
      for (int id : spawner.spawnedMonsterIds) {
        Monster m = monsters.get(id);
        if (m != null && m.ability.alive()) alive++;
      }
      spawner.spawnedMonsterIds.removeIf(id -> {
        Monster m = monsters.get(id);
        return m == null || !m.ability.alive();
      });
      int missing = spawner.count - alive;
      if (missing <= 0) continue;
      spawner.lastRegenAt = now;
      for (int i = 0; i < missing; i++) {
        Position pos = pickSpawnerCell(spawner.map, spawner.center, spawner.radius);
        if (pos == null) break;
        Direction dir = Direction.fromCode(random.nextInt(WorldRandom.Stream.SPAWN, 8));
        WorldObjectSnapshot snapshot = spawn(spawner.template, spawner.map.id(), pos, dir);
        spawner.spawnedMonsterIds.add(snapshot.id());
      }
    }
  }

  private Position pickSpawnerCell(GameMap map, Position center, int radius) {
    if (radius <= 0) {
      return map.canWalk(center) ? center : null;
    }
    for (int attempt = 0; attempt < 30; attempt++) {
      int x = center.x() + random.nextInt(WorldRandom.Stream.SPAWN, 2 * radius + 1) - radius;
      int y = center.y() + random.nextInt(WorldRandom.Stream.SPAWN, 2 * radius + 1) - radius;
      Position candidate = new Position(x, y);
      if (map.canWalk(candidate)) return candidate;
    }
    return null;
  }

  /**
   * Persists online players whose last save was at least {@code saveIntervalMillis} ago,
   * mirroring {@code TUserEngine.ProcessHumans} -> {@code SaveHumanRcd} (10-minute default).
   */
  private void savePlayersPeriodically() {
    long now = clock.getAsLong();
    for (Player player : players.values()) {
      if (now - player.lastSavedAt >= config.saveIntervalMillis()) {
        try {
          persist(player);
          player.lastSavedAt = now;
        } catch (RuntimeException error) {
          LOG.log(Level.WARNING, "periodic player save failed for " + player.name, error);
        }
      }
    }
  }

  private void updateMonsters() {
    long now = clock.getAsLong();
    List<Monster> snapshot = new ArrayList<>(monsters.values());
    for (Monster monster : snapshot) {
      if (!monster.ability.alive()) {
        if (now - monster.diedAt >= config.corpseLingerMillis()) {
          removeMonster(monster);
        }
        continue;
      }
      // TMonster.Run with m_boNoAttackMode (ObjMon.pas:449) skips the entire
      // target/chase/attack block, so a stationary dummy never even looks for a player.
      // (Delphi's TTrainer is an ObjNpc-class object whose Run never reaches the m_boShowHP
      // walk either, so a stationary monster keeps its 心灵启示 bar until it is re-cast.)
      if (monster.template.behavior() == MonsterBehavior.STATIONARY) continue;
      // 心灵启示's m_boShowHP walk (ObjBase.pas:4029) for monsters.
      expireOpenHealth(monster, now);
      // 圣言术's m_boRunAwayMode (ObjMon.pas:447-460): while the fear freeze is running the
      // whole chase/attack block is skipped — the monster stands still. TChickenDeer.Run
      // re-evaluates the flag on every walk step of its own, so only AGGRESSIVE monsters freeze.
      if (monster.template.behavior() == MonsterBehavior.AGGRESSIVE && monster.runAwayUntil != 0) {
        if (now < monster.runAwayUntil) continue;
        monster.runAwayUntil = 0;
      }
      Player target = acquireTarget(monster);
      if (target == null) continue;
      int distance = monster.position.distanceTo(target.position);
      if (monster.template.behavior() == MonsterBehavior.PASSIVE_FLEE) {
        monsterFlee(monster, target, now);
        continue;
      }
      if (distance == 1) {
        monsterAttack(monster, target, now);
      } else {
        monsterChase(monster, target, now);
      }
    }
  }

  private Player acquireTarget(Monster monster) {
    Player current = players.get(monster.targetId);
    if (current != null && isAttackTarget(current) && current.map.id().equals(monster.map.id())
        && monster.position.distanceTo(current.position) <= config.viewRange()) {
      return current;
    }
    monster.targetId = 0;
    Player closest = null;
    int closestDistance = Integer.MAX_VALUE;
    for (int viewerId : visibleIds(monster.map, monster.position, monster.id)) {
      Player candidate = players.get(viewerId);
      if (candidate == null || !isAttackTarget(candidate)) continue;
      // The search gate every monster AI branch shares: `if not BaseObject.m_boHideMode or
      // m_boCoolEye then` (ObjMon.pas:564/1238/1421/1531, ObjMon2.pas:235/551, ObjAxeMon.pas:145,
      // ObjBase.pas:22680). A cloaked player is invisible to a monster unless that instance's
      // per-spawn CoolEye roll succeeded; a target acquired before the cloak landed is kept —
      // clearing it is the cloak's own ±9-cell sweep job (see clearMonsterAggro).
      if (candidate.hideMode && !monster.coolEye) continue;
      int distance = monster.position.distanceTo(candidate.position);
      if (distance < closestDistance) {
        closestDistance = distance;
        closest = candidate;
      }
    }
    if (closest != null) monster.targetId = closest.id;
    return closest;
  }

  /**
   * The monster half of {@code TBaseObject.IsAttackTarget} (ObjBase.pas:21370): a creature
   * never picks a player standing in a safe zone. Delphi only tests the *target* here — a
   * monster inside the zone may still be hit by a player who reaches it.
   */
  private static boolean isAttackTarget(Player player) {
    return player.ability.alive() && !player.map.isSafeZone(player.position);
  }

  private void monsterAttack(Monster monster, Player target, long now) {
    if (now - monster.lastAttackAt < monster.template.attackIntervalMillis()) return;
    monster.lastAttackAt = now;
    monster.direction = Direction.toward(monster.position, target.position);
    WorldObjectSnapshot attacker = monster.snapshot();
    WorldEvent swing = new WorldEvent.ObjectAttacked(attacker, AttackKind.HIT);
    emitToObserversAndSelf(monster, swing);
    // Monsters run the same _Attack chain: their Monster.DB HIT is checked against the
    // player's 敏捷, so a chicken (HIT=3) misses a DEFSPEED=15 character most of the time.
    int damage = rollDamage(monster.ability, target.ability, 0, 0, 0, monster, target);
    applyDamage(target, monster, applyMagicShield(target, damage));
  }

  /**
   * Chicken/deer flee AI: moves away from the nearest player in the opposite direction;
   * never attacks.
   */
  private void monsterFlee(Monster monster, Player target, long now) {
    if (now - monster.lastWalkAt < monster.template.walkIntervalMillis()) return;
    monster.lastWalkAt = now;
    Direction away = Direction.toward(target.position, monster.position);
    Position step = monster.position.translate(away, 1);
    if (monster.map.canWalk(step)) {
      stepMonster(monster, step, away);
      return;
    }
    Direction left = rotate(away, -1);
    Position stepLeft = monster.position.translate(left, 1);
    if (monster.map.canWalk(stepLeft)) {
      stepMonster(monster, stepLeft, left);
      return;
    }
    Direction right = rotate(away, 1);
    Position stepRight = monster.position.translate(right, 1);
    if (monster.map.canWalk(stepRight)) {
      stepMonster(monster, stepRight, right);
    }
  }

  private void monsterChase(Monster monster, Player target, long now) {
    if (now - monster.lastWalkAt < monster.template.walkIntervalMillis()) return;
    monster.lastWalkAt = now;
    Direction direction = Direction.toward(monster.position, target.position);
    Position step = monster.position.translate(direction, 1);
    if (monster.map.canWalk(step)) {
      stepMonster(monster, step, direction);
      return;
    }
    Direction left = rotate(direction, -1);
    Position stepLeft = monster.position.translate(left, 1);
    if (monster.map.canWalk(stepLeft)) {
      stepMonster(monster, stepLeft, left);
      return;
    }
    Direction right = rotate(direction, 1);
    Position stepRight = monster.position.translate(right, 1);
    if (monster.map.canWalk(stepRight)) {
      stepMonster(monster, stepRight, right);
    }
  }

  /**
   * Turns {@code direction} by {@code steps} eighths clockwise (negative = anticlockwise).
   * Delphi's monster walk helpers retry the two neighbouring compass points when the
   * straight step is blocked; the direction codes are cyclic (DR_UP..DR_UPLEFT = 0..7).
   */
  private static Direction rotate(Direction direction, int steps) {
    return Direction.fromCode(Math.floorMod(direction.code() + steps, 8));
  }

  private void stepMonster(Monster monster, Position target, Direction direction) {
    Position source = monster.position;
    Set<Integer> visibleBefore = new LinkedHashSet<>(visibleIds(monster.map, source, monster.id));
    monster.map.move(monster.id, source, target);
    monster.position = target;
    monster.direction = direction;
    Set<Integer> visibleAfter = new LinkedHashSet<>(visibleIds(monster.map, target, monster.id));
    WorldObjectSnapshot snapshot = monster.snapshot();
    emitMovementToObservers(snapshot, source, MovementKind.WALK, visibleBefore, visibleAfter);
    // Monsters share TBaseObject.Walk (ObjBase.pas:20169), so a monster stepping onto a fire
    // wall cell takes the same immediate burn a player would.
    struckByFireWallOnStep(monster);
  }

  private void removeMonster(Monster monster) {
    monster.map.remove(monster.id, monster.position);
    monsters.remove(monster.id);
    WorldEvent disappeared = new WorldEvent.ObjectDisappeared(monster.id);
    for (int viewerId : visibleIds(monster.map, monster.position, monster.id)) {
      emit(players.get(viewerId), disappeared);
    }
  }

  private void expireGroundItems() {
    long now = clock.getAsLong();
    List<GroundItem> expired = new ArrayList<>();
    for (Map.Entry<Integer, Long> entry : itemDropTimes.entrySet()) {
      if (now - entry.getValue() >= config.itemLingerMillis()) {
        GroundItem item = groundItems.get(entry.getKey());
        if (item != null) expired.add(item);
      }
    }
    for (GroundItem item : expired) {
      groundItems.remove(item.id());
      itemDropTimes.remove(item.id());
      WorldEvent disappeared = new WorldEvent.ItemDisappeared(item);
      GameMap map = maps.get(item.mapId());
      if (map != null) {
        for (int viewerId : visibleIds(map, item.position(), 0)) {
          emit(players.get(viewerId), disappeared);
        }
      }
    }
  }

  private List<LearnedMagic> learnedMagics(Player player) {
    return player.skills.values().stream()
        .map(skill -> new LearnedMagic(skill, magicCatalog.require(skill.magicId())))
        .toList();
  }

  private void persist(Player player) {
    playerStateStore.save(player.state());
  }

  private MoveResult rejectMove(Player player, Position target, WorldEvent.MoveRejection reason) {
    emit(player, new WorldEvent.MoveRejected(player.id, target, reason));
    return MoveResult.rejected(player.snapshot(), reason);
  }

  private AttackResult rejectAttack(Player player, WorldEvent.AttackRejection reason) {
    emit(player, new WorldEvent.AttackRejected(player.id, reason));
    return AttackResult.rejected(player.snapshot(), reason);
  }

  private void emitOwnVisibilityChanges(
      Player player, Set<Integer> visibleBefore, Set<Integer> visibleAfter) {
    for (int enteredId : difference(visibleAfter, visibleBefore)) {
      WorldObject entered = findObject(enteredId);
      if (entered != null) emit(player, new WorldEvent.ObjectAppeared(entered.snapshot()));
    }
    for (int leftId : difference(visibleBefore, visibleAfter)) {
      emit(player, new WorldEvent.ObjectDisappeared(leftId));
    }
  }

  private void emitItemVisibilityChanges(Player player, Position source, Position target) {
    List<GroundItem> before = visibleItems(player.map, source);
    List<GroundItem> after = visibleItems(player.map, target);
    for (GroundItem hidden : before) {
      if (!after.contains(hidden)) emit(player, new WorldEvent.ItemDisappeared(hidden));
    }
    for (GroundItem shown : after) {
      if (!before.contains(shown)) emit(player, new WorldEvent.ItemAppeared(shown));
    }
  }

  private void emitMovementToObservers(
      WorldObjectSnapshot movedObject,
      Position source,
      MovementKind movement,
      Set<Integer> visibleBefore,
      Set<Integer> visibleAfter) {
    Set<Integer> observers = new LinkedHashSet<>(visibleBefore);
    observers.addAll(visibleAfter);
    for (int observerId : observers) {
      Player observer = players.get(observerId);
      if (observer == null) continue;
      if (visibleBefore.contains(observerId) && visibleAfter.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectMoved(movedObject, source, movement));
      } else if (visibleBefore.contains(observerId)) {
        emit(observer, new WorldEvent.ObjectDisappeared(movedObject.id()));
      } else {
        emit(observer, new WorldEvent.ObjectAppeared(movedObject));
      }
    }
  }

  private static Position nearestAvailable(GameMap map, Position preferred) {
    if (!map.contains(preferred)) throw new IllegalArgumentException("spawn lies outside map: " + preferred);
    if (map.canWalk(preferred)) return preferred;
    int maxRadius = Math.max(map.width(), map.height());
    for (int radius = 1; radius < maxRadius; radius++) {
      int lowX = Math.max(0, preferred.x() - radius);
      int highX = Math.min(map.width() - 1, preferred.x() + radius);
      int lowY = Math.max(0, preferred.y() - radius);
      int highY = Math.min(map.height() - 1, preferred.y() + radius);
      for (int x = lowX; x <= highX; x++) {
        for (int y = lowY; y <= highY; y++) {
          if (x != lowX && x != highX && y != lowY && y != highY) continue;
          Position candidate = new Position(x, y);
          if (map.canWalk(candidate)) return candidate;
        }
      }
    }
    throw new IllegalStateException("map has no available spawn cell: " + map.id());
  }

  private List<Integer> visibleIds(GameMap map, Position center, int excludedId) {
    List<Integer> result = new ArrayList<>();
    for (int id : map.objectsInSquare(center, config.viewRange())) {
      if (id != excludedId && (players.containsKey(id) || monsters.containsKey(id)
          || npcs.containsKey(id))) {
        result.add(id);
      }
    }
    return result;
  }

  private List<GroundItem> visibleItems(GameMap map, Position center) {
    List<GroundItem> result = new ArrayList<>();
    for (GroundItem item : groundItems.values()) {
      if (item.mapId().equals(map.id()) && item.position().distanceTo(center) <= config.viewRange()) {
        result.add(item);
      }
    }
    return List.copyOf(result);
  }

  private List<GroundItem> itemsOn(String mapId, Position position) {
    List<GroundItem> result = new ArrayList<>();
    for (GroundItem item : groundItems.values()) {
      if (item.mapId().equals(mapId) && item.position().equals(position)) result.add(item);
    }
    return List.copyOf(result);
  }

  private GroundItem newestItemOn(String mapId, Position position) {
    GroundItem newest = null;
    for (GroundItem item : groundItems.values()) {
      if (!item.mapId().equals(mapId) || !item.position().equals(position)) continue;
      if (newest == null || item.id() > newest.id()) newest = item;
    }
    return newest;
  }

  private WorldObject objectAt(GameMap map, Position position) {
    int id = map.objectAt(position);
    return id == 0 ? null : findObject(id);
  }

  private static Set<Integer> difference(Set<Integer> left, Set<Integer> right) {
    Set<Integer> result = new LinkedHashSet<>(left);
    result.removeAll(right);
    return result;
  }

  private static void emit(Player player, WorldEvent event) {
    if (player == null) return;
    try {
      player.sink.send(event);
    } catch (RuntimeException error) {
      // A broken socket/session must not abort a world tick or roll back valid state.
      LOG.log(Level.WARNING, "world event sink failed for player " + player.id, error);
    }
  }

  private Player requirePlayer(int id) {
    Player player = players.get(id);
    if (player == null) throw new NoSuchElementException("player is not in the world: " + id);
    return player;
  }

  private WorldObject findObject(int id) {
    Player player = players.get(id);
    if (player != null) return player;
    Monster monster = monsters.get(id);
    if (monster != null) return monster;
    return npcs.get(id);
  }

  private WorldObject requireObject(int id) {
    WorldObject object = findObject(id);
    if (object == null) throw new NoSuchElementException("object is not in the world: " + id);
    return object;
  }

  private GameMap requireMap(String id) {
    GameMap map = maps.get(id);
    if (map == null) throw new NoSuchElementException("unknown map: " + id);
    return map;
  }

  private int allocateObjectId() {
    if (nextObjectId <= 0) throw new IllegalStateException("world object id space exhausted");
    return nextObjectId++;
  }

  private int allocateMakeIndex() {
    int current = nextItemMakeIndex;
    if (nextItemMakeIndex >= Integer.MAX_VALUE / 2 - 1) nextItemMakeIndex = 1;
    else nextItemMakeIndex++;
    return current;
  }

  private static int seedMakeIndex(long highWater) {
    // GetItemNumber wraps at High(Integer)/2-1; a persisted high-water beyond that restarts at 1.
    if (highWater <= 0 || highWater >= Integer.MAX_VALUE / 2 - 1) return 1;
    return (int) highWater + 1;
  }

  private PlayerState withStableMakeIndexes(PlayerState state) {
    List<BackpackItem> updated = new ArrayList<>(state.backpack().size());
    boolean modified = false;
    for (BackpackItem item : state.backpack()) {
      if (item.makeIndex() <= 0) {
        updated.add(new BackpackItem(item.item(), allocateMakeIndex(), item.dura(), item.duraMax()));
        modified = true;
      } else {
        updated.add(item);
      }
    }
    return modified
        ? new PlayerState(state.characterId(), state.ability(), updated, state.equipment(),
            state.gold(), state.pkPoint(), state.bodyLuck(), state.skills())
        : state;
  }

  private static UUID transientCharacterId(String name) {
    return UUID.nameUUIDFromBytes(("transient:" + name).getBytes());
  }

  private void claimOwnership() {
    Thread current = Thread.currentThread();
    Thread owner = ownerThread.get();
    if (owner == null && ownerThread.compareAndSet(null, current)) return;
    if (ownerThread.get() != current)
      throw new IllegalStateException("world state may only be mutated by its owner thread");
  }

  private <T> CompletableFuture<T> submit(Supplier<T> action) {
    CompletableFuture<T> future = new CompletableFuture<>();
    if (closed.get()) {
      future.completeExceptionally(new IllegalStateException("world engine is closed"));
      return future;
    }
    Pending<T> pending = new Pending<>(action, future);
    commands.add(pending);
    if (closed.get() && commands.remove(pending)) pending.cancel();
    return future;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    scheduler.shutdownNow();
    Pending<?> pending;
    while ((pending = commands.poll()) != null) pending.cancel();
  }

  private enum MagicImpactKind {
    DAMAGE, PIERCING_DAMAGE, AREA_DAMAGE, HEAL, AREA_HEAL, POISON_DECHEALTH, POISON_DAMAGEARMOR,
    /**
     * 集体隐身术's delayed {@code RM_TRANSPARENT} (Magic.pas:1300): unlike every other kind this
     * one carries no damage and is re-checked against the recipient alone, because Delphi's
     * delayed message is addressed to the friend and re-runs {@code MagMakePrivateTransparent}
     * there regardless of what happened to the caster in the meantime.
     */
    TRANSPARENT,
    /**
     * 心灵启示's delayed {@code RM_DOOPENHEALTH} (Magic.pas:527): the same self-addressed shape
     * as {@link #TRANSPARENT} — the 1500 ms delivery re-runs {@code MakeOpenHealth} on the
     * recipient, so the caster's fate inside the window is irrelevant and the target's own
     * {@code m_boShowHP} gate is what the re-cast check reads.
     */
    OPEN_HEALTH
  }

  private record PendingMagicImpact(
      long dueAt, MagicImpactKind kind, int casterId, int targetId, Position target, int power,
      int extra) {
    private PendingMagicImpact {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(target, "target");
      if (casterId <= 0 || targetId <= 0 || power < 0 || extra < 0)
        throw new IllegalArgumentException("invalid pending magic impact");
    }

    /** Compatibility constructor for DAMAGE/HEAL impacts, which never use {@code extra}. */
    PendingMagicImpact(
        long dueAt, MagicImpactKind kind, int casterId, int targetId, Position target, int power) {
      this(dueAt, kind, casterId, targetId, target, power, 0);
    }
  }

  private record Pending<T>(Supplier<T> action, CompletableFuture<T> future) {
    private Pending {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(future, "future");
    }

    private void execute() {
      try {
        future.complete(action.get());
      } catch (Throwable error) {
        future.completeExceptionally(error);
      }
    }

    private void cancel() {
      future.completeExceptionally(new CancellationException("world engine stopped"));
    }
  }

  private static final class Spawner {
    private final MonsterTemplate template;
    private final GameMap map;
    private final Position center;
    private final int radius;
    private final int count;
    private final long respawnIntervalMillis;
    private final List<Integer> spawnedMonsterIds = new ArrayList<>();
    private long lastRegenAt;

    private Spawner(MonsterTemplate template, GameMap map, Position center, int radius,
        int count, long respawnIntervalMillis) {
      this.template = template;
      this.map = map;
      this.center = center;
      this.radius = radius;
      this.count = count;
      this.respawnIntervalMillis = respawnIntervalMillis;
    }
  }

  /** Common state of every solid object tracked by the map occupancy index. */
  private sealed interface WorldObject extends AreaTargetSelector.AreaTarget permits Player, Monster, Npc {
    int id();

    @Override
    default int objectId() {
      return id();
    }

    GameMap map();

    Position position();

    Ability ability();

    void setAbility(Ability ability);

    WorldObjectSnapshot snapshot();

    /** {@code m_btHitPoint} — 准确: the attacker side of the {@code _Attack} dodge check. */
    int hitPoint();

    /** {@code m_btSpeedPoint} — 敏捷: the defender side of the same check. */
    int speedPoint();

    /** {@code m_nAntiMagic}: the target-side {@code Random(10) >= value} spell-resist gate. */
    int antiMagic();

    /** {@code m_btAntiPoison}: the target-side {@code Random(value + 7) <= 6} poison gate. */
    int antiPoison();

    /** {@code m_wStatusTimeArr[POISON_DECHEALTH]}/{@code [POISON_DAMAGEARMOR]} timers. */
    PoisonStatus poison();

    /**
     * The 心灵启示显血 state ({@code m_boShowHP} plus its two timing fields), modelled as one
     * mutable holder exactly like {@link #poison()}.
     */
    RevealedHealth revealedHealth();
  }

  /**
   * 心灵启示's显血 state (ObjBase.pas:193-196/3595/3606/4029): {@code m_boShowHP} flips at the
   * delayed {@code RM_DOOPENHEALTH} delivery, while {@code m_dwShowHPTick}/{@code m_dwShowHPInterval}
   * are stamped on the target at <em>cast</em> time (Magic.pas:525-526) — so the visible window is
   * measured from the cast, 1.5 s before the bar actually appears.
   */
  private static final class RevealedHealth {
    /** {@code m_boShowHP}: true between MakeOpenHealth and BreakOpenHealth. */
    boolean active;
    /** {@code m_dwShowHPTick}: cast-time reference the expiry check measures from. */
    long castAt;
    /** {@code m_dwShowHPInterval}: {@code GetPower13(GetRPow(SC) * 2 + 30) * 1000}, in millis. */
    long intervalMillis;
  }

  /**
   * The two 施毒术 status timers a {@link WorldObject} can carry (Common/Grobal2.pas
   * {@code POISON_DECHEALTH = 0}/{@code POISON_DAMAGEARMOR = 1}), modelled as absolute
   * "until" timestamps rather than Delphi's per-second countdown — consistent with every other
   * timed buff already in this engine (魔法盾, 烈火剑法, ...).
   */
  private static final class PoisonStatus {
    /** {@code m_wStatusTimeArr[POISON_DECHEALTH] > 0}: 0 when inactive. */
    long decHealthUntil;
    /** {@code m_btGreenPoisoningPoint} for the DECHEALTH shape specifically (see W37 notes). */
    int decHealthPoint;
    /** {@code m_LastHiter} at the moment DECHEALTH was applied; who the periodic tick credits. */
    int decHealthCasterId;
    /** Next {@link WorldEngine#POISON_DECHEALTH_TICK_MILLIS} boundary. */
    long nextDecHealthTickAt;
    /** {@code m_wStatusTimeArr[POISON_DAMAGEARMOR] > 0}: 0 when inactive. */
    long damageArmorUntil;
  }

  private static final class Player implements WorldObject {
    private final int id;
    private final UUID characterId;
    private final String name;
    // m_PEnvir equivalent: reassigned by EnterAnotherMap when a gate teleports the player.
    private GameMap map;
    /**
     * {@code m_sHomeMap} (ObjBase.pas:73): the 回城地图 瞬息移动's {@code MapRandomMove} jumps
     * to (Magic.pas:962). Delphi persists it on the character record and rewrites it from
     * {@code GetHomePoint} whenever the player stands next to a start point; this engine has
     * no such column, so {@link #enter} stamps the map the player logged into — the same map
     * every login resolves to through {@code MIR2_MAP_ID}.
     */
    private String homeMapId = "";
    private final int baseFeature;
    /**
     * {@code m_nCharStatus}: the actor's status word, rebuilt by {@code GetCharStatus}
     * (ObjBase.pas:20074) whenever a status slot flips. Seeded from the character record at
     * login; the cloak slice is the first producer of a runtime change (bit 8,
     * {@code STATE_TRANSPARENT}).
     */
    private int status;
    private final WorldEventSink sink;
    private final List<BackpackItem> backpack;
    private final int gender;
    private final int job;
    private Equipment equipment;
    /** Durable m_MagicList, kept in insertion order like Delphi's TList. */
    private final Map<Integer, PlayerSkill> skills = new LinkedHashMap<>();
    /** {@code m_Abil}: the naked character, before any worn gear is applied. */
    private Ability baseAbility;
    private EquipmentBonus bonus = EquipmentBonus.none();
    private Position position;
    private Direction direction;
    /** {@code m_WAbil}: the working ability including equipment. */
    private Ability ability;
    private long lastAttackAt = Long.MIN_VALUE / 4;
    private long lastSpellAt = Long.MIN_VALUE / 4;
    /** Transient STATE_BUBBLEDEFENCEUP: deliberately absent from PlayerState (relog clears it). */
    private long magicShieldUntil;
    private int magicShieldLevel;
    /**
     * Transient {@code m_wStatusTimeArr[STATE_DEFENCEUP]} (神圣战甲术, AC) and
     * {@code [STATE_MAGDEFENCEUP]} (幽灵盾, MAC), modelled as absolute "until" timestamps like
     * every other timed buff here. Delphi keeps them in the runtime status array only, so — as
     * with 魔法盾 — they are deliberately absent from {@link PlayerState} and a relog clears them.
     */
    private long defenceUpUntil;
    private long magDefenceUpUntil;
    /**
     * The AC/MAC the last {@code RecalcAbilitys} pass actually put on the working ability for the
     * two statuses above. {@link #rebase} subtracts exactly these, so the naked {@code m_Abil}
     * that {@link #state()} persists never carries a timed buff.
     */
    private int appliedDefenceUpBonus;
    private int appliedMagDefenceUpBonus;
    /**
     * The 隐身术 family's {@code m_wStatusTimeArr[STATE_TRANSPARENT]} deadline, written to
     * {@code now + GetPower13(30) + GetRPow(SC) * 3} seconds by {@code MagMakePrivateTransparent}
     * (Magic.pas:755). Delphi freezes status slots at or above 60000 — that is how the 隐身戒指
     * grants permanent invisibility (ObjBase.pas:2965) — and the engine has no such item yet, so
     * only the ordinary per-second countdown of ObjBase.pas:4156 is modelled. A moved step
     * rewrites the slot to 1 second (ObjBase.pas:2061/8947/9560); this timestamp then becomes
     * {@code now + 1000}. {@code 0} means the slot is idle.
     */
    private long transparentUntil;
    /**
     * {@code m_boHideMode}: what the monster target search actually reads
     * ({@code if not BaseObject.m_boHideMode or m_boCoolEye}, ObjMon.pas:564). Set with the cloak,
     * cleared by the countdown (ObjBase.pas:4170) or a relog (Initialize, ObjBase.pas:1359).
     */
    private boolean hideMode;
    /**
     * {@code m_boTransparent}: only the cloak spells ever raise it (Magic.pas:759); the countdown
     * leaves it alone. {@code RecalcAbilitys} restores {@code m_boHideMode} from it
     * ({@code if m_boTransparent and m_wStatusTimeArr[8] > 0 then m_boHideMode := True},
     * ObjBase.pas:3364), which is why the pair is kept apart here too.
     */
    private boolean transparent;
    /**
     * 心灵启示's显血 state ({@code m_boShowHP} plus the two timing fields). While active the
     * status word rebuilt by {@code charStatus} carries {@code STATE_OPENHEATH ($2)} — a latent
     * bit, exactly like Delphi, which never repaints the word from Make/BreakOpenHealth itself.
     */
    private final RevealedHealth revealedHealth = new RevealedHealth();
    private long lastSavedAt;
    /** {@code m_dwDeathTick}: 0 while alive, the death timestamp otherwise. */
    private long diedAt;
    /** {@code m_nGold}: the wallet, restored from the character record at login. */
    private long gold;
    /**
     * {@code m_nLight} (ObjBase.pas:169): the actor's light radius, rebuilt by
     * {@code RecalcAbilitys} from the right-hand slot (0 or 3). Zero until a right-hand
     * item with durability is worn; travels to the client in the high byte of the
     * RM_TURN/RM_WALK/RM_RUN/SM_LOGON {@code Series} word and via SM_CHANGELIGHT.
     */
    private int light;
    /**
     * {@code m_boRevival}: recalculated by {@code RecalcAbilitys} from the worn set — a
     * revival-capable ring/weapon grants the death-defying branch in {@code TBaseObject.Run}.
     */
    private boolean revival;
    /** {@code m_dwRevivalTick}: timestamp of the last ring revival (cooldown start). */
    private long revivalTick;
    /** {@code m_nPkPoint}: the persisted murder counter {@code PKLevel} is derived from. */
    private int pkPoint;
    /**
     * {@code m_dBodyLuck} / {@code m_nBodyLuckLevel}: the 幸运值 accumulator and its derived
     * level (ObjBase.pas:2374). Grown by experience, shrunk on death and murder; persisted as
     * {@code HumData.dBodyLuck} and rebuilt through {@code AddBodyLuck(0)} at login.
     */
    private BodyLuck bodyLuck = BodyLuck.NONE;
    /** {@code m_boPKFlag}: transient "recently fought a player" marker (SetPKFlag). */
    private boolean pkFlag;
    /** {@code m_dwPKTick}: when the PK flag was last refreshed; it clears 60s later. */
    private long pkFlagTick;
    /** {@code m_dwDecPkPointTick}: the 2-minute PK-point decay window reference. */
    private long decPkPointTick;
    /** 护身 / 不掉物品 / 不掉装备, recalculated by RecalcAbilitys like {@code m_boRevival}. */
    private DropProtection dropProtection = DropProtection.NONE;
    /**
     * {@code m_sScriptLable}: the merchant dialog label the player last selected
     * ({@code CM_MERCHANTDLGSELECT}); the repair path branches on it. Empty until a label is
     * chosen, which means "normal repair" — Delphi compares against '@s_repair' only.
     */
    private String merchantLabel = "";
    /** {@code m_dwHPMPTick}: the reference point the regeneration counters advance from. */
    private long lastRegenAt;
    /** {@code m_nHealthTick} / {@code m_nSpellTick}. */
    private long healthTicks;
    private long spellTicks;
    /**
     * {@code m_boAllowGroup}: whether the player permits party invitations. Delphi seeds it
     * to {@code False} in {@code TPlayObject.Initialize} (ObjBase.pas:1270) — a fresh
     * character refuses invitations until the client sends {@code CM_GROUPMODE} with
     * param=1 (ObjBase.pas:4777-4782). The W26 port wrongly defaulted this to true, which
     * the W30 duo regression caught: the scripted "invite before the target opened group
     * mode" step was silently succeeding instead of answering {@code SM_CREATEGROUP_FAIL}
     * with reason -4.
     */
    private boolean allowGroup = false;
    /** {@code m_GroupOwner} / {@code m_GroupMembers}: active party container. */
    private PlayerGroup group;
    /**
     * {@code m_btHitPoint} / {@code m_btSpeedPoint} (准确 / 敏捷), rebuilt by
     * {@code RecalcHitSpeed} from {@link HitSpeed} plus the worn set. Delphi seeds them to
     * {@code DEFHIT}/{@code DEFSPEED} in {@code TPlayObject.Initialize} (ObjBase.pas:1241).
     */
    private int hitPoint = HitSpeed.DEF_HIT;
    private int speedPoint = HitSpeed.DEF_SPEED;
    /** {@code m_nHitPlus}: the flat damage 攻杀剑术 adds when a power hit is consumed. */
    private int hitPlus;
    /** {@code m_nAntiMagic}: starts at 1 in RecalcAbilitys, then gear adds AC2 from StdMode 19. */
    private int antiMagic = PLAYER_ANTIMAGIC_BASE;
    /** {@code m_btAntiPoison}: gear-added poison resistance (StdMode 23 AC2). */
    private int antiPoison = PLAYER_ANTIPOISON_BASE;
    /** The three remaining SM_SUBABILITY accumulators; currently wire-visible only. */
    private int poisonRecover;
    private int healthRecover;
    private int spellRecover;
    /** {@code m_btAttackSkillCount}: swings left in the current 攻杀 cycle. */
    private int attackSkillCount;
    /** {@code m_btAttackSkillPointCount}: the swing of the cycle that arms the power hit. */
    private int attackSkillPointCount;
    /** {@code m_boPowerHit}: armed by the cadence, consumed by the next {@code CM_POWERHIT}. */
    private boolean powerHit;
    /**
     * {@code m_boUseThrusting} / {@code m_boUseHalfMoon} (ObjBase.pas:337): the toggle state of
     * 刺杀剑术 / 半月弯刀. Delphi seeds both to {@code False} in {@code Initialize} (ObjBase.pas
     * :1236), re-enables 刺杀 on login when the book has been read (ObjBase.pas:16602) and both
     * on {@code ReadBook} (ObjBase.pas:17377). They are transient runtime flags, never persisted;
     * the server reads them only to pick which {@code +LNG}/{@code +WID} tag to echo — the actual
     * {@code CM_LONGHIT}/{@code CM_WIDEHIT} handling gates on the learned skill instead.
     */
    private boolean useThrusting;
    private boolean useHalfMoon;
    /**
     * {@code m_nHitDouble} (ObjBase.pas:18622): the 烈火剑法 burst percentage divided by ten,
     * rebuilt by {@code RecalcHitSpeed} from the learned book's level.
     */
    private int hitDouble;
    /**
     * {@code m_boFireHitSkill} (ObjBase.pas:340): 烈火剑法 armed and waiting for the next
     * {@code CM_FIREHIT}. Transient like the other special-attack flags — {@code Initialize}
     * clears it (ObjBase.pas:1239) and nothing persists it.
     */
    private boolean fireHitArmed;
    /**
     * {@code m_dwLatestFireHitTick}: stamped both by {@code AllowFireHitSkill} (the 10 s re-arm
     * gate) and by the swing that spends the flag (Jacky's 禁止双烈火 guard, ObjBase.pas:22131),
     * and read by {@code TPlayObject.Run} for the 20 s expiry.
     */
    private long lastFireHitAt = Long.MIN_VALUE / 4;
    /**
     * {@code m_dwDoMotaeboTick} (ObjBase.pas:9114): stamped by {@code ClientSpellXY} when
     * executing 野蛮冲撞 (the 3 s cooldown gate).
     */
    private long lastMotaeboAt = Long.MIN_VALUE / 4;

    private Player(
        int id,
        UUID characterId,
        String name,
        GameMap map,
        Position position,
        Direction direction,
        int feature,
        int status,
        Ability ability,
        List<BackpackItem> backpack,
        Equipment equipment,
        int job,
        WorldEventSink sink) {
      this.id = id;
      this.characterId = characterId;
      this.name = name;
      this.map = map;
      this.position = position;
      this.direction = direction;
      this.baseFeature = feature;
      this.status = status;
      this.baseAbility = ability;
      this.ability = ability;
      this.backpack = new ArrayList<>(backpack);
      this.equipment = equipment;
      this.sink = sink;
      // MakeHumanFeature packs hair/dress/weapon appearance; the low bit of each byte is the
      // gender, so the caller's feature value already carries it (Grobal2.pas:2729).
      this.gender = feature == 0 ? 0 : (feature >>> 24) & 1;
      // The job is not part of the Feature word; the gate passes the character record's
      // btJob through so the level curves (RecalcLevelAbilitys) pick the right branch.
      this.job = job;
    }

    /** Total weight carried in the bag — {@code TBaseObject.RecalcBagWeight} (ObjBase.pas:18533). */
    private int bagWeight() {
      int total = 0;
      for (BackpackItem item : backpack) total += item.item().weight();
      return total;
    }

    /**
     * {@code RecalcLevelAbilitys} sets the base limit from job and level, then
     * {@code RecalcAbilitys} adds the worn set's bonuses (ObjBase.pas:1889, 2818).
     */
    private int maxWearWeight() {
      return LevelAbilities.maxWearWeight(job, ability.level()) + bonus.maxWearWeightBonus();
    }

    private int maxHandWeight() {
      return LevelAbilities.maxHandWeight(job, ability.level()) + bonus.maxHandWeightBonus();
    }

    /** The full {@code TAbility} weight block: current totals plus the recalculated maxima. */
    private WeightLimits weights() {
      return weightsAtLevel(ability.level());
    }

    /**
     * The same block for an explicit level. Awarding enough experience to cross several
     * levels at once emits one {@code RM_ABILITY} per level, and each has to carry the
     * limits of *that* level rather than the final one.
     */
    private WeightLimits weightsAtLevel(int level) {
      return new WeightLimits(
          bagWeight(), LevelAbilities.maxWeight(job, level) + bonus.maxWeightBonus(),
          bonus.wearWeight(), LevelAbilities.maxWearWeight(job, level) + bonus.maxWearWeightBonus(),
          bonus.handWeight(), LevelAbilities.maxHandWeight(job, level) + bonus.maxHandWeightBonus());
    }

    /**
     * {@code TBaseObject.GetFeature} (ObjBase.pas:19992) overlaid on the appearance the
     * character domain supplied at login.
     *
     * <p>Delphi rebuilds the whole word from {@code m_UseItems} every time, so an unequipped
     * player ends up with {@code dress = weapon = gender}. This engine instead keeps the
     * caller's byte for a slot that holds nothing, because the character record already
     * carries the dress/weapon shapes chosen at creation and the world has no other source
     * for them. Once a slot is filled the worn {@code Shape} wins, which is what makes a
     * take-on visibly change the avatar.
     */
    private int feature() {
      int dress = equipment.at(EquipmentSlot.DRESS)
          .map(item -> (item.item().shape() * 2 + gender) & 0xff)
          .orElse((baseFeature >>> 24) & 0xff);
      int weapon = equipment.at(EquipmentSlot.WEAPON)
          .map(item -> (item.item().shape() * 2 + gender) & 0xff)
          .orElse((baseFeature >>> 8) & 0xff);
      // Hair and the low race-image byte are never touched by equipment.
      return (dress << 24) | (baseFeature & 0x00ff0000) | (weapon << 8) | (baseFeature & 0xff);
    }

    /**
     * {@code GetFeatureEx} (ObjBase.pas:19982) = {@code MakeWord(HorseType, DressEffType)}.
     * Mounts and dress effects are not modelled, so both halves stay zero.
     */
    private int featureEx() {
      return 0;
    }

    @Override
    public int id() {
      return id;
    }

    @Override
    public GameMap map() {
      return map;
    }

    @Override
    public Position position() {
      return position;
    }

    @Override
    public Ability ability() {
      return ability;
    }

    /**
     * Combat and experience act on the working ability ({@code m_WAbil}); the durable
     * character record behind it ({@code m_Abil}) has to follow, otherwise a save would
     * write back pre-combat values.
     */
    @Override
    public void setAbility(Ability ability) {
      this.ability = ability;
      this.baseAbility = rebase(ability);
    }

    /**
     * Strips the equipment contribution back out of a working ability so the naked
     * {@code m_Abil} can be persisted and re-derived on the next login.
     *
     * <p>HP/MP are clamped into the naked maxima. Only {@code StdMode 63} charms raise MaxHP
     * and that slot is disabled in the shipped {@code CheckUserItems}, so the clamp cannot
     * actually bite today; it exists so a future HP-granting item degrades predictably
     * instead of tripping the Ability invariants.
     */
    private Ability rebase(Ability working) {
      int maxHp = Math.max(1, working.maxHp() - bonus.hp());
      int maxMp = Math.max(0, working.maxMp() - bonus.mp());
      return new Ability(
          Math.min(working.hp(), maxHp),
          maxHp,
          Math.min(working.mp(), maxMp),
          maxMp,
          Math.max(0, working.minDc() - bonus.minDc()),
          Math.max(0, working.maxDc() - bonus.maxDc()),
          Math.max(0, working.minAc() - bonus.minAc()),
          // Both defence statuses live on the working ability only, so rebase strips them the
          // same way it strips the worn set (see recalculateAbilities).
          Math.max(0, working.maxAc() - bonus.maxAc() - appliedDefenceUpBonus),
          Math.max(0, working.minMac() - bonus.minMac()),
          Math.max(0, working.maxMac() - bonus.maxMac() - appliedMagDefenceUpBonus),
          Math.max(0, working.minMc() - bonus.minMc()),
          Math.max(0, working.maxMc() - bonus.maxMc()),
          Math.max(0, working.minSc() - bonus.minSc()),
          Math.max(0, working.maxSc() - bonus.maxSc()),
          working.level(),
          working.experience(),
          working.maxExperience());
    }

    private PlayerState state() {
      // Persist the naked ability: worn bonuses are re-derived by RecalcAbilitys on load.
      return new PlayerState(characterId, baseAbility, backpack, equipment, gold, pkPoint,
          bodyLuck.value(), List.copyOf(skills.values()));
    }

    @Override
    public WorldObjectSnapshot snapshot() {
      return new WorldObjectSnapshot(
          id, name, WorldObjectType.PLAYER, map.id(), position, direction, feature(), status,
          light, ability);
    }

    @Override
    public int hitPoint() {
      return hitPoint;
    }

    @Override
    public int speedPoint() {
      return speedPoint;
    }

    @Override
    public int antiMagic() {
      return antiMagic;
    }

    @Override
    public int antiPoison() {
      return antiPoison;
    }

    private final PoisonStatus poison = new PoisonStatus();

    @Override
    public PoisonStatus poison() {
      return poison;
    }

    @Override
    public RevealedHealth revealedHealth() {
      return revealedHealth;
    }
  }

  private static final class Monster implements WorldObject {
    private final int id;
    private final MonsterTemplate template;
    private final GameMap map;
    private final List<Integer> droppedItemIds = new ArrayList<>();
    private final PoisonStatus poison = new PoisonStatus();
    private Position position;
    private Direction direction;
    private Ability ability;
    private int targetId;
    private long lastWalkAt;
    private long lastAttackAt;
    private long diedAt;
    /** {@code m_boCoolEye}: rolled once at spawn from the Monster.DB {@code CoolEye} column. */
    private final boolean coolEye;
    /** 心灵启示显血 state ({@code m_boShowHP} + timings), delivered by RM_DOOPENHEALTH. */
    private final RevealedHealth revealedHealth = new RevealedHealth();
    /**
     * {@code m_boRunAwayMode} deadline (Magic.pas:907-909): while in the future an aggressive
     * monster skips the whole chase/attack block — the 圣言术 fear freeze. 0 means idle.
     */
    private long runAwayUntil;

    private Monster(
        int id, MonsterTemplate template, GameMap map, Position position, Direction direction,
        long now, boolean coolEye) {
      this.id = id;
      this.template = template;
      this.map = map;
      this.position = position;
      this.direction = direction;
      this.ability = template.ability();
      this.lastWalkAt = now;
      this.lastAttackAt = now;
      this.coolEye = coolEye;
    }

    @Override
    public int id() {
      return id;
    }

    @Override
    public GameMap map() {
      return map;
    }

    @Override
    public Position position() {
      return position;
    }

    @Override
    public Ability ability() {
      return ability;
    }

    @Override
    public void setAbility(Ability ability) {
      this.ability = ability;
    }

    @Override
    public WorldObjectSnapshot snapshot() {
      return new WorldObjectSnapshot(
          id, template.name(), WorldObjectType.MONSTER, map.id(), position, direction,
          template.feature(), 0, ability);
    }

    /** {@code m_btHitPoint := Monster.wHitPoint} — straight from Monster.DB (UsrEngn.pas:2607). */
    @Override
    public int hitPoint() {
      return template.hitPoint();
    }

    /** {@code m_btSpeedPoint := Monster.wSpeed} — monsters never run RecalcHitSpeed. */
    @Override
    public int speedPoint() {
      return template.speedPoint();
    }

    /** The first-ten wired monsters keep TBaseObject.Initialize's m_nAntiMagic = 0. */
    @Override
    public int antiMagic() {
      return 0;
    }

    /** The first-ten wired monsters keep TBaseObject.Initialize's m_btAntiPoison = 0. */
    @Override
    public int antiPoison() {
      return 0;
    }

    @Override
    public PoisonStatus poison() {
      return poison;
    }

    @Override
    public RevealedHealth revealedHealth() {
      return revealedHealth;
    }
  }

  /**
   * A static, immortal stand-in for Delphi's {@code TNormNpc}/{@code TMerchant}
   * (ObjNpc.pas). NPCs occupy their cell and are seen like any other actor, but never
   * tick, never move and cannot be attacked — {@code TNormNpc.Run} is a no-op and the
   * client renders them from {@code Npc.wil} via {@code TNpcActor}.
   */
  private static final class Npc implements WorldObject {
    private final int id;
    private final String name;
    private final GameMap map;
    private final Position position;
    private final Direction direction;
    /**
     * {@code MakeMonsterFeature(RC_NPC, 0, wAppr)} (Grobal2.pas:2736): low byte
     * {@code RC_NPC = 50} selects {@code TNpcActor}; the high word is the
     * {@code Npc.wil} appearance index ({@code m_wAppearance}).
     */
    private final int feature;

    private Npc(int id, String name, GameMap map, Position position, int appearance,
        Direction direction) {
      this.id = id;
      this.name = name;
      this.map = map;
      this.position = position;
      this.direction = direction;
      this.feature = ((appearance & 0xffff) << 16) | 50;
    }

    @Override
    public int id() {
      return id;
    }

    @Override
    public GameMap map() {
      return map;
    }

    @Override
    public Position position() {
      return position;
    }

    @Override
    public Ability ability() {
      return Ability.immortal();
    }

    @Override
    public void setAbility(Ability ability) {
      // NPCs never take damage; nothing can change an immortal's ability.
    }

    @Override
    public WorldObjectSnapshot snapshot() {
      return new WorldObjectSnapshot(
          id, name, WorldObjectType.NPC, map.id(), position, direction, feature, 0);
    }

    /** {@code TNormNpc} is never an {@code IsProperTarget}, so the pair is never consulted. */
    @Override
    public int hitPoint() {
      return 0;
    }

    @Override
    public int speedPoint() {
      return 0;
    }

    @Override
    public int antiMagic() {
      return 0;
    }

    @Override
    public int antiPoison() {
      return 0;
    }

    /** NPCs are never {@code IsProperTarget}, so this timer pair is never consulted either. */
    private static final PoisonStatus NEVER_POISONED = new PoisonStatus();

    @Override
    public PoisonStatus poison() {
      return NEVER_POISONED;
    }

    /** NPCs are rejected by 心灵启示's target gate, so the显血 state is never consulted. */
    private static final RevealedHealth NEVER_REVEALED = new RevealedHealth();

    @Override
    public RevealedHealth revealedHealth() {
      return NEVER_REVEALED;
    }
  }
}
