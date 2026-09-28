# W36 技能逐项接入：野蛮冲撞（SKILL_MOOTEBO=27）与推人位移/撞墙模型

日期：2026-09-28  
基线：W35（烈火剑法）

## 结论

W36 接入 `SKILL_MOOTEBO = 27`（野蛮冲撞）。这是 1..33 官方权威基线中**战士技能树的最后一项**，也是服务端实现的**首个位移/击退类技能**：
按键（`CM_SPELL` with magicId=27）由 `ClientSpellXY`（ObjBase.pas:9112）经过 3 秒冷却闸门与 MP 消耗判定后，驱动 `DoMotaebo`（ObjBase.pas:21703）执行空冲位移、推退前置低等级目标、3 级双推、撞墙反震受挫与技能熟练度训练闭环。

至此，1.76 官方基线（1..33）所有战士技能（3 基本剑术、7 攻杀剑术、12 刺杀剑术、25 半月弯刀、26 烈火剑法、27 野蛮冲撞）已全部接入。

## 行为对拍要点（严格等价翻译，行为冻结）

- **触发与闸门**（`ClientSpellXY` ObjBase.pas:9112）：
  - 冷却闸门 `(GetTickCount - m_dwDoMotaeboTick) > 3 * 1000`，严格大于 3 秒：未过冷却时直接返回 `Result := True`（回 `+GOOD`），不消耗 MP、不产生位移。
  - 方向解析：`m_btDirection := nTargetX`，客户端将当前朝向打入 `nTargetX`（0..7），服务端更新朝向。
  - 法力消耗：`GetSpellPoint(UserMagic)`（Magic.DB id 27：`spell=15`、`btDefSpell=0` ⇒ 0/1/2/3 级分别扣 4 / 8 / 11 / 15 MP）；MP 不足时不发生冲撞但依然回 `+GOOD`。
- **推人判定**（`CanMotaebo` ObjBase.pas:21705）：
  - 等级门禁：`m_Abil.Level > BaseObject.m_Abil.Level`，**严格大于**；目标等级 >= 施法者等级时判定失败（同级与高级目标不可推动）。
  - 目标状态：`not BaseObject.m_boStickMode`，非固定实体、存活且非安全区内。
  - 概率判定：`nC := m_Abil.Level - BaseObject.m_Abil.Level`，`Random(20) < ((nMagicLevel * 4) + 6 + nC)`。
- **位移与推退**（`DoMotaebo` ObjBase.pas:21703 + `CharPushed` ObjBase.pas:2504）：
  - 步数上限：`_MAX(2, nMagicLevel + 1) + 1` 步（0/1 级冲 3 格，2 级冲 4 格，3 级冲 5 格）。
  - 空冲分支（前无障碍）：每推进一步广播 `SM_RUSH`（6），若中途撞上不可行走地形（墙体），置 `bo35 := True` 提前终止。
  - 推人分支（前有目标）：
    - 3 级冲撞额外检测正前第 2 格，若存在次要目标且同样满足 `CanMotaebo`，则次要目标先退 1 格（实现原版「3 级野蛮双推」机制）。
    - 主要目标受 `CharPushed` 向后推 1 格，广播 `SM_BACKSTEP`（9），朝向转为相反方向 `GetBackDir(nDir)`，怪物步行动作延迟 800ms。
    - 施法者进驻目标原格，广播 `SM_RUSH`（6）。
  - 撞墙受挫与反震伤害：
    - `bo35 = True` 时（撞墙或推人受阻），向视野广播 `SM_RUSHKUNG`（7，携带正前受阻格坐标），并向自身发送红字 `冲撞力不够...`（`sMateDoTooweak`）。
    - 若空冲步数未走完即撞墙（`n28 > 0`），施法者自身受到反震受击伤害 `Random(n24 * 10) + ((n24 + 1) * 3)`，扣减 HP 并广播 `SM_STRUCK`。
  - 目标伤害：
    - 成功推撞目标后，目标受到撞击伤害 `Random((n24 + 1) * 10) + ((n24 + 1) * 10)`，经目标 AC 减免与魔法盾吸收后应用 `applyDamage`。
- **熟练度训练**（ObjBase.pas:9125）：
  - 成功执行 `DoMotaebo` 后，若技能等级 < 3 且角色等级 > 当前技能等级要求（`TrainLevel[btLevel] < m_Abil.Level`，如 0 级需角色 > 30 级），增加 `Random(3) + 1` 熟练度，触发 `CheckMagicLevelup` 并出站 `SM_MAGIC_LVEXP`。

## 自动化证据

世界层 `WorldMotaeboSkillTest`：

- `motaeboIsWarriorOnlyAndRequiresLevelThreshold` —— 法师/道士无法学习；战士未满 30 级不可读卷；30 级战士成功使用技能书学得 0 级野蛮冲撞。
- `emptyRushTraversesStepsAccordingToSkillLevelAndSpendsMana` —— 0 级空冲 3 格、扣 4 MP、产生 3 次 `ObjectRushed`；3 级空冲 5 格、扣 15 MP、产生 5 次 `ObjectRushed`。
- `cooldownIntervalEnforcesThreeSeconds` —— 3 秒内连续按键依然应答 `+GOOD`，但不产生位移、不扣蓝；满 3 秒后再次成功冲撞。
- `hittingSolidObstacleEmitsRushFailedAndDealsRecoilDamage` —— 正前 1 格为实心障碍时冲撞受阻，广播 `ObjectRushFailed`（`SM_RUSHKUNG`）与「冲撞力不够...」，自身承受反震伤害。
- `pushingLowerLevelMonsterPushesMonsterMovesPlayerDealsDamageAndTrains` —— 35 级战士推 1 级木桩，怪物被退 3 格并掉转朝向，战士推进 3 格，怪物承受撞击伤害，战士熟练度增加。
- `failingToPushEqualOrHigherLevelTarget` —— 30 级战士推 35 级玩家失败，双方均不动，广播 `ObjectRushFailed` 与「冲撞力不够...」。
- `levelThreeMotaeboPushesTwoAlignedTargets` —— 3 级冲撞同时推动正前方紧邻的两个怪物。

网关层 `GameMotaeboProtocolTest`：

- `objectRushedEmitsSmRushWithCorrectWireLayout` —— `ObjectRushed` 映射为 `SM_RUSH`（6），字段与 `CharDesc` 结构体对拍。
- `objectPushedEmitsSmBackstepWithCorrectWireLayout` —— `ObjectPushed` 映射为 `SM_BACKSTEP`（9），字段与 `CharDesc` 对拍。
- `objectRushFailedEmitsSmRushkungWithTargetCell` —— `ObjectRushFailed` 映射为 `SM_RUSHKUNG`（7），携带障碍格坐标。

矩阵：`g4-capability-matrix.tsv` 中 `SKILL_MOOTEBO`（三职业）由 `unimplemented` 转 `implemented + gate-game`；`SM_RUSH`、`SM_RUSHKUNG`、`SM_BACKSTEP` 由 `protocol-only` 转 `implemented`。

## 边界（本轮不做）

- `双龙斩(34)`、`狂风斩(38)`、`逐日剑法` 等超出 1..33 官方权威目录的技能继续保持 `unimplemented`。
- 护身符消耗模型（`CheckAmulet`/`UseAmulet`）留待道士符箓/施毒类技能立项。
