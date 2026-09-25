# G0 研发证据：W30 交互与持久化 shadowdiff 回归

**日期：** 2026-09-25  
**对应阶段：** P3 / W27 执行顺序第 4 项（交互与持久化回归）  
**状态：** ✅ 固定脚本、CLI 入口与端到端用例已入库；本 Arena 沙箱缺 JDK/Maven，运行结论需 CI / 开发机复验（见 §5）

---

## 1. 交付范围（w27-next-plan §4）

| 计划要求 | 交付物 | 自动化证据 |
|---|---|---|
| 组队 + 击杀经验 + 掉落 | `shadowdiff --embedded --duo party --strict-messages`：双账号双会话、建组、近身组队击杀鸡、地面 `鸡肉` 拾取、12 格外经验边界、队长踢人、双方重登 | `ScenarioRegressionTest#duoPartyScenarioMatchesOnBothServers`、`OpTest#duoPartyScriptCoversTheWholeInteractionLoop` |
| 死亡自动退队 + PK 边界 | `shadowdiff --embedded --duo death-pk --strict-messages`：p2 谋杀 p1，覆盖交手染色、死亡、`leaveGroup` 自动清理、谋杀 +100 PK 点与双方重登 | `ScenarioRegressionTest#duoDeathPkScenarioMatchesOnBothServers`、`OpTest#duoDeathPkScriptCoversMurderDeathAndBothRelogins` |
| 重登后金币 / 装备 / 背包一致 | `shadowdiff --embedded --persistence --strict-messages`：预种 `木剑` / `布衣(男)` / `金创药(小量)` 与 3000 金币，走穿戴、丢弃、拾取、吃药、取下、重登 | `ScenarioRegressionTest#persistenceScenarioRestoresGoldWornAndBagIdentically`、`OpTest#persistenceAndLockScriptsExerciseTheItemLifecycle` |
| 装备锁定提示 | `shadowdiff --embedded --lock --strict-messages`：通过 GBK `DisableTakeOffList.txt` 锁定 `木剑`，取下失败必须可观测，重登后仍锁定 | `ScenarioRegressionTest#lockScenarioRefusesTheTakeOffObservably`、`GameItemProtocolTest`（`SM_TAKEOFF_FAIL` + `SM_SYSMESSAGE`） |
| 固定脚本语法为后续 Delphi fixture 复用打基础 | `Op` 支持 `p1` / `p2` 前缀、`{p1}` / `{p2}` 占位符、组队四消息和手动时钟 `tick`；当前 W30 `--duo` / seeded solo 入口仍限定 embedded Java↔Java | `ScenarioRegressionTest#duoCustomScriptRunsTheGroupProtocolThroughTwoSessions`、`OpTest#parsesDuoActorPrefixesAndKeepsUnprefixedLinesPrimary` |

---

## 2. 设计要点

### 2.1 双会话 shadowdiff：同一服务器内两名真实玩家同时在线

`DuoHarness` 对每个被测服务端打开两条完整三网关链路（LOGIN → SELECT → GAME），脚本每一步只让 `p1` 或 `p2` 其中一方发包，另一方只 drain 服务端推送。最终得到两条对齐观察流：

- `p1`：自身 ACK / 消息 / 快照 + 观察到 p2 的出现、组队、移动、战斗广播。
- `p2`：自身 ACK / 消息 / 快照 + 观察到 p1 的广播。

两条流分别执行 `ShadowDiff.compare`，任一玩家可见状态不一致即失败。这比单会话脚本多覆盖了组队文本、成员列表、兴趣区广播、玩家间攻击、死亡退队等「第二个客户端才看得到」的面。

### 2.2 新增观测面：组队名单、名字颜色、地面物品

`ShadowSession` 的 `StateSnapshot` 在 W30 增补了三类真实客户端可见状态：

| 观测面 | 来源 SM | 对拍意义 |
|---|---|---|
| `groupMembers` | `SM_GROUPMEMBERS` / `SM_GROUPCANCEL`；`SM_NEWMAP` 清空 | 组队创建、踢人、死亡/下线解散不再只靠消息文本，状态快照也会红灯 |
| `nameColor` | `SM_CHANGENAMECOLOR`（仅跟踪自身对象） | PK 交手色、黄/红名变化可作为状态差异比较 |
| `groundItems` | `SM_ITEMSHOW` / `SM_ITEMHIDE`，按「名称 + 坐标」归一化 | 掉落物名称或落点不同会成为 STATE 失败，而不被服务器本地 object id 干扰 |

背包、穿戴、金币、HP/MP/等级/经验等原有快照继续比较，MakeIndex 仍归一化以避免左右服务器自增差异误报。

### 2.3 手动时钟与确定性怪物脚本

`--duo party` / `--duo death-pk` 运行在 `WorldClock.Mode.MANUAL`，脚本通过 `tick N` 推进世界时间，避免组队经验、鸡 AI、攻击间隔、尸体清理、掉落拾取窗口被宿主墙钟调度扰动。`--duo party` 默认在出生点周围生成 2 只 `chicken`，使用官方掉落表中 1/1 的 `鸡肉` 作为确定性地面物品来源；`--duo death-pk` 默认 0 怪，只验证玩家间战斗。

### 2.4 持久化与锁定场景不引入测试专用服务器命令

`--persistence` / `--lock` 在服务器启动前直接写入 SQLite：

- `AuthService` 注册账号；`CharacterService` 建立同名角色（男战士，满足 `布衣(男)` 性别锁）。
- `PlayerState` 预写入背包与钱包，物品全部从 `StdItemsDb.tsv` 权威目录解析，拼错即启动失败。
- `--lock` 额外写出 GBK 编码的 `DisableTakeOffList.txt`，通过 `ServerConfig.withDisableTakeOffFile` 走生产解析器，而不是绕过世界逻辑。

这样脚本仍然只发送真实客户端能发送的 CM（穿戴、丢弃、拾取、吃药、取下、查询背包、重登）。

---

## 3. 固定脚本入口

```bash
# 组队 + 组队经验/掉落 + 12 格外经验边界 + 双方重登
java -jar shadowdiff/target/mir2-shadowdiff.jar \
  --embedded --duo party --strict-messages \
  --report-dir reports/w30-party

# 死亡自动退队 + 玩家谋杀 / PK 点落库 + 双方重登
java -jar shadowdiff/target/mir2-shadowdiff.jar \
  --embedded --duo death-pk --strict-messages \
  --report-dir reports/w30-death-pk

# 金币 / 穿戴 / 背包持久化
java -jar shadowdiff/target/mir2-shadowdiff.jar \
  --embedded --persistence --strict-messages \
  --report-dir reports/w30-persistence

# DisableTakeOffList 装备锁定与拒绝提示
java -jar shadowdiff/target/mir2-shadowdiff.jar \
  --embedded --lock --strict-messages \
  --report-dir reports/w30-lock
```

对应 JUnit 入口：

```bash
mvn -f java-server/pom.xml -pl shadowdiff -am test -Dtest=ScenarioRegressionTest
mvn -f java-server/pom.xml -pl shadowdiff -am test -Dtest=OpTest,ShadowDiffTest,ShadowDiffMainArgsTest
mvn -f java-server/pom.xml verify
```

---

## 4. 与既有边界的关系

- **未扩展技能、Market_Def 事务、行会、攻城、交易。** 本轮只把既有组队、战斗、掉落、PK、装备、背包、金币、重登语义放入可重复对拍脚本。
- **不替代真实客户端 / Delphi 对拍。** W30 结论是 Java↔Java 确定性回归：它能防止 Java 侧后续改动破坏已迁移语义；G4 仍需要 Windows/mir2.exe 或 Delphi 服务端 fixture 作为外部基线。
- **消息严格性默认打开。** 本轮四个内置场景均建议使用 `--strict-messages`，即关键消息集合漂移也按失败处理，而不是只 advisory。
- **手动时钟仅用于 harness。** `@tick` 只在 MANUAL 世界生效，生产 `SYSTEM` / `VIRTUAL` 模式拒绝，不引入线上 GM 后门。

---

## 5. 复验记录（本沙箱限制）

本 Arena 沙箱当前没有 Java/Maven，且 `apt-get update` 无法连接 Debian 源，因此未能本地执行 JUnit 或生成真实报告：

```text
$ mvn -f java-server/pom.xml -pl shadowdiff -am test
/bin/bash: line 1: mvn: command not found

$ java -version
/bin/bash: line 1: java: command not found

$ sudo apt-get update
W: Failed to fetch http://deb.debian.org/debian/dists/bookworm/InRelease  Connection failed ...
```

因此本文件只记录已入库的固定脚本、CLI 门禁入口和测试用例映射；最终 PASS/FAIL 以 CI 或具备 JDK 21 + Maven 3.9 的开发机执行上述命令为准。

---

## 6. 下一项

进入 w27-next-plan §5：汇总 G4 发布门禁证据——全量 `mvn verify`，基础 shadowdiff、seeded PvE、AI 矩阵，以及本轮新增的 `--duo party`、`--duo death-pk`、`--persistence`、`--lock`；同时继续把技能、脚本、行会/攻城、交易和真实客户端对拍作为红线缺口列出，不签发超出证据范围的 G4 结论。
