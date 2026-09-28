# W33 技能逐项接入：基本剑术 / 精神力战法

日期：2026-09-28  
基线：W32（大火球、雷电术）

## 结论

W33 接入 `SKILL_ONESWORD=3`（基本剑术）与 `SKILL_ILKWANG=4`（精神力战法）。两者在
Delphi `Magic.pas` 中不是独立的施法特效，而是 `ObjBase.pas` 的被动近战技能：

- `RecalcHitSpeed` 以 `DEFHIT=5` / `DEFSPEED=15` 为基础；基本剑术每级增加
  `Round(9 / 3 * btLevel)` 命中，精神力战法每级增加 `Round(8 / 3 * btLevel)` 命中；
- `_Attack` 对相邻目标执行 `Random(target.SpeedPoint) < attacker.HitPoint`，未命中时只保留挥刀
  动作，不发送受击/伤害事件；
- 穿透命中后按 `TrainSkill(Random(3) + 1)` 增加熟练度，执行一次
  `CheckMagicLevelup`，一条 `SkillTrainingChanged` 对应 Delphi 的 `SM_MAGIC_LVEXP`；
- `PlayerSkill` 的等级/熟练度随角色保存，重登时通过 `SM_SENDMYMAGIC` 恢复；技能的职业/学习等级
  仍由权威 `MagicDb.tsv` 约束（基本剑术=战士，精神力战法=道士）。

为保持 W03 既有 Java 行为兼容，尚未学习这两个被动技能的角色沿用此前的相邻攻击路径；一旦
技能行进入角色状态，才启用 Delphi 的命中/闪避比较。后续接入其他战士技能时会移除这条兼容边界，
并以统一的 `HitPoint/SpeedPoint` 子属性模型收口。

## 自动化证据

- `WorldMeleeSkillTest.basicSwordIsWarriorOnlyAndAHitTrainsTheDurableSkill`
  - 学习后命中、`Random(3)+1` 训练、持久化重登。
- `WorldMeleeSkillTest.bothBooksRejectTheWrongJobWithoutConsumingTheBook`
  - 基本剑术/精神力战法的职业门禁与失败不消耗书籍。
- `WorldMeleeSkillTest.ilkwangIsTaoistOnlyAndUsesItsOwnAccuracyCurve`
  - 精神力战法职业与训练路径。
- `WorldMeleeSkillTest.basicSwordAccuracyUsesTheObjBaseHitVersusSpeedComparison`
  - Level 0 的 `5/15` 未命中与 Level 3 的 `14/15` 命中对照。
- `GameProtocolAdapter` 的 `SkillTrainingChanged` 分支
  - `magicId / level / lowTrain / highTrain → SM_MAGIC_LVEXP`。

本环境未安装 JDK/Maven（`java`、`mvn` 命令不可用），故本轮未能在沙盒执行 `mvn verify`；代码与
测试按仓库 JDK 21 目标编写，CI/G4 执行器负责完整编译回归。`g4-release-gate.tsv` 未新增行：
技能 shadowdiff 场景仍按 W31 的统一收口计划处理。

## 边界

- 未接入攻杀剑术、刺杀剑术等主动武器技；
- 未建模 `SM_SUBABILITY` 的完整 Hit/Speed 子属性快照，当前命中值仅在攻击判定中消费；
- 未改变 W31 G4 清单场景。技能 shadowdiff 场景仍待一批技能收口后统一加入。
