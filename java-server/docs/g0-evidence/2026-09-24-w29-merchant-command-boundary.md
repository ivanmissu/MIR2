# G0 研发证据：W29 Market_Def 商人指令清单 + 未知指令安全拒绝边界

**日期：** 2026-09-24
**对应阶段：** P3 / W27 执行顺序第 3 项（NPC 脚本安全边界）
**状态：** ✅ 指令清单 + `@exit` 低风险指令 + 可观测拒绝边界完成；矩阵门禁同步更新

---

## 1. 交付范围（w27-next-plan §3）

| 计划要求 | 交付物 | 自动化证据 |
|---|---|---|
| 保持现有修理 NPC 状态机兼容 | `@repair`/`@s_repair` 路径与 W15 完全一致，仅返回类型由 `boolean` 收敛为语义化 `MerchantSelectOutcome` | `WorldRepairTest`（全量沿用）、`GameRepairProtocolTest` |
| 新增 Market_Def 指令清单 | `MerchantCommand` 目录：`M2Share.pas` 26 个商人标签全量分类（Category × Status） | `MerchantCommandTest`（目录 = M2Share 标签集、状态、前缀匹配、大小写不敏感） |
| 先覆盖只读/低风险指令 | `@exit` → `RM_MERCHANTDLGCLOSE` → `SM_MERCHANTDLGCLOSE` 关窗（纯 UI，无状态写入） | `WorldMerchantCommandTest#theExitLabelClosesTheMerchantDialog`、`GameMerchantCommandProtocolTest#theExitLabelSendsMerchantDlgClose` |
| 买卖/任务脚本待事务测试后再开放 | 买卖/仓库/制药/升级/冠名标记 `DEFERRED_TRANSACTION`；回跳/主菜单/消息标记 `DEFERRED_SCRIPT`，均不接线 | `MerchantCommandTest#onlyRepairAndExitAreImplemented` |
| 未实现脚本不得静默成功，统一记录可观测拒绝事件 | 非实现标签一律 `WorldEvent.MerchantActionRejected`（含 label/category/status/reason）+ 日志；`selectMerchantLabel` 返回 `REJECTED` | `WorldMerchantCommandTest`（deferred / script-callback / 未知 / 前缀 四类拒绝） |

---

## 2. 设计要点与 Delphi 对照

- **不是脚本引擎**：`MerchantCommand` 只做「标签 → 分类 + 完成度」的机械查表，不解析 NPC 脚本、不做标签跳转。红线（Market_Def 脚本引擎对拍前不得扩展）未被突破。
- **分派忠实于 `TMerchant.UserSelect`（ObjNpc.pas:1419）**：
  - 每条 `@` 前缀选择都先 `m_sScriptLable := sData`（Java 侧 `player.merchantLabel = trimmed`），因此在 `@s_repair` 之后选任意非修理标签，再选 `@repair` 会正确回落到普通修理模式——由 `WorldMerchantCommandTest#reSelectingANonRepairLabelDropsBackToNormalRepairMode` 钉死。
  - `@exit` 复刻 `PlayObject.SendMsg(Self, RM_MERCHANTDLGCLOSE, ...)`（ObjNpc.pas:1593 → ObjBase.pas:5846），`SM_MERCHANTDLGCLOSE` 的 `recog` = 客户端点击的商人 id，其余字段 0。
  - `@@useitemname` 用 `CompareLStr` 语义的**前缀匹配**（`@@useitemname屠龙` 命中），其余标签 `CompareText` 大小写不敏感精确匹配。
- **拒绝为何在线上静默**：Delphi 中这些分支都由商人自身的 `m_boBuy`/`m_boSell`/… 旗守卫；此处没有任何 Market_Def 脚本设置这些旗，等价于「该商人不支持此功能」，原版对客户端**不发包**。因此 W29 的拒绝**不向真实客户端发送任何 SM**（避免与 Delphi 线上字节分叉），改为发 `WorldEvent.MerchantActionRejected` + `LOG.fine`，让拒绝在测试 / shadowdiff / 日志中**可观测**，杜绝「静默存下标签冒充成功」。

## 3. 指令清单快照（`MerchantCommand` 目录，26 条）

| 完成度 | 标签 | 分类 |
|---|---|---|
| IMPLEMENTED | `@repair` `@s_repair` | REPAIR |
| IMPLEMENTED | `@exit` | DIALOG_NAVIGATION |
| DEFERRED_TRANSACTION | `@buy` `@sell` | TRADE |
| DEFERRED_TRANSACTION | `@storage` `@getback` | STORAGE |
| DEFERRED_TRANSACTION | `@makedrug` | CRAFTING |
| DEFERRED_TRANSACTION | `@prices` | PRICING |
| DEFERRED_TRANSACTION | `@upgradenow` `@getbackupgnow` | UPGRADE |
| DEFERRED_TRANSACTION | `@@useitemname`（前缀） | ITEM_NAMING |
| DEFERRED_SCRIPT | `@back` `@main` | DIALOG_NAVIGATION |
| DEFERRED_SCRIPT | `@@sendmsg` | MESSAGING |
| DEFERRED_SCRIPT | `~@main` `~@repair` `~@s_repair` `@fail_s_repair` `~@upgradenow_ing/_ok/_fail` `~@getbackupgnow_ok/_fail/_bagfull/_ing` | SCRIPT_CALLBACK |

目录之外的任意 `@` 标签解析为空 → `MerchantActionRejected.unknown() = true`（`category`/`status` 为 null）。

## 4. G4 矩阵变化

| 行 | W28 后 | W29 后 |
|---|---|---|
| `CM_MERCHANTDLGSELECT` | implemented（仅 @repair/@s_repair 注解） | implemented（注解补 `@exit` 关窗 + 其余标签可观测拒绝） |
| `SM_MERCHANTDLGCLOSE` | protocol-only | **implemented / gate-game**（`GameMerchantCommandProtocolTest`） |
| `MERCHANT/@exit` | unimplemented | **implemented / gate-game** |

MERCHANT 区实现数由 2（`@repair`/`@s_repair`）增至 **3**（+`@exit`）；其余 23 个标签仍 unimplemented，但现在**运行期会被可观测拒绝**而非静默——门禁 `G4CapabilityMatrixTest` 的 `smWireColumnMatchesActualEmissions` 现在要求 `SM_MERCHANTDLGCLOSE` 必须由 adapter 实发（否则红灯），把「实现」与「矩阵声明」双向锁死。

## 5. 验证记录

本 Arena 沙箱无 JDK/Maven 与外网（无法 `apt`/下载 JDK），Java 编译与跑批以 CI 为准：

- **矩阵门禁本地镜像**：用与 `G4CapabilityMatrixTest` 同构的 Python 复检 `g4-capability-matrix.tsv`——9 列格式、状态↔wire↔java_constant 一致性、SM 出站集合（adapter/login 不相交且与 wire 列一致）、evidence 测试类实指，**0 违例**（新增 `SM_MERCHANTDLGCLOSE` 已随 adapter 实发被门禁认可）。
- 新增测试：`MerchantCommandTest`（7）、`WorldMerchantCommandTest`（7）、`GameMerchantCommandProtocolTest`（3）；`WorldRepairTest` 的 5 处断言随返回类型迁移到 `MerchantSelectOutcome`，语义不变。
- `mvn -f java-server/pom.xml verify` 与 shadowdiff（embedded strict / seeded PvE / `--ai`）在 CI 执行；本轮为纯增量（新目录类 + 两条 WorldEvent + 一处 gate 分派），未触碰移动/战斗/掉落/存档路径，既有 shadowdiff 脚本行为不变。

## 6. 本轮未做（与红线一致）

- 未实现任何买卖 / 仓库 / 制药 / 升级 / 冠名的库存与金币事务——这些标签保持 `DEFERRED_TRANSACTION` 且运行期拒绝。
- 未引入 Market_Def 脚本引擎、标签跳转图或 `@back`/`@main` 的真实导航（保持 `DEFERRED_SCRIPT`）。
- 未新增 NPC 对象的商家距离校验（`FindMerchant`，|Δ|<15），仍随完整 NPC 切片推进。
- 下一项：交互与持久化回归（w27-next-plan §4，shadowdiff 固定脚本覆盖组队+击杀经验+掉落、死亡自动退队、重登一致等）。
