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
    └── bootstrap/      进程启动入口与可执行 JAR 打包
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
- 账号与角色的 SQLite 持久化
- W03 世界核心：50ms 单逻辑线程 Tick、命令队列、对象进入/离开与确定性生命周期
- Delphi `.map` 文件加载（52 字节头、12 字节列优先单元）及背景/前景碰撞标志
- 八方向走路/跑步、动态占位碰撞、12 格方形视野与出现/移动/消失事件广播
- 可执行 shaded JAR 与 Docker Compose 打包

## 环境要求

| 依赖 | 版本 |
|---|---|
| JDK | 21 及以上 |
| Maven | 3.9 及以上 |
| Docker | 可选（容器化部署时使用） |

## 快速开始

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
| `MIR2_WORLD_TICK_MS` | `50` | 世界逻辑 Tick 间隔（毫秒） |
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
  进行中；
- W03 的世界内核、地图碰撞和移动视野事件已经实现，但 **7200 游戏网关尚未把真实客户端
  的 RunLogin / CM_WALK / CM_RUN 消息接入世界命令队列**，因此客户端当前仍不能实际进图行走；
- 战斗、怪物 AI、掉落、拾取和背包尚未实现；
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
