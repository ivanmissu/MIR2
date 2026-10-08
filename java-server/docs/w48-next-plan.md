# W48 开发计划：`SKILL_SHOWHP`（心灵启示）+ `SKILL_KILLUNDEAD`（圣言术）

延续 W28–W47 的按周技能迁移节奏：本周接 **Magic.DB 28/32 两条单体技能** ——
`SKILL_SHOWHP` = 28 心灵启示（道士，NeedL1=23）与 `SKILL_KILLUNDEAD` = 32 圣言术（法师，NeedL1=32）。
二者都是 `Magic.pas` 里不需要新子系统的单目标分支：心灵启示把"显血订阅"帧
（`SM_OPENHEALTH(1100)` / `SM_CLOSEHEALTH(1101)`）第一次带上线；圣言术是法师对不死系怪物的
即死技，同时引入怪物侧 `Struck` 仇恨与 `m_boRunAwayMode` 恐惧冻结。

候选盘点（Magic.DB 1–33 剩余可学技能）：22 火墙（`MagMakeFireCross` → `TFireBurnEvent` 十字火墙，
需要地图事件/火墙对象子系统，本周不做）、28 心灵启示、32 圣言术。红线技能
16 捆魔咒 / 17 召唤骷髅 / 20 诱惑之光 / 21 瞬息移动 / 30 召唤神兽 继续不动。

## 源码核验结论

### 1. 施法入口：两个独立 `case` 分支

`M2Server/Magic.pas`：

```pascal
522: SKILL_SHOWHP{28}: begin   // 心灵启示
       if (TargeTBaseObject <> nil) and not TargeTBaseObject.m_boShowHP then begin
         if Random(6) <= (UserMagic.btLevel + 3) then begin
           TargeTBaseObject.m_dwShowHPTick:=GetTickCount();
           TargeTBaseObject.m_dwShowHPInterval:=GetPower13(GetRPow(PlayObject.m_WAbil.SC) * 2 + 30) * 1000;
           TargeTBaseObject.SendDelayMsg(TargeTBaseObject,RM_DOOPENHEALTH,0,0,0,0,'',1500);
           boTrain:=True;
         end;
       end;
     end;
572: SKILL_KILLUNDEAD{32}:begin //00493A97  圣言术
       if PlayObject.IsProperTarget (TargeTBaseObject) then begin
         if MagTurnUndead(PlayObject,TargeTBaseObject,nTargetX,nTargetY,UserMagic.btLevel) then
           boTrain:=True;
       end;
     end;
```

- 两条分支都**不碰 `boSpellFail`/`boSpellFire`**：MP 在 `TPlayObject.DoSpell`（ObjBase.pas:21852）
  里先扣，`RM_MAGICFIRE` 在 `DoSpell` 尾部（Magic.pas:714-718）无条件广播 ——
  打空地、打非不死系、`Random` 失败都照样烧蓝、照样播施法帧，只是不训练。
- 心灵启示的门槛只有 `TargeTBaseObject <> nil`（**没有** `IsProperTarget` —— 不查敌友、不查种族，
  玩家/怪物皆可被点亮）；圣言术要求 `IsProperTarget`（存活、同图、行规攻击模式下合法）。
- 心灵启示成功率 `Random(6) <= btLevel + 3`：技能 0/1/2/3 级 → 4/6、5/6、6/6、6/6。
- 显血时长 `GetPower13(GetRPow(SC) * 2 + 30) * 1000`（毫秒）：
  - `GetRPow(SC)`（Magic.pas:73/247）= `HiWord > LoWord ? Random(Hi-Lo+1) + Lo : Lo`，取一次道术区间随机值再 ×2 + 30。
  - `GetPower13`（Magic.pas:64/238）三等分公式：`ROUND(2n/3 / 4 * (btLevel+1) + n/3)`，
    行 28 的 `DefPower/DefMaxPower` 全 0，附加项为 0。
  - 裸 SC(0) 时 n = 30 → 技能 0..3 级 = 15/20/25/30 秒；SC 越高上限越高。
- 两条技能的 `Delay`：行 28 = 40 ms，行 32 = 120 ms（施法间隔 = 共享 1350 ms + Delay）。
- 魔法消耗 `GetSpellPoint` = `Round(wSpell / 4 * (btLevel+1)) + btDefSpell`（银行家舍入）：
  行 28（spell=16, defSpell=0）→ 4/8/12/16；行 32（spell=50, defSpell=40）→
  `Round(12.5)=12`→52、65、`Round(37.5)=38`→78、90。

### 2. `MagTurnUndead`（Magic.pas:901，004926D4）

```pascal
903: if TargeTBaseObject.m_boSuperMan or not (TargeTBaseObject.m_btLifeAttrib = LA_UNDEAD) then exit;
905: TAnimalObject(TargeTBaseObject).Struck{FFEC}(BaseObject);
906: if TargeTBaseObject.m_TargetCret = nil then begin
907:   TAnimalObject(TargeTBaseObject).m_boRunAwayMode:=True;
908:   TAnimalObject(TargeTBaseObject).m_dwRunAwayStart:=GetTickCount();
909:   TAnimalTBaseObject).m_dwRunAwayTime:=10 * 1000;
910: end;
911: BaseObject.SetTargetCreat(TargeTBaseObject);
912: if (Random(2) + (BaseObject.m_Abil.Level - 1)) > TargeTBaseObject.m_Abil.Level then begin
913:   if TargeTBaseObject.m_Abil.Level < g_Config.nMagTurnUndeadLevel then begin
914:     n14:=BaseObject.m_Abil.Level - TargeTBaseObject.m_Abil.Level;
915:     if Random(100) < ((nLevel shl 3) - nLevel + 15 + n14) then begin
916:       TargeTBaseObject.SetLastHiter(BaseObject);
917:       TargeTBaseObject.m_WAbil.HP:=0;
918:       Result:=True;
```

- **种族门槛在最前面**：非不死系（`m_btLifeAttrib <> LA_UNDEAD = 1`，Grobal2.pas:1332）直接 `exit`，
  `Struck` 都不执行 —— 玩家默认 `m_btLifeAttrib := 0`（ObjBase.pas:1244），所以圣言术永远不能对玩家生效；
  `m_boSuperMan` 在首批 10 种怪里没有对应物，Java 不建模。
- 等级门槛 `Random(2) + (施法者等级 - 1) > 目标等级`；目标等级须 `< g_Config.nMagTurnUndeadLevel`
  （`M2Share.pas:2080` 默认 **50**，`!Setup.txt` 可配，`FunctionConfig.dfm` 的 SpinEdit 范围 1..65535）。
- 即死率 `Random(100) < (btLevel shl 3) - btLevel + 15 + 等级差` = **7×技能等级 + 15 + 等级差**（百分比）。
- 即死 = `SetLastHiter` + `m_WAbil.HP := 0` —— 死亡在怪物下一个 `Run` 里处理（掉落/经验/尸体与普通击杀同链），
  本引擎按 W14 约定同步处理 `handleDeath`。
- `Result := True`（训练）**只在即死成功时** —— 打空、非不死系、等级门槛失败、即死率失败都不训练。

### 3. `TAnimalObject.Struck` 与恐惧冻结（ObjBase.pas:2794 / ObjMon.pas:447）

```pascal
2794: procedure TAnimalObject.Struck(hiter: TBaseObject); //004C93A8
2798:   if (m_TargetCret = nil) or GetAttackDir(m_TargetCret, btDir) or (Random(6) = 0) then begin
2801:     if IsProperTarget(hiter) then SetTargetCreat(hiter);
2813:   m_dwHitTick := m_dwHitTick + LongWord(150 - _MIN(130, m_Abil.Level * 4));
```

- `GetAttackDir`（ObjBase.pas:18449）= 当前目标在自身 **3x3 邻域内**（不含同格）→ 必转火；
  否则 **1/6** 概率转火；`IsProperTarget(hiter)` 从怪物视角检查施法者（存活且不在安全区）。
- `m_dwHitTick` 是攻击节拍闸（ObjMon.pas:392 等 `GetTickCount - m_dwHitTick > m_nNextHitTime`），
  叠加 `150 - min(130, 怪等级×4)` 毫秒短硬直（怪等级 ≥ 33 时只有 20 ms）。
- `m_nMeatQuality`（可挖肉品质）是 `m_boAnimal` 的经济手感，Java 无肉系统，不建模。
- `Struck` **不发任何线帧** —— 圣言术打中没有 `SM_STRUCK`，只有施法帧与（即死时的）死亡帧。
- 恐惧：`Struck` 之后若怪物仍无目标（典型：施法者站在安全区，`IsProperTarget(hiter)` 拒转火）
  → `m_boRunAwayMode := True`，10 秒。`TMonster.Run`（ObjMon.pas:447-460）在 `m_boRunAwayMode` 期间
  **跳过整个追击/攻击块** —— 怪物原地冻结，不走不动不打，10 秒后恢复。
  （`TChickenDeer.Run` 每个走步节拍自扫覆盖该标志，鸡鹿仍按自己的逃跑 AI 走，Java 只对
  `AGGRESSIVE` 怪套用冻结。）
- `BaseObject.SetTargetCreat(TargeTBaseObject)`（ObjBase.pas:21929）只是给施法者记目标，无线帧。

### 4. 心灵启示的显血链（ObjBase.pas）

- 投递：`RM_DOOPENHEALTH`（ObjBase.pas:4623-4625）→ `MakeOpenHealth()`（ObjBase.pas:3606）：

  ```pascal
  3606: procedure TBaseObject.MakeOpenHealth(); //004BDC7C
  3608:   m_boShowHP := True;
  3609:   m_nCharStatusEx := m_nCharStatusEx or STATE_OPENHEATH;   // $00000002，Grobal2.pas:96
  3610:   m_nCharStatus := GetCharStatus();                        // 20087 行：低 20 位并入 m_nCharStatusEx
  3611:   SendRefMsg(RM_OPENHEALTH, 0, m_WAbil.HP, m_WAbil.MaxHP, 0, '');
  ```

  注意：**不调 `StatusChanged()`** —— 状态字里的 `$2` 位只是潜伏，烧到客户端要等下一次别的状态重绘。
- 到期：`TBaseObject.Run` 状态走查（ObjBase.pas:4029-4031）
  `if m_boShowHP and ((GetTickCount - m_dwShowHPTick) > m_dwShowHPInterval) then BreakOpenHealth()` ——
  计时从**施法时刻**算（`m_dwShowHPTick` 是施法时戳的，1500 ms 后才点亮），严格大于才破。
- `BreakOpenHealth()`（ObjBase.pas:3595）：清 `m_boShowHP`、`xor` 掉 `$2` 位、重算状态字、
  `SendRefMsg(RM_CLOSEHEALTH)`。同样不主动广播状态帧。
- 显血期间的血量同步：`HealthSpellChanged`（ObjBase.pas:2239-2248）
  `if m_boShowHP then SendRefMsg(RM_HEALTHSPELLCHANGED, ...)` —— **只有显血中的目标**才向观察者广播血量变化。
  （本引擎既有行为是怪物血量变化一律广播 `HealthChanged`，客户端对未显血怪物静默吸收该帧，
  本切片保留既有广播、不改既有测试，只在计划文档里记录这条与 Delphi 的差异。）
- 线帧转换（TPlayObject.Run，ObjBase.pas:6288-6302）：
  `RM_OPENHEALTH` → `SM_OPENHEALTH(1100)` = (recog=对象, param=HP, tag=MaxHP, series=0)；
  `RM_CLOSEHEALTH` → `SM_CLOSEHEALTH(1101)` = (recog=对象, 其余 0)。
- 客户端：`ClMain.pas:4283-4301` —— `SM_OPENHEALTH` 置 `actor.m_boOpenHealth := TRUE` 并写入 HP/MaxHP，
  `SM_CLOSEHEALTH` 清零；`DrawScrn.pas:286-287` 按 `m_boOpenHealth and not m_boDeath` 画血条。
  另：`Actor.pas:1460` 会从状态字的 `STATE_OPENHEATH($2)` 位同步 `m_boOpenHealth`。

### 5. 怪物不死系标记

- `Monster.DB` 的 `Undead` 列 → `m_btLifeAttrib = LA_UNDEAD`（W32 已接 `MonsterTemplate.undead`）；
  首批 10 种已接线怪物里**稻草人（scarecrow）是唯一不死系**，雷电术（11）的 1.5 倍、地狱雷光（24）的
  满功分流都已消费同一字段，圣言术复用之。

## Java 落地范围（本切片）

1. `WorldRandom`：追加三个流（末尾追加，既有种子流水不挪窝）——
   `TURN_UNDEAD`（圣言术 `Random(2)` 等级门槛 + `Random(100)` 即死率，Magic.pas:912/915）、
   `STRUCK_RETARGET`（`Struck` 的 `Random(6)` 转火，ObjBase.pas:2798）、
   `SHOW_HP`（心灵启示 `Random(6)` 显血门槛，Magic.pas:524）。
2. `WorldEvent`：新增 `HealthRevealed(object)`（→ `SM_OPENHEALTH`）与
   `HealthConcealed(objectId)`（→ `SM_CLOSEHEALTH`）。
3. `WorldEngine`：
   - `Player`/`Monster` 各持一个 `RevealedHealth` 小记账板（`active` = `m_boShowHP`、
     `castAt` = `m_dwShowHPTick`、`intervalMillis` = `m_dwShowHPInterval`），经 `WorldObject` 接口暴露
     （与 `poison()` 同构，`Npc` 返回共享哑元）；`Monster` 新增 `runAwayUntil`。
   - `castPlayerSpell`：28/32 纳入支持技能白名单；`validSpellTarget` 对二者走"非己、非 NPC、存活、同图、
     贴脸"规则（与单体法术同形）；新增 `castShowHp` / `castTurnUndead` 两个分发分支。
   - `castShowHp`：`Random(6) <= btLevel+3` 门槛 → 目标记账板写好施法时刻与
     `GetPower13(GetRPow(SC)*2+30)*1000` 时长 → 排一条 1500 ms 自寻址 `OPEN_HEALTH` 延迟投递 →
     无条件播 `MagicFired`，成功才 `trainSpellSkill`。
   - 投递点 `resolvePendingMagicImpacts`：`OPEN_HEALTH` 与 `TRANSPARENT` 同款，在通用存活/站位检查
     之前按"接收者队列"语义处理（施法者 1500 ms 内死亡不影响投递）。
   - `makeOpenHealth` / `breakOpenHealth`：置/清 `active` 并向观察者广播 `HealthRevealed` /
     `HealthConcealed`；到期走查 —— 玩家进 `expireSkillBuffs`、怪物进 `updateMonsters`
     （`STATIONARY` 木桩跳过，对应 Delphi `TTrainer` 的 no-op Run 不破显血）。
   - `charStatus`：显血中的玩家状态字带上 `STATE_OPENHEATH = $2`（潜伏位，与 Delphi 同：
     `Make/BreakOpenHealth` 本身不广播状态帧）。
   - `castTurnUndead`：先播 `MagicFired` 再跑效果（保证 `SM_MAGICFIRE` 在死亡帧之前）；
     `turnUndead` 逐句：不死系门槛 → `monsterStruck`（转火规则 + 攻击硬直）→
     无目标则 `runAwayUntil = now + 10_000` → 等级门槛 → `nMagTurnUndeadLevel` 门槛 →
     即死率 → `HP := 0` + `handleDeath`（掉落/经验/尸体同普通击杀）；即死成功才训练。
   - `updateMonsters`：`AGGRESSIVE` 怪物 `runAwayUntil` 未到期 → 跳过追击/攻击（恐惧冻结）。
4. `GameProtocolAdapter`：`HealthRevealed` → `SM_OPENHEALTH(1100)`
   (recog=对象, param=HP, tag=MaxHP)；`HealthConcealed` → `SM_CLOSEHEALTH(1101)` (recog=对象)。
5. 配置：`g_Config.nMagTurnUndeadLevel`（默认 50，范围 1..65535）→ `MIR2_MAG_TURN_UNDEAD_LEVEL` ——
   `ServerConfig` 新增 record 分量 + 校验 + 兼容构造器，`WorldEngine.Config` 同增分量，
   `Mir2Server` 接线，`deployment.md` 增表行，`ServerConfigTest` 增默认值/环境变量/边界用例。
6. 矩阵：`SKILL_SHOWHP:taoist`、`SKILL_KILLUNDEAD:wizard`、`SM_OPENHEALTH`、`SM_CLOSEHEALTH`
   四行转 implemented（wire=gate-game，证据列填新测试类）。
7. 测试：`WorldShowHpAndTurnUndeadTest`（世界侧）+ `GameShowHpAndTurnUndeadProtocolTest`
   （真实 `CM_SPELL` 进、`SM_OPENHEALTH`/`SM_CLOSEHEALTH`/`SM_MAGICFIRE`/`SM_DEATH` 出站断言）。

## 明确不做（边界）

- 22 火墙（`MagMakeFireCross` → `TFireBurnEvent` 十字火墙 + 地图事件子系统 `g_EventManager`）
  不在本切片；16/17/20/21/30 红线技能不动。
- 心灵启示对 **NPC** 目标不开放（Delphi 未排除 NPC，但 `TNormNpc` 血量无意义，且 Java `Npc`
  是静态哑元；引擎侧 `validSpellTarget` 对 28 拒 NPC）。
- 圣言术对玩家目标：通过 `IsProperTarget`，但 `LA_UNDEAD` 门槛秒退 —— 与 Delphi 一致（玩家 lifeAttrib=0）。
- 既有"怪物血量变化一律广播 `HealthChanged`"的偏差保留（客户端对未显血怪物静默吸收）；
  本切片不改既有广播与既有断言。
- `m_nMeatQuality`（Struck 的挖肉品质）、`m_boSuperMan`、`TChickenDeer` 自管恐惧、TTrainer 不破显血
  以外的怪种特例不建模。
- Delphi 里施法打空地/无效目标仍烧蓝并播 `RM_MAGICFIRE`；本引擎既有单体法术统一在施法前
  `INVALID_TARGET` 拒施（W28 起约定），28/32 沿用，不逐技能复制烧蓝语义。
- 圣言术即死按 W14 约定同步处理死亡（Delphi 在怪物下一 tick 处理 `Die`），帧序
  `SM_MAGICFIRE` → `SM_DEATH` 与 Delphi 的先后一致。
- 本切片为 **Java↔Java 断言，不构成 G4 签发**：没有 mir2.exe / Delphi 服务端 wire diff，
  1100/1101 与圣言术帧序只对 Delphi 源码与客户端读取逻辑逐字对齐。

## 状态

- [x] 源码核验（Magic.pas:522-531 / 572-577 / 901-925；ObjBase.pas:2239 / 2794 / 3595 / 3606 /
      4029 / 4623 / 6288-6302；ObjMon.pas:447-460；Grobal2.pas:96 / 399-400 / 1332；M2Share.pas:2080）
- [x] 计划文档（本文档）
- [x] world 引擎 + `WorldEvent` + `WorldRandom` 接入
- [x] gate 协议适配（`SM_OPENHEALTH` / `SM_CLOSEHEALTH`）
- [x] 配置 `MIR2_MAG_TURN_UNDEAD_LEVEL`（ServerConfig / WorldEngine.Config / Mir2Server / deployment.md）
- [x] `g4-capability-matrix.tsv` 四行更新
- [x] 测试：`WorldShowHpAndTurnUndeadTest` + `GameShowHpAndTurnUndeadProtocolTest` + `ServerConfigTest`
- [x] 本地全量编译 + 单测（ECJ + JUnit5 沙箱流水线：581 基线全绿，W48 增量后 **603/603 全绿**）
