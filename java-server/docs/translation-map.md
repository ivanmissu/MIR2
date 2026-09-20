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
| `RunGate` first RunLogin packet + `CM_TURN/WALK/RUN` | `gate` → `world` adapter | implemented + integration tested; mir2-front login/selection/entry smoke passed |
| `M2Server/ObjBase.pas:AttackDir` / `CheckActionInterval` | `world/WorldEngine.attack`, `AttackKind` | single-cell melee, shared CM_HIT interval, damage roll implemented |
| `M2Server/ObjBase.pas:RM_STRUCK/RM_DEATH/RM_HEALTHSPELLCHANGED/RM_WINEXP` | `gate/GameProtocolAdapter` | SM_STRUCK/SM_DEATH/SM_HEALTHSPELLCHANGED/SM_WINEXP mapped with TMessageBodyWL/TCharDesc |
| `M2Server/ObjMon*.pas` melee monster tick | `world/MonsterTemplate`, `WorldEngine.updateMonsters` | target acquisition, chase, attack intervals, corpse timeout implemented |
| `M2Server/ObjBase.pas:DropItemDown` / `ClientPickUpItem` | `world/ItemDrop`, `GroundItem`, `WorldEngine.pickUp` | drop table, SM_ITEMSHOW/SM_ITEMHIDE, CM_PICKUP implemented |
| `M2Server/LocalDB.pas:LoadMonGen` | `world/MonGenLoader`, `MonsterSpawnDefinition`; bootstrap initial population | legacy `MonGen.txt` rows, quoted names, comments and `loadgen` includes parsed; chicken/orc initial spawn wired through `MIR2_MONGEN_FILE`; respawn scheduler remains future work |
| `M2Server/ObjBase.pas:GetFeature` / `MakeHumanFeature` | `character/Character.feature`, GAME session | gender/hair/dress/weapon appearance persisted and packed into SM_LOGON Feature |
| `DBServer` ability/bag character record | `world/PlayerStateStore`, `persistence/SqliteStore` | Ability and ordered 46-slot backpack transactionally saved/restored; W02 SQLite migration tested |
| `M2Server/ItmUnit.pas:TItem` / `UsrEngn.pas:StdItemList` | `world/StdItem`, `world/ItemDatabase`, `world/StdItems`, `persistence/SqliteStore.itemDatabase` | minimal item catalog implemented (鸡肉/鹿肉/木剑/金创药); SQLite `std_items` seeds at boot and resolves instances by name like `CopyToUserItemFromName` |
| `M2Share.pas:GetItemNumber` / `UsrEngn.pas:CopyToUserItemFromName` | `world/WorldEngine.allocateMakeIndex`, `world/BackpackItem.of` | per-instance MakeIndex seeded from the persisted high-water mark; instances created at full durability (Dura=DuraMax=template word) |
| `Common/Grobal2.pas:TStdItem` / `TClientItem` | `gate/ClientItemCodec` | 66-byte packed TStdItem + aligned MakeIndex → 76-byte little-endian TClientItem, 6-bit encoded; byte-offset unit tests; Delphi-captured golden still required |
| `M2Server/ObjBase.pas:ClientQueryBagItems` / `SendAddItem` | `gate/GameProtocolAdapter` | `CM_QUERYBAGITEMS → SM_BAGITEMS` (series=count, '/'-terminated entries, silent on empty bag) and `SM_ADDITEM` full TClientItem body implemented |
| `DBServer` inventory record with per-item MakeIndex/Dura | `persistence/SqliteStore` (character_inventory + std_items join) | make_index/dura/dura_max persisted per slot; W03 rows upgraded in place and renumbered by the engine on restore |
| `M2Server/Envir.pas:LoadMapData` door scan (`btDoorIndex`) | `world/Mir2MapLoader`, `world/DoorInfo` | `$80` anchors, index `&$7F >0`, TDoorStatus sharing inside ±10 same-index implemented + unit tested |
| `Common/Grobal2.pas:TDoorInfo/TDoorStatus`, `Envir.pas:GetDoor/ArroundDoorOpened` | `world/GameMap` door index & `aroundDoorOpened` | exact-anchor `doorAt` and the ±1 closed-door gate suppression implemented + unit tested |
| `M2Server/UsrEngn.pas:OpenDoor/ProcessMapDoor`, `ObjBase.pas:ClientOpenDoor` | `world/WorldEngine.openDoor` / `closeDoorsPeriodically`; `gate/GameProtocolAdapter` | `CM_OPENDOOR` silent open, `SM_OPENDOOR_OK`±12 broadcast, 500ms sweep + 5s auto-close `SM_CLOSEDOOR`; castle divergence (`bo01`, castle doors) noted |
| `M2Server/LocalDB.pas:QMapInfo` (MapInfo.txt) | `world/MapInfoLoader` | `loadmapinfo` includes (resolved from `MapInfo/` subdir), `;` comments, `[id|alias desc idx]` definitions, route lines with Delphi `GetValidStr3` delimiter chains and `Str_ToInt(x,0)` coercion; flags consumed but deferred |
| `M2Server/Envir.pas:AddMapRoute`, `ObjBase.pas:Walk/EnterAnotherMap` | `world/TeleportRoute`, `WorldEngine.addRoute` / gate crossing in `movePlayer` | routes registered only with both maps loaded; walk/run landing fires `SM_CLEAROBJECTS + SM_CHANGEMAP (+MAPDESCRIPTION)`; unwalkable destination rolls the whole move back; GateObj-on-closed-door suppression preserved |
| `M2Server/UsrEngn.pas:GetGameTime/ProcessHuman` (day/night cycle) | `world/WorldEngine.gameTimeFromHour/calculateDayBright/dayBright`, `WorldEvent.DayChanging`, `gate/GameProtocolAdapter` | 24-hour day/night cycle (day 1, twilight 2, dawn 0, night 3); DARK/DAYLIGHT flag overrides; SM_DAYCHANGING broadcast to online players |
| `M2Server/LocalDB.pas:QMapInfo` MapFlags (SAFE, DARK, DAY, NORECONNECT, MUSIC, EXPRATE, NOCHAT, QUIZ, FIGHT) | `world/MapFlags`, `world/MapInfoLoader` | complete map flag parsing from MapInfo.txt definitions, redirect on spawn for NORECONNECT, restriction enforcement (NOCHAT, QUIZ shout suppression) |
| `M2Server/ObjBase.pas:ClientSay/SendHearMsg/SendWhisper/SendShout` | `world/WorldEngine.say`, `WorldEvent.ChatHeard/Whisper/Shout/SystemMessage`, `gate/GameProtocolAdapter` | 12-cell local chat (`SM_HEAR`), `/target msg` whisper (`SM_WHISPER`), `!msg` map-wide shout (`SM_HEAR` with `(!)` prefix), system commands (`/who`, `@在线`), NOCHAT/QUIZ checks |
| Web frontend day/night & chat | `mir2-front` (`shared`, `bridge`, `web`) | day/night torch spotlight canvas lighting & twilight filter, character speech bubbles, chat input bar (CM_SAY), door interaction button, multi-client chat sync |
| Equipment slots (`UseItems`), magic, NPC scripts | next/future slices | equipment/magic/scripts not started; deliberately outside the W04 bag-sync slice |
| Client-side stress: no Delphi equivalent (new Java-side tooling) | `loadtest/` module: `LoadtestMain` (CLI), `BotWireClient` (real `#…!` frames + prefix rotation + 12-byte little-endian header + 6-bit body, GBK), `Mir2Bot` (login→create→enter→walk/hit/pickup→periodic relogin state machine), `BotSwarm` (ramped start, live monitor line, verdict), `BotMetrics`/`BotReport` (markdown+csv), `BotSwarmEmbeddedTest` (CI) | the swarm speaks the same wire protocol as `mir2.exe`; it replaces a room full of human testers, not the golden-vector work |
| Traffic capture & golden replay: no Delphi equivalent (new Java-side tooling, P0 三件套之一/二) | `wiretool/` module: `WireToolMain` (record/replay/inspect CLI), `RecorderProxy` + `FrameSplitter` (transparent byte relay with `#…!`/noise classification into `.mrec` v1), `RecordingCodec` (truncation-tolerant append format), `Replayer` (paced session replay), `ReplayDiff` (identical/content/missing/extra/skipped), `ReplayReport` (Chinese markdown+csv), `FrameDescriber` (ProtocolConstants reflection, RunLogin with masked cert) | skeleton delivered with loopback/bot-driven evidence; awaiting Windows+mir2.exe Delphi-side captures to become byte-level goldens |

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
