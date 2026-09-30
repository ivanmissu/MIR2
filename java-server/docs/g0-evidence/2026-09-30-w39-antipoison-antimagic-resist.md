# W39 AntiPoison / AntiMagic 抗性接线证据

日期：2026-09-30

## 范围

本批收口 W37/W38 留下的抗性占位：`EquipmentBonus` 已经能从装备模板解析 `wAntiMagic`、`wAntiPoison` 与恢复字段，但运行时对象、`SM_SUBABILITY` 以及技能抵抗门此前仍未消费这些值。

## 代码改动

- `WorldEngine.Player` 新增运行时附加属性：
  - `antiMagic`：按 Delphi `RecalcAbilitys` 裸身基线 `m_nAntiMagic := 1`，再叠加 `StdMode=19/53` 的 `AC2`；
  - `antiPoison`：叠加 `StdMode=23` 的 `AC2`；
  - `poisonRecover` / `healthRecover` / `spellRecover`：按 `EquipmentBonus` 出站。
- `WorldObject` 增加 `antiMagic()` / `antiPoison()` 视图；当前首批 10 种怪物与 NPC 仍保持 Delphi `Initialize` 默认 0。
- `SM_SUBABILITY` 改为发送真实附加属性：`Recog=m_nAntiMagic`、`Param=MakeWord(准确,敏捷)`、`Tag=MakeWord(AntiPoison,PoisonRecover)`、`Series=MakeWord(HealthRecover,SpellRecover)`。
- `SKILL_AMYOUNSUL` 施毒术接入 `Random(target.m_btAntiPoison + 7) <= 6`：抵抗时仍耗粉、仍有 `RM_MAGICFIRE`，但不排队 `RM_POISON`。
- 火球 / 大火球 / 雷电术接入 `Random(10) >= target.m_nAntiMagic`；抵抗时不排队延迟伤害，且火球/雷电沿用 Delphi `TargeTBaseObject := nil` 的 `SM_MAGICFIRE` 包体 0 行为。
- 灵魂火符在护身符消耗后过 `m_nAntiMagic` 门；按原 Delphi 分支，抵抗时不 nil 目标包体，只是不排队伤害。
- 地狱火 / 疾光电影的 `MagPassThroughMagic` 对每个真目标独立过 `m_nAntiMagic`，被抵抗目标不产生 `RM_MAGSTRUCK`，也不触发训练计数。

## 覆盖用例

- `WorldWarriorSkillTest#resistanceGearFeedsTheSubAbilityPanel`：验证 `StdMode=19` 项链、`StdMode=23` 戒指进入 `SM_SUBABILITY` 语义事件。
- `WorldMagicTest#playerAntiMagicCanResistSingleTargetBoltsAtCastTime`：验证裸身玩家 `m_nAntiMagic=1` 下，`Random(10)=0` 的火球抵抗不排队伤害且 `SM_MAGICFIRE` targetId 为 0。
- `WorldAmuletSkillTest#amyounsulHonoursTheTargetsAntiPoisonAccumulator`：验证抗毒戒指让施毒术在 `Random(AntiPoison+7)>6` 时只耗粉/发射、不上毒。
- `WorldLinePiercingSkillTest#beamHonoursPlayerAntiMagicBeforeQueuingMagStruck`：验证直线穿透魔法按目标独立抵抗，抵抗后无 `ObjectStruck`、无训练。
- `GameProtocolAdapterTest#entrySendsOnlyTheObjectsVisibleToTheNewPlayer`：默认裸身 `SM_SUBABILITY.Recog` 从 0 更新为 1。

## 验证状态

当前沙箱未预装 JDK/Maven（`java` / `mvn` 均不可用），因此本轮无法在本地执行 `mvn -f java-server/pom.xml verify`。请在具备 JDK 21+ 与 Maven 的环境中补跑：

```bash
mvn -f java-server/pom.xml verify
```

## 边界

- 未接入怪物特殊子类中手写的超高 `m_btAntiPoison`；当前已接线怪物保持 0。
- 恢复字段仅按 Delphi `SM_SUBABILITY` 出站，源码检索未发现本服对 `m_nPoisonRecover` / `m_nHealthRecover` / `m_nSpellRecover` 的运行时消费点。
- 本批仍不新增 shadowdiff 场景或 `g4-release-gate.tsv` 门禁行。
