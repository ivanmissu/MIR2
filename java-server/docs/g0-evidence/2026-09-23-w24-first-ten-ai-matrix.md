# W24 实跑证据：首批 10 种怪 AI 确定性对拍矩阵

- 日期：2026-09-23
- 基线：`f848114`（W23 已合入 master）
- 模式：`shadowdiff --embedded --ai-all --strict-messages`
- 世界：左右各一套独立 Java 服务端、SQLite 与三网关；`WorldClock.Mode.MANUAL`
- 随机种子：左右均为 `20260923`
- 规模：每种怪左右各 4 只；每行 18 个观测步骤（进图 + 17 ops）
- 运行命令：

```bash
java -jar mir2-shadowdiff.jar \
  --embedded --ai-all --strict-messages \
  --seed 20260923 --settle-ms 120 --report-dir reports/w24-ai-matrix
```

## 结论

**10/10 PASS**。十行合计 180 个观测步骤，状态差异、应答差异、消息集合差异均为 0。

| # | 模板 | 中文名 | 行为族 | 判定 | 状态差异 | 应答差异 | 消息差异 | 非空转证据 |
| ---: | --- | --- | --- | --- | ---: | ---: | ---: | --- |
| 1 | `chicken` | 鸡 | AGGRESSIVE（既有兼容行为） | **PASS** | 0 | 0 | 0 | 4 种 `near` 轨迹；观测到 8 次玩家受击 |
| 2 | `deer` | 鹿 | PASSIVE_FLEE | **PASS** | 0 | 0 | 0 | 4 种 `near` 轨迹；从 `(19,19)…` 持续逃到 `(15,15)…` |
| 3 | `scarecrow` | 稻草人 | AGGRESSIVE | **PASS** | 0 | 0 | 0 | 4 种 `near` 轨迹；观测到 10 次玩家受击 |
| 4 | `hookcat` | 多钩猫 | AGGRESSIVE | **PASS** | 0 | 0 | 0 | 4 种 `near` 轨迹；观测到 9 次玩家受击 |
| 5 | `rakecat` | 钉耙猫 | AGGRESSIVE | **PASS** | 0 | 0 | 0 | 4 种 `near` 轨迹；观测到 9 次玩家受击 |
| 6 | `cavemaggot` | 洞蛆 | AGGRESSIVE | **PASS** | 0 | 0 | 0 | 4 种 `near` 轨迹；观测到 5 次玩家受击 |
| 7 | `scorpion` | 蝎子 | AGGRESSIVE | **PASS** | 0 | 0 | 0 | 4 种 `near` 轨迹；观测到 4 次玩家受击 |
| 8 | `orc` | 半兽人 | AGGRESSIVE | **PASS** | 0 | 0 | 0 | 4 种 `near` 轨迹；观测到 6 次玩家受击 |
| 9 | `orcwarrior` | 半兽勇士 | AGGRESSIVE | **PASS** | 0 | 0 | 0 | 3 种 `near` 轨迹；两次高伤攻击均导致死亡，重登后再次一致死亡 |
| 10 | `orcfighter` | 半兽战士 | AGGRESSIVE | **PASS** | 0 | 0 | 0 | 4 种 `near` 轨迹；观测到 4 次玩家受击 |

`near` 轨迹按状态快照中的怪物坐标与朝向去对象 id 后计数。鹿不攻击是 `PASSIVE_FLEE` 的预期语义，不以“玩家受击”作为它的非空转条件；其坐标持续远离玩家，证明逃跑 AI 确实执行。半兽勇士在第一轮贴身攻击即造成 21 点伤害、玩家死亡；重登恢复到 14 HP 后再次受到 27 点伤害并死亡，左右轨迹、消息与死亡时点完全一致。

## W24 工具增量

新增 `--ai-all`：

1. 矩阵被显式钉死为 P2 已入库的十种模板，不从怪物目录动态枚举，避免越过“剩余 47 种怪行为禁止提前扩展”的红线；
2. 每种模板使用隔离的左右服务端与数据库，单行异常不会短路后续行；
3. 每行仍生成完整 `shadow-report.md/.csv`，根目录额外生成 `ai-matrix.md` 汇总表；
4. `--strict-messages` 下消息多重集合差异也会令该行失败；
5. `--ai-all` 仅允许 embedded 模式，且拒绝与 `--monster-kind` 同用，防止参数含义冲突。

## 边界与诚实结论

- 本报告证明的是：**Java 实现的首批十种现有 AI 在相同随机种子与脚本化世界时钟下可重复，双实例逐步观测一致**。
- 它不是 Delphi↔Java 跨实现对拍。G3 的“10 种怪 AI 对拍一致”最终签发仍需 Windows/Delphi 环境；Delphi 侧若不增加等价时间泵，只能采用 W23 已验证的远离决策边界的墙钟脚本。
- 本轮没有新增或修改任何怪物行为、技能/魔法或 NPC 脚本；只扩展对拍编排与证据覆盖。
- 鸡当前仍沿用早期兼容切片的 `AGGRESSIVE` 行为（源码已有 `TODO(verify)`），没有在本轮借机改为逃跑，以遵守行为冻结原则。
