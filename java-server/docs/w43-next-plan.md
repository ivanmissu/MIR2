# W43 开发计划：`SKILL_LIGHTFLOWER`（地狱雷光）

**状态：** 本切片已落地。基线是 W42 的爆裂火焰与 `CM_SPELL` 范围施法通道；本切片新增世界层与配置接线，但没有新增 Delphi shadowdiff 场景。**G4 仍未签发。**

## 源码依据与采用语义

- `Magic.pas:518-521` 的 `SKILL_LIGHTFLOWER=24` 分支只做一件事：`MagElecBlizzard(PlayObject, PlayObject.GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(PlayObject.m_WAbil.MC), SmallInt(HiWord(PlayObject.m_WAbil.MC)-LoWord(PlayObject.m_WAbil.MC)) + 1))`。它与爆裂火焰共用同一形状的 `GetAttackPower`（整次施法只掷一次），但**不接收点击坐标**，也不接收目标对象。
- `Magic.pas:1191` 的 `MagElecBlizzard` 用 `GetMapBaseObjects(BaseObject.m_PEnvir, BaseObject.m_nCurrX, BaseObject.m_nCurrY, g_Config.nElecBlizzardRange{2}, ...)` 收集**施法者自身格**周围 `[-range,+range]` 的包含边界方形，然后逐目标：非 `LA_UNDEAD` 取 `nPower div 10`，`LA_UNDEAD` 取完整 `nPower`；`IsProperTarget` 通过后 `SendMsg(BaseObject, RM_MAGSTRUCK, 0, nPowerPoint, 0, 0, '')`。该消息**没有延迟**、没有 `SetTargetCreat`（源码里那一行是注释）、也没有逐目标抗魔抵抗门。
- `ObjBase.pas:4500` 的 `RM_MAGSTRUCK` 处理器先在 `m_btRaceServer >= RC_ANIMAL`、非 `bo2BF`、`m_Abil.Level < 50` 时追加 `800 + Random(1000)` 行走计时，再走 `GetMagStruckDamage(nil, nParam1)`（MAC，可被魔法盾减免），伤害为 0 时不广播受击帧。低于 50 级的行走延迟与 Java 现有 `MagicImpactKind.AREA_DAMAGE` 路径逐字一致。
- `ObjBase.pas:8964+ ClientSpellXY` 的通用吸附（`CretInNearXY`，±1 格）依旧生效：点击坐标会被吸附到附近对象格，然后原样进入 `DoSpell`；`Magic.pas:739-749` 的尾部在 `boSpellFire` 为真时广播 `RM_MAGICFIRE`，携带的是**吸附后的点击坐标**与对象 id。因此点击坐标只影响 `SM_MAGICFIRE`，不影响爆炸范围。
- `MagElecBlizzard` 的返回值是“是否至少命中一个合法目标”，即 `DoSpell` 的 `boTrain`；MAC 完全吸收并不撤销这次训练。施法者本人在 `IsAttackTarget` 中被 `BaseObject = Self` 排除，所以不会被自己的雷光打到。
- `FunctionConfig.pas:955/1022` 与 `FunctionConfig.dfm`：`EditElecBlizzardRange` 的 `MinValue = 1`、`MaxValue = 12`（表单初值 1，仅 UI）；`M2Share.pas:2079` 的 `g_Config.nElecBlizzardRange` 运行期默认是 **2**，并以 `Setup/ElecBlizzardRange` 落盘（`M2Share.pas:10401`）。

## W43 已落地

- `WorldEngine.Config` 新增 `elecBlizzardRange`，默认 2、合法 1–12；保留 W42 的 10 参构造签名与全部既有短构造器。服务端从 `MIR2_ELEC_BLIZZARD_RANGE` 读取，`Mir2Server` 传入世界引擎，部署文档已列出该变量。
- `SKILL_LIGHTFLOWER` 进入正常法师职业、等级、距离、法力与冷却门禁；空地点击合法（不需要目标对象），点击仅决定 `SM_MAGICFIRE` 的坐标与目标 id。
- 新增 `castElecBlizzard`：以施法者当前格为中心、按配置半径取包含边界方形，用既有 `isProperTarget` 最小实现过滤（存活、同地图、非自身、非 NPC），逐个目标写入同刻到期的 `AREA_DAMAGE` 冲击；每个冲击携带自己的伤害（`LA_UNDEAD` 满额、其余 `nPower div 10`），到达时逐目标滚 MAC、过魔法盾、走 `applyDamage(..., magical=true)`，并给低于 50 级的怪物追加 `800 + Random(1000)` 行走延迟。
- 与 FireBoom 的差异全部按源码保留：圆心是施法者而不是点击、不做 `SetTargetCreat` 仇恨锁定、没有 600ms 延迟、没有逐目标抗魔抵抗、非亡灵目标按 `div 10` 削减。
- 任一合法区域目标令 `boTrain` 生效且整次施法只训练一次；区域为空（例如点击落点附近无对象、自身格周围空无一物）不训练，但施法仍会消耗法力并发出 `SM_MAGICFIRE`。
- `g4-capability-matrix.tsv` 只把法师行标为 `implemented`；战士/道士行仍因 Magic.DB 同名行 `learnable=no` 保持 `unimplemented`。

## 验收覆盖

- `WorldElecBlizzardTest`：施法者中心与 `LA_UNDEAD` 满额/其余十分之一的分流、点击格与吸附只影响广播、对象绑定冲击在目标离格后仍然生效、包含边界与地图边缘裁剪、空区域不训练、配置半径与 1–12 校验。
- `GameElecBlizzardProtocolTest`：真实 `CM_SPELL` 发包形状、`SM_MAGICFIRE` 的 `effectType=4|effect=22<<8` / 点击坐标 / 目标 id、法力消耗 29（`GetSpellPoint` 零级）、两条 `SM_STRUCK` 魔法标记、`SM_MAGIC_LVEXP` 训练帧，以及空区域只有施法与耗蓝、没有受击与训练帧。
- `ServerConfigTest`：`MIR2_ELEC_BLIZZARD_RANGE` 默认 2、取值 1–12、复制辅助器保持该值。

本轮沙箱没有 Maven 与 Maven Central，因此改用等价的手工编译/运行路径：JDK 21 运行时 + ECJ 3.39.0 编译 `java-server` 全部模块的 `src/main/java` 与 `src/test/java`（含 loadtest / wiretool / character / auth），再用 JUnit Platform launcher 执行全部测试类。结果：**532 项测试通过、0 失败**（world/gate/bootstrap 484 项 + loadtest/wiretool/character/auth 48 项），其中本切片新增的 `WorldElecBlizzardTest` 6 项与 `GameElecBlizzardProtocolTest` 2 项全绿，`G4CapabilityMatrixTest`/`G4ReleaseGateTest` 也在文档改动后通过。

这条路径只是补充证据，不是 Maven 门禁：依赖解析、注解处理与 surefire 配置都没有经过 Maven 校验，权威门禁仍是 CI 的 `mvn -f java-server/pom.xml --batch-mode --no-transfer-progress verify`。

## 已知边界与下一步

- 当前 `IsProperTarget` 仍是已有 Java hostility 子集：未建模玩家攻击/保护模式、召唤物主人归属及独立 ghost 状态；`RM_MAGSTRUCK` 的 `bo2BF` 低级动物豁免也没有 Java 字段。玩家 `m_nPowerRate`、power item、auto/fix color 修正不在 Java 玩家模型中。相关边界已写入世界实现注释与能力矩阵说明，不以 Java↔Java 单测掩盖。
- 本切片没有 Delphi 服务端或真实 `mir2.exe` 的 wire 差分证据，也未增加 shadowdiff 场景；G4 继续未签发。
- 下一技能候选 `SKILL_SNOWWIND`（33，冰咆哮，P1）已完成源码核验并形成 W44 计划：`Magic.pas:578` 与爆裂火焰共用 `MagBigExplosion`，使用独立 `nSnowWindRange`；默认 1、SpinEdit 范围 1–12，无其他调用语义差异。详见 [`w44-next-plan.md`](w44-next-plan.md)。
