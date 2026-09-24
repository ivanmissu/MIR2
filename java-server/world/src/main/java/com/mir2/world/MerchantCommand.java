package com.mir2.world;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The Market_Def / Npc_def special-label catalog — a machine-checkable classification of every
 * merchant dialog label {@code TMerchant.UserSelect} (ObjNpc.pas:1419) dispatches on. Each label
 * from {@code M2Share.pas} (the {@code sSL_SENDMSG..sHIREGUARDOK} block) is placed in exactly one
 * {@link Category} with an explicit {@link Status}, so a client asking for a label the engine has
 * not implemented is <em>rejected observably</em> rather than silently stored — the W29 red line
 * ("未实现脚本不得静默成功").
 *
 * <p>This is deliberately <b>not</b> the Market_Def script engine: it neither parses NPC scripts
 * nor jumps between labels. It only lets {@link WorldEngine#selectMerchantLabel} tell three cases
 * apart:
 * <ul>
 *   <li>{@link Status#IMPLEMENTED} — the label maps to a real, tested behavior
 *       ({@code @repair}/{@code @s_repair} open the repair dialog, {@code @exit} closes it);</li>
 *   <li>{@link Status#DEFERRED_TRANSACTION} — buy/sell/storage/craft/upgrade labels that touch
 *       inventory, gold or the goods list; they stay closed until each has stock/gold/transaction
 *       tests (W27 plan §3);</li>
 *   <li>{@link Status#DEFERRED_SCRIPT} — labels whose meaning only exists inside a running
 *       Market_Def script (navigation back-jumps, {@code ~@} result callbacks, in-script
 *       messaging); they cannot be honored without the script engine.</li>
 * </ul>
 * Any label not in this catalog resolves to {@link Optional#empty()} and is treated as unknown.
 */
public record MerchantCommand(String label, Category category, Status status, String note) {

  public MerchantCommand {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(note, "note");
  }

  /** Functional grouping of a merchant label, mirroring the {@code TMerchant.UserSelect} arms. */
  public enum Category {
    /** {@code @repair} / {@code @s_repair}: open the repair dialog (W15). */
    REPAIR,
    /** {@code @exit} / {@code @back} / {@code @main}: pure dialog navigation. */
    DIALOG_NAVIGATION,
    /** {@code @buy} / {@code @sell}: the goods list and sell window. */
    TRADE,
    /** {@code @storage} / {@code @getback}: the personal storage vault. */
    STORAGE,
    /** {@code @makedrug}: the pharmacy list. */
    CRAFTING,
    /** {@code @prices}: the (Delphi-stubbed) price board. */
    PRICING,
    /** {@code @upgradenow} / {@code @getbackupgnow}: the weapon upgrade workflow. */
    UPGRADE,
    /** {@code ~@*} result labels a script jumps to after an action resolves. */
    SCRIPT_CALLBACK,
    /** {@code @@useitemname}: the item-naming command (prefix-matched like {@code CompareLStr}). */
    ITEM_NAMING,
    /** {@code @@sendmsg}: emit a scripted message. */
    MESSAGING
  }

  /** How complete this label's Java implementation is. */
  public enum Status {
    /** Reproduced and covered by automated tests. */
    IMPLEMENTED,
    /** Deferred until inventory/gold/transaction tests exist (buy/sell/storage/craft/upgrade). */
    DEFERRED_TRANSACTION,
    /** Deferred until the Market_Def script engine exists (navigation/callbacks/messaging). */
    DEFERRED_SCRIPT
  }

  /** The prefix-matched item-naming label ({@code CompareLStr(sLabel, sUSEITEMNAME, ...)}). */
  static final String USE_ITEM_NAME_PREFIX = "@@useitemname";

  private static final Map<String, MerchantCommand> CATALOG = buildCatalog();

  private static Map<String, MerchantCommand> buildCatalog() {
    Map<String, MerchantCommand> map = new LinkedHashMap<>();
    put(map, "@repair", Category.REPAIR, Status.IMPLEMENTED,
        "普通修理：Dec(DuraMax, 磨损 div 30) 后回满 (ObjNpc.pas:RepairItem)");
    put(map, "@s_repair", Category.REPAIR, Status.IMPLEMENTED,
        "特殊修理：DuraMax 不变直接回满，报价 3× 截断基数 quirk (ObjNpc.pas:SuperRepairItem)");
    put(map, "@exit", Category.DIALOG_NAVIGATION, Status.IMPLEMENTED,
        "RM_MERCHANTDLGCLOSE -> SM_MERCHANTDLGCLOSE 关闭商人窗口 (ObjBase.pas:5846)");

    // Navigation that only means something inside a running script's label graph.
    put(map, "@back", Category.DIALOG_NAVIGATION, Status.DEFERRED_SCRIPT,
        "m_sScriptGoBackLable 回跳，需要脚本标签图");
    put(map, "@main", Category.DIALOG_NAVIGATION, Status.DEFERRED_SCRIPT,
        "主菜单标签，需要脚本引擎");
    put(map, "~@main", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "主菜单失败回跳，需要脚本引擎");

    // Transactions — stock/gold/atomicity tests required before any of these open (W27 §3).
    put(map, "@buy", Category.TRADE, Status.DEFERRED_TRANSACTION,
        "RM_SENDGOODSLIST 货物列表；买卖待库存/金币/事务测试");
    put(map, "@sell", Category.TRADE, Status.DEFERRED_TRANSACTION,
        "RM_SENDUSERSELL 开出售窗；同上");
    put(map, "@storage", Category.STORAGE, Status.DEFERRED_TRANSACTION,
        "RM_USERSTORAGEITEM 开仓库；需要仓库容器 + 事务");
    put(map, "@getback", Category.STORAGE, Status.DEFERRED_TRANSACTION,
        "RM_USERGETBACKITEM 取回；同上");
    put(map, "@makedrug", Category.CRAFTING, Status.DEFERRED_TRANSACTION,
        "RM_USERMAKEDRUGITEMLIST 制药列表；需要配方 + 金币");
    put(map, "@prices", Category.PRICING, Status.DEFERRED_TRANSACTION,
        "价格表（Delphi ItemPrices 为空桩，仍按未实现处理）");
    put(map, "@upgradenow", Category.UPGRADE, Status.DEFERRED_TRANSACTION,
        "武器升级；需要升级队列 + 材料事务");
    put(map, "@getbackupgnow", Category.UPGRADE, Status.DEFERRED_TRANSACTION,
        "取回升级武器；同上");
    put(map, USE_ITEM_NAME_PREFIX, Category.ITEM_NAMING, Status.DEFERRED_TRANSACTION,
        "物品冠名（CompareLStr 前缀匹配）；改物品名需要背包写入");

    // Result callbacks a script GotoLable-jumps to; no meaning outside a script run.
    put(map, "~@repair", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "修理完成回跳，需要脚本引擎");
    put(map, "~@s_repair", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "特修完成回跳，需要脚本引擎");
    put(map, "@fail_s_repair", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "特修失败回跳，需要脚本引擎");
    put(map, "~@upgradenow_ing", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "升级中回跳，需要脚本引擎");
    put(map, "~@upgradenow_ok", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "升级成功回跳，需要脚本引擎");
    put(map, "~@upgradenow_fail", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "升级失败回跳，需要脚本引擎");
    put(map, "~@getbackupgnow_ok", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "取回成功回跳，需要脚本引擎");
    put(map, "~@getbackupgnow_fail", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "取回失败回跳，需要脚本引擎");
    put(map, "~@getbackupgnow_bagfull", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "取回背包满回跳，需要脚本引擎");
    put(map, "~@getbackupgnow_ing", Category.SCRIPT_CALLBACK, Status.DEFERRED_SCRIPT,
        "取回中回跳，需要脚本引擎");
    put(map, "@@sendmsg", Category.MESSAGING, Status.DEFERRED_SCRIPT,
        "脚本内发消息（SendCustemMsg），需要脚本引擎");
    return Map.copyOf(map);
  }

  private static void put(Map<String, MerchantCommand> map, String label, Category category,
      Status status, String note) {
    map.put(label.toLowerCase(Locale.ROOT), new MerchantCommand(label, category, status, note));
  }

  /**
   * Resolves the {@code sLabel} token (the part of the {@code CM_MERCHANTDLGSELECT} body before the
   * first CR) to its catalog entry. Matching is case-insensitive like Delphi {@code CompareText};
   * {@code @@useitemname} is prefix-matched like {@code CompareLStr}. Returns empty for an unknown
   * label so the caller can reject it as such.
   */
  public static Optional<MerchantCommand> resolve(String firstToken) {
    if (firstToken == null) return Optional.empty();
    String key = firstToken.strip().toLowerCase(Locale.ROOT);
    if (key.isEmpty()) return Optional.empty();
    if (key.startsWith(USE_ITEM_NAME_PREFIX)) {
      return Optional.of(CATALOG.get(USE_ITEM_NAME_PREFIX));
    }
    return Optional.ofNullable(CATALOG.get(key));
  }

  /** The full label catalog (label -> command), for tests and matrix cross-checks. */
  public static Map<String, MerchantCommand> catalog() {
    return CATALOG;
  }
}
