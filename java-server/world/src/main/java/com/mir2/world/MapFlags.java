package com.mir2.world;

import java.util.Objects;

/**
 * Parsed {@code MapInfo.txt} map flags, mirroring {@code TMapFlag} in {@code Grobal2.pas}
 * and {@code LocalDB.pas:LoadMapInfo}.
 */
public record MapFlags(
    boolean safeZone,
    boolean darkness,
    boolean dayLight,
    boolean fightZone,
    boolean fight3Zone,
    boolean quiz,
    boolean noReconnect,
    String noReconnectMap,
    boolean noChat,
    boolean runHuman,
    boolean runMon,
    int musicId,
    int expRate,
    boolean noDrug,
    boolean noThrowItem) {

  public static final MapFlags DEFAULT = new MapFlags(
      false, false, false, false, false, false, false, "", false, true, false, -1, -1,
      false, false);

  /** Compatibility overload for callers predating the NODRUG/NOTHROWITEM flags. */
  public MapFlags(
      boolean safeZone, boolean darkness, boolean dayLight, boolean fightZone,
      boolean fight3Zone, boolean quiz, boolean noReconnect, String noReconnectMap,
      boolean noChat, boolean runHuman, boolean runMon, int musicId, int expRate) {
    this(safeZone, darkness, dayLight, fightZone, fight3Zone, quiz, noReconnect,
        noReconnectMap, noChat, runHuman, runMon, musicId, expRate, false, false);
  }

  public MapFlags {
    Objects.requireNonNull(noReconnectMap, "noReconnectMap");
  }

  public boolean canRunHuman() {
    return runHuman;
  }

  public boolean isDarkness() {
    return darkness;
  }

  public boolean isDayLight() {
    return dayLight;
  }

  public boolean isSafeZone() {
    return safeZone;
  }

  public boolean isFightZone() {
    return fightZone || fight3Zone;
  }

  public boolean isNoChat() {
    return noChat;
  }

  public boolean isQuiz() {
    return quiz;
  }

  /** {@code Flag.boNODRUG}: potions cannot be eaten on this map (ObjBase.pas:23329). */
  public boolean isNoDrug() {
    return noDrug;
  }

  /** {@code Flag.boNOTHROWITEM}: items cannot be dropped on this map (ObjBase.pas:16233). */
  public boolean isNoThrowItem() {
    return noThrowItem;
  }
}
