# W51 开发计划：技能批次收口 —— 火墙 / 瞬息移动进入影子对拍与发布门禁

延续 W28–W50 的按周技能迁移节奏，但本周**不再接入新的技能分支**。W50 之后 Magic.DB 1–33 的
可学技能已经全部落地，剩余 16 / 17 / 20 / 30 仍是红线（陷阱 / 召唤 / 宠物子系统）。于是下一个
缺口不再是“再写一个技能”，而是 W27 计划 §2 留下的待办：**技能类 shadowdiff 场景与
`g4-release-gate.tsv` 门禁行仍未补齐**。W42–W50 每周都在文档里写着“留作后继”，本周兑现。

本周选择的两条切片都满足 W49 / W50 文档中写明的前提——**在 MANUAL 时钟与世界种子下完全可对拍**：

| 切片 | 技能 | 为什么可以直接对拍 |
|---|---|---|
| 火墙 | `SKILL_EARTHFIRE`(22)，W49 落地 | 十字火墙按 `TFireBurnEvent` 的 3000 ms 节拍灼烧，伤害来自 MAGIC 流，到期时序由 tick 决定 |
| 瞬息移动 | `SKILL_SPACEMOVE`(21)，W50 落地 | 成功门 `Random(11)` 与落点 `MapRandomMove` 都来自 SPACE_MOVE 流，帧序（施法 → 离场 → 入场）可观测 |

## 源码核验结论（Delphi，`GameOfMir/M2Server/Magic.pas`）

- **火墙入口** `Magic.pas:501-509`：`SKILL_EARTHFIRE{22}` 分支调用 `MagMakeFireCross(PlayObject, …)`，
  不读取 `TargeTBaseObject`，地面点击合法。
- **十字铺设** `MagMakeFireCross`（`Magic.pas:1135-1169`）：先过安全区门（`boDisableInSafeZoneFireCross`），
  再按 `(x, y-1)`、`(x-1, y)`、`(x, y)`、`(x+1, y)`、`(x, y+1)` 五个臂逐个 `GetEvent` 查重后创建
  `TFireBurnEvent`；`Result := 1` 无条件，与铺成几格无关。W49 已按此实现。
- **瞬息移动入口** `Magic.pas:495-500`：分支先自播 `RM_MAGICFIRE` 再 `MagSaceMove`；`MagSaceMove`
  （`Magic.pas:951`）的成功门 `Random(11) < nLevel * 2 + 4`，lv0..3 为 4/11、6/11、8/11、10/11。W50 已按此实现。
- 结论：两条分支都不依赖 `TargeTBaseObject` 状态，因此 shadowdiff 的 `spell <id> <x> <y>`（targetId = 0）
  就是真实的 `CM_SPELL` 形状，无需新增 wire 操作。

## 本周范围

### 1. shadowdiff：`--skills --skill-case NAME`

`ShadowDiffMain` 的 `--skills` 原本只驱动 W41 群体治愈。本周把它参数化为 `SkillCase`：

| case | 职业 / 等级 | 预种技能 | 木桩 | 脚本 |
|---|---|---|---|---|
| `healing`（默认） | 道士 31，缺口 80 | 29 群体治愈术（lv0） | 0 | `Op.areaHealingScript()`（不变） |
| `firewall` | 法师 25 | 22 火墙（lv0） | 8（出生点环） | `Op.fireWallScript()` |
| `spacemove` | 法师 25 | 21 瞬息移动（**lv3**） | 0 | `Op.spaceMoveScript()` |

- 未指定 `--skill-case` 时行为与 W41 逐字节一致（`ScenarioRegressionTest` 的既有两条用例不改）。
- `--monsters` 现在可覆盖 case 的默认木桩数；`--script FILE` 仍可整体替换脚本。
- 非法 case 名在任何服务端启动前以用法错误（退出码 2）拒绝。

### 2. 火墙脚本（`Op.fireWallScript`）

`spell 22 18 20`（地面点击木桩所在格，十字中心）→ `tick 1` → `tick 60` ×2 → `tick 120` → `tick 60` →
`relog` → `bag`。301 个 tick 覆盖：创建后第一拍的即时灼烧、约 3 s 后的第二跳、到期（严格大于时长）之后
不再灼烧，以及重登后训练计数保持。

### 3. 瞬息移动脚本（`Op.spaceMoveScript`）

`spell 21 20 20`（施法者自己的格，无目标）→ `tick 1` → `tick 20` → `relog` → `bag`。

**为什么预种 lv3 而不是 lv0**：lv0 的成功门只有 4/11。在默认种子 `20260922` 下它失败，场景只能覆盖
“失败不移动”分支，而“离场→换图→入场”帧序与落点完全没有被对拍。lv3 的门为 10/11，在同一种子下通过，
落点为 `(248,208)`。这是场景输入的选择，不是对随机数的挑选：两侧仍由同一种子驱动，负向对照（见 §5）
证明结果确实依赖种子。

### 4. 门禁与测试

- `g4-release-gate.tsv` 新增四行：`shadowdiff-skills-firewall`、`shadowdiff-skills-firewall-negative`、
  `shadowdiff-skills-spacemove`、`shadowdiff-skills-spacemove-negative`（均 `blocking=yes`）。
- `G4ReleaseGateTest`：必需行集合、负向对照集合、“技能行必须走专用场景”的断言同步扩展。
- `ScenarioRegressionTest` 新增 5 条：火墙正向（严格消息）/ 火墙负向 / 瞬息移动正向（断言落点 `(248,208)`）/
  瞬息移动负向 / 非法 case 名。`ShadowDiffMainArgsTest` 新增 `--skill-case` 取值断言。
- `g4-capability-matrix.tsv`：`SKILL_EARTHFIRE:wizard` 与 `SKILL_SPACEMOVE:wizard` 的 evidence 列追加
  `ScenarioRegressionTest`（仍为 `implemented`，状态不变）。

### 5. 负向对照（证明判定不是空转）

| 场景 | 右侧种子 | 观测到的差异 | 判定 |
|---|---|---|---|
| firewall | 99999 | 灼烧伤害 8 vs 6，训练 3 vs 2（8 步状态差异） | FAIL ✅ |
| spacemove（lv3） | 99999 | 落点 `(248,208)` vs `(191,57)`（3 步状态差异） | FAIL ✅ |

## 边界与刻意不做

- **坐标不随角色持久化**：`PlayerState` 不含坐标，登录总是进出生点（与 W50 §6 的 `homeMapId` 落地口径一致）。
  因此瞬息移动后的 `relog` 步骤两侧都回到 `(20,20)`——这是引擎既有行为，不是本周引入的偏差，证据文档已注明。
  若要让落点跨重登保持，需要先给角色记录加坐标列，属于独立切片。
- **不新增 Delphi / 真实客户端证据**：Java↔Java 对拍只证明两台 Java 服务端在同一种子下行为一致，
  不证明与 `mir2.exe` 一致。G4 仍未签发。
- **不扩大技能覆盖**：FireBoom(23) / LightFlower(24) / SnowWind(33) 等范围技能的 shadowdiff 场景留作后继，
  本周只补已经有最新 Delphi 核验的两条。
- **红线不动**：16 捆魔咒 / 17 召唤骷髅 / 20 诱惑之光 / 30 召唤神兽，以及 34–41 扩展段。
- **不改生产行为**：没有新增环境变量、没有改动 `world` / `gate` 的运行时代码。

## 状态

- [x] Delphi 源码核验（Magic.pas:495-510 / 951-971 / 1135-1169）
- [x] `ShadowDiffMain.SkillCase` + `--skill-case` 参数化（默认 healing 不变）
- [x] `Op.fireWallScript()` / `Op.spaceMoveScript()`
- [x] 本地实跑：healing 默认/显式 PASS；firewall 正向 PASS（严格消息）、负向 FAIL；spacemove（lv3）正向 PASS、负向 FAIL
- [x] `g4-release-gate.tsv` 四行 + `G4ReleaseGateTest` 同步
- [x] `ScenarioRegressionTest`(+5) / `ShadowDiffMainArgsTest`(+1) / `g4-capability-matrix.tsv` evidence 列
- [x] 本地全量编译 + 单测：**642/642 全绿**（W50 基线 636 → +6；沙箱 ECJ 链路，日志 `w51-run1`）
- [x] CI 单测：`java-server.yml` 的 `test`（`mvn verify`）作业在 `05b557d` 上通过
- [x] 证据：`docs/g0-evidence/2026-10-10-w51-skill-shadow-firewall-spacemove.md`
- [x] CI G4 门禁：`05b557d` 上首次 run 38014544715 的 G4 作业失败（退出码 1，即有 blocking 行判 FAIL）。
  随后 `85a75a4`（相对 `05b557d` 仅改文档，代码相同）的 run 38017716504 **G4 作业通过**，其余作业也全部通过。
  - 首次失败的行仍**未定位**：沙箱无法下载日志与产物，`gh run rerun` 被拒绝。
  - 因此按偶发失败处理，**未证明根因**。如需定位，可在 GitHub 上打开 run 38014544715 的 G4 作业第 5 步，
    搜索 `[g4-gate] -> FAIL`。
  - 本地同一清单 16/16 全绿（JRE 25、Temurin 21、`LANG=C.UTF-8`），但本地用的是 ECJ 合并的 fat JAR，不是 CI 的 Maven shade JAR。
- [ ] **待办（偶发 FAIL 追踪）**：若 G4 作业再次失败，必须先读出失败行，再判断是否为时序敏感行（`duo-death-pk`、`ai-matrix` 等长耗时行）。
- [ ] **优先级冲突（待用户确认）**：主计划 `GameOfMir/doc/mir2-java-development-plan.md` 约 L51 的优先级是
  ①Netty 门禁 ②env-lint ③公会 ④技能 / NPC 脚本。本周选择技能 shadowdiff 切片时，没有先经用户确认。
  这是本周的一个未决决策，不视为已定。
- [ ] 下一候选（W52）：范围技能 shadowdiff（FireBoom 23 / LightFlower 24 / SnowWind 33），需先为每个技能确定木桩布局与脚本
