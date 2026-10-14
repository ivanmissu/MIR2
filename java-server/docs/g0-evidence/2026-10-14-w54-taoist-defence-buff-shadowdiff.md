# W54 证据：幽灵盾 / 神圣战甲术 影子对拍场景与门禁行

*2026-10-14 · W54 · 沙箱实跑（本地 ECJ + jdk4py 链路构建，见下文「运行环境」）*

计划见 [`w54-next-plan.md`](../w54-next-plan.md)。本证据覆盖：`--skills --skill-case ghostshield|holyarmour`
两个新对拍场景、正负对照、`g4-release-gate.tsv` 四个新门禁行（清单扩至 30 行），以及为这两条
零伤害技能补齐的三处观测面（`SM_ABILITY` 的 AC/MAC 防御字、`SM_DURACHANGE` 磨损追踪、
细粒度到期阶梯）。

> **G4 未签发。** 能力矩阵仍有 `unimplemented` 行（行会 / 攻城 / 交易 / Market_Def 事务等），
> 本证据只是 Java↔Java 确定性对拍证据，不是 Delphi / 真实客户端的差分证据。

## 运行环境

- JRE：`jdk4py` 提供的 Temurin **25.0.2**（纯 JRE，无 `javac`，编译走 ECJ jar）。
- 编译器：ECJ 4.34（3.40.0，`-source 21 -target 21 -nowarn -proc:none -encoding UTF-8`），
  主代码 124 个源文件、测试 114 个源文件全部编译通过。
- 测试：JUnit Platform Console Standalone 1.11.0（Jupiter 5.11.0）；sqlite-jdbc 3.46.1.0；
  slf4j-api/simple 2.0.9。
- 沙箱无 Maven：清单行的 `{{jar}}` 版本（fat JAR）由 CI 构建；本地以同一参数向量经
  `ShadowDiffMain.run` / CLI classpath 实跑等价行（见下）。

## 一、单测与门禁回归

| 运行 | 结果 | 说明 |
|---|---|---|
| W53 基线 | 659 测试全绿 | W53 交付状态 |
| W54（改动后） | **669 测试全绿**（117 containers，0 failed） | +10：`ScenarioRegressionTest`(+4)、`ShadowDiffTest`(+3)、`OpTest`(+1)、`ShadowDiffMainArgsTest`(+1)、`G4ReleaseGateTest`(+1) |

新增用例：

- `skillsGhostShieldScenarioBurnsCharmsAndExpiryLadderIdentically`（严格消息；断言 mac 2-10 增益字、
  四发护身符 9600/10000、`train=6`、无符首发 `mp=142/146` 只扣不退）
- `skillsGhostShieldWrongSeedIsDetected`（断言持续/训练抽签偏离被 STATE 抓到）
- `skillsHolyArmourScenarioLiftsTheAcBoundIdentically`（严格消息；断言 ac 0-5 升起而 mac 恒 2-5，
  以此把两条 `btType` 臂分开钉住）
- `skillsHolyArmourWrongSeedIsDetected`
- `defenceWordDriftIsAStateFailureNotJustAMessageHint` / `acWordDriftIsAStateFailureForThePhysicalArmToo`
  / `defenceWordsRenderAsRangesAndStayInvisibleUntilTheFirstAbilityFrame`（differ 单元层证明防御字
  漂移是 STATE 失败，且 -1 兼容渲染不改变既有报告行）
- `w54DefenceBuffScriptsAreShapeIdenticalTwinsBehindADeadlineLadder`（29 op 同构 + 阶梯序列钉死）
- `w54DefenceBuffCasesPinTheirTaoistSetupAndCharmBag`（22/25 级、`NeedL1`、护身符种子、旧 case 空背包不變）
- `skillCaseRowsDriveTheirOwnCase`（九个 skill-case 门禁行各自 `--skill-case` 值）

`G4ReleaseGateTest` 与 `G4CapabilityMatrixTest` 全部通过。

## 二、对拍实跑结果

所有运行均为 `--embedded`，两侧为同一配置、同一种子的独立 Java 服务端；技能场景一律
MANUAL 世界时钟，`--right-seed` 时仅右侧换种子。

### 幽灵盾（`--skill-case ghostshield`，道士 22，背包预种护身符，0 怪）

| 运行 | 命令要点 | 判定 | 观测 |
|---|---|---|---|
| 正向 | `--seed 20260922 --strict-messages` | **PASS**（state 0 / acks 0 / messages 0，30 个观测点） | 入场 `mac=2-5 ac=0-0`；无符首发 MP 146→142 且只有 `SM_MAGICFIRE_FAIL`；佩戴后 cast #1 耐久 9900、mac 升 2-10、训练 3；窗口内 cast #2 耐久 9800、训练 4；联合窗口在累计 1040 tick 的桶内到期回 2-5；cast #3 在其后 1001 tick 桶过期；cast #4 后重登：mac 回裸身 2-5 而耐久 9600/10000、训练 6、MP 142 全部保持 |
| 负向 | `--seed 20260922 --right-seed 99999` | **FAIL**（state 23） | cast #1 训练先行分叉（3 vs 2）；联合截止右侧 1011、左侧 1040，累计 1011 tick 的 `tick 9` 桶首次以 `mac: 2-10 vs 2-5` 拉开物理偏离——差异由持续抽签在防御字上产生，不是仅训练点数 |

### 神圣战甲术（`--skill-case holyarmour`，道士 25，同构脚本）

| 运行 | 命令要点 | 判定 | 观测 |
|---|---|---|---|
| 正向 | `--seed 20260922 --strict-messages` | **PASS**（state 0 / acks 0 / messages 0） | 入场 `ac=0-0 mac=2-5`；每发 MP −4（185→181 首发即含 fizzle）；佩戴后 ac 升 0-5、mac 恒 2-5；阶梯/训练/耐久与幽灵盾同数（截止 1040、cast #3 于 1001、耐久 9600、训练 6），仅防御字臂不同 |
| 负向 | `--seed 20260922 --right-seed 99999` | **FAIL**（state 23） | 训练 3 vs 2 先行；累计 1011 tick 桶首次 `ac: 0-5 vs 0-0`，23 步状态差异 |

## 三、门禁清单执行

沙箱无 Maven，未跑 fat JAR 版 `scripts/g4-release-gate.sh`；四条 W54 行已由
`ScenarioRegressionTest` 以清单**同参数向量**（`--embedded --skills --skill-case <case>
[--strict-messages] --seed 20260922 [--right-seed 99999]`）经 `ShadowDiffMain.run` 实跑并
断言退出码 0 / 1（见上表）。清单机械校验（30 行必需行、11 条负向对照、命令可解析性、
runner 数据驱动）随全量单测通过；fat JAR 全量跑批留待 CI 的 `java-server-dist` 工作流。
