# G0 研发证据：W28 三技能最小可验收切片

**日期：** 2026-09-24  
**对应阶段：** P3 / W27 执行顺序第 2 项  
**状态：** ✅ 火球术、治愈术、魔法盾闭环完成；CI 与既有 shadowdiff 全绿

## 1. 交付范围

| 验收项 | 实现 | 自动化证据 |
|---|---|---|
| 单体伤害 | 火球术（ID 1）：距离、职业/等级/已学习、MP、共享冷却校验；600 ms 延迟命中；MC/MAC 伤害；复用 `ObjectStruck`、`HealthChanged`、死亡/经验链 | `WorldMagicTest#fireballConsumesManaHonoursDelayAndUsesTheNormalDamageChain` |
| 单体恢复 | 治愈术（ID 2）：仅玩家目标（含自身），800 ms 延迟，SC 恢复，HP 钳制 MaxHP | `WorldMagicTest#healingTargetsAnotherPlayerAndClampsAtMaximumHealth` |
| Buff 生命周期 | 魔法盾（ID 31）：自身 Buff、不可叠加、物理/魔法减伤、受击削短、tick 过期；不进入 `PlayerState`，因此重登清理 | `WorldMagicTest#magicShieldAppliesExpiresAndNeverSurvivesRelog` |
| 技能书与列表 | StdMode 4 按书名学习，重复/职业/等级失败不耗书；登录 `SM_SENDMYMAGIC`、学习 `SM_ADDMAGIC` | `WorldMagicTest#skillBookLearnsPersistsAndIsSentAgainOnLogin`、`GameMagicProtocolTest` |
| 快捷键 | `CM_MAGICKEYCHANGE` 更新并持久化 `TUserMagic.btKey` | `GameMagicProtocolTest#loginSpellKeyChangeAndFireballUseTheClassicWireLayouts` |
| 确定性失败 | `SpellRejected` 经 gate 输出 `SM_MAGICFIRE_FAIL`、确定性 `SM_SYSMESSAGE` 与命令 `-FAIL` | `WorldMagicTest#failuresAreDeterministicAndDoNotSpendMana`、`GameMagicProtocolTest#rejectedSpellSendsMagicFireFailSystemMessageAndCommandFail` |

世界状态仍不接触 Socket：施法动作、效果、学习、列表和失败均先形成 `WorldEvent`，仅 `GameProtocolAdapter` 负责 CM/SM 映射。

## 2. 权威数据与 wire 布局

- `scripts/extract-geem2-db.py` 新增 `MagicDb.tsv` 提取；只接受 ID 1–33 连续交集，显式排除英雄重复行和 34 号后的重编号扩展。
- `world/src/main/resources/db/MagicDb.tsv` 固化 33 行技能定义；`MagicCatalog` 同时按 ID/中文名索引。
- `MagicCodec` 按 Delphi 6 四字节对齐输出 84 字节 `TClientMagic`；固定偏移由 `MagicCodecTest` 钉死。
- `CM_SPELL` 按 `Recog=MakeLong(X,Y)`、`Param/Series=targetId`、`Tag=MagicId` 解码；`SM_SPELL` 与 `SM_MAGICFIRE` 字段按 `ObjBase.pas` 映射。
- `Ability`/50 字节 `TAbility` 恢复 MAC/MC/SC 全范围；职业等级曲线、装备加成、SQLite 往返和 gate 固定偏移均有测试。

## 3. 持久化与迁移

- `character_state` 原位增加 `min/max_mac`、`min/max_mc`、`min/max_sc`，旧行默认 0 并在玩家进入时按职业/等级重算。
- 新增 `character_magic(character_id, magic_id, level, training_points, key_code)`；技能和玩家状态在同一保存事务内替换。
- `SqliteStoreTest#magicalAbilityRangesAndUserMagicRoundTripAtomically` 覆盖六个能力端点和两条技能记录的关闭/重开往返。
- 魔法盾为明确的瞬态状态，不写入该表，重登不会残留。

## 4. G4 矩阵变化

| 分区 | W27 矩阵 | W28 后 |
|---|---:|---:|
| CM implemented | 31 / 87 | **33 / 87** |
| SM implemented | 70 / 215 | **75 / 215**（另有 3 needs-client-check） |
| SKILL implemented | 0 / 177 | **9 / 177**（3 个技能 × 3 职业，含职业拒绝路径） |

新增接线：`CM_SPELL`、`CM_MAGICKEYCHANGE`、`SM_SPELL`、`SM_ADDMAGIC`、`SM_SENDMYMAGIC`、`SM_MAGICFIRE`、`SM_MAGICFIRE_FAIL`。其余 56 个技能继续保持未实现，不以 catalog 有数据冒充能力完成。

## 5. 验证记录

提交 `ee148c7` 的 push CI：

- **Java server protocol baseline** run `35965921489`：全部成功。
  - Maven 全量测试、可执行 JAR smoke、Compose/Docker 校验成功。
  - embedded dual-server、seeded PvE、virtual-clock moving-monster shadowdiff 成功，且 divergence 自检成功。
  - 50-bot / 2-minute stability、wiretool record/replay 成功。
- **Java server dist build** run `35965921440`：fat JAR 构建与 dist 发布成功。
- W27 基线 381 个测试（W26 371 + 矩阵门禁 10），本切片新增 13 个测试，故当前口径为 **394/394**；Actions 日志二进制端点在沙箱内偶发 EOF，CI run 结论为权威记录。
- 本地静态检查：`python3 -m py_compile scripts/extract-geem2-db.py`、`git diff --check` 均通过。本沙箱无系统 Maven/JDK，完整 Java 跑批使用上述 CI。

## 6. 后续边界

W27 下一项转入 NPC/Market_Def 指令清单与未知指令的可观察拒绝边界。技能侧后续仍须逐项依据 `Magic.pas` 接入，尤其是范围技、召唤、毒/隐身状态和训练升级；本切片不声称完成其余技能。
