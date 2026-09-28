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
public record HitSpeed(
    int hitPoint, int speedPoint, int hitPlus, int attackSkillCycle, int hitDouble) {

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

  /** 刺杀剑术 {@code SKILL_ERGUM} — the {@code SwordLongAttack} shape (wHitMode 4). */
  public static final int SKILL_ERGUM = 12;

  /** 半月弯刀 {@code SKILL_BANWOL} — the {@code SwordWideAttack} fan (wHitMode 5). */
  public static final int SKILL_BANWOL = 25;

  /**
   * 烈火剑法 {@code SKILL_FIRESWORD} — the armed one-shot burst (wHitMode 7). Unlike the other
   * two active shapes it has no geometry at all: {@code RecalcHitSpeed} (ObjBase.pas:18619) only
   * caches {@code m_nHitDouble := 4 + btLevel * 4}, and {@code _Attack} spends the armed flag on
   * the very next front swing for {@code +nPower / 100 * (m_nHitDouble * 10)} percent damage.
   */
  public static final int SKILL_FIRESWORD = 26;

  /** 野蛮冲撞 {@code SKILL_MOOTEBO} (Grobal2.pas). */
  public static final int SKILL_MOOTEBO = 27;

  /** {@code Inc(m_btSpeedPoint, 3)} for 道士 (ObjBase.pas:18562). */
  private static final int TAOIST_SPEED_BONUS = 3;

  public HitSpeed {
    if (hitPoint < 0 || speedPoint < 0) throw new IllegalArgumentException("points must not be negative");
    if (hitPlus < 0) throw new IllegalArgumentException("hit plus must not be negative");
    if (attackSkillCycle < 0) throw new IllegalArgumentException("cycle must not be negative");
    if (hitDouble < 0) throw new IllegalArgumentException("hit double must not be negative");
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
    int hitDouble = 0;
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
        case SKILL_FIRESWORD -> hitDouble = fireSwordHitDouble(skill.level());
        default -> {
          // The remaining RecalcHitSpeed cases only cache a UserMagic pointer for skills this
          // batch does not implement (刺杀剑术/半月弯刀/烈火剑法/野蛮冲撞/逐日剑法/……).
        }
      }
    }
    return new HitSpeed(hitPoint, speedPoint, hitPlus, cycle, hitDouble);
  }

  /**
   * {@code m_nHitDouble := 4 + UserMagic.btLevel * 4} (ObjBase.pas:18622) — the 烈火剑法 burst
   * multiplier, written outside any {@code btLevel > 0} guard, so a level-0 book already grants
   * the 40% bonus and level 3 reaches 160%. Unlike 准确, it is a plain overwrite: the last
   * 烈火剑法 entry of {@code m_MagicList} wins (there can only ever be one).
   */
  public static int fireSwordHitDouble(int level) {
    return 4 + level * 4;
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
      case SKILL_ONESWORD, SKILL_ILKWANG, SKILL_YEDO, SKILL_ERGUM, SKILL_BANWOL, SKILL_FIRESWORD,
          SKILL_MOOTEBO, 34, 38 -> true;
      default -> false;
    };
  }

  /**
   * The three passive ids whose {@code ClientSpellXY} branch is a bare {@code Result := True}
   * (3/4/7). 刺杀剑术 ({@link #SKILL_ERGUM}) and 半月弯刀 ({@link #SKILL_BANWOL}) are also
   * implemented but their {@code ClientSpellXY} branch <em>toggles</em> a flag and emits a
   * {@code +LNG}/{@code +WID} tag, so the world engine handles those two separately.
   */
  public static boolean isImplementedWarriorSkill(int magicId) {
    return magicId == SKILL_ONESWORD || magicId == SKILL_ILKWANG || magicId == SKILL_YEDO;
  }

  /**
   * 刺杀剑术 / 半月弯刀: the two {@code IsWarrSkill} ids whose {@code ClientSpellXY} branch
   * toggles an active special-attack shape ({@code m_boUseThrusting}/{@code m_boUseHalfMoon},
   * ObjBase.pas:9037-9073) rather than being a passive 准确 modifier.
   */
  public static boolean isToggledWeaponSkill(int magicId) {
    return magicId == SKILL_ERGUM || magicId == SKILL_BANWOL;
  }

  /**
   * 烈火剑法: the third {@code IsWarrSkill} id with an active {@code ClientSpellXY} branch
   * (ObjBase.pas:9092). It is neither passive nor a toggle — the press <em>arms</em> a one-shot
   * flag through {@code AllowFireHitSkill} (10 s re-arm gate) and pays {@code GetSpellPoint} mana
   * for the {@code +FIR} tag, and the flag expires 20 s later ({@code +UFIR}).
   */
  public static boolean isFireSwordSkill(int magicId) {
    return magicId == SKILL_FIRESWORD;
  }

  /**
   * 野蛮冲撞: the fourth {@code IsWarrSkill} active branch (ObjBase.pas:9112). It rushes the
   * player forward up to 3/4/5 steps, pushes lower-level obstacles (and a second target at level 3),
   * inflicts collision damage, triggers recoil damage when hitting solid terrain, and emits
   * {@code SM_RUSH}/{@code SM_BACKSTEP}/{@code SM_RUSHKUNG}.
   */
  public static boolean isMotaeboSkill(int magicId) {
    return magicId == SKILL_MOOTEBO;
  }
}
