# W42 开发计划：`SKILL_FIREBOOM`（爆裂火焰）

**状态：** 本切片已落地。基线是 W41 的群体治愈术与 `CM_SPELL` shadowdiff 通道；本切片新增世界层和 gate 协议实现，但没有新增 Delphi shadowdiff 场景。**G4 仍未签发。**

## 源码依据与采用语义

- `Magic.pas:510` 的 `SKILL_FIREBOOM=23` 分支把 `GetAttackPower(...)` 的结果、点击坐标与 `g_Config.nFireBoomRage` 传给 `MagBigExplosion`；配置默认 1，`FunctionConfig.dfm` 限制为 1–12。
- `Magic.pas:1170` 的 `MagBigExplosion` 先经 `GetMapBaseObjects` 收集区域对象，再逐个调用 `IsProperTarget`；合法目标先 `SetTargetCreat`，再收到普通 `SendMsg(..., RM_MAGSTRUCK, ..., nPower, ...)`。这是**对象绑定、无额外延迟**的消息，不是带坐标到达条件的 `RM_DELAYMAGIC`。命中至少一个合法目标就返回 `True`，供 `DoSpell` 设置 `boTrain`；伤害被目标 MAC 完全吸收并不撤销这次训练。
- `ObjBase.pas:19545` 的范围收集按 X/Y 遍历 `[-range,+range]`，两端都包含，并在收集时排除死亡与 ghost。Java 地图占用索引没有独立 ghost 对象；`isProperTarget` 排除死亡对象，非占用 ghost 不会进入候选。
- `ClientSpellXY` 的目标吸附经 `CretInNearXY` 检查点击周围 3×3 格；合法对象将爆炸中心吸附到其格子，死亡目标仍可能留下吸附坐标但不再作为显示目标。普通魔法点击相对角色仍受 ±8 格门禁。FireBoom 不做视线或地形阻挡检测。
- 功率严格沿用 Delphi 调用：`GetAttackPower(GetPower(MPow) + LoWord(MC), SmallInt(HiWord(MC)-LoWord(MC)) + 1)`。第二参数是幅度，不是高端点；加一后仍由 `GetAttackPower` 的 inclusive `Random(nPower + 1)` 再产生额外一点。Luck/UnLuck 走既有 `attackPower` 逻辑。玩家 `m_nPowerRate`、power item、auto/fix color 修正不在当前 Java 玩家模型中。
- `RM_MAGSTRUCK`（`ObjBase.pas:4500`）在低于 50 级的动物类对象上先加 `800 + Random(1000)` 行走计时，再滚目标 MAC，并可经过魔法盾；`GetMagStruckDamage(nil, ...)` 不加针对攻击者的 undead 加成。抵抗 AntiMagic 的 `Random(10)` 门属于穿透/单体链，不应套到 FireBoom。成功受击经 `RM_STRUCK_MAG`，`TMessageBodyWL.lTag2=1`。

## W42 已落地

- `WorldEngine.Config` 与 `ServerConfig` 新增 FireBoom 半径，默认 1、合法 1–12；保留扩展前的构造签名与复制辅助器。服务端从 `MIR2_FIREBOOM_RANGE` 读取，并由 `Mir2Server` 传到世界引擎；部署配置表已列出该变量。
- `SKILL_FIREBOOM` 进入正常法师职业、等级、距离、法力与冷却门禁；空地点击合法，不要求单体目标，也不做逐目标 AntiMagic 抵抗。
- 吸附后的点击坐标作为效果中心；按配置半径筛选包含边界的正方形候选，使用当前 `IsProperTarget` 最小实现过滤死亡、施法者本身、NPC 与跨地图对象；对怪物同步复现 `SetTargetCreat` 的仇恨锁定。整次施法只计算一次 nPower 并共享给所有目标。
- 新增独立 `AREA_DAMAGE` pending impact：到达时不重新检查目标是否仍在爆炸坐标，逐目标走 MAC、魔法盾、正常 `applyDamage`；魔法受击事件携带 `magical=true`，gate 将其写到 `SM_STRUCK` body 的 `lTag2=1`。低级怪物 `RM_MAGSTRUCK` 行走延迟走新追加的随机流，不移动既有种子流编号。
- 任一合法区域目标令 `boTrain` 生效且整次施法只训练一次；区域为空则不训练，但施法仍会消耗法力并发出 `SM_MAGICFIRE`。
- `g4-capability-matrix.tsv` 只把法师行标为 `implemented`；战士/道士仍因 Magic.DB 不可学习而保持 `unimplemented`。

## 验收覆盖

- `WorldAreaExplosionTest`：共享 nPower/逐目标 MAC、魔法盾减伤、包含边界、排除范围外对象、吸附坐标、对象移动后仍受击、无目标时不训练、配置半径与合法范围。
- `GameAreaExplosionProtocolTest`：真实 `CM_SPELL` 发包形状、`SM_MAGICFIRE` 效果/坐标/目标、法力、`SM_STRUCK` 魔法标记和 `SM_MAGIC_LVEXP`。
- `ServerConfigTest`：默认值、环境变量、1–12 校验以及配置复制。

建议在具备 JDK 21+ 与 Maven 3.9+ 的环境运行：

```bash
mvn -f java-server/pom.xml -pl world,gate,bootstrap -am test
```

本轮沙箱找不到 `java` / `mvn` 可执行文件，以上测试尚未运行；不能据此宣称构建或测试通过。

## 已知边界与下一步

- 当前 `IsProperTarget` 仍是已有 Java hostility 子集：未建模玩家攻击/保护模式、召唤物主人归属及独立 ghost 状态；Delphi `bo2BF` 的低级动物延迟豁免也没有 Java 字段。玩家 `m_nPowerRate`、power item、auto/fix color 亦未建模。相关边界已写入世界实现注释及能力矩阵说明，不以 Java↔Java 单测掩盖。
- 本切片没有 Delphi 服务端或真实 `mir2.exe` 的 wire 差分证据，也未增加 shadowdiff 场景；G4 继续未签发。
- 下一技能候选为 `SKILL_LIGHTFLOWER`（24，地狱雷光，P1）。在实现前应先核验 `MagElecBlizzard` 的按目标 undead 分流、`nElecBlizzardRange` 配置与角色中心语义，再定义可测试边界。

## W42 计划闭环

W42 列出的下一候选 `SKILL_LIGHTFLOWER` 已在 W43 按 Delphi 源码证据实现：施法者中心方形、`LA_UNDEAD` 满额/其余 `div 10` 的逐目标分流、立即 `RM_MAGSTRUCK`（无 `SetTargetCreat`、无 600ms 延迟、无抗魔抵抗门）、`MIR2_ELEC_BLIZZARD_RANGE`（默认 2，1–12）。范围与验收见 [`w43-next-plan.md`](w43-next-plan.md)；该实现同样不等同于 Delphi/真实客户端对拍，G4 仍未签发。
