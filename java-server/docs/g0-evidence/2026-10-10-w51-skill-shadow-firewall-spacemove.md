# W51 证据：火墙 / 瞬息移动 影子对拍场景与门禁行

*2026-10-10 · W51 · 沙箱实跑（本地 ECJ + jdk4py 链路构建，见下文「运行环境」）*

计划见 [`w51-next-plan.md`](../w51-next-plan.md)。本证据覆盖：`--skills --skill-case firewall|spacemove`
两个新对拍场景、正向与负向对照、`g4-release-gate.tsv` 四个新门禁行，以及 W50 基线的回归。

> **G4 未签发。** 能力矩阵仍有 `unimplemented` 行（技能 114 行、Market_Def 事务、行会、攻城、交易），
> 本证据只是 Java↔Java 确定性对拍证据，不是 Delphi / 真实客户端的差分证据。

## 运行环境

- JRE：`jdk4py` 提供的 Temurin 25 JRE（`java.compiler` 模块可用，编译走 ECJ）。
- 编译器：ECJ 3.46.100，`-21 -nowarn -proc:none`，主代码 124 个源文件、测试 114 个源文件，全部编译通过。
- 测试：JUnit Jupiter 5.14.2 / Platform 1.14.2 控制台启动器；sqlite-jdbc 3.46.1.0（SHA-1 与上游 `.sha1` 核对一致）
  + slf4j-api 2.0.17。
- 与 CI 的差异：沙箱无法访问 Maven Central，因此本地单测使用 ECJ 链路；CI 的 `java-server.yml` 以 `mvn verify` 复跑全部单测，
  以 `shadowdiff-smoke` 跑 embedded 自对拍，并以 `g4-release-gate` 作业（manifest 驱动，整批 16 行）跑门禁，JAR 由 Maven shade 构建。

## CI 状态（05b557d，未绿）

| 作业 | 结果 |
|---|---|
| `test`（`mvn verify`） | 通过 |
| `Bot-swarm`、`Wiretool`、`Shadowdiff embedded self-comparison` | 通过 |
| `G4 release gate (manifest-driven)` | **失败**（脚本退出码 1，即某个 blocking 行判 FAIL） |

- 失败行未知。沙箱无法读取该 run 的作业日志与 `g4-release-gate` 产物（blob 存储 EOF / 被拦截），
  `gh run rerun --failed` 与 `rerun-failed-jobs` 接口均被拒绝（"cannot be rerun" / 403）。
- 同一 commit 之前 `master` 上 `4aa30ba` 的 G4 作业（run 38013037017）通过；W51 对 healing 路径无改动。
- 本地复现（同一清单、同一 `--jar` 参数，均为 16/16 PASS、exit 0）：

  | 环境 | 结果 |
  |---|---|
  | JRE 25，`LANG` 未设 | 16/16 PASS |
  | Temurin 21.0.8，`LANG` 未设 | 16/16 PASS |
  | Temurin 21.0.8，`LANG=C.UTF-8` | 16/16 PASS |

- 残余差异：本地 JAR 为手工合并的 fat JAR（ECJ 编译），CI 为 Maven shade 构建。`shade` 配置仅过滤签名文件并合并
  services，没有 minimizeJar，两者类内容应等价，但这一点未经 CI 产物验证。
- 在 CI 门禁绿之前，本证据不作为 G4 通过证据。

## 单测与全量回归

| 运行 | 结果 | 说明 |
|---|---|---|
| W50 基线（改动前） | **636 / 636** 全绿 | 耗时约 6 分钟，含 shadowdiff 全部既有场景 |
| W51（改动后） | **642 / 642** 全绿 | +6：`ScenarioRegressionTest`(+5)、`ShadowDiffMainArgsTest`(+1) |

新增用例：
- `skillsFireWallScenarioBurnsTheDummyIdenticallyOnBothServers`（严格消息）
- `skillsFireWallWrongSeedIsDetected`
- `skillsSpaceMoveScenarioRelocatesTheCasterIdentically`（严格消息，断言落点 `(248,208)`）
- `skillsSpaceMoveWrongSeedIsDetected`
- `unknownSkillCaseIsAUsageErrorBeforeAnyServerBoots`
- `skillCaseIsAValuedOptionThatDefaultsToTheHealingScenario`

`G4ReleaseGateTest` 的清单完整性、负向对照、CLI 解析与 runner 数据驱动断言在全量运行中一并通过。

## 对拍实跑结果

所有运行均为 `--embedded`，两侧为同一配置、同一种子的独立 Java 服务端（`--right-seed` 时仅右侧换种子）。

### 火墙（`--skill-case firewall`，法师 25 级，8 只木桩，MANUAL 时钟）

| 运行 | 命令要点 | 判定 | 观测 |
|---|---|---|---|
| 正向 | `--seed 20260922 --strict-messages` | **PASS**（state 0 / acks 0 / messages 0） | `spell 22 18 20` 扣蓝 398→368；`tick 1` 木桩 `struck other dmg=8 hp=9991/9999`；约 3 s 后第二跳 `dmg=8 hp=9983/9999`；之后火焰到期，第三跳未出现；训练 `train=3`；重登后保持 |
| 正向（另一种子） | `--seed 20260921 --strict-messages` | **PASS** | 同一脚本、不同种子，两侧一致 |
| 负向 | `--seed 20260922 --right-seed 99999` | **FAIL**（state 8） | 灼烧伤害 `dmg=8` vs `dmg=6`，训练 `train=3` vs `train=2`，逐步差异 8 处 |

### 瞬息移动（`--skill-case spacemove`，法师 25 级，预种 lv3）

| 运行 | 命令要点 | 判定 | 观测 |
|---|---|---|---|
| 正向（lv0，探查） | `--seed 20260922` | PASS，但成功门未通过 | 扣蓝 10，坐标保持 `(20,20)`——只覆盖失败分支，因此正式场景改用 lv3 |
| 正向（lv3） | `--seed 20260922 --strict-messages` | **PASS**（state 0 / acks 0 / messages 0） | `spell 21 20 20` 扣蓝 398→380；坐标 `(20,20)`→`(248,208)`；重登后回到 `(20,20)`（坐标不持久化，引擎既有边界） |
| 负向（lv3） | `--seed 20260922 --right-seed 99999` | **FAIL**（state 3） | 落点 `(248,208)` vs `(191,57)`，逐步差异 3 处 |

### 技能回归（W41 群体治愈，未改动）

| 运行 | 判定 |
|---|---|
| `--skills --strict-messages --seed 20260922`（默认 case） | **PASS** |
| `--skills --skill-case healing --strict-messages --seed 20260922` | **PASS** |

## 门禁清单

`docs/g4-release-gate.tsv` 新增四行（均 `blocking=yes`）：

| id | expect | 证明什么 |
|---|---|---|
| `shadowdiff-skills-firewall` | exit-0 | 火墙灼烧、MP、训练与到期时序两侧一致，重登保持 |
| `shadowdiff-skills-firewall-negative` | exit-1 | 火墙判定依赖 MAGIC 流（非空转） |
| `shadowdiff-skills-spacemove` | exit-0 | 瞬息移动成功换位的落点两侧一致（3 级，门通过） |
| `shadowdiff-skills-spacemove-negative` | exit-1 | 瞬息移动落点依赖 SPACE_MOVE 流（非空转） |

门禁 TSV 与 CLI 的一致性由 `G4ReleaseGateTest.everyShadowdiffCommandParsesAsARealArgumentVector`
与 `negativeControlsStillDemandDivergence` 机械校验（均通过）。

`docs/g4-capability-matrix.tsv`：`SKILL_EARTHFIRE:wizard`、`SKILL_SPACEMOVE:wizard` 仍为 `implemented`，
evidence 列追加 `ScenarioRegressionTest`；未改变任何行的状态。

## 已知边界

- 坐标不随角色持久化（登录总是进出生点）：瞬息移动的落点在 `relog` 后不保留。这与 W50 的 `homeMapId` 落地口径一致，
  属于独立切片。
- 对拍只比较 Java↔Java；不证明与 `mir2.exe` 或 Delphi 服务端一致。
- 火墙场景只验证了十字右臂 (19,20) 上的一只木桩；十字的其它臂与出生点环上的其它木桩不参与对拍。未覆盖火墙的安全区门
  （`boDisableInSafeZoneFireCross`）与踩火即时伤害，两者仍由 `WorldFireWallTest` 单测覆盖。

## 复现

```bash
# 构建 fat JAR 后（CI 或本地 mvn 环境）
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case firewall --strict-messages --seed 20260922 --report-dir reports/fw
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case firewall --seed 20260922 --right-seed 99999 --report-dir reports/fw-neg   # 期望 exit 1
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case spacemove --strict-messages --seed 20260922 --report-dir reports/sm
java --enable-native-access=ALL-UNNAMED -jar mir2-shadowdiff.jar --embedded --skills --skill-case spacemove --seed 20260922 --right-seed 99999 --report-dir reports/sm-neg   # 期望 exit 1
```
