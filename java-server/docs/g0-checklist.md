# G0 PoC checklist (current baseline)

This checklist deliberately distinguishes local deterministic checks from claims that require a Delphi server/client fixture.

| Gate criterion | Status | Evidence / next action |
|---|---|---|
| 12-byte message layout | Pass | `DefaultMessage` little-endian unit test |
| 20 golden vectors | Partial | 20 deterministic round trips exist; replace/add Delphi-captured wire vectors |
| 6-bit codec | Partial | OLD mode round trips; verify against captured `EDcode.pas` output |
| GBK byte limits | Pass | boundary unit test, including incomplete multibyte prefix |
| Real client login | Not started | implement auth/session and Netty ports |
| Walk/kill/pickup/relogin | Not started | implement engine MVP |
| 50 bots × 1 hour | Not started | implement bot harness |
| Linux container build | CI configured | requires GitHub Actions run with JDK 21 |

No G0 decision should be marked Go until the partial and not-started rows have external Delphi/client evidence.
