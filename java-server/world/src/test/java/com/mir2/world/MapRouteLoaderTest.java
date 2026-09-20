package com.mir2.world;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapRouteLoaderTest {
  @TempDir
  Path temporaryDirectory;

  @Test
  void readsLegacyMapInfoRouteRowsWithMixedSeparatorsAndGbkNames() throws Exception {
    Path file = temporaryDirectory.resolve("MapInfo.txt");
    String contents = "; route comment\n"
        + "[0 比奇省 0]\n"
        + "0, 10\t11 -> 洞穴 20,21 ; trailing comment\n"
        + "洞穴-5-6>0 30 31\n";
    Files.write(file, contents.getBytes(Charset.forName("GBK")));

    assertEquals(List.of(
        new MapRoute("0", new Position(10, 11), "洞穴", new Position(20, 21)),
        new MapRoute("洞穴", new Position(5, 6), "0", new Position(30, 31))),
        MapRouteLoader.load(file));
  }

  @Test
  void followsLoadMapInfoIncludesFromTheLegacyMapInfoDirectory() throws Exception {
    Path root = temporaryDirectory.resolve("MapInfo.txt");
    Path includedDirectory = temporaryDirectory.resolve("MapInfo");
    Files.createDirectories(includedDirectory);
    Files.writeString(root, "LoadMapInfo caves.txt\n0 1 2 -> 1 3 4\n");
    Files.writeString(includedDirectory.resolve("caves.txt"), "1 3 4 -> 2 5 6\n");

    assertEquals(List.of(
        new MapRoute("1", new Position(3, 4), "2", new Position(5, 6)),
        new MapRoute("0", new Position(1, 2), "1", new Position(3, 4))),
        MapRouteLoader.load(root));
  }

  @Test
  void rejectsMalformedRowsWithFileAndLineNumber() throws Exception {
    Path file = temporaryDirectory.resolve("routes.txt");
    Files.writeString(file, "0 10 -> 1 20 30\n");

    Exception error = assertThrows(Exception.class, () -> MapRouteLoader.load(file));
    assertEquals(true, error.getMessage().contains("routes.txt:1"));
  }
}
