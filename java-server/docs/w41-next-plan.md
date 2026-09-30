# W41 下一步开发计划：技能 shadowdiff 通道与 SKILL_FIREBOOM 评估

**基线：** W40（群体治愈术 `SKILL_BIGHEALLING=29` 接入世界引擎与 gate 协议，能力矩阵三行转 `implemented`）；G4 仍未签发。

## W40 落地情况

- `WorldEngine.castAreaHealing`：一次功率轮（治愈术公式）共享给 `GetMapBaseObjects(nRage=1)` 的 3x3 方形；
- 友方判定收窄到 `IsProperFriend` 的 `HAM_GROUP` 分支：施法者本人 + 在线队友，怪物 / NPC / 非队友一律排除；
- `HP < MaxHP` 门同时决定 `boTrain`，满血目标不产生任何 HP 事件；
- `MagicImpactKind.AREA_HEAL`：800ms 延迟、绑定对象（离格仍生效）、到达时复核队伍关系；
- 点击吸附（`CretInNearXY`，±1 格）决定方形中心与 `SM_MAGICFIRE` 坐标；
- 验收：`WorldAreaHealingTest`、`GameAreaHealingProtocolTest`。

## 下一步：shadowdiff 施法通道（P0）

shadowdiff 目前**没有任何施法 op**，W38/W39/W40 三个技能切片的证据都止步于 world + gate 单测。要让
`shadowdiff-skills` 门禁行有意义，必须先补齐对拍链路：

1. `Op`：新增 `spell <magicId> <x> <y> [targetId]`，编码成真实 `CM_SPELL`（Recog = MakeLong(x,y)，
   Param/Series = 目标 id 的高低字）；
2. `ShadowDiffMain.SeededAccount`：支持预置职业、等级与已学技能行，否则道士技能无从起手；
3. `StateSnapshot`：把 HP/MP/技能训练点纳入逐 op 状态比较，否则治疗类技能的差异不可观测；
4. 场景脚本：MANUAL 时钟下 `spell 29` → `tick` 到 800ms → 观察双方 HP/MP/消息序列；
5. 负控制：右侧世界错种子必须判差异（exit 1），与既有 `shadowdiff-ai-negative` 同形。

只有 1–5 全部完成后，才在 `docs/g4-release-gate.tsv` 新增 `shadowdiff-skills` 与
`shadowdiff-skills-negative` 两行，并在能力矩阵的技能行证据里追加该场景。

## 后续候选：SKILL_FIREBOOM（P1）

爆裂火焰（23，`MagBigExplosion`，`g_Config.nFireBoomRage = 1`）与群体治愈共享方形取样，但语义相反：
逐目标抗魔、伤害、阻挡与中心格判定都必须先从 Delphi 源码确认，无法确认的字段保持 `protocol-only`。

## 本轮不做

- 不接入召唤、隐身、火墙、地狱雷光、圣言术、冰咆哮；
- 不实现攻击模式（`CM_CHANGEATTACKMODE`）——群体治愈的友方范围在此之前保持 `HAM_GROUP` 最严格分支；
- 不实现 Market_Def 交易事务、行会、攻城；
- 不把 Java↔Java shadowdiff 证据描述成真实客户端/Delphi 字节级等价；
- 不因技能切片完成而签发 G4，真实 `mir2.exe`/Delphi 外部基线仍是必要条件。
