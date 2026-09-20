package com.mir2.world;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Legacy MapInfo.txt parsing (LocalDB.pas): headers, flags, includes, comments and route chains. */
class MapInfoLoaderTest {
  private static final Charset GBK = Charset.forName("GBK");

  @TempDir
  Path directory;

  @Test
  void parsesMapDefinitionsAndRoutesLikeGetValidStr3() throws Exception {
    Path mapInfo = directory.resolve("MapInfo.txt");
    Files.writeString(mapInfo, String.join("\r\n",
        "; 传奇地图配置",
        "[0\t比奇省\t0\tDAY SAFE]",
        "[1\t\"盟 重省\"\t0]",
        "",
        "0\t330\t330\t\t1\t4\t3",
        "0 331 331 -> 1 5 4",
        "1,6,5,0,7,6 ; trailing comment stays out of dstY",
        "0 10"), GBK);

    MapInfoLoader.MapInfoDocument document = MapInfoLoader.load(mapInfo);

    assertEquals(2, document.maps().size());
    MapInfoLoader.MapDefinition def0 = document.maps().get(0);
    assertEquals("0", def0.id());
    assertEquals("比奇省", def0.description());
    assertTrue(def0.flags().dayLight());
    assertTrue(def0.flags().safeZone());
    assertFalse(def0.flags().darkness());

    assertEquals("盟 重省", document.maps().get(1).description());
    assertEquals(List.of(
        new MapInfoLoader.RouteLine("0", 330, 330, "1", 4, 3),
        new MapInfoLoader.RouteLine("0", 331, 331, "1", 5, 4),
        new MapInfoLoader.RouteLine("1", 6, 5, "0", 7, 6)), document.routes());
    assertEquals(1, document.diagnostics().size(),
        "the truncated route line '0 10' must land in diagnostics, not in the route list");
  }

  @Test
  void parsesDiverseMapFlagsInsideAndOutsideBrackets() throws Exception {
    Path mapInfo = directory.resolve("MapInfo.txt");
    Files.writeString(mapInfo, String.join("\n",
        "[D013\t半兽古墓\t0\tDARK NOCHAT QUIZ]",
        "[3\t盟重省\t0] SAFE FIGHT NORECONNECT(0) MUSIC(5) EXPRATE(200) RUNMON",
        "[4\t封魔谷] FIGHT3"), GBK);

    MapInfoLoader.MapInfoDocument doc = MapInfoLoader.load(mapInfo);
    assertEquals(3, doc.maps().size());

    MapFlags f0 = doc.maps().get(0).flags();
    assertTrue(f0.darkness());
    assertTrue(f0.noChat());
    assertTrue(f0.quiz());
    assertFalse(f0.dayLight());
    assertFalse(f0.safeZone());

    MapFlags f1 = doc.maps().get(1).flags();
    assertTrue(f1.safeZone());
    assertTrue(f1.fightZone());
    assertTrue(f1.noReconnect());
    assertEquals("0", f1.noReconnectMap());
    assertEquals(5, f1.musicId());
    assertEquals(200, f1.expRate());
    assertTrue(f1.runMon());

    MapFlags f2 = doc.maps().get(2).flags();
    assertTrue(f2.fight3Zone());
    assertTrue(f2.isFightZone());
  }

  @Test
  void loadmapinfoIncludesAreExpandedFromTheMapInfoSubdirectory() throws Exception {
    Path subdirectory = directory.resolve("MapInfo");
    Files.createDirectory(subdirectory);
    Files.writeString(subdirectory.resolve("field-maps.txt"),
        "[D013\t半兽古墓一层\t0]\nD013 10 10 -> 0 20 20\n0 20 20 -> D013 5 5\n", GBK);
    Path mapInfo = directory.resolve("MapInfo.txt");
    Files.writeString(mapInfo, "loadmapinfo field-maps.txt\n[0\t比奇省\t0]\n", GBK);

    MapInfoLoader.MapInfoDocument document = MapInfoLoader.load(mapInfo);

    assertEquals(List.of("D013", "0"), document.maps().stream()
        .map(MapInfoLoader.MapDefinition::id).toList());
    assertEquals(2, document.routes().size());
    assertEquals("D013", document.routes().get(0).sourceMapId());
  }

  @Test
  void mapAliasDefinitionsCarryTheFileAliasAndFailingNumericsCoerceToZero() throws Exception {
    Path mapInfo = directory.resolve("MapInfo.txt");
    Files.writeString(mapInfo, String.join("\n",
        "[D016|D015\t半兽古墓三层\t0]",
        "D016 x 999 -> 0 1 1"), GBK);

    MapInfoLoader.MapInfoDocument document = MapInfoLoader.load(mapInfo);

    assertEquals(1, document.maps().size());
    MapInfoLoader.MapDefinition alias = document.maps().get(0);
    assertEquals("D016", alias.id());
    assertEquals("D015", alias.fileAlias());
    assertEquals("D015", alias.mapFileName(),
        "mapFileName() names the .map file without its extension; the bootstrap appends it");
    assertEquals("半兽古墓三层", alias.description());
    // Str_ToInt(%s, 0): a non-numeric route coordinate degrades to 0 instead of failing.
    assertEquals(new MapInfoLoader.RouteLine("D016", 0, 999, "0", 1, 1), document.routes().get(0));
  }

  @Test
  void missingIncludeFilesAreSkippedSilentlyLikeLoadSubMapInfo() throws Exception {
    Path mapInfo = directory.resolve("MapInfo.txt");
    Files.writeString(mapInfo, "loadmapinfo nowhere.txt\n[0\t比奇省\t0]\n", GBK);
    MapInfoLoader.MapInfoDocument document = MapInfoLoader.load(mapInfo);
    assertEquals(1, document.maps().size());
  }

  @Test
  void routesConvertIntoTeleportRouteRecords() throws Exception {
    Path mapInfo = directory.resolve("MapInfo.txt");
    Files.writeString(mapInfo, "[0\t比奇\t0]\n[1\t盟重\t0]\n0 331 331 -> 1 5 4\n", GBK);
    MapInfoLoader.MapInfoDocument document = MapInfoLoader.load(mapInfo);
    TeleportRoute route = document.routes().get(0).toTeleportRoute();
    assertEquals("0", route.sourceMapId());
    assertEquals(new Position(331, 331), route.source());
    assertEquals("1", route.destinationMapId());
    assertEquals(new Position(5, 4), route.destination());
  }
}
