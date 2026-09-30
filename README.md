# MIR2

《热血传奇 2》（Legend of Mir 2）服务端 Java 迁移项目。

仓库保留原始 Delphi 客户端/服务端源码，并持续将服务端迁移到 Java。当前 Java 服务端已经可以跑通登录、选人和进图等基础链路，但仍处于开发阶段，尚未完全替代原版服务端。

## 项目组成

```text
GameOfMir/      原始 Delphi 源码（客户端、网关、登录、选人、游戏服务端）
java-server/    Java 服务端，多模块 Maven 工程
mir2-front/     网页联调控制台（React + Vite + Node.js Bridge）
```

Java 服务端目前包含：

- 原版协议编解码：12 字节消息头、OLDMODE 6 位编码、GBK、DES；
- 三段式 TCP 服务：登录 `7000`、选人 `7100`、游戏 `7200`；
- 账号、角色、背包、装备和金币的 SQLite 持久化；
- 地图、移动、近战战斗、怪物 AI、掉落拾取、等级与死亡复活；
- 部分技能、NPC、地图连接点和网关安全策略；
- `loadtest`、`wiretool`、`shadowdiff` 等测试与对拍工具。

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- Docker（可选）

### 构建与测试

在仓库根目录执行：

```bash
mvn -f java-server/pom.xml verify
```

### 启动服务端

```bash
java -jar java-server/bootstrap/target/mir2-server.jar
```

默认监听：

| 端口 | 用途 |
|---:|---|
| 7000 | 登录网关 |
| 7100 | 选人网关 |
| 7200 | 游戏网关 |

默认使用 `data/mir2.db` 保存数据。首次启动可以创建测试账号：

```bash
MIR2_BOOTSTRAP_USER=hero \
MIR2_BOOTSTRAP_PASSWORD=change-me \
java -jar java-server/bootstrap/target/mir2-server.jar
```

账号只会在不存在时创建，重启不会覆盖密码。

## 常用配置

配置通过环境变量传入。跨机器连接时，必须把 `MIR2_ADVERTISED_HOST` 改成客户端能够访问的地址。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MIR2_DATABASE` | `data/mir2.db` | SQLite 数据库路径 |
| `MIR2_ADVERTISED_HOST` | `127.0.0.1` | 下发给客户端的服务器地址 |
| `MIR2_LOGIN_PORT` | `7000` | 登录端口 |
| `MIR2_SELECT_PORT` | `7100` | 选人端口 |
| `MIR2_GAME_PORT` | `7200` | 游戏端口 |
| `MIR2_MAP_FILE` | 未设置 | 可选的 Delphi `.map` 文件 |
| `MIR2_MAPINFO_FILE` | 未设置 | 可选的 `MapInfo.txt` 多地图配置 |
| `MIR2_SPAWN_X` / `MIR2_SPAWN_Y` | `10` / `10` | 出生坐标 |
| `MIR2_MONSTER_COUNT` | `0` | 启动时生成的怪物数量 |

完整配置、Docker、systemd 和故障排查见 [java-server/docs/deployment.md](java-server/docs/deployment.md)。

## 原版客户端接入

Java 服务端按原版三段式流程工作：

1. 客户端连接 `7000` 登录并获取服务器列表；
2. 连接 `7100` 查询、创建、删除或选择角色；
3. 连接 `7200` 进入地图并进行游戏。

将原版客户端的服务器地址指向部署机器，并确保 `7000`、`7100`、`7200` TCP 端口可访问。跨机器部署时尤其要设置：

```bash
MIR2_ADVERTISED_HOST=<客户端可访问的 IP 或域名>
```

## Docker Compose

```bash
MIR2_ADVERTISED_HOST=192.0.2.10 \
MIR2_BOOTSTRAP_USER=hero \
MIR2_BOOTSTRAP_PASSWORD=change-me \
docker compose -f java-server/compose.yml up --build
```

SQLite 数据保存在 Docker 卷 `mir2-data` 中。更完整的部署方式见 [部署文档](java-server/docs/deployment.md)。

## 网页联调控制台

`mir2-front` 提供登录、选人、地图、战斗、背包和协议日志等调试界面：

```bash
cd mir2-front
npm install
npm test
npm run dev
```

默认会启动 Bridge 和 Web 前端；详细说明见 [mir2-front/README.md](mir2-front/README.md)。

## 开发工具

构建完成后，可以使用以下工具做回归验证：

```bash
# 真实 TCP 全链路压测
java -jar java-server/loadtest/target/mir2-loadtest.jar \
  --embedded --bots 5 --duration 90s

# 两个服务端的确定性状态对拍
java -jar java-server/shadowdiff/target/mir2-shadowdiff.jar \
  --embedded --strict-messages
```

工具参数和协议录制/回放方式见各模块源码及 `java-server/docs/`。

## 当前限制

- Java 服务端仍在迁移中，尚未保证与 `mir2.exe` 的所有字段、消息顺序和行为完全一致；
- 完整的 NPC 商业脚本、行会/攻城/交易、部分技能和怪物行为仍未实现；
- 与 Delphi 服务端及真实客户端的字节级对拍仍在完善中。

欢迎通过 `java-server/docs/translation-map.md` 了解 Delphi 到 Java 的迁移对应关系。

## 许可证

项目基于 [GPL-3.0](LICENSE) 开源。原始 Delphi 源码版权归原作者所有，仅供学习研究。
