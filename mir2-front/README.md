# mir2-front 网页联调客户端与协议代理

`mir2-front` 是专为 `java-server`（Delphi 版传奇 2 服务端重写）打造的**服务端功能验证与全链路联调控制台**。

由于 `java-server` 暴露的是私有二进制 TCP 端口（7000 登录 / 7100 选人 / 7200 游戏），浏览器前端通过本工程内置的 **Node.js/TypeScript Bridge 桥接代理**与后端建立原生 TCP 连接，并使用 WebSocket + JSON 协议驱动网页端控制台。

---

## 1. 架构总览

```
┌─────────────────────────────────┐                 ┌─────────────────────────────┐                 ┌─────────────────────────────┐
│       React 18 + Vite 前端       │                 │     Bridge 代理 (Node.js)    │                 │   java-server / Mock 服务    │
│                                 │                 │                             │                 │                             │
│ ┌─────────────────────────────┐ │ WebSocket(JSON) │ ┌─────────────────────────┐ │   TCP (:7000)   │ ┌─────────────────────────┐ │
│ │ 2D 网格画布 (Canvas 视图)    │ │◀───────────────▶│ │  三段式会话状态机        │ │◀───────────────▶│ │ 登录网关 (LOGIN Gate)   │ │
│ │ 角色管理 / 状态与属性面板    │ │                 │ │  LOGIN -> SELECT -> GAME  │ │                 │ └─────────────────────────┘ │
│ │ 46 格背包物品面板           │ │                 │ └─────────────────────────┘ │   TCP (:7100)   │ ┌─────────────────────────┐ │
│ │ 实时协议与事件日志控制台     │ │                 │ ┌─────────────────────────┐ │◀───────────────▶│ │ 选人网关 (SELECT Gate)  │ │
│ └─────────────────────────────┘ │                 │ │ 字节级协议编解码器       │ │                 │ └─────────────────────────┘ │
│                                 │                 │ │ SixBit/DefaultMsg/Items │ │   TCP (:7200)   │ ┌─────────────────────────┐ │
└─────────────────────────────────┘                 │ └─────────────────────────┘ │◀───────────────▶│ │ 游戏网关 (GAME Gate)    │ │
                                                    └─────────────────────────────┘                 │ └─────────────────────────┘ │
                                                                                                    └─────────────────────────────┘
```

---

## 2. 工程目录结构

```
mir2-front/
├── DESIGN.md                 # 产品设计方案文档
├── README.md                 # 本说明与操作手册
├── package.json              # npm Workspace 根配置
│
├── shared/                   # 前后端共享类型与协议常量
│   ├── src/constants.ts      # CM_* / SM_* 消息号、方向、职业、性别枚举
│   ├── src/types.ts          # WebSocket JSON 命令与事件类型定义
│   └── src/index.ts
│
├── bridge/                   # Node.js + TypeScript 桥接代理服务
│   ├── src/
│   │   ├── protocol/         # 字节级协议编解码移植 (对标 java-server)
│   │   │   ├── ByteStrings.ts       # GBK 编码与字符边界保护
│   │   │   ├── SixBitCodec.ts       # 传奇经典 6-bit 编码 (OLDMODE)
│   │   │   ├── DefaultMessage.ts    # 12 字节小端 TDefaultMessage 头
│   │   │   ├── MessageCodec.ts      # 16 字节头部编解码
│   │   │   ├── CharacterDescription.ts # 8 字节 TCharDesc (Feature+Status)
│   │   │   ├── ClientItemCodec.ts   # 76 字节 TClientItem 背包物品编解码
│   │   │   └── WireMessageCodec.ts  # TCP 帧封包 (#...! / RunLogin / +GOOD/+FAIL)
│   │   ├── tcp/
│   │   │   ├── MirTcpClient.ts      # 带帧分包与序号的 TCP 客户端
│   │   │   └── MockMirServer.ts     # 高保真独立模拟服务端 (供无外置 Java 环境下自测)
│   │   ├── session/
│   │   │   └── GateSession.ts       # 登录 -> 选人 -> 进图三网关状态机
│   │   ├── ws/
│   │   │   └── WebSocketServer.ts   # WS 接口服务与事件分发
│   │   └── index.ts                 # Bridge 入口
│   └── test/                        # 黄金向量与全链路回归单测
│
└── web/                      # React 18 + Vite + TailwindCSS 网页控制台
    ├── src/
    │   ├── components/
    │   │   ├── Header.tsx           # 顶部导航与会话状态
    │   │   ├── LoginPanel.tsx       # 7000 网关登录表单与预设
    │   │   ├── CharacterPanel.tsx   # 7100 选人、建角色、删角色面板
    │   │   ├── GameCanvas.tsx       # 2D 网格视野画布 (玩家/怪物/掉落物/飘字)
    │   │   ├── ControlsPanel.tsx    # 8 方向 D-Pad、攻击/拾取/背包操作台
    │   │   ├── StatusPanel.tsx      # HP/MP/EXP 状态与 12 格广播视野快照
    │   │   ├── InventoryPanel.tsx   # 46 格背包物品展示与属性详情
    │   │   └── LogConsole.tsx       # 分级、按网关过滤的实时日志控制台
    │   ├── hooks/
    │   │   ├── useKeyboardControls.ts # 键盘快捷键监听 (WASD/Shift/Space/G/B)
    │   │   └── useMirWs.ts          # WebSocket 通信状态管理
    │   └── store/
    │       └── gameStore.ts         # 游戏全局状态树
    └── vite.config.ts
```

---

## 3. 分期计划与实现对照

| 阶段 | 目标与计划 | 状态 | 验证方式 |
|---|---|---|---|
| **M0** | **协议编解码对拍**：移植 `DefaultMessage`、`SixBitCodec`、`WireMessageCodec`、`ClientItemCodec`，按 Java 侧黄金向量 100% 回归。 | ✅ 完成 | `npm test` 通过 20 个黄金向量与布局测试 |
| **M1** | **登录 + 选人闭环**：走通 7000 握手登录 → 7100 查询/创建/删除/选择角色，日志面板打通。 | ✅ 完成 | `GateSession.test.ts` 与网页端登录选人面板验证 |
| **M2** | **进图 + 移动**：7200 发送 `RunLogin` 首包，接收 `SM_NEWMAP`/`SM_LOGON`，支持走/跑/转身，Canvas 画布渲染。 | ✅ 完成 | 2D Canvas 实时渲染坐标、朝向、移动及 +GOOD/+FAIL 确认 |
| **M3** | **战斗 + 拾取**：近战攻击（普攻/重击/大砍）、受击与死亡反馈、地面掉落物品显示与拾取、46 格背包同步。 | ✅ 完成 | 击杀怪物掉落鸡肉，拾取后背包即时同步 76 字节 `TClientItem` |
| **M4** | **多会话联调打磨**：多浏览器标签页并发互见、12 格广播视野验证、断线友好提示与操作手册。 | ✅ 完成 | `MultiSession.test.ts` 及双标签页实时互见验证 |
| **M5** | **装备 / 消耗 / 修理验证台**：完整接入 W12/W15 的穿脱、使用、丢弃、耐久、金币、属性和修理消息；装备快照与属性面板可视化。 | ✅ 完成 | `AbilityCodec` / `SM_SENDUSEITEMS` 回归测试，`npm test` + `npm run build` |

---

## 4. 快速上手

### 4.1 安装依赖与构建

```bash
cd mir2-front
npm install
npm run build
```

### 4.2 运行测试套件（回归黄金向量）

```bash
npm test
```

### 4.3 启动开发环境

```bash
# 同时启动 Bridge 代理 (ws://localhost:8080) 与 Web 前端 (http://localhost:5173)
npm run dev
```

> **提示**：当本地未检测到运行中的 `java-server` 实例时，Bridge 会自动启动内置的 `MockMirServer`（模拟 7000/7100/7200 三个端口），包含测试账号 `hero` / `123456` 以及地图上的怪兽与掉落物，无需额外配置即可开箱即玩！

---

## 5. 操作快捷键指南

| 按键 | 动作 | 说明 |
|---|---|---|
| `W / A / S / D` 或 `方向键` | **走 (Walk)** | 每次向目标朝向移动 1 格（发送 `CM_WALK`） |
| `Shift + 方向键 / WASD` | **跑 (Run)** | 每次向目标朝向移动 2 格（发送 `CM_RUN`） |
| `Ctrl + 方向键 / WASD` | **转身 (Turn)** | 原地改变朝向（发送 `CM_TURN`） |
| `小键盘 1 ~ 9` | **8 向移动** | 支持斜向（如 7 左上、9 右上、1 左下、3 右下） |
| `空格 Space / F / 1` | **普通攻击 (HIT)** | 向当前朝向发起普通近战物理攻击（发送 `CM_HIT`） |
| `2` | **重击攻击 (HEAVY HIT)** | 发起重击（发送 `CM_HEAVYHIT`） |
| `3` | **大砍攻击 (BIG HIT)** | 发起大砍（发送 `CM_BIGHIT`） |
| `G / E / ~` | **拾取物品 (Pickup)** | 拾取玩家当前脚下的地面物品（发送 `CM_PICKUP`） |
| `B / I` | **背包面板 (Inventory)** | 打开或关闭 46 格背包物品面板 |
| `鼠标左键点击画布` | **寻路/转向** | 自动向所点击的网格坐标移动或转向 |

---

## 6. 装备与修理联调验证 (M5 场景)

进入游戏后切换到「背包」标签页：

- 点击物品后可执行**穿戴、使用、丢弃**；穿戴槽位和 `SM_SENDUSEITEMS` 快照显示在背包下方；
- 顶部的「普修 / 特修」按钮对应 `@repair` / `@s_repair` 标签，选中磨损物品后点击「报价」或「修理」；
- 右侧状态栏实时显示 `SM_ABILITY` 的 HP/MP/等级/经验、金币，以及 `SM_WEIGHTCHANGED` 的三类负重；
- 耐久变更和复活戒指损耗会通过 `SM_DURACHANGE` 更新对应装备槽，损坏装备会自动从槽位移除；
- 该页面是验证台而非完整商业 NPC：服务端当前仍使用 W15 的最小 merchant label 状态机，未实现 NPC 对象、距离校验和 Market_Def 脚本。

## 7. 多会话联调验证 (M4 场景)

1. 在浏览器中打开第一个标签页，使用 `hero` / `123456` 登录并选择角色进入游戏；
2. 在浏览器中打开第二个标签页（或无痕窗口），使用 `player2` / `123456` 登录并创建角色进入游戏；
3. 两名玩家初始出生在比奇省 `(10, 10)` 附近，在彼此 12 格视野范围内：
   - 当一侧玩家移动、转身或挥刀时，另一侧画布即时广播渲染对方的动态；
   - 击杀怪物后掉落的地面物品，双方均可实时看见；
   - 当一侧玩家离开 12 格视野范围或断开连接时，对方视野中该角色平滑消失。
