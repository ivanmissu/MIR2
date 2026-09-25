# W27 下一步开发计划：G4 玩法等价收口

**基线：** W26（组队管理、经验分摊、装备取下拒绝提示），本地 371/371 测试通过。

## 目标

W27 不再扩展新的大系统，而是完成 P3 的验收收口：把现有能力整理成可重复验证的 G4 证据，并明确技能、NPC 脚本、行会/攻城尚未完成的边界，避免把占位实现误报为已完成。

## 执行顺序

### 1. 建立 G4 缺口清单（P0）

- 从 `GameProtocolAdapter`、`WorldEngine` 和 Delphi `Grobal2.pas` 生成 CM/SM 能力矩阵。
- 对每条能力标记：已实现、仅协议占位、仅服务端逻辑、未实现、需要真实客户端对拍。
- 将技能 59×3、Market_Def 指令、行会、攻城、交易分别列出，不以“有常量”视为完成。
- CI 增加清单格式校验，避免新协议常量没有对应状态。

### 2. 技能系统最小可验收切片（P0）— 已完成（W28）

W28 已按经典 `Magic.DB` 1–33 交集建立基础框架，并实现三类基础技能，而不是一次性翻译 59 个技能：

1. 单体伤害：距离、MP、冷却、伤害与 `SM_HEALTHSPELLCHANGED/SM_STRUCK`。
2. 单体恢复：目标筛选、MP 消耗、上限钳制。
3. Buff 生命周期：施加、过期、重登清理。

要求：所有技能都经过能力/职业/等级/MP 校验；技能失败必须有确定的 `SM_SYSMESSAGE`；技能状态不直接修改 Socket，只通过 `WorldEvent` 出站。完成基础框架后，再按 Delphi `Magic.pas` 的技能表逐项接入。

### 3. NPC 脚本安全边界（P1）— 已完成（W29）

W29 已建立 `MerchantCommand` 目录（`M2Share.pas` 26 个商人标签全量分类），并交付安全边界：

- 保持现有修理 NPC 状态机兼容（`@repair`/`@s_repair` 行为不变，返回类型收敛为语义化 `MerchantSelectOutcome`）。
- 新增 Market_Def 指令清单与拒绝未知指令的测试（`MerchantCommandTest`、`WorldMerchantCommandTest`、`GameMerchantCommandProtocolTest`）。
- 低风险指令 `@exit`（关窗 `SM_MERCHANTDLGCLOSE`）已开放；买卖/仓库/制药/升级/冠名保持 `DEFERRED_TRANSACTION`，回跳/主菜单/消息保持 `DEFERRED_SCRIPT`，事务测试前不接线。
- 未实现脚本不再静默成功：非实现标签一律 `WorldEvent.MerchantActionRejected`（含 label/category/status/reason）+ 日志，线上仍与 Delphi 一样不发包。

证据见 `docs/g0-evidence/2026-09-24-w29-merchant-command-boundary.md`。

### 4. 交互与持久化回归（P1）— 已完成（W30）

W30 已把 §4 收敛为 shadowdiff 固定脚本与 CLI 门禁入口，而不是继续扩大玩法面：

1. **双人组队回归**：`--embedded --duo party --strict-messages` 进程内拉起左右两套服务端、每套双账号双 GAME 会话；脚本覆盖「未开组队先拒绝 → 开关组队 → 建组 → 近身组队击杀鸡 → 掉落拾取 → 12 格外经验边界 → 队长踢人 → 双方重登」。
2. **死亡 / PK 回归**：`--embedded --duo death-pk --strict-messages` 覆盖玩家谋杀、交手/红黄名颜色消息、死亡自动退队、PK 点落库与双方重登恢复（受害者 HP<=0 重登 14 HP）。
3. **持久化回归**：`--embedded --persistence --strict-messages` 预种 `木剑` / `布衣(男)` / `金创药(小量)` 与 3000 金币，走穿戴、丢弃、拾取、吃药、取下、重登，比较金币、装备、背包与关键消息集合。
4. **装备锁定提示回归**：`--embedded --lock --strict-messages` 通过同生产解析器加载 GBK `DisableTakeOffList.txt`，验证 `SM_TAKEOFF_FAIL` + `SM_SYSMESSAGE`「无法取下物品」可观测，且锁定装备重登后仍留在穿戴槽。

支撑改动集中在 `shadowdiff`：`DuoHarness`、`Op` 的 `p1`/`p2` 前缀与组队 op、`ShadowSession` 的组队名单 / 名字颜色 / 地面物品快照、`ShadowDiffMain` 的 `--duo`/`--persistence`/`--lock` 模式，以及 `ScenarioRegressionTest` 的五个端到端 CLI 用例。证据见 `docs/g0-evidence/2026-09-25-w30-interaction-persistence-regression.md`。

### 5. G4 证据与发布门禁（P0）

- 新增 `docs/g0-evidence/2026-09-24-w27-g4-gap-matrix.md`，记录清单、测试命令、通过数和已知红线。
- `mvn -f java-server/pom.xml verify` 必须全绿。
- 运行 shadowdiff：基础 PvE、AI 矩阵、组队经验和重登场景。
- 只有技能、脚本、行会/攻城仍达到计划要求时才签发 G4；否则输出缺口排序，不把 W27 标记为完成。

## 本轮不做

- 不凭空补齐 59 个技能或 57 种怪物。
- 不引入脚本引擎、交易、行会、攻城的半成品实现。
- 不改变 W26 已验证的组队经验公式和协议 quirk。
- 不以真实客户端未可用为由修改已有 Delphi 语义。

## 当前进度与下一项

G4 能力矩阵、三技能基础框架（W28）、NPC/Market_Def 指令清单及安全拒绝边界（W29）和交互/持久化 shadowdiff 回归（W30）已完成；下一项按顺序推进 **G4 证据与发布门禁**（§5：全量 `mvn verify`、基础/PvE/AI/组队/死亡PK/持久化/锁定 shadowdiff 证据汇总，输出仍未完成的技能、脚本、行会/攻城/交易缺口，不把本地 Java↔Java 对拍误报为真实客户端 G4 通过）。每个切片继续独立测试、独立证据、独立对拍。
