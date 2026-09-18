# MIR2 Java Server

Legend of Mir 2 (热血传奇) 服务端的 Java 迁移版本，从原始 Delphi 服务端与协议源码移植而来。本文档说明 `java-server/` 的构建、运行与配置方法。

## 功能概览

- 打包版 Delphi `TDefaultMessage`（12 字节，小端）
- 传统 OLDMODE 6-bit 传输编解码
- 兼容 Delphi 零填充语义的 DES 封装
- 按字节（而非 UTF-16 字符）限长的 GBK 边界辅助方法
- 从 `Grobal2.pas` 机械提取的 302 个 `CM_` / `SM_` 协议常量
- 采用传统 `#<序号><头><体>!` 帧格式的三端口 Socket 服务端
- 登录、选区、角色列表 / 创建 / 删除 / 选择的映射实现
- 基于 SQLite 的账号与角色持久化
- 可执行的 shaded JAR 与 Docker Compose 打包

## 环境要求

- JDK 21
- Maven 3.9+
- （可选）Docker，用于容器化部署

## 构建与运行

在仓库根目录执行：

```bash
# 编译并运行全部测试
mvn -f java-server/pom.xml verify

# 启动服务端
java -jar java-server/bootstrap/target/mir2-server.jar
```

默认监听 TCP 端口 **7000**、**7100**、**7200**，数据存储在工作目录下的 `data/mir2.db`。使用 `Ctrl+C` 或 `SIGTERM` 停止；关闭钩子会干净地关闭监听器与 SQLite。

### 创建首个测试账号

在空数据库上创建一个测试账号：

```bash
MIR2_BOOTSTRAP_USER=hero \
MIR2_BOOTSTRAP_PASSWORD=change-me \
java -jar java-server/bootstrap/target/mir2-server.jar
```

引导账号仅在该用户名尚不存在时创建；后续启动不会重置其密码。

## 配置

所有配置通过环境变量提供：

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `MIR2_DATABASE` | `data/mir2.db` | SQLite 数据库文件路径 |
| `MIR2_LOGIN_PORT` | `7000` | LoginGate 监听端口 |
| `MIR2_SELECT_PORT` | `7100` | SelGate 监听端口 |
| `MIR2_GAME_PORT` | `7200` | RunGate 监听端口 |
| `MIR2_ADVERTISED_HOST` | `127.0.0.1` | 返回给 `mir2.exe` 用于下一次连接的地址 |
| `MIR2_SERVER_NAME` | `MIR2` | 展示给客户端的服务器名称 |
| `MIR2_BOOTSTRAP_USER` | 未设置 | 可选的初始测试账号 |
| `MIR2_BOOTSTRAP_PASSWORD` | 未设置 | 与初始账号配对的密码 |

> 当客户端运行在另一台电脑时，`MIR2_ADVERTISED_HOST` 必须设置为服务端的局域网或公网地址，而不能是 `127.0.0.1`。

## Docker Compose

在仓库根目录执行：

```bash
MIR2_ADVERTISED_HOST=192.0.2.10 \
MIR2_BOOTSTRAP_USER=hero \
MIR2_BOOTSTRAP_PASSWORD=change-me \
docker compose -f java-server/compose.yml up --build
```

SQLite 数据保存在名为 `mir2-data` 的命名卷中。镜像以非特权用户运行。

## 兼容性说明

- `EDcode.pas` 当前为 `ENDECODEMODE = OLDMODE`，因此本基线不会应用 NEWMODE 替换表。在没有 Delphi 黄金采样对照前请勿更改。
- `DefaultMessage` 使用无符号 16 位校验与有符号 32 位 `Recog`，与 Delphi 的 `Word` 和 `Integer` 布局一致。
- 编译与启动冒烟测试尚未完整证明与 `mir2.exe` 的兼容性。真实客户端验证与 W03 游戏世界实现仍在进行中。

## 更多文档

- `java-server/README.md` — S0 可执行基线详情
- `java-server/docs/g0-checklist.md` — G0 检查清单
- `java-server/docs/translation-map.md` — Delphi → Java 翻译映射
- `GameOfMir/doc/mir2-java-development-plan.md` — Java 迁移开发计划
