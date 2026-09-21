# MIR2 服务端 Java 化迁移 开发计划书

*Development Plan · v1.0.20 · 2026-09-21*

**30 周日历（约 7 个月）** · **2 人团队 · 240 人日** · **6 道决策门 G0–G5** · **上线目标：2027 年 5 月** · **全程 Linux/Docker 交付**

本计划以《可行性评估报告》的 GO 结论为基线，将 8–12 人月的迁移工程拆解为 **1 个 PoC + 5 个阶段（P0–P4）+ 6 道决策门（G0–G5）**，覆盖团队分工、周级任务分解、工程规范、 CI/CD、发布回滚与预算，可直接作为项目执行与跟踪的依据。

> **执行状态（截至 2026-09-20）**：S0/W01 协议基座已完成；W02 账号、角色、SQLite 持久化已完成；Gate 接入与会话路由已完成初版；可执行 JAR、环境配置、优雅停机及 Docker Compose 已交付。W03 的 **Tick、地图、碰撞、对象生命周期、12 格视野、RunLogin、移动协议及 GAME→World 接线** 已形成自动化闭环；近战攻击、单种怪物 AI、击杀/经验、掉落/拾取与战斗/背包状态落库已完成。W04 **物品目录与背包同步**已交付：完整 `StdItem` 模板与 SQLite `std_items` 目录、复刻 `GetItemNumber` 的稳定 `MakeIndex`、耐久字段、**76 字节 `TClientItem`** 小端编解码，以及 `CM_QUERYBAGITEMS → SM_BAGITEMS`（空包静默）与 `SM_ADDITEM` 完整载荷。W05 交付 **bot 压测军团**：50 机器人 × 5 分钟在 embedded 与 remote-fatjar 双模式均 PASS（0 错误），CI 每个 PR 增跑 50×2 分钟回归。本次会话（W06）交付 **traffic recorder / replayer 骨架**（P0 三件套至此齐活）：新增 `java-server/wiretool` 模块，`record` 透明代理把 `mir2.exe ↔ 服务端（Delphi 或 Java）`双向流量按 `#…!` 帧无损落盘 `.mrec`，`replay` 把录到的客户端帧按原节奏回放到目标服务端并与录制应答**逐字节对拍**（缺失/多余/首差异偏移分类，支持易变帧跳过清单与 `--structural-only` 结构级判定），`inspect` 输出逐帧注解（ident 名反查、GBK 正文、RunLogin 识别、cert 打码）；CI 新增 wiretool 门禁（代理实捕 bot 登录 → inspect 校验 → 结构级回放 PASS / 字节级回放因认证码易变而如期 FAIL）；dist 通道现同时发布 server/loadtest/wiretool 三个 fat JAR。本次会话（W07）交付**接入层加固第一项**：修复 W06 冒烟暴露的 `GateSessionRegistry.remove` 无调用点缺口——认证码在 GAME 登录真正进图成功后立即消费失效，GAME 连接断线（正常/异常）时同步清理 World 侧玩家对象与网关侧认证码；进图失败重试（如快速重登的 `leavePlayer` 竞态）仍可复用同一认证码直至真正进图成功，不影响 bot-swarm 既有的重登语义。真实 `mir2.exe` 对拍与 Delphi 实捕 golden 仍是最大缺口，待 Windows 客户端环境——届时直接用 wiretool 录 Delphi 链路并按字节级 golden 入库；连接数/频率限制与空闲超时已由 W08 交付。本次会话（W10）交付**门与传送点**：`.map` `btDoorIndex` 门锚点解析（同 index ±10 半径共享 `TDoorStatus`）、`CM_OPENDOOR → SM_OPENDOOR_OK` ±12 广播、500ms 扫拍 5 秒自动关门（`ProcessMapDoor` 语义）、「关门不挡路」客户端本地拦截 quirk 忠实复刻；经典 `MapInfo.txt` 加载器（`loadmapinfo` 子目录内嵌、`;` 注释、`[id|alias desc]` 条目、`GetValidStr3` 分隔符链路线行）与 `MIR2_MAPINFO_FILE` 多图模式；走/跑落格触发换图（`SM_CLEAROBJECTS→SM_CHANGEMAP→SM_MAPDESCRIPTION`、±1 关门压制连接点、目标不可走整步回滚）。城堡门差异分支、跨服切换、MapFlag 旗标与昼夜亮暗显式留在后续切片（MapFlag/昼夜已由 W11 交付）。本次会话（W12）交付 **物品切片：装备穿脱 + 使用 + 丢弃**，P2「物品」一条线就此收口：13 槽 `THumanUseItems` 装备容器、`CheckUserItems` 槽位匹配与 `CheckTakeOnItems` 穿戴条件（性别/负重/`Need` 族）、`RecalcAbilitys` 属性重算（`ApplyItemParameters` 按 `ItemType` 分支 + `Weight/WearWeight/HandWeight` 三桶负重 + `GetFeature` 外观联动）、`CM_TAKEONITEM/CM_TAKEOFFITEM/CM_EAT/CM_DROPITEM` 四条命令与 `SM_TAKEON_OK/FAIL`、`SM_TAKEOFF_OK/FAIL`、`SM_EAT_OK/FAIL`、`SM_DROPITEM_SUCCESS/FAIL`、`SM_SENDUSEITEMS`、`SM_WEIGHTCHANGED`、`SM_ABILITY` 应答，装备槽持久化（新表 `character_equipment`，旧库开库即原位升级）。**同时修复 master 编译中断**：W11（PR #25）在 CI 红灯状态下合入，`WorldEngine.java` 引用了 17 个不存在的方法，本次一并对齐既有 API 并补上缺失的 `addSpawner(MonsterSpawnDefinition)` 重载。本次会话（W14）交付 **等级提升 + 死亡/复活闭环**，P2「打怪闭环」一条线收口：`g_dwOldNeedExps` 经验表 + `GetLevelExp` 钳制、`RecalcLevelAbilitys` 三职业成长曲线（战士 DC 下限 1、仅战士有天然 AC 两处 quirk 忠实保留，Delphi 银行家舍入改用 `Math.rint`）、`GetExp/HasLevelUp` 升级循环（一次只扣一档、`MAXUPLEVEL` 封顶、升级即 `IncHealthSpell(2000,2000)` 回满）与 `SM_LEVELUP`；玩家死亡的 1/3 背包掉落惩罚（`nDieScatterBagRate=3`、`DropWide=2`）与 `SM_DELITEMS` 回推；`ReAlive` 原地复活 + `SM_ALIVE`、3 分钟 `MakeGhost` 尸体离场、重登 `HP<=0 → 14`；`TBaseObject.Run` 的 HP/MP 自然回复；GM `@Level`（`CmdChangeLevel`）。同时把 `Ability.defaultPlayer()` 从 W03 的 100HP/DC3-8 PoC 占位改为忠实的建号块（15HP/DC1-2/MaxExp 100），世界难度因此回到原版水平。本次会话（W15）交付 **修理 NPC 最小闭环 + 复活戒指 + 金币**：`TBaseObject.Run` HP=0 分支的复活戒指（`m_boRevival` 按 `RecalcAbilitys` 重算、60s `dwRevivalTime` 冷却、`ItemDamageRevivalRing` 每件扣 1000 耐久且无 break——双戒同耗、归零销毁清槽、SM_DURACHANGE 仅在千位桶变化时下发（银行家舍入 quirk：2500→1500 不发）、攻击者佩戴 Shape 144 `m_boUnRevival` 压制）；修理三件套 `CM_MERCHANTDLGSELECT`（`m_sScriptLable` 状态机，`@repair`/`@s_repair` 回 `SM_SENDUSERREPAIR` 开修理对话框，不引入脚本引擎）、`CM_MERCHANTQUERYREPAIRCOST → SM_SENDREPAIRCOST`（-1 = 不能修）、`CM_USERREPAIRITEM → SM_USERREPAIRITEM_OK(recog=金币,param=Dura,tag=DuraMax)/FAIL`，普通修理 `Dec(DuraMax,(DuraMax-Dura) div 30)` 后回满、特殊修理保持 DuraMax，价格公式 `Round(nPrice div 3 / DuraMax × 磨损)`（**quirk：特殊修理报价 3×截断基数 vs 实扣未截断基数，价格非 3 倍数时差 1-2 金币**，已按原版分别复刻）；金币 `m_nGold` 落库（W14 旧库原位升级默认 0）、`SM_ABILITY` 头部补 recog=m_nGold/param=MakeWord(btJob,99)（此前恒 0 会清掉客户端金币显示）、`SM_GOLDCHANGED`、`MIR2_TEST_GOLD` 登录下限（复刻 boTestServer/nTestGold，默认 0 = no-op）。

## ✅ 当前执行进度（Session Handoff）

| 状态 | 计划任务 | 已交付内容 | 代码位置 / 验收依据 |
| --- | --- | --- | --- |
| ✅ 完成 | W01：Grobal2 常量与 12B codec | `TDefaultMessage` 小端序编解码；302 个 `CM_ / SM_` 常量 | `java-server/protocol/DefaultMessage.java`、`ProtocolConstants.java`；`ProtocolTest` |
| ✅ 完成 | W01：EDcode 6-bit | OLDMODE 6-bit 编解码及消息封装 | `java-server/protocol/SixBitCodec.java`、`MessageCodec.java` |
| ✅ 完成 | W01：DES | DES/ECB/NoPadding + Delphi 零填充兼容封装 | `java-server/protocol/DesCodec.java`；回环测试 |
| ✅ 完成 | W01：GBK 字节语义 | 按 GBK 字节截断角色名，避免中文半字符截断 | `java-server/protocol/ByteStrings.java`；边界测试 |
| ✅ 完成 | W02：登录+选角最小路径中的账号/角色领域模型 | 注册、登录、会话、角色列表、创建、删除 | `java-server/auth`、`java-server/character` |
| 🟡 部分完成 | W01：20 组 golden | 已有 20 组确定性回环向量；尚未接入 Delphi 实际抓包 golden | `java-server/docs/g0-checklist.md` |
| ✅ 完成 | W02：SQLite 数据存储 | AuthService、CharacterService 已支持注入 SQLiteStore；账号/角色数据通过 SQLite 持久化并可重启恢复；JDK 21 CI 全量测试已通过 | `java-server/persistence`；GitHub Actions run `35329322893` |
| ✅ 完成 | 可启动交付基座 | 增加 Main 入口、环境变量配置、首次测试账号、优雅停机、可执行 fat JAR、Dockerfile 与 Compose；JAR 三端口启动冒烟和镜像构建已进入 CI | `java-server/bootstrap`、`Dockerfile`、`compose.yml`；Actions run `35329322893` |
| 🟡 部分完成 | W02：接入骨架（7000/7100/7200） | 已完成三监听器、`#序号+消息头+消息体!` 分帧、连接状态、整数认证码桥接和登录/选服/角色查询建删选字段映射；GAME 首包已按 `**账号/角色/认证码/版本/登录码` 独立解析，并校验认证码与已选角色；Netty 替换、限速及真客户端验证未完成 | `java-server/gate`；`LegacyGateHandlerTest`、`WireMessageCodecTest` |
| 🟡 部分完成 | W03：tick、地图、移动广播 | 单 owner Tick、地图/碰撞、12 格视野、7200 RunLogin、GAME 会话进出、`SM_NEWMAP/LOGON/MAPDESCRIPTION`、`TCharDesc`、移动确认与观察者广播均已接通 | `GameProtocolAdapterTest`、`GameSessionIntegrationTest`；模拟双会话闭环已通过，尚缺真客户端对拍 |
| ✅ 完成（本地闭环） | W03：近战怪、击杀、掉落、拾取与重登存档 | 近战攻击、怪物 AI、经验、掉落/拾取已实现；HP/MP/等级/经验与 46 格背包由 SQLite 原子保存并进图恢复，角色外观字段已落库。真实客户端 `TClientItem` 与对拍仍归并行兼容任务 | `WorldCombatTest`、`GameCombatProtocolTest`、`WorldPersistenceIntegrationTest`；CI run `35427011522` |
| ✅ 完成（本地闭环） | W04：最小物品目录 + `TClientItem` + 背包同步 | 完整 `StdItem`（66 字节 `TStdItem` 全字段）+ `ItemDatabase` 端口 + SQLite `std_items` 启动种子；`MakeIndex` 复刻 `GetItemNumber` 并按持久化高水位接续；拾取满耐久；76 字节 `TClientItem` 编解码；`CM_QUERYBAGITEMS → SM_BAGITEMS`、`SM_ADDITEM` 完整载荷；W03 背包原位升级 | `ClientItemCodecTest`、`GameCombatProtocolTest`（新增 bag-items 用例）、`SqliteStoreTest`（目录种子 + W03 升级）、`WorldPersistenceIntegrationTest`；CI run `35433957340` 全绿（Maven 全量测试、fat JAR 三端口冒烟、Compose 校验、Docker 镜像构建） |
| ✅ 完成 | W05：bot 压测军团（P0 三件套之一 + G0「50 bots」项） | 新增 `java-server/loadtest` Maven 模块：`BotWireClient`（复用 gate 公有 `WireMessageCodec`，`#…!` 帧 + 前缀轮转 + 12B 小端头 + 6bit 体 + GBK）、`Mir2Bot`（登录→建号→进图→走/打/捡/查包→周期重登状态机，`+GOOD/+FAIL` 应答计时）、`BotSwarm`（斜坡启动、实时监测行、PASS/FAIL 判定）、`BotMetrics/BotReport`（计数/分位延迟/错误分类 + 中文 Markdown/CSV）、`LoadtestMain`（embedded 进程内起服 + JVM 堆采样 / remote 对独立进程 / `--prepare-db` 批量建号）；8 个新单测（3 个测试类）；CI 新增 `bot-swarm` job（每 PR 跑 50×2 分钟 embedded） | `BotSwarmEmbeddedTest` 等（本地 70/70）；`java-server/docs/g0-evidence/`（50×5 分钟 embedded + remote-fatjar 双报告，均 0 错误）；`.github/workflows/java-server.yml` |
| ✅ 完成 | W06：traffic recorder / replayer 骨架（**P0 三件套齐活**） | 新增 `java-server/wiretool` 模块（shaded `mir2-wiretool.jar`）：`record` 透明代理（字节透传 + `#…!` 帧/流外噪声分类落盘 `.mrec`，每条连接一个文件，半关闭语义保持一致）、`replay`（客户端帧按录制节奏重发，应答逐字节对拍，差异四分类 + 跳过清单 + `--structural-only`，中文 Markdown/CSV 报告，退出码即结论）、`inspect`（逐帧注解：ident 名反射反查 `ProtocolConstants`、6-bit+GBK 正文、RunLogin 识别且 cert 打码、`--verify` 统计不可解析帧）；dist 通道同步发布三 JAR | 7 个新测试类（录制编解码/分帧/代理/回放/对拍/注解/CLI）；沙箱实跑证据 `java-server/docs/g0-evidence/2026-09-20-wiretool-record-replay-smoke.md`；CI `wiretool-smoke` 门禁 |
| ✅ 完成 | W07：接入层加固——认证码一次性消费 + 断线清理 | `GateSessionRegistry.requireGame` 保持纯校验语义（供进图重试复用同一认证码）；`LegacyGateHandler.serveGame` 在世界真正接纳玩家（`enterPlayerNear` 成功返回）后立即调用 `sessions.remove` 消费认证码，`finally` 块中做幂等兜底清理（覆盖异常路径与正常断线）；同一认证码在被消费或移除后无法再通过 `require`/`select`/`requireGame` 校验，堵住 W06 冒烟暴露的"断线后认证码终身有效、可重放进图"缺口 | `GateSessionRegistry.java`、`LegacyGateHandler.java`；新增 `GateSessionRegistryTest`（4 用例）与 `GameSessionIntegrationTest#certificationIsConsumedOnEntryAndCannotBeReplayedAfterDisconnect`；连接数/频率限制与空闲超时已由 W08 交付；Netty 替换仍未覆盖 |
| ✅ 完成 | W08：接入层连接数/频率限制 + 空闲超时 | `AccessPolicy` 在三网关共享按 IP 统计活跃连接与滑动窗口新连接尝试；连接超限在认证前拒绝，socket 设置可配置读空闲超时；新增配置项与单测 | `java-server/gate/AccessPolicy.java`、`GateServer.java`、`ServerConfig.java`；`AccessPolicyTest` |
| ✅ 完成（本地闭环） | W09：怪物 AI 框架扩展（首批 10 种）+ MonGen 自动刷新 + 周期存档 | ① `MonsterBehavior`（AGGRESSIVE / PASSIVE_FLEE）+ `MonsterTemplate` 目录扩到首批 10 种（鸡/鹿/稻草人/多钩猫/钉耙猫/洞蛆/蝎子/半兽人/半兽勇士/半兽战士，中文与 ASCII 名双向解析），鹿实现 `TChickenDeer` 逃跑 AI（远离最近玩家、永不攻击、直线被堵回退两侧向）；② `WorldEngine.addSpawner` 复刻 `TUserEngine.RegenMonsters`：每 `dwRegenMonstersTime`(200ms) 轮转处理一行 MonGen，`CertList` 语义统计存活数，按行内 respawn 间隔随机重掷格子补足击杀损失，`Mir2Server.spawnMonGen` 从一次性首批生成改为注册自动刷新 spawner；③ 周期存档复刻 `ProcessHumans → SaveHumanRcd`：每玩家按 `MIR2_SAVE_INTERVAL_SECONDS`（默认 600s = Delphi `dwSaveHumanRcdTime` 10 分钟）落库，存储异常不中断 tick；④ `MIR2_MONSTER_KIND` 支持全部 10 种模板名。除鸡/半兽人外的 8 种数值为 TODO(verify) 占位，待 Monster.DB 导入与对拍校准 | `MonsterBehavior.java`、`MonsterTemplate.java`、`WorldEngine.java`（Spawner/monsterFlee/savePlayersPeriodically）、`Mir2Server.java`、`ServerConfig.java`、`deployment.md`；新增 `WorldRegenAndBehaviorTest`（5 用例）+ `ServerConfigTest` 扩展；沙箱内经 ECJ 全模块编译 + world/gate/protocol/auth/character/bootstrap-config 测试全绿（persistence/loadtest 需 sqlite-jdbc，由 CI 验证） |
| ✅ 完成（本地闭环） | W10：门与传送点（`.map` 门锚点 + MapInfo.txt 多图与连接点） | ① 门：`Mir2MapLoader` 解析 `btDoorIndex` `$80` 锚点（`&$7F>0` 才有门），同 index ±10 半径共享 `TDoorStatus`（`DoorInfo`）；服务端如实复刻 "关门不挡路"（`CanWalk*` 不查门，拦截在原客户端本地数据）；`CM_OPENDOOR` 命中锚点即开、±12 广播 `SM_OPENDOOR_OK(x,y)`，重复/非锚点静默；`closeDoorsPeriodically` 复刻 `ProcessMapDoor`——500ms 扫拍、开满 5 秒广播 `SM_CLOSEDOOR`（每条链接锚点一条广播，客户端按 index 扩散，快照见 `MapUnit.pas`）；② 传送点：`MapInfoLoader` 复刻 `QMapInfo`（`loadmapinfo` 从 `MapInfo/` 子目录内嵌、`;` 注释行、`[id|alias desc idx flags]` 条目、`src srcX srcY -> dst dstX dstY` 路线行走 Delphi `GetValidStr3` 分隔符链，`Str_ToInt(x,0)` 容错）；`Mir2Server` 以 `MIR2_MAPINFO_FILE` 接管多图加载（缺 `.map` 文件告警跳过= `AddMapInfo` 语义，`MIR2_MAP_ID` 指定出生图仍需 fail-fast），路由经 `WorldEngine.addRoute` 装入源图 gate 索引（两端地图缺一即丢弃=`AddMapRoute` 失败语义）；③ 走/跑落格触发 `EnterAnotherMap`：成功 → `SM_CLEAROBJECTS→SM_CHANGEMAP(recog=self,param=x,tag=y,series=0[DayBright占位],body=地图名)→SM_MAPDESCRIPTION` + 双图观察者 appear/disappear + 传送者全量视野重灌；目标图格不可走/目标图缺失 → 整步回滚（`WalkTo` 语义，新 `MoveRejection.GATE_TARGET_UNPASSABLE`）；门 ±1 关门压制连接点（`ArroundDoorOpened`）。城堡门差异分支、跨服切换、MapFlag 旗标、昼夜 `DayBright` 显式留在后续切片 | `DoorInfo.java`、`TeleportRoute.java`、`MapInfoLoader.java`、`GameMap.java`（门/路由索引）、`Mir2MapLoader.java`、`WorldEngine.java`（openDoor/closeDoorsPeriodically/addRoute/teleportPlayer）、`GameProtocolAdapter.java`、`Mir2Server.java`、`ServerConfig.java`（`MIR2_MAPINFO_FILE`）；新增 `WorldDoorAndTeleportTest`（8 用例）、`MapInfoLoaderTest`（5 用例）、`Mir2MapLoaderTest` 门解析 2 用例、`GameProtocolAdapterTest` 2 用例、`ServerConfigTest` 扩展；沙箱无 javac（jdk4py 为 JRE）→ 全量编译与测试交 CI（同 W09 前后一致的验证路径） |
| ✅ 完成（本地闭环 + 实跑 E2E） | W12：物品切片——装备穿脱 + 使用 + 丢弃（P2「物品」收口） | ① `EquipmentSlot` 复刻 `U_DRESS..U_CHARM` 13 槽（`THumanUseItems=array[0..12]`，1.50 版 belt/boots/gem 三槽在 `CheckUserItems` 中被注释掉 → `accepts()` 恒 false，忠实复刻）、`Equipment` 不可变容器 + `EquipmentBonus` 累加器（`ApplyItemParameters` 按 `ItemType`=WEAPON/ARMOR/ACCESSORY 分支，含 StdMode 19/20/21/23/24/52/53/54/62/63/64 饰品表，DC/MC/SC 在 case 之外统一累加）；② `EquipRequirement` 复刻 `CheckTakeOnItems`：StdMode 10/11 性别锁、手持槽查 `MaxHandWeight` 其余查 `MaxWearWeight`（`GetUserItemWeitht` 排除目标槽与两手槽的 quirk 一并复刻）、`Need` 0/1/2/3/10/11/12/13 等级与职业门槛（4/40/41 转生与城堡类记 `TODO(verify)` 拒绝而非放行）；③ `WorldEngine` 新增 `equip/unequip/useItem/dropItem/equipment/spawnGroundItem`，`RecalcAbilitys` 以 `m_Abil`(base)/`m_WAbil`(working) 双份能力复刻，穿脱失败整体回滚，`GetFeature` 按穿戴 Shape 重算外观并向观察者广播；④ Gate 侧四条 `CM_` 解码（注意 `CM_TAKEONITEM` 槽位在 `param`、MakeIndex 在 `recog`）与全部 `SM_` 应答，新增 `AbilityCodec`（50 字节 packed `TAbility`）与 `ClientItemCodec.encodeWornSet`（`slot + '/' + TClientItem + '/'`）；⑤ **quirk 忠实复刻**：`ClientTakeOffItems` 成功路径 `n10` 保持 0，而出口判定是 `if n10 <= 0 then SendDefMessage(SM_TAKEOFF_FAIL...)`，故每次成功取下都会尾随一条 `SM_TAKEOFF_FAIL(recog=0)`；⑥ 持久化新增 `character_equipment` 表，`itemMakeIndexHighWater` 改为背包与装备取最大值（否则重登会重发 MakeIndex）；⑦ `MapFlags` 补 `NODRUG`/`NOTHROWITEM` 两旗标供使用/丢弃门禁 | `EquipmentSlot.java`、`Equipment.java`、`EquipmentBonus.java`、`EquipRequirement.java`、`StdItem.java`(itemType/packed 范围访问器)、`WorldEngine.java`、`WorldEvent.java`(13 个新事件)、`PlayerState.java`、`MapFlags.java`、`MapInfoLoader.java`、`GameProtocolAdapter.java`、`AbilityCodec.java`、`ClientItemCodec.java`、`SqliteStore.java`；新增 `WorldEquipmentTest`(7 用例) + `GameItemProtocolTest`(5 用例) + `SqliteStoreTest`(+1 装备往返与旧库升级)；**本地全量 165/165 绿**（ECJ 3.46 + JUnit 5 控制台启动器，见下方工具链补记），并对真实服务端跑通 E2E：登录→选人→进图→`CM_PICKUP`→`CM_TAKEONITEM(SM_TAKEON_OK)`→`CM_TAKEOFFITEM(SM_TAKEOFF_OK + 尾随 FAIL quirk)`→槽位拒绝→`CM_DROPITEM(SM_DROPITEM_SUCCESS)` 全部符合预期 |
| ✅ 完成（本地闭环） | W14：等级提升 + 死亡/复活闭环（P2「打怪闭环」收口） | ① `LevelExperience` 复刻 `g_dwOldNeedExps`（M2Share.pas:2216，1..500，51 起恒 4,000,000,000 —— 溢出 signed int，故全表用 `long`）与 `GetLevelExp` 的 `> MAXLEVEL(500)` 取末项钳制；② `LevelAbilities` 复刻 `RecalcLevelAbilitys`（ObjBase.pas:1869）三职业曲线：战士 `14+Round((L/4+4.5+L/20)*L)` HP / `11+Round(L*3.5)` MP / `DC=MakeLong(_MAX(L div 5-1,1),_MAX(1,L div 5))`（**战士最小值下限为 1 而非 0，是与法/道不同的 quirk**）/ `AC=MakeLong(0,L div 7)`（仅战士有天然 AC），法师 `15/1.8` + 最深蓝量，道士 `6/2.5`；Delphi `Round` 是**银行家舍入**，Java 侧统一用 `Math.rint` 而非 `Math.round`；③ `Ability` 增 `maxExperience`（`TAbility.MaxExp`）与 `consumeLevelExperience()`，复刻 `GetExp` 的 `Dec(Exp, MaxExp); Inc(Level)`（一次只扣一档）与 `MAXUPLEVEL=65535` 封顶后仍继续扣经验的行为；`HasLevelUp` 末尾的 `IncHealthSpell(2000,2000)` 一并复刻（升级即回满）；④ **`Ability.defaultPlayer()` 由 PoC 的 100HP/DC3-8 改为忠实的 `UsrEngn.pas:503` 建号块**（Level 1、HP/MP 15、DC 1-2、MaxExp 100），连带 quirk：升到 2 级时 DC 反而从建号字面量 1-2 收窄为曲线的 1-1；⑤ 玩家死亡：`TBaseObject.Die` 打死亡时戳 + `TPlayObject.ScatterBagItems` 背包惩罚（`g_Config.nDieScatterBagRate=3` → 每件 1/3 掉落、`DropWide=2` 落点、地图 `NODROPITEM` 整体豁免），掉落清单经 `RM_SENDDELITEMLIST → SM_DELITEMS`（`<名>/<MakeIndex>/` 串）回推客户端；⑥ 复活：`WorldEngine.revive` 复刻 `ReAlive + CmdReAlive` 尾部（原地站起、HP 回满、`SM_ALIVE` 广播 8 字节 `TCharDesc`），`MakeGhost` 复刻 `dwMakeGhostTime=3` 分钟后尸体化鬼（离场），重登复刻 `UsrEngn.pas:600` 的 `HP<=0 → HP:=14`；⑦ HP/MP 自然回复复刻 `TBaseObject.Run`：计数器按 `elapsed div 20` 推进，`nHealthFillTime=300`(6s) 回 `MaxHP div 75 + 1`、`nSpellFillTime=800`(16s) 回 `MaxMP div 18 + 1`，死亡期间整段跳过；⑧ GM `@Level` 命令语义 `WorldEngine.setLevel`（`CmdChangeLevel`：直接置级 + `HasLevelUp(1)`）；⑨ 职业打通 `Character.job → SelectedCharacter → enterPlayer(job)`，`AbilityCodec` 补上此前恒 0 的 `MaxExp` 字段（无符号 DWord，4e9 平台期按位写入）；bot 侧接 `SM_LEVELUP`/`SM_ALIVE` 计数，死亡不再终止 bot 运行 | `LevelExperience.java`、`LevelAbilities.java`（新增）、`Ability.java`、`WorldEvent.java`（+`LevelUp`/`ObjectRevived`/`ItemsRemoved`）、`WorldEngine.java`（awardExperience/applyLevelUp/scatterBagItems/revivePlayer/regenerateHealthAndSpell/makeGhostsOfExpiredCorpses/setLevel）、`GameProtocolAdapter.java`、`AbilityCodec.java`、`GateSessionRegistry.java`、`LegacyGateHandler.java`、`SqliteStore.java`（建号基线不再硬编码）、`Mir2Bot.java`/`BotMetrics.java`/`BotReport.java`；新增 `LevelTablesTest`(6) + `WorldLevelAndDeathTest`(6) + `GameLevelAndRevivalProtocolTest`(5)，**本地全量 184/184 绿**（166 基线 + 18），8 bot × 45s embedded swarm PASS（0 错误，观测到 2 次死亡并成功重登） |
| ✅ 完成（本地闭环） | W15：修理 NPC 最小闭环 + 复活戒指 + 金币（P2 收官周的主动切片） | ① **复活戒指**（`TBaseObject.Run` ObjBase.pas:3750 的 HP=0 分支）：`m_boRevival` 在 `recalculateAbilities` 中按穿戴集重算（weapon/righthand/dress 三槽走 `AniCount`、其余槽走 `Shape`，取值均限 114/160/161/162，且跳过 0 耐久件；**dress 进 flag 分支却从不被消耗**的原版不对称已照搬）；触发条件依次为攻击者 `m_boUnRevival`（佩戴 Shape 144 的玩家攻击者压制复活，怪物不适用）→ `m_boRevival` → `GetTickCount - m_dwRevivalTick > dwRevivalTime(60s)`（严格大于；`m_dwRevivalTick` 初值 0 意味着世界时钟前 60s 内不触发，与 Delphi 的 OS 时钟同构）；`ItemDamageRevivalRing` 逐槽扣 1000 耐久**且无 break**（两枚戒指一次复活各扣 1000），归零即 `SM_DELITEMS` 销毁清槽 + `RecalcAbilitys`，`SM_DURACHANGE` 仅当 `Round(旧/1000) ≠ Round(新/1000)` 才发（**银行家舍入 quirk：2500→1500 两边都是 2，不发**）；成功后 `HP:=MaxHP` + `SM_HEALTHSPELLCHANGED` + 绿字 `g_sRevivalRecoverMsg`「复活戒指生效，体力恢复.」；② **修理**：`CM_MERCHANTDLGSELECT(1011)` 复刻 `TMerchant.UserSelect` 的 `m_sScriptLable` 状态机——`@` 前缀才处理、`@repair`/`@s_repair`（`CompareText` 不分大小写）回 `SM_SENDUSERREPAIR(recog=回显商家 id)` 让客户端打开修理对话框，其余 label 仅记录（无脚本引擎，NPC 对象与 <15 格距离校验留给 NPC 切片）；`CM_MERCHANTQUERYREPAIRCOST(1024)` → `SM_SENDREPAIRCOST`（recog=报价，-1 = 不能修「???? 金币」）；`CM_USERREPAIRITEM(1023)`（MakeIndex 跨 param/tag 双字打包）→ `SM_USERREPAIRITEM_OK(recog=余额金币,param=Dura,tag=DuraMax)` / `SM_USERREPAIRITEM_FAIL(recog=0)`；普通修理 `Dec(DuraMax,(DuraMax-Dura) div 30)` 后 `Dura:=DuraMax`、特殊修理（label=`'@s_repair'`，Delphi `=` 大小写敏感——`'@S_REPAIR'` 开对话框但按普通修理计费的原版行为已复刻）保持 DuraMax 仅回满；价格 `Round(nPrice div 3 / DuraMax × (DuraMax-Dura))`（整除截断在前、银行家舍入在后），**quirk：特殊修理报价 = 3×截断基数而实扣 = 未截断基数（`nPrice×3` 后再 `div 3` 抵消），价格非 3 倍数时二者差 1-2 金币，两条公式均按原版复刻**；StdMode 43（宝石）拒修、`DuraMax=Dura` 拒修、背包无此 MakeIndex+名称（`CompareText` 不分大小写）时**静默无应答**（Delphi 直接 Exit）；`GotoLable` 脚本后续与城堡税金留待 NPC/城堡切片；③ **金币**：`PlayerState.gold`（`m_nGold`，0..10,000,000 = `nHumanMaxGold`）持久化到 `character_state.gold`（W14 旧库 `ensureColumn` 原位升级默认 0）；`SM_ABILITY` 头部补上 Delphi 的 `recog=m_nGold`、`param=MakeWord(btJob,99)`（此前恒 0，每发一次就会清零客户端金币显示）；`RM_GOLDCHANGED → SM_GOLDCHANGED` 事件接通；`MIR2_TEST_GOLD` 复刻 `UserLogon` 的 `boTestServer` 金币下限（默认 0 = no-op，抬升时在 `MapEntered` 后补发 `SM_GOLDCHANGED`，比 Delphi 的静默做法多做一条通知以便联调观测）；④ 目录新增 `复活戒指` 占位模板（Shape 114 / StdMode 22 / DuraMax 5000，`TODO(verify)` 待 StdItems.DB）；bot 指标接 `SM_GOLDCHANGED`、修理三应答与复活绿字（`RING_REVIVALS`） | `WorldEngine.java`（tryRevivalRing/consumeRevivalRings/equipmentGrantsRevival/preventsRevival/selectMerchantLabel/queryRepairCost/repairItem/repairQuote/repairCharge）、`WorldEvent.java`（+GoldChanged/MerchantRepairDialog/RepairCostResolved/ItemRepaired/RepairRejected，`AbilityChanged` 增 gold/job）、`PlayerState.java`（gold/MAX_GOLD/withGold）、`StdItems.java`（revivalRing）、`SqliteStore.java`（gold 列 + 旧库升级）、`GameProtocolAdapter.java`（CM×3/SM×5、`unpackMakeIndex`、`sendAbility` 头部）、`ServerConfig.java`/`Mir2Server.java`（`MIR2_TEST_GOLD`）、`Mir2Bot.java`/`BotMetrics.java`；新增 `WorldRevivalRingTest`(8) + `WorldRepairTest`(6) + `GameRepairProtocolTest`(4)，`SqliteStoreTest`(+1) 与 `ServerConfigTest`(+1) 扩展；**本地全量 204/204 绿**（基线 184 → +20） |

### 当前下一步（Next Session）

> **下次开发起点（W16）：**W15 已交付修理 NPC 最小闭环 + 复活戒指 + 金币（P2 表内除影子对拍 harness 外的主动切片全部完成）。**G3 门（W15 末，P2 收口）现状评估：未正式过门**——「掉落/背包/装备/存档全链路可用」已达成（移动/攻击/掉落/背包/装备/耐久/修理/等级/死亡/复活/复活戒指/金币全链路），但 ①「3 名老玩家盲测手感无差异」②「10 种怪 AI 对拍一致」③「影子对拍工具上线」三项均未完成，且三者共同的前置是**真实客户端对拍环境（Windows + mir2.exe）**——这仍是全项目最大缺口，环境就绪第一时间用 wiretool `record` 录 Delphi 全链路 golden。**优先建议**：① **影子对拍 harness**（P2 任务表中唯一未启动项，2 人日，G3 硬性要求）：同一操作流驱动双服（Delphi / Java）并 diff 状态快照，可先以 Java 双实例 + wiretool 回放做骨架，Delphi 侧接环境后即插即用；② 待需求方提供真实 `StdItems.DB`/导出数据后，替换 W04/W12/W15 的全部 `TODO(verify)` 物品数值（复活戒指占位模板同此）；③ **技能/魔法切片**（P3 首块：`CM_SPELL`、`TUserMagic`、`SM_MAGICFIRE`、`Magic.pas` 59 技能框架）——**红线仍在，须需求方明确解除后方可动工**；④ W14 遗留 `TODO(verify)`：`DropUseItems`（死亡掉装备）依赖 `StdItem.Reserved` 位与 PK 等级模型，红名 `boDieRedScatterBagAll` 全掉分支同理。以下为历史条目：W11 交付昼夜循环/MapFlags/聊天路由；W12 交付物品切片（装备穿脱 + 使用 + 丢弃）并顺手修复了 W11 遗留的 master 编译中断；W13 交付耐久战斗闭环（命中 `Random(5)+2` 损武器、被击 `Random(10)+5` 损衣服 + 1/8 概率损各槽、`SM_DURACHANGE`、归零即销毁清槽）；W14 交付等级提升 + 死亡/复活闭环（P2「打怪闭环」收口）。可并行项不变：bot-swarm 跨图连接点走图/更高并发、城堡门差异分支、Netty 接入替换。红线提醒：~~57 种怪物、技能/魔法、NPC 脚本不得在对拍完成前提前扩展~~ → W12 仅解除了装备穿脱/使用（`CM_EAT`）/丢弃（`CM_DROPITEM`）三项；W15 的修理走 `m_sScriptLable` 协议状态机而非脚本引擎，未触碰 Market_Def 红线；**57 种怪物、技能/魔法、NPC 脚本引擎的红线依然有效**。除鸡/半兽人外 8 种怪物模板数值、W12 装备属性与 W15 复活戒指数值均为 TODO(verify) 占位，Monster.DB/StdItems.DB 导入前不视为权威。

**W08 已交付（2026-09-20）：**`GateServer` 增加三网关共享的 `AccessPolicy`，按 IP 原子限制活跃连接数与滑动窗口新连接频率；接入成功的 socket 设置可配置读空闲超时，超限连接在认证前拒绝。新增 `MIR2_MAX_CONNECTIONS_PER_IP`、`MIR2_CONNECTION_ATTEMPTS_PER_WINDOW`、`MIR2_CONNECTION_ATTEMPT_WINDOW_SECONDS` 与 `MIR2_IDLE_TIMEOUT_SECONDS` 配置及单测；默认值兼容 50 bot 压测。真实 Delphi `IsConnLimited` 阈值仍待 golden/部署数据校准。

1. ✅ **接通 7200 首包认证：**已按 `Client/ClMain.pas:SendRunLogin` 的 `**账号/角色/认证码/客户端版本/RUNLOGINCODE` 格式增加独立 headerless 首包解析；`GateSessionRegistry` 会在 `CM_SELCHR` 时记录角色，并在 GAME 登录时联合校验认证码、账号和已选角色。非法首包返回 `SM_STARTFAIL` 并关闭连接。
2. ✅ **实现移动协议适配器：**已将 `CM_TURN / CM_WALK / CM_RUN` 的 `Recog` 打包坐标与 `Tag` 方向转换为 `WorldEngine.turn/move`；成功/拒绝事件转换为 `+GOOD/<tick>` / `+FAIL/<tick>`，观察者事件转换为 `SM_WALK / SM_RUN / SM_TURN / SM_DISAPPEAR`。
3. ✅ **补齐进图最小消息并接线会话：**`MapEntered` 已转换为 `SM_NEWMAP + SM_LOGON + SM_MAPDESCRIPTION`，实现 8 字节小端 `TCharDesc` 与 16 字节 `TMessageBodyWL`；认证 GAME socket 会加入/移出 `WorldEngine`，支持可配置出生点和邻近空位选择。双模拟会话已覆盖“进入→互相出现→走/跑→离开视野→断线清理”。
4. **真实客户端对拍：**用真 `mir2.exe` 和 Delphi 抓包确认 RunLogin、`+GOOD/+FAIL`、`SM_*` 字段及应答顺序，重点核对日夜亮度、裸装 Feature、角色名/颜色附加体、完整 `SM_LOGON` 后续序列，以及 **拾取 → `SM_ADDITEM` 的 76 字节 `TClientItem` 载荷** 与 **`SM_LOGON` 后客户端自发 `CM_QUERYBAGITEMS` → `SM_BAGITEMS` 的应答顺序与字段**；差异补进 golden，同时保持 Maven/JDK 21、fat JAR 冒烟和 Docker 构建全绿。
5. ✅ **近战战斗切片：**已实现 `Ability`（HP/MP/DC/AC/等级/经验）、`AttackKind`、单格正面攻击与 Delphi 共用的 `CM_HIT` 动作间隔、伤害 = 攻击随机值 − 防御随机值；`MonsterTemplate`（鸡 / 半兽人）带视野索敌、追击、独立攻击间隔与尸体超时清理；`ItemDrop` / `GroundItem` 实现「N 分之一」掉落、地面物品视野同步与 `CM_PICKUP` 拾取；协议侧新增 `SM_HIT/HEAVYHIT/BIGHIT`、`SM_STRUCK`（TMessageBodyWL）、`SM_HEALTHSPELLCHANGED`、`SM_DEATH`（TCharDesc）、`SM_WINEXP`、`SM_ITEMSHOW/ITEMHIDE/ADDITEM`。
6. ✅ **战斗与背包状态落库：**新增 `PlayerStateStore` 端口及 SQLite 实现，将完整 `Ability` 与最多 46 个 `BackpackItem` 在伤害、经验、拾取和离场时保存；进图在 `MapEntered` 前按角色 UUID 恢复。`character_state` 与 `character_inventory` 事务更新，角色删除级联清理；旧 W02 `characters` 表自动增加性别/发型/衣服/武器外观列并回填默认状态。`CM_NEWCHR` 的 hair/sex 已贯通到 `SM_LOGON Feature`。`WorldPersistenceIntegrationTest` 覆盖 Store/World 双重启后的 HP、经验、背包恢复。
7. ✅ **最小物品目录与背包同步（W04）：**`StdItem`/`ItemDatabase`/`StdItems` 落地最小目录（鸡肉/鹿肉/木剑/金创药(小量)）并以 SQLite `std_items` 持久化；`MakeIndex` 复刻 `GetItemNumber` 语义并从持久化高水位接续；`ClientItemCodec` 输出 76 字节小端 `TClientItem`；`CM_QUERYBAGITEMS → SM_BAGITEMS` 与 `SM_ADDITEM` 完整载荷接通；W03 背包行原位升级并在进图时重编号。物品数值仍为 TODO(verify) 占位，待真实 StdItems 数据导入。
8. **真实客户端对拍（可并行）：**用真 `mir2.exe` 和 Delphi 抓包确认 RunLogin、`+GOOD/+FAIL`、进图与战斗 `SM_*` 的字段及应答顺序，差异补进 golden。
9. ✅ **认证码 GAME 登录消费即失效 + 断线清理（W07）：**`GateSessionRegistry` 的认证码在世界真正接纳玩家后立即被 `LegacyGateHandler.serveGame` 消费，断线（正常或异常）时同步幂等清理；进图失败重试（快速重登的 `leavePlayer` 竞态）仍可复用同一认证码。接入层剩余的消息大小限制和 Netty 替换仍需完成，但不阻塞上述 world 协议闭环的 PoC 顺序。
10. 57 种怪物、技能/魔法、远程与群攻仍不得提前扩展，等存档闭环与对拍完成后再按 P2 计划推进。

### 工具链 Runbook：GitHub Actions 编译打包 → fetch 产物 → jdk4py 启动（Agent 备忘，2026-09-20 实测）

> **场景**：受限沙箱（Arena）内无 `java`/`mvn`，需要拿到可运行的服务端 fat JAR 并启动。**全程实测通过**（CI run `35480880626` → dist 分支 `47b1f8b` → jdk4py Temurin 21.0.8 → 三端口 17000/17100/17200 全部 UP）。


#### 沙箱内本地编译 + 跑测（2026-09-21 实测打通，强烈建议后续会话复用）

> 此前几个会话都判定「沙箱无法编译 Java、只能交 CI」，导致 W11 在 CI 红灯时被合入而没人及时发现。**现已找到完全本地化的方案**，编译 + 全量单测约 25 秒，务必在提交前先本地跑一遍。

1. **JRE**：`pip install jdk4py`（PyPI 可达），得到 `JAVA_HOME=<venv>/lib/python3.11/site-packages/jdk4py/java-runtime`。它只有 JRE（无 `jdk.compiler`），**不能** `javac`，但能跑任何 jar。
2. **编译器**：用 **ECJ**（Eclipse Compiler for Java，单 jar、纯 Java 实现、支持 `-21`）。Maven Central 拉不到，但 GitHub 上有人把 Maven 仓库整个提交进了仓库，用 blobs API 取：
   - `gh api "repos/NorbertXYZ/eclipse-maven/contents/org/eclipse/jdt/ecj/3.46.100/ecj-3.46.100.jar" --jq .sha`
   - `gh api "repos/NorbertXYZ/eclipse-maven/git/blobs/<sha>" -H "Accept: application/vnd.github.raw" > ecj.jar`
   - 用法：`java -jar ecj.jar -21 -nowarn -proc:none -cp <deps> -d <out> @sources.txt`
3. **测试框架**：同法取 JUnit 5 全套（`junit-jupiter-api/engine/params`、`junit-platform-commons/engine/launcher`、`opentest4j`、`apiguardian-api`），例如镜像仓库 `GurjarMohit/Electronix-World`、`MichaelSorunke/thinkon-skills-check`。**注意**：有些仓库的 jar 是 Git LFS 指针（拉下来只有 ~130 字节的 `version https://git-lfs...` 文本），需换一个仓库；先用 `contents` API 看 `size` 字段筛选。
4. **SQLite 驱动**（persistence/bootstrap/loadtest 测试需要）：`org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar`（14MB），在若干 Minecraft 服务端仓库的 `libraries/` 目录里有非 LFS 的副本。
5. **跑测**：JUnit 5 控制台启动器那个 standalone jar 不好找，直接写 10 行 `Launcher` + `SummaryGeneratingListener` 的 `RunTests.java`，用 `selectClasspathRoots` 扫 test-classes 目录即可。
6. **实测结果**：全 9 模块 82 个主源文件 + 全部测试类编译通过，**165/165 测试全绿**，耗时 ~25 秒。


#### 网络事实（决定路径选择）

| 目的地 | 可达性 | 影响 |
| --- | --- | --- |
| `github.com`（git push/fetch、gh api、api.github.com）、PyPI、`codeload.github.com` | ✅ 可达 | CI 触发、git 拉产物、装 jdk4py 都可行 |
| `repo.maven.apache.org`（Maven Central）、`dlcdn.apache.org`、`objects.githubusercontent.com`、`raw.githubusercontent.com` | ❌ TLS 握手即断 | Maven 直连不可行；**但见下方「沙箱内本地编译 + 跑测」** |
| `api.github.com` 的 **git blobs API** | ✅ 可达 | **关键发现**：`gh api repos/{owner}/{repo}/git/blobs/{sha} -H 'Accept: application/vnd.github.raw'` 可以拉任意仓库里的二进制文件（jar 也行），绕开被墙的 raw/release CDN |
| `productionresultssa*.blob.core.windows.net`（Actions artifact 存储）、`objects.githubusercontent.com`（Release 资产）、`raw.githubusercontent.com` | ❌ TLS 握手即断 | **`gh run download` / Release 下载在沙箱内不可用**，产物必须走 git 通道（`dist` 分支） |

#### Step 1 · 让 GitHub Actions 编译打包（两条通道）

- **主流水线** `.github/workflows/java-server.yml`：push/PR 触发 `mvn verify` 全量测试，并上传 artifact `mir2-server-jar`（即 `java-server/bootstrap/target/mir2-server.jar`，保留 7 天）。**仅在 artifact 存储可达的环境**（如本地开发机）可直接下载：
  ```bash
  gh run list --branch <branch> --limit 3            # 找到成功的 run id
  gh run download <run-id> -n mir2-server-jar -D /tmp/mir2-dist
  ```
- **沙箱通道** `.github/workflows/java-server-dist.yml`（本 session 新增）：`java-server/**` 或该文件变更时 push 自动触发（`workflow_dispatch` 需文件先合入默认分支 master 才可用，功能分支上走 push 触发即可）。job 执行 `mvn -pl bootstrap -am -DskipTests package`，随后把 fat JAR + sha256 + manifest **force-push 到 `dist` 孤儿分支**（每次全量覆盖，无历史膨胀）。产物清单：`mir2-server.jar`、`mir2-server.jar.sha256`、`dist-manifest.txt`（记录源提交/分支/构建 run）。

#### Step 2 · fetch 打好的包（沙箱实测命令）

```bash
cd /home/user/MIR2
# 本仓库为单分支克隆（remote.origin.fetch 仅 master），必须用显式 refspec 才会建 origin/dist 跟踪引用
git fetch origin +refs/heads/dist:refs/remotes/origin/dist --depth=1
mkdir -p /tmp/mir2-dist
git show origin/dist:mir2-server.jar        > /tmp/mir2-dist/mir2-server.jar
git show origin/dist:mir2-server.jar.sha256 > /tmp/mir2-dist/mir2-server.jar.sha256
git show origin/dist:dist-manifest.txt       # 校对源提交 / 构建 run
echo "$(cat /tmp/mir2-dist/mir2-server.jar.sha256)  mir2-server.jar" \
  | (cd /tmp/mir2-dist && sha256sum -c -)
```

#### Step 3 · PyPI 安装 jdk4py 取得 JDK 21

```bash
python3 -m venv /tmp/jdk4py-venv                    # 系统 Python 受 PEP 668 限制，直接 pip install 会被拒
/tmp/jdk4py-venv/bin/pip install jdk4py==21.0.8.2   # Temurin 21.0.8 LTS，匹配 maven.compiler.release=21
JAVA=$(/tmp/jdk4py-venv/bin/python -c 'import jdk4py; print(jdk4py.JAVA)')
# jdk4py.JAVA     → <venv>/lib/python3.x/site-packages/jdk4py/java-runtime/bin/java
# jdk4py.JAVA_HOME → 对应 JAVA_HOME（需要 javac/jar 等全套工具时用）
```

#### Step 4 · java 命令启动 + 三端口冒烟

```bash
cd /tmp/mir2-dist
MIR2_DATABASE=/tmp/mir2-dist/mir2.db \
MIR2_LOGIN_PORT=17000 MIR2_SELECT_PORT=17100 MIR2_GAME_PORT=17200 \
MIR2_BOOTSTRAP_USER=smoke MIR2_BOOTSTRAP_PASSWORD=smoke-password \
"$JAVA" -jar mir2-server.jar &
for i in $(seq 1 30); do
  (echo >/dev/tcp/127.0.0.1/17000) 2>/dev/null && (echo >/dev/tcp/127.0.0.1/17100) 2>/dev/null \
    && (echo >/dev/tcp/127.0.0.1/17200) 2>/dev/null && { echo "ALL 3 PORTS UP"; break; }
  sleep 1
done
```

- 端口默认值 7000/7100/7200（`GatePorts.DEFAULT_*`），上例显式改为 17xxx 避免与本地其它服务冲突；启动成功日志为 `MIR2 Java server started: login=…, select=…, game=…`，并自动创建 `MIR2_BOOTSTRAP_USER` 测试账号。
- 其余可用环境变量：`MIR2_MAP_FILE/MIR2_MAP_ID`、`MIR2_MONSTER_COUNT/MIR2_MONSTER_KIND`、`MIR2_SPAWN_X/Y`、`MIR2_WORLD_TICK_MS`、`MIR2_SERVER_NAME`、`MIR2_ADVERTISED_HOST`（见 `bootstrap/ServerConfig.java`）。
- **长驻注意**：Arena 会话内要让服务器持续运行请用 start_process 工具；普通 bash 调用超时会连同后台子进程一起被杀（本 session 实测）。

### W10 门与传送点交接明细（2026-09-20）

#### Delphi 事实依据（本切片语义均来自源码核对，非推测）

| 主题 | 依据 | 结论 |
|---|---|---|
| 门解析 | `Envir.pas:LoadMapData` | `btDoorIndex & $80 ≠ 0` 且 `& $7F > 0` 才建 `TDoorInfo`；±10 切比雪夫半径内同 index 共享一个 `TDoorStatus`（`nRefCount` 计数链） |
| 开门 | `ObjBase.pas:ClientOpenDoor` / `UsrEngn.OpenDoor` | `CM_OPENDOOR(1002)`，`nParam2=x / nParam3=y`；`GetDoor` 只命中锚点格；`!boOpened && !bo01` 才执行；`dwOpenTick=now`，±12 玩家收到 `SM_OPENDOOR_OK(612)`（recog=0, param=x, tag=y, 空体），客户端 `TMap.OpenDoor` 再按 index 在 ±10 窗口内扩散整组门 |
| 关门 | `UsrEngn.ProcessMapDoor`（`dwProcessMapDoorTick` 500ms） | 开满 5 秒（`GetTickCount - dwOpenTick > 5000`）自动关，广播 `SM_CLOSEDOOR(614)`；Delphi 只广播**链接中第一条门记录**的锚点——客户端 `TMap.CloseDoor` 的不对称窗口（`cx-8..cx+10`）负责补齐，复刻此行为并注释 |
| 关门不挡路 | `Envir.CanWalk/CanWalkOfItem/CanWalkEx` | 服务端行走校验**不查门**；关门拦截是客户端 `TMap.CanMove` 用 `btDoorOffset & $80` 做的本地行为——Java 側忠实复刻为 quirk，不做「服务端关门点位补拦」 |
| 路线解析 | `LocalDB.pas:QMapInfo` | `MapInfo.txt`：`;` 整行注释、`loadmapinfo <file>` 从 `<dir>/MapInfo/` 子目录内嵌、`[id desc idx flags]` 条目（可选 `[id|alias desc]` 别名，本切片把 alias 解释为 `.map` 文件名覆盖——Delphi 中该分支赋值的 `sNewName` 是**死变量**）、其余非空行为路线；字段级分隔符链与 `Str_ToInt(x,0)` 容错已逐字符复刻 |
| 路线注册 | `Envir.AddMapRoute` | 两端地图都存在才链接，否则整行丢弃——`WorldEngine.addRoute` 同样返回 false |
| 触发 | `ObjBase.Walk`（`RunTo`→`Walk(RM_RUN)`） | 走/跑终点格有 `OS_GATEOBJECT` 且 `m_btRaceServer=RC_PLAYOBJECT` 且 `ArroundDoorOpened(当前格)`（±1 内无未开门）才触发 `EnterAnotherMap`；怪物不触发 |
| 换图 | `ObjBase.EnterAnotherMap` | 目标图/格不可用 → 整步回滚（`WalkTo` 删位还原）；成功 → `SM_CLEAROBJECTS(633)`→`SM_CHANGEMAP(634)`（recog=self, param=x, tag=y, series=DayBright, body=**地图名**）→ 客户端 `g_boMapMoving` 路径重建视野 |

#### 已落地代码

- `world/DoorInfo.java`（锚点 + `DoorStatus` 共享工厂）、`world/TeleportRoute.java`、`world/GameMap.java`（门/路由索引、`aroundDoorOpened`、`withTitle`——`SM_MAPDESCRIPTION` 发的是 MapInfo 描述而非 `.map` 头标题，见 `SendMapDescription`）、`world/Mir2MapLoader.java`（`btDoorIndex` 解析）、`world/MapInfoLoader.java`（include/注释/条目/路线全量解析 + diagnostics）。
- `world/WorldEngine.java`：`openDoor`（Delphi 静默语义）、`closeDoorsPeriodically`（500ms/5s 常量内联）、`addRoute`、`movePlayer` gate 分支 + `teleportPlayer`（完整事件扇出：MoveAccepted→旧图 Observer disappear→`PlayerMapChanged`→新图 appear + 传送者全量视野与物品重灌）、新 `MoveRejection.GATE_TARGET_UNPASSABLE`。
- `world/WorldEvent.java`：`DoorOpened`/`DoorClosed`/`PlayerMapChanged` 三种事件。
- `gate/GameProtocolAdapter.java`：`CM_OPENDOOR` 接入（Delphi 无应答——不发送 `+GOOD/+FAIL`）；`SM_OPENDOOR_OK/SM_CLOSEDOOR`（recog=0, x, y）与 `SM_CLEAROBJECTS→SM_CHANGEMAP→SM_MAPDESCRIPTION` 序列（`DayBright` 长期占位 0，与 `SM_NEWMAP` 既有处理一致）。
- `bootstrap`：`MIR2_MAPINFO_FILE` 新配置（与 `MIR2_MAP_FILE` 互斥，`ServerConfigTest` 覆盖）；`Mir2Server` 多图加载（缺 `.map` 文件告警跳过）、出生图 fail-fast、路线经命令队列注册、`spawnMonGen` 匹配扩展到全部已加载地图、启动日志带 `maps=/routes=` 汇总。

#### 新增测试（8+5+2+2+1 用例）

- `WorldDoorAndTeleportTest`（8）：±12 广播边界、重复/非锚点静默、链接门共享状态 + 500ms 扫拍 5s 自动关、走路过河全事件扇出（含双图观察者）、跑步同触发、关门压制连接点（开门后恢复传送）、目标不可走整步回滚（占位复原）、未知地图路由丢弃。
- `MapInfoLoaderTest`（5）：定义行/路线行（含逗号制表符混合、`->` 变体、行尾注释截断）、`loadmapinfo` 子目录内嵌、别名条目 + 非数字坐标回 0、缺失 include 静默跳过、RouteLine→TeleportRoute 转换。
- `Mir2MapLoaderTest`（+2）：门锚点解码（Point=0 不算门、不同 index 不共享）、同 index ±10 共享/超 10 独立。
- `GameProtocolAdapterTest`（+2）：开门广播布局 + 静默语义、落 gate 的 SM 序列（`Status(true)→CLEAROBJECTS→CHANGEMAP(body=地图id)→MAPDESCRIPTION`）。
- `ServerConfigTest`（+1）：`MIR2_MAPINFO_FILE` 解析与互斥校验。

#### 明确未实现 / 注意事项

- **城堡门差异分支不做**：`Castle.pas` 便门守卫（`CheckInPalace` 等）与攻城战相关 `bo01`——城堡子系统未落地；当前语义等同 `Castle=nil` 分支。
- **昼夜亮暗不做**：`SM_CHANGEMAP`/`SM_NEWMAP` 的 `DayBright` series 与 `SM_DAYCHANGING` 仍按 0 发送，等待昼夜 + `DARK/DAYLIGHT` MapFlag 切片。
- **MapFlag 不做**：SAFE/FIGHT/NORECONNECT/NEEDSET_ON…（`EnterAnotherMap` 里的 `nNEEDSETONFlag` 关卡检查）与 music id（`SM_MAPDESCRIPTION` recog）暂按 `-1/0` 占位；flag 令牌在 `MapInfoLoader` 已被消费丢弃，后续可原位扩展。
- **跨服切换不做**（`m_nServerIndex` 不同 → `DisappearA+SwitchData+紧急关连接` 流程）——Java 单进程恒同服，与既有 PoC 范围一致。
- **怪物不触发连接点**——忠实复刻 `m_btRaceServer=RC_PLAYOBJECT` 分支（Delphi 中怪物落入 gate 会 `Result:=False` 回滚整步，这一怪异路径未复刻，实际地图数据基本不触发；如发现需要请走 quirks 流程）。
- **回滚语义对齐**：`GATE_TARGET_UNPASSABLE` 是 Java 侧新增的显式拒绝码（Delphi 走 `WalkTo` 静默回滚且不发走路广播），bot/前端按「整步未发生」处理即可——占位复原已在占用索引上验证。
- mir2-front 网页控制台对 `SM_CLEAROBJECTS/`SM_CHANGEMAP`/`SM_OPENDOOR_OK/`SM_CLOSEDOOR` 只记录不中断（bridge keepalive 未受影响）；真联调对拍仍在等 Windows 客户端环境。

### W06 traffic recorder / replayer 交接明细（2026-09-20）

#### 已落地代码（`java-server/wiretool/`，Maven 第 9 模块，shaded `mir2-wiretool.jar`）

- `.mrec` 录制格式 v1（`RecordingCodec`）：`MREC` magic + 版本字节 + UTF-8 元数据行（`tool/format/label/listen/target/peer/started`，分号/等号/换行自动转义）+ 逐条记录（1 字节类别 `>`/`<`/`n`/`N`/`E`、u32-LE 相对毫秒、u32-LE 长度、载荷）。writer 每条 flush——录得再久，突然 kill 也只损失最后半条；reader 容忍截断尾部（保留可读前缀，正好覆盖进程被杀的场景）。
- `FrameSplitter`：与 `WireMessageCodec.readPacket` 同源的增量分类器——帧外字节记为噪声段、超 8KiB 的"帧"降级为噪声且**保留开头 `#`** 使原始字节流可逐字节重构（有不变式单测）；未收尾残尾在 `finish()` 时吐为噪声。
- `RecorderProxy`（`record` 子命令）：每连接一条虚拟线程 + 双向 pump，**转发的是原始字节块**（录制只是旁路分类），半关闭（`shutdownOutput`）按原样传递；每条连接写一个 `<label>-<时间戳>-<端口>-<序号>.mrec` 并夹入 session-open/close marker；录制写盘失败只告警、绝不掐断代理；`--max-connections N` 便于 CI/单拍；SIGTERM 优雅退出。
- `Replayer`（`replay` 子命令内核）：只回放客户端事件（帧重新 `#…!` 包裹、噪声原样发），节奏 = 录制时戳 ÷ `--speed`（或 `--max-speed`）；读取线程旁收服务端帧/噪声；发送被对端截断时记录 note 并继续对拍（剩余记 MISSING），绝不抛栈。
- `ReplayDiff`：按服务端帧序配对，五分类（一致 / 内容差异 + 首差异偏移 / 缺失 / 多余 / 跳过）；字节级判定 = 内容差异+缺失+多余全零，结构级判定（`--structural-only`）只卡缺失+多余——用于认证码/tick 等已知易变字段的会话冒烟，与字节级 golden 判定的语义边界写进了类注释与报告里。
- `ReplayReport`：中文 Markdown + CSV 双报告（`replay-<label>-<时间戳>`），汇总表 + 差异明细表（帧注解内嵌）;
- `FrameDescriber`（`inspect`/报告共用）：反射反查 `ProtocolConstants` 数百个 CM_/SM_ 名；客户端帧先探 RunLogin（整条 6-bit 解码出 `**account/character/cert/version/code` 才算，**cert 打码**，避免 6-bit 正文被误当消息头）再探标准包头；服务端帧识别 `+GOOD/+FAIL/<tick>` 原始应答帧且不剥数字前缀；正文 6-bit+GBK 解码预览；不可解析帧降级 hex 摘要。**注解只是描述，不参与录制/回放的正确性**。
- `WireToolMain`：`record` / `replay` / `inspect` 三子命令，退出码约定 0=OK/PASS、1=回放 FAIL 或 inspect verify 失败、2=用法或 I/O 错误；完整中文 `--help`。
- CI：`java-server.yml` 新增 `wiretool-smoke` job——实起 fat JAR 服务端 + 录制代理 + 2 bot 经代理登录，随后 `inspect --verify`（全帧可解析）、结构级回放（PASS 退出 0）、字节级回放（因 `SM_SELECTSERVER_OK` 内嵌随机认证码如期 FAIL 退出 1，反向证明对拍不是摆设），产物（捕获 + 报告）上 artifact；dist 通道改为同时发布 `mir2-server.jar` / `mir2-loadtest.jar` / `mir2-wiretool.jar`（各自 sha256）。

#### 验收证据

- 7 个新测试类：`RecordingCodecTest`（往返/元数据转义/截断容忍）、`FrameSplitterTest`（跨块/噪声/未收尾/超帧降级 + 字节重构不变式）、`FrameDescriberTest`（CM_/SM_ 反查、RunLogin 打码、hex 降级）、`ReplayDiffTest`（五分类与两种判定）、`RecorderProxyTest`（回环字节透传、帧/噪声分类、单调时戳、串行连接一文件一连）、`ReplayerTest`（逐字节命中、偏移定位、缺失/多余、节奏缩放、中途断链对拍）、`WireToolMainTest`（CLI 出口码与 verify 语义）。
- 沙箱实跑（jdk4py JRE + CI 产物）：服务端 + 录制代理 17000→27000 + bot 登录实捕 → inspect 注解 → 结构级/字节级回放，报告存档 `java-server/docs/g0-evidence/2026-09-20-wiretool-record-replay-smoke.md`。
- 计划书 v1.0.14；CI run 见本 PR 检查区。

#### Delphi 侧 golden 实捕操作指引（Windows 环境就绪后照做）

1. 在 Delphi 服务端机器（或同网段代理机）上放 `mir2-wiretool.jar` 与一个 JRE 21；
2. 每道门挂一个录制代理：`record --listen-port 7000 --target-host 127.0.0.1 --target-port 17000`（按 Delphi 侧 LoginGate/SelGate/RunGate 实际绑定端口调整；客户端登录器指向代理端口）；
3. 用真 `mir2.exe` 走完整链路（登录→选人→进图→走/打/捡/重登），Ctrl+C 收工；
4. `inspect --file <capture>.mrec --verify` 全绿后，把 `.mrec` 与 `inspect` 摘要提交 `java-server/docs/golden/`（后续切片任务）；
5. 此后 Java 服务端每改一段协议，`replay --file <golden>.mrec --target-host … --target-port …`（字节级）即为回归对拍；易变帧序号用 `--skip-server-frames` 登记并在 PR 里说明。

#### 明确未实现 / 注意事项

- 回放是**单连接**语义：三段式握手要分三次回放（每道门各自的 `.mrec` 对各自端口），不跨连接维持认证态；`SM_LOGON` 之后的 GAME 门会话若要回放字节级 PASS，需要先快照数据库并用 `--skip-server-frames` 登记认证码/tick 帧——骨架期如实暴露，不做 magic。
- `--skip-server-frames` 的序号以**录制中服务端帧序**为准（`inspect` 列出的序号）；骨架不做模式语言/字节掩码，留待真实 golden 出现后再长。
- 录制代理不做限速/加密/DES 变换（本链路无 DES），字节透传是唯一职责。
- recorder 与 replayer 是「golden 捕获与回放对拍」工具，**不能**证明语义正确性——它只能证明两个服务端字节一致；语义仍靠与 Delphi 源码对照与真客户端盲测（计划§05/§10 的铁律不变）。
- **冒烟顺带暴露（转「接入层加固」切片处理，本切片不修）**：`GateSessionRegistry.remove(certification)` 全库无调用点，认证码断线后终身有效——游戏门回放凭录制里的旧认证码成功重进图（证据文档「暴露的服务端事实」第 1 条）。加固时应实现 GAME 登录消费即失效 + 断线清理，并先对照 Delphi `M2Share` 会话表语义。

### W05 bot 压测军团交接明细（2026-09-19）

#### 已落地代码（`java-server/loadtest/`，Maven 第 8 模块）

- `BotWireClient`：真实线上协议客户端——`#<seq><12B 小端头><6bit 体>!` 帧、客户端前缀轮转 `'1'..'9'`（服务端剥离）、GBK 文本、`+GOOD/<tick>` / `+FAIL/<tick>` 动作确认（先于正常 `SM_` 帧到达）。直接复用 gate 公有的 `WireMessageCodec`/`DefaultMessage`/`MessageCodec`，不复制编解码逻辑。
- `Mir2Bot`：单个机器人状态机——登录门三步（`CM_PROTOCOL → CM_IDPASSWORD → CM_SELECTSERVER`）、选人门（`CM_QUERYCHR`，无角色则 `CM_NEWCHR` 建号，再 `CM_SELCHR`）、GAME 无头首包 `**账号/角色/认证码/120040918/9`，进图后 `SM_NEWMAP/SM_LOGON/MAPDESCRIPTION` 对齐；游戏循环按 锁定威胁→靠近→攻击 / 拾取地面物 / 随机走跑转向 决策（只用已实现消息），每会话发一次 `CM_QUERYBAGITEMS`；到 `--relog-every` 周期完整退出重登。位置认知漂移已修复：移动成功后同步自身 `position/direction`，`Direction.toward` 相等崩溃以 `towardOrRandom()` 容错。
- `BotSwarm`：斜坡启动（默认 10s）、`--bots` 个虚拟线程机器人、每 10s 打印监测行（inWorld/entries/relogs/sent/recv/ackP90/errors）、运行结束按 **0 错误且全部进图** 判定 PASS/FAIL；外部 `requestStop()` 干净收场。
- `BotMetrics` / `BotReport`：计数器（进图、重登、死亡、包收发、按 `SM_` 分布）+ 动作应答延迟分位（p50/p90/p99/max，按动作分类）+ 错误分类计数；输出中文 Markdown 与 CSV 双报告到 `--report-dir`。
- `LoadtestMain`（可执行 fat JAR 入口）：三种模式——`--embedded`（进程内起 `Mir2Server`，临时库 + 随机端口 + 1s 周期 JVM 堆采样写进报告）、remote（`--host/--login-port` 对独立进程的 `mir2-server.jar`）、`--prepare-db`（批量创建 `bot0001..` 账号）；`--duration/--relog-every` 支持 `PT5M`/`45s`/`5m`/`1h`/纯秒数；随机种子固定可复现；SIGTERM 关停钩子也会写出（部分）报告。
- CI：`.github/workflows/java-server.yml` 新增 `bot-swarm` job——Maven 构建后跑 50 机器人 × 2 分钟 embedded，报告作为 artifact 上传，非零退出码即失败。
- 测试：`LongSamplesTest`（最近邻分位）、`BotReportTest`（报告/CSV 内容与不变式）、`BotSwarmEmbeddedTest`（embedded 全链路：多 bot 走/打/捡/重登断言）；本地全套 70/70 通过。

#### 验收证据（G0「50 bots」项）

- **remote 模式**：`mir2-server.jar` 独立进程（24 鸡），50 bots × 5 分钟——PASS，0 错误；350 进图 / 300 次完整重登；22,197 动作、769,008 包收包；turn p50=70.9ms/p90=91.4ms（≈ tick+命令队列的物理下限，PoC 合理）。
- **embedded 模式**：同规模 5 分钟——PASS，0 错误；JVM 堆峰值 39MB / 结束 24MB（62 次采样，无泄漏迹象）；服务端运行后三端口仍可接受连接。
- 报告存档：`java-server/docs/g0-evidence/2026-09-19-{remote-fatjar,embedded}-50bots-5min.md/.csv`。
- 完整 G0 口径（50 × 1 小时）命令：`java -jar loadtest/target/mir2-loadtest.jar --embedded --bots 50 --duration 1h --monsters 24 --relog-every 90s`。

#### 压测暴露的服务端事实（记录，不在本切片修）

- 每次重登的「socket 已关 vs world 写入」竞态会让 `WorldEngine.emit` 打一条 WARNING 堆栈（`Socket is closed`）——**已有 catch 保护，不中断 tick、不丢状态**，但 350 次重登即数百条日志噪音；后续切片可在 gate 出站侧对已关闭连接静默或降频。
- 高密度出生区（50 bot 锚点 15×15）`+FAIL` 约 21%（格被占）——符合碰撞语义，非缺陷；动作应答 p99 ≈ 125ms，受 50ms tick + 队列深度支配。
- 本地沙箱用 jdk4py JRE + ECJ 构建（脚本 `~/tools/build-mir2.sh`，仓库外），CI 的 Maven 构建不受影响；`junit-framework` `TestIdentifier.java:321` 的 `ClassNotFoundException` 补丁只在本地工具树，**不入库**。

#### 明确未实现 / 注意事项

- bot 只覆盖**已实现**的 `CM_` 消息；装备穿脱、`CM_EAT`、`CM_DROPITEM`、技能与 57 怪按红线不在 bot 行为里（发现服务器支持后再扩展行为树）。
- `ackP90<=…ms` 的监测行显示的是**截至当前最差**分位，精确值以最终报告为准。
- recorder / replayer（P0 三件套另两件）未开始；bot-swarm 是「行为正确性 + 稳定性」证据，不能替代字节级 golden 与真客户端对拍。

### W04 物品目录与背包同步交接明细（2026-09-19）

#### 已落地代码

- `world/StdItem`：完整 `TStdItem` 模板（name/stdMode/shape/weight/aniCount/source/needIdentify/looks/duraMax/ac/mac/dc/mc/sc/need/needLevel/price），u32 字段用 `long` 保存并在写入线上时截断；`packedRange(min,max)` 复刻 `ItmUnit.pas GetItemAddValue` 的 `MakeLong` 打包；`placeholder(name,looks)` 兜底目录外名称。
- `world/ItemDatabase` + `world/StdItems`：world 内的目录端口（等价 M2Server 内存 `StdItemList`），最小目录 = 鸡肉/鹿肉/木剑/金创药(小量)；数值按经典数据设置但全部标 `TODO(verify)`，待真实 StdItems 导入校正。
- `world/BackpackItem`：由 `(name, looks)` 升级为 `(StdItem 模板, makeIndex, dura, duraMax)`；`of(template, makeIndex)` 复刻 `CopyToUserItemFromName` 的满耐久创建（实例耐久 = 模板 DuraMax 低 16 位）。
- `world/WorldEngine`：新增 `ItemDatabase` 注入；`allocateMakeIndex` 复刻 `M2Share.GetItemNumber`（单调递增，超过 `High(Integer)/2-1` 回绕到 1），构造时从 `PlayerStateStore.itemMakeIndexHighWater()` 接续，重启不重号；拾取时按名称解析模板（目录缺失则用地面 looks 兜底）；掉落 Looks 优先取目录模板值；进图恢复时为 W03 旧行（makeIndex=0）重编号。
- `gate/ClientItemCodec`：76 字节小端 `TClientItem`（packed 66 字节 `TStdItem` + 2 字节对齐填充 + `MakeIndex`(偏移 68) + `Dura`/`DuraMax` word）；`String[20]` 名字槽 = 1 长度字节 + GBK 载荷 + 零填充；`encodeBag` 按 Delphi 惯例给每个条目补尾随 `/`。
- `gate/GameProtocolAdapter`：`CM_QUERYBAGITEMS → SM_BAGITEMS`（recog=玩家、series=数量、空包完全静默，与 `ObjBase.pas:15952` 一致）；`SM_ADDITEM` 载荷从纯名字升级为完整 `TClientItem`（`SendAddItem` 无分隔符）。
- `persistence/SqliteStore`：新增 `std_items` 表并在启动时 `INSERT OR IGNORE` 种子目录；`character_inventory` 增加 `make_index/dura/dura_max` 列（W03 原位升级）；载入按名称 LEFT JOIN 模板、缺失时用 placeholder 保留条目；保存时对包内模板 `INSERT OR IGNORE`（首写为准，不覆盖精调数据）；`itemMakeIndexHighWater()` 取 `MAX(make_index)`；`itemDatabase()` 启动快照交给引擎。
- `bootstrap`：`WorldEngine` 装配接入 `store.itemDatabase()`。

#### 关键证据与推导（TClientItem = 76 字节）

- `TStdItem`（packed，Grobal2.pas:540）：`String[20]` 名字槽 21 字节 + 7 个单字节字段 + `Looks` word + 9 个 dword = **66 字节**（源码 "60 bytes" 注释源自旧版 `String[14]`，已过时）。
- `TClientItem`（非 packed，Grobal2.pas:562）：`MakeIndex: Integer` 按 4 字节自然对齐落在偏移 68，字节 66–67 为对齐填充，加 `Dura`/`DuraMax` 两个 word 共 **76 字节**；客户端 `ClMain.pas ClientGetAddItem/ClientGetBagItmes` 均按 `sizeof(TClientItem)` 解码。此前计划中的 "68 字节" 是基于过时注释的推导，已更正；最终以 Delphi 抓包 golden 为准。
- `SM_BAGITEMS`：头 `MakeDefaultMsg(SM_BAGITEMS, Recog, 0, 0, Count)`，正文 = 每件 `EncodeBuffer(TClientItem) + '/'`（含末尾 `/`）；空包不发送。`SM_ADDITEM`：series=1，正文为单个无分隔 `TClientItem` 块。
- `MakeIndex`：`GetItemNumber`（M2Share.pas:3611）单调递增；`CopyToUserItemFromName`（UsrEngn.pas:1624）创建实例时 `Dura := DuraMax := StdItem.DuraMax`。

#### 验收证据

- `gate/ClientItemCodecTest`：76 字节布局逐偏移断言（GBK 名字槽、`MakeLong(2,5)` 的 DC、66–67 填充、`MakeIndex` 偏移 68）、6-bit 回环、20 字节满槽名字、实例耐久 word 截断、bag 正文 `/` 拼接与空串。
- `gate/GameCombatProtocolTest`：拾取后 `SM_ADDITEM` 解码出完整模板 + 正数 `MakeIndex`；新增 `CM_QUERYBAGITEMS` 用例断言空包静默、拾取后 series=1 且正文与 `encodeBag` 逐字节一致。
- `world/WorldCombatTest`：拾取事件携带完整模板、`MakeIndex>0`、满耐久。
- `persistence/SqliteStoreTest`：磨损木剑（makeIndex=101, dura=7/20）+ 满耐久鸡肉的完整往返；目录种子与未知名称 placeholder 保留；W03 `character_inventory`（仅 name+looks）原位升级、升级行 join 到目录模板、重编号往返；高水位断言。
- `persistence/WorldPersistenceIntegrationTest`：杀鸡拾取 → 重启 Store/World → 重登，`MakeIndex` 与模板逐字段不丢。

#### 明确未实现 / 注意事项

- 物品数值（价格/恢复量/耐久）是最小占位目录，全部标注 `TODO(verify)`；真实 StdItems.DB 导入前不得用于经济平衡。
- `SM_UPDATEITEM`（耐久变化）、`CM_EAT`（使用物品）、`CM_DROPITEM`（丢弃）、装备槽 `UseItems` 与 `SM_SENDUSEITEMS` 均未实现，属于后续物品切片。
- 目录查找按名称精确匹配；Delphi `CompareText` 的大小写不敏感语义对中文名称无影响，待英文物品名导入时再对齐。
- `TClientItem` 布局是从 Grobal2.pas 字段表推导的（Delphi 记录对齐规则），**尚未有 Delphi 端抓包 golden**；真客户端对拍是 G0 前置条件。
- 46 件物品的 `SM_BAGITEMS` 正文约 4.7K 字符，低于 8192 字节帧上限；更大背包需分帧时按 Delphi 行为再定。

### W03 存档闭环交接明细（2026-09-19）

#### 已落地代码

- `world/PlayerStateStore` 是 world→持久化端口；`PlayerState` 以角色 UUID 绑定完整 `Ability` 与有序 `BackpackItem`，背包上限固定为 Delphi `MAXBAGITEM=46`。无 JDBC 的 world 单测继续使用 transient no-op 实现。
- `persistence/SqliteStore` 新增 `character_state` 与 `character_inventory`；能力和完整背包在同一事务中 upsert/替换，角色删除通过外键级联。状态保存会同步 `characters.level`，保证选人界面的等级不滞后。
- 旧 W02 数据库启动时原位迁移：`characters` 自动增加 `gender/hair/dress_shape/weapon_shape`，既有角色回填默认 `Ability`，无需删库。
- `WorldEngine` 以角色 UUID 进图，在 `MapEntered` 前恢复状态；玩家受伤、获得经验、拾取和离场均触发保存。拾取先落库再移除地面物品，保存失败会回滚内存背包且不会吞掉掉落。
- `Character.feature()` 复刻 `MakeHumanFeature(0, dress*2+gender, weapon*2+gender, hair*2+gender)`；`CM_NEWCHR` 的 hair/sex 已进入角色表、选人列表和 GAME 会话，最终用于 `SM_LOGON/TCharDesc`。

#### 验收证据

- `SqliteStoreTest`：覆盖外观、HP/MP/等级/经验、背包重开恢复，角色删除级联，以及 W02 旧表自动升级/状态回填。
- `WorldPersistenceIntegrationTest`：确定性执行「杀鸡得经验 → 拾取鸡肉 → 半兽人造成 HP 损失 → 关闭并重开 Store/World → 同 UUID 进图」，逐项断言 Ability 与背包不丢。
- `GameSessionIntegrationTest`：从 `CM_NEWCHR hair/sex` 一直断言到 `SM_LOGON` 的 packed Feature。
- GitHub Actions run `35427011522` 全绿：Maven/JDK 21 全量测试、fat JAR 上传与三端口冒烟、Compose 校验、Docker 镜像构建。

#### 下一步边界

- ~~当前持久化条目只有名称和 `Looks`~~（已由 W04 交付）：最小物品目录、稳定 `MakeIndex`、耐久字段、76 字节 `TClientItem` 编码以及 `CM_QUERYBAGITEMS → SM_BAGITEMS` 均已落地；真客户端验收重登背包仍待对拍。
- SQLite 当前按每次战斗状态变化同步写入，满足 PoC 一致性但不代表最终吞吐方案；P2 压测后再决定脏标记/周期批量保存，不得在 G0 对拍前提前复杂化。
- 角色表已有衣服/武器 shape，但尚无穿脱命令、装备槽和属性重算；这些属于后续物品/装备切片。

### W03 战斗切片交接明细（2026-09-19）

#### 已落地代码

- `world/Ability`：HP/MaxHP、MP/MaxMP、DC/AC 上下限、等级与经验；Delphi 把攻防存成 `Word` 高低字节区间，Java 侧展开存储，仅在上线字节时再打包。
- `world/AttackKind`：`HIT / HEAVY_HIT / BIG_HIT`，对应 `CM_HIT / CM_HEAVYHIT / CM_BIGHIT`；按 `ObjBase.pas:CheckActionInterval` 的语义共用同一个 `CM_HIT` 动作间隔（默认 900ms）。
- `WorldEngine.attack`：校验坐标一致、存活、动作间隔，命中正前方单格；伤害 = `rand(minDC..maxDC) - rand(minAC..maxAC)`，下限 0。攻击动画对**攻击者本人不回发**（复刻 `ObjBase.pas:5324` 的 `RM_HIT` 抑制）。
- `world/MonsterTemplate` + `ItemDrop`：鸡（6HP，必掉鸡肉）与半兽人（45HP，1/2 鹿肉、1/20 木剑）；`feature` 直接按 `MakeMonsterFeature(raceImg, weapon, appr)` 打包存储。
- `WorldEngine.updateMonsters`：每 Tick 做索敌（视野内最近的存活玩家）、贴身则按怪物攻击间隔攻击、否则按行走间隔追击（正前方受阻时尝试左右相邻方向）；死亡后按 `corpseLingerMillis` 清理尸体并发 `SM_DISAPPEAR`。
- `world/GroundItem` + `WorldEngine.pickUp`：死亡格优先掉落、被占则扫描周围 8 格；地面物品参与视野进出同步；拾取要求玩家站在同格，取该格最新一件。
- `gate/GameProtocolAdapter`：新增 `CM_HIT/HEAVYHIT/BIGHIT`（Recog 打包坐标 + Tag 方向）与 `CM_PICKUP`（param/tag 为客户端自身坐标，与 `ClMain.pas:3050` 一致）入站；出站新增 `SM_HIT/HEAVYHIT/BIGHIT`、`SM_STRUCK`（param=HP、tag=MaxHP、series=伤害、body=16B `TMessageBodyWL`）、`SM_HEALTHSPELLCHANGED`、`SM_DEATH`（body=8B `TCharDesc`）、`SM_WINEXP`（recog=总经验、param/tag=本次经验高低字）、`SM_ITEMSHOW/SM_ITEMHIDE/SM_ADDITEM`。
- `bootstrap`：新增 `MIR2_MONSTER_COUNT`（0–1000）与 `MIR2_MONSTER_KIND`（`chicken`/`orc`），启动时围绕出生点成环布怪，便于真客户端直接验证打怪闭环。

#### 验收证据

- `world/WorldCombatTest`：虚拟时钟 + 固定种子随机数，覆盖击杀与经验、掉落与拾取、怪物追击与反击、动作间隔与坐标不符拒绝、怪物进图可见与尸体超时消失。
- `gate/GameCombatProtocolTest`：覆盖三种攻击 ident 的入站与观察者广播、击杀链路的 `SM_STRUCK/HEALTHSPELLCHANGED/DEATH/WINEXP/ITEMSHOW` 字段、拾取的 `+GOOD` → `SM_ADDITEM` → `SM_ITEMHIDE` 顺序，以及纯出站事件映射。

#### 明确未实现 / 注意事项

- ~~**完整背包线上载荷仍未实现**~~（已由 W04 交付）：`BackpackItem` 现携带完整模板、`MakeIndex` 与耐久，`SM_ADDITEM/SM_BAGITEMS` 输出 76 字节 `TClientItem`；但未经真客户端对拍前仍不能视为「真客户端验证完成」。
- 伤害公式是 Delphi 基础攻防区间的简化版，未包含幸运/诅咒、命中闪避、护身与麻痹等修正；等级提升、`SM_LEVELUP` 与属性成长也未实现。
- 玩家死亡后仅广播 `SM_DEATH` 并禁止移动/攻击，尚无复活、掉落惩罚与 `SM_ALIVE`。
- 掉落只有物品名与 `Looks`，没有完整 `TClientItem`（60 字节 `TStdItem` + 耐久），因此 `SM_ADDITEM` 目前只带名字，接入真实客户端前必须补物品数据库。
- 怪物刷新点、刷怪计时、`MonGen` 配置文件解析均未实现，当前只能靠环境变量在启动时一次性布怪。

### W03 本次交接明细（2026-09-18）

#### 已落地代码

- 新增 Maven 模块 `java-server/world`，根 Reactor 和 `bootstrap` 已接入；父 POM 统一启用 Surefire 3.2.5，确保各模块 JUnit 5 测试实际执行。
- `WorldEngine`：50ms 默认固定 Tick、单 owner 线程、跨线程 FIFO 命令队列、每 Tick 最大 10,000 条命令；提供 `enterPlayer`、`leavePlayer`、`move`、`turn`、`snapshot`、`onlinePlayers`。
- `Mir2MapLoader`：读取 Delphi `TMapHeader`（52B）和 `TMapUnitInfo`（12B），按 `x * height + y` 列优先布局生成碰撞数据；支持背景图/前景图 `$8000` 阻挡标志，地图上限与原数组一致为 1,000,000 单元。
- `GameMap`：静态地形碰撞、动态移动对象占位、原子移动/移除及方形视野索引。
- `Direction/MovementKind`：保持 `DR_UP=0 .. DR_UPLEFT=7`；走路 1 格、跑步 2 格，并校验目标坐标、沿途地形和对象占位。
- `WorldEvent`：已定义 `MapEntered/MapLeft`、`ObjectAppeared/ObjectMoved/ObjectTurned/ObjectDisappeared`、移动/转向成功与拒绝事件；跨线程只暴露不可变 `WorldObjectSnapshot`。
- 视野范围默认 12 格，移动前后计算可见集合差异，分别发送出现、移动和消失事件；事件接收器异常不会中断世界 Tick。
- `Mir2Server` 会在 Gate 监听前启动世界线程，停服时按 Gate → World → SQLite 顺序关闭。支持 `MIR2_MAP_FILE`、`MIR2_MAP_ID`、`MIR2_WORLD_TICK_MS`；未配置地图文件时创建 256×256 空白 PoC 地图。

#### 验收证据

- 领域测试：`DirectionTest`、`GameMapTest`、`Mir2MapLoaderTest`、`WorldEngineTest`，覆盖方向值、列优先地图、静态/动态碰撞、Tick 串行化、走跑、视野广播、线程归属和离场清理。
- 基线提交：`a493d2e71a5e1a3aaafb29a47c0ed1f855e516ff`。
- GitHub Actions run `35335312828` 全绿：Maven 全量测试、可执行 JAR 三端口启动冒烟、Compose 校验及 Docker 镜像构建均通过。

#### 明确未实现 / 注意事项

- 7200 GAME 已接入 `WorldEngine` 并通过模拟 socket 验证进图和移动闭环；尚未获得真客户端抓包，因此亮度、外观、消息顺序等字段仍必须视为待对拍假设。
- 仓库没有随附可加载的 `.map` 资源，运行默认使用空白 PoC 地图；真实地图需通过 `MIR2_MAP_FILE` 指定。出生点由 `MIR2_SPAWN_X/Y` 配置，占用时选择邻近空位。
- `TCharDesc` 和 `TMessageBodyWL` 的结构及字节序已实现；角色性别、发型和衣服/武器外观现已持久化并生成 `Feature`。装备穿脱与状态效果未实现，因此 `Status` 仍为零。
- 动作间隔、基础近战、怪物、经验、掉落、背包及世界存档已实现；防加速细化、门/传送点、完整物品/装备和技能仍未实现。
- 当前跑步会严格检查两格路径上的地形和动态占位；与 Delphi `CanWalkEx/MoveToMovingObject` 的特殊放行语义仍需真实 golden 对拍，发现差异时先记录兼容 quirks，不要直接“优化”。

### 未完成清单（明确边界）

#### S0 PoC

- ⬜ Delphi 实际 traffic recorder 与 20 组字节级 golden 对拍
- ⬜ 真 `mir2.exe` 登录、选区、角色列表、建删角色、进入世界
- 🟡 Tick/地图/碰撞/视野、7200 认证、进图与移动 socket 闭环已完成并有双会话集成测试；真 `mir2.exe` 广播验证未完成
- ✅ 近战怪、击杀、掉落、拾取、**HP/MP/等级/经验/背包重登存档** 与 **完整 `TClientItem`/`SM_BAGITEMS` 载荷** 已有确定性及 SQLite 重启集成测试；真客户端对拍仍未完成
- ✅ 50 机器人稳定性验证：bot-swarm 军团 50×5 分钟 embedded/remote 双模式 PASS（0 错误），CI 每 PR 跑 50×2 分钟；G0 口径的 50×1 小时可用 `--duration 1h` 复现
- ⬜ G0 决策门评审与 v0.1 基线 tag

#### P0/P1

- ⬜ 完整 216 SM_ / 51 CM_ 协议字段规格和 golden 套件（wiretool 已具备捕获/回放能力，等 Delphi 实捕）
- ⬜ Netty 正式接入、IP 黑名单、连接限制、限速、防加速校验
- ⬜ 旧 IdDB/Hum.DB/Mir.DB 迁移器
- ⬜ env-lint 与 Envir 资源扫描
- ✅ traffic-recorder / replayer / bot-swarm 三件套：W05 交付 bot-swarm，W06 交付 recorder/replayer 骨架，均入 CI 门禁；Delphi 实捕 golden 待 Windows 环境
- ⬜ Web 控制台

#### P2–P4

- 🟡 地图碰撞、玩家/近战怪生命周期、12 格视野、基础战斗/经验及协议实发已完成；**门/传送点已由 W10 交付**（`.map` 门锚点、`CM_OPENDOOR`±12 广播、500ms 扫拍 5 秒自动关、`MapInfo.txt` 多图与连接点换图、目标不可走整步回滚、关门压制连接点）；城堡门差异分支、跨服切换、MapFlag 旗标与昼夜亮暗未完成
- 🟡 掉落、拾取、46 格背包、状态存档、最小物品目录、`MakeIndex`/耐久与 76 字节 `TClientItem`/`SM_BAGITEMS` 已完成；周期批量存档已由 W09 交付（`MIR2_SAVE_INTERVAL_SECONDS`，默认 10 分钟对齐 `dwSaveHumanRcdTime`）；装备穿脱、物品使用/丢弃未完成（对拍前禁做）
- 🟡 怪物 AI 框架 + 首批 10 种常见怪已交付（W09：AGGRESSIVE/PASSIVE_FLEE 双行为、MonGen 自动刷新）；除鸡/半兽人外数值为占位，剩余 47 种怪、59 个技能、NPC 脚本未完成
- ⬜ 交易、组队、PK、红名、行会、攻城
- ⬜ 500 机器人 × 4 小时压测、灰度、Docker 双架构、上线回滚演练

> **当前已完成范围包括协议/账号/角色/SQLite、7200→World 的移动/近战/拾取闭环、重登存档及完整 `TClientItem`/`SM_BAGITEMS` 背包同步；尚未经过真实 `mir2.exe` 对拍，装备/技能等未完成项不得视为已支持。**

## 📑 目录

- [01 · 计划总览（Overview）](#overview)
- [02 · 目标、范围与完成定义（Goals & DoD）](#scope)
- [03 · 团队与协作机制（Team & Cadence）](#team)
- [04 · 总路线图（30 周 + 6 决策门）（Roadmap & Gates）](#roadmap)
- [05 · S0 · PoC 最小闭环（W01–W03，30 人日）（Proof of Concept）](#poc)
- [06 · P0–P1 · 规格基座与接入层（W04–W09，55 人日）（Spec & Access Layer）](#p1)
- [07 · P2 · 引擎 MVP（W10–W15，55 人日）（Engine MVP）](#p2)
- [08 · P3 · 玩法全量（W16–W27，70 人日）（Full Gameplay）](#p3)
- [09 · P4 · 迁移、压测与上线（W28–W30，30 人日）（Migration & Launch）](#p4)
- [10 · 工程规范与质量体系（Standards & Quality）](#std)
- [11 · CI/CD 与环境矩阵（CI/CD & Environments）](#cicd)
- [12 · 发布与回滚（Release & Rollback）](#release)
- [13 · 预算汇总（Budget）](#budget)
- [14 · 附录 A · Delphi → Java 翻译清单（Translation Inventory）](#inventory)
- [15 · 附录 B · 文档索引与版本（Documents & Changelog）](#appendix)

**相关文档**：[MIR2 项目分析报告](mir2-analysis-report.md) · [Java 迁移可行性评估](mir2-java-migration-feasibility.md)

---

<a id="overview"></a>

## 01 · 计划总览（Overview）

| 项目 | 内容 |
| --- | --- |
| 计划版本 | v1.0.10（2026-09-19），基线：可行性评估报告 v1.1 |
| 交付物 | `mir2-server`：单 JVM 模块化单体（Netty + 单逻辑线程引擎 + SQLite + Web 控制台），Docker 镜像，旧档迁移工具，运维手册 |
| 硬性约束 | mir2.exe 客户端**零改动**直连（12B 帧 / 6-bit 编码 / DES / GBK 逐字节兼容）；部署平台 Linux；非商业用途 |
| 总体节奏 | 准备期 3 周（培训/环境）→ PoC 3 周 → P0–P4 共 27 周 → 上线 2027-05（目标，整体可平移） |
| 预算规模 | 240 人日（约 48 人周 ≈ 11 人月，落在评估的 8–12 人月区间）；现金成本 < ¥1,500 |
| 相关文档 | [① 项目分析报告](mir2-analysis-report.md) · [② Java 迁移可行性评估](mir2-java-migration-feasibility.md) · ③ 本计划书 |

- **30周** — 总日历周期 · 2026-10-12 → 2027-05-07
- **240人日** — 计划工作量 · 2 人 × 30 周 × 80% 负载
- **6道** — 决策门 G0–G5 · 每门有量化通过标准
- **15个** — 双周 Sprint · 每周五演示 + 阶段门评审

> [!NOTE]
> **计划的两条铁律（来自评估报告，全程有效）：** ① **行为冻结**——迁移期只做等价翻译，任何玩法改进/数值调整一律进 V2 backlog； ② **对拍先行**——没有 golden 用例覆盖的消息与公式，不允许开始翻译对应模块。

<a id="scope"></a>

## 02 · 目标、范围与完成定义（Goals & DoD）

### 范围内（In Scope）

- **9 个 Delphi 程序**的全部服务端功能：登录、选角、存档、三网关、游戏引擎、日志、总控（后两者由合并与 Web 控制台替代）
- **协议层**：216 个 SM_ / 51 个 CM_ 分支的逐字节兼容实现
- **存档迁移**：Hum.DB / Mir.DB / IdDB → SQLite，含校验工具
- **Envir 资源加载**：MonGen、Market_Def 脚本等 100+ 配置文件原格式兼容
- **Linux 部署**：Docker 双架构镜像 + compose 编排 + 运维手册

### 范围外（Out of Scope，进 V2 backlog）

- ❌ 任何数值/掉率/AI 行为「优化」与新增玩法
- ❌ 客户端修改（含反外挂补丁）
- ❌ 多服务器分布式架构（单进程够用）
- ❌ 账号充值/计费体系（原 Fee 相关仅做字段兼容）
- ❌ 商业运营相关功能（法律红线）

> **项目级完成定义（DoD = 评估报告 M5，G5 验收）：** ① 真 mir2.exe 全流程可用：登录→选角→打怪→掉落→交易→攻城；② 51 个 CM_ 分支 golden 用例 100% 通过且入 CI； ③ 旧服存档迁移逐条校验通过；④ 500 机器人 × 4 小时压测无崩溃、无死锁、内存平稳； ⑤ 灰度 2 周无回滚级缺陷；⑥ Docker 镜像在 Linux x86/ARM 双架构可运行；⑦ 运维手册与回滚演练完成。

<a id="team"></a>

## 03 · 团队与协作机制（Team & Cadence）

### 角色 A · 引擎负责人（1 人）

**职责：**M2Server 引擎翻译（对象/战斗/怪物/脚本/攻城）、单逻辑线程架构 owner、golden 规格提取。 **要求：**Java 服务端 5 年+；准备期完成 Pascal 阅读训练（2–4 周，能读懂+能断点调试）。

### 角色 B · 平台/工具工程师（1 人）

**职责：**protocol/gate/auth/character 模块、三件套工具（录制/回放/机器人）、CI/CD、Docker、Web 控制台、压测。 **要求：**Netty 与 Linux 运维熟练，测试工具开发经验。

### 外部参与（非全职）

**种子玩家：**10–20 名老玩家（灰度盲测「手感」）； **Delphi 顾问（可选）：**按需答疑 2–4 人日，处理疑难 Pascal 语义； **法务提醒：**运营边界由项目负责人把关（非商业/私密社区）。

### 协作节奏

- **每日：**15 分钟站会（昨天/今天/阻塞），远程异步亦可
- **每周五：**Sprint 内演示（可运行产物，不许 PPT）+ 周报
- **双周：**Sprint 规划与回顾（15 个 Sprint）
- **阶段末：**决策门评审（对照 G0–G5 量化标准，Go / Fix / Stop 三选一）

### 任务跟踪与分支约定

- 任务粒度 ≤ 2 人日；每任务附「对应 Delphi 源码位置 + 验收方式」两个字段
- GitHub Issues + Milestone（按 G0–G5 建 6 个 Milestone）+ Projects 看板
- 分支：trunk-based，feature 分支存活 ≤ 3 天；PR 必须过 CI + 1 人评审
- 风险跟踪：评估报告 R1–R9 每周过一遍状态（Open/Mitigated/Closed）

<a id="roadmap"></a>

## 04 · 总路线图（30 周 + 6 决策门）（Roadmap & Gates）

| 阶段 | 周期 | 里程碑 |
| --- | --- | --- |
| 准备期 · 培训与环境 | 3 周 | W-3 ~ W0<br>2026-09-21 起 |
| S0 · PoC 最小闭环 | W01–03 | → 2026-11-01<br>G0 决策门 |
| P0 · 规格与工具基座 | W04–05 | → 2026-11-13<br>G1 决策门 |
| P1 · 协议与接入层 | W06–09 | → 2026-12-11<br>G2 决策门 |
| P2 · 引擎 MVP | W10–15 | → 2027-01-22<br>G3 决策门 |
| P3 · 玩法全量 | W16–27 | → 2027-04-16<br>G4 决策门 |
| P4 · 迁移/压测/上线 | W28–30 | → 2027-05-07<br>G5 上线 |

| 决策门 | 时点 | 量化通过标准（全部满足才 Go） | 不通过的动作 |
| --- | --- | --- | --- |
| `G0 · PoC` | W03 末 | 真 mir2.exe 登录→走路→打怪→捡装备→重登不丢；20 组 golden 字节级一致；50 机器人×1h 稳定；全程 Linux 容器构建 | 回评估 R1/R4/R5 定位；最多追加 1 周 PoC-2，再议 |
| `G1 · 协议基座` | W05 末 | 协议规格文档 v1.0 评审通过（216 SM_/51 CM_ 全表）；NPC 脚本指令全量清单提取完毕；env-lint 可扫描示例 Envir | 规格缺口回填，P1 顺延（最多 1 周） |
| `G2 · 真客户端登录` | W09 末 | mir2.exe 完成登录/选区/角色列表/建删角色/进入空世界；golden 套件入 CI 且 100% 绿；账号与角色数据存 SQLite 可重启恢复 | 阻断性缺陷清单 ≤3 项时修复后复验，>3 项回退规划 |
| `G3 · 打怪闭环` | W15 末 | 移动/攻击手感 3 名老玩家盲测无差异；10 种怪 AI 对拍一致；掉落/背包/装备/存档全链路可用；影子对拍工具上线 | 冻结 P3 启动，专项修复「手感」差异 |
| `G4 · 玩法等价` | W27 末 | 59 技能 × 3 职业可用；NPC 脚本指令清单覆盖率 100%（未实现指令为 0）；行会/攻城/交易/组队/红名全流程通过脚本化测试 | 按缺失清单排 2 周补齐窗口，砍非核心玩法入 V2 |
| `G5 · 可上线` | W30 末 | 项目级 DoD 七项全部满足（见第 02 节） | 上线顺延，不允许带伤上线 |

> [!IMPORTANT]
> **人力负载核算：**2 人 × 30 周 = 300 人日容量；计划任务 240 人日（80% 负载），余 60 人日吸收请假、会议、故障与计划外工作。任何阶段实际消耗超出预算 **15%** 即触发里程碑重排。

<a id="poc"></a>

## 05 · S0 · PoC 最小闭环（W01–W03，30 人日）（Proof of Concept）

目标：用最小代价验证两大不确定性（协议兼容、引擎骨架），产出物全部保留进正式工程，**零浪费**。

| 周 | 任务 | Owner | 人日 | 产出 |
| --- | --- | --- | ---: | --- |
| `W01` | 翻译 Grobal2 常量 + EDcode 6-bit + DES + 12B codec | **A** | 6 | `protocol 模块 v0` |
|  | Delphi 侧流量录制工具（挂在旧服前抓 golden） | **B** | 4 | `traffic-recorder + 20 组用例` |
| `W02` | Netty 接入骨架（7000/7100/7200 三端口） | **B** | 4 | `gate 骨架 + 心跳/限速` |
|  | 登录+选角最小路径（内存账号库） | **A** | 6 | `mir2.exe 可进空世界` |
| `W03` | tick 循环 + 地图加载 + 移动广播 | **A** | 5 | `engine/world v0` |
|  | 1 种近战怪 + 击杀/掉落/拾取 | **B** | 3 | `打怪闭环` |
|  | 集成演示 + 老玩家盲测 + G0 评审材料 | **AB** | 2 | `G0 评审包` |

> **G0 通过标准：**评估报告第 11 节五条（真客户端无断线乱码 / golden 100% / 盲测无差异 / 50 机器人 1h / Linux 容器全程）。**通过后立即冻结 PoC 代码为 v0.1 基线 tag。**

<a id="p1"></a>

## 06 · P0–P1 · 规格基座与接入层（W04–W09，55 人日）（Spec & Access Layer）

### P0 · 规格与工具基座（W04–05，20 人日）

| 任务 | Owner | 人日 |
| --- | --- | ---: |
| 协议规格文档 v1.0（216 SM_ / 51 CM_ 全表 + 字段语义） | **A** | 5 |
| NPC 脚本指令全量清单提取（grep 源码枚举） | **A** | 4 |
| 战斗/掉落/升级公式提取表（逐函数注释来源行号） | **A** | 4 |
| env-lint：Envir 引用完整性/大小写/行尾扫描 | **B** | 4 |
| 三件套骨架：recorder / replayer / bot-swarm | **B** | 3 |

G1 = 规格评审通过 + golden 入 CI。规格文档是后续所有翻译的「合同」。

### P1 · 接入与账号（W06–09，35 人日）

| 任务 | Owner | 人日 |
| --- | --- | ---: |
| protocol 模块硬化：全部消息 POJO/codec/单测 | **A** | 8 |
| LoginGate/SelGate 接入（IP 黑名单/连接限制） | **B** | 5 |
| RunGate 转发（消息过滤/防加速校验） | **B** | 6 |
| auth：账号验证/DES 口令/会话路由（IdDB→SQLite） | **A** | 8 |
| character：建删角色/角色列表 DAO + 校验 | **A** | 6 |
| Web 控制台骨架（Javalin，在线列表/日志） | **B** | 2 |

G2 = 真 mir2.exe 走完整登录选角流程，数据可重启恢复。

<a id="p2"></a>

## 07 · P2 · 引擎 MVP（W10–W15，55 人日）（Engine MVP）

目标：跑通「走路→战斗→掉落→背包→存档」核心闭环，建立引擎骨架与对拍能力。本阶段结束即具备小范围内测资格。

| 任务 | 对应源码 | Owner | 人日 |
| --- | --- | --- | ---: |
| 地图引擎：.map 读取 / 48×32 碰撞 / 门与传送点 | `Envir.pas` | **A** | 8 |
| 对象系统骨架：BaseObject 生命周期 / 消息队列 | `ObjBase.pas(骨架)` | **A** | 8 |
| 移动/走路校验（防加速，时序与原版一致） | `ObjBase.Run` | **A** | 6 |
| 视野与九宫格广播 | `ObjBase 可见性` | **B** | 6 |
| 近战公式：命中/伤害/死亡/经验 | `ObjBase 战斗段` | **A** | 8 |
| 物品：掉落表/背包 46 格/穿脱装备 | `ItmUnit + LocalDB` | **B** | 8 |
| 存档：在线周期保存 + 下线即存 | `RunDB/UsrEngn` | **B** | 4 |
| 怪物 AI 框架 + 首批 10 种常见怪（稻草人/鸡/鹿/多钩猫…） | `ObjMon.pas` | **A** | 5 |
| 影子对拍 harness：同操作流双服 diff 状态快照 | `—` | **B** | 2 |

> **G3 通过标准：**打怪闭环全链路 + 老玩家盲测手感无差异 + 10 种怪 AI 对拍一致 + 影子对拍工具可用。**此门是全项目质量分水岭。**

<a id="p3"></a>

## 08 · P3 · 玩法全量（W16–W27，70 人日）（Full Gameplay）

| 任务块 | 内容 | 对应源码 | Owner | 人日 |
| --- | --- | --- | --- | ---: |
| 技能系统 | 59 技能 × 3 职业：弹道/范围/ Buff/召唤/瞬移 | `Magic.pas + JClasses` | **A** | 18 |
| 怪物全量 | 剩余 47 种怪：远程/施法/召唤/钻地/自爆/守卫/城门 | `ObjMon/2/3 + ObjAxeMon` | **A** | 10 |
| NPC 脚本引擎 | Market_Def 全指令集：买卖/修理/传送/任务对话/收购物品 | `ObjNpc.pas + Mission` | **A** | 15 |
| 交互系统 | 交易/组队/PK 红名/守卫正义/摆摊边界 | `ObjBase 相关段` | **B** | 8 |
| 行会系统 | 创建/入退/行会战/同盟 | `Guild.pas` | **B** | 6 |
| 沙巴克攻城 | 城堡/城门/城墙/箭塔可破坏物 + 攻城时间窗 | `Castle.pas + ObjMon2 守卫` | **A** | 8 |
| GM 与管理 | Command.ini 全命令 + Web 控制台完整版 | `各 *Config 窗体` | **B** | 5 |

> [!WARNING]
> **P3 风险提示（对应评估 R2/R6）：**本阶段最容易发生「顺手优化」与范围蔓延。规则：每完成一个技能/怪物/指令，先过对拍再合入； 发现原版疑似 bug 一律记录进 `quirks.md`（忠实复刻 + 标注），由项目负责人统一裁决是否修复。**任何玩法改动不进本阶段。**

> **G4 通过标准：**脚本指令覆盖率 100%（未实现指令数为 0，CI 强制断言）；59 技能/57 怪/行会/攻城脚本化测试全绿；影子对拍连续 3 天无差异告警。

<a id="p4"></a>

## 09 · P4 · 迁移、压测与上线（W28–W30，30 人日）（Migration & Launch）

| 任务 | Owner | 人日 | 产出 |
| --- | --- | ---: | --- |
| 存档迁移工具：Hum.DB/Mir.DB/IdDB → SQLite（逐字段映射 + 双向往返校验） | **A** | 8 | `tools/migrator + 迁移报告` |
| bot-swarm 压测：500 机器人 × 4h 跑图/打怪/交易混合负载 + 调优 | **B** | 6 | `压测报告（延迟/内存/GC）` |
| 灰度：10–20 名种子玩家 2 周封闭测试（与 W26 起并行） | **AB** | 6 | `灰度缺陷清单` |
| 上线清单 + 回滚演练（含旧服热备切换实操） | **B** | 4 | `runbook v1.0` |
| 运维手册与交接文档 | **B** | 3 | `docs/ops-manual` |
| 正式上线 + 72 小时护航期 | **AB** | 3 | `G5 签发` |

> **G5 = 项目 DoD 七项全满足（第 02 节）。**上线后进入 2 周护航期：旧 Delphi 服保留热备，可 15 分钟内回切。

<a id="std"></a>

## 10 · 工程规范与质量体系（Standards & Quality）

### 模块依赖规则（单向，CI 强制校验）

```
admin ──→ engine ──→ character/auth ──→ protocol
  │           │                            │
  └───────────┴────────→ persistence ←─────┘
gate ──→ protocol          commons（零依赖，被所有模块引用）
```

- **protocol 不许 import 任何业务模块**（保证可独立对拍）
- engine 之外不许直接操作 session/发送消息
- 循环依赖 = CI 红灯，无例外

### 测试要求（合入门禁）

- **golden 字节级用例：**每消息至少 1 正例 + 1 边界例；总数 ≥ 120，CI 100% 通过
- **单元测试覆盖率：**protocol / combat / persistence ≥ 70%（Jacoco 门禁）
- **影子对拍：**P2 起每日夜间跑一轮，diff 告警进晨会
- **压测：**每个阶段门跑一次递增压测（50→200→500 机器人）

### AI 辅助翻译流水线（本项目效率关键）

- 四步合入：**AI 初译 → 人工 diff 评审 → 单测 + golden 对拍 → 合入**，缺一不可
- **伤害/掉落/升级公式禁止 AI 直译**：人工从源码提取，双通道验证（源码推导 + 抓包对照）
- AI 输出中每个含糊语义必须生成 `// TODO(verify)` 注释，评审清单化处理
- 翻译对照表入库：`docs/translation-map.md` 记录「Delphi 单元 ↔ Java 类」映射

### 编码与日志规范

- 协议边界一律 `byte[] + GBK`，禁用 String 直传网络层（评估 R4）
- 逻辑线程内**禁止** sleep / 散落异步 / 阻塞 I/O（评估 R5），延迟统一 tick 调度器
- 日志分级：`ECONOMY`（交易/掉落）/ `ACTION`（战斗）/ `SECURITY`（登录/封禁）三类独立 appender
- 禁止硬编码路径 / 魔法数字；数值一律来自配置或常量类（对应源码行号注释）

<a id="cicd"></a>

## 11 · CI/CD 与环境矩阵（CI/CD & Environments）

### 流水线（GitHub Actions）

```
# PR 触发：编译 + 单测 + golden + env-lint + 依赖检查
on: pull_request
  - mvn verify            # 含 120+ golden 用例
  - archunit check        # 模块依赖单向校验
  - env-lint --envir tests/fixtures   # 大小写/引用/行尾
# main 合入：staging 自动部署
  - docker compose up -d  # staging 环境
# tag v*：生产镜像构建（手动确认部署）
  - docker buildx --platform linux/amd64,linux/arm64
  - push ghcr.io/<org>/mir2-server:v*
```

### 环境矩阵

| 环境 | 用途 | 规格 |
| --- | --- | --- |
| `local` | 开发自测（Linux 容器内跑） | 开发机 Docker |
| `staging` | 影子对拍 / 灰度前验证（与 Delphi 旧服并行） | 2C4G VPS |
| `prod` | 正式社区服 | 2C4G ARM + 对象存储备份 |

staging 从 P1 起常驻（对拍需要）；prod 在 W28 预备。**所有环境同一镜像 tag 矩阵管理，禁止手改生产容器。**

<a id="release"></a>

## 12 · 发布与回滚（Release & Rollback）

### 上线检查清单（节选）

- ✅ 存档迁移报告：记录数一致 + 抽样 50 角色字段 diff 为 0
- ✅ golden / 影子对拍 / 压测三报告全绿，附 G5 评审记录
- ✅ 备份任务上线并验证可恢复（VACUUM INTO + 异地同步）
- ✅ 监控告警就位（探活 + tick 延迟 + 磁盘 + 在线数）
- ✅ 回滚演练完成一次（≤ 15 分钟恢复旧服）
- ✅ 玩家公告与客户端连接指引（无需改客户端，仅换 IP 段说明）

### 灰度与回滚策略

| 阶段 | 流量 | 时长 | 晋级条件 |
| --- | --- | --- | --- |
| 灰度一 | 10–20 种子玩家 | 2 周 | 无回滚级缺陷 + 手感反馈收敛 |
| 灰度二 | ≤ 50 人 | 1 周 | 性能指标达标 + 经济日志抽查无异常 |
| 全量 | 全部 | — | — |

**回滚预案：**镜像 tag 秒级回滚（<5 分钟）；存档异常回滚 = 恢复迁移前快照 + 增量重放；极端情况切回 Delphi 旧服热备（≤ 15 分钟），Java 服数据按最后一致快照回迁。

<a id="budget"></a>

## 13 · 预算汇总（Budget）

| 科目 | 数量 | 说明 |
| --- | ---: | --- |
| 人力（核心） | 240 人日 ≈ 11 人月 | 2 人 × 30 周 × 80% 负载；含 PoC 与灰度护航 |
| 人力（外部） | 2–4 人日 | Delphi 顾问按需答疑（可选）+ 种子玩家致谢 |
| 云主机 | 约 ¥600–1,100 | 2C4G × 2 环境（staging+prod）× 7 个月，ARM 机型 |
| 备份存储 | 约 ¥50 | 对象存储 7 天快照 × 2 份 |
| 域名（可选） | 约 ¥60/年 | 仅提示用；客户端连 IP 亦可 |
| **现金总计** | **< ¥1,500** | 主要成本为人力；时间成本见左 |

> [!NOTE]
> **成本对齐说明：**本计划 240 人日落在可行性评估「8–12 人月」区间内（48 人周 ≈ 11 人月），其中已含 60 人日缓冲。 若 G0 后决定压缩：可将 P3 的「行会/攻城」后置到上线后 V1.1（省约 14 人日，风险为开服玩法不全，复古服可接受），总工期可缩至 26 周。

<a id="inventory"></a>

## 14 · 附录 A · Delphi → Java 翻译清单（Translation Inventory）

「策略」图例：`直译` 逐行/逐函数翻译并对拍 ｜ `拆分` 巨型文件拆为多类 ｜ `替代` 由新架构组件替代，仅参考行为 ｜ `废弃` 不迁移

| Delphi 源文件 | 行数 | 目标 Java 模块 | 策略 | 阶段 |
| --- | ---: | --- | --- | --- |
| `Common/Grobal2.pas` | ~3,000 | `protocol（常量+POJO）` | `直译` | PoC/P1 |
| `Common/EDcode.pas + DES.pas` | ~1,500 | `protocol/codec` | `直译` + 字节级 golden | PoC |
| `Common/HUtil32 + MudUtil` | ~3,000 | `commons` | `直译`（工具函数） | P1 |
| `M2Server/ObjBase.pas` | 26,821 | `engine/object + combat + world` | `拆分`（→10+ 类） | P2/P3 |
| `M2Server/ObjNpc.pas + Mission.pas` | ~12,500 | `engine/script` | `拆分` + 指令清单驱动 | P3 |
| `M2Server/M2Share.pas` | 11,087 | `core/config` | `拆分`（全局状态→配置类） | P1/P2 |
| `M2Server/LocalDB.pas` | 3,670 | `persistence/loader` | `直译`（数据加载） | P2 |
| `M2Server/UsrEngn.pas` | 3,176 | `engine/world（tick）` | `直译` | P2 |
| `M2Server/ObjMon/2/3 + ObjAxeMon` | ~7,400 | `engine/monster（57 类）` | `直译`（策略模式重组） | P2/P3 |
| `M2Server/Envir.pas` | 1,575 | `engine/world/map` | `直译` | P2 |
| `M2Server/Magic.pas + JClasses.pas` | ~2,000 | `engine/skill` | `直译` | P3 |
| `M2Server/Castle/Guild/Event/ItmUnit` | ~4,500 | `engine/guild + castle + item` | `直译` | P3 |
| `M2Server/svMain + JNetwork + JSocket` | ~6,100 | `bootstrap + gate` | `替代`（Netty/启动类） | P1 |
| `M2Server/GameConfig/FunctionConfig 等 25 窗体` | ~9,000 | `admin（Web 控制台）` | `替代` | P1/P3 |
| `DBServer/HumDB.pas + DBSMain` | ~5,000 | `tools/migrator + character` | `拆分`（格式→迁移器，逻辑→DAO） | P1/P4 |
| `DBServer 其余（窗体/工具）` | ~4,000 | `admin` | `替代` | P3/P4 |
| `LoginSrv 全部` | 6,496 | `auth` | `直译`（IdDB→SQLite） | P1 |
| `LoginGate / SelGate / RunGate` | 12,351 | `gate（×3 Netty 端口）` | `替代`（过滤逻辑直译） | P1 |
| `GameCenter` | 6,652 | `—` | `废弃`（compose/systemd 替代） | — |
| `LogDataServer` | 3,216 | `logging（Logback）` | `废弃` | — |

> [!IMPORTANT]
> **清单维护规则：**此表随开发推进更新「状态」列（未开始/翻译中/已对拍/已合入），并同步 `docs/translation-map.md`；每阶段门评审时核对覆盖率。

<a id="appendix"></a>

## 15 · 附录 B · 文档索引与版本（Documents & Changelog）

| # | 文档 | 文件 | 定位 |
| --- | --- | --- | --- |
| ① | MIR2 项目分析报告 | [mir2-analysis-report.md](mir2-analysis-report.md) | 代码库全景：架构、模块、协议、风险（认知基线） |
| ② | Java 迁移可行性评估 | [mir2-java-migration-feasibility.md](mir2-java-migration-feasibility.md) | GO/NO-GO 决策依据：代码取证、12 章评估（决策基线） |
| ③ | 开发计划书（本文档） | `mir2-java-development-plan.md` | 执行蓝图：阶段/任务/门禁/规范/预算（执行基线） |

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| `v1.0` | `2026-09-18` | 首次发布：基于可行性评估 v1.1 的 GO 结论编制；30 周计划 / 6 决策门 / 240 人日 |
| `v1.0.1` | `2026-09-18` | 增加执行状态与会话交接记录：S0/W01 协议基座完成，W02 账号/角色领域服务完成，明确下一步为 gate + SQLite + 最小链路 |
| `v1.0.2` | `2026-09-18` | 更新已完成/未完成矩阵：Gate 初版、会话路由、SQLite 注入与重启恢复已完成；明确真实客户端链路、W03 世界闭环及 P0–P4 未完成项 |
| `v1.0.3` | `2026-09-18` | Gate 接入推进：实现真实 TCP 分帧、连接态认证桥接及登录/选服/角色操作初版字段映射；下一步调整为真客户端抓包验证、限速和 W03 |
| `v1.0.4` | `2026-09-18` | 增加可执行 bootstrap、配置与优雅停机，交付 fat JAR、Dockerfile/Compose；CI 已验证 JAR 三端口启动与镜像构建 |
| `v1.0.5` | `2026-09-18` | W03 world 内核交接：记录单线程 Tick、Delphi `.map`、碰撞占位、玩家生命周期、走跑转向及 12 格视野事件；固定下一步为 7200 RunLogin、移动 `CM_→world→SM_` 适配和真客户端验证；基线 `a493d2e`、CI run `35335312828` |
| `v1.0.6` | `2026-09-19` | 完成 7200 headerless RunLogin 首包解析、认证码/账号/已选角色联合校验及非法登录拒绝；下一步调整为 GAME→World 移动协议适配 |
| `v1.0.7` | `2026-09-19` | 完成移动协议适配器：`CM_TURN/WALK/RUN` 入站转换、`+GOOD/+FAIL` 动作确认及 `SM_TURN/WALK/RUN/DISAPPEAR` 观察者事件；下一步为进图消息与 GAME 会话生命周期接线 |
| `v1.0.8` | `2026-09-19` | 完成 GAME→World 生命周期接线、最小进图消息、`TCharDesc/TMessageBodyWL`、可配置出生点及双 socket 进入/出现/走跑/离视野/断线集成测试；下一步为真客户端对拍 |
| `v1.0.9` | `2026-09-19` | W03 战斗切片交接：近战攻击与动作间隔、伤害/击退消息、单种近战怪 AI、掉落表与拾取、经验结算全部落地；新增 `MIR2_MONSTER_COUNT/KIND` 配置与 `WorldCombatTest`、`GameCombatProtocolTest`；下一步固定为战斗与背包状态落库 |
| `v1.0.10` | `2026-09-19` | 完成 Ability/46 格背包 SQLite 事务存档、进图恢复、W02 schema 原位升级及角色性别/发型/装备外观 Feature；新增跨 Store/World 重启闭环测试，CI run `35427011522` 全绿；下一步为完整 `TClientItem/SM_BAGITEMS` 与真客户端对拍 |
| `v1.0.11` | `2026-09-19` | W04 物品目录与背包同步：完整 `StdItem`/`ItemDatabase`/SQLite `std_items`、`GetItemNumber` 语义的稳定 `MakeIndex`、耐久字段、76 字节 `TClientItem` 小端编解码（更正旧 "68 字节" 推导）、`CM_QUERYBAGITEMS → SM_BAGITEMS` 与 `SM_ADDITEM` 完整载荷、W03 背包原位升级；下一步为真客户端对拍 |
| `v1.0.12` | `2026-09-19` | W05 bot 压测军团：新增 `loadtest` 模块（真实线上协议全链路 bot、embedded/remote 双模式、Markdown/CSV 报告、`--prepare-db`）；50 bots × 5 分钟双模式 PASS（0 错误），CI 每 PR 增跑 50×2 分钟；G0「50 机器人」本地项完成；recorder/replayer 仍待做 |
| `v1.0.13` | `2026-09-20` | 固化受限沙箱工具链 Runbook：新增 `.github/workflows/java-server-dist.yml`（CI 编译 fat JAR 并 force-push 到 `dist` 孤儿分支，绕开被阻断的 Actions artifact 存储/Maven Central）；本 session 实测 run `35480880626` → git fetch → PyPI `jdk4py==21.0.8.2`（Temurin 21）→ `java -jar` 三端口启动全通；记录网络可达性矩阵与单分支克隆的显式 refspec 注意点 |
| `v1.0.14` | `2026-09-20` | W06 交付 traffic recorder / replayer 骨架，**P0 三件套齐活**：新增 `java-server/wiretool` 模块（`.mrec` v1 录制格式、字节透传代理、节奏回放 + 五分类对拍、结构级/字节级双判定、`inspect` 逐帧注解 + `--verify`、中文 Markdown/CSV 报告）；7 个新测试类；CI 新增 `wiretool-smoke` 门禁（实捕 bot 登录 → 结构级 PASS / 字节级因认证码如期 FAIL）；dist 通道同时发布 server/loadtest/wiretool 三 JAR；补写 Windows+Delphi 侧 golden 实捕操作指引 |
| `v1.0.15` | `2026-09-20` | W07 交付接入层加固第一项：**认证码 GAME 登录消费即失效 + 断线清理**（修复 W06 冒烟暴露的 `GateSessionRegistry.remove` 无调用点缺口）——`LegacyGateHandler.serveGame` 在世界真正接纳玩家后立即消费认证码，并在 `finally` 中做幂等兜底清理；先前"进图失败即重试"的语义（如快速重登留下的 `leavePlayer` 竞态）保持不变，因为消费只发生在真正进图成功之后，重试仍可复用同一认证码。新增 `GateSessionRegistryTest`（4 个用例：非消费性校验、移除后各校验路径均失败、幂等移除、未选角色/角色不匹配拒绝）及 `GameSessionIntegrationTest#certificationIsConsumedOnEntryAndCannotBeReplayedAfterDisconnect`（端到端证明断线后旧认证码无法重放进图）；连接数/频率限制、空闲超时仍是下一步候选（参考 Delphi 三网关共用的 `IsConnLimited`） |
| `v1.0.16` | `2026-09-20` | 并行推进 W08 后续：新增经典 `MonGen.txt` 解析器，支持 `loadgen` 包含、注释、引号怪物名与 GBK/UTF-8；通过 `MIR2_MONGEN_FILE` 将鸡/半兽人首批刷怪配置接入启动流程，并加入解析单测。刷怪复活调度与更多怪物模板仍留在后续切片 |
| `v1.0.18` | `2026-09-21` | 交付 **W12 物品切片：装备穿脱 + 使用 + 丢弃**（P2「物品」线收口）：① 13 槽 `THumanUseItems` 容器（`EquipmentSlot`/`Equipment`）与 `CheckUserItems` 槽位匹配，1.50 版 belt/boots/gem 三槽被注释掉的事实忠实复刻为「永不接受」；② `EquipmentBonus` 复刻 `ApplyItemParameters`（按 `ItemType` 三分支 + 饰品 StdMode 表，DC/MC/SC 在 case 外统一累加）与 `RecalcAbilitys` 的 `Weight/WearWeight/HandWeight` 三桶负重；③ `EquipRequirement` 复刻 `CheckTakeOnItems`（性别锁、两手槽独立负重预算、`Need` 0/1/2/3/10-13 门槛，转生/城堡类记 `TODO(verify)` 拒绝）；④ 四条命令 `CM_TAKEONITEM/CM_TAKEOFFITEM/CM_EAT/CM_DROPITEM` 与全部应答接通，新增 `AbilityCodec`(50B `TAbility`)、`ClientItemCodec.encodeWornSet`(`SM_SENDUSEITEMS` 分槽体)；⑤ **quirk**：成功取下后尾随一条 `SM_TAKEOFF_FAIL(recog=0)`（`n10` 保持 0 而出口判定为 `n10 <= 0`）；⑥ 新表 `character_equipment` + `itemMakeIndexHighWater` 跨背包/装备取最大；⑦ `MapFlags` 补 `NODRUG`/`NOTHROWITEM`。**另修复 master 编译中断**：W11(PR #25) 在 CI 红灯下合入，`WorldEngine.java` 有 17 处引用不存在的方法（`Ability.exp/withExp`、`Position.directionTo`、`Direction.rotateLeft/Right`、`ItemDrop.oneInChance/itemName`、`DoorStatus.openedAt`、`MoveResult/AttackResult.rejected` 单参重载等），本次全部对齐既有 API 并补 `addSpawner(MonsterSpawnDefinition)` 重载与两处测试修正。新增 `WorldEquipmentTest`(7)+`GameItemProtocolTest`(5)+`SqliteStoreTest`(+1)；**本地 165/165 全绿**（首次在沙箱内打通 ECJ+JUnit5 本地编译跑测链路，见 Runbook 新增小节）并对真服跑通装备全流程 E2E；下一步：导入真实 `StdItems.DB` 取代物品数值占位、耐久消耗与修理、技能切片 |
| `v1.0.19` | `2026-09-21` | 交付 **W14 等级提升 + 死亡/复活闭环**（P2「打怪闭环」收口）：① `LevelExperience` 复刻 `g_dwOldNeedExps`（1..500，51 起恒 4e9 → 溢出 int，全表用 `long`）+ `GetLevelExp` 越界取末项；② `LevelAbilities` 复刻 `RecalcLevelAbilitys` 三职业曲线，**战士 DC 最小值下限 1（法/道为 0）** 与 **仅战士有天然 AC** 两处 quirk 忠实保留；Delphi `Round` 为银行家舍入，全部改用 `Math.rint`；③ `Ability` 增 `maxExperience` + `consumeLevelExperience()`，复刻 `GetExp` 一次只扣一档、`MAXUPLEVEL=65535` 封顶后继续扣经验、`HasLevelUp` 尾部 `IncHealthSpell(2000,2000)`；④ **`Ability.defaultPlayer()` 改为忠实的 `UsrEngn.pas:503` 建号块（Level 1 / HP·MP 15 / DC 1-2 / MaxExp 100）**，取代此前 100HP/DC3-8 的 PoC 占位，连带复刻「升 2 级时 DC 从 1-2 收窄为 1-1」的 quirk；`SqliteStore` 旧库回填 SQL 不再硬编码而是取自同一基线；⑤ 玩家死亡：`Die` 时戳 + `ScatterBagItems` 1/3 背包掉落（`nDieScatterBagRate=3`、`DropWide=2`、地图 `NODROPITEM` 豁免）+ `RM_SENDDELITEMLIST → SM_DELITEMS`；⑥ `ReAlive`/`CmdReAlive` → `SM_ALIVE`（8B `TCharDesc` 体）、`MakeGhost` 3 分钟离场、重登 `HP<=0 → 14`；⑦ HP/MP 自然回复复刻 `TBaseObject.Run`（`div 20` 计数器、300/800 阈值、死亡跳过）；⑧ GM `@Level` 语义 `setLevel`；⑨ 职业 `btJob` 从 `Character` 打通到 world，`AbilityCodec.MaxExp` 不再恒 0；bot 接 `SM_LEVELUP`/`SM_ALIVE` 计数且死亡后改为重登而非退出。新增 `LevelTablesTest`(6)+`WorldLevelAndDeathTest`(6)+`GameLevelAndRevivalProtocolTest`(5)，**本地 184/184 全绿**（基线 166 → +18），8 bot × 45s swarm PASS。下一步：修理 NPC + 复活戒指消耗、技能/魔法切片、真实 `StdItems.DB`/`Monster.DB` 导入 |
| `v1.0.20` | `2026-09-21` | 交付 **W15 修理 NPC 最小闭环 + 复活戒指 + 金币**（P2 收官周主动切片）：① **复活戒指**（`TBaseObject.Run` HP=0 分支）：`m_boRevival` 随 `RecalcAbilitys` 重算（weapon/righthand/dress 走 AniCount、其余槽走 Shape，限 114/160/161/162，跳过 0 耐久；**dress 进 flag 分支却不被消耗**的不对称照搬）、攻击者 Shape 144 `m_boUnRevival` 压制、`dwRevivalTime=60s` 严格大于冷却（`m_dwRevivalTick` 初值 0 → 世界时钟前 60s 不触发，与 Delphi OS 时钟同构）；`ItemDamageRevivalRing` 逐槽扣 1000 **无 break**（双戒同耗）、归零 `SM_DELITEMS` 销毁清槽 + `RecalcAbilitys`、`SM_DURACHANGE` 仅千位桶变化才发（**银行家舍入 quirk：2500→1500 不发**）；成功 `HP:=MaxHP` + `SM_HEALTHSPELLCHANGED` + 绿字「复活戒指生效，体力恢复.」；② **修理三件套**：`CM_MERCHANTDLGSELECT` 复刻 `m_sScriptLable` 状态机（`@repair`/`@s_repair` → `SM_SENDUSERREPAIR` 开对话框，其余 label 仅记录；不引入脚本引擎，NPC 对象/距离校验留给 NPC 切片）；`CM_MERCHANTQUERYREPAIRCOST → SM_SENDREPAIRCOST`（-1 = 不能修）；`CM_USERREPAIRITEM`（MakeIndex 跨 param/tag 双字）→ `SM_USERREPAIRITEM_OK(recog=金币,param=Dura,tag=DuraMax)/FAIL`；普通修理 `Dec(DuraMax,磨损 div 30)` 后回满、特殊修理保持 DuraMax（label 比较用 Delphi `=` **大小写敏感**，`'@S_REPAIR'` 开对话框却按普通修理计费已复刻）；价格 `Round(nPrice div 3 / DuraMax × 磨损)`，**quirk：特殊修理报价 3×截断基数 vs 实扣未截断基数（差 1-2 金币），两公式均照搬**；StdMode 43 拒修、满耐久拒修、无此物品**静默**（Delphi Exit 无应答）；③ **金币**：`PlayerState.gold` 落库 `character_state.gold`（旧库原位升级默认 0）、`SM_ABILITY` 头部补 `recog=m_nGold`/`param=MakeWord(btJob,99)`（修复此前恒 0 清零客户端金币显示）、`SM_GOLDCHANGED`、`MIR2_TEST_GOLD` 登录下限（boTestServer/nTestGold 语义，默认 0）；④ 目录新增 `复活戒指` 占位（`TODO(verify)`）；bot 指标接金币/修理/复活绿字。新增 `WorldRevivalRingTest`(8)+`WorldRepairTest`(6)+`GameRepairProtocolTest`(4)，`SqliteStoreTest`/`ServerConfigTest` 各 +1；**本地全量 204/204 绿**（基线 184 → +20）。G3 门现状：打怪闭环全链路可用，但盲测/真实客户端对拍/影子对拍工具三项未完成，**未正式过门**。下一步：影子对拍 harness（P2 唯一未启动项）、真实 StdItems.DB 导入、技能切片（须先解除红线） |
| `v1.0.17` | `2026-09-20` | 补记 W09 合并（PR #22）并交付 **W10 门与传送点**：① `.map` `btDoorIndex` 门锚点解析（同 index ±10 共享 `TDoorStatus`）、`CM_OPENDOOR → SM_OPENDOOR_OK` ±12 广播、500ms 扫拍 5 秒自动关（`ProcessMapDoor` 语义，`SM_CLOSEDOOR` 首锚点广播 + 客户端 index 扩散）、「关门不挡路」客户端本地拦截 quirk 忠实复刻；② 经典 `MapInfo.txt` 加载器（`loadmapinfo`、`;` 注释、`[id|alias desc]` 条目、`GetValidStr3` 分隔符链路线行）、`MIR2_MAPINFO_FILE` 多图模式、连接点经 `WorldEngine.addRoute` 注册；③ 走/跑落格换图（`SM_CLEAROBJECTS→SM_CHANGEMAP→SM_MAPDESCRIPTION`、`ArroundDoorOpened` 压制、EnterAnotherMap 失败整步回滚），新 `MoveRejection.GATE_TARGET_UNPASSABLE`；新增 `WorldDoorAndTeleportTest`(8) + `MapInfoLoaderTest`(5) + `Mir2MapLoaderTest`(+2) + `GameProtocolAdapterTest`(+2) + `ServerConfigTest`(+1)；边界：城堡门/跨服切换/MapFlag/昼夜亮暗留后续切片，装备穿脱等仍对拍前禁做；下一步调整：真客户端对拍（仍卡 Windows 环境）+ bot-swarm 加压扩展/Netty 替换并行候选 |

> [!WARNING]
> **合规声明：**本计划仅用于技术学习与私密社区研究。传奇 IP 与美术资源版权归盛趣游戏 / Wemade 所有； 禁止商业运营、公开拉新与客户端资源分发。上线运营前请再次确认法律边界（详见评估报告第 09 节 R8）。

---

*📋 MIR2 → JAVA · DEVELOPMENT PLAN v1.0.20*

基线：ivanmissu/MIR2 · 9 程序 / 142,007 行 Pascal → 单 JVM / Linux·Docker · 兼容 mir2.exe 零改动

2026-09-18 编制 · 计划假设 2026-10-12 启动（可整体平移） · 前置阅读：项目分析报告 / 可行性评估报告
