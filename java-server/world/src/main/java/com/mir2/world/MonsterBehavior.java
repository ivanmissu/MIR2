package com.mir2.world;

/**
 * How a monster reacts to nearby players, mirroring the two AI families used by the
 * first-ten spawn list in {@code ObjMon.pas}.
 *
 * <p>{@code AGGRESSIVE} is the {@code TATMonster} chase-and-attack loop already shipped in
 * W03. {@code PASSIVE_FLEE} is the {@code TChickenDeer} run-away mode: the monster never
 * attacks and walks away from the nearest visible player instead of toward it.
 */
public enum MonsterBehavior {
  AGGRESSIVE,
  PASSIVE_FLEE
}
