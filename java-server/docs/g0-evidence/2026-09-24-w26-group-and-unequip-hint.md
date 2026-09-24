# G0 研发证据：W26 组队管理（CM/SM 完整协议 + 组队经验加成与分摊）+ 装备取下拒绝提示

**日期：** 2026-09-24  
**对应阶段：** P3 / W26  
**状态：** ✅ 全量通过（371/371 测试全绿，+9 新增/扩展测试用例）

---

## 1. 交付目标与完成情况

| 需求项 | 模块/组件 | 状态 | 验证方式 |
|---|---|---|---|
| 组队模型与上限 | `world/PlayerGroup.java`（上限 12 人，队长管理，成员管理） | ✅ 完成 | `WorldGroupTest` 单元与集成测试 |
| 组队事件与生命周期 | `world/WorldEvent.java` + `world/WorldEngine.java`（离线/死亡解散/退队） | ✅ 完成 | `WorldGroupTest` |
| 组队经验加成与按等级分摊 | `world/WorldEngine.java`（12 格同图范围判定、20% 每人递增、按等级权重分摊） | ✅ 完成 | `WorldGroupExpTest` |
| 客户端组队协议全链路 | `gate/GameProtocolAdapter.java`（CM 658-661 解码，SM 658-666 编码） | ✅ 完成 | `GameGroupProtocolTest` |
| 装备不可取下拒绝提示 | `gate/GameProtocolAdapter.java`（SM_SYSMESSAGE `"无法取下物品"`） | ✅ 完成 | `GameItemProtocolTest` |

---

## 2. 协议与实现细节

### 2.1 组队操作与状态机（ObjBase.pas / M2Share.pas）
- **允许组队开关 (`CM_GROUPMODE` -> 658)**：
  - 玩家开启/关闭组队允许状态（`allowGroup`）；若关闭且当前处于队伍中，自动退队。
  - 回复 `SM_GROUPMODECHANGED`。
- **创建队伍 (`CM_CREATEGROUP` -> 659)**：
  - 发起人作为队长，邀请目标玩家；目标未开启组队模式则拒绝（reason `-4`）。
  - 若发起人已在队伍中则拒绝（`-1`），目标不存在（`-2`），目标已在队伍中（`-3`）。
  - 成功发送 `SM_CREATEGROUP_OK` 与 `SM_GROUPMEMBERSCHANGED`，失败发送 `SM_CREATEGROUP_FAIL`（含 reason 码）。
- **添加队员 (`CM_ADDGROUPMEMBER` -> 660)**：
  - 仅队长可邀请（非队长返回 `-1`）；队伍上限 12 人（满员返回 `-5`）。
  - 成功向全队广播 `SM_ADDGROUPMEMBER_OK` 与最新成员列表 `SM_GROUPMEMBERSCHANGED`。
- **移出队员/退队 (`CM_DELGROUPMEMBER` -> 661)**：
  - 队长可踢出队员，队员可移出自己（退队）。若队伍仅剩 1 人则自动解散，向剩余成员发送 `SM_GROUPCANCEL`。
- **离线与死亡清理**：
  - 队员掉线（`leavePlayer`）或死亡（`killPlayer`）时，自动移出队伍；若队长离线或解散，向队员发送 `SM_GROUPCANCEL`。

### 2.2 组队打怪经验分摊模型
- 当怪物被击杀时，检查击杀者是否在队伍中：
  - 统计同地图且距离在 12 格以内（`max(|dx|, |dy|) <= 12`）的存活队员数 $N$。
  - 当 $N \ge 2$ 时：
    - 组队经验池加成：$TotalExp = BaseExp \times (1 + (N - 1) \times 0.20)$
    - 队员按等级比例分摊：$MemberExp = \mathrm{round}\left(TotalExp \times \frac{Level_{member}}{\sum Level}\right)$
    - 逐个发放经验并发送 `SM_WINEXP`。
  - 当 $N = 1$（队员均超出 12 格或单人）时，击杀者独享基础经验。

### 2.3 取下装备拒绝提示
- 在 `GameProtocolAdapter` 处理 `TakeOffResult.CANNOT_TAKE_OFF`（如受 `DisableTakeOffList` 限制物品）时，发送 `SM_SYSMESSAGE` 提示 `"无法取下物品"`，完成 W22 记录的协议回包闭环。

---

## 3. 测试验证结果

全工程 10 个模块编译及自动化测试全量通过：
```
--- Module: protocol (1 classes, 0.51s) ---
   [         5 tests found           ]
   [         5 tests successful      ]
   [         0 tests failed          ]
--- Module: auth (1 classes, 0.48s) ---
   [         2 tests found           ]
   [         2 tests successful      ]
   [         0 tests failed          ]
--- Module: character (1 classes, 0.48s) ---
   [         3 tests found           ]
   [         3 tests successful      ]
   [         0 tests failed          ]
--- Module: world (30 classes, 1.74s) ---
   [       169 tests found           ]
   [       169 tests successful      ]
   [         0 tests failed          ]
--- Module: persistence (2 classes, 9.67s) ---
   [        11 tests found           ]
   [        11 tests successful      ]
   [         0 tests failed          ]
--- Module: gate (18 classes, 3.32s) ---
   [        93 tests found           ]
   [        93 tests successful      ]
   [         0 tests failed          ]
--- Module: bootstrap (2 classes, 2.01s) ---
   [        17 tests found           ]
   [        17 tests successful      ]
   [         0 tests failed          ]
--- Module: loadtest (3 classes, 15.51s) ---
   [         8 tests found           ]
   [         8 tests successful      ]
   [         0 tests failed          ]
--- Module: wiretool (7 classes, 4.18s) ---
   [        35 tests found           ]
   [        35 tests successful      ]
   [         0 tests failed          ]
--- Module: shadowdiff (5 classes, 102.55s) ---
   [        28 tests found           ]
   [        28 tests successful      ]
   [         0 tests failed          ]

==================================================
TOTAL TEST RESULTS: 371/371 PASSED (FAILED: 0)
==================================================
```

---

## 4. 红线与架构边界合规性核对

1. **红线核对：**
   - 技能/魔法系统：未触碰（无技能代码引入）。
   - NPC 脚本引擎：未触碰（无脚本解释器引入）。
   - 57 种怪物行为扩展：未触碰（保持原有 10 种怪与木桩行为不变）。
2. **确定性与纯度：**
   - 组队经验分摊完全基于整数/浮点确定性计算，不消耗随机数流，不扰动确定性测试序列。
   - 所有世界状态修改均在世界线程队列执行，线程安全且行为可复现。
