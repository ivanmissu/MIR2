# W06 wiretool 录制/回放对拍 · 沙箱实机冒烟证据（2026-09-20）

**范围**：`mir2-wiretool.jar`（`record` / `inspect` / `replay`）对真实运行的 Java 服务端做
end-to-end 冒烟——代理实捕 bot 全链路流量 → 逐帧注解校验 → 三种模式回放对拍。
本证据证明的是**工具链闭环**，不证明协议与 Delphi 一致（那需要 Windows + mir2.exe 的实捕 golden）。

## 环境

- 产物：CI dist 通道（run `35483848016` /  ack 修复后 run `35484296354`）拉取
  `mir2-server.jar` / `mir2-loadtest.jar` / `mir2-wiretool.jar`，`sha256sum -c` 全部 OK
- 运行时：PyPI `jdk4py==21.0.8.2`（Temurin 21.0.8 LTS）
- 拓扑：`mir2.exe 等价 bot → wiretool 代理(27×00) → mir2-server(17×00)`，三门全部过代理

## 操作与结果

```bash
# 1) 种子账号 + 服务端（4 只鸡）
java -jar mir2-loadtest.jar --prepare-db mir2.db --bots 2
MIR2_DATABASE=mir2.db MIR2_LOGIN_PORT=17000 MIR2_SELECT_PORT=17100 MIR2_GAME_PORT=17200 \
  MIR2_MONSTER_COUNT=4 MIR2_MONSTER_KIND=chicken java -jar mir2-server.jar
# INFO: MIR2 Java server started: login=17000, select=17100, game=17200 ✅

# 2) 三门各挂一个录制代理
java -jar mir2-wiretool.jar record --listen-port 27000 --target-host 127.0.0.1 --target-port 17000 --label login
java -jar mir2-wiretool.jar record --listen-port 27100 --target-host 127.0.0.1 --target-port 17100 --label select
java -jar mir2-wiretool.jar record --listen-port 27200 --target-host 127.0.0.1 --target-port 17200 --label game

# 3) 2 个 bot 经代理全链路（登录→选人→进图→走/打/捡→重登）
java -jar mir2-loadtest.jar --host 127.0.0.1 --login-port 27000 --select-port 27100 --game-port 27200 \
  --bots 2 --duration 45s --ramp 1s --relog-every 20s --report-dir swarm-report
```

- bot-swarm 判定：**PASS（errors=0）**，2/2 进图，6 次进图、4 次重登、130 动作
  ——录制代理对线上流量完全透明；
- 捕获：每门 7 条连接各一个 `.mrec`（含 session-open/close marker），共 21 个文件。

## inspect --verify（逐帧注解 + 可解析校验）

登录门捕获（`login-…-27000-002.mrec`，8 事件 219ms）：

```
| 1 | 3 | 17 | C→S CM_PROTOCOL(2000) recog=0 p=0 t=0 s=0 |
| 2 | 6 | 16 | S→C SM_CERTIFICATION_SUCCESS(500) recog=0 p=0 t=0 s=0 |
| 3 | 70 | 36 | C→S CM_IDPASSWORD(2001) … body="bot0001/bot-pw" |
| 4 | 118 | 26 | S→C SM_PASSOK_SELECTSERVER(529) … s=1 body="MIR2/1/" |
| 5 | 162 | 23 | C→S CM_SELECTSERVER(104) … body="MIR2" |
| 6 | 210 | 51 | S→C SM_SELECTSERVER_OK(530) … body="127.0.0.1/17100/2116123599" |
verify：全部帧均可按包头/RunLogin 解析 ✅（exit 0）
```

游戏门捕获（`game-…-27200-002.mrec`，145 事件 20.3s）亮点：

- 首包注解为 **`RunLogin account=bot0001 character=bot0001 cert=*** version=120040918 code=9`**
  —— 无头 RunLogin 正确识别且认证码打码；
- 进图四连 `SM_NEWMAP(51) → SM_LOGON(50) → SM_MAPDESCRIPTION(54)` + 12 格视野 `SM_TURN/WALK`；
- 服务端原始动作应答帧识别为 **`ack +GOOD tick=…`**（ack 修复提交 `d8fc646` 的动机）；
- `CM_QUERYBAGITEMS(81)` **无应答** —— 空包静默与 W04 的 Delphi 语义一致；
- 杀鸡链路 `CM_HIT → SM_HIT/STRUCK/HEALTHSPELLCHANGED/DEATH/ITEMSHOW/WINEXP` 全部注解成功；
- `verify：全部帧均可按包头/RunLogin 解析 ✅（exit 0）`，145/145 帧 0 不可解析。

## replay 对拍（同一 live 服务端）

| # | 回放 | 模式 | 结果 | 判定细节 |
|---|---|---|---|---|
| 1a | 登录门 | 结构级 | **PASS exit 0** | 一致=2 内容差异=1 缺失=0 多余=0 |
| 1b | 登录门 | 字节级 | **FAIL exit 1** | 内容差异=1：`SM_SELECTSERVER_OK` 帧首差异**偏移 38**（`*/17100/` 后的认证码） |
| 1c | 登录门 | 字节级 + `--skip-server-frames 2` | **PASS exit 0** | 跳过=1，其余 2 帧逐字节一致 |
| 2a | 选人门 | 结构级 | **PASS exit 0** | 帧形状 1:1；过期认证码使 2 帧应答内容全变 |
| 2b | 选人门 | 字节级 | **FAIL exit 1** | 内容差异=2（`SM_QUERYCHR`/`NEWCHR_SUCCESS`），`SM_STARTPLAY` 因正文无认证码**逐字节一致** |
| 3 | 游戏门 | 字节级 | **FAIL exit 1** | 缺失=81（录制 113 帧 vs 实收 32 帧）——见下方「暴露的服务端事实」 |

→ 三种判定模式、跳过清单、MISSING 分类与退出码语义全部按设计工作；
报告样例见本目录同日期 CSV/MD（`replay-login-byte-….md` 等 6 份，已随证据留存）。

## 暴露的服务端事实（记录，不在本切片修）

1. **认证码不失效**：`GateSessionRegistry.remove(certification)` 定义了但**全库无调用点**
   —— 断线后认证码终身有效。游戏门回放用录制里的旧认证码 RunLogin **真的重新进图了**
   （收到新的 `SM_NEWMAP/LOGON/MAPDESCRIPTION` 与世界广播；113 录制帧中 `SM_MAPDESCRIPTION`
   因完全确定而逐字节一致，其余因 id/坐标不同而内容差异）。这解释了回放 #3 为何收到 32 帧而
   不是 1 帧 `SM_STARTFAIL`。→ 归「接入层加固」切片：GAME 登录消费即失效 + 断线清理 +
   连接数/频率限制一并处理（Delphi `M2Share` 会话表语义需先对照）。
2. 空 `+GOOD/+FAIL` 帧此前未被描述层识别（本次修复 `d8fc646`）——说明骨架的确会在
   真实流量上暴露盲区，边界已记录：注解永远只是描述，不参与录制/回放正确性。

## 局限（与设计一致）

- 回放为单连接语义，三段式握手需分三次回放；跨连接认证态不维持（#3 的 MISSING=81 即此语义
  在过期（本应）认证上的正常表现）。
- 结构级模式容忍认证码/tick/坐标等易变字段；**Delphi golden 判定必须用字节级 + 显式跳过清单**。
- 本证据只证明工具链闭环与 Java 服务端自洽；协议与 Delphi/mir2.exe 的一致性仍待
  Windows 环境实捕（操作指引见开发计划书 v1.0.14「W06 交接明细」）。
