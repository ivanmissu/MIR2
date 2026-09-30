# W38 技能逐项接入：法师多格直线穿透魔法 SKILL_FIRE(9 地狱火)/SKILL_SHOOTLIGHTEN(10 疾光电影)

日期：2026-09-30
基线：W37（护身符充能门 + 施毒术/灵魂火符）

## 结论

W38 接入官方 1..33 权威目录中最后两项法师直线穿透魔法：`SKILL_FIRE = 9`（地狱火，射程 5 格）
与 `SKILL_SHOOTLIGHTEN = 10`（疾光电影，射程 8 格）。两者在 Delphi 里共用同一条穿透链：
`Magic.pas:TabStruck` 沿 `GetNextDirection` 方向逐格收集真目标（`IsProperTarget`），
`ObjBase.pas:2546 MagPassThroughMagic` 对每个目标结算一次独立掷骰的伤害；疾光电影额外对每个
命中目标过 `LA_UNDEAD 1.5x`，且该倍率是**就地写回伤害值的复利**（ObjBase.pas:2550
`nDamage := Round(nDamage * 1.5)` 写回变量后再参与下一格计算）——包括活体目标在内，逐格累积。
现有施法门禁（职业/等级/MP/冷却）全部保留，失败仍回确定性 `SM_SYSMESSAGE`；世界层只发
`WorldEvent`，网关把 `MagicFired` 映射为 `SM_MAGICFIRE`。

## 行为对拍要点（严格等价翻译，行为冻结）

- **光束终点**（`MagPassThroughMagic` 终点半径）：地狱火 5 格、疾光电影 8 格。
  `SM_MAGICFIRE` 的 param/tag 广播的是**光束穿过的最后一个格子坐标**而非点击坐标。
- **点击吸附**（`ClientSpellXY` 目标点名）：点击坐标 ±1 格内的具名怪物才被命名为
  `targetId` 并写进 `SM_MAGICFIRE` 包体；纯地面点击或点中 1 格以外的物体，包体目标恒为 0，
  且被点中的物体**不会**改变光束方向（方向只由施法者→点击格决定）。
- **命中判定按受害位置而非格子**：`PIERCING_DAMAGE` 是 `positionBound=false` 的延迟结算——
  玩家在光束落地前跑出该格，伤害仍落在受害者本人身上（与雷电术单体链同源）。
- **地形与 NPC 不挡光**：被阻挡地形（blocked cell）与 NPC 所在格不吸收光束，穿透继续，
  NPC 格不产生 `ObjectStruck`。
- **地图边界**：终点越界时光束截到最后一个图内格；方向越出图外的点击（负坐标）仍扣蓝、
  仍按**原始点击坐标**广播 `MagicFired`（不做任何坐标修正），但命中链因射线立即出图而为空，
  且不产生技能熟练度变化。
- **疾光电影×LA_UNDEAD 复利率**：`shootLightenCompoundsTheUndeadMultiplierOncePerProperTarget`
  用确定性 `FixedRandom` 复算整条伤害链 `rint(P·1.5) → rint(rint(P·1.5)·1.5) → …`，逐格逐
  目标与引擎输出精确相等，严格证明 ObjBase.pas:2550 的「伤害就地写回」怪癖被保留（不是
  每格 `P·1.5^k` 重算后取整，而是逐级取整连锁）。
- **技能熟练**：命中即训一次（1–3 点），空放不训，熟练度掉线重登保持。
- **施法门禁**：职业/等级/MP/冷却门禁先行于光束结算；未实现技能（对照：SKILL_FIREWIND=8
  抗拒火环）→ `UNSUPPORTED_SKILL`，射程外 → `OUT_OF_RANGE`，连发 → `TOO_FAST`，全部走既有
  `SM_SYSMESSAGE` 确定性回信。

## 编码事实（两拍一致）

`SM_MAGICFIRE`（地狱火 id=9 时 series = 1797 = `(5&0xff)|((7&0xff)<<8)`）：
`DefaultMessage(recog=施法者id, ident, param=光束终点.x, tag=光束终点.y,
series=(effectType&0xff)|((effect&0xff)<<8))`，`WirePacket.encodedBody` = 4 字节小端
`targetId` 的 SixBitCodec 字符串；无 ±1 格吸附时 body 解码为 0。

## 自动化证据

世界层 `WorldLinePiercingSkillTest`（9 用例，全部通过）：
`fireBeamStrikesEveryCellUpToItsReachAndBroadcastsTheBeamEnd`、
`shootLightenCompoundsTheUndeadMultiplierOncePerProperTarget`、
`beamPunchesThroughBlockedTerrainAndNpcCells`、
`mapEdgeForeshortensTheBeamToTheLastInMapCells`、
`beamSkippedAtTheEdgeStillCostsManaAndFiresWithTheRawClick`、
`beamDamageFollowsTheVictimNotTheCellItStoodOn`、
`clickedObjectFurtherThanOneCellDoesNotSteerTheBeamOrGetNamed`、
`aHitTrainsTheSkillAndThePointsSurviveRelog`、
`castGatesStillApplyBeforeTheBeam`。

网关层 `GameLinePiercingProtocolTest`（2 用例，全部通过）：
地面点击 CM_SPELL → `SM_MAGICFIRE` param/tag=光束终点坐标、series=1797、body 解码为 0；
吸附点击（具名怪物 ±1 格）→ body 解码为怪物 id。

**测试执行情况（本轮已实际运行）**：离线工具链本轮打通——为无外网沙箱编写了 JUnit 最小替身
（`/home/user/tools/jshim`，含 `@Test`/`Assertions`/`assertThrows`/`@TempDir` 与反射式
`shim.MiniRunner`），并以随 Jetty 分发的 ECJ 3.38 批编译器编译。ECJ 对
`assertEquals(Object,Object)` 与 `(int,int)` 重载存在与 javac 不一致的「歧义」误判（微复现已
确认）；解法是把对象重载改为 `assertEquals(T,T)` 泛型（改用 `Objects.equals`+跨 Number 的
BigDecimal 值比较兜底），不再重写任何仓库测试。运行结果：**world 238/238、gate 124/124、
protocol 5/5、character 3/3、auth 2/2，共 372 全通过**——W37 那批此前从未跑过的用例
（`WorldAmuletSkillTest`/`GameAmuletProtocolTest`）本次一并首次执行且全部通过。
另修复了 W38 阶段自带的 `DirectionTest#getNextDirectionKeepsTheM2ShareSnapQuirks` 期望值错误
（Delphi `ClFunc.pas` GetNextDirection 的 Y 吸附窗是非对称的：`sY > dy-1 and sY <= dy+1`
严格保留；测试的预算与引擎翻译本就一致，是测试第三处断言的期望写错）。

矩阵：`g4-capability-matrix.tsv` 中 `SKILL_FIRE`、`SKILL_SHOOTLIGHTEN`（各三职业行）由
`unimplemented` 转 `implemented + gate-game`，共 6 行。

## 边界（本轮不做）

- `SKILL_FIREWIND`(8 抗拒火环)/`SKILL_LIGHTENING`(11 雷电术，W32 已接入) 之外的法师群体魔法
  不纳入本批。
- 本批不新增 shadowdiff 场景与 `g4-release-gate.tsv` 门禁行（按既定约定，全部技能批次完成后
  统一立项）。
- G4 门禁整体仍未签署，本批只翻矩阵行。
