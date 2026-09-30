# W40 下一步开发计划：群体魔法最小切片与技能门禁收口

**基线：** W39（AntiPoison/AntiMagic 属性接线与真实抵抗），技能矩阵已覆盖官方 Magic.DB 1–33 的大部分单体、武器技和直线穿透技能；G4 仍未签发。

## 当前进度

- [x] `AreaTargetSelector`：方形范围、Chebyshev 距离、合法目标过滤、稳定排序。
- [x] `WorldObject` 适配：Player/Monster/Npc 可作为范围目标。
- [x] `AreaHealing` 纯计算层：逐目标治疗、最大 HP 钳制、满血目标过滤。
- [x] `WorldEngine` 接入 `SKILL_BIGHEALLING=29`（`castAreaHealing` + `MagicImpactKind.AREA_HEAL`）。
- [x] gate 协议测试（`GameAreaHealingProtocolTest`）与世界验收测试（`WorldAreaHealingTest`）。
- [x] 能力矩阵 `SKILL_BIGHEALLING:{warrior,wizard,taoist}` 由 `unimplemented` 更新为 `implemented`。
- [ ] 技能 shadowdiff 场景与 `shadowdiff-skills` 门禁行（shadowdiff 目前没有任何施法 op，见 w41 计划）。

## 下一步：接入 SKILL_BIGHEALLING（P0）

### 1. 技能识别和权限

- 增加 `SKILL_BIGHEALLING` 常量及 `isAreaHealingSkill` 分支；
- 仅允许 Magic.DB 标记的道士技能通过职业、等级、已学习技能检查；
- 接入既有 MP、公共施法间隔、失败 `SM_SYSMESSAGE` 和持久化回滚流程；
- 技能未学习、职业错误、蓝量不足、冷却中必须不改变任何目标状态。

### 2. 目标关系和范围

- 施法者本人和同组队友可被治疗；
- 非组队玩家、怪物、NPC 不得被治疗；
- 以点击位置为中心，使用已经完成的 `AreaTargetSelector`；
- 范围边界采用包含边界，结果按距离后 objectId 排序；
- 不把“范围内玩家”默认当作友方，必须经过明确的队伍关系判断。

### 3. 延迟事件和出站

- 沿用现有技能的施法动画与延迟影响模型；
- 影响到达时重新确认目标仍在当前地图且仍满足友方关系；
- 每个实际恢复目标独立产生 `HealthChanged`/技能影响事件；
- 满血目标不产生虚假的 HP 增量，但不影响其他目标；
- 事件必须经过 world event → gate codec，不允许直接写 Socket。

### 4. 验收测试

新增：

- `WorldAreaHealingTest`：单目标、多人组队、非队友、满血、范围边界、地图切换、延迟期间退队；
- `GameAreaHealingProtocolTest`：MP 扣除、施法消息、逐目标 HP 消息和失败系统消息；
- `ShadowDiff` 技能场景：同种子下双服目标排序、HP、MP、消息序列一致；错误种子负控制必须返回差异。

只有上述测试和证据完成后，才将 `SKILL_BIGHEALLING:taoist` 相关矩阵行从 `unimplemented` 更新为 `implemented`，并新增 `shadowdiff-skills` G4 门禁行。

## 后续候选：SKILL_FIREBOOM（P1）

群体治愈完成后再评估爆裂火焰。必须先确认 Delphi 的范围形状、中心格、阻挡格、逐目标抗魔与伤害衰减语义；无法从源码或录制确认的字段保持 `protocol-only`，不进行猜测实现。

## 本轮不做

- 不接入召唤、隐身、火墙、地狱雷光、圣言术、冰咆哮；
- 不实现 34/38 超出当前权威目录的技能；
- 不实现 Market_Def 交易事务、行会、攻城；
- 不把 Java↔Java shadowdiff 证据描述成真实客户端/Delphi 字节级等价；
- 不因技能切片完成而签发 G4，真实 `mir2.exe`/Delphi 外部基线仍是必要条件。
