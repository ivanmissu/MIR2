# W53 开发计划：法师高级技能批次收口 —— 魔法盾 / 圣言术 进入影子对拍

W52 完成了范围技能（爆裂火焰 23 / 地狱雷光 24 / 冰咆哮 33）的 shadowdiff 接入与门禁扩充，并在
下一候选（W53）中明确了方向：**31 魔法盾 `SKILL_SHIELD` 和 32 圣言术 `SKILL_KILLUNDEAD`
进入 `--skill-case` 确定性对拍**。本周完整交付该切片，并将 `g4-release-gate.tsv` 门禁清单从
22 行扩充至 26 行。

## 一、为什么选择魔法盾与圣言术

| 技能 | 机制与命令形状 | 确定性对拍依据 |
|---|---|---|
| `SKILL_MAGIC_SHIELD`(31) | 自身地面施法（`Recog=(20,20)`），MC 抽签决定持续秒数（18–19s），存续期重复施法被拒，超时消失后可重施 | MANUAL 虚拟时钟可精准推进秒数；持续期重复施法确定性触发 `BUFF_ALREADY_ACTIVE` 拒绝；超时后再次施法成功 |
| `SKILL_KILLUNDEAD`(32) | 单体指定目标（`Tag=32, targetId=2/6`），针对不死怪（`Monster.DB Undead=1` 稻草人）的等级门与即死概率抽签 | 单体目标显式指定；`TURN_UNDEAD` 随机流与等级差确定即死率（51%）；即死触发 `SM_DEATH`、`SM_WINEXP`、掉落与 `trainSpellSkill` 训练 |

## 二、源码核验与问题修复（Delphi，`GameOfMir/M2Server/Magic.pas` & `ObjBase.pas`）

1. **魔法盾技能熟练度训练修复**：
   - Delphi `Magic.pas:568-571` 中：`if MagBubbleDefenceUp(PlayObject, UserMagic.btLevel) then boTrain := True;`，盾激活成功时设置 `boTrain := True` 触发 `TrainSkill`。
   - Java 端在 `WorldEngine.castSpell` 激活魔法盾时此前遗漏了 `trainSpellSkill(player, skill, magic)` 调用。本周补齐该调用，使魔法盾激活时正确增加技能熟练度（`train=0 -> 3`）并向客户端发送 `SM_MAGIC_LVEXP`，且在存续期重复施法被拒时不增加熟练度。
2. **魔法盾持续时间与状态流**：
   - 31 级法师自然 MC 为 3..4，`rollMagicShieldSeconds` 在 lv0 盾下计算为 `mc + 15` = 18 或 19 秒。
   - 施法产生 `STATE_BUBBLEDEFENCEUP` 状态位与 `"魔法盾已生效，持续N秒"` 系统消息。
   - 存续期内重复施法直接被 `BUFF_ALREADY_ACTIVE`（`"魔法盾效果仍在持续"`）拒绝，不消耗 MP，不增加训练。
   - 推进 400 ticks（20 秒）后状态自然消失，再次施法成功。
3. **圣言术等级门与即死链路**：
   - Delphi `Magic.pas:572-577` → `MagTurnUndead(Magic.pas:901-925)`：
     - 首道门：目标必须为不死生物（`LA_UNDEAD` / `Monster.DB Undead=1`），否则直接返回；
     - 怪物仇恨被唤醒（`Struck`）；
     - 等级门：`Random(2) + (casterLevel - 1) > targetLevel` 且 `targetLevel < g_Config.nMagTurnUndeadLevel(50)`；
     - 即死概率：`Random(100) < ((skillLevel << 3) - skillLevel + 15 + (casterLevel - targetLevel))`；
     - 即死成功：怪物 HP 归 0，触发 `handleDeath`（`SM_DEATH`、经验、掉落）以及 `trainSpellSkill` 训练。
   - 39 级法师预种 1 级圣言术（`NeedL2=35`），对 10 级不死稻草人即死率为 `7 + 15 + 29 = 51%`。
   - 种子 20260922 下：首发对北臂 2 号稻草人掷出 87（未即死，仅扣蓝和唤醒），次发对南臂 6 号稻草人掷出 50（< 51%，即死成功，触发死亡、+15 经验、掉落与训练）。

## 三、本周改动范围

### 1. `SkillCase` 与脚本

- `SkillCase.SHIELD`: 法师 31 级，预种 `SKILL_MAGIC_SHIELD`(31) lv0，无怪，执行 `Op.shieldScript()`。
- `SkillCase.KILLUNDEAD`: 法师 39 级，预种 `SKILL_KILLUNDEAD`(32) lv1，8 × 不死稻草人，执行 `Op.killUndeadScript()`。
- 脚本设计：
  - `shieldScript`: 激活盾（MP 572→537，训练+3）→ 29 ticks 后重复施法（被拒，MP/训练不变）→ 370 ticks 后重施（盾已过，重新激活）→ 400 ticks 后重施 → 365 ticks 后测试 18s/19s 差异 → 重登验证持久化。
  - `killUndeadScript`: 对 2 号稻草人施法（未即死，耗蓝 65）→ 30 ticks 跨过冷却 → 对 6 号稻草人施法（即死成功，死亡+15经验+掉落+训练+3）→ 重登验证经验与熟练度。

### 2. 门禁与测试扩充

- `g4-release-gate.tsv` 新增 4 行（正负各 2 行，总行数由 22 行扩至 26 行）：
  - `shadowdiff-skills-shield` (`exit-0`)
  - `shadowdiff-skills-shield-negative` (`exit-1`)
  - `shadowdiff-skills-killundead` (`exit-0`)
  - `shadowdiff-skills-killundead-negative` (`exit-1`)
- `G4ReleaseGateTest`: 必需行与负向对照集合扩充至 26 行与 9 行。
- `ScenarioRegressionTest`: 新增 4 个回归用例（2 正向严格消息 + 2 负向）。
- `OpTest` & `ShadowDiffMainArgsTest`: 补充参数解析与脚本结构断言。
- `g4-capability-matrix.tsv`: `SKILL_SHIELD:wizard` 与 `SKILL_KILLUNDEAD:wizard` 追加 evidence 与 notes。

## 四、负向对照验证（右侧 `--right-seed 99999` 实测）

| 场景 | 观测到的差异 | 判定 |
|---|---|---|
| `shield` | 第三轮持续时间抽签（19s vs 18s），在 365 ticks (18.25s) 处右侧已超时可重施、左侧仍在持续被拒（16 步状态差异） | FAIL (exit 1) ✅ |
| `killundead` | 首发即死抽签差异（左侧 87 未命中 vs 右侧 36 即死成功），经验 0 vs 15、死亡帧与训练差异（7 步状态差异） | FAIL (exit 1) ✅ |

## 五、验收与状态

- [x] Delphi 源码核验（Magic.pas:568-577 / 901-925；ObjBase.pas:24331-24350）
- [x] `WorldEngine.java` 修复 `SKILL_MAGIC_SHIELD` 的 `trainSpellSkill` 熟练度增加逻辑
- [x] `Op.shieldScript()` 与 `Op.killUndeadScript()` 确定性脚本实现
- [x] `ShadowDiffMain.SkillCase` 扩展 `SHIELD` 与 `KILLUNDEAD`
- [x] `ScenarioRegressionTest` 4 个测试全部绿灯
- [x] `G4ReleaseGateTest` 与 `G4CapabilityMatrixTest` 全部绿灯
- [x] `g4-release-gate.tsv` 26 行扩充完成
- [x] `g4-capability-matrix.tsv` 能力矩阵更新
- [x] 本地全量技能门禁 16 行实跑全部绿灯（exit 0）

## 下一候选（W54）

- **首选**：`--skill-case` 扩展至道士辅助与防御技能 —— **14 幽灵盾 `SKILL_HANGMAJINBUB`** 与 **15 神圣战甲术 `SKILL_DEJIWONHO`**（W46 已实现护身符消耗与 MAC/AC 增益，接入确定性 shadowdiff 对拍）。
- 备选：**28 心灵启示 `SKILL_SHOWHP`** 对拍，或道士召唤物机制前期设计。
- 红线技能（16/17/20/30 与 34–41 扩展段）、57 怪行为扩展、NPC 脚本引擎继续维持红线。
