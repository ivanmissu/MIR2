# 影子对拍回归：GEEM2 真实库导入后（W18）

*2026-09-23 · W18 · 沙箱实跑（本地 ECJ + jdk4py 链路构建）*

W18 把 `MonsterTemplate`、`StdItems` 与掉落表的全部占位/TODO(verify) 数值替换成官方 1.76
GEEM2 基线（378 行 Monster.DB、684 项 StdItems、10 张 MonItems）之后，重跑 W16/W17 交付的
shadowdiff 影子对拍，验证「数据替换不改行为、不破坏两进程确定性」。

## 1. 结论速览

| 场景 | 命令 | 判定 | 说明 |
| --- | --- | --- | --- |
| 带怪 PvE（8 木桩，严格判定） | `--embedded --pve --seed 20260922 --strict-messages` | **PASS**（状态 0 / 应答 0 / 消息 0，18 步） | 对拍目标已升级：木桩 HP 9999（`练功师` 真行）、战斗/掉落读真实库 |
| 标准 28 步脚本（背包/装备/使用/丢弃/重登往返） | `--embedded --seed 20260921` | **PASS**（0/0/0，29 步含进图） | 物品 wire 字段全走真实 StdItems（鸡肉 Looks 13、木剑 Shape 1 …）仍逐字节一致 |

## 2. 对拍此刻的含金量（与 W17 证据的差异）

- 木桩：`练功师` 行 HP 9999 / AC 0 / Exp 1（W17 时为占位 5000）；`hit` 观测面不再是占位数值。
- 掉落：`鸡` 掉 `鸡肉`(1/1)（占位期有 1/8 概率带子）；`鹿` 掉 `肉`（真库无「鹿肉」，W03 占位掉落退役）；
  `洞蛆` 掉 `蛆卵`(1/5) + 9 种特戒各 1/1000 万——9 条 `ItemDrop` 名称全部解析自真实目录 Looks。
- `goldDrops`（金币行）已解析但落地延期到 P3 金币地面堆切片，线上行为不变。
- 半兽人 `ATTACK_SPD=2500ms`（占位 1000ms）、鸡 exp=9 等节拍/经验真值进入 world 引擎，
  由 253 例全量测试（其中 `Geem2BaselineImportTest` 5 例逐字段钉死模板↔DB↔掉落）固化。

## 3. 实跑日志摘录

```
# 带怪 PvE（种子两侧共用 20260922，严格判定）
[shadowdiff] embedded-left  up: ... monsters=8xtrainer
[shadowdiff] embedded-right up: ... monsters=8xtrainer
[shadowdiff] left=embedded-left ... right=embedded-right ..., 17 ops
[shadowdiff] verdict=PASS state=0 acks=0 messages=0

# 标准脚本（28 ops + enter）
[shadowdiff] left=embedded-left ... right=embedded-right ..., 28 ops
[shadowdiff] verdict=PASS state=0 acks=0 messages=0
```

（本次用沙箱 `java -cp` 直跑 `ShadowDiffMain`，等价于 `java -jar mir2-shadowdiff.jar` 的
embedded 模式；报告生成于 `/tmp/mir2-w18/w18-sh/{seeded,default29}/shadow-report.{md,csv}`。）

## 4. 全量测试

```bash
bash /tmp/mir2-w18/tools/build.sh && bash /tmp/mir2-w18/tools/run-tests.sh
# TOTAL=253 PASS=253 FAIL=0   （W17 基线 248 + Geem2BaselineImportTest 5 例）
```

## 5. 边界（不变）

- seeded 之外的生产路径仍是「单全局随机流」（`MIR2_WORLD_SEED` 未设），与 Delphi 一致。
- 会动的怪对拍仍受墙钟支配（需虚拟时钟注入切片）；真实客户端 Delphi-vs-Java 对拍仍卡 Windows 环境。
- 木桩数值为官方行但 `练功师` 在原版属 NPC 类（TRAINER=55），Java 侧以 monster 对象对拍——不改变红线状态。
