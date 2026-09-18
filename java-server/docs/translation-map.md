# Delphi → Java translation map

| Delphi source | Java target | Status |
|---|---|---|
| `Common/Grobal2.pas:TDefaultMessage` | `protocol/DefaultMessage` | implemented + unit tested |
| `Common/Grobal2.pas:CM_/SM_ constants` | `protocol/ProtocolConstants` | 89 constants extracted |
| `Common/EDcode.pas:Encode6BitBuf` | `protocol/SixBitCodec` | OLD mode implemented + unit tested |
| `Common/EDcode.pas:EncodeMessage/DecodeMessage` | `protocol/MessageCodec` | implemented + unit tested |
| Delphi `AnsiString` / GBK fixed fields | `protocol/ByteStrings` | implemented + unit tested |
| `Common/DES.pas` | `protocol/DesCodec` | not started; requires Delphi golden vectors |
| Netty access layer | `gate` | not started |

## G0 evidence status

- [x] 12-byte packing and legacy codec skeleton
- [x] 20 local deterministic round-trip vectors
- [ ] Delphi-captured byte-for-byte vectors
- [ ] unmodified client login
- [ ] Linux container build
