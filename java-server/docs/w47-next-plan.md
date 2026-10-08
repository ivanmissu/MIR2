# W47 开发计划：`SKILL_CLOAK`（隐身术）+ `SKILL_BIGCLOAK`（集体隐身术）

延续 W37（护身符充能门）、W40（群体治愈术）、W44/W45（冰咆哮 / 抗拒火环）、W46（幽灵盾 / 神圣战甲术）
的按周技能迁移节奏：本周接 **Magic.DB 18/19 两条道士隐身系**（`SKILL_CLOAK` = 18 隐身术、
`SKILL_BIGCLOAK` = 19 集体隐身术），二者是 `Magic.pas` 护身符门块 `SKILL_FIRECHARM{13} .. SKILL_BIGCLOAK{19}`
里最后两条可独立落地的分支（16 捆魔咒 / 17 召唤骷髅仍被红线挡住：一个需要陷阱子系统、一个需要召唤物）。

这也是引擎第一次引入 **`m_boHideMode` / `m_boTransparent` / `m_wStatusTimeArr[STATE_TRANSPARENT]`（0x70）**
这套"隐身状态"：它同时改变**怪物索敌**（`not m_boHideMode or m_boCoolEye`）与**客户端渲染**
（`charStatus` 位 8 = `0x00800000` 半透明），并首次让 `SM_CHARSTATUSCHANGED(657)` 真正上线
（矩阵 row 224 从 protocol-only 转 implemented）。

## 源码核验结论

### 1. 施法入口：与 13..19 共用护身符门

`M2Server/Magic.pas:420-497`：

```pascal
420: SKILL_FIRECHARM{13}, SKILL_HANGMAJINBUB{14}, SKILL_DEJIWONHO{15},
     SKILL_HOLYSHIELD{16}, SKILL_SKELLETON{17}, SKILL_CLOAK{18}, SKILL_BIGCLOAK{19}: begin
       boSpellFail := True;
       if CheckAmulet(PlayObject, 1, 1, nAmuletIdx) then begin
         UseAmulet(PlayObject, 1, 1, nAmuletIdx);
         case UserMagic.MagicInfo.wMagicId of
           ...
476:       SKILL_CLOAK{18}: begin        // 隐身术 004943DF
             if MagMakePrivateTransparent(PlayObject, GetPower13(30) + GetRPow(PlayObject.m_WAbil.SC) * 3) then
               boTrain := True;
           end;
481:       SKILL_BIGCLOAK{19}: begin     // 集体隐身术
             if MagMakeGroupTransparent(PlayObject, nTargetX, nTargetY,
                                        GetPower13(30) + GetRPow(PlayObject.m_WAbil.SC) * 3) then
               boTrain := True;
           end;
```

- `nType = 1` 护身符（Shape 5，每次 100 点 Dura），与 W46 完全同门：
  **无符 → `boSpellFail = True` → 只发 `RM_MAGICFIREFAIL`**（MP 已扣不退、施法姿态已播）。
- 护身符在 `case` **之前**扣除 ⇒ **已隐身时再施隐身术照样烧一张符**（见 quirk 1）。
- 时长公式 `GetPower13(30) + GetRPow(SC) * 3`（单位：秒）：
  - `GetPower13(30)`（`Magic.pas:238`，与 unit 级 `:64` 同式）在技能 0/1/2/3 级给出 **15/20/25/30**
    （排除了 `DefPower/DefMaxPower`，Magic.DB 18/19 两行全 0）。
  - `GetRPow(SC)`（`Magic.pas:73`）= `HiWord(SC) > LoWord(SC) ? Random(Hi-Lo+1) + Lo : Lo`，
    取一次 `[minSC, maxSC]` 之间的随机道术再 **×3**。
  - 上界远小于状态秒表的 60000 冻结门限（`m_wStatusTimeArr[i] < 60000` 才倒数），所以隐身术必然自然到期。

### 2. `MagMakePrivateTransparent`（Magic.pas:734，004930E8）

```pascal
741: if BaseObject.m_wStatusTimeArr[STATE_TRANSPARENT] > 0 then exit;   // 已隐身：直接 False，不刷新
745: BaseObject.GetMapBaseObjects(envir, x, y, 9, list);                 // ±9 格（19x19）
747:   if (TargeTBaseObject.m_btRaceServer >= RC_ANIMAL) and (TargeTBaseObject.m_TargetCret = BaseObject) then
750:     if (abs(dx) > 1) or (abs(dy) > 1) or (Random(2) = 0) then
751:       TargeTBaseObject.m_TargetCret := nil;                          // 清仇恨
755: BaseObject.m_wStatusTimeArr[STATE_TRANSPARENT] := nHTime;
756: BaseObject.m_nCharStatus := BaseObject.GetCharStatus();
757: BaseObject.StatusChanged();
758: BaseObject.m_boHideMode := True;
759: BaseObject.m_boTransparent := True;
```

- **只清怪物的目标**（race ≥ `RC_ANIMAL`），玩家/NPC 不在内；**贴身（|dx|≤1 且 |dy|≤1）的怪只有 50% 概率丢目标**，
  稍远的必丢。9 格外的怪**不会**被清（会继续追，与原文一致）。
- 三个状态赋值都在"已隐身早退"之后，所以重复施放**不会**刷新计时、不会重发状态帧。
- 私有隐身**不广播任何 RM_TRANSPARENT**；可见性变化完全靠 `StatusChanged()` 的
  `RM_CHARSTATUSCHANGED`（见 §5）。

### 3. `MagMakeGroupTransparent`（Magic.pas:1286，0049320C）

```pascal
1292: BaseObject.GetMapBaseObjects(envir, nX, nY, 1, list);              // 点击格 ±1（3x3）
1294:   if BaseObject.IsProperFriend(TargeTBaseObject) then
1299:     if TargeTBaseObject.m_wStatusTimeArr[STATE_TRANSPARENT] = 0 then begin
1300:       TargeTBaseObject.SendDelayMsg(TargeTBaseObject, RM_TRANSPARENT, 0, nHTime, 0, 0, '', 800);
1301:       Result := True;
```

- **以点击点为中心**（不是施法者），只选**计时为 0** 的友方；每个目标给自己排一条 **800 ms 延迟**的
  `RM_TRANSPARENT`，由接收方在 `ObjBase.pas:4619` 调
  `MagMakePrivateTransparent(Self, ProcessMsg.nParam1)` ——即 §2 的完整私有隐身（含它自己的清仇恨扫）。
- 友方判定用**施法者**的 `IsProperFriend`：本引擎沿用 W40/W46 的收窄模型（`HAM_GROUP`：本人 + 在线队友）。
- `Result` = 至少一个目标被排上 ⇒ 决定 `boTrain`。**被判为友但 800 ms 内自己已隐身的目标不会重复上榜**
  （投递时 §2 的 `> 0` 早退再次拦截），但**不查询 800 ms 后是否仍是队友**（Delphi 不复查，Java 保留）。

### 4. 计时与破隐（`ObjBase.pas`）

- `4156-4180` 状态秒表：只有 `0 < v < 60000` 才每秒 -1；`STATE_TRANSPARENT(8)` 归零时
  `m_boHideMode := False`，`boChg := True` → `4236-4240` 重算 `m_nCharStatus` 并发 `StatusChanged()`。
  （≥60000 永不倒数 —— 那是**隐身戒指** `2965/3266` `:= 6*10*1000` 的"永久隐身"通道，
  GEEM2 基线里没有该物品的引擎侧消费者，本切片不含戒指模型，仅保留注释。）
- 破隐（把剩余秒数压到 1，下一个整秒即结束，**不是立即**）：
  - `2061` 走一格成功后：`if m_boTransparent and m_boHideMode then m_wStatusTimeArr[8] := 1`
    （`8947` 跑、`9560` 骑马跑同句）。
  - 每次迈步都重置为 1 ⇒ 连续走动的隐身角色**只要脚步快于 1 秒就仍保持隐身**（原文 quirk）。
  - 攻击/受击**不破隐**（全 M2Server 里 `STATE_TRANSPARENT` 仅上述这些写入点 + Magic.pas + ObjMon 毒蛾）。
- `15473 TPlayObject.Disappear`：下线/离图时若正在隐身，把计时清 0。
- `3364-3383 RecalcAbilitys`：先暂存并清 `m_boHideMode`，`m_boTransparent and 计时>0` 时恢复；
  恢复失败且旧值为 True 才补 `StatusChanged()`。Java 端只在计时过期时清理，不引入额外广播。
- `ObjMon.pas:1586-1588`（**TGasMothMonster 专属**，毒蛾）与 `ObjMon3.pas:1233 TFrostTiger` 自隐
  都是特定怪种行为，本引擎未接这两种怪，记录为边界。

### 5. 怪物索敌与协议

- 索敌 gate：`if not BaseObject.m_boHideMode or m_boCoolEye then` ——
  `ObjMon.pas:564/1238/1421/1531`、`ObjMon2.pas:235/551`、`ObjAxeMon.pas:145`、`ObjBase.pas:22680`。
  即**默认所有怪都看不见隐身玩家**，除非该怪实例 `m_boCoolEye = True`。
- `UsrEngn.pas:1950`（`MonInitialize` 之后）：`if Random(100) < Cert.m_btCoolEye then Cert.m_boCoolEye := True`
  ——每只怪**出生时**按 Monster.DB 的 `CoolEye` 百分比掷一次。
- `ObjBase.pas:20139 StatusChanged` → `SendRefMsg(RM_CHARSTATUSCHANGED, m_nHitSpeed, m_nCharStatus, 0, 0, '')`；
  `TPlayObject.Run:5966-5973` 转成
  `SendDefMessage(SM_CHARSTATUSCHANGED, BaseObject, LoWord(nParam1), HiWord(nParam1), wParam, '')`
  ⇒ **wire 657 = (recog=对象 id, param=charStatus 低 16 位, tag=charStatus 高 16 位, series=m_nHitSpeed)**。
- `SendRefMsg` 广播范围 ±12 格且只发玩家（`19590+`）⇒ Java 端用 `emitToObserversAndSelf` 等价。
- 客户端：`ClMain.pas:4491` → `PlayScn.pas:2346`（`m_nState := MakeLong(Param,Tag)`）→
  `Actor.pas:1965 if m_nState and $00800000 <> 0 then blend := True`（半透明渲染）。
- `GetCharStatus`（`20074`）= 对每个 `m_wStatusTimeArr[i] > 0` 置 `$80000000 shr i`；
  位 8 = `0x00800000`。Java 端按已建模的槽位（0/1 中毒、8 隐身、9 防御、10 魔御、11 魔法盾）拼字。

## Java 落地范围（本切片）

1. `Player`：新增 `transparentUntil`（绝对毫秒，0=未激活）、`hideMode`、`transparent`（`m_boTransparent`），
   并把 `status` 改为可变（承载 `m_nCharStatus`）。
2. `castPlayerSpell`：18/19 纳入"支持技能"与"空地点允许"白名单，进入 `castAmuletGatedSpell` 的护身符链。
3. `castPrivateCloak` / `applyPrivateTransparent`：§2 逐句（早退不刷新、±9 格清仇恨含 `Random(2)`、
   置计时/状态字/`hideMode`/`transparent`、发 `CharacterStatusChanged`）。
4. `castGroupCloak`：§3 逐句（点击格 ±1、`HAM_GROUP` 友方、计时=0 才排单、800 ms 延迟投递、
   `affected > 0` 才算 `boTrain`）。
5. 破隐：`movePlayer` 成功迈步后把计时压到 `now + 1000`（仅 `transparent and hideMode`）；
   计时归零时清 `hideMode` 并补发 `CharacterStatusChanged`。
6. 索敌：`acquireTarget` 的**搜索**分支跳过"隐身且怪无 CoolEye"的候选（保留目标分支不动，与原文一致）；
   `MonsterTemplate` 增加 `coolEyePercent`，`spawn` 时 `Random(100) < percent` 掷实例标记
   （percent = 0 不消耗随机数，既有确定性向量不受影响）。
7. 协议：新增 `WorldEvent.CharacterStatusChanged(objectId, hitSpeed, charStatus)`，适配器发
   `SM_CHARSTATUSCHANGED(657)`；矩阵 row 224 更新为 implemented。
8. 测试：`WorldCloakTest`（世界侧：门/计时/清仇恨/索敌/破隐/群隐延迟）+
   `GameCloakProtocolTest`（真实 `CM_SPELL` 报文进、657 出站断言）。

## 明确不做（边界）

- 16 捆魔咒（陷阱子系统）、17 召唤骷髅（召唤物）、20 诱惑之光（宠物）、21 瞬息移动（地图位移）不在本切片。
- 隐身戒指（`StdMode`/`AniCount=111` → 60000 永久隐身）不建模，只保留 `>=60000` 冻结语义注释。
- 毒蛾/雪虎等特定怪种的自隐与"踩破隐身"行为不建模（怪种未接）。
- 攻击/受击不破隐（与 Delphi 一致，不是遗漏）。
- Delphi 的 `boChg` 对**任何**状态槽归零都会补发 `StatusChanged`（`ObjBase.pas:4166`），本切片只把隐身族接上
  657：中毒位（slot 0/1）与 W46 的防御盾位（slot 9/10/11）变化仍不广播（W46 边界，未在本切片扩展）。
  `GetCharStatus` 本身已把这几个槽都算进状态字，所以隐身帧带出的字是完整的。
- 本切片为 **Java↔Java 断言，不构成 G4 签发**：没有 mir2.exe / Delphi 服务端 wire diff，
  666/657 的出站帧序只对 Delphi 源码与客户端读取逻辑逐字对齐。
