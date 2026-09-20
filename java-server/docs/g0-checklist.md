# G0 PoC checklist (current baseline)

This checklist deliberately distinguishes local deterministic checks from claims that require a Delphi server/client fixture.

| Gate criterion | Status | Evidence / next action |
|---|---|---|
| 12-byte message layout | Pass | `DefaultMessage` little-endian unit test |
| 20 golden vectors | Partial | 20 deterministic round trips exist; replace/add Delphi-captured wire vectors (capture tooling ready: `java-server/wiretool`, see the recorder/replayer row below) |
| 6-bit codec | Partial | OLD mode round trips; verify against captured `EDcode.pas` output |
| GBK byte limits | Pass | boundary unit test, including incomplete multibyte prefix |
| Real client login | Partial | socket framing and login/selection handler implemented; validate against mir2.exe and captured traffic |
| recorder / replayer trio members | Pass (skeleton) | `java-server/wiretool`: transparent recording proxy (`.mrec` v1), paced byte-level replay diff + structural mode, per-frame inspector; loopback and bot-proxied evidence in `java-server/docs/g0-evidence/2026-09-20-wiretool-record-replay-smoke.md`; Delphi-side capture remains as the follow-up |
| Walk/kill/pickup/relogin | Partial | Local tests cover world/7200 combat, SQLite + World restart restoration, full 76-byte TClientItem bodies, `CM_QUERYBAGITEMS → SM_BAGITEMS` and stable MakeIndex; real mir2.exe validation remains |
| 50 bots × 1 hour | Pass (local, scaled rehearsal) | bot-swarm harness (`java-server/loadtest/`) drives 50 bots through the real three-port TCP chain (login → create → enter → walk/hit/pickup → periodic relog); 50 bots × 5 min PASSed in both embedded and remote-fatjar modes with 0 errors (reports in `java-server/docs/g0-evidence/`), a 50 bots × 2 min run gates every PR in CI, and the full 50 × 1 hour run reproduces via `--duration 1h` (report to be appended to g0-evidence). Not yet run against mir2.exe-derived behaviour assumptions, so still local evidence only |
| Linux/JDK build | Pass | Maven verify, executable JAR three-port startup smoke test, and container image build passed on GitHub Actions run `35427011522` |

No G0 decision should be marked Go until the partial and not-started rows have external Delphi/client evidence.
