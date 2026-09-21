# mir2-front 产品设计方案（草案 v0.1）

## 0. 背景与定位

`java-server` 是 GameOfMir（Delphi 版传奇 2）服务端的 Java 重写，目标是与真实 `mir2.exe`
客户端字节级兼容。它目前只暴露**三个裸 TCP 端口**（7000 登录 / 7100 选人 / 7200 游戏），
协议是私有二进制帧（`#...!`、12 字节小端消息头、6-bit 编码、GBK 文本），浏览器 JS 无法
直接建立 TCP 连接。

`mir2-front` 的目标**不是**做一个像素级还原的网页游戏客户端，而是一个**服务端功能验证/联调
控制台**：

- 用最小成本走通「登录 → 选人 → 进图 → 走路/攻击/拾取」全链路，肉眼确认 `java-server`
  的行为符合预期；
- 暴露关键协议事件（服务端消息、ACK 结果）为人类可读的日志，帮助排查 bug；
- 支持同时开多个浏览器标签页（多账号/多角色）互相观察，验证视野广播、物品可见性等
  多人特性；
- 完全独立于 `GameOfMir`（Delphi 客户端）代码，不依赖、不修改它。

**明确的非目标**：不追求像素级 UI、不做技能/装备/交易等尚未在服务端实现的功能、
不考虑移动端适配、不做账号安全的生产级加固（这是内网/沙盒测试工具）。

---

## 1. 总体架构

浏览器无法直接 connect TCP，因此需要一个桥接层。选型结论（已与你确认）：

- **不修改 `java-server`**，新增一个**独立的 Node.js/TypeScript 桥接代理进程**
  （下称 *bridge*），代码放在 `mir2-front/` 内部（例如 `mir2-front/bridge`）；
- 浏览器与 bridge 之间用 **WebSocket + JSON**（语义化消息，例如 `login` /
  `walk` / `attack`，而不是原始字节）；
- bridge 与 `java-server` 之间用**原生 TCP**，说 `java-server` 的原生协议
  （帧格式、6-bit 编码、GBK），这部分逻辑需要在 TypeScript 中**重新实现一份协议编解码**
  （移植自 `protocol`/`gate` 模块的 `DefaultMessage` / `SixBitCodec` / `WireMessageCodec`
  等类）。

```
┌─────────────┐   WebSocket (JSON)   ┌───────────────────┐   TCP :7000/7100/7200   ┌──────────────┐
│ 浏览器 Tab A │ ───────────────────▶ │                    │ ───────────────────────▶│              │
│ (React 前端) │ ◀─────────────────── │  bridge (Node/TS)  │ ◀───────────────────────│  java-server │
└─────────────┘                      │  一个 WS 连接        │                         │  (三网关+world)│
┌─────────────┐   WebSocket (JSON)   │  = 一条完整的        │                         │              │
│ 浏览器 Tab B │ ───────────────────▶ │  登录/选人/游戏会话   │                         │              │
│ (React 前端) │ ◀─────────────────── │                    │                         │              │
└─────────────┘                      └───────────────────┘                         └──────────────┘
```

关键设计取舍：

- **一个浏览器 WS 连接 = bridge 里的一条完整会话状态机**（LOGIN → SELECT → GAME），
  与 `java-server` 里 `LegacyGateHandler.ConnectionState` 的状态转移一一对应；
  bridge 内部按需依次建立/切换到 7000 → 7100 → 7200 的 TCP 连接，对浏览器屏蔽这些细节。
- **多标签页天然支持多人观察**：每个标签页各开一条独立 WS 连接 → 各自一条独立的
  三段式 TCP 会话 → 在 `java-server` 世界里就是两个独立玩家。它们之间的互相可见
  （走近触发 `SM_TURN`/`ObjectMoved`/`ObjectAppeared` 广播）完全由 `java-server`
  现有的 12 格视野广播机制保证，bridge 不需要做任何“多路复用”或“转发给其他会话”的
  特殊处理——它只是忠实地把这条 TCP 会话收到的服务端消息转给对应的浏览器连接。
- bridge 是**无状态可水平扩展的**（状态都在每条 WS 连接自己的闭包里），单进程即可，
  不需要 Redis/DB。

### 为什么不直接在 java-server 里加 WebSocket 网关？

也评估过（`gate` 模块新增第四个 WebSocket 端口，直接转发到 `World`），协议路径更“真实”，
但会侵入正在快速演进、且以“比特级复刻 Delphi”为第一优先级的后端代码，跟“先验证、
后接入”的分工冲突。独立 bridge 可以完全在 `mir2-front` 范围内快速迭代，java-server
保持不变，一旦后端协议有 breaking change，只需要改 bridge 的编解码层。

---

## 2. 协议编解码移植范围（bridge 需要重新实现的部分）

对照 `java-server/protocol` 和 `java-server/gate` 现有实现，bridge 至少要移植：

| Java 源 | 用途 | TS 侧计划 |
|---|---|---|
| `DefaultMessage`（12 字节小端 `Integer + 4×Word`） | 消息头编解码 | `DefaultMessage.ts`，逐字节对照单测 |
| `SixBitCodec`（OLDMODE 6-bit） | 线上传输编码，头部固定编成 16 字节 | `sixBitCodec.ts`，用 Java 侧已有的 20 组黄金向量回归 |
| `WireMessageCodec` 的帧规则（`#<可选序号><16字节编码头><编码体>!`，`RunLogin` 首包，`+GOOD/+FAIL` 原始 ACK 帧） | TCP 帧读写 | `wireCodec.ts` |
| GBK 编解码（`ByteStrings`） | 文本字段 | Node 内置 `iconv-lite`（GBK 支持） |
| `CharacterDescription`（8 字节 Feature+Status） | 外观/状态包 | `characterDescription.ts` |
| `ClientItemCodec`（76 字节 `TClientItem`） | 背包物品编解码 | `clientItemCodec.ts` |
| `ProtocolConstants` 中用到的 `CM_*`/`SM_*` 常量子集 | 消息号 | 从 Java 常量表**脚本化生成**一份 TS 常量文件，避免手抄出错、且后端加新常量时可重新生成 |
| DES（`DesCodec`） | 目前登录路径**没有**用到 DES（`CM_IDPASSWORD` 明文经 6-bit+GBK 传输，未加密），暂不移植；标注为已知差距 | 先跳过，MVP 不需要 |

移植策略：**不追求逐行翻译**，而是对每个编解码器写“**与 Java 侧相同输入 → 相同输出**”的
比对测试（可以把 Java 单测里的向量原样搬到 TS 测试里跑一遍，两边字节相等即通过），
把 Java 代码当作唯一真理来源（source of truth）。

---

## 3. bridge ↔ 浏览器 的 WebSocket 协议（JSON）

设计为“语义化指令 + 语义化事件”，屏蔽底层帧/编码细节，前端只关心业务动作。

### 3.1 客户端 → bridge（指令）

```jsonc
{ "type": "login", "account": "hero", "password": "..." }
{ "type": "queryCharacters" }
{ "type": "createCharacter", "name": "aaa", "job": 0, "gender": 0, "hair": 0 }
{ "type": "deleteCharacter", "name": "aaa" }
{ "type": "selectCharacter", "name": "aaa" }        // 触发进入 GAME 网关 + RunLogin
{ "type": "walk", "direction": 4 }                   // 0..7，对应 Direction 枚举
{ "type": "run",  "direction": 4 }
{ "type": "turn", "direction": 4 }
{ "type": "attack", "kind": "HIT" | "HEAVY_HIT" | "BIG_HIT" }
{ "type": "pickup" }
{ "type": "queryBagItems" }
```

注：`walk`/`run`/`turn`/`attack` 里原协议需要客户端回传"自己当前认为的坐标"
（`Recog` 打包 x/y），bridge 会在会话状态里维护"当前坐标"（从服务端的确认事件更新），
指令本身不需要携带坐标，由 bridge 补全，简化前端逻辑、也避免坐标不同步导致的
`+FAIL`。

### 3.2 bridge → 客户端（事件）

```jsonc
{ "type": "loginResult", "ok": true }
{ "type": "loginResult", "ok": false, "reason": "密码错误" }
{ "type": "characterList", "characters": [{ "name": "aaa", "job": 0, "hair": 0, "level": 1, "gender": 0 }] }
{ "type": "mapEntered", "playerId": 1001, "mapId": "0", "mapTitle": "...", "x": 10, "y": 10,
  "visibleObjects": [ /* ObjectSnapshot[] */ ], "visibleItems": [ /* GroundItem[] */ ] }
{ "type": "objectAppeared" | "objectMoved" | "objectTurned" | "objectDisappeared", "object": { ... } }
{ "type": "actionResult", "action": "walk", "ok": true, "tick": 123456 }   // 对应 +GOOD/+FAIL
{ "type": "struck", "victimId": 1002, "attackerId": 1001, "damage": 7, "hp": 93, "maxHp": 100 }
{ "type": "death", "victimId": 1002 }
{ "type": "healthChanged", "objectId": 1001, "hp": 90, "mp": 20, "maxHp": 100 }
{ "type": "experienceGained", "gained": 5, "total": 105 }
{ "type": "itemShow" | "itemHide", "item": { "id": 1, "name": "...", "x": 1, "y": 1, "looks": 0 } }
{ "type": "bagUpdated", "items": [ /* BackpackItem[] */ ] }
{ "type": "log", "level": "info" | "warn" | "error", "message": "人类可读的一行日志" }
{ "type": "disconnected", "reason": "..." }
```

**日志需求（你选的“只要简单日志”）**：bridge 对每个进出的关键消息都生成一条
`log` 事件的人类可读描述（例如 `"[7200] 收到 SM_STRUCK：#1002 被 #1001 打了 7 点伤害"`），
前端只需要把这些日志行滚动展示成一个"控制台面板"，不需要做十六进制/字段级可视化
（你已明确不需要 wiretool 那种逐字节解析 UI）。

---

## 4. 前端设计

### 4.1 技术栈

React + TypeScript + Vite（生态成熟、组件化，方便后续加测试台功能）。

### 4.2 页面/模块划分

1. **连接与登录面板**：填服务端地址（bridge 的 WS 地址，默认同源）、账号、密码，
   显示登录结果。
2. **角色面板**：角色列表（查询/创建/删除/选择），沿用服务端已支持的字段
   （job/hair/level/gender）。
3. **游戏视图（简化 2D 网格，Canvas）**：
   - 不做美术资源、不做精灵动画，用色块/圆点 + 文字标签表示玩家、怪物、地面物品；
   - 支持方向键/按钮触发 walk/run/turn/attack/pickup；
   - 头顶显示 HP 条（用 `HealthChanged`/`ObjectStruck` 事件更新）；
   - 视野内对象随 `ObjectAppeared`/`ObjectMoved`/`ObjectDisappeared` 增删/移动，
     用于验证服务端广播是否正确。
4. **背包面板**：展示 `queryBagItems`/`SM_ADDITEM` 同步回来的物品列表（名称、耐久、
   makeIndex），用于验证掉落/拾取/存档链路。
5. **日志控制台**：滚动展示 bridge 转发的 `log` 事件，按 level 着色，可按连接/来自
   哪个网关过滤，方便对照“做了什么操作 → 服务端返回了什么”。
6. **多会话观察**：不需要在单页面里做多开——直接支持用户自行打开多个浏览器标签页，
   每个标签页独立登录/选人/进图；因为服务端广播机制天然让它们互相可见，
   页面里不需要专门的“多人视角切换器”（除非后续想要，作为二期功能）。

### 4.3 状态管理

一个 WS 连接对应一个前端 session store（可以用 Zustand/Context，具体轻量方案不影响
整体设计，实现时再定），保存：当前登录态、角色列表、当前地图快照（自己 + 视野内对象 +
地面物品）、背包、日志列表。

---

## 5. bridge 内部设计

### 5.1 会话状态机

复刻 `LegacyGateHandler` 的状态流转，但服务对象从"一个 TCP 客户端"换成"一个 WS 连接"：

```
IDLE
  │ login 指令
  ▼
CONNECTING_LOGIN ──(TCP connect 7000, 发 CM_PROTOCOL + CM_IDPASSWORD)──▶ LOGGED_IN
  │ 收到 SM_PASSOK_SELECTSERVER，自动发 CM_SELECTSERVER
  ▼
SELECTING_SERVER ──(收到 SM_SELECTSERVER_OK，解析下一跳 host/port)──▶ CONNECTED_SELECT
  │ queryCharacters / createCharacter / deleteCharacter 指令 直接透传
  │ selectCharacter 指令 → 发 CM_SELCHR，收到 SM_STARTPLAY（含 game host/port）
  ▼
CONNECTING_GAME ──(TCP connect 7200, 发 RunLogin 首包)──▶ IN_GAME
  │ walk/run/turn/attack/pickup/queryBagItems 指令 → 编码发送 + 维护本地坐标
  │ 持续读取 SM_* 广播 → 翻译成第 3 节的 JSON 事件推给浏览器
  ▼
（WS 断开 或 TCP 断开）→ 清理、关闭底层 TCP 连接
```

### 5.2 关键实现点

- **地址跳转**：`SM_SELECTSERVER_OK`/`SM_STARTPLAY` 里下发的是
  `MIR2_ADVERTISED_HOST/端口`。bridge 需要一个配置项决定连接策略：
  - 简单模式（推荐 MVP）：忽略服务端下发的 host，只取端口，始终连接
    配置好的 `java-server` 主机（因为沙盒/本地测试时 `advertisedHost` 未必是
    bridge 能直接访问的地址）；
  - 严格模式（可选二期）：完全按服务端下发的 host/port 连接，用于验证
    `MIR2_ADVERTISED_HOST` 配置本身的正确性。
- **坐标维护**：CM_TURN/WALK/RUN 需要客户端回传"自己认为的当前坐标"（`Recog`
  字段打包 x/y），bridge 在收到 `SM_LOGON`（进图）和历次 `MoveAccepted`/
  `ObjectMoved`（针对自己）事件时更新这个"本地已知坐标"，发指令时自动填入，
  前端不用关心。
- **一次性认证码**：`RunLogin` 需要 `certification`，bridge 从 `SM_STARTPLAY`
  的响应体里解析出来直接使用，一个 WS 会话只消费一次，与后端"认证码一次性消费"
  的语义天然吻合。
- **断线处理**：任一段 TCP 连接异常关闭 → bridge 推 `disconnected` 事件给浏览器，
  并关闭 WS（或允许前端点"重新登录"重新走一遍状态机，不做自动重连以免掩盖
  服务端问题——这是测试工具，异常应该显性暴露）。
- **并发/多连接**：bridge 用一个 Map 维护 `wsConnectionId -> SessionState`，
  之间无共享可变状态，天然支持多个浏览器标签页并发。

### 5.3 配置

通过环境变量（与 `java-server` 的命名风格对齐，方便使用者理解对应关系）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `BRIDGE_WS_PORT` | `8080` | bridge 监听的 WebSocket 端口 |
| `BRIDGE_TARGET_HOST` | `127.0.0.1` | `java-server` 所在主机 |
| `BRIDGE_LOGIN_PORT` | `7000` | 对应 `MIR2_LOGIN_PORT` |
| `BRIDGE_SELECT_PORT` | `7100` | 对应 `MIR2_SELECT_PORT`（严格模式下可被服务端下发值覆盖） |
| `BRIDGE_GAME_PORT` | `7200` | 对应 `MIR2_GAME_PORT` |
| `BRIDGE_ADDRESS_MODE` | `simple` | `simple`（忽略服务端下发 host）或 `strict`（完全遵循下发地址） |

---

## 6. 仓库结构规划

```
mir2-front/
├── DESIGN.md              # 本文档
├── bridge/                 # Node/TS 桥接代理
│   ├── src/
│   │   ├── protocol/        # 从 java-server/protocol 移植的编解码（DefaultMessage/SixBit/...）
│   │   ├── gateClient/       # 三段式 TCP 会话状态机（对照 LegacyGateHandler）
│   │   ├── ws/               # WebSocket 服务 + JSON 消息映射
│   │   └── index.ts
│   ├── test/                 # 编解码回归测试（对拍 java-server 的黄金向量）
│   ├── package.json
│   └── tsconfig.json
└── web/                     # React + TS + Vite 前端
    ├── src/
    │   ├── components/       # 登录面板/角色面板/画布/背包/日志控制台
    │   ├── store/
    │   └── ws/                # WS 客户端封装 + 类型定义（与 bridge 的 JSON 协议对应）
    ├── package.json
    └── vite.config.ts
```

前端和 bridge 的 JSON 消息类型建议共享一份 TypeScript 类型定义（比如
`mir2-front/shared/protocol-types.ts`），避免两边手写字段名不一致。

构建产物、`node_modules`、`dist` 等按仓库现有约定加入 `.gitignore`，不进 Git。

---

## 7. 分期计划（MVP → 完整闭环）

1. **M0：协议编解码对拍** —— TS 版 `DefaultMessage`/`SixBitCodec`/帧格式，
   用 Java 侧现有黄金向量跑一遍，字节级一致才算过；不涉及网络和 UI。
2. **M1：登录 + 选人闭环** —— bridge 走通 7000→7100，前端能登录、查询/建号/删号/
   选角色；日志面板打通。
3. **M2：进图 + 移动** —— 7200 RunLogin、走路/奔跑/转向、视野内对象的出现/移动/
   消失，Canvas 画布雏形。
4. **M3：战斗 + 拾取** —— 近战攻击、受击/死亡反馈、地面物品显示与拾取、背包同步。
5. **M4：多会话联调打磨** —— 多标签页互相可见的验收场景、断线/异常的清晰提示、
   README/操作手册。
6. **M5：装备 / 消耗 / 修理验证台** —— 将 W12/W15 已落地的 `CM_TAKEONITEM`、
   `CM_TAKEOFFITEM`、`CM_EAT`、`CM_DROPITEM`、修理三消息、`SM_ABILITY`、
   `SM_SENDUSEITEMS`、`SM_DURACHANGE` 和金币/负重事件贯通到 Bridge + React，作为
   下一步服务端协议对拍前的可操作验收面板。（已完成）
7. **M6：真实客户端对拍与协议冻结** —— 使用 Windows 上未修改的 `mir2.exe` 捕获
   LOGIN/SELECT/GAME golden，先做 `wiretool inspect` 结构核对，再逐帧修正 W12/W15
   字段和消息顺序；没有 golden 前不扩展 NPC 脚本或交易语义。
8. **M7（可选）**：在协议 golden 稳定后，增加 NPC 对象/距离校验和 Market_Def
   脚本最小切片；地图背景网格和 wiretool 高级可视化仍属于二期。

每期都以"人能用浏览器点出一个可验证的操作结果"为验收标准，而不是先把所有编解码
一次性搬完。

---

## 8. 风险与已知差距

- **协议本身仍在演进、未经真实 `mir2.exe` 字节级验证**（见仓库 README「已知限制」），
  bridge 的编解码只能对齐当前 Java 实现，Java 侧协议一旦因为 Delphi 对拍结果调整，
  bridge 要跟着改，两边会有短暂不一致窗口——这是选择"独立重新实现"这条路线的
  已知代价，通过 M0 的黄金向量回归测试来尽量压缩风险。
- **DES 未使用**：目前登录密码走明文（未加密）传输，仅在 6-bit+GBK 编码之上，
  MVP 不引入 DES 移植；如果后端后续给 `CM_IDPASSWORD` 加上 DES，这里要跟进。
- **地址下发（advertisedHost）与沙盒网络环境**：`simple` 模式规避了这个问题，
  但也意味着没有验证 `MIR2_ADVERTISED_HOST` 配置本身，需要在文档里说明。
- **不做自动化重连/断线重试**：作为测试工具，我们认为"清晰暴露异常"优先于"体验丝滑"。

---

## 9. 待确认的收尾问题

写代码之前，还有几个实现细节问题，等你确认（不影响整体架构，只影响一些默认值）：

1. bridge 和前端要不要放同一个 Node 工程用 workspaces（pnpm/npm workspaces）管理，
   还是完全独立的两个 `package.json`？
2. Canvas 画布的坐标系要不要跟 `.map` 文件的真实尺寸对齐（服务端当前默认
   256×256 空白 PoC 地图），还是先用一个固定的可视窗口（比如以玩家为中心画
   21×21 格，与视野广播范围一致）即可？
3. 日志控制台需要保留多长的历史（比如最近 500 条，超过滚动清掉），还是不设限？
