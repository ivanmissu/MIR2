# MIR2 Java 服务端部署指南

本文档覆盖 MIR2 Java 服务端（`mir2-server.jar`）的部署、配置与验证：
Docker Compose 快速启动、裸 JAR + systemd 部署、受限环境（无 Maven Central）下的
dist 产物获取，以及完整的配置项参考与故障排查。

> 适用版本：W08 基线（master `c068d22`，2026-09-20）。
> 客户端接入方式与协议细节见仓库根目录 [README.md](../../README.md)。

---

## 1. 部署概览

服务端是**单进程、三端口**的 TCP 服务，SQLite 单文件持久化：

```
mir2.exe 客户端
   │
   ├─ ① TCP 7000  登录网关   账号验证、服务器列表、下发下一段地址
   │        └─ → ② TCP 7100 选人网关   角色查询/建/删/选，下发游戏网关地址
   │                 └─ → ③ TCP 7200 游戏网关   进图、移动、战斗、拾取（50ms 世界 Tick）
   │
   └─ 数据落盘: <data-dir>/mir2.db (SQLite: accounts / characters / character_state /
                character_inventory / std_items)
```

- 进程内为**虚拟线程 Socket + 单线程世界 Tick** 架构，无外部依赖（不需要 MySQL/Redis）。
- 优雅停机：`SIGTERM`/`Ctrl+C` 触发关闭钩子，释放监听端口并安全关闭 SQLite 连接。
- 接入层防护（W08）：按 IP 的活跃连接上限、滑动窗口连接频率限制、空闲读超时。

## 2. 快速启动（Docker Compose）⭐

### 2.1 前置要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| Docker Engine + Compose v2 | 20.10+ / v2.x | `docker compose`（带空格）为 v2 语法 |

镜像为多阶段构建（`maven:3.9-temurin-21` 构建 → `temurin-21-jre` 运行），
**首次构建需要拉取 Maven 依赖，耗时数分钟**；以非特权用户（uid 10001）运行。

### 2.2 最小启动（3 条命令）

```bash
cd java-server

# 1) 写一个 .env（至少设置 MIR2_ADVERTISED_HOST，跨机器访问时必须是客户端可达的地址）
cat > .env <<'EOF'
MIR2_ADVERTISED_HOST=192.168.1.10      # ← 改成客户端实际可访问的服务器 IP（本机试玩可用 127.0.0.1）
MIR2_BOOTSTRAP_USER=hero
MIR2_BOOTSTRAP_PASSWORD=change-me      # ← 务必改掉
MIR2_CLIENT_MAP_DIR=/srv/mir2-client/Map   # ← 客户端 Map 目录，只读挂进容器加载真实比奇省
MIR2_MONSTER_COUNT=8                   # 出生点安全区之外刷 8 只鸡；村里不会被攻击
MIR2_MONSTER_KIND=chicken
EOF

# 2) 构建并后台启动
docker compose up -d --build

# 3) 确认三端口就绪 + 查看启动日志
docker compose ps
docker compose logs -f mir2-server
```

看到下面这行即启动成功（`Ctrl+C` 退出日志跟随，不影响服务）：

```
INFO: MIR2 Java server started: login=7000, select=7100, game=7200,
      advertisedHost=192.168.1.10, database=/app/data/mir2.db, map=0(256x256),
      worldTickMs=50, monsters=8xchicken
```

首次启动还会输出 `INFO: Created bootstrap account 'hero'`（仅当账号不存在时创建，
**重启不会重置该账号密码**）。

### 2.3 验证部署（推荐三步）

```bash
# ① 端口探活（宿主机执行）
for p in 7000 7100 7200; do (echo >/dev/tcp/127.0.0.1/$p) 2>/dev/null \
  && echo "port $p: UP" || echo "port $p: DOWN"; done

# ② （可选）bot 冒烟压测：5 机器人 × 90 秒走真实三端口全链路
#    注意：--prepare-db 必须在服务端启动前对同一数据库执行（容器内是 /app/data/mir2.db，
#    可用 `docker compose exec mir2-server cat /app/data/mir2.db > mir2.db` 导出后播种，
#    或直接用 embedded 模式自验：java -jar mir2-loadtest.jar --embedded --bots 5 --duration 90）
java -jar mir2-loadtest.jar --host <服务器IP> --login-port 7000 \
  --bots 5 --duration 90 --relog-every 45s --report-dir reports
echo $?   # 0 = PASS

# ③ 数据落库检查（容器内 SQLite）
docker compose exec mir2-server ls -l /app/data/     # mir2.db 存在且非空
```

### 2.4 日常运维

| 操作 | 命令 |
|---|---|
| 查看日志 | `docker compose logs -f mir2-server` |
| 停止（保留数据） | `docker compose down` |
| 停止并**删除全部存档** | `docker compose down -v` ⚠️ |
| 升级到新版本 | `git pull && docker compose up -d --build` |
| 进入容器排查 | `docker compose exec mir2-server sh` |
| 备份存档 | `docker run --rm -v mir2-data:/data -v $PWD:/backup alpine cp /data/mir2.db /backup/` |

数据保存在命名卷 `mir2-data`（挂载点 `/app/data`），容器重建**不丢数据**。
注意 SQLite 不支持多进程并发写：**不要**在容器运行时用外部工具对 `mir2.db` 执行写操作。

### 2.5 端口冲突时

```bash
# .env 里改端口即可，compose 会同步映射宿主机端口
MIR2_LOGIN_PORT=17000
MIR2_SELECT_PORT=17100
MIR2_GAME_PORT=17200
```

## 3. 裸 JAR 部署（systemd）

适合物理机/VM 直跑，需要 JDK 21+。

### 3.1 获取 JAR 并放置

```bash
# 方式 A：本地构建（需要 JDK 21+ 与 Maven 3.9+，可访问 Maven Central）
mvn -f java-server/pom.xml -pl bootstrap -am -DskipTests package
# 产物: java-server/bootstrap/target/mir2-server.jar

# 方式 B：直接取 CI 发布的 dist 产物（无需本地构建，见第 4 节）
git fetch origin '+refs/heads/dist:refs/remotes/origin/dist'
mkdir -p /opt/mir2 && git --work-tree=/opt/mir2 checkout origin/dist -- mir2-server.jar
sha256sum mir2-server.jar | awk '{print $1}' | diff - mir2-server.jar.sha256 && echo "sha256 OK"
```

### 3.2 systemd 单元示例

```ini
# /etc/systemd/system/mir2-server.service
[Unit]
Description=MIR2 Java game server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=mir2
Group=mir2
WorkingDirectory=/opt/mir2
EnvironmentFile=/etc/mir2/mir2.env
ExecStart=/usr/bin/java --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75 -jar /opt/mir2/mir2-server.jar
Restart=on-failure
RestartSec=5
# 优雅停机：SIGTERM 触发关闭钩子（释放端口 + 关闭 SQLite），默认 90s 强杀
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
```

```bash
# /etc/mir2/mir2.env —— 全部可用变量见第 5 节
MIR2_DATABASE=/opt/mir2/data/mir2.db
MIR2_ADVERTISED_HOST=192.168.1.10
MIR2_BOOTSTRAP_USER=hero
MIR2_BOOTSTRAP_PASSWORD=change-me
# 裸 JAR 不走 compose，需显式指向客户端地图与原版出生点
MIR2_MAP_FILE=/srv/mir2-client/Map/0.map
MIR2_SPAWN_X=289
MIR2_SPAWN_Y=618
MIR2_MONSTER_COUNT=8

# 启用
sudo useradd --system --home-dir /opt/mir2 mir2 && sudo mkdir -p /opt/mir2/data \
  && sudo chown -R mir2:mir2 /opt/mir2
sudo systemctl daemon-reload && sudo systemctl enable --now mir2-server
sudo journalctl -u mir2-server -f
```

> 压测机器人播种（`mir2-loadtest.jar --prepare-db`）必须**在服务端启动前**执行；
> 服务运行期间的账号创建请走客户端注册协议。

## 4. 受限环境：dist 产物 + jdk4py

在无法访问 Maven Central / Actions artifact 的环境（如 Arena 沙箱，实测于 2026-09-20），
CI 的 `Java server dist build` 工作流会把三个 fat JAR（server / loadtest / wiretool）
连同 sha256 与 `dist-manifest.txt`（含 `source_commit`）强推到孤儿分支 `dist`：

```bash
# ① 取产物（含完整性校验）
git fetch origin '+refs/heads/dist:refs/remotes/origin/dist'
mkdir -p /opt/mir2 && git --work-tree=/opt/mir2 checkout origin/dist -- \
  mir2-server.jar mir2-loadtest.jar mir2-server.jar.sha256
cat /opt/mir2/dist-manifest.txt        # source_commit 应为期望的提交
(cd /opt/mir2 && sha256sum mir2-server.jar | awk '{print $1}' \
  | diff - mir2-server.jar.sha256 && echo "sha256 OK")

# ② 无系统 JDK 时，从 PyPI 装 jdk4py（Temurin 运行时）
pip3 install --target /opt/jdk jdk4py
export JAVA=/opt/jdk/jdk4py/java-runtime/bin/java   # 需 JDK 21+，实测 21/25 均可

# ③ 播种压测账号（先于服务端启动）并启动
cd /opt/mir2 && mkdir -p data
$JAVA --enable-native-access=ALL-UNNAMED -jar mir2-loadtest.jar \
  --prepare-db data/mir2.db --bots 5
MIR2_BOOTSTRAP_USER=hero MIR2_BOOTSTRAP_PASSWORD=change-me \
MIR2_MONSTER_COUNT=8 \
$JAVA --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75 \
  -jar mir2-server.jar
```

> `--enable-native-access=ALL-UNNAMED` 只是抑制 SQLite JDBC 本地库在新版 JDK 上的
> native-access 警告，不加也能跑。

## 5. 配置项参考（环境变量）

全部通过环境变量注入；非法值在启动时 **fail-fast**（进程拒绝启动并打印原因）。

### 5.1 网络与服务

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MIR2_LOGIN_PORT` | `7000` | 登录网关 TCP 端口 |
| `MIR2_SELECT_PORT` | `7100` | 选人网关 TCP 端口 |
| `MIR2_GAME_PORT` | `7200` | 游戏网关 TCP 端口 |
| `MIR2_ADVERTISED_HOST` | `127.0.0.1` | 下发给客户端的下一段连接地址。**跨机器访问必须改为客户端可达的 IP/主机名**，否则客户端会去连它自己的回环地址 |
| `MIR2_SERVER_NAME` | `MIR2` | 客户端服务器列表中显示的名字，不得含 `/` |

### 5.2 存档与地图

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MIR2_DATABASE` | `data/mir2.db` | SQLite 数据库路径（相对路径基于进程工作目录；容器内固定为 `/app/data/mir2.db`） |
| `MIR2_MAP_FILE` | `/maps/0.map`（compose）/ 未设置（裸 JAR） | Delphi `.map` 地图文件路径。`compose.yml` 默认把客户端 Map 目录只读挂到 `/maps` 并指向比奇省 `0.map`；未设置时回退到 256×256 空白 PoC 地图并打印告警（与 `MIR2_MAPINFO_FILE` 互斥） |
| `MIR2_MAPINFO_FILE` | 未设置 | 可选：经典 `MapInfo.txt` 路径（W10）；文件内每个 `[id desc idx]` 条目从同目录加载 `<id>.map`，缺失文件告警并跳过（Delphi `AddMapInfo` 语义）；路线行 `src srcX srcY -> dst dstX dstY` 注册为地图连接点，`loadmapinfo` 从 `MapInfo/` 子目录包含子文件 |
| `MIR2_MAP_ID` | `0` | 地图 ID（对应客户端地图文件名；`MIR2_MAPINFO_FILE` 多图模式下必须是已加载地图之一，否则启动 fail-fast） |
| `MIR2_SPAWN_X` / `MIR2_SPAWN_Y` | `289` / `618`（compose）/ `10` / `10`（裸 JAR 默认） | 首次进图出生点。compose 默认用原版比奇省新手村坐标（`!Setup.txt`：`HomeMap=0 HomeX=289 HomeY=618`）；被占用或不可走时自动选择邻近可行走格 |

### 5.3 世界与战斗

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MIR2_WORLD_TICK_MS` | `50` | 世界逻辑 Tick 间隔，1–10000ms |
| `MIR2_MONSTER_COUNT` | `0` | 启动时在出生点四周生成的怪物数，0–1000。怪物**均匀分布在安全区之外的一圈**（半径 `MIR2_SAFE_ZONE_SIZE + 1`），不会贴脸生成 |
| `MIR2_SAFE_ZONE_SIZE` | `10` | 出生点安全区半径（对应 `!Setup.txt` 的 `SafeZoneSize`）。对应 Delphi `TBaseObject.InSafeZone`：站在安全区内的玩家**不会被怪物选为攻击目标**（`IsAttackTarget`）。设为 0 可关闭（压测/对拍用），地图自带的 `boSAFE` 标志不受影响 |
| `MIR2_MONSTER_KIND` | `chicken` | 首批 10 种模板之一：`chicken`（鸡）、`deer`（鹿，逃跑型）、`scarecrow`（稻草人）、`hookcat`（多钩猫）、`rakecat`（钉耙猫）、`cavemaggot`（洞蛆）、`scorpion`（蝎子）、`orc`（半兽人）、`orcwarrior`（半兽勇士）、`orcfighter`（半兽战士）；另有 `trainer`（木桩，站桩不还手，对应 Delphi `TRAINER`=55 / `TTrainer` 伤害测试木桩，供对拍用）；中文名同样有效 |
| `MIR2_MONGEN_FILE` | 未设置 | 可选的经典 `MonGen.txt` 路径；支持 `loadgen`、引号怪物名、范围/数量/分钟/刷新率字段；每行注册为自动刷新的 spawner（对应 `TUserEngine.RegenMonsters`），按行内分钟数补足被击杀的怪物 |
| `MIR2_SAVE_INTERVAL_SECONDS` | `600` | 在线玩家周期存档间隔（对应 Delphi `SaveHumanRcdTime`，默认 10 分钟）；事件型存档（伤害/拾取/离场）不受影响 |
| `MIR2_TEST_GOLD` | `0` | 测试服登录金币下限（对应 Delphi `boTestServer`/`nTestGold`，`UserLogon` 语义）：登录时金币低于该值即补足并下发 `SM_GOLDCHANGED`；0 = 不生效，上限 10,000,000（`nHumanMaxGold`） |
| `MIR2_WORLD_SEED` | 未设置 | **世界随机种子**。未设置（生产默认）= 全服共用一条随机流，等价 Delphi 的全局 `Random()`。设置后随机性按子系统拆成 5 条互不干扰的流（伤害 / 装备磨损 / 掉落 / 死亡掉包 / 刷怪落点），各自由该种子派生——**掉落 roll 了几次不再影响第 N 次伤害**。两台服务端配同一个值即可对拍 PvE 数值（见 `docs/g0-evidence/2026-09-22-shadowdiff-pve-seeded.md`）；仅影响可复现性，不改变线上手感 |

### 5.4 账号引导

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MIR2_BOOTSTRAP_USER` | 未设置 | 初始测试账号名；仅在该账号不存在时创建 |
| `MIR2_BOOTSTRAP_PASSWORD` | 未设置 | 与初始账号配套的密码；两者必须同时设置 |

### 5.5 接入层防护（W08）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MIR2_MAX_CONNECTIONS_PER_IP` | `128` | 三网关合计的单 IP 活跃连接上限；超出在认证前拒绝 |
| `MIR2_CONNECTION_ATTEMPTS_PER_WINDOW` | `300` | 单 IP 滑动窗口内的新连接尝试上限 |
| `MIR2_CONNECTION_ATTEMPT_WINDOW_SECONDS` | `60` | 滑动窗口长度（秒） |
| `MIR2_IDLE_TIMEOUT_SECONDS` | `900` | 已建立连接无数据的读超时（秒） |

> 默认值兼容 50 机器人压测的连接模式（每 bot 三条连接 + 周期重登）。
> 真实公网部署请结合压测数据校准；所有值必须为正整数。

## 6. 部署验证清单

部署完成后按序核对（✅ 为 2026-09-20 沙箱实测值，dist 产物 `c068d22` + Temurin 25）：

| # | 检查项 | 方法 | 通过标准 |
|---|---|---|---|
| 1 | 进程与端口 | `ss -ltn \| grep -E '7000\|7100\|7200'` 或 `/dev/tcp` 探活 | 三端口全部监听 ✅（实测 `0.0.0.0:7000/7100/7200`） |
| 2 | 启动日志 | 日志中 `MIR2 Java server started` | 一行汇总，端口/DB/怪物数与配置一致 ✅ |
| 3 | 引导账号 | 日志 `Created bootstrap account '...'`（仅首次） | ✅ |
| 4 | 全链路冒烟 | `mir2-loadtest.jar --host H --login-port 7000 --bots 5 --duration 90` | 退出码 0（PASS）✅ 实测：5/5 进图、10 次进图（含 5 次重登）、688 动作、0 错误、应答 p90 < 100ms |
| 5 | 战斗/拾取闭环 | 冒烟报告统计 | 实测：`SM_ADDITEM` 8、`SM_WINEXP` 8、`SM_DEATH` 23、`SM_BAGITEMS` 5（空包静默符合设计）✅ |
| 6 | 存档落库 | SQLite 行数：`accounts/characters/character_state/character_inventory/std_items` | 实测：6 / 5 / 5 / 8 / 4（bot 捡到的 8 件物品已入背包表）✅ |
| 7 | 优雅停机 | `SIGTERM` 后进程退出、端口释放、重启数据仍在 | ✅（重启后 bot 账号与存档保留） |

## 7. 常见问题排查（FAQ）

**Q1：客户端登录后卡住/连不上选人端口。**
`MIR2_ADVERTISED_HOST` 用了默认 `127.0.0.1`。服务端把该地址下发给客户端作为
下一段连接目标——跨机器部署时客户端会去连**它自己**的回环地址。改为服务器局域网/公网 IP。

**Q2：日志出现 `WARNING: world event sink failed ... java.net.SocketException: Socket is closed`。**
已知现象（开发计划书 W05 交接记录）：玩家**重登/断线瞬间**，世界 Tick 向刚关闭的 socket
广播事件时的竞态。已有 catch 保护，**不中断 Tick、不丢状态**，会话清理语义有测试覆盖；
仅是日志噪音，可忽略。后续切片会在 gate 出站侧对已关闭连接静默。

**Q3：`bot-swarm` 大量连接被拒 / `Connection refused` 前先看限流。**
单 IP 超过 `MIR2_MAX_CONNECTIONS_PER_IP`（默认 128）或在窗口内尝试超过
`MIR2_CONNECTION_ATTEMPTS_PER_WINDOW`（默认 300/60s）的连接会在**认证前被拒**。
把 loadtest 与服务端部署在同一台机器/同 NAT 出口时，所有 bot 共享一个 IP——
加大这两个值或拉长 `--ramp`。

**Q4：bot 报账号不存在。**
remote 模式的 bot 账号须先用 `--prepare-db` 播种到**服务端使用的同一个数据库**，
且必须在**服务端启动前**执行（SQLite 不支持并发写）。embedded 模式无此要求。

**Q5：容器重启后数据丢了。**
确认没有执行过 `docker compose down -v`（该命令删除 `mir2-data` 卷）。
正常 `down`/`up` 与镜像重建都会保留卷。备份方法见 2.4。

**Q6：进程启动即退出，提示 `IllegalArgumentException`。**
配置校验 fail-fast：检查端口是否为整数、`MIR2_MONSTER_COUNT` 是否 0–1000、
`MIR2_WORLD_TICK_MS` 是否 1–10000、bootstrap 账号密码是否成对出现、
`MIR2_SERVER_NAME` 是否含 `/`。日志首行会给出具体字段。

**Q7：端口被占用。**
`Address already in use` → 换端口（2.5 节）或停掉占用进程。三个端口可独立配置，
但必须同时对外可达（客户端三段式握手逐段下沉）。

**Q8：想加载真实 Delphi 地图。**
设置 `MIR2_MAP_FILE` 指向 `.map` 文件（52 字节头 + 12 字节列优先单元格式，已实现加载与
背景/前景碰撞）。容器部署时需把文件挂载进容器并在 `compose.yml` 中追加 volume。
多张地图联动（含连接点传送）改用 `MIR2_MAPINFO_FILE` 指向经典 `MapInfo.txt`：把所有
`<id>.map` 与 `MapInfo.txt` 放到同一目录（`loadmapinfo` 子文件放 `MapInfo/` 子目录），
出生图由 `MIR2_MAP_ID` 指定；路线行两端地图未加载时该行被丢弃并告警。

**Q9：进游戏后世界是空的（左下角显示 `PoC empty map`），或出生点被一群鸡围住。**
2026-09-22 已修复默认配置。`compose.yml` 现在默认：

- 把客户端 Map 目录只读挂到容器 `/maps`，`MIR2_MAP_FILE=/maps/0.map` 加载**真实比奇省**；
- 出生点用原版 `!Setup.txt` 的 `HomeX=289 HomeY=618`（比奇省新手村）；
- `MIR2_SAFE_ZONE_SIZE=10` 建立出生点安全区，怪物不会攻击区内玩家，刷怪圈也被推到安全区外。

只需在 `.env` 里指出客户端 Map 目录：

```bash
# .env（与 compose.yml 同目录）
MIR2_CLIENT_MAP_DIR=/srv/mir2-client/Map      # Windows 例：D:/传奇客户端/Map
```

```bash
docker compose up -d --build
```

裸 JAR 方式等价配置：

```bat
set MIR2_MAP_FILE=C:\传奇客户端\Map\0.map
set MIR2_MAP_ID=0
set MIR2_SPAWN_X=289
set MIR2_SPAWN_Y=618
java -jar mir2-server.jar
```

要点：

- **客户端地形永远来自它自己的 `Map\0.map`**，服务端加载的 `.map` 只提供碰撞判定与地图 ID
  ——两边文件必须同名同源，否则会出现"人在墙里走"；
- 启动日志里的 `map=0(1000x1000)` 与客户端左下角的地图名（比奇省）可用来确认加载成功；
  若仍显示 `PoC empty map`，说明 `MIR2_MAP_FILE` 没生效，日志会有一条明确告警；
- 出生格若恰好不可走，服务端不再启动失败，而是告警并让玩家落在最近的可行走格
  （对应 `enterPlayerNear`）；
- 想要新手村外有怪练级，设 `MIR2_MONSTER_COUNT=8`：怪会均匀分布在安全区外围一圈，
  走出村子就能打到，站在村里不会被攻击。

**Q10：怪物在 mir2.exe 里显示成大刀守卫/卫士。**
2026-09-22 已修复：怪物线上的 Feature 外观编码此前是占位值（Appr 一律 0），客户端
`GetMonImg(0)` 会取 `Mon1.wil` 第 0 块贴图——正是卫士。现已按官方 1.76 Monster.DB
（GEEM2 基线转储）校正全部模板的 RaceImg/Appr（鸡 11/160、鹿 11/161、稻草人 18/27、
多钩猫 17/25、钉耙猫 17/26、洞蛆 16/24、蝎子 32/83、半兽人 19/100、半兽战士 19/101、
半兽勇士 19/102、练功师 19/72），回归测试见
`world/src/test/java/com/mir2/world/MonsterAppearanceTest.java`。更新到含此修复的
构建（`dist` 分支产物或重新 `docker compose up --build`）即可。

**Q11：角色血条/蓝条是空的，底部经验条和负重也不显示。**
2026-09-22 已修复。`SM_ABILITY` 的 50 字节 `TAbility` 包尾部有六个负重字段
（`Weight/MaxWeight/WearWeight/MaxWearWeight/HandWeight/MaxHandWeight`），此前一律写 0。
`SM_WEIGHTCHANGED` 只携带三个**当前**值（`ClMain.pas:4473`），上限只能靠 `SM_ABILITY`
下发，而客户端 `FState.pas:3646` 要求 `(MaxExp > 0) and (MaxWeight > 0)` 才绘制底部状态条，
`Actor.pas:2633` 还会拿它做除数。现已按 `RecalcLevelAbilitys`（`ObjBase.pas:1889`）的
职业/等级曲线下发真实上限（战士 1 级为 50/15/12）。回归测试见
`gate/src/test/java/com/mir2/gate/AbilityWeightBlockTest.java`。

若仍是空条，请确认客户端连的是含此修复的构建（`dist` 分支产物或重新
`docker compose up --build`）。

## 8. 生产环境建议（P4 前的过渡态）

当前版本（W08）适合内网联调、压测与对拍环境，**尚未**完成真实 `mir2.exe` 字节级对拍。
公网暴露前至少：

1. 修改 `MIR2_BOOTSTRAP_PASSWORD`，或改用客户端注册协议建号后移除 bootstrap 变量；
2. 按实际压测数据校准 5.5 节限流参数（Delphi `IsConnLimited` 语义的阈值仍在等待 golden 校准）；
3. 用 compose/卷快照或 2.4 的备份命令做 `mir2.db` 定期备份（SQLite 单文件，冷备即可）；
4. 只放行 7000/7100/7200 三个 TCP 端口，`MIR2_ADVERTISED_HOST` 用公网 IP；
5. 长稳验收：`mir2-loadtest.jar --host ... --bots 50 --duration 1h`（G0 口径为 50×1h PASS）。

---

*本文档的部署流程于 2026-09-20 在 Linux 沙箱实测通过（dist 产物 `c068d22`，dist-manifest
build_run `35494175252`，sha256 校验通过；三端口监听、bootstrap 建号、8 怪世界、5 bot × 90s
remote 冒烟 PASS、存档落库与优雅停机均验证）。Docker Compose 路径由 CI 门禁
（`docker compose config` + 镜像构建 + JAR 冒烟）保障，与本文档配置一致。*
