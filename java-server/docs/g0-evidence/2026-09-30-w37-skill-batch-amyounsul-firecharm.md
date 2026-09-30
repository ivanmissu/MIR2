# W37 技能逐项接入：护身符充能门（CheckAmulet/UseAmulet）+ 施毒术（SKILL_AMYOUNSUL=6）/灵魂火符（SKILL_FIRECHARM=13）

日期：2026-09-30
基线：W36（野蛮冲撞）

## 结论

W37 接入官方 1..33 权威目录中最后两项道士主动技能：`SKILL_AMYOUNSUL = 6`（施毒术）与
`SKILL_FIRECHARM = 13`（灵魂火符）。两者在 Delphi 里共用同一条 W36 遗留的「本轮不做」项——
`CheckAmulet`/`UseAmulet`（Magic.pas:81/134）护身符充能门：施法者身上 `U_ARMRINGL`（左手镯）或
`U_BUJUK`（护身符/道符）槽位必须有一件 `StdMode = 25` 且 `Shape` 匹配的消耗品并还有余量，否则
`DoSpell` 直接把 `boSpellFail` 保持为 `True`——法力已经扣了、`RM_SPELL` 施法姿势也已经广播了，
但 `RM_MAGICFIRE` 弹道永远不会发出去，外层 `ClientSpellXY` 只应答一个孤零零的
`RM_MAGICFIREFAIL`（`SM_MAGICFIRE_FAIL = 639`）。这与既有的 `SpellRejected`（法力不扣、姿势不播、
`+FAIL`）是两种不同的失败形状，因此新增了 `WorldEvent.SpellFizzled` 而不是复用 `SpellRejected`。

## 行为对拍要点（严格等价翻译，行为冻结）

- **护身符充能门**（`CheckAmulet` Magic.pas:81，`UseAmulet` Magic.pas:134）：
  - 扫描顺序固定 `U_ARMRINGL` → `U_BUJUK`，取第一个满足条件的槽位，两槽从不合并计数。
  - 条件：`StdMode = 25`；`SKILL_FIRECHARM` 要求 `Shape = 5`（护身符）；`SKILL_AMYOUNSUL` 要求
    `Shape <= 2`（1=灰色药粉、2=黄色药粉，具体哪种由 `Shape` 本身决定后续效果）；
    `ROUND(Dura / 100) >= nCount`（本服每次施法 `nCount = 1`），银行家舍入与其余 `Round` 一致。
  - 命中后 `UseAmulet` 消耗 100 点耐久（`AMULET_CHARGE_UNITS`），复用既有 `damageEquipment` 的
    「耗尽即摘除」语义，触发 `SM_DURACHANGE`（642，已在 W?? 验证过，未新增行为）。
  - 未命中：`RM_MAGICFIRE` 不广播，只应答 `SM_MAGICFIRE_FAIL`；法力已经在 `DoSpell` 之前扣除且
    不回滚，`RM_SPELL` 施法姿势也已经广播——这是本轮新增的 `WorldEvent.SpellFizzled`，网关只映射
    裸 `SM_MAGICFIRE_FAIL` 包，不带 `SM_SYSMESSAGE`，也不改动 `+GOOD/+FAIL` 状态（`+GOOD` 早已由
    `SpellAccepted` answered）。
- **SKILL_FIRECHARM（灵魂火符）**：护身符命中后是与火球/雷电共用的单体延迟伤害链
  （`GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(m_WAbil.SC), ...)`，Magic.pas:439）——
  用「道术 SC」代替火球的「魔法 MC」，且只加一次 SC 而非二倍；延迟 1200ms 后落地，无
  `m_nAntiMagic` 闪避判定（与既有火球/雷电一致，因为目前没有任何目标带非零 `AntiMagic`）。
- **SKILL_AMYOUNSUL（施毒术）**：护身符命中后先过一次抵抗判定
  `Random(m_btAntiPoison + 7) <= 6`（Magic.pas:333/345）——由于目前没有任何目标携带非零
  `AntiPoison`，这个判定恒为真（抵抗恒不成立），单独拆到 `WorldRandom.Stream.POISON_RESIST`
  流上，以便未来接入 `AntiPoison` 属性时只需改分界值、不影响其余魔法掷骰的抽取顺序。
  - `Shape = 1`（灰色药粉）→ `POISON_DECHEALTH`：`GetPower13(40)`（`GetPower13` 是把常量拆成
    「1/3 固定 + 2/3 随等级线性」的第二种缩放函数，与 `GetPower`/`scalePower` 并列）+
    `GetRPow(SC) * 2` 秒，该值**同时**是 DoT 的持续时间（秒）——这是刻意保留的 Delphi 怪癖；
    每 2.5 秒（`dwPosionDecHealthTime`）执行一次 `DamageHealth(point + 1)`，`point` 由
    `Round(等级/3 * 秒数/10)`（`nAmyOunsulPoint = 10`）给出；`DamageHealth` 是比
    `StruckDamage`/`applyDamage` 更轻的路径——不设 PK 旗、不磨装备、不广播受击闪避，只扣血。
  - `Shape = 2`（黄色药粉）→ `POISON_DAMAGEARMOR`：期间该目标承受的**任意**伤害（近战或魔法，
    只要经过 `applyDamage`）乘以 `nPosionDamagarmor / 10 = 1.2`。
  - `MakePosion`（ObjBase.pas:22730）取**更长的剩余时间**而非叠加——较弱/较短的重新中毒如果
    换算出的到期时间不超过当前剩余时间，则连 `point`/施法者归属都不会被覆盖，本服严格保留这个
    「谁的持续时间更长就归谁」比较。
  - `sYouPoisoned`（M2Share.pas:2972，"你中毒了[时间:%d秒，点数:%d点]."）只在目标是玩家时发送，
    中毒的怪物没有任何提示，只有周期伤害/伤害倍率本身可见。

## 自动化证据

世界层 `WorldAmuletSkillTest`：

- `fireCharmFizzlesWithoutAnAmuletAndStillSpendsMana` —— 无护身符时施法仍扣蓝、仍应答
  `castSpell = true`，事件里只有 `SpellFizzled`、没有 `MagicFired`，木桩不掉血。
- `fireCharmConsumesOneAmuletChargeAndDealsDelayedDamage` —— 佩戴满耐久「护身符」后施法：
  `MagicFired` 正常广播，`ItemDurabilityChanged` 显示恰好消耗 100 点耐久，1200ms 延迟落地前后
  木桩血量的变化符合延迟伤害链模型。
- `amyounsulDecHealthPoisonsAPlayerTargetWithAMessageAndPeriodicDamage` —— 对玩家目标释放灰色
  药粉：目标收到含「你中毒了」的 `SystemMessage`、施法者收不到；2.5 秒后触发一次周期掉血。
- `amyounsulDamageArmorMultipliesEveryLandedHitBy1Point2x` —— 黄色药粉命中的木桩与未中毒的木桩
  各挨一发同等火球（确定性引擎、随机数收敛到下界排除噪声），中毒木桩的伤害正好是对照组的 1.2 倍
  （银行家舍入）。
- `amyounsulReapplicationNeverShortensOrWeakensTheActivePoison` —— 3 级道士先中毒（长持续时间/
  大 point），0 级道士随后用同色药粉对同一目标重新施法（短持续时间落在前者剩余窗口内）；两次
  DoT 周期伤害完全相同，证明较弱的重新中毒没有覆盖持续时间/point/施法者归属。

网关层 `GameAmuletProtocolTest`：

- `fireCharmWithoutAnAmuletSendsOnlyMagicFireFailAndKeepsStatusGood` —— 无护身符：只发
  `SM_MAGICFIRE_FAIL`，不发 `SM_MAGICFIRE`、不发 `SM_SYSMESSAGE`，`+GOOD` 状态不变。
- `fireCharmWithAnAmuletSendsMagicFireAndWearsTheCharm` —— 有护身符：`SM_MAGICFIRE` 与
  `SM_DURACHANGE` 都发出，不出现 `SM_MAGICFIRE_FAIL`。

矩阵：`g4-capability-matrix.tsv` 中 `SKILL_AMYOUNSUL`、`SKILL_FIRECHARM`（各三职业行）由
`unimplemented` 转 `implemented + gate-game`；`SM_MAGICFIRE_FAIL`（已在更早批次接入）沿用既有行，
未改动其证据。

## 环境限制说明（本轮未能在本地跑通 `mvn verify`）

执行本轮改动的沙箱环境没有预装 JDK/Maven，且出站网络只放行 `pypi.org`/`files.pythonhosted.org`/
`github.com`/`codeload.github.com`/`api.github.com`/`registry.npmjs.org`，无法访问
`repo1.maven.org` 或任何已知的 Maven Central 镜像来解析 `junit-jupiter` 等测试依赖，因此本轮
**没有**跑通 `mvn -f java-server/pom.xml verify`。作为替代，改动前后对每一个新增/修改的方法都逐一
核对了被调用方法的真实签名、字段名与返回类型（`Equipment.at`/`BackpackItem.item/.dura`/
`damageEquipment`/`getMagicPower`/`rollExclusive`/`WorldEvent.SystemMessage`/
`PendingMagicImpact` 的双构造函数重载等），并对四个改动文件与两个新增测试文件做了括号配对的
静态扫描；但这**不能替代**真实编译器的类型检查与单元测试执行。请在有 Maven Central 出网权限的
环境中尽快补跑 `mvn -f java-server/pom.xml verify` 予以确认；若发现签名不符，问题范围应仅限于
本文件列出的这批新增代码（`WorldEngine.java` 的 `castAmuletGatedSpell`/`castFireCharm`/
`castAmyounsul`/`findAmulet`/`consumeAmulet`/`applyPoisonStatus`/`tickPoison*` 一族方法，以及
`WorldEvent.SpellFizzled`、`GameProtocolAdapter` 的对应 `case` 分支）。

## 边界（本轮不做）

- `AntiPoison`/`AntiMagic` 属性本身仍未接入装备/怪物数据（`EquipmentBonus`/`MonsterDb.tsv` 均无
  对应列），因此施毒术的抵抗判定与灵魂火符的闪避判定目前都是「恒定结果」的占位实现，只保证掷骰
  顺序独立（各自专属的 `WorldRandom.Stream`），分界值留给未来接入时再改。
- `SKILL_HANGMAJINBUB`(14 幽灵盾) 仍 `unimplemented`——`DamageHealth` 的 MP 吸收分支（相关但更
  复杂）留给幽灵盾立项时再做，本轮 `applyPoisonDamage` 只实现了它的 HP-only 分支。
- 装备磨损掷骰（`StruckDamage` 里 `POISON_DAMAGEARMOR` 命中后是否额外触发护甲磨损）不在本轮
  范围内，与 W33 起历次战士技能批次的「装备磨损掷骰延后」保持一致。
- 不为本批单独新增 shadowdiff 场景或门禁行。

下一项：待 `AntiPoison`/`AntiMagic` 属性接入后回填施毒术/灵魂火符的真实抵抗判定；或转向法师
多格穿透/群体魔法（`SKILL_FIRE`(9 地狱火)/`SKILL_SHOOTLIGHTEN`(10 疾光电影)）。

## 追记（2026-09-30，W38 收尾时）

W38 收尾阶段打通了离线测试工具链（JUnit 最小替身 + ECJ 3.38 批编译器，详见
`2026-09-30-w38-skill-batch-fire-shootlighten.md` 的「测试执行情况」），本批全部用例已实际
执行并通过：`WorldAmuletSkillTest`（5/5）、`GameAmuletProtocolTest`（2/2）包含在
world 238/238、gate 124/124 的全绿结果内。上节「环境限制说明」所述风险就此消除。
