# W34 技能逐项接入：刺杀剑术 / 半月弯刀

日期：2026-09-28  
基线：W33（基本剑术、精神力战法、攻杀剑术）

## 结论

W34 接入两把「可切换」的主动武器技：`SKILL_ERGUM=12`（刺杀剑术，`wHitMode=4`）与
`SKILL_BANWOL=25`（半月弯刀，`wHitMode=5`）。它们不同于 W33 的被动准确技，而是
`TPlayObject.ClientSpellXY`（ObjBase.pas:9037-9073）里的开关：按键翻转
`m_boUseThrusting` / `m_boUseHalfMoon` 并回一个 `+LNG/+ULNG/+WID/+UWID` 标签帧，
客户端据此决定后续发 `CM_LONGHIT/CM_WIDEHIT` 还是普通 `CM_HIT`。

### 行为对拍要点（严格等价翻译，行为冻结）

- **开关**：`castPlayerSpell` 命中 `isToggledWeaponSkill` 时走 `toggleWeaponSkill`——翻转标志、
  发绿字提示、发 `WeaponSkillToggled` 与 `SpellAccepted`。`IsWarrSkill`（Magic.pas:211）同样短路
  掉法力/冷却门，故开关不耗蓝、不受 `m_dwMagicAttackInterval` 限制，永远回 `+GOOD`。
- **自动启用**：`ReadBook`（ObjBase.pas:17377）在学得书后 `ThrustingOnOff(True)`/`HalfMoonOnOff(True)`，
  带绿字提示 + 标签；登录（ObjBase.pas:16602）只对「已学刺杀」静默重挂 `+LNG`（**无提示**），
  半月弯刀登录不自动启用——两条路径在此处刻意分叉。
- **命中判定不看服务端开关**：`AttackDir`（ObjBase.pas:18790/18845）对 `wHitMode 4/5` 只校验
  「书是否学得」（半月另加 `m_WAbil.MP > 0`）。开关是客户端决定「发哪个包」，服务端收到
  `CM_LONGHIT/CM_WIDEHIT` 即按学得与否解析，未学退化为 `RM_HIT`。
- **几何 / nSecPwr**（`SwordLongAttack`/`SwordWideAttack`，ObjBase.pas:22169-22200）：一次
  `GetAttackPower` 掷出的 `nPower` 同时喂正前主目标与追加目标。
  - 刺杀：命中正前**第 2 格**单目标，`nSecPwr = Round(nPower / (btTrainLv+2) * (btLevel+2))`
    （`btTrainLv=3` 硬编码，`SwordLongPowerRate=100` 忽略）。
  - 半月：扫 `dir-1 / dir+1 / dir+2` 三格扇形（正前 dir 是主目标不含），
    `nSecPwr = Round(nPower / (btTrainLv+10) * (btLevel+2))`。
  - 追加目标用 `DirectAttack`（ObjBase.pas:21969）：`Random(max(1,SpeedPoint)) < HitPoint`，
    **无** 主目标的 `HitPoint>0` 木桩门，伤害整额落地（不掷 AC）。
- **法力**：半月每挥先 `DamageSpell(btDefSpell + GetMagicSpell)` = Magic.DB id25 的固定 3 MP
  （spell=0、defSpell=3），刺杀无消耗；即便挥空也照扣，与 Delphi 一致。
- **训练**：主目标穿透命中后 `TrainSkill(skill, 1)`（定值 +1，区别于被动的 `Random(3)+1`）+
  一次 `CheckMagicLevelup`，门 `level<3 && charLevel>=TrainLevel[level]`，余量留 `nTranPoint`。
- **广播**：`wHitMode 4→SM_LONGHIT(19)`、`5→SM_WIDEHIT(24)`；退化时回 `SM_HIT(14)`。
  普攻/HEAVY/BIG/POWER 分支保持原样，防 W03/W33 回归。

## 自动化证据

世界层 `WorldSpecialAttackSkillTest`：

- `castingAToggleFlipsTheShapeWithoutSpendingManaOrACooldown`
  —— 半月开/关两次翻转、绿字提示、不耗蓝、无冷却。
- `readingTheBookLearnsTheSkillAndAutoEnablesItsShape`
  —— 读刺杀/半月书 → 学得 + 自动启用（提示 + `WeaponSkillToggled(on)`）。
- `loginReEnablesThrustingSilentlyButLeavesHalfMoonOff`
  —— 登录只静默重挂刺杀（无提示），半月不启用；已挂状态下按键翻转为关。
- `thrustingStrikesTheFrontTargetAndTheCellBeyondItAndTrains`
  —— 50 级战士（minDc=9）正前 9 伤 + 第 2 格 `nSecPwr=4`，广播 `SM_LONGHIT`，训练 +1。
- `halfMoonSweepsTheThreeCellFanAndSpendsItsMana`
  —— 正前 9 伤 + 三格扇形各 `nSecPwr=1`，扣 3 MP，广播 `SM_WIDEHIT`，训练 +1。
- `anUnlearnedShapeIdentDegradesToAnOrdinaryFrontHit`
  —— 未学时 `CM_LONGHIT` 只打正前、不触第 2 格、广播 `SM_HIT`、不训练。

网关层 `GameSpecialAttackProtocolTest`：

- `weaponSkillToggledBecomesTheMatchingTagFrame`
  —— `WeaponSkillToggled → +LNG/+ULNG/+WID/+UWID`，跨会话隔离，编码 `#+LNG!` 等裸标签帧。
- `clientLongAndWideHitReachTheWorldAndDegradeToHitForAnUnlearnedActor`
  —— `CM_LONGHIT/CM_WIDEHIT` 被 `isSupported`/`handle` 接受并转发，未学退化 `SM_HIT`。
- `longAndWideHitBroadcastsMapToTheirOwnIdents`
  —— `ObjectAttacked(LONG_HIT/WIDE_HIT) → SM_LONGHIT/SM_WIDEHIT`，字段布局对拍。

回归与门禁：

- `WorldWarriorSkillTest.castingAWeaponSkillIsAcknowledgedWithoutManaCooldownOrTarget`
  的「未实现战士技拒绝」断言从 id=12（刺杀，已实现）改用 id=27（野蛮冲撞，仍未实现）。
- `G4CapabilityMatrixTest`：`g4-capability-matrix.tsv` 中 `CM_LONGHIT/CM_WIDEHIT`、
  `SM_LONGHIT/SM_WIDEHIT`、`SKILL_ERGUM/SKILL_BANWOL`（三职业）由 `protocol-only/unimplemented`
  升为 `implemented + gate-game + 证据`，与 `isSupported` / 实际 SM 发射一致。

本环境未安装 JDK/Maven（`java`、`mvn` 不可用；且 Maven Central 出网被封，无法拉取依赖），
本轮未能在沙盒执行 `mvn verify`；代码与测试按仓库 JDK 21 目标编写，CI/G4 执行器负责完整编译回归。
`g4-release-gate.tsv` 未新增行：技能 shadowdiff 场景仍按 W31 的统一收口计划处理。

## 边界

- 未接入 `CM_CRSHIT`/双龙斩（`SKILL_CROSSMOON=34`，`CrsWideAttack`）与狂风斩（38）：其 Magic.DB
  行超出本服加载的 1..33 权威目录，显式延后；`烈火剑法(26)`、`野蛮冲撞(27)` 仍 `unimplemented`。
- 仍未建模 `SM_SUBABILITY` 的完整 Hit/Speed 子属性快照（沿用 W33 边界）。
- 未改变 W31 G4 清单场景；技能 shadowdiff 场景待一批技能收口后统一加入。
