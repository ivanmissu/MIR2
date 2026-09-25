# G0 研发证据：W32 技能逐项接入·第一批（大火球 + 雷电术）

**日期：** 2026-09-25
**对应阶段：** P3 / W27 执行顺序第 2 项的延续（`docs/w27-next-plan.md` §「下一项」）
**状态：** ✅ `SKILL_FIREBALL2`（5，大火球）、`SKILL_LIGHTENING`（11，雷电术）闭环完成；矩阵/文档同步

## 1. 为什么是这两个技能

W31 的结论是「技能 168 行仍未实现，G4 未签发」。下一步要求「以 `Magic.pas` 为准按批迁移，每批独立测试、
独立证据」，而不是一次性猜测式补齐 59 个技能。本批选择大火球与雷电术，是因为它们在 Delphi 源码里就是
W28 已实现的 `SKILL_FIREBALL` 单体延迟伤害管线的直接延伸，风险最低、可验证性最高：

- `Magic.pas:280`：
  ```pascal
  SKILL_FIREBALL{1},
  SKILL_FIREBALL2{5}: begin //小火球 大火球
    ...GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(m_WAbil.MC), ...)...
    PlayObject.SendDelayMsg(PlayObject, RM_DELAYMAGIC, nPower, MakeLong(nTargetX,nTargetY), 2, Integer(TargeTBaseObject), '', 600);
  end;
  ```
  两个技能**共用同一个 `case` 分支**——大火球不是新形状，只是 Magic.DB 里换了一组等级/威力/冷却数字。
- `Magic.pas:392`（`SKILL_LIGHTENING`）：同样的单体延迟伤害公式、同样的 600ms 延迟，唯一的额外分支是
  ```pascal
  if TargeTBaseObject.m_btLifeAttrib = LA_UNDEAD then
    nPower := ROUND(nPower * 1.5);
  ```
  即命中不死系目标时，最终伤害（已经过双方各自的随机浮动、防御减免之前）乘以 1.5，四舍五入用 Delphi
  `Round` 的银行家舍入语义。

两者都不需要新的 wire 形状、新的 UI 概念或新的物品消耗模型，只是复用 W28 已经打通的
`rollFireballPower` / `validSpellTarget` / `MagicImpactKind.DAMAGE` 管线——这正是 W27 计划要求的
「不以‘有常量’视为完成」的反面：先证明可以对着真实 Delphi 分支做最小闭环，再逐步扩大到更重的技能形状
（战士武器技、符箓消耗、AOE/召唤）。

## 2. 交付范围

| 验收项 | 实现 | 自动化证据 |
|---|---|---|
| 大火球单体延迟伤害 | `SKILL_FIREBALL2`（id 5）复用 `SKILL_FIREBALL` 的距离/职业/等级/MP/冷却校验与 600ms 延迟伤害链，仅数据来自其自身 Magic.DB 行 | `WorldMagicTest#fireball2SharesTheFireballDelayedDamageChain` |
| 雷电术单体延迟伤害 | `SKILL_LIGHTENING`（id 11）同一管线，无需 `SKILL_FIREBALL` 特有的 8 格外校验以外的额外前置条件（Delphi 侧同样只走 `IsProperTarget`） | 与上同一测试类，另见下一行 |
| `LA_UNDEAD` 1.5x 判定 | 施法时（非命中结算时）按 `isUndead(targetObject)` 对 `rollFireballPower` 的最终结果做 `Math.rint(power * 1.5)`，与 Delphi `ROUND` 的银行家舍入语义一致；`MonsterTemplate` 新增 `undead` 字段，`MonsterDb.Row.undead()`（此前已导入但无消费方）在 `fromDb` 中接线；首批已接线怪物中仅**稻草人** `Undead=1` | `WorldMagicTest#lighteningAppliesTheUndeadMultiplierAtCastTime` |
| 学习/背包/协议 | 无需改动——`ReadBook` 按物品名匹配 `MagicCatalog`，`CM_SPELL`/`SM_SPELL`/`SM_MAGICFIRE`/`SM_MAGICFIRE_FAIL` 与网关分派对技能 id 完全通用；`大火球`（StdItemsDb id 27）/`雷电术`（id 91）技能书本已存在于 W18 权威导入，学习链路本就可用，只是此前施法会被 `UNSUPPORTED_SKILL` 拒绝 | 既有 `GameMagicProtocolTest` 覆盖通用协议路径，未受影响（无需新增） |

世界状态仍不直接触碰 Socket：两枚新技能与 W28 一样只产生 `WorldEvent`，`GameProtocolAdapter` 负责编码。

## 3. 代码改动

- `WorldEngine`：
  - 新增常量 `SKILL_FIREBALL2 = 5`、`SKILL_LIGHTENING = 11`、`LIGHTENING_UNDEAD_MULTIPLIER = 1.5`。
  - 抽出 `isDamageBolt(magicId)`（`FIREBALL`/`FIREBALL2`/`LIGHTENING`）复用于「允许施法的技能白名单」与
    `validSpellTarget` 的目标校验分支，避免逐处堆砌 `||`。
  - 新增 `isUndead(WorldObject)`：`target instanceof Monster monster && monster.template.undead()`。
  - `castPlayerSpell` 的伤害分支从「仅 `SKILL_FIREBALL`」扩展为「`FIREBALL`/`FIREBALL2` 共用一支，
    `LIGHTENING` 单独一支（多算一次 undead 乘算）」，与 Delphi `case` 语句的分组方式保持同构。
- `MonsterTemplate`：
  - record 新增 `boolean undead` 分量；新增一个 10 参数兼容构造函数（`undead` 默认 `false`），
    保留原有 8/9 参数兼容构造函数语义不变（现有所有测试用例均未受影响，见下）。
  - `fromDb` 把 `row.undead() != 0` 接入新分量；class/method 级 Javadoc 同步更正
    「race/Undead/CoolEye/SPEED/HIT 均无消费方」为「race/CoolEye/SPEED/HIT 仍无消费方，Undead 已接线」。
- 文档：`docs/g4-capability-matrix.tsv` 的 `SKILL_FIREBALL2:{warrior,wizard,taoist}` 与
  `SKILL_LIGHTENING:{warrior,wizard,taoist}` 六行由 `unimplemented` 改为 `implemented`
  （`wire=gate-game`，`evidence=WorldMagicTest`）；`docs/translation-map.md` 补充 `Magic.pas:216 DoSpell`
  一行，取代此前「Magic system... not started」的过期表述；`docs/w27-next-plan.md` 追加本批小节；
  `README.md` 补一条 W32 功能小结并修正「技能/魔法…仍未实现」的过期措辞为 5/59 已接线。

## 4. 雷电术 1.5x 倍率的测试方法

雷电术自身的威力区间（Magic.DB `Power=8, MaxPower=28`）很宽，叠加施法者 MC 区间的二次随机，两次独立
施法的原始伤害不适合直接比较大小（可能出现巧合的假阳性/假阴性）。测试用一个仅覆盖
`nextInt(int bound)` 返回 `0` 的 `FixedRandom`（`extends java.util.Random`）新增 `deterministicEngine`
构造：`WorldRandom.of(random)` 的每一路 `Stream` 共享同一个 `Random` 实例，把 `nextInt(bound)` 钉死为
`0` 后，`rollExclusive`/`between` 的每一次随机抽样都退化为区间下界，两次施法（一次打不死系稻草人、一次
打普通木桩）因此得到**完全相同的乘算前伤害**，只有 undead 分支的 1.5x 是唯一变量。断言直接核对
`round(livingDamage * 1.5) == undeadDamage`（`Math.rint`，与线上倍率计算同一舍入语义），不依赖也不
断言具体数值，因此不因职业成长曲线的系数调整而变脆。

## 5. G4 矩阵变化

| 分区 | W31 矩阵 | W32 后 |
|---|---:|---:|
| SKILL implemented | 9 / 177（3 技能 × 3 职业） | **15 / 177**（5 技能 × 3 职业） |

新增 6 行（`SKILL_FIREBALL2`/`SKILL_LIGHTENING` 各 3 职业）转为 `implemented`；矩阵总行数、CM/SM 分区
不变。`G4CapabilityMatrixTest#skillSectionCoversEveryDelphiSkillForAllThreeJobs` 继续校验 59 个
`SKILL_*` × 3 职业的 177 行不缺不多，`matrixIsWellFormed` 校验新行的 `wire`/`evidence` 与
`implemented` 状态自洽。

## 6. 本轮不做

- 不新增技能类 shadowdiff 场景或 `g4-release-gate.tsv` 行——W28 的三个基础技能同样只有单元测试证据，
  本批延续同一验收基线；技能类 shadowdiff 覆盖留给后续技能批次积累到一定数量后一次性补齐，避免每批都
  重新设计脚本。
- 不处理 `MagCanHitTarget`（施法命中前的视线/寻路阻挡校验）——这是 W28 上线火球术时就已经存在、未被
  记录的简化（`validSpellTarget` 目前只做距离与生存/阵营校验），本批雷电术按同样的简化处理以保持两个
  技能实现质量一致，不在无关变更里顺带引入新的行为面。
- 不处理战士武器技（`SKILL_ONESWORD` 等）或符箓消耗类技能（`SKILL_FIRECHARM` 等）——前者需要先移植
  `ObjBase.pas:9030` 附近的近战特殊出招管线，后者需要先移植 `CheckAmulet`/`UseAmulet` 的护身符消耗
  模型，两者都比「纯复用现有单体延迟伤害管线」重得多，留给独立批次。

## 7. 验证记录

本沙箱不含系统 JDK/Maven（`java`/`mvn` 均不可执行，`apt-get` 因沙箱网络策略无法安装），与既往每一份
W2x/W3x 证据的记录一致：改动经过人工静态复核（类型/可见性/现有构造函数调用点逐一核对，确认
`MonsterTemplate` 新增分量不破坏任何既有调用点——除本次新增测试外，仓库内所有 `new MonsterTemplate(...)`
调用均走未改变签名的兼容构造函数），并遵照仓库既定流程把最终 `mvn -f java-server/pom.xml verify` 的
执行与结论移交 CI（`.github/workflows/java-server.yml`）。提交后请以该 PR 的 Actions 运行结果为准；
如需要在合入前额外确认，可在有 JDK 21 + Maven 3.9 的环境本地重跑：

```bash
mvn -f java-server/pom.xml -pl world,gate -am test \
  -Dtest=WorldMagicTest,G4CapabilityMatrixTest
```
