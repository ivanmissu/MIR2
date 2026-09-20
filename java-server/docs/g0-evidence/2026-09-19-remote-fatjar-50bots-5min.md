# MIR2 bot-swarm 稳定性压测报告

- 生成时间：2026-09-19T10:20:03.112654676Z
- 目标服务器：`127.0.0.1:` login=`7000`, select/game 按服务端广播地址，server=`MIR2`
- 规模：**50 bots**，duration=PT5M，ramp=PT10S，think=300–900ms，relogEvery=PT45S，seed=20260919
- 实际墙钟：PT5M10.860582333S（提前手动停止）

## 结论：PASS ✅
错误计数为 0，50/50 个机器人至少进图一次，累计进图 350 次。

## 会话

| 指标 | 值 |
|---|---|
| bots launched | 50 |
| bots that entered the world at least once | 50 |
| game entries (SM_NEWMAP received) | 350 |
| game-enter attempts retried (e.g. the leave-race after a fast relog) | 0 |
| voluntary relogs completed | 350 |
| game sessions ended | 350 |
| sessions restarted to resynchronise position | 0 |
| bot deaths observed (no revival exists in the PoC world) | 0 |
| bag queries sent | 350 |
| SM_BAGITEMS received | 114 |
| SM_ADDITEM (拾取成功) | 24 |
| SM_WINEXP (经验推送) | 24 |
| SM_DEATH (他者死亡) | 460 |

## 动作与应答延迟

| 动作 | 发送 | +GOOD | +FAIL | 超时 | p50 | p90 | p99 | max |
|---|---|---|---|---|---|---|---|---|
| turn | 2623 | 2623 | 0 | 0 | 70.9 ms | 91.4 ms | 122.9 ms | 135.9 ms |
| walk | 13533 | 10622 | 2911 | 0 | 68.7 ms | 91.0 ms | 124.5 ms | 139.1 ms |
| run | 5939 | 4185 | 1754 | 0 | 68.7 ms | 91.0 ms | 125.4 ms | 139.2 ms |
| melee hit | 78 | 78 | 0 | 0 | 61.3 ms | 92.9 ms | 134.7 ms | 134.7 ms |
| pickup | 24 | 24 | 0 | 0 | 66.6 ms | 95.3 ms | 124.1 ms | 124.1 ms |

## 服务端消息（前 20）

| 消息 | 数量 |
|---|---|
| SM_WALK | 395995 |
| SM_TURN | 163939 |
| SM_RUN | 150793 |
| SM_DISAPPEAR | 49949 |
| SM_HIT | 1939 |
| SM_ITEMHIDE | 1097 |
| SM_ITEMSHOW | 1073 |
| SM_HEALTHSPELLCHANGED | 977 |
| SM_STRUCK | 977 |
| SM_DEATH | 460 |
| SM_HEAVYHIT | 440 |
| SM_LOGON | 350 |
| SM_NEWMAP | 350 |
| SM_MAPDESCRIPTION | 350 |
| SM_BIGHIT | 157 |
| SM_BAGITEMS | 114 |
| SM_ADDITEM | 24 |
| SM_WINEXP | 24 |

## 错误

无。
