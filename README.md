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
    └── wiretool/       流量录制/回放对拍（recorder 透明代理 + replayer 字节级对拍）
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
- **近战怪物 AI**：鸡与半兽人两种模板，视野内索敌、追击、按自身间隔攻击、死亡后尸体定时清理
- **掉落与拾取**：按 Delphi「N 分之一」概率的掉落表、`SM_ITEMSHOW / SM_ITEMHIDE`、`CM_PICKUP` 与 `SM_ADDITEM`
- **战斗/背包存档**：HP、MP、等级、经验与 46 格背包通过 SQLite 事务保存，在角色进图前恢复；支持从旧 W02 schema 原位升级
- **物品目录与背包同步（W04）**：最小标准物品库（`StdItem` 完整 `TStdItem` 字段 + SQLite `std_items` 表）、
  复刻 `GetItemNumber` 的稳定 `MakeIndex`、耐久字段、76 字节 `TClientItem` 小端编解码，
  以及 `CM_QUERYBAGITEMS → SM_BAGITEMS`（含空包静默）与 `SM_ADDITEM` 完整载荷
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
| `MIR2_MAP_FILE` | 未设置 | 可选：首张 Delphi `.map` 文件；未设置时建立 256×256 空白 PoC 地图 |
| `MIR2_MAP_ID` | `0` | 首张地图 ID（对应客户端地图文件名） |
| `MIR2_SPAWN_X` / `MIR2_SPAWN_Y` | `10` / `10` | GAME 首次进图坐标；占用时自动选择邻近可行走格 |
| `MIR2_WORLD_TICK_MS` | `50` | 世界逻辑 Tick 间隔（毫秒） |
| `MIR2_MONSTER_COUNT` | `0` | 启动时在出生点四周生成的怪物数量（0 表示不生成） |
| `MIR2_MONSTER_KIND` | `chicken` | 怪物种类：`chicken`（鸡）或 `orc`（半兽人） |
| `MIR2_MAX_CONNECTIONS_PER_IP` | `128` | 三个网关合计的单 IP 活跃连接上限 |
| `MIR2_CONNECTION_ATTEMPTS_PER_WINDOW` | `300` | 单 IP 滑动窗口内的新连接尝试上限 |
| `MIR2_CONNECTION_ATTEMPT_WINDOW_SECONDS` | `60` | 新连接频率窗口（秒） |
| `MIR2_IDLE_TIMEOUT_SECONDS` | `900` | 已建立连接无数据时的读超时（秒） |
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
  报告见 `java-server/docs/g0-evidence/`）与 wiretool 录制/回放对拍骨架已先行就位，
  但**不能替代**真实客户端对拍与 Delphi 实捕 golden；
- 7200 游戏网关已把 RunLogin、移动与战斗消息接入世界命令队列，并通过双会话 Socket 集成测试；
  但**尚未与真实 `mir2.exe` 对拍**，字段与消息顺序仍属待验证假设；
- 认证码一次性消费与断线清理仅覆盖 GAME 连接的会话生命周期；LOGIN/SELECT 网关的连接数/频率限制、
  空闲超时仍未实现（沿用 Delphi `IsConnLimited` 语义待移植），Netty 替换同样未完成；
- 近战战斗、近战怪物 AI、掉落/拾取及 HP/MP/等级/经验/背包重登存档已实现；W04 起背包条目携带完整
  `TStdItem` 模板、稳定 `MakeIndex` 与耐久，`SM_ADDITEM/SM_BAGITEMS` 输出 76 字节 `TClientItem`
  载荷（`TStdItem` 为 66 字节：`String[20]` 占 21 字节，Delphi 源码 "60 bytes" 注释已过时）；
  物品数值仍是最小占位目录，待导入真实 StdItems 数据后校正；装备穿脱、技能/魔法、远程攻击与
  57 种怪物仍未实现；
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
