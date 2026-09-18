# G0 PoC checklist (current baseline)

This checklist deliberately distinguishes local deterministic checks from claims that require a Delphi server/client fixture.

| Gate criterion | Status | Evidence / next action |
|---|---|---|
| 12-byte message layout | Pass | `DefaultMessage` little-endian unit test |
| 20 golden vectors | Partial | 20 deterministic round trips exist; replace/add Delphi-captured wire vectors |
| 6-bit codec | Partial | OLD mode round trips; verify against captured `EDcode.pas` output |
| GBK byte limits | Pass | boundary unit test, including incomplete multibyte prefix |
| Real client login | Partial | socket framing and login/selection handler implemented; validate against mir2.exe and captured traffic |
| Walk/kill/pickup/relogin | Not started | implement engine MVP |
| 50 bots × 1 hour | Not started | implement bot harness |
| Linux/JDK build | Pass | Maven verify, executable JAR three-port startup smoke test, and container image build passed on GitHub Actions run `35329322893` |

No G0 decision should be marked Go until the partial and not-started rows have external Delphi/client evidence.
