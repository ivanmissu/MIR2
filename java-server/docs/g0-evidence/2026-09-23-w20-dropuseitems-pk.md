# W20 实跑证据：DropUseItems（死亡掉装备）+ PK 等级模型 + 红名全掉

- 日期：2026-09-23
- 分支：`arena/01a0cc07-mir2`（基线 `40b2528`，W19 交付后）
- 链路：沙箱内 ECJ（`ecj.jar`）+ jdk4py 21.0.8.2 + JUnit 5.10.2 本地编译跑测（与 W12 起的 Runbook 一致）

## 结论

| 项 | 结果 |
| --- | --- |
| 全量单测 | **TOTAL=276 PASS=276 FAIL=0 SKIP=0** |
| 基线（W19 交付时） | TOTAL=259 PASS=259 |
| 本轮净增 | **+17**，全部来自新增 `WorldPkAndEquipmentDropTest` |
| 主源码编译 | `BUILD_OK`（无告警级错误） |

## 新增用例清单（`world/src/test/java/com/mir2/world/WorldPkAndEquipmentDropTest.java`）

PK 模型：

1. `pkLevelIsTheCounterDividedByOneHundredAndDrivesTheNameColour` —— `PKLevel = m_nPkPoint div 100` 边界（0/99/100/200/399）与 255/$FB/$F9/$2F 四色映射，含「红名交手仍是红名」。
2. `killingAnotherPlayerCostsOnePkLevelAndRepaintsTheMurderersName` —— 谋杀 +100 点、双方 GBK 文案、`SM_CHANGENAMECOLOR` 广播给观察者。**实跑发现**：线上那一条广播的颜色字节是 $2F 而非 $FB——因为同一场战斗也设了 `m_boPKFlag`，而 `GetCharColor` 让交手色在 `PKLevel < 2` 时压过等级色（ObjBase.pas:19024）。断言按原版行为固化。
3. `aSecondMurderMakesTheKillerRedAndTheVictimOfAFlaggedPlayerIsALawfulKill` —— 2→3 级仍重绘，且红名忽略交手色。
4. `killingAFlaggedPlayerIsLawfulAndCostsNoPkPoints` —— `IsGoodKilling`：只发「[--你受到正当规则保护--]」，不计点、不发谋杀文案。
5. `tradingBlowsSetsThePkFlagForSixtySecondsAndThenClearsIt` —— `SetPKFlag` 只染**攻击方**；59 秒未清、61 秒由 `CheckPKStatus` 清除并重绘回 255。
6. `pkPointsBleedOffOnePerTwoMinutesAndTheColourFollowsTheLevel` —— 2 分钟衰减 1 点，1→0 级重绘。
7. `aDeepRedNameDroppingFromLevelThreeToTwoStaysSilent` —— **广播 quirk**：`DecPKPoint` 的 `(nOldLevel > 0) and (nOldLevel <= 2)` 让深红 3→2 全程不重绘。
8. `thePkPointCounterSurvivesALogoutAndLogin` —— `HumData.nPKPOINT` 随角色档往返；`m_boPKFlag` 不落库。

DropUseItems：

9. `aMonsterKillScattersWornGearWithinTwoCellsAndReportsTheLoss` —— `boKillByMonstDropUseItem`=True：头盔落在 2 格内、`SM_DELITEMS` 带名带 MakeIndex、槽位清空并补发 `SM_SENDUSEITEMS`。
10. `aPlayerKillLeavesTheVictimsGearAlone` —— `boKillByHumanDropUseItem`=False：PK 杀不掉装备。
11. `reservedBitEightDestroysTheItemOutrightAndReportsItWithoutAName` —— `Reserved and 8`：**空名**条目（`DelList.AddObject('', MakeIndex)`）、物品不落地、未中签的普通槽保持穿戴。
12. `reservedBitTenDropsTheItemOnTheFloorWhileTheWearerKeepsIt` —— `Reserved and 10 <> 0`（实际可达的只有 bit 2，bit 8 已被趟一吃掉）：地上有一件、身上还穿着、且不进删除列表。复制 quirk 照搬。
13. `theProtectionRingsGateTheTwoDeathPenaltiesIndependently` —— 170 全免 / 171 只保包裹 / 172 只保装备 / 0 耐久不生效。
14. `aWornAngryRingKeepsBothTheBagAndTheGear` —— 端到端：护身戒指下死亡，包裹 6 件与装备全保。

红名与地图旗：

15. `aRedNameDropsItsEntireBagInsteadOfOneThird` —— `boDieRedScatterBagAll`：`PKLevel >= 2` 时 10 件全掉（对照 1/3 路径）。
16. `aNoDropItemMapSuppressesTheBagScatterButNotTheEquipmentDrop` —— **原版语义**：`NODROPITEM` 图保包裹不保装备（`DropUseItems` 无地图门禁，`Die` 自身的 `(not m_boNoItem) or (not Flag.boNODROPITEM)` 是个满足不了的 OR）。
17. `mapInfoParsesNoDropItemSeparatelyFromNoThrowItem` —— `MapInfo.txt` 的 `NODROPITEM` 与 `NOTHROWITEM` 是两个独立旗。

## 确定性说明

装备掉落的 1-in-30 roll 走新增的 `WorldRandom.Stream.DEATH_DROP_USE_ITEM` 独立流（W17 拆流机制），
因此上述用例用 `WorldRandom.seeded(seed)` 钉住首抽：种子 3 首抽为 0（必掉），种子 1 首抽为 4、次抽 17
（必不掉），无需依赖循环次数。这也意味着装备掉落 roll 不再扰动包裹掉落序列——正是 W17 拆流的目的。

## 复现

```bash
# 沙箱链路（Runbook 见计划书「工具链 Runbook」小节）
/tmp/build.sh && /tmp/test.sh
# 或在有 Maven Central 的环境
mvn -f java-server/pom.xml verify
```
