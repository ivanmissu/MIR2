# W23 实跑证据：虚拟时钟注入 + 会动的怪确定性对拍

- 日期：2026-09-23
- 分支：`arena/01a0cdd1-mir2`（基线 `d28e08f`，W22 PR #40 合入 master 后）
- 链路：沙箱内 ECJ（`ecj.jar`）+ jdk4py + JUnit 5.10.2 本地编译跑测（`~/tools/build-mir2.sh` / `~/tools/test-mir2.sh`），shadowdiff 实跑用 `~/tools/run-shadowdiff.sh`

## 结论

| 项 | 结果 |
| --- | --- |
| 全量单测 | **TOTAL=329 PASS=329 FAIL=0 SKIP=0** |
| 基线（W22 交付时） | TOTAL=307 PASS=307 |
| 本轮净增 | **+22**（`WorldClockTest` 7 + `WorldVirtualClockDeterminismTest` 6 + `ShadowDiffMainArgsTest` 3 + `OpTest` +2 + `ServerConfigTest` +2 + `ShadowSessionEmbeddedTest` +2） |
| 主源码编译 | `compile OK`（108 main + 64 test，无错误） |
| 会动的怪对拍 | `--embedded --ai --strict-messages` **18 步 PASS**，负向对照 `--right-seed 99999` **FAIL** |
| 决策边界对照 | 墙钟 **5/5 FAIL**（差异数漂移 3/2/2/2/2）vs 虚拟时钟 **5/5 PASS** |

## 一、要解决的问题

W17 的 PvE 对拍只能打**木桩**（`MonsterBehavior.STATIONARY`），因为怪物 AI 的节拍是对时钟的区间判定：

```pascal
// ObjMon.pas:437  TMonster.Run
if not m_boWalkWaitLocked and (Integer(GetTickCount - m_dwWalkTick) > m_nWalkSpeed) then begin
  m_dwWalkTick := GetTickCount();
// ObjMon.pas:392  攻击间隔
if Integer(GetTickCount - m_dwHitTick) > m_nNextHitTime then begin
```

`GetTickCount` 是**进程外的 OS 运行时间**，两个进程永远不共享。`WorldRandom`（W17）只保证「第 N 次抽签相同」，但决定不了「到底抽了几次」——只要两侧走位/攻击的**次数**因宿主抖动差一次，后续所有伤害就整体错位。这就是 G3 门「10 种怪 AI 对拍一致」卡住的根因。

Delphi 侧 `GetTickCount` 调用密度（本仓库实测）：`ObjBase.pas` 310 处、`UsrEngn.pas` 87 处、`ObjMon.pas` 79 处、`Envir.pas` 5 处。

## 二、交付内容

### ① `world/WorldClock`（新增，无 Delphi 对应类，是 `GetTickCount` 的注入点）

三态时间源：

| 模式 | 时间来源 | 用途 |
| --- | --- | --- |
| `SYSTEM` | `System.currentTimeMillis()` | **生产默认**，与本轮改动前逐位一致 |
| `VIRTUAL` | `epoch + ticks × tickMillis`，由引擎 tick 循环自增 | 让所有节拍变成 tick 的纯函数 |
| `MANUAL` | 同上，但只由调用方推进 | 对拍 harness：世界时间由脚本说了算 |

- `DEFAULT_EPOCH_MILLIS = 1_000_000`：**不从 0 起**。`TBaseObject.Initialize`（ObjBase.pas:20095，其中 `m_dwDeathTick := GetTickCount`）用当前 tick 给各冷却窗口打戳，随后按 `GetTickCount - stamp > interval` 判定；epoch=0 会让世界开局的头几秒同时落在每个冷却窗口内。
- `advancesWithEngineTick()`：仅 `VIRTUAL` 为真，引擎据此决定要不要动这个时钟。

**已标注的刻意偏离**（`WorldClock` 类注释里也写了）：Delphi 在 tick **内部**实时读 `GetTickCount`，因此一次慢 tick 会让下次区间判定看到更大的 delta，怪物可以在一趟里把「欠的」时间一次走完；虚拟模式把时间量化到 tick 网格，欠的时间不累积。按出厂 50ms tick，引擎内所有区间都 ≥200ms（≥4 tick），健康宿主上不可观测，但**高负载下是真实偏离**——这正是生产保持 `SYSTEM` 的理由。

### ② `WorldEngine` 接线

- 新增 `WorldClock` 字段 + 3 个构造器重载；旧的 `LongSupplier clock` 构造器全部保留（`worldClock == null`），24 处 `clock.getAsLong()` 调用点一处未改，换时钟即全部确定化。
- `defaultHourSupplier(WorldClock)`：昼夜小时是世界的第二个墙钟依赖（Delphi `GetGameTime` 读宿主时刻）。虚拟世界改从自己的时钟取 `floorMod(millis/3600000, 24)`，两进程的 `SM_DAYCHANGING` 才能对齐；`SYSTEM` 仍读 `LocalTime.now()`。
- `tickOnce()` 拆为「排空命令队列」+ `runTickBody()`（`regenSpawners / updateMonsters / decayPkPoints / ...`，即 `TUserEngine.Run` 在收包之后干的事）。`VIRTUAL` 时钟**在 tick body 之前**自增，本趟里的一切读到同一个已递增的时间戳。
- `advanceTicks(int)`：MANUAL 世界的推进泵，走世界线程，每个 tick 推一格时钟再跑一次完整 `runTickBody()`，返回新的世界时间。`SYSTEM`/`VIRTUAL` 上是 no-op（时间不归调用方管）。
- **MANUAL 下 `tickOnce()` early-return，跳过 `runTickBody()`**。这是本轮修掉的一个竞态：手动时钟下调度器仍在跑周期体，虽然时间冻结、周期体基本幂等，但它**相对于入站命令**的执行时机取决于宿主调度器——于是「怪物这一拳落在哪个 op 的观测桶里」又变回了赛跑。命令排空保持无条件执行：两次泵之间客户端仍须被服务。

### ③ `@tick [N]` GM 命令（`processSay` → `pumpTicks`）

- **无 Delphi 对应物**，是 harness 专用命令，已在 `translation-map.md` 标注。
- 仅 MANUAL 世界生效；`SYSTEM`/`VIRTUAL`（含所有生产服）直接回绝「@tick 仅在手动世界时钟下可用」，所以运营没显式开手动时钟时该命令是惰性的。
- 上限 `MAX_PUMPED_TICKS = 100_000`（出厂 50ms tick 下约 83 分钟虚拟时间）：一个泵 tick 是一整个 tick body，不设上限的话一行聊天就能无限占住世界线程。
- 回执 `SM_SYSMESSAGE`：`"@tick N -> T ticks, now=M"`。**回执带新世界时间**，两侧实际跑了几个 tick 若有出入会直接变成状态差异，而不是静默漂移。

### ④ `ServerConfig`

- 新字段 `worldClockMode`（默认 `SYSTEM`）+ `withWorldClockMode()` + `worldClock()` 工厂。
- 环境变量 `MIR2_WORLD_CLOCK` = `system`（默认）/ `virtual`，大小写不敏感。**`manual` 被显式拒绝**：运营设了它会得到一个时间永不流动的世界；harness 走 `withWorldClockMode()` 这条内部通道。
- 虚拟时钟的 tick 间隔跟随 `worldTickMillis`，否则「tick 数相同」就不再等于「世界时间相同」。

### ⑤ shadowdiff

- `--ai` 开关：MANUAL 时钟 + 4 只 `chicken` + `Op.aiScript()`。
- 脚本 op 新增 `tick <N>`（经 `@tick` 下发）。与 `sleep` 的区别：`sleep` 让两侧墙钟各跑各的、只是近似对齐；`tick` 把**世界时间本身**变成脚本量。
- 状态快照新增 `near=[(x,y) dir=N, ...]`（视野内其它角色的格子+朝向，排序去 id）与 `worldTime=`；`ShadowDiff` 相应新增 `near:` / `worldTime:` 差异行。
- `--right-seed N`：只给右侧换种子的**负向对照**开关。
- **报告新增「## 观测轨迹（左侧逐步状态）」表**：逐 op 打印左侧 `state.describe()`。此前报告只列「一致/差异」判定，PASS 无法审计——分不清「18 步 0 差异」和「什么都没发生」。新增判定维度时必须同步进这张表。
- **修 bug**：`ShadowDiffMain.Args.parse` 漏把 `--ai` 登记为无值 flag，会吞掉下一个参数（`--ai --monsters 4` 报 `unexpected argument: 4`）。已抽出 `Args.FLAGS` 常量集并加 `ShadowDiffMainArgsTest` 回归。

## 三、实跑证据

### 3.1 会动的怪对拍 PASS，且**可审计非空转**

```bash
~/tools/run-shadowdiff.sh --embedded --ai --strict-messages --seed 20260922 \
    --right-seed 20268841 --report-dir /tmp/sdrun/r20268841
# [shadowdiff] verdict=PASS state=0 acks=0 messages=0
```

连跑 3 次（`--right-seed` 取 20268841 / 20260923 / 缺省）全 `PASS state=0 acks=0 messages=0`，18 步。观测轨迹节选（左侧）：

| # | 操作 | 状态（节选） |
|---|---|---|
| 0 | `enter` | `near=[(19,19) dir=4, (19,21) dir=4, (21,19) dir=4, (21,21) dir=4]` |
| 2 | `tick 1` | 同上 `worldTime=1000050` |
| 10 | `walk 4` | `cell=(20,21)` `near=[(19,19)…]` `worldTime=1004550` |
| 11 | `tick 20` | `hp=11/15` `combat=[struck self dmg=1 hp=14/15, …hp=11/15]` `near=[(19,21) dir=4, (20,20) dir=3, (21,20) dir=4, (21,21) dir=4]` `worldTime=1005550` |
| 12 | `hit 6` | `combat=[struck other dmg=2 hp=3/5]` `worldTime=1005550` |
| 13 | `tick 20` | `hp=12/15`（自然回复）`worldTime=1006550` |
| 14 | `tick 60` | `hp=8/15` `combat=[struck self dmg=1 ×4]` `worldTime=1009550` |
| 15 | `relog` | `cell=(19,19)` `near=[(19,21) dir=2, (20,20) dir=4, (21,20) dir=5, (21,21) dir=6]` |
| 17 | `tick 20` | `near=[(19,20) dir=0, (20,19) dir=7, (20,20) dir=4, (20,21) dir=6]` `worldTime=1010550` |

即：**怪物真的在走位**（`near` 从 4 只鸡的初始环位一路变化到贴脸）、**真的在攻击**（`struck self dmg=1` 连击把 HP 从 15 打到 8）、**玩家真的在反击**（`struck other dmg=2 hp=3/5`）、HP 自然回复、relog 后重新入图的邻居普查逐位一致。全程 `worldTime` 只在 `tick` op 上增长，增量恰为 `N × 50ms`。

### 3.2 负向对照：换种子必须 FAIL

```bash
~/tools/run-shadowdiff.sh --embedded --ai --strict-messages --seed 20260922 --right-seed 99999
# [shadowdiff] verdict=FAIL state=1 acks=0 messages=0
```

差异精确定位到：

```
| 12 | `hit 6` | 状态差异 | combat: [struck other dmg=2 hp=3/5] vs [struck other dmg=1 hp=4/5] |
```

手动时钟消掉的是**计时竞态**，不是**比较本身**。

### 3.3 墙钟 vs 虚拟时钟：决策边界对照（W23 价值的量化证明）

**先记一个诚实的负面发现**：最初假设「墙钟对拍在会动的怪上必然失败」——**不成立**。把 `--ai` 脚本里的 `tick N` 换成等价的 `sleep`（`/tmp/sdrun/wallclock.txt`），在空载跑 3 次、起 6 个忙循环压满 2 核再跑 2 次、把静默窗口收紧到 `--settle-ms 120` 再跑 4 次，**全部 PASS**。原因查 `MonsterDb.tsv` 鸡行即明：走位 1400ms、攻击 3000ms，节拍远大于本机抖动。

要稳定暴露差异，得构造**决策恰好落在 op 观测边界**的场景——让 sleep 值等于怪物攻击间隔：

`/tmp/sdrun/boundary-wall.txt`（墙钟版）
```
bag
walk 4
sleep 3000   # ×6，鸡的攻击间隔正是 3000ms
```

`/tmp/sdrun/boundary-tick.txt`（等价虚拟时钟版）
```
bag
walk 4
tick 60      # ×6，60 × 50ms = 3000ms 世界时间
```

| 跑次 | 墙钟（`sleep 3000`） | 虚拟时钟（`tick 60`） |
| --- | --- | --- |
| 1 | FAIL state=3 | **PASS** state=0 |
| 2 | FAIL state=2 | **PASS** state=0 |
| 3 | FAIL state=2 | **PASS** state=0 |
| 4 | FAIL state=2 | **PASS** state=0 |
| 5 | FAIL state=2 | **PASS** state=0 |

墙钟版首跑的差异长这样（注意它甚至不是「数值不同」，而是**同一拳落进了不同的 op 桶**）：

```
| 2 | `walk 4`     | 状态差异 | hp: 15/15 vs 11/15
                              combat: [] vs [struck self dmg=1 ×4]
                              messages: [SM_WALK,SM_WALK] vs [SM_WALK,SM_WALK,SM_HIT,SM_STRUCK,…]
| 3 | `sleep 3000` | 状态差异 | hp: 11/15 vs 7/15
```

**结论**：虚拟时钟的价值不在「随便什么场景都更稳」，而在**消除 AI 决策落在 op 观测边界时的桶归属竞态**——而这恰恰是逐 op 比对的 harness 唯一会被咬的地方。差异数在墙钟下还会逐次漂移（3/2/2/2/2），意味着墙钟版的 FAIL 报告本身也不可复现，无法用作 CI 门禁。

## 四、新增单测（+22）

| 测试类 | 用例数 | 钉死的性质 |
| --- | --- | --- |
| `world/WorldClockTest` | 7 | SYSTEM 读宿主时钟且 advance 是 no-op；VIRTUAL 是 tick 的纯函数；两个 VIRTUAL 同 tick 数同时间；MANUAL 不随引擎 tick 且 `advancesWithEngineTick()==false`；epoch ≥60000 的理由；非法参数抛 IAE；不同 `tickMillis` 同 tick 数时间不等 |
| `world/WorldVirtualClockDeterminismTest` | 6 | 同种子同 tick 预算、**宿主快慢不同**的两个虚拟世界追击路径逐格一致；追击者确实在移动（非空转）；虚拟时间每 tick 恰进一格；MANUAL 世界被泵之前完全冻结、泵后 AI 才动；同泵法两世界同态、异泵法不同态；`advanceTicks` 在 VIRTUAL/SYSTEM 上是 no-op |
| `shadowdiff/ShadowDiffMainArgsTest` | 3 | `--ai --monsters 4` 不再吞参数（回归本轮 bug）；`runEmbedded` 里所有按 flag 处理的开关都在 `FLAGS` 里；键值选项仍要求带值 |
| `shadowdiff/OpTest` | +2 | `tick N` 解析/`describe()` 往返/非法参数拒绝；`aiScript()` 泵够时间、**不含任何 `sleep`**、玩家动作夹在两泵之间、含 relog |
| `bootstrap/ServerConfigTest` | +2 | 默认 SYSTEM 且 `MIR2_WORLD_CLOCK=virtual` 生效、虚拟时钟 tick 间隔跟随 `worldTickMillis`；`manual` 与非法值被环境解析拒绝，但 `withWorldClockMode` 可达 |
| `shadowdiff/ShadowSessionEmbeddedTest` | +2 | 两个 MANUAL 世界跑完整 `aiScript()` 对拍 PASS，且逐 op 断言 `worldTime` 恰等于「epoch + 已泵 tick × 50」（时间既真的动了、又没被宿主多推一格），邻居普查在跑动中确实变化；负向对照：同泵法异种子必须报 STATE 差异 |

> `ShadowSessionEmbeddedTest` 的负向对照特意选了 seed `99999`（CLI `--right-seed` 实跑验证过会在 `hit 6` 分叉为 dmg 2 vs 1）。并非任意种子对都会在一条短脚本内分叉——鸡几拳就死、很多次抽签落在同一值上——所以对照钉一对**确实会分叉**的种子。

> `BotSwarmEmbeddedTest.singleBotLogsInWalksFightsAndRelogs` 为已知墙钟抖动型偶发失败，与本轮改动无关；最终一轮 329/329 全绿。

## 五、红线核对

未触碰技能/魔法系统（`CM_SPELL`/`TUserMagic`/`SM_MAGICFIRE`/`Magic.pas`）、NPC 脚本引擎（`Market_Def`）、57 种怪物行为扩展。本轮只动时间源注入与对拍 harness，`MonsterBehavior` 枚举与既有 AI 分支一行未改——`--ai` 用的是 W17 就已入库的 `chicken`（AGGRESSIVE）行为，没有新增任何怪物行为。

## 六、复现

```bash
~/tools/build-mir2.sh    # compile OK（108 main + 64 test）
~/tools/test-mir2.sh     # TOTAL=329 PASS=329

# 会动的怪对拍
~/tools/run-shadowdiff.sh --embedded --ai --strict-messages --report-dir /tmp/sdrun/ai
# 负向对照
~/tools/run-shadowdiff.sh --embedded --ai --strict-messages --right-seed 99999

# 决策边界对照
~/tools/run-shadowdiff.sh --embedded --monsters 4 --monster-kind chicken \
    --script /tmp/sdrun/boundary-wall.txt   # 墙钟：FAIL
~/tools/run-shadowdiff.sh --embedded --ai --monsters 4 --monster-kind chicken \
    --script /tmp/sdrun/boundary-tick.txt   # 虚拟时钟：PASS

# 或在有 Maven Central 的环境
mvn -f java-server/pom.xml verify
```

## 七、对 G3 门的推进与剩余缺口

G3「10 种怪 AI 对拍一致」的**工具前提已就绪**：harness 现在能以确定性方式对拍会动的怪，且有可审计的观测轨迹与有效的负向对照。剩余缺口仍是**真实客户端/Delphi 服务端环境**：

- 环境就绪后零改码：`--left-host <Delphi LoginGate> --right-host <Java>`。
- **但注意**：`--ai` 的 remote 用法要求两台服务端都以 MANUAL 时钟启动，这不是生产配置；Delphi 侧没有 `@tick` 对应物，因此跨实现对拍会动的怪仍需要先在 Delphi 侧补一个等价的时间泵，或退回到用「决策远离 op 边界」的脚本做墙钟对拍（3.3 已证明该做法在鸡这类慢节拍怪上可行）。这一条记入计划书的下一步。
