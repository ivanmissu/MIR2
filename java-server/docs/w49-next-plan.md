# W49 开发计划：`SKILL_EARTHFIRE`（火墙）+ 地图事件对象子系统（`TFireBurnEvent` 十字火墙）

延续 W28–W48 的按周技能迁移节奏：本周接 Magic.DB 中**最后一条可学技能 22 火墙**
（法师，`NeedL1=24`，`job=1`）。与前 21 个技能不同，火墙不是瞬时/延迟伤害，而是**引擎的
第一个地图事件对象子系统**：`MagMakeFireCross`（Magic.pas:1135）在目标格铺开十字形的
`TFireBurnEvent`（Event.pas:226），由 `g_EventManager`（Event.pas:68）按节拍驱动，周期性
灼烧站在火里的一切活物，并把事件的出现/消失通过 `SM_SHOWEVENT(804)`/`SM_HIDEEVENT(805)`
同步给视野内的玩家。这也是 W48 盘点里「需要地图事件/火墙对象子系统、本周不做」的那条技能。

W49 之后 Magic.DB 1–33 的可学技能全部落地；剩余 16 捆魔咒（陷阱子系统）/ 17 召唤骷髅（召唤物）/
20 诱惑之光（宠物）/ 21 瞬息移动（地图位移）/ 30 召唤神兽（召唤物）继续红线不动；34–41
（`SKILL_CROSSMOON`/`WINDTEBO`/`UENHANCER`/`ENERGYREPULSOR`/`TWINBLADE`/`GROUPDEDING`/
`UNAMYOUNSUL`/`ANGEL`）是引擎重编号扩展段，GEEM2 基线 Magic.DB 没有对应可学行，同样不动。

## 源码核验结论

### 1. 施法入口：`Magic.pas:501-510`（`SKILL_EARTHFIRE{22}` 分支）

```pascal
SKILL_EARTHFIRE{22}: begin  //火墙  00493B40
  if MagMakeFireCross(PlayObject,
                      PlayObject.GetAttackPower (GetPower(MPow(UserMagic)) + LoWord(PlayObject.m_WAbil.MC),
                      SmallInt(HiWord(PlayObject.m_WAbil.MC)-LoWord(PlayObject.m_WAbil.MC))+ 1),
                      GetPower(10) + (Word(GetRPow(PlayObject.m_WAbil.MC)) shr 1),  //火墙时间
                      nTargetX, nTargetY) > 0 then
    boTrain:=True;
end;
```

- 分支**不读 `TargeTBaseObject`**——与 抗拒火环/群体治愈术/爆裂火焰 同属「纯地面点击」施法：客户端可以对空地施法（targetId=0），`CretInNearXY`(ObjBase.pas:16854) 把 ±1 格内的命名对象吸附成施法坐标（引擎在各地面法术里已按此建模）。
- 两个实参表达式在进入 `MagMakeFireCross` **之前**求值（Delphi 参数从左到右求值），所以安全区拦截也会先掷威力与时长两次随机——Java 按同顺序掷，保持种子流对齐。
- `boSpellFire` 不被改动，`DoSpell` 尾部（Magic.pas:713-718）**无条件广播 `RM_MAGICFIRE`**——连安全区拦截的施法也照常播施法帧；魔法值在 `DoSpell` 进入分支前已扣（ObjBase.pas:21852，W48 已核验）。
- `boTrain` = `MagMakeFireCross(...) > 0`：安全区拦截返回 0 不训练；其余情况 `Result:=1` **无条件**（见 §2），重复铺火照常训练。

### 2. `MagMakeFireCross`（Magic.pas:1135-1170, 00492C9C）

- **安全区门**：`if g_Config.boDisableInSafeZoneFireCross and PlayObject.InSafeZone(PlayObject.m_PEnvir, nX, nY)` → `SysMsg('安全区不允许使用...', c_Red, t_Notice)`（Magic.pas:1140/1144，字符串原样含三个 ASCII 点）+ `exit`（Result=0）。配置默认 **False**（M2Share.pas:2070 `boDisableInSafeZoneFireCross: False`），即默认允许在安全区铺火。`InSafeZone(Envir, nX, nY)`（ObjBase.pas:21564）= 全图 `boSAFE` / 红名地图中心安全区 / `g_StartPoint` 安全区（引擎 `GameMap.isSafeZone(Position)` 已建模，W48 起用）。
- **十字五格**：依次 `(nX, nY-1)`、`(nX-1, nY)`、`(nX, nY)`、`(nX+1, nY)`、`(nX, nY+1)`，每格先查 `PlayObject.m_PEnvir.GetEvent(x, y) = nil` 才铺火。
  - `TEnvirnoment.GetEvent`（Envir.pas:1420）**不滤事件类型**——格子上有任何事件（火墙/矿/宝箱）即视为占用。本切片只铺火墙，所以「格子上已有火墙」等价。
  - `TFireBurnEvent.Create(Creat, x, y, ET_FIRE, nHTime * 1000, nDamage)` + `g_EventManager.AddEvent`。
  - `Result:=1` 写在函数尾部，**与铺成几格无关**——十字全被占用时仍返回 1（训练 + 已扣蓝）。
- **威力**（每跳伤害，整场火共享同一个值）：`GetAttackPower(GetPower(MPow(UserMagic)) + LoWord(MC), HiWord(MC)-LoWord(MC)+1)`——与 爆裂火焰/地狱雷光（Magic.pas:510/519）**完全同形**，直接复用引擎 `rollMcAttackPower`（W42 已核验：`GetPower(MPow)` = `MPow + Random(MaxPower-MPow)` 的 `rollExclusive` + `scalePower` + `rollExclusive(defPower, defMaxPower)`，`GetAttackPower` 的 `base + Random(spread+1)` 含幸运暴击）。
  - Magic.DB 行 22：`power=3, maxPower=3` → `MPow = 3`；`defPower=3, defMaxPower=3` → `GetPower(MPow)=rint(3/4*(lv+1))+3` = 4/5/5/6（lv0..3）。
- **时长**（秒，每格事件各自独立计时）：`GetPower(10) + (Word(GetRPow(MC)) shr 1)` → `nHTime * 1000` 毫秒。
  - `GetPower(10) = rint(10/4*(lv+1)) + 3` = 5/8/11/13 秒（lv0..3，行 22 的 `defPower=defMaxPower=3`）。
  - `GetRPow(MC)`（Magic.pas:73）= `LoMC..HiMC` 闭区间一roll（平坦区间不掷骰），`shr 1` = 无符号右移一位（整除 2）。
  - 引擎已有同形的 `random.between(Stream.MAGIC, minMc, maxMc)`（W46 `rollTransparentSeconds` 同款）。

### 3. `TFireBurnEvent` 与 `TEventManager`（Event.pas:18-100/226-258）

- `TEvent.Create`（Event.pas:265）：戳 `m_dwOpenStartTick`、`m_dwContinueTime = dwETime`、`m_boVisible=True` → `m_Envir.AddToMap(m_nX, m_nY, OS_EVENTOBJECT, Self)`——**事件可铺在墙上/ occupied 格**（Envir.pas:236 的 `OS_EVENTOBJECT` 分支为空，不查可走性）。
- `TFireBurnEvent.Create`（Event.pas:226）：`m_nDamage := nDamage`、`m_OwnBaseObject := Creat`。
- `TFireBurnEvent.Run`（Event.pas:239）：
  - 节拍门：`(GetTickCount - m_dwRunTick) > 3000`。注意 `TFireBurnEvent` **重新声明**了 `m_dwRunTick`（Event.pas:63）——派生字段从 0 开始（Delphi `NewInstance` 清零），所以**第一次被管理器调用就立即灼烧**，之后每 3000 ms 一跳。管理器侧 `TEventManager.Run`（Event.pas:102）按基类 `m_dwRunTick=500` 的门每 500 ms 喊一次 `Event.Run()`。
  - 目标：`m_Envir.GeTBaseObjects(m_nX, m_nY, True, BaseObjectList)`（Envir.pas:1377）= 该格 `OS_MOVINGOBJECT` 里**活着的**对象（玩家 + 怪物；boFlag=True 排除死亡；`bo2B9` 恒 true）。对每个满足 `m_OwnBaseObject.IsProperTarget(target)` 的目标：`target.SendMsg(m_OwnBaseObject, RM_MAGSTRUCK_MINE, 0, m_nDamage, 0, 0, '')`——挂到受害者自己队列里的自消息，由受害者的 `Operate` 消费。
- `TEvent.Run`（基类，Event.pas:277，在 `TFireBurnEvent.Run` 末尾 `inherited` 调用）：
  - `(GetTickCount - m_dwOpenStartTick) > m_dwContinueTime`（**严格大于**）→ `m_boClosed := True; Close()`——`Close` 把事件从格子上摘掉（`DeleteFromMap`），客户端感知是**下一次 `SearchViewRange` 扫不到**才收到 `RM_HIDEEVENT`。
  - `if (m_OwnBaseObject <> nil) and (m_OwnBaseObject.m_boGhost or m_OwnBaseObject.m_boDeath) then m_OwnBaseObject := nil;`——施法者死亡/成鬼后火墙**停止伤人但火焰照烧到时长结束**（视觉残留是原版行为）。

### 4. `RM_MAGSTRUCK_MINE`(8030) 的伤害链（ObjBase.pas:4501-4520，Grobal2.pas:1215）

- 与 `RM_MAGSTRUCK` **不同**：没有 `m_dwWalkTick + 800 + Random(1000)` 的怪物停顿（那段只挂在 `wIdent = RM_MAGSTRUCK` 上）。
- `nDamage := GetMagStruckDamage(nil, ProcessMsg.nParam1)`（ObjBase.pas:22441）：`n14 := LoWord(MAC) + Random(HiWord(MAC)-LoWord(MAC)+1)`（闭区间 MAC roll），`nDamage := max(0, raw - n14)`；`BaseObject=nil` 所以不死系加成不触发；命中 `m_boAbilMagBubbleDefence`（魔法盾）时 `nDamage := rint(nDamage/100 * (盾等级+2) * 8)` 且 `DamageBubbleDefence`——与引擎 `applyMagicShield`（W28 已核验）同形。
- `if nDamage > 0`：`StruckDamage`（黄毒 1.2x / 装备耐久 / `DamageHealth`）+ `HealthSpellChanged` + `SendRefMsg(RM_STRUCK_MAG, nDamage, HP, MaxHP, 攻击者id, '')`。
- `RM_STRUCK_MAG → SM_STRUCK(31)`（ObjBase.pas:5469-5520）：recog=受害者，param=HP，tag=MaxHP，series=伤害，body=`TMessageBodyWL{lParam1=feature, lParam2=charStatus, lTag1=攻击者id, lTag2=1}`——**lTag2=1 标记魔法命中**（引擎 `ObjectStruck.magical` 已有此位，W28 起用）。广播给受害者 + 视野内玩家（`SendRefMsg` 语义 = 引擎 `emitToObserversAndSelf`）。
- 击杀归属：`RM_STRUCK_MAG` 的自身分支里 `SetLastHiter(攻击者)`（ObjBase.pas:5484）——火墙杀死的怪物经验/掉落归施法者，走引擎现成的 `applyDamage → handleDeath` 链（W14 同步死亡约定）。

### 5. 走上火格的即时伤害：`TBaseObject.Walk`（ObjBase.pas:20169-20229）

- 任何移动对象（玩家**和怪物**共用 `Walk`）走完一步后，扫描**落点格**的 `ObjList`：`OS_EVENTOBJECT` 且 `m_OwnBaseObject <> nil` → `if Event.m_OwnBaseObject.IsProperTarget(Self)` → 立即 `SendMsg(Event.m_OwnBaseObject, RM_MAGSTRUCK_MINE, 0, Event.m_nDamage, 0, 0, '')`。
- 不阻挡走步（`Result` 不被改坏）、不伤施法者自己（`IsProperTarget` 排除自身）、与 3 秒周期伤害**并存**（踩上去先挨一下，站着每 3 秒再挨）。
- 传送落地不走 `Walk`（`EnterAnotherMap` 直接改坐标），所以瞬移/传送点踩火**不触发**这段。

### 6. 事件可见性：`SearchViewRange` + `RM_SHOWEVENT`/`RM_HIDEEVENT`（ObjBase.pas:19772/25503/25626/25764-25774/6259-6277）

- 每个玩家维护 `m_VisibleEvents` + `nVisibleFlag` 三态扫描（±`m_nViewRange`=12 的方形格子 sweep）：新进入 → `RM_SHOWEVENT(m_nEventType, Integer(MapEvent), MakeLong(m_nX, m_nEventParam), m_nY)`；扫不到 → `RM_HIDEEVENT(0, Integer(MapEvent), m_nX, m_nY)` 并从列表删除。`UpdateVisibleEvent`（ObjBase.pas:19772）还把 `m_nX/m_nY` 回写成格子坐标。
- 线格式（注意 `SendMsg(BaseObject, wIdent, wParam, nParam1..3, sMsg)` 的参数顺序与 `TDefaultMessage{Recog:Integer; Ident,Param,Tag,Series:Word}` 的 12 字节头）：
  - `SM_SHOWEVENT(804)`：recog=事件 id（指针），param=事件类型（`ET_FIRE=5`，Grobal2.pas:102），tag=`MakeLong(x, eventParam)` 的低字 = **x**，series=**y**，body=`TShortMessage{Ident=eventParam, wMsg=0}`（4 字节小端）。`TFireBurnEvent` 的 `m_nEventParam` 恒 0。
  - `SM_HIDEEVENT(805)`：recog=事件 id，param=0，tag=x，series=y，无 body。
- 客户端（ClMain.pas:4389-4401）：`TClEvent.Create(msg.Recog, Loword(msg.Tag){x}, msg.Series{y}, msg.Param{事件类型})`，`m_nEventParam := smsg.Ident`，`EventMan.AddEvent`；隐藏 `EventMan.DelEventById(msg.Recog)`。
- 玩家换图时 `m_VisibleEvents.Clear`（ObjBase.pas:4420，`EnterAnotherMap` 里）——新图靠后续 sweep 重建。

### 7. 边界与刻意偏离（明确不做 / 折叠）

- `CanSafeWalk`（Envir.pas:1169）：有伤害事件的格子拒绝 `bo2BA` 怪物的走步。`bo2BA` 只在刷怪时对**不在已接线十怪之列**的种类随机设置（`MONSTER_DIGOUTZOMBI` 1/2、`MONSTER_ZILKINZOMBI` 1/4、`MONSTER_COW` 1/2、`MONSTER_SCULTURE` 恒 true——UsrEngn.pas:1887-1904），已接线十怪 + 木桩没有 → **不建模**（57 怪红线）。
- `AddToMap` 的「每格 5 个 OS 对象上限拒绝」（Envir.pas:232）：Java 格子模型是单移动对象 + 独立侧列表，不存在该上限 → 不建模。
- 地图外十字臂：事件对象仍被创建（会走周期与到期，但 `AddToMap` 越界静默、`GeTBaseObjects` 越界空表），完全不可观测 → Java 直接不注册越界臂。
- 管理器 500 ms 门 / 首跳立即：Java 折叠为每 tick（50 ms）检查、`lastDamageAt=0` 首跳即烧（与 Delphi「创建后第一拍即烧」一致），时长到期严格大于（`now - createdAt > duration`）。
- 施法者死亡后的 owner 清空：Delphi 每 500 ms 清一次；Java 并入伤害节拍当拍清（W14 同步死亡约定），≤500 ms 窗口折叠。
- `SysMsg` 的 `t_Notice` 前缀（`boShowPreFixMsg`）：引擎 `SystemMessage` 全局约定不加前缀，沿用。
- `SearchViewRange` 是周期扫描（秒级延迟才发现事件出现/消失）；Java 改为事件驱动（创建/到期/移动/进图/传送点当场广播）——**可观测行为等价**，帧序按 Delphi 的「先施法帧、后事件帧」排列。
- 事件**不持久化**（Delphi `g_EventManager` 纯内存）——重启清空，与 Delphi 一致。
- 其余事件类型（`TStoneMineEvent` 挖矿 / `TPileStones` 宝箱 / `THolyCurtainEvent` / `OS_MAPEVENT` 攻城活动）**不在本切片**——火墙是事件子系统的第一个落地实例。

## Java 落地范围

1. **`world/FireWallEvent.java`**（新增）：`TFireBurnEvent` 模型——id、map、position、damage、ownerId、createdAt、durationMillis、lastDamageAt（0 = 尚未灼烧）。
2. **`world/WorldEvent.java`**：新增 `EventAppeared(eventId, eventType, position, eventParam)` 与 `EventDisappeared(eventId, position)` 两个事件记录。
3. **`world/WorldEngine.java`**：
   - 新增 `fireWalls`（LinkedHashMap）注册表、常量 `SKILL_EARTHFIRE=22`、`ET_FIRE=5`、`FIRE_WALL_TICK_MILLIS=3000`、`FIRE_CROSS_SAFE_ZONE_MESSAGE='安全区不允许使用...'`。
   - `castPlayerSpell`：白名单 + 地面点击豁免（不读 `TargeTBaseObject`，空地施法合法）+ 分发到 `castFireWall`。
   - `castFireWall`：`CretInNearXY` 吸附 → **先掷威力（`rollMcAttackPower`）与时长（`rollFireWallSeconds`）** → 安全区门（命中则系统提示 + `MagicFired`，不铺火不训练）→ 按 Delphi 顺序铺最多五格（跳过已有火墙的格子）→ `MagicFired` → 按格广播 `EventAppeared` → `trainSpellSkill`（`Result:=1` 无条件训练）。
   - `tickFireWalls`（接入 `runTickBody`）：owner 存活清空 → 3000 ms 灼烧门（格上活物 + `isProperTarget` → `applyFireWallDamage`）→ 严格大于到期 + 广播 `EventDisappeared`。
   - `applyFireWallDamage`：MAC 闭区间 roll（`MAGIC` 流）→ `applyMagicShield` → `applyDamage(magical=true)`（即 `SM_STRUCK` 的 lTag2=1 链；无停顿、无魔法躲避、无不死加成）。
   - `movePlayer`/`stepMonster`：落步后 `struckByFireWallOnStep`（Walk 踩火即时伤害，玩家怪物共用）。
   - `enter`/`teleportPlayer`：地图引导后补发视野内火墙的 `EventAppeared`；`movePlayer`：`emitFireWallVisibilityChanges` 差量广播（对齐 `emitItemVisibilityChanges`）。
4. **`gate/GameProtocolAdapter.java`**：`EventAppeared` → `SM_SHOWEVENT(804)`（recog=事件 id，param=事件类型，tag=x，series=y，body=`TShortMessage` 4 字节小端）；`EventDisappeared` → `SM_HIDEEVENT(805)`（recog=事件 id，param=0，tag=x，series=y）。
5. **配置**：`MIR2_DISABLE_FIRE_CROSS_IN_SAFE_ZONE`（默认 `false` = Delphi `boDisableInSafeZoneFireCross`）——`ServerConfig` 记录 + 兼容构造器 + `with` 复制器 + 环境变量解析；`WorldEngine.Config` 记录 + 兼容构造器；`Mir2Server` 接线；`deployment.md` 增行；`ServerConfigTest` 增例。
6. **能力矩阵**：`SKILL_EARTHFIRE:wizard`、`SM_SHOWEVENT`、`SM_HIDEEVENT` 三行翻转为 implemented（wire=gate-game，证据=新测试类）。
7. **测试**：新增 `WorldFireWallTest`（世界侧）+ `GameFireWallProtocolTest`（线格式侧）+ `ServerConfigTest` 扩展；本地全量绿后报告基线/增量数字。

## 明确不做

- 红线技能 16 捆魔咒 / 17 召唤骷髅 / 20 诱惑之光 / 21 瞬息移动 / 30 召唤神兽（陷阱/召唤/宠物/位移子系统）；
  34–41 引擎重编号扩展段（GEEM2 基线无可学行）。
- `CanSafeWalk`/`bo2BA` 怪物避火走位（涉及未接线怪物种类，57 怪红线）；`AddToMap` 每格 5 对象上限。
- 瞬移/传送落点踩火伤害（Delphi 传送不走 `Walk`）；除火墙外的一切事件类型（挖矿/宝箱/圣喻/攻城）。
- 事件持久化（Delphi 不落盘）；客户端 `TClEvent` 渲染细节（服务端只负责线格式）。
- 新增 Java↔Java shadowdiff 场景（对齐 W42–W48 的节奏，本切片不扩 `--skills`；火墙在 MANUAL 时钟 + 世界种子下完全可对拍，留作后继）。
- G4 仍未签发：Java 单测与 Java↔Java 对拍不等于 Delphi/真实客户端证据。

## 状态

- [x] Delphi 源码核验（Magic.pas:501-510 / 1135-1170 / 56-77；Event.pas:18-100 / 226-258 / 265-290；
      Envir.pas:236 / 1169 / 1377 / 1420；ObjBase.pas:4501-4520 / 5469-5520 / 19772 / 20169-20229 /
      21564 / 22441 / 25503-25774 / 6259-6277；UsrEngn.pas:1875-1910；Grobal2.pas:102 / 700 / 712 /
      1215 / 1259；M2Share.pas:2070；ClMain.pas:4389-4401；FunctionConfig.pas:967/1050/1224）
- [x] `world/FireWallEvent.java` + `WorldEvent` 新事件（`EventAppeared`/`EventDisappeared`，含 permits）+ `WorldEngine`（铺火/周期灼烧/踩火/可见性/安全区门）
- [x] `GameProtocolAdapter` 的 `SM_SHOWEVENT(804)`/`SM_HIDEEVENT(805)` 出站（含 `TShortMessage` body 编码）
- [x] `ServerConfig`/`WorldEngine.Config`/`Mir2Server`/`deployment.md` 配置接线（`MIR2_DISABLE_FIRE_CROSS_IN_SAFE_ZONE`，默认 false）+ `ServerConfigTest`
- [x] `g4-capability-matrix.tsv` 三行翻转（`SKILL_EARTHFIRE:wizard`、`SM_SHOWEVENT`、`SM_HIDEEVENT`）
- [x] `WorldFireWallTest`(13) + `GameFireWallProtocolTest`(4) 新增 + `ServerConfigTest`(+1)；既有 `WorldLinePiercingSkillTest` 的"未实现技能"对照组由 火墙(22) 更新为 瞬息移动(21)（W49 起 22 已实现）
- [x] 本地全量编译 + 单测全绿（ECJ + JUnit5 沙箱流水线：603 基线全绿，W49 增量后 **621/621 全绿**）
- [x] 红线核对：未碰召唤/陷阱/宠物/位移子系统（16/17/20/21/30 与 34-41 不动）、未扩怪物行为（`bo2BA`/`CanSafeWalk` 避火不建模）、未碰 NPC 脚本引擎；事件不持久化与 Delphi 一致
