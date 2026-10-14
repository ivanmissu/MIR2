# W54 开发计划：道士增益技能批次收口 —— 幽灵盾 / 神圣战甲术 进入影子对拍

W53 在「下一候选」中明确了 W54 的首选方向：**14 幽灵盾 `SKILL_HANGMAJINBUB` 与
15 神圣战甲术 `SKILL_DEJIWONHO`**（W46 已实现护身符消耗与 MAC/AC 增益）接入
`--skill-case` 确定性对拍。本周兑现该切片，并将 `g4-release-gate.tsv` 门禁清单从
26 行扩充至 30 行。

这两条是 `Magic.pas` 1..33 段中唯一不造伤害、不命中敌人、只改自身防御字的技能，因此本周的
真正难点不是技能本身，而是**可观测性**：没有 `SM_STRUCK`、没有死亡帧、没有伤害数字，
对拍的全部证据落在三件此前未进快照/未进脚本的事上 —— 状态窗口的升/降（`SM_ABILITY` 里的
AC/MAC 字）、护身符的逐发耐久消耗（`SM_DURACHANGE`）、以及持续秒数的抽签（到期时刻）。
本周同时补齐这三处观测面。

## 一、源码核验结论（Delphi，`GameOfMir/M2Server/Magic.pas` & `ObjBase.pas`）

- `Magic.pas:451/456`：两分支同构（W46 已逐字核验），仅 `MagMakeDefenceArea(nTargetX, nTargetY,
  3, nPower, btType)` 的尾参不同：14 → `btType=1`（`MagDefenceUp` / `STATE_MAGDEFENCEUP`，
  ObjBase.pas:24309），15 → `btType=0`（`DefenceUp` / `STATE_DEFENCEUP`，ObjBase.pas:24255）。
  `nPower` 单位是**秒**：`GetAttackPower(GetPower13(60) + LoWord(SC)*10, HiWord(SC)-LoWord(SC)+1)`。
- `ObjBase.pas:24248`：`Inc(Result)` 在 `DefenceUp` 返回值之外 —— 受术者已有更长窗口时重施
  **照样计数、照常训练**，且 `DefenceUp` 无条件刷新提示/`RecalcAbilitys`/`RM_ABILITY`；
  截止时刻取 `max(剩余, 新掷)`（24255/24309）。**当前窗口在跑时重施不会缩短它，但会重置
  秒表基准**——脚本据此设计联合窗口截止：从 cast #2 起算，联合截止 = `max(20·R1 − 29, 20·R2)`
  tick（29 是 cast #1 与 cast #2 之间泵过的 tick 数：1 + 28）。
- `ObjBase.pas:3418-3421`（`RecalcAbilitys`）：只抬防御字的高字（上界），下界不动；加成
  `2 + Level div 7` 取**受术者自己**的等级。状态字归零清零（4155-4190），到期时发
  `防御力恢复正常`（4175）/ `魔法防御力恢复正常`（4181），最后共用一次 `RecalcAbilitys` +
  `RM_ABILITY`（4243-4247）把防御字刷回裸身值。
- 护身符门 `Magic.pas:428` / `CheckAmulet`（81）：`U_ARMRINGL`、`U_BUJUK` 顺序扫描
  StdMode 25 家族，Shape 5（护身符）或 Shape ≤ 2（药粉）；无符时 `boSpellFail` 保持 True，
  只有 `RM_MAGICFIREFAIL`，**已扣的 MP 不退**。`UseAmulet`（134）每发烧 100 耐久
  （= 一次充能），经 `RM_DURACHANGE` 上行。
- 状态是瞬态：Delphi 只存 `m_dwStatusArr` 内存值，存档只存裸身属性；重登即清空
  （W46 `theBuffNeverLeaksIntoThePersistedCharacterRecord` 已锁服务端侧）。脚本用 cast #4
  让重登时刻窗口仍在跑，把这一条钉到线上：重登快照防御字必须回到裸身值，而训练点数、
  MP、护身符剩余耐久都必须与重登前一致。

## 二、可观测性缺口与本周补法（先于场景本身）

引擎内没有一帧带着持续秒数：秒数只藏在 `防御力增加%d秒` 系统消息的**正文**里，而
shadowdiff 的 `ShadowSession` 消息集合只保留 ident 名，STATE 从不看正文。因此本周补三处：

1. **`StateSnapshot` 新增 `ac` / `mac` 两个字段**（带 `describe()` 渲染 `ac=0-5 mac=2-10`）：
   取最近一帧 `SM_ABILITY` 的打包防御字。W40/W46 的 `RM_ABILITY` 刷新从此成为一等公民
   对拍证据；旧快照构造器经兼容重载默认 -1，`describe()` 在 -1 时不渲染，既有报告逐字节不变。
   `SM_ABILITY` 在 enter（ObjBase.pas:16572 登录刷新）即到达，因此裸身防御字从入场那一刻
   就是可观测的：道士 22 级 `mac=2-5`（`MakeLong(rint(22/6) div 2, rint(22/6)+1)` = 2,5）、
   `ac=0-0`。
2. **`ShadowSession` 追踪 `SM_DURACHANGE`**：`worn` 条目按帧更新耐久。**已知偏差（记录在案，
   本周不动引擎）**：Delphi `RM_DURACHANGE` → `SM_DURACHANGE` 是 `Recog=槽位, Param=耐久,
   Tag/Series=耐久上限低/高字`（ObjBase.pas:5837-5843），而 Java gate 自 W46 起编码为
   `Recog=耐久, Param=槽位, Tag/Series=耐久上限`（`GameDefenceBuffProtocolTest` 已把 Java
   侧钉死）。Java↔Java 对拍无法仲裁哪侧是对的——真实客户端对拍时这是第一个要核对的字段级
   候选；harness 按 Java 编码解码，两侧自洽。
3. **细粒度到期阶梯**：不用单发 52 秒泵完，而是把候选截止时刻逐个关进各自的观察桶：
   cast #2 后累计 1/996/1001/1011/1020/1030/1040/1045 tick，联合截止集合
   `{1000, 1011, 1020, 1040}` 每个都有专属桶；cast #3（单次抽签）后累计 1/996/1001/1020/1040/1041，
   覆盖 `{1000, 1020, 1040}`。任何持续抽签分叉都必然在其中一个桶首次表现为防御字差异。

## 三、本周改动范围

### 1. `SkillCase` 与脚本

- `SkillCase.GHOSTSHIELD`：道士 22 级（= `NeedL1`），预种 `SKILL_HANGMAJINBUB`(14) lv0、
  背包 1 枚护身符（种子行 StdItemsDb 104 号，DuraMax 10000），0 怪，`Op.ghostShieldScript()`。
- `SkillCase.HOLYARMOUR`：道士 25 级（= `NeedL1`），预种 `SKILL_DEJIWONHO`(15) lv0，
  背包同样 1 枚护身符，`Op.holyArmourScript()` —— 与幽灵盾脚本**逐 op 同构**（29 个 op，
  仅 spell id 不同），使两行对拍报告的任何差异都只能归因于 `btType` 选择的那条臂。
- 种子带背包需要 `SkillCase` 增加 `bagItems` 维度并接入 `SeededAccount`；此前 8 个 case
  一律空背包，构造器与行为逐字节不变（`runSkillScenario` 唯一调用点已改传
  `skillCase.bagItems()`）。
- 脚本阶段（两案相同）：bag → **无符首发**（`spell 1X 20 20`：`+GOOD` + MP −4 +
  `SM_MAGICFIRE_FAIL`，无提示/无训练/无磨损）→ tick 29（28 + 1：过 1350ms 间隔 + 40ms
  行延迟）→ `takeon 护身符` → cast #1（烧 100 耐久、训 +抽签、防御字升）→ 29 tick →
  cast #2（窗口内重施：`max` quirk + 训练）→ 8 段阶梯泵至联合截止之后 → cast #3 →
  单抽签阶梯 → cast #4（窗口在跑）→ `relog`（防御字回裸身、耐久/训练持久）→ bag。

### 2. 门禁与测试扩充

- `g4-release-gate.tsv` 新增 4 行（正负各 2 行，26 → 30 行）：
  `shadowdiff-skills-ghostshield(-negative)`、`shadowdiff-skills-holyarmour(-negative)`。
- `G4ReleaseGateTest`：必需行 26 → 30、负向对照 9 → 11；另补 `skillCaseRowsDriveTheirOwnCase`
  把每个 skill-case 行的 `--skill-case` 值钉死、正向 W54 行钉 `--strict-messages`。
- `ScenarioRegressionTest`：新增 4 个回归（2 正向严格消息 + 2 负向）。
- `OpTest` / `ShadowDiffMainArgsTest` / `ShadowDiffTest`：脚本同构与阶梯序列、case 种子
  （等级/背包/木桩种类）、以及 `ac:`/`mac:` 防御字漂移必为 STATE 失败的 differ 单元测试。
- `g4-capability-matrix.tsv`：`SKILL_HANGMAJINBUB:taoist` 与 `SKILL_DEJIWONHO:taoist`
  两行 evidence 追加 `ScenarioRegressionTest`，notes 追加 W54 对拍说明。

## 四、实测锚点（种子 20260922，MANUAL 时钟）

| 量 | 幽灵盾（22 级） | 神圣战甲术（25 级） |
|---|---|---|
| maxMp | 146 | 185 |
| 每发 MP | 4（`Round(15/4 × 1) + 0`） | 4 |
| 裸身 → 增益防御字 | mac 2-5 → **2-10**，ac 恒 0-0 | ac 0-0 → **0-5**，mac 恒 2-5 |
| 持续抽签 R | ∈ {50, 51, 52} 秒（`GetPower13(60)=30` + SC 下界 2×10， spread 2） | 同左（SC 同为 2-3） |
| 联合窗口实测截止（自 cast #2） | 1040 tick（R2=52 压过 R1=51 的残值 991） | 1040 |
| cast #3 实测截止 | 1001 tick 桶内过期（R3=50） | 1001 |
| 训练序列 | cast #1..#4：3 → 4 → 5 → 6（SKILL_TRAIN 流） | 同左 |
| 护身符耐久 | 每次 −100：9900/9800/9700/9600，重登保持 9600/10000 | 同左 |
| 重登防御字 | 回 2-5（瞬态清空） | 回 0-0 |

## 五、负向对照验证（右侧 `--right-seed 99999` 实测）

| 场景 | 观测到的差异 | 判定 |
|---|---|---|
| `ghostshield` | cast #1 训练抽签先分叉（3 vs 2）；持续抽签随后分叉：联合截止右侧 1011（R1=52 残值）、左侧 1040，`tick 9` 桶（累计 1011）首次出现 `mac: 2-10 vs 2-5` 状态差异，全程 23 步状态差异 | FAIL (exit 1) ✅ |
| `holyarmour` | 同构：训练 3 vs 2 先行，`tick 9` 桶首次 `ac: 0-5 vs 0-0`，23 步状态差异 | FAIL (exit 1) ✅ |

负向差异**首先**由持续抽签在防御字上拉开物理偏离——这正是本周补 AC/MAC 观测面的目的：
换种子若只动训练点数，技能场景早在 W41 就已证明能抓到；本周证明新观测面本身是活的。

## 六、验收与状态

- [x] Delphi 源码复核（Magic.pas:81/134/428/451-460；ObjBase.pas:24207/24248/24255/24309/
      3418-3421/4155-4190/4243-4247）——实现侧 W46 已落地，本周只读核验脚本假设
- [x] `StateSnapshot` AC/MAC 字段 + `ShadowSession` SM_DURACHANGE 追踪（兼容重载保旧构造器）
- [x] `Op.ghostShieldScript()` 与 `Op.holyArmourScript()` 同构脚本与到期阶梯
- [x] `ShadowDiffMain.SkillCase` 扩展 `GHOSTSHIELD` / `HOLYARMOUR` 与 `bagItems` 种子维度
- [x] `ScenarioRegressionTest` 4 个新用例 + 全量 **669 测试全绿**（W53 末 659 + 本周 10）
- [x] `G4ReleaseGateTest` / `G4CapabilityMatrixTest` 全绿
- [x] `g4-release-gate.tsv` 30 行扩充完成；`g4-capability-matrix.tsv` 两行已更新
- [x] 四条 W54 门禁行已由 `ScenarioRegressionTest` 以清单同参数实跑（正向严格消息 PASS、
      负向 exit 1）；沙箱无 Maven，fat JAR 版 `g4-release-gate.sh` 全量跑批留待 CI

## 下一候选（W55）

- **首选**：`--skill-case` 扩展至 **28 心灵启示 `SKILL_SHOWHP`**（W48 已实现 HP 显示态）：
  对静止木桩/自身施法，`Random(6) <= btLevel+3` 显示门、`GetPower13(GetRPow(SC)*2+30)` 秒数
  窗口与到期关条都可走 MANUAL 时钟对拍；观测面需要把「目标 HP 条开/关」带进快照
  （`SM_HEALTHSPELLCHANGED` / 状态字），是本周 AC/MAC 观测面之后的下一块。
- 备选：道士召唤物机制（17 召唤骷髅）前期设计 —— 需要宠物对象模型，是跨周切片，不宜与
  对拍行混在同一周。
- 红线不变：16/17/20/30 与 34–41 扩展段技能、57 怪行为扩展、NPC 脚本引擎继续维持红线；
  `SM_DURACHANGE` 词序偏差在真实客户端对拍前复核（见 §二.2）。
