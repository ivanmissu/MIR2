# W27 下一步开发计划：G4 玩法等价收口

**基线：** W26（组队管理、经验分摊、装备取下拒绝提示），本地 371/371 测试通过。

## 目标

W27 不再扩展新的大系统，而是完成 P3 的验收收口：把现有能力整理成可重复验证的 G4 证据，并明确技能、NPC 脚本、行会/攻城尚未完成的边界，避免把占位实现误报为已完成。

## 执行顺序

### 1. 建立 G4 缺口清单（P0）

- 从 `GameProtocolAdapter`、`WorldEngine` 和 Delphi `Grobal2.pas` 生成 CM/SM 能力矩阵。
- 对每条能力标记：已实现、仅协议占位、仅服务端逻辑、未实现、需要真实客户端对拍。
- 将技能 59×3、Market_Def 指令、行会、攻城、交易分别列出，不以“有常量”视为完成。
- CI 增加清单格式校验，避免新协议常量没有对应状态。

### 2. 技能系统最小可验收切片（P0）— 已完成（W28）

W28 已按经典 `Magic.DB` 1–33 交集建立基础框架，并实现三类基础技能，而不是一次性翻译 59 个技能：

1. 单体伤害：距离、MP、冷却、伤害与 `SM_HEALTHSPELLCHANGED/SM_STRUCK`。
2. 单体恢复：目标筛选、MP 消耗、上限钳制。
3. Buff 生命周期：施加、过期、重登清理。

要求：所有技能都经过能力/职业/等级/MP 校验；技能失败必须有确定的 `SM_SYSMESSAGE`；技能状态不直接修改 Socket，只通过 `WorldEvent` 出站。完成基础框架后，再按 Delphi `Magic.pas` 的技能表逐项接入。

### 3. NPC 脚本安全边界（P1）— 已完成（W29）

W29 已建立 `MerchantCommand` 目录（`M2Share.pas` 26 个商人标签全量分类），并交付安全边界：

- 保持现有修理 NPC 状态机兼容（`@repair`/`@s_repair` 行为不变，返回类型收敛为语义化 `MerchantSelectOutcome`）。
- 新增 Market_Def 指令清单与拒绝未知指令的测试（`MerchantCommandTest`、`WorldMerchantCommandTest`、`GameMerchantCommandProtocolTest`）。
- 低风险指令 `@exit`（关窗 `SM_MERCHANTDLGCLOSE`）已开放；买卖/仓库/制药/升级/冠名保持 `DEFERRED_TRANSACTION`，回跳/主菜单/消息保持 `DEFERRED_SCRIPT`，事务测试前不接线。
- 未实现脚本不再静默成功：非实现标签一律 `WorldEvent.MerchantActionRejected`（含 label/category/status/reason）+ 日志，线上仍与 Delphi 一样不发包。

证据见 `docs/g0-evidence/2026-09-24-w29-merchant-command-boundary.md`。

### 4. 交互与持久化回归（P1）— 已完成（W30）

W30 已把 §4 收敛为 shadowdiff 固定脚本与 CLI 门禁入口，而不是继续扩大玩法面：

1. **双人组队回归**：`--embedded --duo party --strict-messages` 进程内拉起左右两套服务端、每套双账号双 GAME 会话；脚本覆盖「未开组队先拒绝 → 开关组队 → 建组 → 近身组队击杀鸡 → 掉落拾取 → 12 格外经验边界 → 队长踢人 → 双方重登」。
2. **死亡 / PK 回归**：`--embedded --duo death-pk --strict-messages` 覆盖玩家谋杀、交手/红黄名颜色消息、死亡自动退队、PK 点落库与双方重登恢复（受害者 HP<=0 重登 14 HP）。
3. **持久化回归**：`--embedded --persistence --strict-messages` 预种 `木剑` / `布衣(男)` / `金创药(小量)` 与 3000 金币，走穿戴、丢弃、拾取、吃药、取下、重登，比较金币、装备、背包与关键消息集合。
4. **装备锁定提示回归**：`--embedded --lock --strict-messages` 通过同生产解析器加载 GBK `DisableTakeOffList.txt`，验证 `SM_TAKEOFF_FAIL` + `SM_SYSMESSAGE`「无法取下物品」可观测，且锁定装备重登后仍留在穿戴槽。

支撑改动集中在 `shadowdiff`：`DuoHarness`、`Op` 的 `p1`/`p2` 前缀与组队 op、`ShadowSession` 的组队名单 / 名字颜色 / 地面物品快照、`ShadowDiffMain` 的 `--duo`/`--persistence`/`--lock` 模式，以及 `ScenarioRegressionTest` 的五个端到端 CLI 用例。证据见 `docs/g0-evidence/2026-09-25-w30-interaction-persistence-regression.md`。

### 5. G4 证据与发布门禁（P0）— 已完成（W31，结论：G4 未签发）

W31 把发布门禁从「散落在 CI YAML / 文档 / JUnit 的三处描述」收敛为一份数据 + 一个执行器：

- `docs/g4-release-gate.tsv`：8 列门禁清单（id / kind / blocking / expect / command / artifact /
  scope / notes），10 行 = `mvn verify` + 9 个 shadowdiff 场景（base、seeded PvE、AI、AI 负控制、
  首批 10 种 AI 矩阵、duo party、duo death-pk、persistence、lock）。
- `scripts/g4-release-gate.sh`：数据驱动执行器，替换 `{{jar}}/{{report}}/{{repo}}`、按 `expect`
  比对退出码（`exit-1` 只属于负控制，`exit 2` 崩溃一律失败），输出每行日志、shadowdiff 报告与
  中文汇总 `g4-release-gate.md`；支持 `--list` / `--only` / `--skip-maven` / `--jar`。
- `G4ReleaseGateTest`（shadowdiff 模块，7 用例）：校验清单格式与场景完整性、把每行参数向量喂给真正的
  `ShadowDiffMain.Args` 解析、锁死负控制语义、禁止执行器硬编码场景，并且**只要能力矩阵还有
  `unimplemented` 行，证据文档就必须写明「G4 未签发」**。
- CI 新增 `g4-release-gate` job：打包 shadowdiff fat JAR 后跑全量清单（`mvn verify` 由 `test` job 承担）
  并上传跑批证据。

结论按计划要求输出缺口排序而非签发：技能 168 行、Market_Def 事务 23 标签、行会/攻城/交易 32 行仍
`unimplemented`，真实 mir2.exe / Delphi 外部基线仍挂账，故 **不签发 G4**。证据见
`docs/g0-evidence/2026-09-25-w31-g4-release-gate.md`。

## 本轮不做

- 不凭空补齐 59 个技能或 57 种怪物。
- 不引入脚本引擎、交易、行会、攻城的半成品实现。
- 不改变 W26 已验证的组队经验公式和协议 quirk。
- 不以真实客户端未可用为由修改已有 Delphi 语义。

## 当前进度与下一项

W27 计划五项全部落地：§1 G4 能力矩阵、§2 三技能最小切片（W28）、§3 NPC/Market_Def 指令清单与安全拒绝
边界（W29）、§4 交互/持久化 shadowdiff 回归（W30）、§5 G4 证据与发布门禁（W31）。**G4 未签发。**

下一项按 W31 §5 的缺口排序推进 **技能逐项接入（缺口 1）**：以 `Magic.pas` 为准按批迁移，每批独立测试、
独立证据，并把对应 shadowdiff 场景直接写入 `docs/g4-release-gate.tsv`，让新玩法从第一天起就进发布门禁。
Market_Def 事务（缺口 2）在库存/金币事务模型与回滚测试就绪前继续保持 `DEFERRED_TRANSACTION`；
行会 / 攻城 / 交易与真实客户端对拍仍是显式红线。

### 技能逐项接入·第一批（W32）— 已完成

W32 在 W28 框架上接入两个技能，两者都复用既有的单体延迟伤害管线，不引入新形状：

- `SKILL_FIREBALL2`（5，大火球）：Magic.pas:280 与 `SKILL_FIREBALL` 共用同一 `case` 分支，Java 侧同样
  直接复用 `rollFireballPower`/`validSpellTarget`/`MagicImpactKind.DAMAGE` 管线，只是换成大火球自己的
  Magic.DB 等级/威力/冷却数据。
- `SKILL_LIGHTENING`（11，雷电术）：Magic.pas:392，同为单体延迟伤害，额外复刻
  `TargeTBaseObject.m_btLifeAttrib = LA_UNDEAD` 时最终伤害 `ROUND(nPower * 1.5)` 的判定；为此把
  Monster.DB 已导入但此前无消费方的 `Undead` 列接到 `MonsterTemplate.undead()`（首批接线怪物中仅
  稻草人 `Undead=1`，回归测试可观测）。

`docs/g4-capability-matrix.tsv` 对应 6 行（`SKILL_FIREBALL2`/`SKILL_LIGHTENING` × 3 职业）由
`unimplemented` 转为 `implemented`，evidence 复用 `WorldMagicTest`。证据见
`docs/g0-evidence/2026-09-25-w32-skill-batch-fireball2-lightening.md`。

**本轮不做**：没有给这两个技能接 shadowdiff 场景或 `g4-release-gate.tsv` 新行——W28 的三个基础技能
同样只有单元测试证据，本批延续同一验收基线，不单独抬高门槛；技能类 shadowdiff 场景仍是本条目未清的
待办，留给技能批次全部就绪后一次性补齐更有效率。

下一项：继续按 `Grobal2.pas` SKILL_* 顺序推进第二批（候选：`SKILL_ONESWORD`/`SKILL_ILKWANG` 等战士
武器技需要先梳理 `ObjBase.pas:9030` 的近战特殊出招管线，`SKILL_FIRECHARM` 等符箓类需要先梳理
`CheckAmulet`/`UseAmulet` 的护身符消耗模型——两者都比本批的“纯复用”更重，需要单独立项）。

### 技能逐项接入·第二批（W33）— 已完成：战士武器技 + 准确/敏捷模型

W33 接入 `SKILL_ONESWORD`(3 基本剑术)、`SKILL_ILKWANG`(4 精神力战法)、`SKILL_YEDO`(7 攻杀剑术)。这三个
技能在 Delphi 里被 `MagicManager.IsWarrSkill`（`Magic.pas:211`）挡在 `DoSpell` 之外，**是纯被动量**：
唯一作用是给 `TBaseObject.RecalcHitSpeed`（`ObjBase.pas:18551`）贡献准确点。而准确点此前在 Java 侧没有
任何消费方，所以本批的主体是先补齐这条链：

- **准确 / 敏捷模型**：新 `HitSpeed` 值类复刻 `RecalcHitSpeed`（`DEFHIT=5`/`DEFSPEED=15`、道士 `+3`、
  三张 `Round(n/3*level)` 加成表、`m_nHitPlus = DEFHIT + level`、节拍 `7 - level`）；`RecalcAbilitys`
  之后叠加装备的 `wHitPoint/wSpeedPoint`；怪物侧把 `Monster.DB` 的 `SPEED`/`HIT` 两列（此前已导入、
  无消费方）接到 `MonsterTemplate`，对应 `UsrEngn.pas:2606` 的直抄语义。
- **`_Attack` 闪避判定**（`ObjBase.pas:22240`）：`if 目标准确 > 0 then if 我方准确 < Random(目标敏捷)
  then nPower := 0`。木桩（`练功师`，`HIT=0`）因此永不被闪避，shadowdiff 的 PvE 基准仍确定性。
- **攻杀剑术闭环**：`ClientAttack` 的节拍块（`ObjBase.pas:8861`）→ `+PWR` 裸标签帧 → 客户端下一刀发
  `CM_POWERHIT(3018)` → `wHitMode=3` 消费 `m_boPowerHit` 加 `m_nHitPlus` → `AttackDir` 广播
  `SM_SPELL2(117)`（未武装时仍是 `SM_HIT`）。
- **`SM_SUBABILITY(752)`** 随每个 `SM_ABILITY` 出站（`ObjBase.pas:5601`），准确/敏捷第一次对客户端可见。
- **`ClientSpellXY` 空实现分支**（`ObjBase.pas:9027`）：3/4/7 回 `+GOOD`，不扣蓝、不进冷却、不要目标；
  同属 `IsWarrSkill` 但本批未实现的 12/25/26/27/34/38 明确回 `+FAIL` +「该技能尚未开放」。
- 顺带补上 `ReadBook → RecalcAbilitys`（`ObjBase.pas:23466`），并修正 `applyDamage` 在 0 伤害时仍广播
  `SM_STRUCK` 的旧偏差（Delphi 的 `StruckDamage`/`RM_STRUCK` 都在 `if nPower > 0` 里）。

矩阵 9 行（3 技能 × 3 职业）转 `implemented`，`CM_POWERHIT`/`SM_SPELL2`/`SM_SUBABILITY` 由
`protocol-only` 转 `implemented`。证据见 `docs/g0-evidence/2026-09-26-w33-warrior-weapon-skills.md`。

W33 后续小批补上两个被动技能的熟练度闭环：穿透近战命中后按 Delphi `TrainSkill(Random(3) + 1)`
累加 `character_magic.training_points`，执行一次 `CheckMagicLevelup` 风格升级；训练结果通过世界层
`SkillTrainingChanged` 事件出站，由 gate 编码为 `SM_MAGIC_LVEXP` 的 magic id、level 与熟练度低/高字。
`WorldMeleeSkillTest` 覆盖命中训练、等级/熟练度余数、持久化重登与 gate 无 Socket 依赖的边界。

**本轮不做**：刺杀/半月/烈火/野蛮冲撞/双龙斩/狂风斩（每个都要新的攻击形状：直线三格、扇形、十字、
位移撞墙）；属性点加点 `m_BonusAbil`；攻杀/特殊技能的额外训练规则；本批同样不加 shadowdiff 场景或门禁行。

下一项：`SKILL_ERGUM`(12 刺杀剑术) 起的**特殊攻击形状**批次（`SwordLongAttack` 直线三格 →
`SwordWideAttack` 扇形 → `CrsWideAttack` 十字），它们与本批共用已经铺好的 `wHitMode`/`+标签帧`/
`AttackKind` 管线，只需补几何与目标选择；`SKILL_AMYOUNSUL`（6，施毒术）或 `SKILL_FIRECHARM`（13，灵魂火符）
仍需先梳理 `CheckAmulet`/`UseAmulet` 的护身符消耗模型，不能简化成扣 MP。

### 技能逐项接入·第三批（W34）— 已完成：刺杀剑术 / 半月弯刀（可切换特殊攻击形状）

W34 接入 `SKILL_ERGUM`(12 刺杀剑术，`wHitMode=4`) 与 `SKILL_BANWOL`(25 半月弯刀，`wHitMode=5`)。
两者是 `ClientSpellXY`（ObjBase.pas:9037-9073）里的**开关型**主动技：按键翻转
`m_boUseThrusting`/`m_boUseHalfMoon` 并回 `+LNG/+ULNG/+WID/+UWID` 标签帧，客户端据此决定发
`CM_LONGHIT/CM_WIDEHIT` 还是普通 `CM_HIT`；`ReadBook` 学书即自动启用（带绿字），登录只静默重挂刺杀。

- 命中解析看「书学得 + 半月另需 MP>0」，与服务端开关无关（`AttackDir` 18790/18845），未学退化 `RM_HIT`；
- 几何：刺杀打正前第 2 格单目标；半月扫 `dir-1/+1/+2` 三格扇形（正前是主目标）。追加目标用
  `DirectAttack`（无木桩门，整额落地，不掷 AC），`nSecPwr = Round(nPower/(3+offset)*(level+2))`，
  刺杀 offset=2、半月 offset=10；
- 半月每挥先扣固定 3 MP（Magic.DB id25 spell=0/defSpell=3），刺杀无消耗；
- 主目标穿透命中 `TrainSkill(skill,1)` 定值训练 + `CheckMagicLevelup`；
- 广播 `SM_LONGHIT(19)`/`SM_WIDEHIT(24)`；普攻/攻杀分支保持原样防回归；
- 证据：`WorldSpecialAttackSkillTest`、`GameSpecialAttackProtocolTest`；`WorldWarriorSkillTest`
  的「未实现战士技拒绝」断言由 id=12 改用 id=27；`g4-capability-matrix.tsv` 相应六行升为
  `implemented+gate-game`。详见 `docs/g0-evidence/2026-09-28-w34-skill-batch-ergum-banwol.md`。

**本轮不做 / 显式延后**：`CM_CRSHIT`/双龙斩（`SKILL_CROSSMOON=34`，`CrsWideAttack` 十字）与狂风斩(38)——
其 Magic.DB 行超出本服加载的 1..33 权威目录；`烈火剑法(26)`、`野蛮冲撞(27)` 仍 `unimplemented`。

下一项：待 Magic.DB 目录扩到含 34/38 后接入 `CrsWideAttack`（十字）与狂风斩；或转向
`SKILL_AMYOUNSUL`(6)/`SKILL_FIRECHARM`(13) 前先立项护身符消耗模型（`CheckAmulet`/`UseAmulet`）。

### 技能逐项接入·第四批（W35）— 已完成：烈火剑法（一次性蓄力）

W35 接入 `SKILL_FIRESWORD`(26 烈火剑法，`wHitMode=7`)——`IsWarrSkill` 九个 id 里的第三种形态：
W33 的 3/4/7 是纯被动，W34 的 12/25 是开关，烈火是**一次性蓄力**，不引入任何新的攻击几何。

- **蓄力**：`ClientSpellXY`(9092) → `AllowFireHitSkill`(9782) 的 `> 10s` 严格闸门置
  `m_boFireHitSkill` 并盖时间戳，**先点旗后判蓝**，`GetSpellPoint` = Magic.DB id26 的固定 7 MP，
  够蓝才扣并回 `+FIR`（蓝不够则旗亮而客户端不知情，quirk 照搬）；整个分支恒 `Result := True`，
  冷却中按键也回 `+GOOD`，只有红字「召唤烈火精灵失败...」。
- **消费**：`_Attack`(22128) 清旗 + 重新盖时间戳（禁止双烈火）并
  `nPower += Round(nPower/100*(m_nHitDouble*10))`，`m_nHitDouble = 4 + 4·等级`(18622)；
  砍空分支(22152)同样烧掉蓄力但不加伤；`AttackDir`(18841) 只在旗亮时广播 `SM_FIREHIT(8)`，
  否则退化 `SM_HIT`。
- **失效**：`TPlayObject.Run`(6427) 的 `> 20s` 清旗 + 红字 +`+UFIR`。
- **训练**：守卫是 `wHitMode = 7` 而非旗，未蓄力的烈火刀穿透命中同样 `TrainSkill(skill,1)`(22354)。
- 证据：`WorldFireSwordSkillTest`（6 用例）、`GameSpecialAttackProtocolTest`（扩展三个用例）；
  矩阵 `SKILL_FIRESWORD` 三行 + `CM_FIREHIT`/`SM_FIREHIT` 升为 `implemented`。
  详见 `docs/g0-evidence/2026-09-28-w35-skill-batch-firesword.md`。

### 技能逐项接入·第五批（W36）— 已完成：野蛮冲撞（SKILL_MOOTEBO=27）与推人位移/撞墙模型

W36 接入 `SKILL_MOOTEBO`(27 野蛮冲撞)——官方 1.76 基线（1..33）战士技能树全部收口，也是服务端首个位移/击退类技能。

- **触发与闸门**：`ClientSpellXY`(9112) 的 `> 3 * 1000` 冷却闸门与 `GetSpellPoint`（4/8/11/15 MP）消耗；
  朝向从 `nTargetX` 解析；未过冷却依然应答 `+GOOD`。
- **推人与双推**：`CanMotaebo`(21705) 严格要求施法者等级 > 目标等级；0/1 级冲 3 格，2 级 4 格，3 级 5 格；
  3 级冲撞可双推正前紧邻两格的目标；主要目标被退 1 格并掉转朝向，怪物步行动作延迟 800ms。
- **撞墙受挫与反震**：撞墙或推人受阻时广播 `SM_RUSHKUNG(7)` 与红字「冲撞力不够...」；步数未尽撞墙施法者承受反震伤害。
- **熟练度训练**：穿透冲撞命中目标后 `TrainSkill(skill, Random(3) + 1)` 并触发 `CheckMagicLevelup`。
- 证据：`WorldMotaeboSkillTest`（7 用例）、`GameMotaeboProtocolTest`（3 用例）；`WorldWarriorSkillTest` 的未实现战士技拒绝断言由 id=27 改用 id=34；
  矩阵 `SKILL_MOOTEBO` 三行及 `SM_RUSH`/`SM_RUSHKUNG`/`SM_BACKSTEP` 升为 `implemented`。
  详见 `docs/g0-evidence/2026-09-28-w36-skill-batch-motaebo.md`。

**本轮不做 / 显式延后**：
- `双龙斩(34)`、`狂风斩(38)`、`逐日剑法` 等超出 1..33 官方目录的技能继续延后。
- 不为本批单独新增 shadowdiff 场景或门禁行。

下一项：转向道士符箓消耗模型（`CheckAmulet`/`UseAmulet`）与 `SKILL_FIRECHARM`(13 灵魂火符)/`SKILL_AMYOUNSUL`(6 施毒术)；
或法师多格穿透/群体魔法（`SKILL_FIRE`(9 地狱火)/`SKILL_SHOOTLIGHTEN`(10 疾光电影)）。

### 技能逐项接入·第六批（W37）— 已完成：护身符充能门（CheckAmulet/UseAmulet）+ 施毒术/灵魂火符

W37 接入 `SKILL_AMYOUNSUL`(6 施毒术)/`SKILL_FIRECHARM`(13 灵魂火符)，一并立项两者共用的
`CheckAmulet`/`UseAmulet`（Magic.pas:81/134）护身符充能门——官方 1..33 权威目录中最后两项道士
主动技能，全部收口。

- **充能门**：`U_ARMRINGL`→`U_BUJUK` 顺序扫描 `StdMode=25` 且 `Shape` 匹配的消耗品，
  `ROUND(Dura/100)>=1` 才算命中，命中后 `UseAmulet` 耗 100 耐久（复用既有 `damageEquipment`）。
  未命中时法力已扣、`RM_SPELL` 已播，但 `RM_MAGICFIRE` 永不广播，只应答裸 `SM_MAGICFIRE_FAIL`——
  与既有 `SpellRejected`（不扣蓝、不播姿势、`+FAIL`）是不同的失败形状，新增
  `WorldEvent.SpellFizzled` 承载。
- **灵魂火符**：护身符（`Shape=5`）命中后是与火球/雷电共用的单体延迟伤害链，用「道术 SC」顶替
  「魔法 MC」且只加一次（不二倍），1200ms 延迟。
- **施毒术**：药粉（`Shape<=2`）命中后先过 `Random(AntiPoison+7)<=6` 抵抗判定（`AntiPoison` 属性
  未接入前恒为真，单独拆到 `WorldRandom.Stream.POISON_RESIST`）；`Shape=1` 灰色药粉→
  `POISON_DECHEALTH`（`GetPower13(40)+GetRPow(SC)*2` 秒 DoT，该值同时是持续时间秒数，每 2.5 秒
  `DamageHealth(point+1)`）；`Shape=2` 黄色药粉→`POISON_DAMAGEARMOR`（受到任意伤害 ×1.2）；
  `MakePosion` 取更长剩余时间而非叠加；`sYouPoisoned` 只提示玩家目标。
- 证据：`WorldAmuletSkillTest`（5 用例）、`GameAmuletProtocolTest`（2 用例）；矩阵
  `SKILL_AMYOUNSUL`/`SKILL_FIRECHARM` 各三行升为 `implemented+gate-game`。
  详见 `docs/g0-evidence/2026-09-30-w37-skill-batch-amyounsul-firecharm.md`。

**本轮不做 / 显式延后**：
- `AntiPoison`/`AntiMagic` 属性本身仍未接入装备/怪物数据，抵抗判定目前是恒定结果的占位实现。
- `SKILL_HANGMAJINBUB`(14 幽灵盾) 的 MP 吸收分支仍 `unimplemented`；`applyPoisonDamage` 只实现了
  `DamageHealth` 的 HP-only 分支。
- 护甲磨损掷骰、shadowdiff 场景/门禁行本轮不新增。

**环境限制**：本轮开发沙箱无 Maven/JDK 预装，且出网仅放行 pypi/GitHub/npm 域名，无法访问 Maven
Central 解析测试依赖，因此本轮改动未能在本地跑通 `mvn -f java-server/pom.xml verify`——已对每个
新增/修改方法逐一核对真实签名/字段名，但请在有 Maven Central 出网权限的环境中尽快补跑验证。

下一项：待 `AntiPoison`/`AntiMagic` 属性接入后回填真实抵抗判定；或转向法师多格穿透/群体魔法
（`SKILL_FIRE`(9 地狱火)/`SKILL_SHOOTLIGHTEN`(10 疾光电影)）。
