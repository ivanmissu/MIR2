package com.mir2.world;

/**
 * How a monster reacts to nearby players, mirroring the AI families used by the first-ten
 * spawn list in {@code ObjMon.pas}.
 *
 * <p>{@code AGGRESSIVE} is the {@code TATMonster} chase-and-attack loop already shipped in
 * W03. {@code PASSIVE_FLEE} is the {@code TChickenDeer} run-away mode: the monster never
 * attacks and walks away from the nearest visible player instead of toward it.
 *
 * <p>{@code STATIONARY} is {@code TMonster.Run} with {@code m_boNoAttackMode} set
 * (ObjMon.pas:449) and no walk step: the whole "acquire target, chase, attack" block is
 * skipped, so the object just stands on its cell and absorbs blows. Delphi builds exactly
 * such an object for {@code TRAINER} (M2Share.pas:152, {@code TTrainer} in ObjNpc.pas:2626) —
 * the damage-test dummy that reports 破坏力 / 平均值 when struck. It is also the only monster
 * shape whose observable behaviour does not depend on the wall clock, which makes it the
 * target the shadow-comparison harness can drive reproducibly.
 */
public enum MonsterBehavior {
  AGGRESSIVE,
  PASSIVE_FLEE,
  STATIONARY
}
