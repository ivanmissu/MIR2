# W52 开发计划：范围技能批次收口 —— 爆裂火焰 / 地狱雷光 / 冰咆哮 进入影子对拍

W51 把 W49/W50 落地的两条技能分支（火墙、瞬息移动）接进 shadowdiff 并补齐门禁行，同时在
「下一候选」里写下 W52 的方向：**范围技能 shadowdiff（FireBoom 23 / LightFlower 24 /
SnowWind 33），需先为每个技能确定木桩布局与脚本**。本周兑现这一条，并顺带补上 W51 遗留的
**CI 失败可读性**缺口（见 §5）。

本周不再新增任何技能实现：23 / 24 / 33 三条分支分别由 W42 / W43 / W44 落地，且都已各有
单测与协议测试。本切片只做三件事：把三条分支放进**同一份确定性脚本驱动的双服对拍**、
为每条分支各配一条负向对照、把 `g4-release-gate.tsv` 从 16 行扩到 22 行。

## 一、为什么这三条能直接对拍

| 技能 | 命令形状 | 为什么可确定对拍 |
|---|---|---|
| `SKILL_FIREBOOM`(23) | 地面点击 + 3×3 方形（`MIR2_FIREBOOM_RANGE`，默认 1） | `MagBigExplosion` 一次共享 `GetAttackPower`，`RM_MAGSTRUCK` 即时下发，无延迟事件 |
| `SKILL_LIGHTFLOWER`(24) | 地面点击，但爆心是**施法者自身**（`MIR2_ELEC_BLIZZARD_RANGE`，默认 2） | `MagElecBlizzard` 只读自身坐标；`LA_UNDEAD` 满威力、其余 `power div 10` |
| `SKILL_SNOWWIND`(33) | 与 23 共用 `MagBigExplosion`，半径走 `MIR2_SNOW_WIND_RANGE` | 同上，且能把两个配置字段分开钉住 |

三条都在 `--skills` 既有前提内：预种角色 + 静止木桩环 + MANUAL 世界时钟 + 同种子。
伤害、MP、`SM_MAGIC_LVEXP` 训练、死亡/经验/掉落全部是种子与脚本的纯函数。

## 二、源码核验结论（Delphi，`GameOfMir/M2Server/Magic.pas`）

- **爆裂火焰** `Magic.pas:510-517`：`MagBigExplosion(PlayObject, nTargetX, nTargetY,
  g_Config.nFireBoomRage)`；`nPower` 在遍历方块**之前**求值一次，方块内每个 proper target
  共用同一个 `nPower`（`Magic.pas:1170-1190`）。
- **冰咆哮** `Magic.pas:578-585`：同一入口，末参换成 `g_Config.nSnowWindRange`。
  GEEM2 Magic.DB：`NeedL1=35`、`spell=12/defSpell=30`、`power=defPower=12/14`、`Delay=60`。
- **地狱雷光** `Magic.pas:518-520` → `MagElecBlizzard`（`Magic.pas:1191`）：`nX/nY` 取自
  `PlayObject.m_nCurrX/m_nCurrY`，点击坐标不参与爆心；`if BaseObject.m_btLifeAttrib =
  LA_UNDEAD then nDamage := nPower else nDamage := nPower div 10`；**没有** `SetTargetCreat`、
  没有抗魔判定；`Result := True`（→ `boTrain`）只要求方块里存在 proper target。
  该行 `NeedL1=30`，因此本周的 case 取 31 级法师（不是 W51 的 25 级）。
- **木桩种类**：`Monster.DB` 首批十种里只有 `稻草人` 的 `Undead=1`（HP 20 / Exp 15）。
  地狱雷光若继续用 `练功师`（`Undead=0`），伤害走 `power div 10`，低几位会被整数除法抹掉，
  负向对照可能落到同一个商上 —— 这正是本周给 `SkillCase` 增加木桩种类的原因。

## 三、本周范围

### 1. `SkillCase` 增加木桩种类（`TrainingDummyKind`）

`runSkillScenario` 原本把 `"trainer"` 写死；现在改为 `skillCase.monsterKind()`，取值来自新的
`TrainingDummyKind` 枚举（`trainer` / `scarecrow`）。**既有 case 的取值一个未变**：
healing / firewall / spacemove 仍全部是 `trainer`，`--skills` 默认行为逐字节不变。

| case | 职业 / 等级 | 预种技能 | 木桩 | 脚本 |
|---|---|---|---|---|
| `healing`（默认） | 道士 31，缺口 80 | 29 群体治愈术（lv0） | 0 | `Op.areaHealingScript()`（不变） |
| `firewall` | 法师 25 | 22 火墙（lv0） | 8 × trainer | `Op.fireWallScript()`（不变） |
| `spacemove` | 法师 25 | 21 瞬息移动（lv3） | 0 | `Op.spaceMoveScript()`（不变） |
| `fireboom` | 法师 25 | 23 爆裂火焰（lv0） | 8 × trainer | `Op.fireBoomScript()` |
| `lightflower` | 法师 31 | 24 地狱雷光（lv0） | 8 × **scarecrow** | `Op.lightFlowerScript()` |
| `snowwind` | 法师 35 | 33 冰咆哮（lv0） | 8 × trainer | `Op.snowWindScript()` |

### 2. 三段脚本

- **fireboom**：`spell 23 20 19`（木桩环北臂，3×3 吃 5 只）→ `tick 1` → `tick 29`（1450 ms，
  跨过 1350 ms 施法间隔 + 行延迟 60 ms）→ `spell 23 20 21`（南臂，另外 5 只）→ `tick 1` →
  `relog` → `bag`。两次施法是**两次独立的共享威力抽签**，把单次 3 个可能取值的判定面翻倍。
- **snowwind**：与 fireboom 同形，魔法号 33、等级 35、MP 33/次。
- **lightflower**：`spell 24 20 20`（施法者自身格）→ `tick 1`（八只稻草人同吃满威力、全灭，
  死亡帧 + `exp +15 × 8` + 掉落进入同一观测桶）→ `tick 29` → 第二发打**空方块**（照样扣蓝、
  照样 roll 威力，但无目标 ⇒ 无伤害、**无训练**，即 `boTrain` 语义）→ `relog` → `bag`。

### 3. 门禁与测试

- `g4-release-gate.tsv` 新增六行（均 `blocking=yes`）：`shadowdiff-skills-fireboom`、
  `shadowdiff-skills-fireboom-negative`、`shadowdiff-skills-lightflower`、
  `shadowdiff-skills-lightflower-negative`、`shadowdiff-skills-snowwind`、
  `shadowdiff-skills-snowwind-negative`。
- `G4ReleaseGateTest`：必需行集合、负向对照集合、`skillsRowsDriveTheDedicatedSpellScenario`
  三处同步扩展。
- `ScenarioRegressionTest` 新增 6 条（三条正向严格消息 + 三条负向），`OpTest` 新增
  `areaSkillScriptsCastRealSpellsAndClearTheSpellIntervalWithoutSleeping`，
  `ShadowDiffMainArgsTest` 新增 `areaSkillCasesPinTheirLevelRequirementsAndDummyKind`。
- `g4-capability-matrix.tsv`：`SKILL_FIREBOOM:wizard` / `SKILL_LIGHTFLOWER:wizard` /
  `SKILL_SNOWWIND:wizard` 的 evidence 列追加 `ScenarioRegressionTest`，notes 注明 W52 起有
  shadowdiff 场景（状态仍 `implemented`，不改变任何行状态）。

### 4. 负向对照（右侧 `--right-seed 99999`，本机实测）

| 场景 | 观测到的差异 | 判定 |
|---|---|---|
| fireboom | 首轮 `dmg=12` vs `10`，次轮 `12` vs `11`；训练 4 vs 5（7 步状态差异） | FAIL ✅ |
| lightflower | 满威力伤害不同；金币堆 2 vs 1；训练 3 vs 2（7 步） | FAIL ✅ |
| snowwind | 首轮 `dmg=23` vs `21`，次轮 `23` vs `22`；训练 4 vs 5（7 步） | FAIL ✅ |

### 5. 顺带交付：G4 CI 失败必须可读（W51 遗留待办）

W51 的 CI G4 作业失败过一次（run 38014544715），但沙箱读不到 job 日志与 artifact，
「失败行未定位」被挂账。本周把诊断做成**可从沙箱读取的两个通道**：

- `scripts/g4-release-gate.sh`：失败时在 stdout 打印 `FAILING ROWS: <id,...>` 与每行的
  `FAIL DIAG <id>: 状态差异 N 步…`（诊断取自该行的 shadow-report.md 结论行，或日志末行），
  并把「失败行明细 + 每行日志尾部」写进汇总 Markdown。
- `.github/workflows/java-server.yml` 的 G4 作业：把上述行转成 `::error::` **check-run
  annotation**，并把汇总 Markdown 追加进 `$GITHUB_STEP_SUMMARY`。两者都能用
  `gh api repos/.../check-runs/<id>` 读到 —— 这正是沙箱唯一能读的 CI 产物形式
  （job 日志与 artifact 走 `*.blob.core.windows.net` / `results-receiver.actions.githubusercontent.com`，
  实测均为 EOF）。

**接线缺陷（本周实跑发现并修复）**：`row_diagnosis()` 最初定义在跑批循环**之后**。bash 只在控制流
到达定义处才生成函数，因此循环里的调用报 `command not found`（stderr 被重定向进日志，stdout 收到空串），
`FAIL DIAG` 行**恒为空**——恰好在 CI 最需要它的时候失效。修复：函数上移到循环之前，并补两条回归测试
（定义必须早于首次调用；workflow 必须消费指纹，且 `PIPESTATUS[0]` 不能被 `tee` 吞掉）。同时把诊断的
回退顺序改为「report 结论行 → 该行 `[shadowdiff] verdict=` 行 → 日志末行」，以覆盖「实跑是绿的、
只是退出码与 `expect` 不符」这种清单配置错误。

**失败路径验证**：本地把一行正向的 `expect` 临时反转为 `exit-1`，门禁如期望红（exit 1）并打印

```
[g4-gate] FAILING ROWS: shadowdiff-skills-fireboom
[g4-gate] FAIL DIAG shadowdiff-skills-fireboom: 状态差异 0 步，应答差异 0 步，消息集合差异 0 步，共 9 步。
```

汇总 Markdown 同步出现「失败行明细」表与该行日志尾部；随后还原清单、复核 22 行全绿。

## 边界与刻意不做

- **不新增技能实现**：23/24/33 的引擎语义一行未改（W42/W43/W44 交付），本周只加对拍场景与门禁。
- **地狱雷光只覆盖 `LA_UNDEAD` 满威力臂**：`--monsters` 只能放一种模板，无法在同场混编不死/活物；
  `power div 10` 的活物分支继续由 `WorldElecBlizzardTest` 单测覆盖，证据文档已注明。
- **不加新配置项**：半径继续用默认值（1/2/1），脚本与断言按默认值钉死；改默认值会让回归测试变红，
  这是刻意的（配置漂移必须显式）。
- **不改 `Op`/`StateSnapshot` 的观测面**：三条技能的伤害、训练、经验、掉落全部落在既有字段里。
- **红线不动**：16 困魔咒 / 17 召唤骷髅 / 20 诱惑之光 / 30 召唤神兽、34–41 扩展段、57 怪行为扩展、
  NPC 脚本引擎均未触碰。
- **G4 仍未签发**：本切片只增加 Java↔Java 确定性证据，`g4-capability-matrix.tsv` 仍有
  `unimplemented` 行（行会 / 攻城 / 交易 / Market_Def 事务等）。

## 状态

- [x] Delphi 源码核验（Magic.pas:510-520 / 578-585 / 1170-1191；Monster.DB `Undead` 列）
- [x] `SkillCase.TrainingDummyKind` + 三个新 case；`runSkillScenario` 改读 case 的木桩种类
- [x] `Op.fireBoomScript()` / `Op.lightFlowerScript()` / `Op.snowWindScript()`
- [x] 本地实跑：三条正向（严格消息）PASS；三条负向如期 FAIL（差异见 §4）
- [x] `g4-release-gate.tsv` 六行 + `G4ReleaseGateTest` 同步
- [x] `ScenarioRegressionTest`(+6) / `OpTest`(+1) / `ShadowDiffMainArgsTest`(+1)
- [x] `g4-capability-matrix.tsv` evidence / notes 列更新
- [x] CI 失败可读性：门禁脚本 FAILING ROWS / FAIL DIAG + workflow 注解与 job summary
- [x] 本地全量编译 + 单测（沙箱 ECJ + jdk4py 链路）：`~/tools/test.sh` → **650 successful / 0 failed**（W51 为 642）
- [x] 本地全量门禁 22 行跑批：**22 行全绿、exit 0**（`maven-verify` SKIP，7 条负向行如期 `exit-1`，11.5 min）
- [x] 推送分支 + PR [#78](https://github.com/ivanmissu/MIR2/pull/78) + CI 复核：run 38202881179 五个作业全绿（含 G4 门禁作业，22 行清单首次在 CI 跑通）；提交 b9baa61 的 check-run 可用 `gh api` 逐一复核
- [x] 失败指纹接线缺陷修复 + 两条回归测试（定义早于调用 / workflow 消费指纹）
- [x] 证据：`docs/g0-evidence/2026-10-12-w52-area-skill-shadowdiff.md`（含 650/650 实跑数与 §5 CI 可读性）
