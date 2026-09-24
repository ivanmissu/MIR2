# MIR2 · 传奇 2 服务端 Java 迁移项目

本项目是将经典的《热血传奇 2》（Legend of Mir 2）服务端由 **Delphi 源码逐步迁移为 Java 实现**
的开源工程。仓库中同时保留了原始 Delphi 全套源码（客户端 + 服务端），以及正在开发中的
Java 服务端。

当前 Java 端处于 **S0 可执行基线** 阶段：协议编解码、账号登录、选人建号等链路已经打通，
游戏内世界逻辑仍在持续迁移中。

## 仓库结构

```
├── GameOfMir/          原始 Delphi 源码（传奇引擎全套）
│   ├── Client/         游戏客户端 (mir2.exe) 源码
│   ├── Common/         公共协议单元（Grobal2.pas / EDcode.pas / DES.pas 等）
│   ├── DBServer/       角色数据库服务器
│   ├── LoginGate/      登录网关
│   ├── LoginSrv/       登录服务器（账号验证）
│   ├── SelGate/        角色选择网关
│   ├── RunGate/        游戏运行网关
│   ├── M2Server/       游戏世界主服务器
│   └── ...
└── java-server/        ★ Java 迁移实现（Maven 多模块工程）
    ├── protocol/       协议层：消息结构、6 位编码、DES、GBK 字节处理
    ├── gate/           网关层：三端口 Socket 服务与会话路由
    ├── auth/           账号认证
    ├── character/      角色列表 / 创建 / 删除 / 选择
    ├── world/          单线程 Tick、地图碰撞、对象生命周期与视野广播
    ├── persistence/    SQLite 持久化
    ├── bootstrap/      进程启动入口与可执行 JAR 打包
    ├── loadtest/       bot 压测军团（全链路稳定性压测与报告）
    ├── wiretool/       流量录制/回放对拍（recorder 透明代理 + replayer 字节级对拍）
    └── shadowdiff/     影子对拍 harness（同一操作流驱动双服并 diff 状态快照）
```

## 已实现的 Java 功能

- Delphi `TDefaultMessage` 消息头（12 字节、小端序、紧凑布局）的精确复刻
- 旧版 OLDMODE 6 位传输编码 / 解码（对应 `EDcode.pas` 当前配置）
- DES 兼容封装（DES/ECB/NoPadding + Delphi 风格的零填充语义）
- GBK 边界助手：按**字节数**而非 UTF-16 字符数截断，与原客户端行为一致
- 302 个 `CM_` / `SM_` 协议常量（机械提取自 `Grobal2.pas`）
- 20 组确定性消息往返测试向量，外加二进制与 GBK 边界测试
- 三端口 Socket 服务器，兼容旧版 `#<序号><消息头><消息体>!` 帧格式
- 登录认证、服务器选择、角色列表 / 创建 / 删除 / 选择 的完整映射
- 账号与角色的 SQLite 持久化；角色性别、发型、衣服/武器外观按 Delphi `MakeHumanFeature` 生成 `Feature`
- W03 世界核心：50ms 单逻辑线程 Tick、命令队列、对象进入/离开与确定性生命周期
- Delphi `.map` 文件加载（52 字节头、12 字节列优先单元）及背景/前景碰撞标志
- 八方向走路/跑步、动态占位碰撞、12 格方形视野与出现/移动/消失事件广播
- 7200 游戏网关已接通世界：RunLogin 首包认证、`CM_TURN/WALK/RUN` 进队列、`SM_NEWMAP/LOGON/MAPDESCRIPTION` 进图
- **接入层加固（认证码一次性消费 + 断线清理）**：`GateSessionRegistry` 的认证码在成功进图后立即失效，
  同一认证码无法在第二条 GAME 连接上重放；GAME 连接断线（正常退出或异常）时同步清理 World 侧玩家对象
  与网关侧认证码，避免认证码/会话在客户端断线后悬挂；已进入世界失败的重试（如快速重登留下的
  `leavePlayer` 竞态）仍可安全复用同一认证码直至真正进图成功
- **近战战斗闭环**：`CM_HIT / CM_HEAVYHIT / CM_BIGHIT` 攻击判定（共用 Delphi 的 CM_HIT 动作间隔）、
  `SM_STRUCK / SM_HEALTHSPELLCHANGED / SM_DEATH / SM_WINEXP` 广播
- **近战怪物 AI**：鸡与半兽人两种模板（W09 起首批 10 种），视野内索敌、追击、按自身间隔攻击、死亡后尸体定时清理
- **掉落与拾取**：按 Delphi「N 分之一」概率的掉落表、`SM_ITEMSHOW / SM_ITEMHIDE`、`CM_PICKUP` 与 `SM_ADDITEM` / `SM_GOLDCHANGED`
- **真实官方数据库导入（W18）**：以官方 1.76 GEEM2 基线转储（`cjlaaa/Mir2-GeeM2`）为数据源，由
  `java-server/scripts/extract-geem2-db.py` 确定性生成受管工件 `db/MonsterDb.tsv`（378 行）、
  `db/StdItemsDb.tsv`（686 行，上游重名首行收敛为 684 项）与 `db/MonItems/`（10 张掉落表）；
  世界侧新增 `MonsterDb / StdItemsDb / MonsterDropTable` 加载器——首批 10 种怪 + 木桩的
  HP/防御/攻击/等级/经验/走攻节拍与掉落全部替换为真实数值（W04/W09/W12/W15/W17 的
  TODO(verify) 占位就此消除，`鹿肉` 占位掉落退役为真库 `肉`），SQLite `std_items` 启动
  种子自动跟随；`金币` 掉落行已接入地面金币堆，拾取后直接进入钱包并触发 `SM_GOLDCHANGED`。
- **`TStdItem.Reserved` 字节修正（W19）**：把 W18 曾临时并入 `NeedIdentify` 的官方
  `Reserved` 字节拆回独立字段，SQLite `std_items` 原位迁移旧缓存；`TClientItem` 线上
  76 字节布局现在会正确携带 祈祷/赤血 系列的 `Reserved` 标志，同时穿脱逻辑复刻
  `Reserved & 2` / `Reserved & 4` 的「无法取下」锁定分支。
- **死亡掉装备 + PK 等级模型（W20）**：`TPlayObject.DropUseItems` 两趟扫十三槽——
  `Reserved and 8` 的物品原地销毁、永不落地，并以**空名**条目（`/MakeIndex/`）回报客户端；
  其余每槽 `1/30` 掉落（`PKLevel > 2` 提高到 `1/15`），中签物品落在尸体 2 格内，但
  **只有 `Reserved and 10 = 0` 才真正离身**——「掉在地上却仍然穿着」的原版复制 quirk 照搬。
  谁杀的决定掉不掉：怪物杀掉装备、玩家杀不掉（`boKillByMonstDropUseItem` / 
  `boKillByHumanDropUseItem` 出厂值）。`RecalcAbilitys` 的三面护身旗
  （`m_boAngryRing` 全免 / `m_boNoDropItem` 只保包裹 / `m_boNoDropUseItem` 只保装备，
  武器·右手·衣服读 `AniCount`、其余槽读 `Shape`）同步实现。
  PK 侧：`PKLevel = m_nPkPoint div 100` 与白/黄($FB)/红($F9)/交手($2F) 名字颜色
  （交手色只在红名以下压过等级色）、谋杀 +100 点并双方收到原版 GBK 文案、受害者已挂
  PK 旗时判正当防卫不计点、每 2 分钟衰减 1 点（**旧等级为 1 或 2 时才重绘名字**的广播
  quirk 保留）、交手 60 秒染色，颜色经 `SM_CHANGENAMECOLOR` 广播；红名（`PKLevel >= 2`）
  死亡时 `boDieRedScatterBagAll` 让包裹**全掉**而非三分之一。`character_state.pk_point`
  原位加列落库，交手旗按原版语义不落库。同时纠正了一处长期错误：包裹掉落豁免用的是
  地图 `NODROPITEM` 旗，而非此前误用的 `NOTHROWITEM`（原版是两个不同的旗）。
- **真实客户端四联修复（W21）**：针对 mir2.exe 实机反馈的四个问题——
  (1) **进图人物被雾盖住**：按 `RM_LOGON`（`ObjBase.pas:5618`）原版顺序补发
  `SM_CHANGELIGHT`、`SM_FEATURECHANGED` 与主动 `SM_USERNAME`，并把
  `m_nLight`（右手有耐久装备即 3）打进 `SM_LOGON/SM_TURN/SM_WALK/SM_RUN` 的
  `Series=MakeWord(方向, 光照)` 高字节，装备变化经 `SM_CHANGELIGHT` 即时广播；
  未显式配置出生点且加载真实比奇省时自动采用经典出生点 289,618（角落默认 10,10
  在左上山区，人物被地形贴图盖住）。
  (2) **旧库图标错乱**（鸡掉"炼狱"）：启动时目录自愈 `reconcileStandardItems`，
  W18 权威导入内的行强制回归权威值，W04 占位残留（`鸡肉 Looks=41`）自动纠正，
  自建条目不动。
  (3) **小退失效**：`CM_SOFTCLOSE`（1009）按 `ObjBase.pas:4751` 语义处理（无回包、
  幂等移出世界），certification 改为跨进图保留（Delphi admission 是纯校验），账号
  重新登录时淘汰旧会话，客户端 `tcReSelConnect` 同证书回选人门可正常
  查询/重选/再进图。
  (4) **NPC 不可见**：最小 NPC 切片——`WorldEngine.spawnNpc` 以
  `MakeMonsterFeature(RC_NPC=50, 0, wAppr)` 特征放置静态 NPC（客户端
  `TNpcActor` 渲染），占格、入视野广播、可查名（`CM_QUERYUSERNAME →
  SM_USERNAME/SM_GHOST` 复刻 `CretInNearXY` 3×3 窗口），不可攻击；默认出生点旁
  3 个，`MIR2_NPC_LIST` 可自定义，Market_Def 商人功能仍在红线外。
- **Market_Def 商人指令清单 + 未知指令安全拒绝边界（W29）**：`MerchantCommand` 目录把
  `M2Share.pas` 26 个商人标签按「分类 × 完成度」全量归档（这不是脚本引擎，只做机械查表，不解析
  NPC 脚本、不做标签跳转）。`CM_MERCHANTDLGSELECT` 分派忠实于 `TMerchant.UserSelect`
  （ObjNpc.pas:1419）：`@repair`/`@s_repair` 沿用 W15 修理闭环，新增低风险 `@exit` 关窗
  （`RM_MERCHANTDLGCLOSE → SM_MERCHANTDLGCLOSE`，recog=商人 id）；买卖/仓库/制药/升级/冠名
  标记 `DEFERRED_TRANSACTION`、回跳/主菜单/消息标记 `DEFERRED_SCRIPT`，事务测试前一律不接线。
  关键红线：**未实现标签不再静默存下冒充成功**——一律发 `WorldEvent.MerchantActionRejected`
  （含 label/category/status/reason）+ 日志，让拒绝在测试/shadowdiff/日志中可观测，但线上仍与
  Delphi 一样不向真实客户端发包（原版这些分支由未设置的 `m_boXXX` 旗守卫，等价于「该商人不支持
  此功能」）。`@@useitemname` 复刻 `CompareLStr` 前缀匹配，其余标签 `CompareText` 大小写不敏感。
- **战斗/背包存档**：HP、MP、等级、经验与 46 格背包通过 SQLite 事务保存，在角色进图前恢复；支持从旧 W02 schema 原位升级
- **物品目录与背包同步（W04）**：最小标准物品库（`StdItem` 完整 `TStdItem` 字段 + SQLite `std_items` 表）、
  复刻 `GetItemNumber` 的稳定 `MakeIndex`、耐久字段、76 字节 `TClientItem` 小端编解码，
  以及 `CM_QUERYBAGITEMS → SM_BAGITEMS`（含空包静默）与 `SM_ADDITEM` 完整载荷
- **装备穿脱 / 使用 / 丢弃（W12）**：13 槽 `THumanUseItems` 装备容器（`U_DRESS..U_CHARM`），
  `CheckUserItems` 槽位匹配与 `CheckTakeOnItems` 穿戴条件（性别锁、手持/负重双预算、`Need` 等级与职业门槛），
  `RecalcAbilitys` 属性重算（`ApplyItemParameters` 按 `ItemType` 分支、`Weight/WearWeight/HandWeight`
  三桶负重、按穿戴 `Shape` 重算 `Feature` 外观并广播）；协议侧接通
  `CM_TAKEONITEM / CM_TAKEOFFITEM / CM_EAT / CM_DROPITEM` 与
  `SM_TAKEON_OK/FAIL`、`SM_TAKEOFF_OK/FAIL`、`SM_EAT_OK/FAIL`、`SM_DROPITEM_SUCCESS/FAIL`、
  `SM_SENDUSEITEMS`（分槽 `TClientItem`）、`SM_WEIGHTCHANGED`、`SM_ABILITY`（50 字节 `TAbility`）；
  装备槽由 SQLite `character_equipment` 表持久化，旧库开库即原位升级。
  原版 quirk 忠实复刻：成功取下装备后会尾随一条 `SM_TAKEOFF_FAIL(recog=0)`
  （Delphi 成功路径 `n10` 保持 0，而出口判定是 `if n10 <= 0`）
- **等级提升 / 死亡 / 复活闭环（W14）**：完整复刻 `g_dwOldNeedExps` 升级经验表（1..500 级，
  51 级起恒定 4,000,000,000）与 `GetLevelExp` 的越界钳制；`RecalcLevelAbilitys` 的战士 / 法师 /
  道士三条成长曲线（HP、MP、DC、AC、三桶负重上限），Delphi 的银行家舍入按 `Math.rint` 复刻；
  `GetExp / HasLevelUp` 升级链路（一次只扣一档经验、`MAXUPLEVEL` 封顶、升级即
  `IncHealthSpell(2000, 2000)` 回满）与 `SM_LEVELUP`；玩家死亡按 `nDieScatterBagRate = 3`
  掉落约三分之一背包（`DropWide = 2` 落点，地图 `NODROPITEM` 整体豁免）并用 `SM_DELITEMS`
  回推掉落清单；`ReAlive` 原地复活 + `SM_ALIVE`（8 字节 `TCharDesc` 体）、3 分钟
  `MakeGhost` 尸体离场、重登时 `HP <= 0 → 14` 的救场规则；`TBaseObject.Run` 的 HP/MP
  自然回复（6 秒回 `MaxHP div 75 + 1`、16 秒回 `MaxMP div 18 + 1`，死亡期间不回）；
  GM `@Level` 命令语义。
  **注意**：新建角色的初始属性已从 PoC 占位（100 HP / DC 3-8）改为忠实的原版建号块
  （1 级、HP/MP 15、DC 1-2、升级经验 100），世界难度因此回到原版水平——一个 1 级角色
  确实会被几只鸡打死。另一处 quirk 也一并复刻：角色升到 2 级时 DC 反而从建号字面量
  1-2 收窄为成长曲线的 1-1
- **修理 NPC 最小闭环 + 复活戒指 + 金币（W15）**：复活戒指复刻 `TBaseObject.Run` 的
  HP=0 分支与 `ItemDamageRevivalRing`——穿戴 `Shape/AniCount in [114,160,161,162]`
  （武器/右手/衣服槽看 AniCount，其余槽看 Shape）即获得 `m_boRevival`，致死一击触发
  60 秒冷却的原地满血复活（`SM_HEALTHSPELLCHANGED` + 绿字「复活戒指生效，体力恢复.」），
  每件复活装备扣 1000 耐久（**双戒同耗**，归零即 `SM_DELITEMS` 销毁清槽），
  `SM_DURACHANGE` 仅在千位桶变化时下发（银行家舍入 quirk：2500→1500 不发）；
  攻击者佩戴 Shape 144 装备可压制复活（`m_boUnRevival`）。修理走
  `CM_MERCHANTDLGSELECT` 的 `m_sScriptLable` 状态机（`@repair`/`@s_repair` →
  `SM_SENDUSERREPAIR` 打开客户端修理对话框）、`CM_MERCHANTQUERYREPAIRCOST →
  SM_SENDREPAIRCOST`（-1 = 不能修）与 `CM_USERREPAIRITEM → SM_USERREPAIRITEM_OK/
  FAIL`；普通修理 `DuraMax` 按磨损三十分之一衰减后回满、特殊修理保持 `DuraMax`；
  价格 `Round(价格 div 3 / DuraMax × 磨损)`（**quirk：特殊修理报价与实扣相差 1-2 金币**，
  均按原版复刻）。金币（`m_nGold`）随角色落库，`SM_ABILITY` 头部携带
  `recog=金币 / param=MakeWord(职业,99)`，`SM_GOLDCHANGED` 通知余额，
  `MIR2_TEST_GOLD` 复刻测试服登录金币下限（默认 0 = 不生效）
- **bot 压测军团（loadtest 模块）**：走真实 TCP 三端口全链路（登录→建号→进图→走/打/捡→周期性重登）
  的 50+ 机器人稳定性压测工具，输出中文 Markdown/CSV 报告（进图率、重登数、动作 +GOOD/+FAIL/超时、
  p50/p90/p99/max 应答延迟、服务端消息分布、错误分类）；支持 embedded（进程内起服务端 + JVM 堆采样）
  与 remote（对独立进程的 `mir2-server.jar`）两种模式
- **流量录制/回放对拍（wiretool 模块，P0 三件套之二）**：`record` 透明代理把 `mir2.exe ↔ 服务端`
  （Delphi 或 Java）的双向流量按 `#…!` 帧无损落盘 `.mrec`（原始字节透传、半关闭语义保持）；
  `replay` 将录到的客户端帧按录制节奏回放到目标服务端并与录制应答**逐字节对拍**（一致/内容差异+首差异
  偏移/缺失/多余/跳过五分类，中文 Markdown/CSV 报告，退出码即结论），支持已知易变帧跳过清单与
  `--structural-only` 结构级冒烟模式；`inspect` 逐帧注解（CM_/SM_ 名反查、6-bit+GBK 正文预览、
  RunLogin 识别且认证码打码）。Delphi 实捕 golden 后即成为协议回归的基线工具
- **接入层加固其二（W08）**：三网关共享 `AccessPolicy`——按 IP 的活跃连接上限、滑动窗口新连接频率
  限制、可配置读空闲超时；超限连接在认证前拒绝，默认值兼容 50 bot 压测
- **接入层尾项（W25）**：补齐 Delphi 网关剩余三项能力——① `BlockIPList.txt` 黑名单（永久表**前缀
  匹配**可封整个网段、临时表全等匹配，复刻 `CompareLStr` 的「条目长于待测地址则永不匹配」）；
  ② `BlockMethod` 三态处置（`disconnect` / `block` / `block-list`，后两者连带踢掉该 IP 的全部在线
  连接）；③ RunGate 独有的单次读取突发守卫（`>7000` 字节或 `>15` 帧即处置，`≤150` 字节不检查）。
  突发窗口改为复刻原版 1 秒 / 3 秒双 tumbling 窗（20 / 40）。两处原版 quirk 原样保留并在
  Javadoc 标注：突发窗滚动时计数置 0 导致每窗实放 N+1 条；新 IP 首连不查限额
- **怪物 AI 框架 + 首批 10 种怪、MonGen 自动刷新、在线周期存档（W09）**：`AGGRESSIVE` / `PASSIVE_FLEE`
  双行为模板（鸡/鹿/稻草人/多钩猫/钉耙猫/洞蛆/蝎子/半兽人/半兽勇士/半兽战士，鹿复刻 `TChickenDeer`
  逃跑 AI）；`addSpawner` 复刻 `RegenMonsters`——200ms 轮转、`CertList` 存活统计、按行内刷新间隔补足；
  `MIR2_SAVE_INTERVAL_SECONDS`（默认 600s）逐玩家周期落库（`SaveHumanRcdTime` 语义）；
  怪物线上 Feature 的 **RaceImg/Appr 已按官方 1.76 Monster.DB（GEEM2 基线转储）校正**
  （此前 Appr 占位值 0 会让真实 mir2.exe 把所有怪渲染成 `Mon1.wil` 第 0 块的卫士/大刀守卫），
  回归测试 `MonsterAppearanceTest` 钉死该对照表；战斗数值已由 W18 的真实 Monster.DB 导入收口
- **门与传送点（W10）**：`.map` 门锚点解析（`btDoorIndex` `$80` 锚点 + 同 index `±10` 共享
  `TDoorStatus`）、`CM_OPENDOOR → SM_OPENDOOR_OK`（±12 广播）、500ms 扫拍 + 5 秒自动关门
  （`ProcessMapDoor` 语义，广播 `SM_CLOSEDOOR`）；`MapInfo.txt`（`loadmapinfo` 包含、`;` 注释、
  `[id|alias desc idx]` 条目）多图加载与路线行（`src srcX srcY -> dst dstX dstY`）；
  走/跑落到连接点即换图（`SM_CLEAROBJECTS + SM_CHANGEMAP + SM_MAPDESCRIPTION`），邻近关门压制
  连接点（`ArroundDoorOpened`），目标不可走则整步回滚（`WalkTo` 语义）
- 可执行 shaded JAR 与 Docker Compose 打包

## 环境要求

| 依赖 | 版本 |
|---|---|
| JDK | 21 及以上 |
| Maven | 3.9 及以上 |
| Docker | 可选（容器化部署时使用） |

## 快速开始

> 📦 完整的部署指南（Docker Compose 快速启动、systemd 裸 JAR 部署、受限环境产物获取、
> 配置项参考与故障排查）见 **[java-server/docs/deployment.md](java-server/docs/deployment.md)**。

### 1. 构建与测试

在仓库根目录执行：

```bash
mvn -f java-server/pom.xml verify
```

### 2. 启动服务端

```bash
java -jar java-server/bootstrap/target/mir2-server.jar
```

默认监听三个 TCP 端口：**7000（登录）/ 7100（选人）/ 7200（游戏）**，数据保存在运行目录
下的 `data/mir2.db`。使用 `Ctrl+C` 或发送 `SIGTERM` 停止进程，关闭钩子会干净地释放监听
端口与 SQLite 连接。

### 3. 创建第一个测试账号

数据库为空时，可以通过环境变量引导一个初始账号：

```bash
MIR2_BOOTSTRAP_USER=hero \
MIR2_BOOTSTRAP_PASSWORD=change-me \
java -jar java-server/bootstrap/target/mir2-server.jar
```

引导账号只在该用户名不存在时创建；后续重启**不会**重置它的密码。

### 4. 压测（bot 军团）

`loadtest` 模块提供无需真实客户端的稳定性压测：机器人用**与 `mir2.exe` 相同的线上协议**
（`#…!` 帧、前缀轮转、12 字节小端消息头 + 6 位编码体、GBK）走完 登录→建号→进图→走/打/捡→周期性重登 全链路。

```bash
# 模式一：embedded——进程内起服务端（临时库 + 随机端口 + 堆采样），单命令即可复现
java -jar java-server/loadtest/target/mir2-loadtest.jar \
  --embedded --bots 50 --duration 1h --monsters 24 --relog-every 90s

# 模式二：remote——对已运行的 mir2-server.jar 实例压测（先准备好机器人账号）
java -jar java-server/loadtest/target/mir2-loadtest.jar \
  --prepare-db data/mir2.db --bots 50
java -jar java-server/loadtest/target/mir2-loadtest.jar \
  --host 127.0.0.1 --login-port 7000 --bots 50 --duration 1h --relog-every 90s
```

运行结束在 `reports/` 生成中文 Markdown + CSV 报告；退出码即压测结论（PASS=0）。
所有参数（持续时间、节奏、重登周期、随机种子、报告目录等）见 `--help`。
CI 在每个 PR 上跑一轮缩短版（50 机器人 × 2 分钟）作为稳定性回归。

### 5. 流量录制 / 回放对拍（wiretool）

`wiretool` 模块（P0「录制/回放/机器人」三件套之二）提供无需改客户端的流量捕获与回放对拍，
是后续 Delphi 字节级 golden 的捕获工具与协议回归工具：

```bash
# 录制：挂在客户端与服务端之间（Delphi 服务端抓 golden 同理，指向 Delphi 门端口）
java -jar java-server/wiretool/target/mir2-wiretool.jar record \
  --listen-port 7000 --target-host 127.0.0.1 --target-port 17000 \
  --out-dir captures --label mir2

# 摘要：逐帧注解（ident 名、GBK 正文、RunLogin），--verify 校验全部帧可解析
java -jar java-server/wiretool/target/mir2-wiretool.jar inspect \
  --file captures/<capture>.mrec --verify

# 回放对拍：客户端帧按原节奏重发到目标服务端，应答与录制逐字节比较
java -jar java-server/wiretool/target/mir2-wiretool.jar replay \
  --file captures/<capture>.mrec --target-host 127.0.0.1 --target-port 17000 \
  --max-speed --skip-server-frames 2        # 已知易变帧（认证码等）可跳过
```

- 回放退出码即结论：0 = 字节级一致，1 = 有差异（中文 Markdown/CSV 报告落在 `reports/`）。
- `--structural-only` 只判定帧序列形状（缺失/多余），忽略内容差异——适合认证码/tick 随机
  字段多发的会话冒烟；真正的 golden 判定请用字节级。
- 录制文件格式 `.mrec` v1：magic + 元数据行 + 逐条（方向、相对毫秒、长度、载荷）；
  帧外原始字节记为噪声段，整条流可逐字节重构；写入逐条落盘，kill 进程最多损失最后半条。

### 6. 影子对拍（shadowdiff，P2 收尾件 / G3 硬性要求）

`shadowdiff` 模块用**同一份确定性操作流**（走真实 `#…!` + RunLogin 线上协议）依次驱动两台
服务端，并在每一步之后 diff 玩家可观测状态（地图/坐标/朝向/HP/MP/等级/金币/背包/装备）：

```bash
# 模式一：embedded——进程内拉起两台独立 Java 服务端互拍（骨架自检 + 确定性回归）
java -jar java-server/shadowdiff/target/mir2-shadowdiff.jar --embedded --strict-messages

# 模式二：remote——对拍任意两台已运行的服务端（Delphi 侧就绪后即为真正的 Delphi↔Java 对拍）
java -jar java-server/shadowdiff/target/mir2-shadowdiff.jar \
  --left-label delphi --left-host 192.0.2.10 --left-login-port 7000 \
  --right-label java  --right-host 127.0.0.1 --right-login-port 7000 \
  --script my-ops.txt
```

- 操作脚本一行一个 op（`#` 注释）：`turn/walk/run/hit/heavyhit/bighit <0..7>`、`pickup`、
  `bag`、`say <文本>`、`drop/eat/takeon/takeoff <物品名>`、`sleep <ms>`、`tick <N>`、`relog`；
  缺省用内置冒烟脚本（含 relog 存档往返）。
- 判定分三级：**状态差异 / 应答（+GOOD/+FAIL）差异 = FAIL**，消息集合差异默认仅提示
  （`--strict-messages` 升级为 FAIL）；退出码即结论，报告落 `reports/shadow-report.md|.csv`。
- MakeIndex、对象 id 等服务端本地量已在快照中归一化，两台服务端只需世界配置一致。
- CI 在每个 PR 上跑一轮 embedded 自拍作为确定性回归。

#### `--ai`：对拍**会动的怪**（W23）

怪物 AI 的节拍是对时钟的区间判定（`ObjMon.pas:437` 走位、`ObjMon.pas:392` 攻击都读
`GetTickCount`），而两个进程永远不共享 OS 运行时间。因此在 W23 之前，对拍只能打不会动的
木桩：种子相同只能保证「第 N 次抽签相同」，保证不了「到底抽了几次」。

`--ai` 把两台服务端切到**手动世界时钟**，世界时间只在脚本的 `tick <N>` 上前进：

```bash
java -jar java-server/shadowdiff/target/mir2-shadowdiff.jar --embedded --ai --strict-messages
# 负向对照：只给右侧换种子，必须 FAIL
java -jar .../mir2-shadowdiff.jar --embedded --ai --right-seed 99999
```

- `tick <N>` 经 GM 命令 `@tick N` 下发，**仅手动时钟世界接受**，生产服直接回绝。
- 状态快照相应新增 `near=[(x,y) dir=N, ...]`（视野内其它角色的格子与朝向）与 `worldTime=`。
- 报告新增「观测轨迹」表，逐 op 打印左侧状态——PASS 因此可审计，能区分「0 差异」与「空转」。
- 实测对照（详见 `java-server/docs/g0-evidence/2026-09-23-w23-virtual-clock.md`）：把 AI 决策
  构造到恰好落在 op 观测边界上时，墙钟版 5/5 FAIL 且差异数逐次漂移，等价的 tick 版 5/5 PASS。

## 配置说明

所有配置均通过环境变量注入：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `MIR2_DATABASE` | `data/mir2.db` | SQLite 数据库文件路径 |
| `MIR2_LOGIN_PORT` | `7000` | 登录网关监听端口 |
| `MIR2_SELECT_PORT` | `7100` | 选人网关监听端口 |
| `MIR2_GAME_PORT` | `7200` | 游戏网关监听端口 |
| `MIR2_ADVERTISED_HOST` | `127.0.0.1` | 下发给客户端的下一段连接地址 |
| `MIR2_SERVER_NAME` | `MIR2` | 显示给客户端的服务器名称 |
| `MIR2_WORLD_CLOCK` | `system` | 世界时间源：`system` 读宿主墙钟（生产默认，等同 Delphi `GetTickCount`）；`virtual` 把所有节拍（怪物走位/攻击间隔、刷怪、回复、PK 衰减、昼夜）改为 tick 计数的纯函数，供对拍使用 |
| `MIR2_TEST_GOLD` | `0` | 测试服登录金币下限（`boTestServer`/`nTestGold` 语义；0 = 不生效） |
| `MIR2_MAP_FILE` | 未设置 | 可选：首张 Delphi `.map` 文件；未设置时建立 256×256 空白 PoC 地图（与 `MIR2_MAPINFO_FILE` 互斥） |
| `MIR2_MAPINFO_FILE` | 未设置 | 可选：经典 `MapInfo.txt`（`loadmapinfo` 包含、`[id|alias desc idx]` 地图条目、路线行）；按条目从同目录加载 `<id>.map` 多图并注册地图连接点（与 `MIR2_MAP_FILE` 互斥） |
| `MIR2_MAP_ID` | `0` | 首张地图 ID（对应客户端地图文件名；`MIR2_MAPINFO_FILE` 模式下必须是已加载地图之一） |
| `MIR2_SPAWN_X` / `MIR2_SPAWN_Y` | `10` / `10` | GAME 首次进图坐标；占用时自动选择邻近可行走格 |；未显式设置且加载真实比奇省 `0` 号图时自动采用经典出生点 289,618（W21）
| `MIR2_WORLD_TICK_MS` | `50` | 世界逻辑 Tick 间隔（毫秒） |
| `MIR2_MONSTER_COUNT` | `0` | 启动时在出生点四周生成的怪物数量（0 表示不生成） |
| `MIR2_MONSTER_KIND` | `chicken` | 怪物种类：`chicken`（鸡）或 `orc`（半兽人） |
| `MIR2_NPC_LIST` | 内置三人 | 可见 NPC 摆设（W21 最小切片）：`名字:外观:dx:dy` 逗号分隔，`外观`为 `Npc.wil` 精灵索引，`dx/dy` 相对出生点偏移；`none` 关闭。仅站立可见/占格/查名，不含 Market_Def 商人功能 |
| `MIR2_MONGEN_FILE` | 未设置 | 可选：经典 `MonGen.txt` 刷怪配置；支持 `loadgen`、地图/坐标/范围/数量/分钟/刷新率字段（当前启动时生成首批） |
| `MIR2_MAX_CONNECTIONS_PER_IP` | `50` | 三个网关合计的单 IP 活跃连接上限（RunGate `nMaxConnOfIPaddr`） |
| `MIR2_CONNECTION_BURST_LIMIT_1S` | `20` | 单 IP 每 1 秒新连接上限（`nIPCountLimit1`） |
| `MIR2_CONNECTION_BURST_LIMIT_3S` | `40` | 单 IP 每 3 秒新连接上限（`nIPCountLimit2`） |
| `MIR2_IDLE_TIMEOUT_SECONDS` | `900` | 已建立连接无数据时的读超时（秒） |
| `MIR2_BLOCK_IP_FILE` | 未设置 | 可选：`BlockIPList.txt` 路径。每行一条，**前缀匹配**（`203.0.113.` 可封整段）；文件缺失只告警不中断启动 |
| `MIR2_BLOCK_METHOD` | `disconnect` | 触发限额后的处置：`disconnect` 仅断开；`block` 加临时黑名单并踢掉该 IP 全部连接；`block-list` 同前但写入永久黑名单 |
| `MIR2_MAX_CLIENT_PACKET_SIZE` | `7000` | GAME 网关单次读取的字节上限，超出即按 `MIR2_BLOCK_METHOD` 处置 |
| `MIR2_NORMAL_CLIENT_PACKET_SIZE` | `150` | 低于该长度的读取不做突发检查（原版 `nNomClientPacketSize`） |
| `MIR2_MAX_CLIENT_MESSAGES_PER_READ` | `15` | GAME 网关单次读取内的最大消息帧数（`nMaxClientMsgCount`） |
| `MIR2_KICK_ON_OVERSIZE_PACKET` | `true` | `true` 踢人；`false` 只丢弃该次读取的数据并保持连接 |
| `MIR2_BOOTSTRAP_USER` | 未设置 | 可选：初始测试账号名 |
| `MIR2_BOOTSTRAP_PASSWORD` | 未设置 | 与初始账号配套的密码 |

> ⚠️ 如果客户端运行在**另一台机器**上，`MIR2_ADVERTISED_HOST` 必须设置为服务器的局域网
> 或公网地址，不能用默认的 `127.0.0.1`——否则客户端登录后会尝试连接它自己的回环地址。

## 客户端接入方式

服务端目标是与**原始 `mir2.exe` 客户端** 直接互通，接入流程遵循传奇经典的三段式握手：

1. **登录（7000 端口）**：客户端建立 TCP 连接，发送协议握手（`CM_PROTOCOL`）与账号密码
   （`CM_IDPASSWORD`）；服务端校验成功后回传服务器列表。
2. **选择服务器**：客户端发送 `CM_SELECTSERVER`，服务端根据 `MIR2_ADVERTISED_HOST` 和
   选人端口，把下一段连接地址（`主机/端口`）下发给客户端。
3. **选人（7100 端口）**：客户端携带登录令牌重连，进行角色查询（`CM_QUERYCHR`）、创建
   （`CM_NEWCHR`）、删除（`CM_DELCHR`）、选择（`CM_SELCHR`）。选择成功后服务端下放游戏
   网关地址。
4. **进游戏（7200 端口）**：客户端连接游戏网关进入世界（游戏内逻辑正在迁移中，见下文
   "已知限制"）。

**客户端配置要点**（以原始 Delphi 客户端为例）：

- 将客户端的登录器 / 登录配置指向服务器的 IP 与 `MIR2_LOGIN_PORT`；
- 客户端列表中的服务器名必须与服务端 `MIR2_SERVER_NAME` 一致；
- 跨机器部署时务必正确设置 `MIR2_ADVERTISED_HOST`，并放行 7000/7100/7200 三个端口的
  TCP 入站流量。

**协议帧格式**（供自制客户端 / 工具参考）：

- 每个数据包以 `#` 开头、`!` 结尾，单包最大 8192 字节；
- 消息头为 12 字节 `DefaultMessage`（无符号 16 位校验字段 + 有符号 32 位 `Recog`，与
  Delphi 的 `Word` / `Integer` 内存布局一致），经 6 位编码后在线上固定占 16 字节；
- 文本字段一律使用 GBK 编码，长度限制按字节计算；
- 当前 `EDcode.pas` 配置为 `ENDECODEMODE = OLDMODE`，因此**未**启用 NEWMODE 替换表——
  在没有 Delphi 抓取的金样本之前请勿改动。

## Docker Compose 部署

在仓库根目录执行：

```bash
MIR2_ADVERTISED_HOST=192.0.2.10 \
MIR2_BOOTSTRAP_USER=hero \
MIR2_BOOTSTRAP_PASSWORD=change-me \
docker compose -f java-server/compose.yml up --build
```

- SQLite 数据保存在名为 `mir2-data` 的卷中，容器重建不丢数据；
- 镜像以非特权用户运行，三个端口按同名环境变量映射暴露；
- `MIR2_ADVERTISED_HOST` 请替换为客户端实际可访问的地址。

## 已知限制与路线图

- 编译与启动冒烟测试**尚不能**证明与真实 `mir2.exe` 完全兼容，真实客户端联调验证仍在
  进行中；bot 压测军团（50 机器人 × 5 分钟全链路，embedded 与 remote 双模式，
  报告见 `java-server/docs/g0-evidence/`）、wiretool 录制/回放对拍骨架与 shadowdiff
  影子对拍 harness（embedded 双 Java 自拍严格判定 PASS，含 W23 起的 `--ai` 会动的怪确定性对拍；
  remote 模式待 Delphi 环境即插即用）均已先行就位，但**不能替代**真实客户端对拍与 Delphi 实捕
  golden；`--ai` 的 remote 用法还要求两侧都以手动时钟启动，Delphi 侧目前无 `@tick` 对应物；
- 7200 游戏网关已把 RunLogin、移动与战斗消息接入世界命令队列，并通过双会话 Socket 集成测试；
  但**尚未与真实 `mir2.exe` 对拍**，字段与消息顺序仍属待验证假设；
- 认证码一次性消费与断线清理仅覆盖 GAME 连接的会话生命周期；LOGIN/SELECT 网关的连接数/频率限制、
  空闲超时仍未实现（沿用 Delphi `IsConnLimited` 语义待移植），Netty 替换同样未完成；
- 近战战斗、近战怪物 AI、掉落/拾取及 HP/MP/等级/经验/背包重登存档已实现；W04 起背包条目携带完整
  `TStdItem` 模板、稳定 `MakeIndex` 与耐久，`SM_ADDITEM/SM_BAGITEMS` 输出 76 字节 `TClientItem`
  载荷（`TStdItem` 为 66 字节：`String[20]` 占 21 字节，Delphi 源码 "60 bytes" 注释已过时）；
  物品数值已由 W18 的真实 StdItems.DB 全量导入收口（684 项权威目录，占位与 TODO(verify) 消除；
  真库不存在 `鹿肉`，掉落表已改引用真库 `肉`）；`金币` 行已按 `Count div 2 + Random(Count)`
  生成地面金币堆，拾取只改钱包不占背包；W12 已补齐装备穿脱、使用（`CM_EAT`）与
  丢弃（`CM_DROPITEM`），装备属性映射逐条取自 `ItmUnit.pas` 且数值随真实目录一起权威；
  W15 的修理（普通/特殊）与复活戒指（`ItemDamageRevivalRing`）数值同样真实化
  （复活戒指 AC/MAC 0-1、Looks 175、等级 16、价 20000）；套装效果（Shape/AniCount
  111-217 表未收录行为）、技能/魔法、远程攻击与剩余 47 种怪物仍未实现——**Monster.DB 全 378 行
  虽已入库，但红线内 47 种怪不接线行为，仅数据待命**；修理目前是
  协议级最小闭环（`m_sScriptLable` 状态机 + 修理三消息）。W29 起商人标签走
  `MerchantCommand` 指令清单：`@repair`/`@s_repair`/`@exit` 已实现，其余 23 个标签
  按 `DEFERRED_TRANSACTION`/`DEFERRED_SCRIPT` 归档并在运行期**可观测拒绝**（不再静默），
  但 **NPC 对象、商家距离校验（`FindMerchant`）与 Market_Def 脚本引擎仍未实现**
  （买卖/仓库/制药/升级等待库存·金币·事务测试后再开放，受「NPC 脚本对拍前不得扩展」红线约束）；
- 等级提升与死亡/复活闭环（W14）已实现；死亡掉落自 W20 起**背包与已穿装备都覆盖**
  （`DropUseItems` + 删除列表空名 quirk + 红名 `boDieRedScatterBagAll` 全掉 + PK 等级模型）。
  仍未迁移的相关小项：`InDisableTakeOffList`（服务器配置文本清单，非目录列）、
  谋杀时的 `AddBodyLuck(-500)` 幸运惩罚与 `MakeWeaponUnlock`（缺幸运 / 武器锁模型），
  以及名字颜色的行会 / 沙城 / FIGHT3 分支（随行会系统切片）；玩家自助复活现有两条
  路径——复活戒指（W15，装备触发）与 GM 语义的 `WorldEngine.revive`，死亡后回城在
  原版里走的是重新登录路径；
- 门与地图连接点（W10）已实现：`.map` 门锚点 + `CM_OPENDOOR` + 5 秒自动关门、`MapInfo.txt` 多图与
  连接点换图（含目标不可走整步回滚）；昼夜亮暗（`DayBright`）、地图旗标（SAFE/FIGHT/NORECONNECT 等）、
  城堡门差异分支与跨服切换（`nServerIndex` 不同）仍未实现；
- DES 封装已按 Delphi 语义实现，但仍需 Delphi 端密文金样本做最终核对。

## 参与迁移

`java-server/docs/translation-map.md` 维护了 Delphi 源码到 Java 类的逐条对照表与 G0 证据
清单，欢迎按图索骥认领模块。提交前请确保：

```bash
mvn -f java-server/pom.xml verify
```

全部通过，CI（`.github/workflows/java-server.yml`）会做同样的校验。

## 许可证

本项目基于 [GPL-3.0](LICENSE) 开源。原始 Delphi 源码版权归原作者所有，仅供学习研究。
