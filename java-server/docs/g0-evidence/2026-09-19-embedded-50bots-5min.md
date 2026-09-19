# MIR2 bot-swarm 稳定性压测报告

- 生成时间：2026-09-19T10:27:41.665504169Z
- 目标服务器：`127.0.0.1` login=`45063`, select/game 按服务端广播地址，server=`MIR2`
- 规模：**50 bots**，duration=PT5M，ramp=PT10S，think=300–900ms，relogEvery=PT45S，seed=20260919
- 实际墙钟：PT5M10.207346978S
- embedded server still running after the run: true
- embedded 进程 JVM 堆使用峰值 39 MB、结束 24 MB / 上限 954 MB（62 次采样）

## 结论：PASS ✅
错误计数为 0，50/50 个机器人至少进图一次，累计进图 350 次。

## 会话

| 指标 | 值 |
|---|---|
| bots launched | 50 |
| bots that entered the world at least once | 50 |
| game entries (SM_NEWMAP received) | 350 |
| game-enter attempts retried (e.g. the leave-race after a fast relog) | 0 |
| voluntary relogs completed | 300 |
| game sessions ended | 350 |
| sessions restarted to resynchronise position | 0 |
| bot deaths observed (no revival exists in the PoC world) | 0 |
| bag queries sent | 350 |
| SM_BAGITEMS received | 96 |
| SM_ADDITEM (拾取成功) | 24 |
| SM_WINEXP (经验推送) | 24 |
| SM_DEATH (他者死亡) | 526 |

## 动作与应答延迟

| 动作 | 发送 | +GOOD | +FAIL | 超时 | p50 | p90 | p99 | max |
|---|---|---|---|---|---|---|---|---|
| turn | 2596 | 2596 | 0 | 0 | 71.4 ms | 91.0 ms | 124.3 ms | 136.3 ms |
| walk | 13617 | 10352 | 3265 | 0 | 71.1 ms | 91.1 ms | 124.7 ms | 140.2 ms |
| run | 5778 | 3871 | 1907 | 0 | 71.2 ms | 91.1 ms | 125.0 ms | 140.7 ms |
| melee hit | 84 | 84 | 0 | 0 | 70.5 ms | 90.7 ms | 124.9 ms | 124.9 ms |
| pickup | 24 | 24 | 0 | 0 | 78.1 ms | 98.3 ms | 131.6 ms | 131.6 ms |

## 服务端消息（前 20）

| 消息 | 数量 |
|---|---|
| SM_WALK | 402200 |
| SM_TURN | 164423 |
| SM_RUN | 144627 |
| SM_DISAPPEAR | 46405 |
| SM_HIT | 2337 |
| SM_HEALTHSPELLCHANGED | 1332 |
| SM_STRUCK | 1332 |
| SM_ITEMHIDE | 1135 |
| SM_ITEMSHOW | 1111 |
| SM_DEATH | 526 |
| SM_HEAVYHIT | 407 |
| SM_LOGON | 350 |
| SM_NEWMAP | 350 |
| SM_MAPDESCRIPTION | 350 |
| SM_BIGHIT | 254 |
| SM_BAGITEMS | 96 |
| SM_ADDITEM | 24 |
| SM_WINEXP | 24 |

## 错误

无。
