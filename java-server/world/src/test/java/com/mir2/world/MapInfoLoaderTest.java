package com.mir2.world;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Legacy MapInfo.txt parsing (LocalDB.pas): headers, includes, comments and route chains. */
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
    assertEquals(new MapInfoLoader.MapDefinition("0", null, "比奇省"), document.maps().get(0));
    assertEquals("盟 重省", document.maps().get(1).description());
    assertEquals(List.of(
        new MapInfoLoader.RouteLine("0", 330, 330, "1", 4, 3),
        new MapInfoLoader.RouteLine("0", 331, 331, "1", 5, 4),
        new MapInfoLoader.RouteLine("1", 6, 5, "0", 7, 6)), document.routes());
    assertEquals(1, document.diagnostics().size(),
        "the truncated route line '0 10' must land in diagnostics, not in the route list");
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
    // Str_ToInt(%%s, 0): a non-numeric route coordinate degrades to 0 instead of failing.
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
