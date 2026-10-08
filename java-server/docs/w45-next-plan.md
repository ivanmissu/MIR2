# W45 开发计划：`SKILL_FIREWIND`（抗拒火环）

**状态：** 本切片已落地（W45）。基线是 W44 的冰咆哮与 `CM_SPELL` 通道；本切片没有新增配置项、没有新增
Delphi shadowdiff 场景。**G4 仍未签发**，Java 单测不等于 Delphi/真实客户端对拍证据。

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

## W45 已落地

- `WorldEngine` 新增 `SKILL_FIREWIND = 8` 与 `isPushArroundSkill(int)`；该判定接进 `castPlayerSpell` 的三处既有
  门禁：职业/等级/距离/法力/冷却共用同一路径、`validSpellTarget` 单体目标校验把抗拒火环列入跳过名单
  （与地狱雷光/范围爆炸同组），并在 `SKILL_LIGHTFLOWER` 分支之后单独分派到 `castPushArround`，
  不再落到单体冲击链。
- 新增 `castPushArround`：先按 `CretInNearXY` 的既有规则吸附点击（吸附结果只随 `RM_MAGICFIRE` 下发），再以
  `visibleIds(player.map, player.position, player.id)` 遍历施法者可见对象（不含自身），按源码顺序过滤
  ——含边界 3×3、存活、`caster.level > target.level`（等级差）、随机门、`isProperTarget`；通过后
  `Direction.getNextDirection` 取方向、循环调用既有 `charPushed` 最多
  `1 + max(0, skillLevel - 1) + Random(2)` 步，任一格被挡立即 `break`。**不掷功率、不造成任何伤害、不做 MAC 或魔法盾结算。**
- 两处 `Random` 使用新增的独立流 `PUSH_GATE`（`Random(20)` 命中门）与 `PUSH_DISTANCE`（`Random(2)` 步数），
  追加在 `MAGIC_STAGGER` 之后，既有种子流编号不变。
- 语义按源码逐条保留：随机门在 `IsProperTarget` **之前**掷（NPC 也会吃掉一次门掷）；`Inc(Result)` 在
  `CharPushed` 之外，因此**撞墙零步也算推动并训练**；推动成功才 `trainSpellSkill`（`btLevel < 3` 与
  `TrainLevel[btLevel] <= Level` 由既有 `PlayerSkill.train` 承载）；一个都没推动时仍耗蓝并广播 `SM_MAGICFIRE`。
  推动步数逐格经 `charPushed` 发 `RM_PUSH`，gate 已将其映射为 `SM_BACKSTEP`，无需新增世界事件。
- **本技能不引入任何新配置项**：Delphi 侧没有 `g_Config` 开关，`ServerConfig`/`WorldEngine.Config`/部署文档
  均未改动。
- `g4-capability-matrix.tsv` 只把 `SKILL_FIREWIND:wizard` 标为 `implemented`；战士/道士行仍因 Magic.DB 同名行
  `learnable=no` 保持 `unimplemented`，`SKILL_ENERGYREPULSOR`（37）明确不在本切片范围。

## 验收覆盖

- `WorldPushArroundTest`（10 项）：相邻低等级目标被推一格、朝向回落 `GetBackDir`、无伤害、无 `SM_STRUCK`、
  一次训练与耗蓝 2；技能等级 0–3 的步数公式（`1 + max(0, level-1)` 基线）；随机门对等级差与技能等级
  的依赖（固定掷 19：gap 5 + 技能 0 被拒、同 gap + 技能 3 通过、gap 15 + 技能 0 通过）；含边界 3×3
  （对角目标按 `GetNextDirection` 推出、隔一格对象不动）；**门掷早于 `IsProperTarget`**（NPC 只吃掉一次
  `Random(20)`、没有 `Random(2)`）；撞墙零步仍训练；同级目标在掷骰前被跳过；被推动物行走计时 +800ms
  （400ms 不动、800ms 后恢复追击）；职业/等级拒绝与超距 `OUT_OF_RANGE`。
- `GamePushArroundProtocolTest`（2 项）：真实 `CM_SPELL` 发包 → `+GOOD`、`SM_MAGICFIRE` 的
  `effectType=4 | effect=6<<8`（Series 1540）/ 吸附坐标 / 目标 id、耗蓝 2、`SM_BACKSTEP` 携带**推动后**的格子与
  `nBackDir` 朝向（`CharPushed` 先改 `m_nCurrX/m_nCurrY` 再 `SendRefMsg`）、零条 `SM_STRUCK`、
  `SM_MAGIC_LVEXP`（recog=8、等级 0、点数可见）；以及空 3×3 只广播施法与耗蓝、没有后退帧与训练帧。

## 已执行的验证

本轮沙箱没有 Maven，也没有 Maven Central 出网（只有 github.com / api.github.com / registry.npmjs.org /
pypi.org 可达），因此沿用 W44 的等价手工路径：PyPI `jdk4py` 21.0.8.2 运行时 + GitHub blob API 取回的
ECJ **3.46.100**（W44 记录的是 3.39.0；本仓库可用版本为 3.33.0/3.38.0/3.45.0/3.46.100）编译全部模块的
`src/main/java` 与 `src/test/java`，依赖用与 `pom.xml` 同版本、从 GitHub 已提交的 `.m2` 目录按 blob SHA
取回的 `junit-jupiter{,-api,-engine,-params}-5.10.3` / `junit-platform-{commons,engine,launcher}-1.10.3` /
`opentest4j-1.3.0` / `apiguardian-api-1.1.2` / `sqlite-jdbc-3.46.1.0` / `slf4j-api-1.7.36`，
再用 `junit-platform-launcher` 直接执行测试类。结果：**554 项测试通过、0 失败**
（基线 542 项 + 本切片新增 12 项），`G4CapabilityMatrixTest` 在矩阵改动后同样通过。

这条路径只是补充证据，不是 Maven 门禁：依赖解析、注解处理与 surefire 配置都没有经过 Maven 校验，
权威门禁仍是 CI 的 `mvn -f java-server/pom.xml --batch-mode --no-transfer-progress verify`。

## 已知边界与下一步

- 已核对等价、无需记录的差异：Delphi `CharPushed` 的 `CanWalk(..., False)` + `MoveToMovingObject(..., False)`
  在 Java 侧由 `GameMap.canWalk`（地形 + 占用）承担，推动路径上站着另一个对象时两边都是就地 `Break`，
  剩余步数作废。
- 仍未建模（已写入实现注释与能力矩阵）：`m_boStickMode`（钉住模式）在 Java 对象模型里没有字段，因此
  当前没有推不动的对象；`IsProperTarget` 仍是既有 hostility 子集，未建模玩家攻击/保护模式、召唤物主人
  归属与独立 ghost 状态；`m_VisibleActors` 在 Delphi 里由可视范围刷新维护，Java 的 `visibleIds` 是按
  `viewRange` 现算的集合，两者在本切片的 ±1 格过滤下等价（`viewRange >= 1` 必然覆盖 3×3），刷新时机
  差异仍记录在案。
- 没有 Delphi 服务端实跑或真实 `mir2.exe` wire 差分，不能声称 G4 通过。W45 只交付 Java 行为切片及其
  可复现测试证据。
- 下一技能候选：`SKILL_ENERGYREPULSOR`（37，气功波）与 `SKILL_FIREWIND` 共用 `MagPushArround`，但 GEEM2
  基线没有同编号行、Job 归属待校准，**不能**凭共用函数直接开放。Magic.DB 1–33 内尚未实现、且不依赖召唤物/
  脚本系统的候选还有：幽灵盾（14）/神圣战甲术（15）一类的限时增益组、隐身术（18）/集体隐身术（19）组、
  瞬息移动（21）、圣言术（32，仅亡灵）。下一次会话应先按 `w45` 的方法做逐分支源码核验，再决定切片顺序。
