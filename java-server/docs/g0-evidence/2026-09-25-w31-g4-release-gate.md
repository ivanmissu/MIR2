# G0 研发证据：W31 G4 证据与发布门禁

**日期：** 2026-09-25
**对应阶段：** P3 / W27 执行顺序第 5 项（G4 证据与发布门禁）
**状态：** ✅ 门禁清单 / 执行器 / CI 接线 / 数据校验测试入库；**G4 未签发**（缺口见 §5，沙箱无 JDK 见 §6）

---

## 1. 本轮交付（w27-next-plan §5）

| 计划要求 | 交付物 | 校验方式 |
|---|---|---|
| 汇总 G4 证据、命令、通过数与已知红线 | 本文件 + 跑批自动汇总 `g4-release-gate.md` | 执行器每次跑批重写 |
| `mvn verify` 必须全绿 | 清单行 `maven-verify`（blocking） | `scripts/g4-release-gate.sh` |
| 运行 shadowdiff：基础 PvE、AI 矩阵、组队经验与重登场景 | 清单 9 个 shadowdiff 行（base / pve / ai / ai-negative / ai-matrix / duo-party / duo-death-pk / persistence / lock） | 同上；CI 新增 `g4-release-gate` job |
| 只有技能、脚本、行会/攻城达标才签发 G4，否则输出缺口排序 | §5 缺口排序 + §4 的「不可签发」机械约束 | `G4ReleaseGateTest#theEvidenceDoesNotClaimG4WhileTheMatrixStillHasGaps` |

新增/修改文件：

```
java-server/docs/g4-release-gate.tsv                         门禁清单（唯一权威，8 列 TSV）
java-server/scripts/g4-release-gate.sh                       数据驱动执行器（--list/--only/--skip-maven/--jar）
java-server/shadowdiff/src/test/java/.../G4ReleaseGateTest.java  清单↔执行器↔CLI↔矩阵 一致性门禁（7 用例）
.github/workflows/java-server.yml                            新增 g4-release-gate job（打包后跑全量清单并上传证据）
```

---

## 2. 为什么是「清单 + 执行器」而不是再写一段 CI 脚本

此前 shadowdiff 场景散落在 CI YAML、证据文档和 JUnit 三处，任何一处漏改都会让「门禁全绿」名不副实。
W31 把场景集合收敛成一份 TSV：

- **CI、开发机、证据文档共用同一份数据。** 执行器只做替换 `{{jar}}` / `{{report}}` / `{{repo}}` 并比对退出码；
  `G4ReleaseGateTest#theRunnerIsDataDrivenByTheManifest` 禁止执行器里出现任何场景名（`maven-verify` 除外，
  它是 kind 判定需要），杜绝第二真值源。
- **命令不是字符串注释，而是被真解析器验证过的参数向量。** 测试把每行 `-jar` 之后的 token 喂给
  `ShadowDiffMain.Args.parse`，并强制 `--embedded` + `--report-dir` 且报告目录必须以声明的 artifact 结尾。
  一个拼错的开关过去会以 usage 退出码 2 结束——对负控制行甚至可能被误读成「检出差异」。
- **退出码语义写进数据。** `expect=exit-0` / `exit-1`，其中 `exit-1` 只允许负控制行使用（测试强制），
  `exit 0`（对拍失明）与 `exit 2`（崩溃）一律判失败。

## 3. 门禁清单（`docs/g4-release-gate.tsv`）

| id | kind | expect | 证明范围 |
|---|---|---|---|
| `maven-verify` | maven | exit-0 | 全模块单测/集成测试 + 能力矩阵门禁 + 本清单门禁 |
| `shadowdiff-base` | shadowdiff | exit-0 | 登录→选人→进图→走/说话/背包，严格消息逐 op 一致 |
| `shadowdiff-pve` | shadowdiff | exit-0 | 同种子木桩 PvE 伤害/HP/经验同流（守 `WorldRandom` 注入） |
| `shadowdiff-ai` | shadowdiff | exit-0 | MANUAL 时钟下会动的怪节拍一致（守虚拟时钟不漏墙钟） |
| `shadowdiff-ai-negative` | shadowdiff | **exit-1** | 右侧错种子必须被判差异——证明 AI 对拍非空转 |
| `shadowdiff-ai-matrix` | shadowdiff | exit-0 | 首批 10 种怪逐种 AI 对拍（`ai-matrix.md`） |
| `shadowdiff-duo-party` | shadowdiff | exit-0 | 组队全链路、共享经验与掉落、12 格边界、踢人、双方重登 |
| `shadowdiff-duo-death-pk` | shadowdiff | exit-0 | 谋杀、名字颜色、死亡自动退队、PK 点落库、重登 14 HP |
| `shadowdiff-persistence` | shadowdiff | exit-0 | 金币/装备/背包全生命周期跨重登一致 |
| `shadowdiff-lock` | shadowdiff | exit-0 | DisableTakeOffList 取下拒绝可观测且跨重登保持 |

跑批入口：

```bash
# 全量（需要 JDK 21 + Maven 3.9；先打包 shadowdiff fat JAR）
mvn -f java-server/pom.xml -pl shadowdiff -am -DskipTests package
java-server/scripts/g4-release-gate.sh --report-dir /tmp/g4

# 只看清单内容 / 只跑部分行 / 跳过 mvn verify
java-server/scripts/g4-release-gate.sh --list
java-server/scripts/g4-release-gate.sh --skip-maven --only shadowdiff-duo-party,shadowdiff-lock
```

产物：每行一个 `<id>.log`、shadowdiff 行各自的 `shadow-report.md` / `.csv`，以及汇总
`g4-release-gate.md`（含每行 PASS/FAIL、退出码、耗时，并在末尾重复「全绿 ≠ G4 签发」的红线）。

## 4. 「不可签发」的机械约束

`G4ReleaseGateTest` 的 7 个用例：

1. `manifestIsWellFormed` — 8 列、id 唯一且 kebab、kind/blocking/expect 枚举、artifact 唯一、
   scope/notes 非空、maven 行 artifact = `<id>.log`、shadowdiff 行必须 `-jar {{jar}}` 且写到 `{{report}}`。
2. `manifestCoversEveryScenarioThePlanRequires` — §5 要求的 10 行一个都不能消失。
3. `everyShadowdiffCommandParsesAsARealArgumentVector` — 见 §2。
4. `theNegativeControlStillDemandsDivergence` — 负控制双种子必须不同、`expect=exit-1`，且**只有**它可以期待非零。
5. `aiMatrixRowCoversTheFirstTenSlice` — 矩阵行必须 `--ai-all`，且 `AI_MONSTER_KINDS` 仍是 10 种（变了就要重写证据）。
6. `theRunnerIsDataDrivenByTheManifest` — 执行器读清单、支持三个占位符、不得硬编码场景。
7. `theEvidenceDoesNotClaimG4WhileTheMatrixStillHasGaps` — 只要 `g4-capability-matrix.tsv` 还有
   `unimplemented` 行，本文件就必须写明「未签发」。**这条让「提前宣布 G4 通过」变成一次红色构建。**

## 5. G4 缺口排序（本轮结论：**G4 未签发**）

依据 `docs/g4-capability-matrix.tsv` 当前状态（223 行 `unimplemented`：SKILL 168 + MERCHANT 23 +
GUILD 12 + SIEGE 14 + DEAL 6，另 CM/SM 共 190 行 `protocol-only`、3 行 `needs-client-check`）：

| 序 | 缺口 | 现状 | 下一刀 |
|---|---|---|---|
| 1 | 技能系统 | W28 只落了三类基础技能（SKILL 区 9 行 implemented），其余 168 行仍 `unimplemented` | 按 `Magic.pas` 逐项接入，每批独立测试 + shadowdiff 场景并入本清单 |
| 2 | Market_Def 事务（买卖/仓库/制药/升级/冠名） | W29 只放行 `@repair`/`@s_repair`/`@exit`，其余 `DEFERRED_TRANSACTION` 显式拒绝 | 先做库存/金币事务模型与失败回滚测试，再开放 `@buy`/`@sell` |
| 3 | NPC 脚本引擎 | 无解析器，回跳标签与 `@@sendmsg` 全未实现（已是可观测拒绝，不会假成功） | 需要独立设计，属于 P4 范围 |
| 4 | 行会 / 攻城 / 交易 | 26 行 `unimplemented`，无线上处理 | 本阶段红线，不做半成品 |
| 5 | 真实客户端 / Delphi 外部基线 | 3 条 SM `needs-client-check`；wiretool 已就绪但缺 Windows 环境实捕 | 一旦拿到 mir2.exe 环境：录制 golden → `replay` 字节级对拍 → shadowdiff `--left-host` 指向 Delphi |
| 6 | 怪物覆盖 | AI 矩阵只覆盖首批 10 种（共 57 种） | 按批扩展模板并同步扩 `AI_MONSTER_KINDS` |

**结论：** 本地 Java↔Java 门禁已具备可重复、可审计、可拒绝的形态，但 §5 第 1/2/5 项未达 w27-next-plan
的 G4 条件，因此本轮**不签发 G4**，仅签发「发布门禁基础设施 + 缺口排序」。

## 6. 复验记录（本沙箱限制）

本 Arena 沙箱仍无 JDK/Maven，且 Debian 源与 JDK 下载站点均不可达：

```text
$ mvn -v      -> /bin/bash: line 1: mvn: command not found
$ java -version -> /bin/bash: line 1: java: command not found
$ sudo apt-get update -> W: Failed to fetch http://deb.debian.org/... Connection failed
```

本地已完成的验证：

```text
$ bash -n java-server/scripts/g4-release-gate.sh      # 语法 OK
$ java-server/scripts/g4-release-gate.sh --list       # 10 行清单全部解析（见 §3 表）
```

因此本文件记录的是入库的门禁定义与命令；真实 PASS/FAIL 以 CI 的 `g4-release-gate` job
或具备 JDK 21 + Maven 3.9 的开发机执行 `scripts/g4-release-gate.sh` 为准，跑批汇总
（`g4-release-gate.md` + 各 `shadow-report.md`）作为 CI artifact 上传。

## 7. 下一项

W27 计划五项已全部落地（§1 矩阵 / §2 技能最小切片 / §3 NPC 边界 / §4 交互持久化回归 / §5 本轮门禁）。
下一阶段建议按 §5 缺口排序推进 **技能逐项接入（缺口 1）**：每批技能独立测试、独立证据，并把对应
shadowdiff 场景直接加入 `g4-release-gate.tsv`，让新玩法从第一天起就在发布门禁里。
