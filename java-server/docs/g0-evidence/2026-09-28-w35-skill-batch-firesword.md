# W35 技能逐项接入：烈火剑法（SKILL_FIRESWORD=26 / wHitMode=7）

日期：2026-09-28  
基线：W34（刺杀剑术、半月弯刀）

## 结论

W35 接入 `SKILL_FIRESWORD = 26`（烈火剑法）。它是 `MagicManager.IsWarrSkill`（Magic.pas:211）
九个 id 里的**第三种形态**：W33 的 3/4/7 是纯被动，W34 的 12/25 是开关，烈火则是**一次性蓄力**——
按键把 `m_boFireHitSkill` 点亮，下一刀 `CM_FIREHIT` 把它烧掉换取一次百分比暴击，20 秒不用即失效。
本批不引入任何新的攻击几何：伤害仍落在正前单格，只是 `nPower` 多了一个百分比加成。

## 行为对拍要点（严格等价翻译，行为冻结）

- **蓄力**（`ClientSpellXY` ObjBase.pas:9092 + `AllowFireHitSkill` ObjBase.pas:9782）：
  - 未学（`m_MagicFireSwordSkill = nil`）直接跳过，Java 侧在 `castPlayerSpell` 的
    `UNKNOWN_SKILL` 分支即被拒（Delphi 同样不做任何事）。
  - 再蓄闸门 `(GetTickCount - m_dwLatestFireHitTick) > 10 * 1000`，**严格大于**：整 10 秒仍然失败，
    只发红字「召唤烈火精灵失败...」。
  - 成功即先 `m_dwLatestFireHitTick := GetTickCount`、`m_boFireHitSkill := True`、绿字
    「召唤烈火精灵成功...」，**然后**才判法力：`nSpellPoint = GetSpellPoint(UserMagic)`
    （Magic.DB id26：`wSpell=0`、`btDefSpell=7` ⇒ 恒为 7），`MP >= nSpellPoint` 才扣蓝并回 `+FIR`。
    **quirk 照搬**：蓝不够时旗照样点亮，但客户端收不到 `+FIR`，因而不会改发 `CM_FIREHIT`。
  - 整个 case 一律 `Result := True`，所以失败也回 `+GOOD`（`IsWarrSkill` 同时短路法力/冷却门）。
- **`m_nHitDouble`**（`RecalcHitSpeed`，ObjBase.pas:18619）：`4 + btLevel * 4`，写在 `btLevel > 0`
  守卫**之外**，0 级书即 40%，3 级 160%。烈火不进任何一张准确加成表，`m_btHitPoint` 不受影响。
- **消费**（`_Attack`，ObjBase.pas:22128 / 22152）：`wHitMode = 7` 且旗点亮时——
  清旗、**重新盖时间戳**（Jacky「禁止双烈火」），并
  `nPower := nPower + Round(nPower / 100 * (m_nHitDouble * 10))`，加成发生在攻杀 `m_nHitPlus`
  之后、闪避判定之前。**砍空**（前方无目标）走 22152 的分支：同样清旗 + 盖时间戳但不加伤，
  即「防止砍空刀刀烈火」。
- **广播**（`AttackDir`，ObjBase.pas:18841）：`7: if boFireHit then wIdent := RM_FIREHIT`，
  旗在 `_Attack` 之前被快照，未蓄力的 `CM_FIREHIT` 退化为 `SM_HIT`。
- **失效**（`TPlayObject.Run`，ObjBase.pas:6427）：`> 20 * 1000` 后清旗、红字
  「召唤烈火精灵结束...」并回 `+UFIR`。Java 侧挂在 `expireSkillBuffs` 的同一 tick 窗口。
- **训练**（ObjBase.pas:22354）：守卫是 `wHitMode = 7` 而**不是**旗，所以未蓄力的烈火刀只要穿透
  命中同样 `TrainSkill(skill, 1)` + 一次 `CheckMagicLevelup`，与 W34 的定值训练一致。

## 自动化证据

世界层 `WorldFireSwordSkillTest`：

- `hitDoubleFollowsTheBookLevel` —— `m_nHitDouble = 4 + 4·level`（0/1/3 级 = 4/8/16），
  烈火不加准确，`IsWarrSkill` / 三类谓词归属正确。
- `pressingTheKeyArmsTheChargeSpendsManaAndEmitsTheTag` —— 登录不自动蓄力；首次按键绿字 +
  `+FIR` + 扣 7 MP + `+GOOD`；10 秒内再按只有红字，不发标签、不扣蓝、仍成功。
- `anArmedSwingBurnsTheChargeForTheHitDoublePercentageAndTrains` —— 50 级战士（minDc=9）
  蓄力刀 `9 + Round(9/100·40) = 13`，广播 `SM_FIREHIT`，训练 +1；下一刀回落 9 且广播
  `SM_HIT`，但训练仍 +1（守卫是 hit mode）。
- `swingingAtAirStillBurnsTheCharge` —— 砍空后蓄力已失，补刀只有基础伤害。
- `theChargeLapsesAfterTwentySeconds` —— 整 20 秒仍在，+1 毫秒即红字 + `+UFIR`，伤害回落。
- `anUnlearnedFireHitIsAnOrdinarySwing` —— 未学时施法被 `UNKNOWN_SKILL` 拒、`CM_FIREHIT`
  退化为普攻、不训练。

网关层 `GameSpecialAttackProtocolTest`（扩展 W34 三个用例）：

- `WeaponSkillToggled(26, on/off) → +FIR / +UFIR`，裸标签帧编码为 `#+FIR!` / `#+UFIR!`；
- `CM_FIREHIT(3025)` 进 `isSupported`/`handle` 并映射 `AttackKind.FIRE_HIT`，未学退化 `SM_HIT`；
- `ObjectAttacked(FIRE_HIT) → SM_FIREHIT(8)`，字段布局对拍。

矩阵：`g4-capability-matrix.tsv` 中 `SKILL_FIRESWORD`（三职业）由 `unimplemented` 转
`implemented + gate-game`，`CM_FIREHIT` / `SM_FIREHIT` 由 `protocol-only` 转 `implemented`。

本环境未安装 JDK/Maven（`java`、`mvn` 不可用，且 Maven Central 出网被封），本轮未能在沙盒执行
`mvn verify`；代码与测试按仓库 JDK 21 目标编写，CI / `g4-release-gate.sh` 负责完整编译回归。
`g4-release-gate.tsv` 未新增行——技能类 shadowdiff 场景仍按 W31 的统一收口计划处理。

## 边界（本轮不做）

- `野蛮冲撞(27)`（`DoMotaebo` 位移撞墙）、`双龙斩(34)`、`狂风斩(38)`、`逐日剑法` 仍 `unimplemented`；
  34/38 的 Magic.DB 行超出本服加载的 1..33 权威目录，显式延后。
- 不实现 `m_boTwinHitSkill`（烈火同一 tick 窗口里的孪生分支）与 `CM_TWINHIT`。
- 不为烈火加 shadowdiff 场景或门禁行（延续 W28/W32/W33/W34 的验收基线）。
- 不改动属性点 `m_BonusAbil`、护身符消耗模型（`CheckAmulet`/`UseAmulet`）与任何既有 quirk。
