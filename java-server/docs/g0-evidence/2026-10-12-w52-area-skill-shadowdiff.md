# W52 证据：爆裂火焰 / 地狱雷光 / 冰咆哮 影子对拍场景与门禁行

*2026-10-12 · W52 · 沙箱实跑（本地 ECJ + jdk4py 链路构建，见下文「运行环境」）*

计划见 [`w52-next-plan.md`](../w52-next-plan.md)。本证据覆盖：`--skills --skill-case
fireboom|lightflower|snowwind` 三个新对拍场景、正负对照、`g4-release-gate.tsv` 六个新门禁行、
`SkillCase` 的木桩种类参数化，以及 W51 遗留的 **G4 CI 失败可读性**改动（门禁脚本
`FAILING ROWS` / `FAIL DIAG` + workflow check-run 注解与 job summary）。

> **G4 未签发。** 能力矩阵仍有 `unimplemented` 行（行会 / 攻城 / 交易 / Market_Def 事务等），
> 本证据只是 Java↔Java 确定性对拍证据，不是 Delphi / 真实客户端的差分证据。

## 运行环境

- JRE：`jdk4py` 提供的 Temurin **25.0.2**（`java.compiler` 模块可见，编译走 ECJ）。
- 编译器：ECJ 3.46.100（`gh api repos/NorbertXYZ/eclipse-maven/git/blobs/<sha>` 取回，
  `-21 -nowarn -proc:none -encoding UTF-8`），主代码 124 个源文件、测试 114 个源文件全部编译通过。
- 测试：JUnit Jupiter 5.14.2 / Platform 1.14.2（`junit-platform-launcher` 驱动，自写 `RunTests`
  入口）；sqlite-jdbc 3.46.1.0（SHA-1 `97ca06712e2afa8c0e551245da72280fca5474cf` 与上游 `.sha1` 一致）；
  slf4j-api 2.0.17。
- 与 CI 的差异：沙箱无法访问 Maven Central，本地单测/对拍走 ECJ 链路与手工 fat JAR；CI 用 Maven
  shade 构建的 `mir2-shadowdiff.jar` 复跑同一清单与同一批测试。

## 一、单测与全量回归

| 运行 | 结果 | 说明 |
|---|---|---|
| W51 基线（改动前，`--class` 全量跑） | **642 / 642** 全绿 | 与 W51 证据记录的基线一致 |
| W52（改动后） | **650 / 650** 全绿 | +8：`ScenarioRegressionTest`(+6)、`OpTest`(+1)、`ShadowDiffMainArgsTest`(+1) |

新增用例：

- `skillsFireBoomScenarioStrikesBothRingArmsIdentically`（严格消息；断言两轮 `dmg=12` 与 `train=4`）
- `skillsFireBoomWrongSeedIsDetected`
- `skillsLightFlowerScenarioKillsTheUndeadRingIdentically`（严格消息；断言 `dmg=37` / `hp=0/20`、
  `exp +15 total=120`、第二发空爆的 `mp=514/572` 与「不训练」）
- `skillsLightFlowerWrongSeedIsDetected`
- `skillsSnowWindScenarioStrikesBothRingArmsIdentically`
- `skillsSnowWindWrongSeedIsDetected`
- `areaSkillScriptsCastRealSpellsAndClearTheSpellIntervalWithoutSleeping`（脚本形状与 tick 预算）
- `areaSkillCasesPinTheirLevelRequirementsAndDummyKind`（23→25 级 / 24→31 级 / 33→35 级；
  `lightflower` 必须用 `scarecrow`，且 `MonsterTemplate.forName("scarecrow").undead()` 为真）

`G4ReleaseGateTest`（必需行集合、负向对照、CLI 参数向量、runner 数据驱动）与
`G4CapabilityMatrixTest`（矩阵 × Delphi 常量、evidence 指向真实测试类）在全量运行中一并通过。

## 二、对拍实跑结果

所有运行均为 `--embedded`，两侧为同一配置、同一种子的独立 Java 服务端；技能场景一律
MANUAL 世界时钟（时间只由脚本的 `tick` 推进），`--right-seed` 时仅右侧换种子。

### 爆裂火焰（`--skill-case fireboom`，法师 25，8 × 木桩）

| 运行 | 命令要点 | 判定 | 观测 |
|---|---|---|---|
| 正向 | `--seed 20260922 --strict-messages` | **PASS**（state 0 / acks 0 / messages 0） | `spell 23 20 19`：MP 398→384（14 蓝），5 只木桩同吃 `dmg=12`（`hp=9987/9999`）；`spell 23 20 21`：MP 384→370，另外 5 只同吃 `dmg=12`（两臂交集的 2 只此前已被打掉 12 HP，故显示 `9975/9999`）；训练 `train=3 → 4`；重登后保持 |
| 负向 | `--seed 20260922 --right-seed 99999` | **FAIL**（state 7） | 首轮 `dmg=12` vs `10`、次轮 `12` vs `11`；训练 `train=4` vs `5` |

### 地狱雷光（`--skill-case lightflower`，法师 31，8 × **不死稻草人**）

| 运行 | 命令要点 | 判定 | 观测 |
|---|---|---|---|
| 正向 | `--seed 20260922 --strict-messages` | **PASS**（state 0 / acks 0 / messages 0） | `spell 24 20 20`：MP 572→543（29 蓝），八只稻草人同吃满威力 `dmg=37 hp=0/20`（若走 `power div 10` 分支只有 3 点，且 20 HP 不会死）；八条 `death other at=(…)`、`exp +15 total=120`、地面掉落 `金币 at=(20,19)` / `金币 at=(21,20)`；第二发（1450 ms 后）打空方块：MP 543→514（照样扣蓝），**训练保持 `train=3` 不涨**（`boTrain` 只在方块里有 proper target 时为真）；重登后经验、掉落与训练行全部保持 |
| 负向 | `--seed 20260922 --right-seed 99999` | **FAIL**（state 7） | 满威力伤害不同、金币堆 2 vs 1、训练 `train=3` vs `2` |

### 冰咆哮（`--skill-case snowwind`，法师 35，8 × 木桩）

| 运行 | 命令要点 | 判定 | 观测 |
|---|---|---|---|
| 正向 | `--seed 20260922 --strict-messages` | **PASS**（state 0 / acks 0 / messages 0） | `spell 33 20 19`：MP 706→673（33 蓝，`spell=12/defSpell=30` 的 lv0 开销），5 只同吃 `dmg=23`；`spell 33 20 21`：MP 673→640，另外 5 只同吃 `dmg=23`；训练 `train=3 → 4`；重登后保持 |
| 负向 | `--seed 20260922 --right-seed 99999` | **FAIL**（state 7） | 首轮 `dmg=23` vs `21`、次轮 `23` vs `22`；训练 `train=4` vs `5` |

### 既有技能场景回归（未改动）

| 运行 | 判定 |
|---|---|
| `--skills --strict-messages --seed 20260922`（默认 healing） | **PASS** |
| `--skills --skill-case firewall --strict-messages --seed 20260922` | **PASS** |
| `--skills --skill-case spacemove --strict-messages --seed 20260922` | **PASS** |

## 三、木桩布局与「为什么换种类」

`EmbeddedWorld` 的 `safeZoneSize=0`（对比世界刻意关掉出生安全区）使木桩环从半径 1 开始铺：
8 只木桩恰好占满 `ringCells((20,20), 1)` 的八个格。爆裂火焰/冰咆哮的 3×3 方形以点击格为心，
北臂 `(20,19)` 与南臂 `(20,21)` 各覆盖 5 只（两臂在 `(19,20)`/`(21,20)` 各有一只重叠）。

地狱雷光以**施法者**为心、半径 2，八个格一次全中；但它的伤害分流是
`LA_UNDEAD → nPower`、其余 `→ nPower div 10`。若沿用 `练功师`（Undead=0），八只各只掉个位数，
负向对照可能在整数除法之后落到同一个商上 —— 那样「对拍」就会在**判定面被抹平**的情况下假绿。
首批十种模板里唯一 `Undead=1` 的是 `稻草人`，于是 `SkillCase` 增加木桩种类维度：

- 好处：LA_UNDEAD 分支的**满威力**数值直接上到线上，负向对照有足够的判定面（本机实测必 FAIL）。
- 代价（诚实记录）：同一次运行只能放一种模板，`power div 10` 的活物分支不在这条场景里，
  继续由 `WorldElecBlizzardTest` 单测覆盖。

## 四、门禁清单

`docs/g4-release-gate.tsv` 新增六行（均 `blocking=yes`），清单从 16 行变为 22 行：

| id | expect | 证明什么 |
|---|---|---|
| `shadowdiff-skills-fireboom` | exit-0 | 两次共享威力抽签、MP、训练与重登一致性 |
| `shadowdiff-skills-fireboom-negative` | exit-1 | 爆裂火焰判定依赖 MAGIC 流（非空转） |
| `shadowdiff-skills-lightflower` | exit-0 | 施法者中心 + LA_UNDEAD 满威力 + 死亡/经验/掉落 + 空爆不训练 |
| `shadowdiff-skills-lightflower-negative` | exit-1 | 不死伤害与掉落抽签依赖种子（非空转） |
| `shadowdiff-skills-snowwind` | exit-0 | 冰咆哮复用 `MagBigExplosion` 但走自己的半径字段 |
| `shadowdiff-skills-snowwind-negative` | exit-1 | 冰咆哮判定依赖 MAGIC 流（非空转） |

一致性由 `G4ReleaseGateTest` 机械校验：行格式、artifact 唯一、`--report-dir` 结尾与 artifact 对齐、
负向行必须 `exit-1` 且两侧种子不同、技能行必须带 `--skills`、runner 不得硬编码任何行 id（全部通过）。

**本机跑批（2026-10-12，`--skip-maven --jar tools/mir2-shadowdiff.jar`）：22 行全绿、退出码 0**，
`maven-verify` 因沙箱无 Maven 记 `SKIP`（1 行），7 条负向行全部如期 `exit-1`；单行耗时 10–207 s，
合计 11.5 min（`/tmp/g4-w52/g4-release-gate.md`：失败行数 0）。本轮**冻结 JAR 与门禁脚本后不再改动**，
上一轮 16 行跑批中 `shadowdiff-ai-matrix` 的失败因此没有复现（见 § 五教训）。

## 五、CI 失败可读性（W51 遗留待办）

W51 的 G4 作业在 run `38014544715` 失败过一次（同代码重跑 `38017716504` 通过），失败行始终未定位：
沙箱内 `gh run view --job <id> --log`、`gh api .../jobs/<id>/logs`、artifact 下载全部返回 EOF
（Actions 日志与产物存储在 `*.blob.core.windows.net` / `results-receiver.actions.githubusercontent.com`，
本环境不可达）。本周给出**两个可从沙箱读取的通道**：

1. `scripts/g4-release-gate.sh` 失败时额外打印

   ```
   [g4-gate] FAILING ROWS: <id,...>
   [g4-gate] FAIL DIAG <id>: 状态差异 N 步，应答差异 N 步，消息集合差异 N 步，共 N 步。
   ```

   （诊断优先取该行 `shadow-report.md` 的结论行，取不到时回退到日志末行），并把「失败行明细 +
   每行日志尾部 40 行」写入汇总 `g4-release-gate.md`。
2. `.github/workflows/java-server.yml` 的 G4 作业把上述行转成 `::error::` check-run 注解，
   并把汇总 Markdown 追加进 `$GITHUB_STEP_SUMMARY`。

两者都能用 `gh api repos/ivanmissu/MIR2/check-runs/<id>` 读到（本次实测：现有失败作业的
`annotations` 可读，`output.summary` 因当前没有任何步骤写 summary 而为 `null`）。**历史失败仍
未定位**：本地用同一清单连跑两遍（16 行）只有一次异常，且经查是本次会话**在跑批过程中重建
fat JAR** 导致的运行时读 zip 失败（`--ai-all` 的 3/10 行报
`UncheckedIOException: failed reading MonItems/…`，与代码无关，已用同一 JAR 单独复跑该怪物
种类 PASS 证实）。教训：**跑批期间不得重建 JAR**；门禁脚本本身也因同一原因（脚本被编辑）
在收尾时报过语法错误，重跑即恢复。

## 六、已知边界

- 只比较 Java↔Java；不证明与 `mir2.exe` 或 Delphi 服务端一致。
- `lightflower` 只覆盖 `LA_UNDEAD` 满威力臂（见 §3 的诚实记录）。
- 三条场景都按**默认半径**（火墙侧 `MIR2_FIREBOOM_RANGE=1`、`MIR2_ELEC_BLIZZARD_RANGE=2`、
  `MIR2_SNOW_WIND_RANGE=1`）钉死；`MIR2_*_RANGE` 的非默认取值仍由 `ServerConfigTest` 与
  `WorldAreaExplosionTest`/`WorldElecBlizzardTest`/`WorldSnowWindTest` 单测覆盖。
- 木桩环的 8 只木桩 HP 9999，本切片里不会死；`lightflower` 的稻草人 20 HP 必死，因此那条场景
  额外把死亡/经验/掉落纳入观测面。

## 复现

```bash
# 构建 fat JAR 后（CI 或本地 mvn 环境）
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case fireboom --strict-messages --seed 20260922 --report-dir reports/fb
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case fireboom --seed 20260922 --right-seed 99999 --report-dir reports/fb-neg   # 期望 exit 1
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case lightflower --strict-messages --seed 20260922 --report-dir reports/lf
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case lightflower --seed 20260922 --right-seed 99999 --report-dir reports/lf-neg  # 期望 exit 1
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case snowwind --strict-messages --seed 20260922 --report-dir reports/sw
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case snowwind --seed 20260922 --right-seed 99999 --report-dir reports/sw-neg   # 期望 exit 1

# 整批门禁
java-server/scripts/g4-release-gate.sh --skip-maven --jar <fat-jar> --report-dir reports/g4
```
