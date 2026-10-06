# W44 开发计划：`SKILL_SNOWWIND`（冰咆哮）

**状态：** 本切片已落地。基线是 W43 的地狱雷光与 `CM_SPELL` 范围施法通道；本切片复用 W42 的 `MagBigExplosion` 路径并接上独立半径配置，没有新增 Delphi shadowdiff 场景。**G4 仍未签发。**

## 源码依据与采用语义

- `Magic.pas:578-585` 的 `SKILL_SNOWWIND{33}` 分支与 `Magic.pas:510-517` 的 `SKILL_FIREBOOM{23}`
  **逐字同形**：先算 `GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(MC), SmallInt(HiWord(MC)-LoWord(MC)) + 1)`，
  再把 `nTargetX/nTargetY` 与半径一起交给 `MagBigExplosion`；唯一的实参差别是
  `g_Config.nSnowWindRange{1}` 换掉了 `g_Config.nFireBoomRage{1}`。因此 W42 已经证实的共同语义全部沿用：
  点击经 `CretInNearXY` 可吸附到邻近对象、吸附后坐标为爆炸中心、中心方形含边界、整次施法只掷一次共享功率、
  合法目标先 `SetTargetCreat` 再收到对象绑定立即生效的普通 `RM_MAGSTRUCK`、任一合法目标即训练一次、
  MAC 全吸收不取消训练、空区域仍耗蓝并广播施法但不训练。
- `M2Share.pas:2078`：`nSnowWindRange` 运行期默认值为 **1**；`M2Share.pas:10395-10396` 以
  `Setup/SnowWindRange` 读取/写回，`GameOfMir/MirServer/!Setup.txt:296` 的 `SnowWindRange=1` 与之
  一致（与 `nElecBlizzardRange` 的“表单初值 1、运行期 2”不同，冰咆哮两处都是 1）。
- `FunctionConfig.dfm:2059-2078`：`EditSnowWindRange` 的 `MinValue = 1`、`MaxValue = 12`、`Value = 1`；
  `FunctionConfig.pas:954/1012-1016` 负责表单初值与写回。配置核验确认没有不同于 FireBoom 的半径上下限。
- `Magic.DB` 第 33 行（`world/src/main/resources/db/MagicDb.tsv`）把 冰咆哮 标为 `job=1`（法师）可学习，
  战士/道士不可学习；`NeedL1=35`、`spell=12`/`defSpell=30` → 零级 `GetSpellPoint` = **33**，
  `delay=60`、`effectType=2/effect=31`（`SM_MAGICFIRE` 的 `Series`）。

## W44 已落地

- `WorldEngine.Config` 与 `ServerConfig` 新增 `snowWindRange`，默认 1、合法 1–12；两个类型都保留了
  W43 的旧构造签名（既有测试、embedded 与 loadtest 调用方不受影响）。服务端从 `MIR2_SNOW_WIND_RANGE`
  读取，`Mir2Server` 传入世界引擎，`ServerConfig` 增加 `withSnowWindRange` 复制辅助器，部署文档已列出该变量。
- `SKILL_SNOWWIND` 进入正常法师职业、等级、距离、法力与冷却门禁；空地点击合法（不需要目标对象），
  点击只决定吸附后的爆炸中心与 `SM_MAGICFIRE` 坐标。
- 新增 `castSnowWind`，与 `castAreaExplosion` 一起收敛到同一个 `castBigExplosion` 私有实现：
  两者只有半径实参不同（SnowWind 读 `config.snowWindRange()`，FireBoom 读 `config.fireBoomRange()`），
  **SnowWind 不会回落到 FireBoom 的半径**。仇恨锁定、`AREA_DAMAGE` 即时投递、施法广播与训练语义共用一套代码。
- `g4-capability-matrix.tsv` 只把法师行标为 `implemented`；战士/道士行仍因 Magic.DB 同名行
  `learnable=no` 保持 `unimplemented`。

## 验收覆盖

- `WorldSnowWindTest`（7 项）：默认半径 1 的含边界 3×3 方形与整次共享功率/逐目标 MAC、魔法受击标记、
  只训练一次与耗蓝 33；点击吸附广播坐标；对象移动后冲击仍到达（手工时钟把 `RM_MAGSTRUCK` 推迟到
  受害者走出方形之后才泵送）；配置半径 2 的边界与空区域不训练但仍耗蓝广播；
  **半径独立性**（同一点击在 `nSnowWindRange=3` 下打到 (7,8)，在 `nFireBoomRage=1` 下打不到）；
  `SetTargetCreat` 仇恨锁定（被击木桩在有人更近时仍追向施法者）；战士/道士 `WRONG_JOB` 与 34 级
  `LEVEL_TOO_LOW` 拒绝；配置 1–12 校验与默认值。
- `GameSnowWindProtocolTest`（2 项）：真实 `CM_SPELL` 发包形状、`SM_MAGICFIRE` 的
  `effectType=4|effect` → 本行 `2|31<<8` / 吸附坐标 / 目标 id、`+GOOD`、法力消耗 33、两条 `SM_STRUCK`
  的魔法标记（`lTag2=1`）、`SM_MAGIC_LVEXP` 训练帧（recog=33、等级 0、点数可见）；
  以及空方块只广播施法与耗蓝、没有受击与训练帧。
- `ServerConfigTest`：`MIR2_SNOW_WIND_RANGE` 默认 1、取值 1–12、`withSnowWindRange` 与全部无关复制
  辅助器保持该值，且改 SnowWind 不会拖动 FireBoom/ElecBlizzard。

本轮沙箱没有 Maven，也没有 Maven Central 出网（只有 github.com / api.github.com /
registry.npmjs.org / pypi.org 可达），因此改用等价的手工编译/运行路径：PyPI 的 `jdk4py` 21.0.8.2
运行时 + GitHub 上核对过的 ECJ 3.39.0 编译 `java-server` 全部模块的 `src/main/java` 与
`src/test/java`，依赖用与 `pom.xml` 同版本、从 GitHub 仓库内已提交的 `.m2` 目录按 blob SHA 取回的
`junit-jupiter{,-api,-engine,-params}-5.10.3` / `junit-platform-{commons,engine,launcher}-1.10.3` /
`opentest4j-1.3.0` / `apiguardian-api-1.1.2` / `sqlite-jdbc-3.46.1.0` / `slf4j-api-1.7.36`，
再用 `junit-platform-launcher` 直接执行全部测试类。结果：**542 项测试通过、0 失败**
（基线 532 项 + 本切片新增 10 项），`G4CapabilityMatrixTest`/`G4ReleaseGateTest` 在文档改动后同样通过。

这条路径只是补充证据，不是 Maven 门禁：依赖解析、注解处理与 surefire 配置都没有经过 Maven 校验，
权威门禁仍是 CI 的 `mvn -f java-server/pom.xml --batch-mode --no-transfer-progress verify`。

## 已知边界与下一步

- 当前 `IsProperTarget` 仍是已有 Java hostility 子集：未建模玩家攻击/保护模式、召唤物主人归属及独立
  ghost 状态；`RM_MAGSTRUCK` 的 `bo2BF` 低级动物豁免也没有 Java 字段。玩家 `m_nPowerRate`、power item、
  auto/fix color 修正不在 Java 玩家模型中。边界已写入世界实现注释与能力矩阵说明，不以 Java↔Java 单测掩盖。
- 本切片没有 Delphi 服务端或真实 `mir2.exe` 的 wire 差分证据，也未增加 shadowdiff 场景；G4 继续未签发。
- 下一技能候选 `SKILL_FIREWIND`（8，抗拒火环，P1）已完成源码核验并形成 W45 计划：
  `Magic.pas:384-386` 只调用 `MagPushArround(PlayObject, UserMagic.btLevel)`，不掷功率、不造成伤害，
  是纯粹的位移技能；详见 [`w45-next-plan.md`](w45-next-plan.md)。
