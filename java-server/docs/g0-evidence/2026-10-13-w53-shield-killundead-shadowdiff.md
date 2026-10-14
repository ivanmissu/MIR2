# W53 证据：魔法盾 / 圣言术 影子对拍场景与门禁行

*2026-10-13 · W53 · 沙箱实跑（本地 ECJ + jdk4py 链路构建，见下文「运行环境」）*

计划见 [`w53-next-plan.md`](../w53-next-plan.md)。本证据覆盖：`--skills --skill-case shield|killundead`
两个新对拍场景、正负对照、`g4-release-gate.tsv` 四个新门禁行（清单扩至 26 行），以及
`WorldEngine.java` 魔法盾熟练度训练逻辑的补齐。

> **G4 未签发。** 能力矩阵仍有 `unimplemented` 行（行会 / 攻城 / 交易 / Market_Def 事务等），
> 本证据只是 Java↔Java 确定性对拍证据，不是 Delphi / 真实客户端的差分证据。

## 运行环境

- JRE：`jdk4py` 提供的 Temurin **25.0.2**（`java.compiler` 模块可见，编译走 ECJ）。
- 编译器：ECJ 3.46.100（`-21 -nowarn -proc:none -encoding UTF-8`），主代码 124 个源文件、测试 114 个源文件全部编译通过。
- 测试：JUnit Jupiter 5.14.2 / Platform 1.14.2（`junit-platform-launcher` 驱动）；sqlite-jdbc 3.46.1.0；slf4j-api 2.0.17。

## 一、单测与门禁回归

| 运行 | 结果 | 说明 |
|---|---|---|
| W52 基线 | 653 测试全绿 | W52 交付状态 |
| W53（改动后） | 659 测试全绿 | +6：`ScenarioRegressionTest`(+4)、`OpTest`(+1)、`ShadowDiffMainArgsTest`(+1) |

新增用例：

- `skillsShieldScenarioActivatesRefusesAndExpiresIdentically`（严格消息；断言魔法盾激活、持续期重复施法被拒、超时重施与 `train=6`）
- `skillsShieldWrongSeedIsDetected`（断言 18s 与 19s 持续时间在 18.25s 重施时的确定性发散）
- `skillsKillUndeadScenarioKillsTargetScarecrowIdentically`（严格消息；断言首发未即死、次发即死成功、`combat=[death other at=(20,21), exp +15 total=15]` 与 `train=3`）
- `skillsKillUndeadWrongSeedIsDetected`（断言 `TURN_UNDEAD` 随机流导致首发命中发散）
- `w53SkillScriptsCastRealSpellsAndRoundTrip`（脚本结构与操作码序列）
- `w53SkillCasesPinTheirLevelRequirementsAndDummyKind`（等级门与稻草人不死模板断言）

`G4ReleaseGateTest` 与 `G4CapabilityMatrixTest` 全部通过。

## 二、对拍实跑结果

所有运行均为 `--embedded`，两侧为同一配置、同一种子的独立 Java 服务端；技能场景一律
MANUAL 世界时钟，`--right-seed` 时仅右侧换种子。

### 魔法盾（`--skill-case shield`，法师 31，0 怪）

| 运行 | 命令要点 | 判定 | 观测 |
|---|---|---|---|
| 正向 | `--seed 20260922 --strict-messages` | **PASS**（state 0 / acks 0 / messages 0） | `spell 31 20 20` 激活盾（MP 572→537，训练+3）；29 ticks 后重施被拒（`BUFF_ALREADY_ACTIVE`，MP 不变）；370 ticks 后盾过期再次施法成功（MP 569→534，训练+1）；400 ticks 后再施法成功；重登后训练 `train=6` 保持 |
| 负向 | `--seed 20260922 --right-seed 99999` | **FAIL**（state 16） | 第三轮持续时间抽签（19s vs 18s），在 365 ticks (18.25s) 处右侧已超时重施成功（训练 7）、左侧仍在持续被拒（训练 6） |

### 圣言术（`--skill-case killundead`，法师 39，8 × **不死稻草人**）

| 运行 | 命令要点 | 判定 | 观测 |
|---|---|---|---|
| 正向 | `--seed 20260922 --strict-messages` | **PASS**（state 0 / acks 0 / messages 0） | 首发对北臂 2 号稻草人：k=87 未即死，扣 65 MP（854→789），怪唤醒；30 ticks 后次发对南臂 6 号稻草人：k=50 即死成功，扣 65 MP（789→724），`SM_DEATH`、`exp +15 total=15`、地面掉落金币、训练 `train=3`；重登后经验与训练保持 |
| 负向 | `--seed 20260922 --right-seed 99999` | **FAIL**（state 7） | 首发即死抽签差异（左侧 87 未即死 vs 右侧 36 即死成功），经验 0 vs 15、死亡帧与训练差异 |

## 三、门禁清单执行

执行 `scripts/g4-release-gate.sh` 运行全部 16 条技能门禁（含既有 12 条与本周新增 4 条），结果全部符合预期：

```
[g4-gate] (11/26) shadowdiff-skills                     -> PASS (exit 0)
[g4-gate] (12/26) shadowdiff-skills-negative            -> PASS (exit 1)
[g4-gate] (13/26) shadowdiff-skills-firewall            -> PASS (exit 0)
[g4-gate] (14/26) shadowdiff-skills-firewall-negative   -> PASS (exit 1)
[g4-gate] (15/26) shadowdiff-skills-spacemove           -> PASS (exit 0)
[g4-gate] (16/26) shadowdiff-skills-spacemove-negative  -> PASS (exit 1)
[g4-gate] (17/26) shadowdiff-skills-fireboom            -> PASS (exit 0)
[g4-gate] (18/26) shadowdiff-skills-fireboom-negative   -> PASS (exit 1)
[g4-gate] (19/26) shadowdiff-skills-lightflower         -> PASS (exit 0)
[g4-gate] (20/26) shadowdiff-skills-lightflower-negative-> PASS (exit 1)
[g4-gate] (21/26) shadowdiff-skills-snowwind            -> PASS (exit 0)
[g4-gate] (22/26) shadowdiff-skills-snowwind-negative   -> PASS (exit 1)
[g4-gate] (23/26) shadowdiff-skills-shield              -> PASS (exit 0)
[g4-gate] (24/26) shadowdiff-skills-shield-negative     -> PASS (exit 1)
[g4-gate] (25/26) shadowdiff-skills-killundead          -> PASS (exit 0)
[g4-gate] (26/26) shadowdiff-skills-killundead-negative -> PASS (exit 1)
```
