# W46 开发计划：`SKILL_HANGMAJINBUB`（幽灵盾）+ `SKILL_DEJIWONHO`（神圣战甲术）

延续 W37（`SKILL_FIRECHARM` 护身符充能门）、W40（`SKILL_HEALING`）、W44（`SKILL_SNOW_WIND`）、W45（`SKILL_FIREWIND`）
的按周技能迁移节奏：本周把 Magic.DB 中**两条道士增益系**（`SKILL_HANGMAJINBUB` = 14 幽灵盾、
`SKILL_DEJIWONHO` = 15 神圣战甲术）按 Delphi 源码逐分支核验后接入 Java `world` 引擎。

这两条是 `Magic.pas` 中唯一一对"只加防御属性、不造伤害"的魔法，也是第一批**给队友**加状态的技能，
因此它同时打开了三块此前没碰过的面：状态计时器（`m_dwStatusArr[STATE_*]`）、
`RecalcAbilitys` 里的状态加成、以及"训练计数落在返回值之外"这一 Delphi 特有的训练语义。

## 源码核验结论

`M2Server/Magic.pas`：

- `451`（14）与 `456`（15）是**两条并列分支**，函数体完全同构，只有传入 `MagMakeDefenceArea`
  的最后一个实参与提示串不同：

  ```pascal
  451: SKILL_HANGMAJINBUB: begin          { 456: SKILL_DEJIWONHO: begin
  452:   nPower := GetAttackPower(GetPower13(60) + LoWord(PlayObject.m_Magic[nIndex].m_nSC) * 10,
  453:       HiWord(...m_nSC) - LoWord(...m_nSC) + 1);
  454:   if PlayObject.MagMakeDefenceArea(nTargetX, nTargetY, 3, nPower, 1) > 0 then   { 459: ..., 0)
  455:     boTrain := True;
  ```

  `nPower` 的单位是**秒**，不是伤害：`GetPower13(60)`（`Magic.pas:238`）在技能 0/1/2/3 级给出
  30/40/50/60，再加 `LoWord(SC) * 10`，最后 `GetAttackPower(x, y)` 在 `[x, x + HiWord(SC)-LoWord(SC)]`
  上取一次随机（`Magic.pas:186`）。GEEM2 `Magic.DB` 两行的 `power`/`maxpower` 全为 0，
  所以随机区间宽度为 0，掷出的就是 `GetPower13(60) + minSC * 10`。

- `428`：两条与 13..19 共用同一道护身符充能门 `CheckAmulet(PlayObject, 1, 1, nAmuletIdx)`，
  `nType = 1` 即"每次施法扣 100 点 DuraMax"；无符时只发 `RM_MAGICFIREFAIL`（`boSpellFail := True`），
  MP 已在 `413` 扣掉且**不退**——与灵魂火符、施毒术（`nType = 2`，`Magic.pas:359`）逐字一致。

`M2Server/ObjBase.pas`：

- `24207 MagMakeDefenceArea(CenterX, CenterY, nRadius, nSec, btType)`：以**点击格**为中心的
  含边界 `7×7` 方形遍历（`for nX := CenterX - nRadius to CenterX + nRadius`），
  过滤条件只有 `OS_MOVINGOBJECT` + `not m_boGhost` + `IsProperFriend(..., HAM_GROUP)`，
  然后 `case btType of 1: MagDefenceUp; else DefenceUp`，并且

  ```pascal
  24248: Inc(Result);   { 注意：在 case 之外，与 DefenceUp 的 Boolean 返回值无关
  ```

  ——即使目标身上的旧状态时间更长（`DefenceUp` 返回 False），**仍然计数并因此训练技能**（见下）。

- `24255 DefenceUp` / `24309 MagDefenceUp`：
  `if m_dwStatusArr[STATE_*] < GetTickCount + nSec * 1000 then begin m_dwStatusArr[...] := ...`
  ——取 `max(剩余, 新掷)`，不会缩短正在跑的窗口；随后**无条件**刷新 `m_dwStatusArrTick := GetTickCount`、
  发 `g_sDefenceUpTime` / `g_sMagDefenceUpTime`（`M2Share.pas:3141-3142`，`String.ini:148-149`：
  `防御力增加%d秒` / `魔法防御力增加%d秒`）、`RecalcAbilitys`、`SendMsg(Self, RM_ABILITY, ...)`。

- `3418-3421`（`RecalcAbilitys` 内，紧接穿戴套装加成之后、同一次重算之内）：

  ```pascal
  3418: if m_btStatusArr[STATE_DEFENCEUP] > 0 then
  3419:   m_WAbil.AC := MakeLong(LoWord(m_WAbil.AC), HiWord(m_WAbil.AC) + 2 + m_Abil.Level div 7);
  3420: if m_btStatusArr[STATE_MAGDEFENCEUP] > 0 then
  3421:   m_WAbil.MAC := MakeLong(LoWord(m_WAbil.MAC), HiWord(m_WAbil.MAC) + 2 + m_Abil.Level div 7);
  ```

  只抬**高字**（上界），下界不动；加成量取**受术者自己**的等级。

- `4155-4190` 状态秒表（每秒 -1，`STATE_DEFENCEUP`(9) 排在 `STATE_MAGDEFENCEUP`(10) 之前）：
  归零时发 `防御力恢复正常`（`4175`）/ `魔法防御力恢复正常`（`4181`），`c_Green`/`c_Hint`，
  然后 `4243-4247` 用**一次**共用的 `RecalcAbilitys` + `RM_ABILITY` 收尾（两个状态同一秒到期也只刷一次）。

- `24091 IsProperFriend(target, HAM_GROUP)`：`HAM_GROUP` 把判定收窄成"本人或 `m_boGroup` 且
  同 `m_GroupList` 的在线玩家"——怪物、NPC、路人一律排除（`HAM_ALL` 才包含宠物与召唤物）。

- `714-716`（`DoSpell` 结尾）：`if boSpellFail then exit;` 之后才 `SendRefMsg(RM_MAGICFIRE, ...)`，
  再 `TrainSkill(Random(3) + 1)`。因此线上帧序固定为
  **SysMsg(绿) → RM_ABILITY →（RM_MAGICFIRE 广播）→ RM_MAGIC_LVEXP**。

- `Magic.pas:454` 的 `boTrain := True` 来自 `MagMakeDefenceArea(...) > 0`，而 `Result` 是"命中友军数"。
  推论（Delphi 原文如此）：**点击点距自己超过 3 格 ⇒ 方形里没有自己 ⇒ Result = 0 ⇒ 不训练**，
  但护身符与 MP 照扣、`RM_MAGICFIRE` 照发。Java 端逐字保留该行为。

## W46 已落地

`world/src/main/java/com/mir2/world/WorldEngine.java`：

1. **分发接入**：`castPlayerSpell` 把 14/15 归入护身符门控分支，新增
   `castAmuletGatedSpell`（把 W37 灵魂火符的 `CheckAmulet` 段抽出来共用，`Magic.pas:428`），
   再进 `castDefenceArea`。
2. **`castDefenceArea`**：`objectsInSquare(click, 3)` 复刻 `24207` 的含边界方形遍历；
   过滤 `not ghost` + `isDefenceAreaFriend`（`24091` 的 `HAM_GROUP` 语义）；
   `affected` 在 `applyDefenceUp` 的返回值之外自增（`24248`），`affected > 0` 才 `boTrain`。
3. **`applyDefenceUp`**：`max(旧截止, now + 秒*1000)`，随后提示串（秒数）+ `recalculateAbilities`
   + `RM_ABILITY`，顺序与 `24255/24309` 一致。
4. **`recalculateAbilities`**：新增 `DEFENCE_UP_BASE_BONUS = 2` / `DEFENCE_UP_LEVEL_DIVISOR = 7`，
   只加 `maxAc` / `maxMac` 上界；同时记录本次实际加了多少（`appliedDefenceUpBonus` /
   `appliedMagDefenceUpBonus`）。
5. **`expireSkillBuffs`**：按 9→10 的顺序清两个计时器，各自发提示串，最后共用一次
   `recalculateAbilities` + `RM_ABILITY`。
6. **`Player.rebase` 扣回状态加成**：`setAbility` 会把工作属性 rebase 成裸身属性再存档，
   而 rebase 原本只扣穿戴加成；不扣回状态加成时，一次施法就会把 `+5 AC` 写进
   `PlayerState`，并在下一次施法时叠加成 `+10`（由 `theBuffNeverLeaksIntoThePersistedCharacterRecord`
   锁定）。状态本身仍是瞬态：存档只存裸身属性，重登即清空。

未改动：`gate`（复用 `CM_SPELL` / `SM_MAGICFIRE` / `SM_ABILITY` / `SM_SYSMESSAGE` /
`SM_MAGIC_LVEXP` / `SM_DURACHANGE` 既有编码）、`protocol`、`persistence`。
**没有新增 `g_Config` 开关**（Delphi 侧这两条也没有），因此没有新增 `MIR2_*` 环境变量，
`deployment.md` 无需改动。

## 验收覆盖

`world/src/test/java/com/mir2/world/WorldDefenceBuffTest.java`（12 条）：

| 用例 | 锁定的 Delphi 行为 |
| --- | --- |
| `holyArmourLiftsTheUpperAcBoundOnly` | `3418` 只抬 AC 上界（+2 + Level div 7），下界、MAC、SC 全不动 |
| `ghostShieldLiftsTheUpperMacBoundOnly` | `3420` 同上，作用在 MAC 上界 |
| `theSquareIsInclusiveAtThreeCellsAndEmptyAtFour` | `24207` 的含边界 7×7（切比雪夫，对角格算在内）；且加成取**受术者**等级（20 级战士 +4，不是施法者的 +5） |
| `strangersAndMonstersAreNeverBuffed` | `24091` 的 `HAM_GROUP` 收窄：路人、怪物都在方形内却拿不到状态 |
| `aShorterRecastKeepsTheLongerRunningWindow` | `24255` 取 `max(剩余, 新掷)`：短窗口既不会缩短也不会延长正在跑的窗口 |
| `theBuffNeverLeaksIntoThePersistedCharacterRecord` | `Player.rebase` 必须扣回状态加成；否则 `+5 AC` 漏进 `PlayerState` 并在下次施法叠成 `+10` |
| `theWindowExpiresWithItsHintAndOneAbilityRefresh` | `4175` 的 `防御力恢复正常` + 一次 `RecalcAbilitys`/`RM_ABILITY` |
| `bothWindowsExpireOnTheirOwnTickButShareOneAbilityRefresh` | `4155` 的 9→10 顺序：两个窗口各自在自己的 tick 到期，但共用一次刷新 |
| `noCharmMeansNoBuffButTheManaIsAlreadySpent` | `428` 的 `RM_MAGICFIREFAIL`：无符时无状态、无训练，MP 不退 |
| `thePerRowNeedLevelAndJobGatesStillApply` | 职业门与 NeedL 门在两条新技能上照旧生效 |
| `anEmptySquareStillBurnsTheCharmWithoutTraining` | `Magic.pas:454` 的 `Result > 0` 门槛：点远（方形内没有自己）⇒ 扣符扣蓝、发 `RM_MAGICFIRE`，但不训练 |
| `theRaisedAcAbsorbsMeleeDamage` | 抬高的 AC 上界真的进了 `monsterAttack` 的减伤掷骰（准确 99 排除敏捷闪避） |

`gate/src/test/java/com/mir2/gate/GameDefenceBuffProtocolTest.java`（2 条）：真实 `CM_SPELL`
报文 → `SM_MAGICFIRE`（`Series = 9 | (12 << 8) = 3081`，点击格在 param/tag）、
一条 `SM_ABILITY`（解包 50 字节 `TAbility`，校验 AC 高字 = 5、低字 = 0）、
`SM_SYSMESSAGE` = `防御力增加50秒`、`SM_MAGIC_LVEXP`（recog = 15）、`SM_DURACHANGE`
（recog = 剩余 Dura，param = 槽位）；以及无护身符时只有 `SM_MAGICFIRE_FAIL`。

矩阵：`g4-capability-matrix.tsv` 中 `SKILL_HANGMAJINBUB:taoist` / `SKILL_DEJIWONHO:taoist`
两行改为 `implemented`（证据 `WorldDefenceBuffTest,GameDefenceBuffProtocolTest`）；
`warrior` / `wizard` 四行按 Magic.DB `learnable = no` 保持 `unimplemented`。

## 已执行的验证

- `WorldDefenceBuffTest` 12 条 + `GameDefenceBuffProtocolTest` 2 条全部通过。
- 全量回归 **568 tests, 0 failed**（W45 收尾时为 554 条，本周新增 14 条）。
- 本环境无 Maven（`mvn` 不在 PATH，Maven Central 不可达），编译与测试用 ECJ + junit-platform
  console launcher 直跑；CI（`.github/workflows/java-server-dist.yml`）仍是权威门禁。
- 这些是 Java↔Java 断言，**不构成 G4 已签发**：没有对真实客户端/DELPHI 服务端做过报文 diff。

## 已知边界与下一步

- `IsProperFriend` 直接复用既有 `isHostile` 子集：攻击模式（和平/组队/全体）与召唤物归属
  （`m_Master` 链，tamed slave 本可成为 friend）尚未建模，与 W44/W45 的已知边界一致。
- `24248` 的"剩余时间更长也计数并训练"是 Delphi 原文行为，不是 bug，也不是优化空间。
- 状态瞬态：重登清空（Delphi 同样只在 `m_dwStatusArr` 内存中）。
- 未接的相邻技能：`SKILL_REVELATION`(28) 需要客户端 HP 显示态；`SKILL_HIDING`/`MASSHIDING`(18/19)
  需要 `MagMakePrivateTransparent`(`Magic.pas:734`) / `MagMakeGroupTransparent`(`1286`)；
  `SKILL_FIREBANG`(22) 需要持久地面场对象；`SKILL_TELEPORT`(21) 走 `MapRandomMove`；
  `SKILL_BANISH`(32) 依赖 `g_Config.nMagTurnUndeadLevel`。
- `SKILL_ENERGYREPULSOR`(37) 虽与 W45 的 `MagPushArround` 共用函数，但 Magic.DB 职业归属未核实，
  **不**因共用辅助函数而开。
