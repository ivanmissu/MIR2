# W45 开发计划：`SKILL_FIREWIND`（抗拒火环）

**状态：** 计划已完成源码核验，尚未实现。W44 的下一技能候选落地为本计划：目标是把法师的**纯位移**技能
抗拒火环接入既有魔法门禁；不得把 Java 单测描述为 Delphi/真实客户端对拍证据，G4 仍未签发。

与 W42–W44 的三发范围伤害不同，本切片**不掷功率、不造成伤害**：整条分支只是把身边一圈对象推开，
因此它的验收重心从 `RM_MAGSTRUCK` 转移到 `RM_PUSH`（Java `WorldEvent.ObjectPushed` → `SM_BACKSTEP`）。

## 源码核验结论

- `Magic.pas:384-386`：`SKILL_FIREWIND{8}` 分支只有一行
  `if MagPushArround(PlayObject, UserMagic.btLevel) > 0 then boTrain:=True;`。它既不读
  `nTargetX/nTargetY`，也不读 `TargeTBaseObject`，更不调用 `GetAttackPower` —— 与
  `SKILL_LIGHTFLOWER`（24）一样是“空地也能放”的技能，但雷光至少还取施法者自身格作圆心，
  抗拒火环连自己的坐标都不读（中心来自遍历时的逐对象比较）。
- `Magic.pas:146-171` 的 `MagPushArround` 遍历 `PlayObject.m_VisibleActors`（施法者的可见对象表，
  不是 `GetMapBaseObjects`），逐个对象按下列顺序过滤：
  `abs(dx) <= 1 and abs(dy) <= 1`（以施法者为中心的含边界 3×3）→ 未死亡且非自身 →
  `PlayObject.m_Abil.Level > BaseObject.m_Abil.Level`（严格大于）且 `not m_boStickMode` →
  `Random(20) < 6 + nPushLevel * 3 + levelgap`（`levelgap` 为双方等级差）→ `IsProperTarget`。
  通过后 `push := 1 + _MAX(0, nPushLevel - 1) + Random(2)`，
  `nDir := GetNextDirection(施法者 → 对象)`，`BaseObject.CharPushed(nDir, push)`，`Inc(Result)`。
- `ObjBase.pas:2504-2532` 的 `CharPushed`：先把朝向设为 `nDir`、算出 `nBackDir := GetBackDir(nDir)`，
  然后循环 `nPushCount` 次 `GetFrontPosition` + `CanWalk(..., False)` + `MoveToMovingObject`；
  每成功一步发一次 `SendRefMsg(RM_PUSH, nBackDir, m_nCurrX, m_nCurrY, 0, '')`，并对
  `m_btRaceServer >= RC_ANIMAL` 的对象把 `m_dwWalkTick` 追加 800ms。结束后朝向回落为 `nBackDir`，
  一步都没推动时恢复 `olddir`。**被墙或对象挡住就地 Break，剩余步数作废。**
- `Magic.pas:715-721`：`boSpellFire` 为真时尾部广播 `RM_MAGICFIRE`，携带
  `MakeWord(effectType, effect)`、点击坐标与对象 id。抗拒火环没有把 `boSpellFire` 置假（只有
  `SKILL_SPACEMOVE` 那类会置假），所以它会正常下发施法帧；坐标仍是
  `ClientSpellXY` 的 `CretInNearXY`（ObjBase.pas:8964+）吸附后的点击点。
- `Magic.pas:676` 的 `SKILL_ENERGYREPULSOR`（37，气功波）调用同一个 `MagPushArround`；GEEM2 基线
  没有同编号行、Job 归属待校准，本切片**不**顺带开放 37。
- `Magic.DB` 第 8 行（`world/src/main/resources/db/MagicDb.tsv:14`，已用 `MagicCatalog.defaults()`
  复核）：`job=1`（法师，战士/道士不可学习）、`effectType=4/effect=6`（`Series=4|6<<8=1540`）、
  `spell=8/defSpell=0` → 零级 `GetSpellPoint` = **2**、`NeedL1=12`、`delay=30`、`power/maxPower=1`
  （本技能不掷功率，这两个字段不被读取）。

## 实现范围

1. 新增 `SKILL_FIREWIND = 8` 常量与 `isPushArroundSkill(int)` 判定；把它接进
   `castPlayerSpell` 的三处既有门禁：职业/等级/距离/法力/冷却、**跳过 `validSpellTarget` 单体目标校验**
   （与地狱雷光同组）、并单独分派到 `castPushArround`，不再落到单体冲击链。
2. 实现 `castPushArround`：以 `visibleIds(player.map, player.position, player.id)` 取施法者可见对象
   （对应 Delphi `m_VisibleActors`，不是地图方形收集），按源码顺序逐项过滤
   ——含边界 3×3、存活、非自身、`caster.level > target.level`、`isProperTarget`、等级/技能等级随机门；
   通过后按 `GetNextDirection` 方向循环调用既有 `charPushed`，最多
   `1 + max(0, level - 1) + Random(2)` 步，返回 0 即停。**不产生任何伤害、不做 MAC/魔法盾结算。**
3. 两处 `Random` 都需要新的独立流枚举（`WorldRandom.Stream`），沿用
   `MAGIC_STAGGER` 的追加做法，避免移动既有种子流编号：建议 `PUSH_GATE`（`Random(20)` 命中门）与
   `PUSH_DISTANCE`（`Random(2)` 步数）。
4. 任一被成功推动的对象令 `boTrain` 成立且整次施法只训练一次；一个都没推动则不训练，但施法仍耗蓝
   并发出 `SM_MAGICFIRE`。推动步数由 `charPushed` 逐帧发出的 `RM_PUSH` 承载，无需新增世界事件；
   gate 已把 `WorldEvent.ObjectPushed` 映射为 `SM_BACKSTEP`（`GameProtocolAdapter:255`）。
   **本技能不引入任何新配置项**（Delphi 侧同样没有 `g_Config` 开关），因此不需要动
   `ServerConfig`/`WorldEngine.Config`/部署文档的环境变量表。
5. 能力矩阵仅把 `SKILL_FIREWIND:wizard` 标成 Java `implemented`，并明确 warrior/taoist 仍不可学习、
   `SKILL_ENERGYREPULSOR`（37）不在本切片范围。
6. 增加 `WorldPushArroundTest` 与 `GamePushArroundProtocolTest`：等级差与技能等级对命中门的影响、
   3×3 含边界与非可见对象不参与、随机门失败不推动也不训练、多步推动与撞墙截断、
   `RM_PUSH` 携带 `GetBackDir` 朝向、动物行走计时 +800ms、施法者自身与 NPC 被 `IsProperTarget` 排除、
   法力 2 与 `SM_MAGICFIRE` `Series=1540`、无 `SM_STRUCK`、推动成功才有 `SM_MAGIC_LVEXP`。
7. 更新本计划的验收记录（无新环境变量，部署文档无需改动）。必须实际运行权威 Maven verify；
   若当前环境无法取得 Maven/JDK 或依赖，不得将手工检查写成 Maven 通过（W44 用的是
   `jdk4py` + ECJ + `junit-platform-launcher` 的等价路径，只能是补充证据）。

## 验收与边界

- 建议门禁：`mvn -f java-server/pom.xml -pl world,gate,bootstrap,shadowdiff -am test`，最终按仓库 CI
  运行完整 `mvn -f java-server/pom.xml --batch-mode --no-transfer-progress verify`。
- 已知未建模项（须写入实现注释与能力矩阵，不以 Java↔Java 单测掩盖）：
  `m_boStickMode`（钉住模式，抗拒火环推不动的对象）在 Java 对象模型里没有字段；
  `IsProperTarget` 仍是既有 hostility 子集（未建模玩家攻击/保护模式、召唤物主人归属与独立 ghost）；
  Delphi `CharPushed` 的 `MoveToMovingObject(..., False)` 与 Java `GameMap.move` 的占用语义差异需要在
  实现时逐项比对，尤其是“推动路径上站着另一个对象”的分支（源码是 `Break`，不是跳过）。
- `m_VisibleActors` 在 Delphi 里由可视范围刷新维护；Java 的 `visibleIds` 是按 `viewRange` 现算的
  可见集合，两者在本切片的 ±1 格过滤下应等价，但刷新时机差异仍需记录在案。
- 没有 Delphi 服务端实跑或真实 `mir2.exe` wire 差分，不能声称 G4 通过。W45 只交付 Java 行为切片
  及其可复现测试证据。
