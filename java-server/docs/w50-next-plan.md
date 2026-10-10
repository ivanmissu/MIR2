# W50 开发计划：`SKILL_SPACEMOVE`（瞬息移动）+ 地图位移子系统（`MapRandomMove`/`SpaceMove`）

延续 W28–W49 的按周技能迁移节奏：本周拆掉 Magic.DB 1–33 里**最后一条可学技能红线**——
21 瞬息移动（法师，`NeedL1=19`，`job=1`）。它是引擎的**第一个地图位移子系统**：
`MagSaceMove`（Magic.pas:951）掷一次成功率门，成功后先广播离场帧，再经
`TBaseObject.MapRandomMove`（ObjBase.pas:9810）→ `TBaseObject.SpaceMove`（ObjBase.pas:4359）
把施法者丢到**回城地图**上的一个随机可走格，并用 `SM_SPACEMOVE_HIDE2(806)`/
`SM_SPACEMOVE_SHOW2(807)` 这一对帧把「离场→换图→入场」同步给自己和视野内玩家。

W50 之后 Magic.DB 1–33 的可学技能**全部落地**（1–15、18/19、22–29、31–33 已在 W28–W49
完成，21 本周补齐）。剩余 16 困魔咒（陷阱子系统）/ 17 召唤骷髅（召唤物）/ 20 诱惑之光
（宠物）/ 30 召唤神兽（召唤物）继续红线不动；34–41
（`SKILL_CROSSMOON`/`WINDTEBO`/`UENHANCER`/`ENERGYREPULSOR`/`TWINBLADE`/`GROUPDEDING`/
`UNAMYOUNSUL`/`ANGEL`）是引擎重编号扩展段，GEEM2 基线 Magic.DB 没有对应可学行，同样不动。

## 源码核验结论

### 1. 施法入口：`Magic.pas:495-500`（`SKILL_SPACEMOVE{21}` 分支）

```pascal
SKILL_SPACEMOVE{21}: begin //瞬息移动 00493ADD
  PlayObject.SendRefMsg(RM_MAGICFIRE,0,MakeWord(UserMagic.MagicInfo.btEffectType,UserMagic.MagicInfo.btEffect),MakeLong(nTargetX,nTargetY),Integer(TargeTBaseObject),'');
  boSpellFire:=False;
  if MagSaceMove(PlayObject,UserMagic.btLevel) then
    boTrain:=True;
end;
```

- 与 W49 的火墙同属「纯地面点击」施法：分支**不读 `TargeTBaseObject` 的状态**，只把它当
  整数塞进 `RM_MAGICFIRE` 的 `nParam3`，所以空地施法（targetId=0）合法。
- **本分支自己播 `RM_MAGICFIRE` 并把 `boSpellFire` 置 False**——这是 1–33 里唯一一个这么写的
  分支：`DoSpell` 尾部（Magic.pas:713-718）的通用广播被关掉，施法帧因此**在位移之前**发出，
  而不是像其它技能那样在分支跑完之后。打空地、成功率失败都照样烧蓝、照样播施法帧。
- `boSpellFail` 在本分支不被置位 → `DoSpell` 返回 True（`+GOOD`）；只有 `boTrain` 反映成败。
- 魔法值在进入 `case` 前已扣（`DoSpell` 头部之前，ObjBase.pas:21852，W48 已核验）；门外的
  范围检查 `abs(dx) > g_Config.nMagicAttackRage` 同样先于扣蓝（Magic.pas:261）。
- Magic.DB 行 21（`db/MagicDb.tsv`）：`effectType=4, effect=19, spell=10, defSpell=8,
  job=1(wizard), NeedL1=19, Delay=50`。`GetSpellPoint` =
  `Round(10/4*(lv+1)) + 8` → **10/13/16/18**（lv0..3）。

### 2. `MagSaceMove`（Magic.pas:951-971, 004927D8）

```pascal
Result:=False;
if Random(11) < nLevel * 2 + 4 then begin
  BaseObject.SendRefMsg(RM_SPACEMOVE_FIRE2,0,0,0,0,'');
  if BaseObject is TPlayObject then begin
    Envir:=BaseObject.m_PEnvir;
    BaseObject.MapRandomMove(BaseObject.m_sHomeMap,1);
    if (Envir <> BaseObject.m_PEnvir) and (BaseObject.m_btRaceServer = RC_PLAYOBJECT) then
      PlayObject.m_boTimeRecall:=False;
  end;
  Result:=True;
end;
```

- **成功率门 `Random(11) < btLevel * 2 + 4`**：lv0..3 = 4/11、6/11、8/11、10/11。失败时
  除了已发的施法帧外**什么都没有**——不训练、不移动、不退蓝。
- **目标地图是 `m_sHomeMap`（回城地图）**，不是当前地图。`m_sHomeMap` 由 `GetHomePoint`
  （ObjBase.pas:9885）在靠近 `g_StartPointList` 里某个出生点时改写，红名（PKLevel ≥ 2）时改为
  `g_Config.sRedHomeMap`，并随角色记录持久化（HumData.sHomeMap，ObjBase.pas:24901 /
  UsrEngn.pas:2329）。本引擎没有这套持久化，见 §6 的落地口径。
- `Result := True` 写在 `if` 块**尾部的括号里**：门过了就无条件训练，哪怕
  `MapRandomMove` 因为「地图不存在」或「201 步没找到可走格」实际上没动。
- `nInt = 1`：这个实参一路传到 `SpaceMove`，决定收尾帧是 `RM_SPACEMOVE_SHOW2` 而不是
  `RM_SPACEMOVE_SHOW`（ObjBase.pas:4431-4433）。

### 3. `MapRandomMove`（ObjBase.pas:9810-9830）

```pascal
oEnvir := m_PEnvir;
Envir := g_MapManager.FindMap(sMapName);
if Envir <> nil then begin
  if Envir.Header.wHeight < 150 then begin
    if Envir.Header.wHeight < 30 then nEgdey := 2 else nEgdey := 20;
  end else nEgdey := 50;
  nX := Random(Envir.Header.wWidth  - nEgdey - 1) + nEgdey;
  nY := Random(Envir.Header.wHeight - nEgdey - 1) + nEgdey;
  SpaceMove(sMapName, nX, nY, nInt);
end;
```

- 找图失败（地图没加载）→ 整段跳过（仍然训练）。
- 边距 `nEgdey` 只由**地图高度**决定，x/y 两个方向共用；随机区间是**左闭右开**的
  `Random(w - edge - 1) + edge`。

### 4. `SpaceMove`（ObjBase.pas:4359-4461）

- **同服务器分支**（`nServerIndex = Envir.nServerIndex`）：单服务器部署下所有地图共享同一个
  server index，所以**同图与跨图走的是同一段代码**（跨服务器分支只用于多机部署，Java 单机
  引擎永不进入）。
  1. `DeleteFromMap` 摘掉移动对象 → 清空 `m_VisibleHumanList` / `m_VisibleItems` /
     `m_VisibleActors` / `m_VisibleEvents`；
  2. 写入新地图与新坐标；
  3. `GetRandXY`（ObjBase.pas:4360-4385）找可走格——**最多 201 次**判定：

     ```pascal
     if Envir.Header.wWidth < 80 then n18 := 3 else n18 := 10;      // 步进
     if Envir.Header.wHeight < 150 then begin
       if Envir.Header.wHeight < 50 then n1C := 2 else n1C := 15;   // 边距
     end else n1C := 50;
     n14 := 0;
     while True do begin
       if Envir.CanWalk(nX, nY, True) then begin Result := True; Break; end;
       if nX < (Envir.Header.wWidth - n1C - 1) then Inc(nX, n18)
       else begin
         nX := Random(Envir.Header.wWidth);
         if nY < (Envir.Header.wHeight - n1C - 1) then Inc(nY, n18)
         else nY := Random(Envir.Header.wHeight);
       end;
       Inc(n14);
       if n14 >= 201 then Break;
     end;
     ```

     `CanWalk(x, y, True)` 的第三个参数 `boFlag = True` 表示**忽略格上对象**（Envir.pas:392-427），
     所以只判地形；Delphi 允许瞬移落点与另一个角色重合。
  4. 成功：`AddToMap` → `SendMsg(Self, RM_CLEAROBJECTS)` → `SendMsg(Self, RM_CHANGEMAP, ...,
     m_sMapName)` → `SendRefMsg(RM_SPACEMOVE_SHOW2, m_btDirection, m_nCurrX, m_nCurrY, 0, '')`
     → `m_dwMapMoveTick := GetTickCount; m_bo316 := True`。
  5. 失败（201 步耗尽）：把旧地图/旧坐标写回并 `AddToMap`，**不发任何帧**（离场帧已经在
     `MagSaceMove` 里发出去了），仍然训练。

### 5. 线格式（ObjBase.pas:6206-6256；ClMain.pas:4549-4575）

| Delphi RM | SM | 编码 |
|---|---|---|
| `RM_SPACEMOVE_FIRE2`(8042) | `SM_SPACEMOVE_HIDE2`(806) | recog = 施法者对象，param/tag/series = 0，无 body |
| `RM_SPACEMOVE_SHOW2`(8099) | `SM_SPACEMOVE_SHOW2`(807) | recog = 对象，param = x，tag = y，series = `MakeWord(direction, m_nLight)`，body = `TCharDesc{feature, status}` |
| `RM_CLEAROBJECTS` | `SM_CLEAROBJECTS` | 仅自己（既有 `PlayerMapChanged` 已建模） |
| `RM_CHANGEMAP` | `SM_CHANGEMAP` | 仅自己（同上） |

- 800/801（`SM_SPACEMOVE_HIDE`/`SHOW`）由 `RM_SPACEMOVE_FIRE`/`RM_SPACEMOVE_SHOW` 产生，
  是 NPC 传送/其它调用点（`nInt = 0`）用的**另一对**编号；瞬息移动用不到，本周不翻转。
- `RM_SPACEMOVE_SHOW2` 的 body 只在 `sMsg <> ''` 时才追加「名字/颜色」串（ObjBase.pas:6247），
  而 `SendRefMsg(..., '')` 传的是空串 → **body 只有 `TCharDesc`**（与引擎既有的
  `sendObjectAction` 完全一致，可直接复用）。
- `SendRefMsg`（ObjBase.pas:19590）把帧投递给 ±12 方块内的**其它玩家以及自己**（自己也在自己
  格子的 `ObjList` 里），对应引擎的 `emitToObserversAndSelf`。
- 客户端 `SM_SPACEMOVE_HIDE2` 对 `g_MySelf` 静默（ClMain.pas:4552-4554），只让旁观者把角色藏起来；
  `SM_SPACEMOVE_SHOW2` 对旁观者 `NewActor` 并转发给 `PlayScene`。

### 6. 边界与刻意偏离（明确不做 / 折叠）

- **`m_sHomeMap` 的落地口径**：Java 侧角色记录不持久化回城地图，玩家每次登录都进
  `MIR2_MAP_ID` 配置的出生图（`LegacyGateHandler` → `enterPlayerNear(world.mapId(), ...)`）。
  因此 `m_sHomeMap` 落地为**玩家进图时所在的那张图**（`enter()` 里盖章），与 Delphi 在
  「回城地图 = 出生图」这一常见配置下逐字等价；跨图后施放瞬息移动会把人送回登录图，这正是
  Delphi 的行为。红名改投 `sRedHomeMap`（ObjBase.pas:9910）不建模。
- **`m_boTimeRecall := False`**（记忆套装/时空门回城标记）不建模——引擎没有回城子系统。
- **`m_dwMapMoveTick` / `m_bo316`**（防加速的移动时间闸）不建模——引擎不做移动校验。
- **`CanWalk(..., True)` 忽略对象 vs Java 单占格模型**：Delphi 允许落点与另一个角色重合，
  Java 每格只存一个移动对象。落地时 `GetRandXY` 的判定改为「地形可走 **且** 格上无对象」，
  步进规则与 201 步上限逐字照搬；只要地图上不存在「地形可走但被占用」的格子（PoC 空图、
  已接线十怪的常规图都是如此），随机流与落点与 Delphi 逐字一致。
- `Random(负数)`：Delphi 返回 0；Java 的 `nextInt(bound)` 要求 bound ≥ 1，落地时按
  `Math.max(1, ...)` 夹紧（等价于 Delphi 的 0），只在 `w - edge - 1 <= 0` 的畸形小图上才生效。
- 瞬移落点**不触发踩火伤害**（Delphi 的 `SpaceMove` 不走 `TBaseObject.Walk`，W49 §5 已核验）。
- 跨服务器分支（`DisappearA` + 重连换图，ObjBase.pas:4452-4461）不建模——Java 单机单服。
- 其余调用点（NPC 传送、`@move` GM 命令、攻城回城 `Castle.pas:882`、怪物
  `ObjMon3.pas` 的瞬移 AI）走的是 `RM_SPACEMOVE_FIRE`/`MapRandomMove(..., 0)`，**不在本切片**。
- 其余红线技能 16/17/20/30 与 34–41 扩展段不动；不扩 57 怪行为；不碰 NPC 脚本引擎。
- 本切片不新增 Java↔Java shadowdiff 场景（对齐 W42–W49 的节奏，不扩 `--skills`）。
- G4 仍未签发：Java 单测不等于 Delphi/真实客户端证据。

## Java 落地范围

1. **`WorldRandom`**：追加 `SPACE_MOVE` 流（末尾追加，既有种子流水不挪窝）——
   `Random(11)` 成功率门 + `MapRandomMove` 的两次 `Random(w - edge - 1)` +
   `GetRandXY` 越界时的 `Random(wWidth)`/`Random(wHeight)`。
2. **`WorldEvent`**：新增 `SpaceMoveHidden(objectId)`（→ `SM_SPACEMOVE_HIDE2`）与
   `SpaceMoveShown(object)`（→ `SM_SPACEMOVE_SHOW2`）两个事件记录（含 permits）。
3. **`WorldEngine`**：
   - 常量 `SKILL_SPACEMOVE = 21`、`SPACE_MOVE_GATE_BOUND = 11`、`GET_RAND_XY_ATTEMPTS = 201`。
   - `Player` 新增 `homeMapId`（`m_sHomeMap`），`enter()` 里盖章为进图地图（含 `noReconnect`
     重定向之后的图）。
   - `castPlayerSpell`：白名单 + 地面点击豁免（不读 `TargeTBaseObject`）→ 分发到 `castSpaceMove`。
   - `castSpaceMove`：先播 `MagicFired` → `Random(11) < level*2+4` 门（失败即返回，已扣蓝、
     已播帧、不训练）→ 广播 `SpaceMoveHidden` → `MapRandomMove(homeMap, 1)` 的两段实现
     （`mapRandomMoveStart` 起跳点 + `randomMapCell` 的 201 步搜索；落点为空则原地不动）
     → 落点非空则 `relocatePlayer` → 无条件 `trainSpellSkill`。
   - `relocatePlayer`（与既有 `teleportPlayer` 同形，但帧序按 `SpaceMove`）：
     摘格/放格 → 自己收 `PlayerMapChanged`（`SM_CLEAROBJECTS` + `SM_CHANGEMAP` +
     `SM_MAPDESCRIPTION`）→ 旧格观察者收 `ObjectDisappeared` → 全场收 `SpaceMoveShown`
     → 新格观察者收 `ObjectAppeared` → 自己补发视野内的对象/地面物品/火墙。
4. **`gate/GameProtocolAdapter`**：`SpaceMoveHidden` → `SM_SPACEMOVE_HIDE2(806)`
   （recog = 对象，其余 0，无 body）；`SpaceMoveShown` → 复用 `sendObjectAction` 发
   `SM_SPACEMOVE_SHOW2(807)`（recog = 对象，param = x，tag = y，series = `MakeWord(dir, light)`，
   body = `TCharDesc`）。
5. **配置**：无新增环境变量（`MagSaceMove` 与 `SpaceMove` 在 Delphi 侧没有可配项）。
6. **能力矩阵**：`SKILL_SPACEMOVE:wizard`、`SM_SPACEMOVE_HIDE2`、`SM_SPACEMOVE_SHOW2`
   三行翻转为 implemented（wire=gate-game，证据=新测试类）；
   `SM_SPACEMOVE_HIDE(800)`/`SM_SPACEMOVE_SHOW(801)` 保持 protocol-only（本周不产生）。
7. **测试**：新增 `WorldSpaceMoveTest`（世界侧）+ `GameSpaceMoveProtocolTest`（线格式侧）；
   既有 `WorldLinePiercingSkillTest` 的「未实现技能」对照组由 瞬息移动(21) 更新为
   诱惑之光(20)（W50 起 21 已实现）；本地全量绿后报告基线/增量数字。

## 明确不做

- 红线技能 16 困魔咒 / 17 召唤骷髅 / 20 诱惑之光 / 30 召唤神兽（陷阱/召唤/宠物子系统）；
  34–41 引擎重编号扩展段（GEEM2 基线无可学行）。
- 800/801 这一对帧（NPC 传送等 `nInt = 0` 调用点）、`m_boTimeRecall`、`m_dwMapMoveTick` /
  `m_bo316` 防加速闸、跨服务器换图重连、`sRedHomeMap` 红名回城重定向。
- 瞬移落点的踩火伤害（Delphi 不走 `Walk`）；落点与角色重合（Java 单占格模型）。
- 新增 Java↔Java shadowdiff 场景与 G4 签发（本地单测不构成 Delphi/客户端证据）。

## 状态

- [x] Delphi 源码核验（Magic.pas:260-262 / 495-500 / 951-971；ObjBase.pas:4359-4461 / 9810-9830 /
      9885-9915 / 6206-6256 / 19590-19720 / 24901；Envir.pas:392-427；Grobal2.pas:388-395 /
      1221-1244；UsrEngn.pas:2329；ClMain.pas:4549-4575）
- [x] 计划文档（本文档）
- [x] `WorldRandom.SPACE_MOVE` + `WorldEvent` 两个新事件（`SpaceMoveHidden`/`SpaceMoveShown`，含 permits）
- [x] `WorldEngine`（`homeMapId` / `castSpaceMove` / `mapRandomMoveStart` / `randomMapCell` /
      `relocatePlayer` 接线）
- [x] `GameProtocolAdapter` 的 `SM_SPACEMOVE_HIDE2(806)`/`SM_SPACEMOVE_SHOW2(807)` 出站
- [x] `g4-capability-matrix.tsv` 三行翻转（`SKILL_SPACEMOVE:wizard` → implemented；806/807 →
      implemented；800/801 因只用于 `nInt = 0` 调用点保持 protocol-only）+ warrior/taoist 两行
      补上 WRONG_JOB 说明
- [x] `translation-map.md` 的 `DoSpell` 行更新到 W50 的技能覆盖面
- [x] 测试：`WorldSpaceMoveTest`(12) + `GameSpaceMoveProtocolTest`(3) 新增；
      `WorldLinePiercingSkillTest` 的「未实现技能」对照组由 瞬息移动(21) 更新为 诱惑之光(20)
- [x] 本地全量编译 + 单测全绿（沙箱流水线：ECJ 3.45 + JDK 21 语言级 + JUnit5 兼容层；
      基线 **591/591** 全绿，W50 增量后 **606/606** 全绿；另有 30 个 SQLite 驱动的用例
      因沙箱无法访问 Maven Central 而**环境性跳过**，CI 上照常执行）
- [x] 红线核对：未碰陷阱/召唤/宠物子系统（16/17/20/30 与 34-41 不动）、未扩怪物行为
      （`bo2BA`/`CanSafeWalk` 等）、未碰 NPC 脚本引擎、未新增环境变量
