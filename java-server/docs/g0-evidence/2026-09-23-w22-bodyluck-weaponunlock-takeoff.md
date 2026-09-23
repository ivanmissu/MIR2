# W22 实跑证据：补齐 W20 遗留小项（幸运值 + 武器锁 + 禁止取下列表）

- 日期：2026-09-23
- 分支：`arena/01a0cd34-mir2`（基线 `229d4c0`，W21 PR #39 合入 master 后）
- 链路：沙箱内 ECJ（`ecj.jar`）+ jdk4py + JUnit 5.10.2 本地编译跑测（`~/tools/build-mir2.sh` / `~/tools/test-mir2.sh`，含 sqlite-jdbc + slf4j-api 运行期 classpath）

## 结论

| 项 | 结果 |
| --- | --- |
| 全量单测 | **TOTAL=307 PASS=307 FAIL=0 SKIP=0** |
| 基线（W21 交付时） | TOTAL=283 PASS=283 |
| 本轮净增 | **+24**（`BodyLuckTest` 8 + `WeaponPointsTest` 4 + `DisableTakeOffListTest` 6 + `WorldDisableTakeOffTest` 2 + `ClientItemCodecTest` +2 + `SqliteStoreTest` +1，另一处为既有用例统计口径） |
| 主源码编译 | `compile OK`（107 main + 61 test，无错误） |

> 说明：`BotSwarmEmbeddedTest.singleBotLogsInWalksFightsAndRelogs` 为已知墙钟计时抖动型偶发失败（"the bot must move at least once"），与本轮改动无关；连跑复现即恢复，最终一轮 307/307 全绿。

## 交付内容（忠实复刻 Delphi 语义）

### ① 幸运值 `AddBodyLuck`（ObjBase.pas:2374）
- `world/BodyLuck`：`m_dBodyLuck` 累加器 + 派生等级 `Trunc(value/BODYLUCKUNIT)` 钳制 `[-10,5]`（`BODYLUCKUNIT`=5000，M2Share.pas:94）。
- **非对称守卫**照搬：正增量仅在 `value < 5*UNIT` 生效、负增量仅在 `value > -5*UNIT` 生效，单次可略越界但不复利。
- 接线：经验 `AddBodyLuck(dwExp*0.002)`、升级 `AddBodyLuck(100)`、死亡自罚 `-Level*5`、谋杀恒 `AddBodyLuck(-500)`（ObjBase.pas:20938）。
- 等级目前是纯记账位（Delphi 仅 NPC 升级公式 ObjNpc.pas:1309 与 `nCHECKLUCKYPOINT` ObjNpc.pas:7152 读取，均在脚本引擎红线内），忠实追踪并持久化以备红线解除。

### ② 武器锁 `MakeWeaponUnlock`（ObjBase.pas:2393）
- `world/WeaponPoints`：武器 per-instance `btValue[3]`（幸运）/`btValue[4]`（诅咒）两字节。
- 先耗幸运点（`btValue[3] > 0` 时 `Dec`），耗尽后累积诅咒点（`btValue[4] < 10` 时 `Inc`），**已满仍发消息不变值**。
- **不入服务端战斗**：`RecalcAbilitys` 读 base catalog（`GetStdItem`）而非实例，因此仅经 `GetItemAddValue`（ItmUnit.pas:124-125）折进客户端 `TClientItem`——武器 `AC-low += 幸运`、`MAC-low += 诅咒`，非武器不折、高字保持不变。`ClientItemCodecTest` 两个新用例钉死折叠与非武器豁免。

### ③ 禁止取下列表 `InDisableTakeOffList`（M2Share.pas:4642 / `LoadDisableTakeOffList` M2Share.pas:4578）
- `world/DisableTakeOffList`：按物品名（小写）匹配（引擎以名为稳定身份，Delphi 以 StdItems.DB 索引），`parse` 复刻 `GetValidStr3` 分隔符链（空格/`/`/`,`/TAB），`;` 与空行跳过。
- 门禁 `ClientTakeOffItems`（ObjBase.pas:17144/17259）与 `DropUseItems` 跳槽（ObjBase.pas:15532）：`isLockedInPlace` 由 static 改实例方法并叠加清单判定。
- `Mir2Server` 经 `MIR2_DISABLE_TAKEOFF_FILE`（GBK）装载 → `setDisableTakeOffList`（仿 `addRoute` 的 submit 模式推入世界线程）。
- `WorldDisableTakeOffTest`：装载后取下被拒（`CANNOT_TAKE_OFF`）、装载空清单后又可取下（`g_DisableTakeOffList.Clear` 语义）。

### ④ 战斗幸运暴击 `GetAttackPower`（ObjBase.pas:2416）
- 接入玩家近战：luck>0 有 `1/(10-min(9,luck))` 命中最大值、luck<0 有 `1/(10-max(0,-luck))` 压到最小值；玩家 luck=`bonus.luck() - bonus.unLuck()`（`EquipmentBonus` 已按穿戴集累加）。
- **零回退保护**：`luck == 0` 分支直接委托旧 `randomBetween`（含 `min == max` 不抽签短路），逐位保持既有确定性抽样顺序；怪物攻击仍走无 luck 重载。

### ⑤ 持久化（`persistence/SqliteStore`）
- `character_state.body_luck REAL DEFAULT 0`、`character_equipment.weapon_luck/weapon_curse INTEGER DEFAULT 0`，均经 `ensureColumn` 原位加列（旧库默认 0）。
- `PlayerState` 增第 7 分量（`bodyLuck`）、`BackpackItem` 增第 5 分量（`weaponPoints`，4-arg 兼容 + null→NONE），全链路 thread through。
- `WorldRandom.Stream` 增第 7 条流 `WEAPON_UNLOCK`（谋杀武器锁 roll 不扰动伤害序列）。
- `SqliteStoreTest.bodyLuckAndWeaponPointsRoundTripThroughTheNewColumns`：`bodyLuck=12345.5` 与武器 `luck=4/curse=3` 往返，背包 item 保持 `WeaponPoints.NONE`。

## 红线核对

未触碰技能/魔法、NPC 脚本引擎（Market_Def）与 57 怪行为扩展。所选三项均在红线外。

## 复现

```bash
~/tools/build-mir2.sh    # compile OK（107 main + 61 test）
~/tools/test-mir2.sh     # TOTAL=307 PASS=307
# 或在有 Maven Central 的环境
mvn -f java-server/pom.xml verify
```
