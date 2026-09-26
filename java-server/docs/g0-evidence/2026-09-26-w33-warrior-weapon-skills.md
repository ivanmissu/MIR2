# W33 · 技能逐项接入·第二批：战士武器技 + 准确/敏捷模型

日期：2026-09-26　分支：`arena/01a0dc88-mir2`　范围：`java-server/world`、`java-server/gate`

本批把 `Grobal2.pas` 里 id 最小的三个战士武器技接入引擎：**基本剑术**（`SKILL_ONESWORD`=3）、
**精神力战法**（`SKILL_ILKWANG`=4）、**攻杀剑术**（`SKILL_YEDO`=7）。与 W28/W32 的三个法术不同，这三个
技能在 Delphi 里根本不走 `MagicManager.DoSpell`——`IsWarrSkill`（`Magic.pas:211`）在函数第二行就把它们
挡掉。它们是**被动量**：唯一的作用是给 `TBaseObject.RecalcHitSpeed` 贡献 `m_btHitPoint`（准确），而准确
本身此前在 Java 侧没有任何消费方。所以本批的主体是先把 **准确 / 敏捷 → `_Attack` 闪避判定** 这条链补上，
三个技能才谈得上"实现"而不是"记了个数"。

## 1. 迁移的 Delphi 分支（逐条）

| Delphi 位置 | 内容 | Java 落点 |
|---|---|---|
| `M2Share.pas:128-129` | `DEFHIT = 5`、`DEFSPEED = 15` | `HitSpeed.DEF_HIT` / `DEF_SPEED` |
| `ObjBase.pas:18551` `RecalcHitSpeed` | 基线 + 道士 `+3` 敏捷 + 三个技能的准确加成 + `m_nHitPlus` / `m_btAttackSkillCount` / `m_btAttackSkillPointCount` | `HitSpeed.of(job, skills)` |
| `ObjBase.pas:3385 / 3394` | `RecalcAbilitys` 先跑 `RecalcHitSpeed`（仅 `RC_PLAYOBJECT`），再叠加 `m_AddAbil.wHitPoint/wSpeedPoint` | `WorldEngine.recalculateHitSpeed` |
| `ObjBase.pas:1241-1242` | 构造初值 5 / 15 | `Player.hitPoint` / `speedPoint` 字段初值 |
| `UsrEngn.pas:2606-2607` | 怪物直接抄 `Monster.DB` 的 `SPEED`/`HIT`，永不跑 `RecalcHitSpeed` | `MonsterTemplate.speedPoint()/hitPoint()`（`MonsterDb.Row` 的两列第一次有了消费方） |
| `ObjBase.pas:22240` `_Attack` | `if AttackTarget.m_btHitPoint > 0 then if (m_btHitPoint < Random(AttackTarget.m_btSpeedPoint)) then nPower := 0` | `WorldEngine.meleeEvaded` |
| `ObjBase.pas:22122 / 22145` | `if (wHitMode = 3) and m_boPowerHit then begin m_boPowerHit := False; Inc(nPower, m_nHitPlus); end`（命中与砍空两条分支都消费） | `attackWith` 开头快照 + `rollDamage(..., hitPlus, ...)` |
| `ObjBase.pas:8861` `ClientAttack` | 攻杀节拍：`Dec(count)` → 命中 `pointCount` 时 `m_boPowerHit := True; SendSocket(nil,'+PWR')` → `count <= 0` 时重置为 `7 - level` 并 `Random(count)` | `WorldEngine.advancePowerHitCadence` |
| `ObjBase.pas:8848 / 18841` | `CM_POWERHIT → wHitMode 3`；`AttackDir` 仅在快照到 `m_boPowerHit` 时把广播 ident 换成 `RM_SPELL2`，否则仍是 `RM_HIT` | `AttackKind.POWER_HIT` + `GameProtocolAdapter.attackKind/attackIdent` |
| `ObjBase.pas:9027` `ClientSpellXY` | 3/4/7 共用一个 `case` 分支，函数体只有 `Result := True`；`IsWarrSkill` 同时跳过 `CheckActionStatus` 与 `m_dwMagicAttackInterval` | `castPlayerSpell` 的战士技能早返回分支 |
| `ObjBase.pas:23466` `ReadBook` | 把 `TUserMagic` 加进 `m_MagicList` 之后立刻 `RecalcAbilitys()` | `readSkillBook` 补上 `recalculateAbilities(player)` |
| `ObjBase.pas:5601` `RM_ABILITY` | 每个 `SM_ABILITY` 之后紧跟一个 `SM_SUBABILITY`，`Param = MakeWord(m_btHitPoint, m_btSpeedPoint)` | `WorldEvent.SubAbilityChanged` → `GameProtocolAdapter.sendSubAbility` |
| `ClMain.pas:3620 / 2128` | `'+PWR'` 标签帧置 `g_boNextTimePowerHit`，客户端下一刀改发 `CM_POWERHIT` | `GameOutbound.Signal.POWER_HIT` → `#+PWR!` |

### 1.1 准确加成表（`Math.rint` = Delphi `Round` 的银行家舍入）

| 技能 | 公式 | level 0/1/2/3 |
|---|---|---|
| 基本剑术 | `Round(9 / 3 * level)` | 0 / 3 / 6 / 9 |
| 精神力战法 | `Round(8 / 3 * level)` | 0 / 3 / 5 / 8 |
| 攻杀剑术 | `Round(3 / 3 * level)` | 0 / 1 / 2 / 3 |

攻杀剑术另外无条件（**不**受 `btLevel > 0` 保护）写入 `m_nHitPlus = DEFHIT + level` 与节拍长度
`7 - level`，所以一本刚读完的 0 级攻杀剑术已经在数刀了。

### 1.2 两个照搬的 quirk

- **闪避判定以"目标自己的准确"为开关**：`if AttackTarget.m_btHitPoint > 0`。`练功师`（木桩）在
  `Monster.DB` 里 `HIT = 0`，因此**永远不会被闪避**——这正好保住了 shadowdiff 的 PvE 对拍基准是确定性的。
- **`Random(0)`**：Delphi 的 `Random(0)` 返回 0 但仍然推进随机数种子。Java 侧用
  `nextInt(ACCURACY, Math.max(1, speedPoint))` 精确复刻（`nextInt(1)` 恒 0 且消费一次抽签）。
- **精神力战法覆盖 `m_MagicOneSwordSkill`**（`ObjBase.pas:18627`）：它把自己的 `UserMagic` 写进了本属于
  基本剑术的指针字段。全 M2Server 源码里这个字段除赋值外只在 `_Attack` 的**技能熟练度**分支被读，而熟练度
  本身尚未移植，所以该 quirk 当前不可观测——只在 `HitSpeed` 的类注释里记录，不做无意义建模。

## 2. 行为变化（重要）

接上闪避判定后，近战命中率第一次真正受准确/敏捷支配。几个基线数字（角色无加点、无装备）：

| 场景 | 判定 | 结果 |
|---|---|---|
| 角色（准确 5）打鸡（敏捷 10、准确 3） | `5 < Random(10)` | 约 40% 落空 |
| 角色打半兽人（敏捷 15、准确 6） | `5 < Random(15)` | 约 60% 落空 |
| 半兽人打角色（角色敏捷 15） | `6 < Random(15)` | 约 53% 落空 |
| 鸡打角色 | `3 < Random(15)` | 约 73% 落空 |
| 任何人打木桩（准确 0） | 不判定 | 永不落空 |
| 基本剑术满级角色（准确 14）打半兽人 | `14 < Random(15)` | 约 6.7% 落空 |

这解释了为什么"基本剑术"在原版里是战士的第一本必读书：它把对同级怪的落空率从 60% 压到个位数。

**顺带修正的既有偏差**：`applyDamage` 过去在 `damage <= 0` 时仍然广播一个 0 伤害的 `ObjectStruck`。
Delphi 的 `StruckDamage` 与 `RM_STRUCK` 都在 `if nPower > 0` 里面（`ObjBase.pas:22252-22262`），落空/被
AC 完全吸收的一刀**在线路上是完全静默的**。W33 之前这只在"AC 吃满伤害"的罕见情形下出现，现在每一次
闪避都会走到，必须改正，否则客户端会对每一次落空播放受击动作并飘一个 0。

## 3. 随机数流

新增两条 `WorldRandom.Stream`，都**追加在枚举末尾**，seeded 模式下既有流的序列一字不动：

- `ACCURACY`：`_Attack` 的闪避抽签，每次打到合法目标抽一次。
- `POWER_HIT`：`Random(m_btAttackSkillCount)`，只有学过攻杀剑术的角色才会抽（`RecalcHitSpeed` 每次重算
  一次，节拍走完一轮再抽一次），因此**没有学过攻杀剑术的既有确定性向量完全不受影响**。

legacy 共享模式（`WorldRandom.of(new Random(seed))`，单元测试用）下所有流共用一个发生器，闪避抽签会整体
平移后续序列——这是不可避免的，受影响的既有用例已按"断言意图"而不是"断言某个随机噪声值"重写（见 §5）。

## 4. 新增/变更的对外行为

- `CM_POWERHIT (3018)` 进入 `isSupported` 白名单，映射到 `AttackKind.POWER_HIT`，与 `CM_HIT` 共用同一个
  攻击间隔（`sharesHitInterval()` 恒 true，对应 Delphi 用同一个 `m_dwAttackTick`）。
- 命中且消费了攻杀标志时，围观者收到 `SM_SPELL2 (117)`；未消费时仍是 `SM_HIT`。攻击者自己**收不到**出招
  广播（`ObjBase.pas` 对自己抑制 RM_HIT 系列，本引擎沿用）。
- 节拍触发时给**本人**发 `#+PWR!`（新的 `GameOutbound.Signal`，走 `+GOOD/+FAIL` 那条裸标签帧通道，
  没有 `/tick` 后缀）。
- 每个 `SM_ABILITY` 之后紧跟 `SM_SUBABILITY (752)`：`Recog = m_nAntiMagic`、
  `Param = MakeWord(准确, 敏捷)`、`Tag = MakeWord(抗毒, 解毒)`、`Series = MakeWord(体力恢复, 魔法恢复)`。
  后四项没有装备列喂数据，按裸角色的真实值发 0。
- `CM_SPELL` 打到 3/4/7 时回 `+GOOD`，不扣蓝、不进冷却、不需要目标；打到同属 `IsWarrSkill` 但本批未实现的
  12/25/26/27/34/38 时回 `+FAIL` + `SM_SYSMESSAGE「该技能尚未开放」`，绝不静默成功。

## 5. 因行为变化而改写的既有用例

| 用例 | 原写法为什么不再成立 | 改法 |
|---|---|---|
| `WorldCombatTest#monsterChasesAndAttacksTheNearestPlayerOnItsOwnIntervals` | 40 次 tick 内半兽人有一半刀落空 | 观察窗放宽到 120 tick |
| `WorldLevelAndDeathTest#healthAndManaRefillOnTheirOwnClocksButNotWhileDead` | 鸡 73% 落空、3 秒一刀；半兽人打 20 级战士要跑赢回血 | 两处循环预算放宽到 200 / 1500 tick |
| `WorldEquipmentTest#eatingAPotionRestoresHealthRemovesTheItemAndRefreshesWeight` | 单个攻击间隔不再保证挨一刀，且 6 秒回血会把伤口填平 | 循环等到真的出现 `damage > 0` 的 `ObjectStruck` |
| `WorldEquipmentTest#successfulMeleeHitWearsAndDestroysAZeroDurabilityWeapon` | 落空的一刀不磨武器 | 循环挥到第一刀命中为止 |
| `WorldPkAndEquipmentDropTest`（2 个） | `SetPKFlag` 只在 `nPower > 0` 分支触发，单刀不再必中 | 新增 `landOneBlow` 助手，挥到掉血为止 |
| `WorldRevivalRingTest`（3 个） | `StruckDamage` 的 1/8 逐格磨损也会啃复活戒指，绝对耐久不再固定 | 断言改为"整千的复活扣费"（`assertRevivalCharges`）+ 按耐久值匹配 `RM_DURACHANGE` |
| `GameProtocolAdapterTest` / `GameSessionIntegrationTest` | 登录序列多了一帧 `SM_SUBABILITY` | 补进期望 ident 序列、读帧数 +1 |

## 6. 新增测试

- `world/.../WorldWarriorSkillTest`（5 个用例）
  - `recalcHitSpeedReproducesTheDelphiTable`：三张加成表、道士 +3、`m_nHitPlus`/节拍、
    `IsWarrSkill` 的 9 个 id。
  - `theLearnedWeaponSkillsReachTheClientThroughSmSubAbility`：战士（基本剑术 3 级 + 攻杀 1 级）准确
    `5+9+1=15`；道士（精神力战法 3 级）准确 `5+8=13`、敏捷 `15+3=18`。
  - `aDodgedSwingCostsThePowerAndStaysSilentWhileAZeroHitTargetIsNeverDodged`：用"每次抽签都返回
    `bound-1`"的 `MaxRandom` 把闪避钉死为必定发生，断言伤害 0 且**没有** `ObjectStruck`；再对木桩
    （`HIT = 0`）断言必定命中。
  - `readingThePowerHitBookArmsTheCadenceAndTheNextPowerHitCarriesHitPlus`：用 `FixedRandom`（抽签恒 0）
    把 `m_btAttackSkillPointCount` 钉死为 0，于是 `+PWR` 精确出现在第 7 刀；第 8 刀发 `POWER_HIT` 伤害
    正好 `+5`（`DEFHIT + 0`）且围观者收到 `POWER_HIT` 广播；第 9 刀再发 `CM_POWERHIT` 伤害回落、广播退回
    `HIT`。这一条同时证明 `ReadBook → RecalcAbilitys` 已经接上（书是当场读的）。
  - `castingAWeaponSkillIsAcknowledgedWithoutManaCooldownOrTarget`：连按两个武器技都成功、蓝量不变、
    没有 `MagicFired`；未实现的刺杀剑术（12）被明确拒绝。
- `gate/.../GameWarriorSkillProtocolTest`（4 个用例）：`CM_POWERHIT` 入站 + 未武装时广播 `SM_HIT`、
  武装时 `SM_SPELL2`；`PowerHitReady → #+PWR!` 且不串号；`SM_SUBABILITY` 的四个字打包；登录帧里的
  `DEFHIT/DEFSPEED`。

## 7. G4 矩阵变化

| 分区 | W32 后 | W33 后 |
|---|---:|---:|
| SKILL implemented | 15 / 177（5 技能 × 3 职业） | **24 / 177**（8 技能 × 3 职业） |
| CM implemented | — | `CM_POWERHIT` 由 `protocol-only` 转 `implemented` |
| SM implemented | — | `SM_SPELL2`、`SM_SUBABILITY` 由 `protocol-only` 转 `implemented` |

`G4CapabilityMatrixTest` 的四项机械校验（59×3 技能行完整、`wire` 列与真实 `isSupported`/出站点一致、
evidence 指向真实存在的 `*Test`、状态自洽）全部通过。**G4 仍未签发**：SKILL 区还有 153 行
`unimplemented`，Market_Def 事务与行会/攻城/交易缺口未动。

## 8. 本轮不做

- **技能熟练度（`TrainSkill` / `CheckMagicLevelup` / `RM_MAGIC_LVEXP`）不做**。`_Attack` 在命中后会给
  基本剑术涨 `Random(3)+1` 点熟练度、给刚消费过攻杀的角色涨攻杀熟练度（`ObjBase.pas:22282-22313`），但
  熟练度体系对 W28/W32 的三个法术也同样缺席（`DoSpell` 的 `boTrain` 至今没有消费方）。它应该作为一个
  独立批次一次性接入全部技能，而不是在武器技这一批里只给两个技能开小灶。
- **刺杀剑术(12)/半月弯刀(25)/烈火剑法(26)/野蛮冲撞(27)/双龙斩(34)/狂风斩(38) 不做**。它们虽然同属
  `IsWarrSkill`，但每一个都要新的攻击形状（`SwordLongAttack` 直线三格、`SwordWideAttack` 扇形、
  `CrsWideAttack` 十字、冲撞的位移与撞墙判定），属于独立批次。本批显式让它们回 `+FAIL`。
- **属性点加点（`m_BonusAbil` / `CM_ADJUST_BONUS`）不做**，所以 `RecalcHitSpeed` 的
  `DEFHIT + m_BonusAbil.Hit div BonusTick.Hit` 在 Java 侧恒等于 `DEFHIT`。
- **不给本批加 shadowdiff 场景或 `g4-release-gate.tsv` 新行**，延续 W28/W32 的验收基线（技能类
  shadowdiff 场景仍是未清待办）。
- **`m_nHitDouble`（半月的双倍判定）、`m_AddAbil.btWeaponStrong`（武器强度减磨损）不做**——两者都只在
  本批未实现的技能/装备列上才有来源。

## 9. 验证记录

本沙箱依旧不含系统 JDK/Maven（`apt`、Adoptium 发行包、Maven Central 均被沙箱网络策略阻断），但本轮
搭起了一条**可真正执行的本地回归链**，不再只靠静态复核：

- 编译器：`@ctxo/lang-java-analyzer`（npm 可达）内含 Eclipse JDT 3.39 的 `ecj` 批处理编译器；
  运行时：`jdk4py` 提供的 Temurin 25 JRE。
- 用一份最小 JUnit 5 API 桩（`@Test` / `@TempDir` / `Assertions`）+ 反射式 runner 代替 surefire。
- 结果：`protocol` / `auth` / `character` / `world` / `gate` 五个模块 **61 个测试类、323 个用例全部通过**；
  全仓 10 个模块的 **121 个主源文件与 82 个测试源文件全部编译通过**；加上 `persistence` / `bootstrap` /
  `loadtest` / `shadowdiff` 后共 **415 个用例通过**，余下 28 个失败全部是本地缺 `sqlite-jdbc` 驱动
  （`No suitable driver found for jdbc:sqlite:`）或 runner 不支持参数注入所致，与本轮改动无关。

`mvn -f java-server/pom.xml verify` 的权威执行仍按仓库既定流程交给 CI（本文件在 CI 结论产出后补记）。

<!-- CI-RESULTS -->
