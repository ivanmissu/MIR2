# 影子对拍：带怪 PvE 对拍（世界随机种子注入）

*2026-09-22 · W17 · 沙箱实跑*

W16 交付的 shadowdiff harness 有一条明确的已知限制：**embedded 自拍不放怪**——两台服务端各自
持有独立的 `java.util.Random`，一旦地图上有怪，伤害/掉落数值立刻发散，对拍必然 FAIL。本次
（W17）交付世界随机种子注入解除该限制，本文件是对应的实跑证据。

## 1. 结论速览

| 模式 | 世界种子 | 判定 | 状态差异 | 说明 |
| --- | --- | --- | --- | --- |
| unseeded（生产默认） | 两侧均不设 | **FAIL** | **6** | 复刻 Delphi 全局 `Random` 的行为，两进程必然发散 |
| seeded（对拍模式） | 两侧同为 `20260922` | **PASS** | **0** | 17 步全部一致，且为严格判定（`--strict-messages`） |

**两行都必须成立**，这条证据才有意义：seeded PASS 证明种子注入有效，unseeded FAIL 证明该对拍
**不是空转**——harness 确实在观测伤害，只是此前观测不到。

## 2. 负向对照（关键证据）

同一脚本、同一地图、同一出生点、同样 8 个木桩，**只有随机种子不同**：

```
==== mode=unseeded verdict=FAIL state=6 acks=0 ====
  #4  hit 6       -> [combat: [struck other dmg=1 hp=4999/5000] vs [struck other dmg=2 hp=4998/5000]]
  #6  hit 6       -> [combat: [struck other dmg=2 hp=4997/5000] vs [struck other dmg=1 hp=4997/5000]]
  #8  hit 6       -> [combat: [struck other dmg=1 hp=4996/5000] vs [struck other dmg=2 hp=4995/5000]]
  #10 heavyhit 6  -> [combat: [struck other dmg=2 hp=4994/5000] vs [struck other dmg=2 hp=4993/5000]]
  first blow (L): struck other dmg=1 hp=4999/5000
  first blow (R): struck other dmg=2 hp=4998/5000

==== mode=seeded verdict=PASS state=0 acks=0 ====
  first blow (L): struck other dmg=2 hp=4998/5000
  first blow (R): struck other dmg=2 hp=4998/5000
```

多次重跑：unseeded 侧每次都发散（具体数值随机），seeded 侧每次都复现同一首击 `dmg=2`。

## 3. CLI 实跑（严格判定）

```bash
java -jar mir2-shadowdiff.jar --embedded --pve --seed 20260922 --strict-messages
```

```
[shadowdiff] embedded-left  up: login=36211 select=36639 game=37687 seed=20260922 monsters=8xtrainer
[shadowdiff] embedded-right up: login=44891 select=38953 game=38337 seed=20260922 monsters=8xtrainer
[shadowdiff] left=embedded-left (127.0.0.1:36211) right=embedded-right (127.0.0.1:44891), 17 ops
[shadowdiff] verdict=PASS state=0 acks=0 messages=0
```

17 步（含进图）逐步判定「一致」：

| # | 操作 | 判定 |
| --- | --- | --- |
| 0 | `enter` | 一致 |
| 1 | `bag` | 一致 |
| 2 | `turn 6` | 一致 |
| 3 | `walk 6` | 一致 |
| 4 | `hit 6` | 一致 |
| 6 | `hit 6` | 一致 |
| 8 | `hit 6` | 一致 |
| 10 | `heavyhit 6` | 一致 |
| 12 | `bighit 6` | 一致 |
| 14 | `hit 6` | 一致 |
| 16 | `relog` | 一致 |
| 17 | `bag` | 一致 |

（5/7/9/11/13/15 为 `sleep 1000`，用于清 900ms `CM_HIT` 动作间隔，同样判定一致。）

## 4. 为什么需要「分流」而不是单纯一个种子

Delphi 全局只有一个 `Random()`，所有子系统共享同一序列：一次掉落 roll 会挪走本该属于下一次
伤害 roll 的抽样。生产环境无所谓，但对拍时致命——怪物 AI、刷怪计时、HP/MP 回复计数器都由
**墙钟**驱动，两个进程的墙钟永远不会完全一致，于是「抽了多少次」不同，后续所有数值全部错位。

因此 `WorldRandom` 把随机性拆成 5 条互不干扰的流（`DAMAGE` / `EQUIPMENT_WEAR` / `LOOT_DROP` /
`DEATH_SCATTER` / `SPAWN`），每条由世界种子派生（SplitMix64 雪崩，避免相邻流相关）。这样
**掉落 roll 了几次不再影响第 N 次伤害**，由脚本驱动的 PvE 数值才可复现。单测
`WorldRandomTest#seededStreamsAreIndependentOfEachOther` 与
`WorldSeedDeterminismTest#lootDrawsDoNotPerturbTheDamageStream` 固化了这一点。

## 5. 为什么打「木桩」而不是鸡

剩下的不确定性来自**墙钟驱动的行为**：怪物什么时候迈一步、什么时候挥一刀，取决于两个进程各自
的 `System.currentTimeMillis()`。只要目标会动会还手，对拍就仍会发散。

Delphi 本身就提供了答案：`TRAINER = 55`（M2Share.pas:152）→ `TTrainer`（ObjNpc.pas:2626），
官方的**伤害测试木桩**，挨打时回报「破坏力 / 平均值」。它的行为对应 `TMonster.Run` 中
`m_boNoAttackMode`（ObjMon.pas:449）分支：整个「索敌→追击→攻击」块被跳过。本次按此复刻
`MonsterBehavior.STATIONARY` 与 `MonsterTemplate.trainer()`（名称 `木桩` / `trainer`），
它是**唯一行为与墙钟无关**的 PvE 目标，因而可确定性对拍。

> 注：木桩以 monster 对象实现，**未触碰 NPC/脚本引擎红线**——所需的线上可观测行为（站立、挨打、
> 死亡、不掉落）monster 对象已完全覆盖，未引入任何脚本钩子。数值为 `TODO(verify)` 占位，
> 待真实 `Monster.DB` 第 55 行导入后校准。

## 6. 复现方式

```bash
# 带怪 PvE 对拍（严格判定）
java -jar mir2-shadowdiff.jar --embedded --pve --seed 20260922 --strict-messages

# 自定义：任意脚本 + 任意怪物模板 + 任意只数
java -jar mir2-shadowdiff.jar --embedded --seed 20260922 \
     --monsters 8 --monster-kind trainer --script my-ops.txt

# remote（Delphi 就绪后）：两侧服务端各自配 MIR2_WORLD_SEED=<同一值> 后
java -jar mir2-shadowdiff.jar --left-host <delphi> --right-host <java> --pve
```

## 7. 边界（明确未解决）

- **生产默认仍不设种子**（`MIR2_WORLD_SEED` 未设 = 一条共享随机流，等价 Delphi 行为）。种子只为
  对拍/复现服务，不改变线上手感。
- **会动的怪仍不可确定性对拍**：`--monster-kind chicken` 之类仍会因墙钟差异发散，这是墙钟问题
  而非随机数问题，需要虚拟时钟注入（未做，且会偏离「真实进程对拍」的初衷）。
- 木桩数值、以及 W04/W09/W12/W15 的全部 `TODO(verify)` 数值，仍待真实 `StdItems.DB` /
  `Monster.DB` 导入后校准。
- G3 剩余两项（3 名老玩家盲测手感、10 种怪 AI 对拍）仍卡**真实客户端环境**（Windows + mir2.exe
  + Delphi 服务端），与本切片无关。
