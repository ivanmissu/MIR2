# W44 开发计划：`SKILL_SNOWWIND`（冰咆哮）

**状态：** 计划已完成源码核验，尚未实现。W43 的下一技能候选落地为本计划。目标是沿用 W42 `MagBigExplosion` 路径，为法师接入冰咆哮；不得把 Java 单测描述为 Delphi/真实客户端对拍证据，G4 仍未签发。

## 源码核验结论

- `GameOfMir/M2Server/Magic.pas:578-585`：`SKILL_SNOWWIND=33` 调用 `MagBigExplosion`，伤害公式与 `SKILL_FIREBOOM=23` 相同：`GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(MC), SmallInt(HiWord(MC)-LoWord(MC)) + 1)`；区别是使用 `g_Config.nSnowWindRange`。
- `GameOfMir/M2Server/M2Share.pas:2078`：`nSnowWindRange` 运行期默认值为 **1**。`M2Share.pas:10393-10396` 从 `Setup/SnowWindRange` 读取/写回；`GameOfMir/MirServer/!Setup.txt:295-296` 也为 1。
- `GameOfMir/M2Server/FunctionConfig.dfm:2059-2078`：SpinEdit 范围为 **1–12**，表单初值 1；`FunctionConfig.pas:1012-1016` 将 UI 值写入配置。配置核验确认没有不同于 FireBoom 的半径上下限。
- SnowWind 与 FireBoom 共用 `MagBigExplosion`，因此沿用已证实的共同语义：点击可吸附到邻近对象，吸附后坐标为爆炸中心；中心方形含边界；每次施法只掷一次共享功率；合法目标收到对象绑定、立即生效的普通 `RM_MAGSTRUCK`；目标怪物设置施法者为仇恨目标；任一合法目标即训练一次，MAC 全吸收不取消训练；空区域仍耗蓝并广播施法，但不训练。
- `Magic.DB` 对应技能行将 SnowWind 标为 wizard 可学习，warrior/taoist 不可学习；实现只开启法师路径，不改变其他职业能力矩阵。

## 实现范围

1. 新增独立配置项 `snowWindRange`：默认 1，校验 1–12；从 `MIR2_SNOW_WIND_RANGE` 读取，经 `ServerConfig` 传入 `WorldEngine.Config`，并更新部署文档。保留既有构造器签名/默认值兼容，避免既有测试、embedded 与 loadtest 调用方破坏。
2. 将技能 33 接入既有魔法门禁及施法分派。实现 `castSnowWind` 时复用 FireBoom 的目标吸附、`isProperTarget`、单次 `rollMcAttackPower`、仇恨锁定、AREA_DAMAGE 即时投递、施法广播及训练语义；半径只读 SnowWind 配置，不复用 FireBoom 配置。
3. 能力矩阵仅把 `SKILL_SNOWWIND:wizard` 标成 Java implemented，并明确 warrior/taoist 仍不可学习。
4. 增加 `WorldSnowWindTest` 与 `GameSnowWindProtocolTest`，验证范围边界/配置、共享伤害、吸附与广播坐标、空区域不训练、目标对象移动后冲击仍到达、伤害标记与耗蓝；增加 `ServerConfigTest` 配置默认值/环境变量/边界/复制行为断言。
5. 更新部署文档及本计划的验收记录。必须实际运行权威 Maven verify；若当前环境无法取得 Maven/JDK 或依赖，不得将手工检查写成 Maven 通过。

## 验收与边界

- 建议门禁：`mvn -f java-server/pom.xml -pl world,gate,bootstrap,shadowdiff -am test`，最终按仓库 CI 运行完整 `mvn -f java-server/pom.xml --batch-mode --no-transfer-progress verify`。
- 当前世界 hostility 仍未覆盖玩家攻击/保护模式、召唤物主人归属和独立 ghost 状态；继续沿用 FireBoom 已声明的最小 Java 目标过滤，不借此切片扩大目标规则。
- 没有 Delphi 服务端实跑或真实 `mir2.exe` wire 差分，不能声称 G4 通过。W44 只交付 Java 行为切片及其可复现测试证据。
