# W25 接入层尾项实跑证据（IP 黑名单 / BlockMethod / 包体突发守卫）

*日期：2026-09-23 · 分支：`arena/01a0cefd-mir2` · 全量单测：**362/362 全绿***

本轮把 Delphi 网关（`LoginGate` / `SelGate` / `RunGate`）接入层的三项尾部能力补齐到 Java 侧：
永久 / 临时 IP 黑名单、`BlockMethod` 三种处置策略、以及 RunGate 独有的「单次 recv 包体 +
帧数」突发守卫。本文记录复刻依据、两处**推翻初始假设**的勘查结论，以及跨真实 TCP 的实跑输出。

---

## 1. 复刻依据（Delphi 源坐标）

| 能力 | Delphi 位置 | 语义要点 |
| --- | --- | --- |
| 并发 / 突发限额 | `LoginGate/Main.pas` L1005-1044 `IsConnLimited` | 命中 `Inc(nCount)`；1000ms 窗 `nIPCountLimit1=20`、3000ms 窗 `nIPCountLimit2=40`；末尾 `nCount > nMaxConnOfIPaddr` 拒绝 |
| 黑名单判定 | `RunGate/Main.pas` L1306-1333 `IsBlockIP` | 临时表 `CompareText` **全等**；永久表 `CompareLStr(ip, entry, Length(entry))` **前缀匹配** |
| 前缀比较 | `HUtil32.pas` L1158-1172 `CompareLStr` | 任一串短于 `compn` 直接 False（**条目比待测地址长则永不匹配**）；逐字符 `UpCase` |
| 接入处置 | `LoginGate/Main.pas` L520-560 | 先 `IsBlockIP`→关；再 `IsConnLimited`→按 `BlockMethod` 分三路 |
| 断开回收 | `LoginGate/Main.pas` L240-271 | `Dec(nCount)`，`<=0` 删整条记录（**连带丢弃突发计数器**） |
| 黑名单文件 | LoginGate `LoadBlockIPFile` / RunGate `LoadFromFile` | RunGate 版**无 `inet_addr` 校验**，故网段前缀条目得以保留；`SaveBlockIPList` 只写永久表 |
| 包体守卫 | 常量 `RunGate/GateShare.pas` L96-102；判定 `RunGate/Main.pas` L982-1003 | `nMaxClientPacketSize=7000`、`nNomClientPacketSize=150`、`nMaxClientMsgCount=15`、`bokickOverPacketSize=True` |

`BlockMethod` 三态（`GateShare.pas`）：`mDisconnect` 仅关连接；`mBlock` 加**临时**表 +
`CloseConnect(ip)`；`mBlockList` 加**永久**表 + `CloseConnect(ip)`。

---

## 2. 两处推翻初始假设的勘查结论

### 2.1 并发上限**没有** off-by-one（曾误判，用 Python 逐行复算证伪）

初稿曾断言 `nCount` 先自增后比 `>` 会让 `limit=50` 实放 51 条。实际复算 Delphi 控制流后发现：
被拒的 socket 会立即 `Close`，从而触发 `ServerSocketClientDisconnect`（L253-262）的 `Dec(nCount)`，
**自增与回退相互抵消**，`limit=50` 恰好放行 50 条并发。

> Java 侧据此在**拒绝路径回退计数**。若不回退，一个曾突破上限的 IP 会因计数永不归零而被永久锁死——
> 这是本轮修正测试断言（而非修改实现）才暴露出来的隐患。

### 2.2 突发窗口的 off-by-one **是原版 quirk，予以保留**

`IsConnLimited` 在窗口滚动时执行 `tick1 := now; nIPCount1 := 0`——置 0 而非置 1，**当前这一条连接没有被计入新窗口**。
因此 `nIPCountLimit1=20` 每秒实际放行 **21** 条。这属于原版行为，Java 侧原样复刻并在 Javadoc 标注。

此外，新 IP 首次接入时建记录 `nCount := 1` 直接放行、**不查任何限额**，两个窗口 tick 保持 0。

### 2.3 时间泵改造（G3 候选①）——勘查后**否定**

原计划借「可注入 tick」为 G3 对拍提供零改码路径。实测 M2Server 有 **775 处裸 `GetTickCount`**（分布于 25 个单元），
无任何可注入的缓存全局，驱动源为 `svMain.dfm RunTimer(Interval=1)` → `svMain.pas` L1069。
全局替换 775 处风险过高，**放弃该路径**；G3 ② 改由已交付的 W23 虚拟时钟 + W24 对拍矩阵承担。

---

## 3. 跨真实 TCP 实跑

### 3.1 启动装配

```
MIR2_BLOCK_IP_FILE=/tmp/w25-smoke/BlockIPList.txt   # 内容：203.0.113.  /  198.51.100.7
MIR2_BLOCK_METHOD=block   MIR2_MAX_CONNECTIONS_PER_IP=3
```

```
INFO: Loaded BlockIPList (2 entr(ies)) from /tmp/w25-smoke/BlockIPList.txt
INFO: MIR2 Java server started: login=17000, select=17100, game=17200, ...,
      maxConnPerIp=3, blockMethod=block, blockedIps=2
```

启动行新增 `maxConnPerIp=` / `blockMethod=` / `blockedIps=` 三个字段，运维可一眼确认生效配置。

### 3.2 并发上限与释放（`MIR2_BLOCK_METHOD=disconnect`，cap=3）

```
conn#1: OPEN(admitted)      conn#2: OPEN(admitted)      conn#3: OPEN(admitted)
conn#4: EOF(closed by server)          <- 恰好在第 4 条被拒，印证 §2.1「无 off-by-one」
conn#5 after release: OPEN(admitted)   <- 释放一条即可重新接入
conn#6 (all released): OPEN(admitted)  <- mDisconnect 不留黑名单
```

### 3.3 包体字节上限（game gate，边界精确）

```
sent     8B -> STILL OPEN        sent  7000B -> STILL OPEN     <- 边界内
sent   150B -> STILL OPEN        sent  7001B -> CLOSED         <- 恰好越界即断
sent   151B -> STILL OPEN        sent  8000B -> CLOSED
```

判定为 `>` 而非 `>=`，7000 放行、7001 断开，与 Delphi 一致。

### 3.4 `mBlock` 的连带封禁

在 `MIR2_BLOCK_METHOD=block` 下触发超限后，该地址被写入临时表，**其余网关**的后续连接同样被拒：

```
offender (game gate)      -> CLOSED by server (EOF)
new connection (login gate) -> CLOSED by server (EOF) = BANNED
```

对照组 `mDisconnect` 下同样触发超限，login gate 后续连接 `OPEN` —— 证明封禁确由 `BlockMethod` 驱动，而非副作用。

### 3.5 一处**被污染的探针**及其纠正（记录以免重蹈）

用裸字节构造「多帧」探针时，15 帧与 40 帧**全部**被关闭，一度疑似帧数守卫失效。追加对照实验发现：

```
1 garbage frame, 4B   -> CLOSED      # 单帧就已关闭
no '!' at all, 80B    -> OPEN
```

即**单个格式非法的 `!` 帧先在 `WireMessageCodec` 处被拒**，与大小守卫无关——探针被协议解析污染，不能作为守卫证据。
故帧数守卫改由 gate 层集成测试在**协议解析之前**取证（见 §4），边界为 15 放行 / 16 拦截。

---

## 4. 配套测试（+32，330 → 362）

| 测试类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `AccessPolicyTest`（重写） | 8 | 并发上限恰为 N、拒绝不泄漏槽位、tumbling 突发窗、3s 窗独立、黑名单先于限额、三种 BlockMethod、计数清零遗忘突发、默认值 50/20/40 |
| `BlockIpListTest`（新） | 7 | 前缀匹配、条目长于地址永不匹配、临时表全等、`parse` 保留网段前缀、永久/临时分离持久化、去重与大小写不敏感 |
| `PacketSizePolicyTest`（新） | 9 | 默认值、≤150 不检查、`>` 边界（15/16 帧、7000/7001 字节）、`kickOnOversize=false` 走 DISCARD、`countFrames`、守卫流行为、非法配置 |
| `GateAdmissionIntegrationTest`（新） | 7 | **跨真实 TCP**：黑名单拒连、并发上限拒连、超限踢人并封禁、login gate 不启用读守卫、帧数 15/16 边界、常规流量不受影响 |
| `ServerConfigTest`（增补） | 3 | 接入层默认值、9 个环境变量覆写与策略透传、非法配置拒绝启动 |

**负向对照**：临时把 `PacketSizePolicy.evaluate` 的越界判定改为恒 `false` 后重跑，
`PacketSizePolicyTest` 4 例 + `GateAdmissionIntegrationTest` 1 例**共 5 例转红**（357→352），
确认这些用例确实在检验守卫行为而非恒真断言；随后已还原实现。

---

## 5. 结论与限制

- 三项接入层尾项均已复刻并跨真实 TCP 验证，含两处原版 quirk（突发窗 off-by-one、新 IP 首连不查限额）。
- `WireMessageCodec.MAX_PACKET_BYTES=8192`（单帧硬上限）与本轮 7000 字节/次 recv 是**不同维度**，并存不冲突。
- LoginGate / SelGate 读路径在原版中**无任何大小校验**，Java 侧同样只在 GAME 网关启用守卫（已有专门用例固定该差异）。
- 未触碰红线范围（技能 / NPC 脚本 / 57 怪扩展）。
