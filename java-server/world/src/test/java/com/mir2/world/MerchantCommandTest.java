package com.mir2.world;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit coverage for the {@link MerchantCommand} Market_Def label catalog and its resolver. */
class MerchantCommandTest {

  /** The 26 merchant labels from {@code M2Share.pas} that reach {@code TMerchant.UserSelect}. */
  private static final Set<String> EXPECTED_LABELS = Set.of(
      "@repair", "~@repair", "@s_repair", "~@s_repair", "@fail_s_repair",
      "@buy", "@sell", "@makedrug", "@prices", "@storage", "@getback",
      "@upgradenow", "~@upgradenow_ing", "~@upgradenow_ok", "~@upgradenow_fail",
      "@getbackupgnow", "~@getbackupgnow_ok", "~@getbackupgnow_fail",
      "~@getbackupgnow_bagfull", "~@getbackupgnow_ing",
      "@@useitemname", "@@sendmsg", "@exit", "@back", "@main", "~@main");

  @Test
  void catalogCoversExactlyTheMerchantLabelBlock() {
    Set<String> labels = MerchantCommand.catalog().values().stream()
        .map(MerchantCommand::label)
        .collect(Collectors.toSet());
    assertEquals(EXPECTED_LABELS, labels, "catalog must match the M2Share MERCHANT label set");
  }

  @Test
  void onlyRepairAndExitAreImplemented() {
    Set<String> implemented = MerchantCommand.catalog().values().stream()
        .filter(c -> c.status() == MerchantCommand.Status.IMPLEMENTED)
        .map(MerchantCommand::label)
        .collect(Collectors.toSet());
    assertEquals(Set.of("@repair", "@s_repair", "@exit"), implemented);
  }

  @Test
  void everyLabelHasANonEmptyNote() {
    for (MerchantCommand command : MerchantCommand.catalog().values()) {
      assertFalse(command.note().isBlank(), command.label() + " needs a note");
    }
  }

  @Test
  void resolveIsCaseInsensitiveLikeCompareText() {
    assertSame(MerchantCommand.resolve("@repair").orElseThrow(),
        MerchantCommand.resolve("@REPAIR").orElseThrow());
    assertEquals(MerchantCommand.Category.TRADE,
        MerchantCommand.resolve("@BuY").orElseThrow().category());
  }

  @Test
  void resolveMatchesItemNamingByPrefix() {
    MerchantCommand named = MerchantCommand.resolve("@@useitemname屠龙").orElseThrow();
    assertEquals(MerchantCommand.Category.ITEM_NAMING, named.category());
    assertSame(named, MerchantCommand.resolve("@@USEITEMNAME whatever").orElseThrow());
  }

  @Test
  void resolveReturnsEmptyForUnknownAndBlankLabels() {
    assertTrue(MerchantCommand.resolve("@nosuchlabel").isEmpty());
    assertTrue(MerchantCommand.resolve("").isEmpty());
    assertTrue(MerchantCommand.resolve("   ").isEmpty());
    assertTrue(MerchantCommand.resolve(null).isEmpty());
  }

  @Test
  void catalogIsKeyedByLowercaseLabel() {
    for (Map.Entry<String, MerchantCommand> entry : MerchantCommand.catalog().entrySet()) {
      assertEquals(entry.getValue().label().toLowerCase(java.util.Locale.ROOT), entry.getKey());
    }
  }
}
