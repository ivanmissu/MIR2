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
| `TClientItem`, `CM_QUERYBAGITEMS` / `SM_BAGITEMS`, equipment, magic, NPC scripts | next/future slices | full item payload and client bag sync are next; equipment/magic/scripts not started |

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
- [ ] Real-client TClientItem/SM_BAGITEMS relog validation
- [ ] 50-bot one-hour stability run
