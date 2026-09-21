# MIR2 影子对拍报告（shadowdiff）

- 生成时间：2026-09-21T10:55:04.421081229Z
- 左侧目标：`embedded-left`
- 右侧目标：`embedded-right`
- 操作流：29 步（含进图）
- 消息判定：严格（消息集合差异也算 FAIL）

## 结论：PASS ✅

状态差异 0 步，应答差异 0 步，消息集合差异 0 步，共 29 步。

| # | 操作 | 判定 | 详情 |
|---|---|---|---|
| 0 | `enter` | 一致 |  |
| 1 | `bag` | 一致 |  |
| 2 | `say @who` | 一致 |  |
| 3 | `turn 2` | 一致 |  |
| 4 | `walk 2` | 一致 |  |
| 5 | `walk 2` | 一致 |  |
| 6 | `turn 4` | 一致 |  |
| 7 | `walk 4` | 一致 |  |
| 8 | `walk 4` | 一致 |  |
| 9 | `turn 6` | 一致 |  |
| 10 | `walk 6` | 一致 |  |
| 11 | `walk 6` | 一致 |  |
| 12 | `turn 0` | 一致 |  |
| 13 | `walk 0` | 一致 |  |
| 14 | `walk 0` | 一致 |  |
| 15 | `run 2` | 一致 |  |
| 16 | `hit 2` | 一致 |  |
| 17 | `sleep 1200` | 一致 |  |
| 18 | `heavyhit 4` | 一致 |  |
| 19 | `sleep 1200` | 一致 |  |
| 20 | `bighit 6` | 一致 |  |
| 21 | `sleep 1200` | 一致 |  |
| 22 | `pickup` | 一致 |  |
| 23 | `drop 木剑` | 一致 |  |
| 24 | `say hello-shadow` | 一致 |  |
| 25 | `relog` | 一致 |  |
| 26 | `bag` | 一致 |  |
| 27 | `walk 4` | 一致 |  |
| 28 | `turn 0` | 一致 |  |

## 操作脚本

```
bag
say @who
turn 2
walk 2
walk 2
turn 4
walk 4
walk 4
turn 6
walk 6
walk 6
turn 0
walk 0
walk 0
run 2
hit 2
sleep 1200
heavyhit 4
sleep 1200
bighit 6
sleep 1200
pickup
drop 木剑
say hello-shadow
relog
bag
walk 4
turn 0
```

---

## 证据说明（本次会话追加）

- 运行方式：`java -jar mir2-shadowdiff.jar --embedded --strict-messages`（进程内两台独立
  `Mir2Server`：独立 SQLite、独立随机端口、相同配置 0 号 PoC 空图、出生点 20,20、无怪物）。
- 结论：**严格消息判定下 29/29 步全一致（PASS）**——状态差异 0、应答差异 0、消息集合差异 0。
- 差异检测非空转的证明：`ShadowSessionEmbeddedTest.divergentWorldsAreCaught` 把右侧世界的
  出生点故意错开到 (30,30)，首步即报 STATE 差异且总体 FAIL。
- 本报告验证的是**骨架自检 + Java 服务端确定性**。Delphi ↔ Java 的真正对拍在 Windows +
  mir2.exe 环境就绪后运行：`--left-host <Delphi LoginGate> --right-host <Java>`，脚本与
  判定逻辑完全复用。
