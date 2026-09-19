# Delphi → Java translation map

| Delphi source | Java target | Status |
|---|---|---|
| `Common/Grobal2.pas:TDefaultMessage` | `protocol/DefaultMessage` | implemented + unit tested |
| `Common/Grobal2.pas:CM_/SM_ constants` | `protocol/ProtocolConstants` | mechanically extracted; field specifications still incomplete |
| `Common/EDcode.pas:Encode6BitBuf` | `protocol/SixBitCodec` | OLD mode implemented + unit tested; Delphi golden still required |
| `Common/EDcode.pas:EncodeMessage/DecodeMessage` | `protocol/MessageCodec` | implemented + unit tested |
| Delphi `AnsiString` / GBK fixed fields | `protocol/ByteStrings` | implemented + unit tested |
| `Common/DES.pas:EncryStr/DecryStr` | `protocol/DesCodec` | implemented with DES/ECB/NoPadding + zero padding; Delphi ciphertext golden still required |
| `LoginGate` / `SelGate` / `RunGate` socket boundary | `gate` | virtual-thread PoC and legacy framing implemented; limits, Netty and true-client validation remain |
| `M2Server/UsrEngn.pas:TUserEngine.Run` | `world/WorldEngine` | single-owner fixed tick and FIFO command queue implemented |
| `M2Server/Envir.pas:TMapHeader/TMapUnitInfo` | `world/Mir2MapLoader` | packed 52-byte header and 12-byte column-major cells implemented + unit tested |
| `M2Server/Envir.pas:CanWalk/MoveToMovingObject` | `world/GameMap` | terrain flags and moving-object occupancy implemented + unit tested |
| `M2Server/ObjBase.pas:WalkTo/RunTo/ClientChangeDir` | `world/WorldEngine` | eight-way walk/run/turn validation implemented; timing/golden comparison remains |
| `M2Server/ObjBase.pas:SearchViewRange/SendRefMsg` | `world/WorldEngine` | 12-cell square interest events (appear/move/turn/disappear) implemented |
| `RunGate` first RunLogin packet + `CM_TURN/WALK/RUN` | `gate` → `world` adapter | implemented + integration tested |
| `M2Server/ObjBase.pas:AttackDir` / `CheckActionInterval` | `world/WorldEngine.attack`, `AttackKind` | single-cell melee, shared CM_HIT interval, damage roll implemented |
| `M2Server/ObjBase.pas:RM_STRUCK/RM_DEATH/RM_HEALTHSPELLCHANGED/RM_WINEXP` | `gate/GameProtocolAdapter` | SM_STRUCK/SM_DEATH/SM_HEALTHSPELLCHANGED/SM_WINEXP mapped with TMessageBodyWL/TCharDesc |
| `M2Server/ObjMon*.pas` melee monster tick | `world/MonsterTemplate`, `WorldEngine.updateMonsters` | target acquisition, chase, attack intervals, corpse timeout implemented |
| `M2Server/ObjBase.pas:DropItemDown` / `ClientPickUpItem` | `world/ItemDrop`, `GroundItem`, `WorldEngine.pickUp` | drop table, SM_ITEMSHOW/SM_ITEMHIDE, CM_PICKUP implemented |
| `M2Server/ObjBase.pas:GetFeature` / `MakeHumanFeature` | `character/Character.feature`, GAME session | gender/hair/dress/weapon appearance persisted and packed into SM_LOGON Feature |
| `DBServer` ability/bag character record | `world/PlayerStateStore`, `persistence/SqliteStore` | Ability and ordered 46-slot backpack transactionally saved/restored; W02 SQLite migration tested |
| `M2Server/ItmUnit.pas:TItem` / `UsrEngn.pas:StdItemList` | `world/StdItem`, `world/ItemDatabase`, `world/StdItems`, `persistence/SqliteStore.itemDatabase` | minimal item catalog implemented (鸡肉/鹿肉/木剑/金创药); SQLite `std_items` seeds at boot and resolves instances by name like `CopyToUserItemFromName` |
| `M2Share.pas:GetItemNumber` / `UsrEngn.pas:CopyToUserItemFromName` | `world/WorldEngine.allocateMakeIndex`, `world/BackpackItem.of` | per-instance MakeIndex seeded from the persisted high-water mark; instances created at full durability (Dura=DuraMax=template word) |
| `Common/Grobal2.pas:TStdItem` / `TClientItem` | `gate/ClientItemCodec` | 66-byte packed TStdItem + aligned MakeIndex → 76-byte little-endian TClientItem, 6-bit encoded; byte-offset unit tests; Delphi-captured golden still required |
| `M2Server/ObjBase.pas:ClientQueryBagItems` / `SendAddItem` | `gate/GameProtocolAdapter` | `CM_QUERYBAGITEMS → SM_BAGITEMS` (series=count, '/'-terminated entries, silent on empty bag) and `SM_ADDITEM` full TClientItem body implemented |
| `DBServer` inventory record with per-item MakeIndex/Dura | `persistence/SqliteStore` (character_inventory + std_items join) | make_index/dura/dura_max persisted per slot; W03 rows upgraded in place and renumbered by the engine on restore |
| Equipment slots (`UseItems`), magic, NPC scripts | next/future slices | equipment/magic/scripts not started; deliberately outside the W04 bag-sync slice |
| Client-side stress: no Delphi equivalent (new Java-side tooling) | `loadtest/` module: `LoadtestMain` (CLI), `BotWireClient` (real `#…!` frames + prefix rotation + 12-byte little-endian header + 6-bit body, GBK), `Mir2Bot` (login→create→enter→walk/hit/pickup→periodic relogin state machine), `BotSwarm` (ramped start, live monitor line, verdict), `BotMetrics`/`BotReport` (markdown+csv), `BotSwarmEmbeddedTest` (CI) | the swarm speaks the same wire protocol as `mir2.exe`; it replaces a room full of human testers, not the golden-vector work |

## G0 evidence status

- [x] 12-byte packing and legacy codec skeleton
- [x] 20 local deterministic round-trip vectors
- [x] Linux container/JDK build baseline
- [x] World tick, map collision, lifecycle and movement visibility unit tests
- [ ] Delphi-captured byte-for-byte vectors
- [ ] Unmodified client login and RunLogin
- [ ] Unmodified client movement broadcast
- [x] One monster, kill, drop and pickup in deterministic unit/integration tests
- [x] Local relog persistence of HP/MP/level/experience/bag across SQLite and World restart
- [x] Full 76-byte TClientItem encoding plus `CM_QUERYBAGITEMS → SM_BAGITEMS` in deterministic tests (byte-offset layout derived from the Grobal2.pas field list)
- [ ] Real-client TClientItem/SM_BAGITEMS relog validation
- [ ] 50-bot one-hour stability run — harness delivered and scaled evidence in: 50×5 min PASS (0 errors) in both embedded and remote-fatjar modes (reports in `g0-evidence/`) plus a 50×2 min gate per PR in CI; the full 1 h run reproduces via `--duration 1h` and its report will be appended
