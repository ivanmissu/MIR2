package com.mir2.world;

import java.util.Collection;

/**
 * {@code TBaseObject.RecalcHitSpeed} (ObjBase.pas:18551): the 准确 / 敏捷 pair every melee blow
 * is resolved against, plus the two 攻杀剑术 counters the same pass seeds.
 *
 * <p>Delphi recomputes this inside {@code RecalcAbilitys} and only for {@code RC_PLAYOBJECT}
 * (ObjBase.pas:3385) — monsters keep the {@code Monster.DB} values {@code TUserEngine.RegenMonsters}
 * copied straight into {@code m_btSpeedPoint}/{@code m_btHitPoint} (UsrEngn.pas:2606). The pass is:
 *
 * <pre>
 *   m_btHitPoint   := DEFHIT   + m_BonusAbil.Hit   div BonusTick.Hit;
 *   m_btSpeedPoint := DEFSPEED + m_BonusAbil.Speed div BonusTick.Speed;
 *   if m_btJob = 2 then Inc(m_btSpeedPoint, 3);          // 道士
 *   m_nHitPlus := 0; m_nHitDouble := 0; ... all m_Magic*Skill pointers := nil
 *   for each UserMagic in m_MagicList do case wMagIdx of
 *     SKILL_ONESWORD: if btLevel > 0 then Inc(m_btHitPoint, Round(9 / 3 * btLevel));
 *     SKILL_YEDO:     if btLevel > 0 then Inc(m_btHitPoint, Round(3 / 3 * btLevel));
 *                     m_nHitPlus := DEFHIT + btLevel;
 *                     m_btAttackSkillCount := 7 - btLevel;
 *                     m_btAttackSkillPointCount := Random(m_btAttackSkillCount);
 *     SKILL_ILKWANG:  if btLevel > 0 then Inc(m_btHitPoint, Round(8 / 3 * btLevel));
 *   ...
 * </pre>
 *
 * <p>{@code m_BonusAbil} is the 属性点 allocation ({@code CM_ADJUST_BONUS}), which this server
 * does not implement yet, so both accumulators are zero and the bases collapse to the two
 * {@code DEFHIT}/{@code DEFSPEED} constants. {@code RecalcAbilitys} then adds the worn set's
 * {@code m_AddAbil.wHitPoint}/{@code wSpeedPoint} on top (ObjBase.pas:3394), which is where
 * {@link EquipmentBonus#hitPoint()}/{@link EquipmentBonus#speedPoint()} enter.
 *
 * <p>The three 战士 weapon skills that feed this pass are exactly the ones {@code MagicManager
 * .DoSpell} refuses to run ({@code IsWarrSkill}, Magic.pas:211): they are passive modifiers, not
 * castable effects. 精神力战法 additionally overwrites {@code m_MagicOneSwordSkill} with its own
 * {@code UserMagic} (ObjBase.pas:18627) even though the field belongs to 基本剑术 — the pointer is
 * never read back anywhere in the M2Server sources, so the quirk has no observable effect and is
 * recorded here rather than modelled.
 */
public record HitSpeed(int hitPoint, int speedPoint, int hitPlus, int attackSkillCycle) {

  /** {@code DEFHIT} (M2Share.pas:128). */
  public static final int DEF_HIT = 5;

  /** {@code DEFSPEED} (M2Share.pas:129). */
  public static final int DEF_SPEED = 15;

  /** 基本剑术 {@code SKILL_ONESWORD} (Grobal2.pas). */
  public static final int SKILL_ONESWORD = 3;

  /** 精神力战法 {@code SKILL_ILKWANG}. */
  public static final int SKILL_ILKWANG = 4;

  /** 攻杀剑术 {@code SKILL_YEDO}. */
  public static final int SKILL_YEDO = 7;

  /** {@code Inc(m_btSpeedPoint, 3)} for 道士 (ObjBase.pas:18562). */
  private static final int TAOIST_SPEED_BONUS = 3;

  public HitSpeed {
    if (hitPoint < 0 || speedPoint < 0) throw new IllegalArgumentException("points must not be negative");
    if (hitPlus < 0) throw new IllegalArgumentException("hit plus must not be negative");
    if (attackSkillCycle < 0) throw new IllegalArgumentException("cycle must not be negative");
  }

  /**
   * The full {@code RecalcHitSpeed} result for a character of {@code job} holding {@code skills}
   * (the {@code m_MagicList} equivalent). Worn-gear bonuses are added by the caller, mirroring the
   * {@code RecalcAbilitys} ordering.
   */
  public static HitSpeed of(int job, Collection<PlayerSkill> skills) {
    int hitPoint = DEF_HIT;
    int speedPoint = DEF_SPEED + (job == LevelAbilities.JOB_TAOIST ? TAOIST_SPEED_BONUS : 0);
    int hitPlus = 0;
    int cycle = 0;
    for (PlayerSkill skill : skills) {
      switch (skill.magicId()) {
        case SKILL_ONESWORD -> hitPoint += oneSwordHitBonus(skill.level());
        case SKILL_ILKWANG -> hitPoint += ilkwangHitBonus(skill.level());
        case SKILL_YEDO -> {
          hitPoint += yedoHitBonus(skill.level());
          // Both counters are written outside the `btLevel > 0` guard, so a freshly learned
          // level-0 攻杀剑术 already arms the 7-swing cadence and the +DEFHIT damage bonus.
          hitPlus = DEF_HIT + skill.level();
          cycle = attackSkillCycle(skill.level());
        }
        default -> {
          // The remaining RecalcHitSpeed cases only cache a UserMagic pointer for skills this
          // batch does not implement (刺杀剑术/半月弯刀/烈火剑法/野蛮冲撞/逐日剑法/……).
        }
      }
    }
    return new HitSpeed(hitPoint, speedPoint, hitPlus, cycle);
  }

  /** {@code Round(9 / 3 * btLevel)} — 基本剑术 is the strongest 准确 source of the three. */
  public static int oneSwordHitBonus(int level) {
    return level <= 0 ? 0 : (int) Math.rint(9 / 3.0 * level);
  }

  /** {@code Round(8 / 3 * btLevel)} — 精神力战法: 3 / 5 / 8 at levels 1..3 (banker's rounding). */
  public static int ilkwangHitBonus(int level) {
    return level <= 0 ? 0 : (int) Math.rint(8 / 3.0 * level);
  }

  /** {@code Round(3 / 3 * btLevel)} — 攻杀剑术 contributes its level verbatim. */
  public static int yedoHitBonus(int level) {
    return level <= 0 ? 0 : (int) Math.rint(3 / 3.0 * level);
  }

  /** {@code m_btAttackSkillCount := 7 - btLevel}: one 攻杀 every 7..4 swings. */
  public static int attackSkillCycle(int level) {
    return 7 - level;
  }

  /**
   * {@code MagicManager.IsWarrSkill} (Magic.pas:211). {@code DoSpell} exits immediately for these
   * ids and {@code ClientSpellXY} skips both the action-status check and the magic interval,
   * which is why a 战士 weapon skill answers {@code +GOOD} without spending mana or a cooldown.
   */
  public static boolean isWarriorSkill(int magicId) {
    return switch (magicId) {
      // 3 基本剑术, 4 精神力战法, 7 攻杀剑术, 12 刺杀剑术, 25 半月弯刀, 26 烈火剑法,
      // 27 野蛮冲撞, 34 双龙斩, 38 狂风斩 (Grobal2.pas:1272-1309).
      case SKILL_ONESWORD, SKILL_ILKWANG, SKILL_YEDO, 12, 25, 26, 27, 34, 38 -> true;
      default -> false;
    };
  }

  /** The three ids this batch gives a behavioural implementation to. */
  public static boolean isImplementedWarriorSkill(int magicId) {
    return magicId == SKILL_ONESWORD || magicId == SKILL_ILKWANG || magicId == SKILL_YEDO;
  }
}
