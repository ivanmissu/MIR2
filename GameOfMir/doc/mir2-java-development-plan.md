# MIR2 服务端 Java 化迁移 开发计划书

*Development Plan · v1.0 · 2026-09-18*

**30 周日历（约 7 个月）** · **2 人团队 · 240 人日** · **6 道决策门 G0–G5** · **上线目标：2027 年 5 月** · **全程 Linux/Docker 交付**

本计划以《可行性评估报告》的 GO 结论为基线，将 8–12 人月的迁移工程拆解为 **1 个 PoC + 5 个阶段（P0–P4）+ 6 道决策门（G0–G5）**，覆盖团队分工、周级任务分解、工程规范、 CI/CD、发布回滚与预算，可直接作为项目执行与跟踪的依据。

> **执行状态（截至 2026-09-18）**：S0/W01 协议基座已完成；W02 账号、角色、SQLite 持久化已完成；Gate 接入与会话路由已完成初版；可执行 JAR、环境配置、优雅停机及 Docker Compose 已交付并通过 CI 启动冒烟。当前处于 **S0 PoC：真实客户端验证和 W03 游戏世界闭环之前**。下次继续开发请从本节的「当前下一步」开始；未完成项统一见「未完成清单」。

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
| 🟡 部分完成 | W02：接入骨架（7000/7100/7200） | 已完成三监听器、`#序号+消息头+消息体!` 分帧、连接状态、整数认证码桥接和登录/选服/角色查询建删选字段映射；Netty 替换、限速及真客户端验证未完成 | `java-server/gate`；`LegacyGateHandlerTest`、`WireMessageCodecTest` |
| ⬜ 未开始 | W03：tick、地图、移动广播 | 尚未实现 | 完成接入层后开始 |
| ⬜ 未开始 | W03：近战怪、击杀、掉落、拾取 | 尚未实现 | 完成 tick/world 后开始 |

### 当前下一步（Next Session）

1. 用真 `mir2.exe` 和 Delphi 抓包验证已实现的分帧及登录/选角字段，修正认证、应答确认符和异常码差异。
2. 为接入层补充连接数限制、消息大小/频率限制与空闲超时，再评估用 Netty 替换当前虚拟线程 socket transport。
3. 保持 Maven/JDK 21 CI 全绿；当前全量测试、可执行 JAR 启动冒烟及 Docker 镜像构建已在 GitHub Actions run `35329322893` 通过，后续为真实抓包补 golden 后继续扩充门禁。
4. 接入链路验证后进入 W03：单逻辑线程 tick、空世界、地图加载和移动广播。

### 未完成清单（明确边界）

#### S0 PoC

- ⬜ Delphi 实际 traffic recorder 与 20 组字节级 golden 对拍
- ⬜ 真 `mir2.exe` 登录、选区、角色列表、建删角色、进入世界
- ⬜ Tick 循环、地图加载、移动广播
- ⬜ 近战怪、击杀、掉落、拾取、重登不丢档
- ⬜ 50 机器人 × 1 小时稳定性验证
- ⬜ G0 决策门评审与 v0.1 基线 tag

#### P0/P1

- ⬜ 完整 216 SM_ / 51 CM_ 协议字段规格和 golden 套件
- ⬜ Netty 正式接入、IP 黑名单、连接限制、限速、防加速校验
- ⬜ 旧 IdDB/Hum.DB/Mir.DB 迁移器
- ⬜ env-lint 与 Envir 资源扫描
- ⬜ traffic-recorder / replayer / bot-swarm 三件套
- ⬜ Web 控制台

#### P2–P4

- ⬜ 地图碰撞、对象生命周期、九宫格视野、战斗和经验
- ⬜ 物品、背包、装备、存档周期保存
- ⬜ 57 种怪物 AI、59 个技能、NPC 脚本
- ⬜ 交易、组队、PK、红名、行会、攻城
- ⬜ 500 机器人 × 4 小时压测、灰度、Docker 双架构、上线回滚演练

> **当前已完成的范围只到账号/角色领域服务和接入层 PoC，以上未完成项不得视为已支持。**

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
| 计划版本 | v1.0（2026-09-18），基线：可行性评估报告 v1.1 |
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

> [!WARNING]
> **合规声明：**本计划仅用于技术学习与私密社区研究。传奇 IP 与美术资源版权归盛趣游戏 / Wemade 所有； 禁止商业运营、公开拉新与客户端资源分发。上线运营前请再次确认法律边界（详见评估报告第 09 节 R8）。

---

*📋 MIR2 → JAVA · DEVELOPMENT PLAN v1.0*  
基线：ivanmissu/MIR2 · 9 程序 / 142,007 行 Pascal → 单 JVM / Linux·Docker · 兼容 mir2.exe 零改动  
2026-09-18 编制 · 计划假设 2026-10-12 启动（可整体平移） · 前置阅读：项目分析报告 / 可行性评估报告
