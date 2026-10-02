# W41 开发计划：技能 shadowdiff 通道与 SKILL_FIREBOOM 评估

**基线：** W40（群体治愈术 `SKILL_BIGHEALLING=29` 已接入世界引擎与 gate 协议，能力矩阵三行均为 `implemented`）；G4 仍未签发。

## W40 已落地语义

- `WorldEngine.castAreaHealing`：一次功率轮共享给 `GetMapBaseObjects(nRage=1)` 的 3×3 方形；
- 友方判定收窄到 `IsProperFriend` 的 `HAM_GROUP` 分支：施法者本人 + 在线队友，怪物 / NPC / 非队友一律排除；
- `HP < MaxHP` 门同时决定 `boTrain`，满血目标不产生 HP 事件；
- `MagicImpactKind.AREA_HEAL`：800 ms 延迟、绑定对象（离格仍生效）、到达时复核队伍关系；
- 点击吸附（`CretInNearXY`，±1 格）决定方形中心与 `SM_MAGICFIRE` 坐标；
- world / gate 验收：`WorldAreaHealingTest`、`GameAreaHealingProtocolTest`。

## W41 已完成：shadowdiff 施法通道（P0）

- [x] `Op` 支持 `spell <magicId> <x> <y> [targetId]`；`ShadowSession` 以真实 `CM_SPELL` 发包：`Recog=MakeLong(x,y)`、`Param/Series=targetId` 低/高字、`Tag=magicId`。
- [x] `SeededAccount` 可预置职业、等级、存活 HP 缺口和 `PlayerSkill` 行；嵌入式世界会用同一参数创建角色记录和 `PlayerState`。这使 shadowdiff 不需要新增任何服务端作弊命令也能从 31 级、已学群体治愈术的道士状态起跑。
- [x] `StateSnapshot` 已比较 HP/MP，并新增由 `SM_SENDMYMAGIC` / `SM_ADDMAGIC` 建立、由 `SM_MAGIC_LVEXP` 更新的已学技能快照（魔法 ID、等级、熟练度、快捷键）。技能训练差异因此是 `STATE` 差异，而不是仅存在于消息日志中。
- [x] 内置 `Op.areaHealingScript()`：预种的受伤道士在 `(20,20)` 发出 `spell 29 20 20`，以 `tick 16` 精确推进 800 ms，观察即时 MP 消耗、延迟 HP 恢复、`SM_MAGIC_LVEXP`，并重登确认技能行与能力值持久化。
- [x] `--embedded --skills` 启动上述 MANUAL 时钟场景；`--right-seed 99999` 是非空转负控制，改变 `MAGIC` 随机流后必须 `exit 1`。
- [x] `docs/g4-release-gate.tsv` 新增 `shadowdiff-skills` 和 `shadowdiff-skills-negative` 两个 blocking 行，`G4ReleaseGateTest` 机械校验它们与所有负控制；`ScenarioRegressionTest` 覆盖正向和错种子负向 CLI 回归。
- [x] `SKILL_BIGHEALLING:taoist` 能力矩阵行追加 `ScenarioRegressionTest`；该 shadowdiff 证据明确仅为 Java↔Java 确定性回归，不是 Delphi 或真实客户端字节级结论。

## 本地验证命令

```bash
# 正向：同种子两套嵌入式 Java 世界必须一致
java --enable-native-access=ALL-UNNAMED \
  -jar java-server/shadowdiff/target/mir2-shadowdiff.jar \
  --embedded --skills --strict-messages --seed 20260922 \
  --report-dir reports/shadowdiff-skills

# 负向：右侧 MAGIC 流种子不同，必须报告差异（退出码 1）
java --enable-native-access=ALL-UNNAMED \
  -jar java-server/shadowdiff/target/mir2-shadowdiff.jar \
  --embedded --skills --seed 20260922 --right-seed 99999 \
  --report-dir reports/shadowdiff-skills-negative
```

## 后续候选：SKILL_FIREBOOM（P1）

爆裂火焰（23，`MagBigExplosion`，`g_Config.nFireBoomRage = 1`）与群体治愈共享方形取样，但语义相反：逐目标抗魔、伤害、阻挡与中心格判定都必须先从 Delphi 源码确认。无法确认的字段保持 `protocol-only`；不得按群体治愈的形状猜测性实现。

## 本轮不做

- 不接入召唤、隐身、火墙、地狱雷光、圣言术、冰咆哮；
- 不实现攻击模式（`CM_CHANGEATTACKMODE`）——群体治愈的友方范围继续保持 `HAM_GROUP` 最严格分支；
- 不实现 Market_Def 交易事务、行会、攻城；
- 不把 Java↔Java shadowdiff 证据描述成真实客户端 / Delphi 字节级等价；
- 不因本切片完成而签发 G4：真实 `mir2.exe` / Delphi 外部基线仍是必要条件。
