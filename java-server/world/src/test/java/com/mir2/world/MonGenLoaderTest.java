package com.mir2.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonGenLoaderTest {
  @Test
  void parsesQuotedRowsAndExpandsLoadgen() throws Exception {
    Path root = Files.createTempDirectory("mir2-mongen");
    Files.writeString(root.resolve("extra.txt"), "; comment\n0 20 21 \"鸡\" 2 3 5 60\n");
    Files.writeString(root.resolve("MonGen.txt"),
        "loadgen extra.txt\n0 10 11 \"orc\" 3 4 10 80 ; trailing comment\n");

    List<MonsterSpawnDefinition> rows = MonGenLoader.load(root.resolve("MonGen.txt"));

    assertEquals(2, rows.size());
    assertEquals("鸡", rows.get(0).monsterName());
    assertEquals(300_000L, rows.get(0).respawnMillis());
    assertEquals(4, rows.get(1).count());
    assertEquals(600_000L, rows.get(1).respawnMillis());
  }

  @Test
  void rejectsMalformedRowsWithFileAndLine() throws Exception {
    Path file = Files.createTempFile("MonGen", ".txt");
    Files.writeString(file, "0 10 10 chicken 1\n");
    var error = assertThrows(java.io.IOException.class, () -> MonGenLoader.load(file));
    assertEquals(true, error.getMessage().contains(":1:"));
  }
}
