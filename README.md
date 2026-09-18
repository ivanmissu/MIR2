# MIR2 Java server · S0 executable baseline

This directory contains the executable Java migration slice derived from the original Delphi server and protocol sources.

## Implemented

- packed Delphi `TDefaultMessage` (12 bytes, little-endian)
- legacy OLDMODE 6-bit transport encoding/decoding
- DES compatibility wrapper with Delphi zero-padding semantics
- GBK boundary helpers with byte—not UTF-16 character—limits
- 20 deterministic message round-trip vectors plus binary and GBK edge tests
- 302 `CM_` / `SM_` constants mechanically extracted from `Grobal2.pas`
- three-port socket server with legacy `#<sequence><header><body>!` framing
- initial login, server selection, and character list/create/delete/select mapping
- SQLite account and character persistence
- executable shaded JAR and Docker Compose packaging

## Build and run

JDK 21 and Maven 3.9+ are required:

```bash
mvn -f java-server/pom.xml verify
java -jar java-server/bootstrap/target/mir2-server.jar
```

The default process listens on TCP ports 7000, 7100, and 7200 and stores data in `data/mir2.db` relative to its working directory. Stop it with `Ctrl+C` or `SIGTERM`; the shutdown hook closes listeners and SQLite cleanly.

To create a first test account on an empty database:

```bash
MIR2_BOOTSTRAP_USER=hero \
MIR2_BOOTSTRAP_PASSWORD=change-me \
java -jar java-server/bootstrap/target/mir2-server.jar
```

The bootstrap account is created only when that username does not already exist. Its password is not reset on later starts.

## Configuration

| Environment variable | Default | Purpose |
|---|---:|---|
| `MIR2_DATABASE` | `data/mir2.db` | SQLite file path |
| `MIR2_LOGIN_PORT` | `7000` | LoginGate listener |
| `MIR2_SELECT_PORT` | `7100` | SelGate listener |
| `MIR2_GAME_PORT` | `7200` | RunGate listener |
| `MIR2_ADVERTISED_HOST` | `127.0.0.1` | Address returned to `mir2.exe` for its next connection |
| `MIR2_SERVER_NAME` | `MIR2` | Server name shown to the client |
| `MIR2_BOOTSTRAP_USER` | unset | Optional initial test account |
| `MIR2_BOOTSTRAP_PASSWORD` | unset | Password paired with the initial account |

For a client on another computer, `MIR2_ADVERTISED_HOST` must be the server's LAN or public address, not `127.0.0.1`.

## Docker Compose

From the repository root:

```bash
MIR2_ADVERTISED_HOST=192.0.2.10 \
MIR2_BOOTSTRAP_USER=hero \
MIR2_BOOTSTRAP_PASSWORD=change-me \
docker compose -f java-server/compose.yml up --build
```

SQLite is retained in the named `mir2-data` volume. The image runs as an unprivileged user.

## Compatibility notes

`EDcode.pas` currently has `ENDECODEMODE = OLDMODE`; therefore this baseline intentionally does not apply the NEWMODE substitution tables. Do not change this without Delphi golden captures. `DefaultMessage` uses unsigned 16-bit validation and signed 32-bit `Recog`, matching Delphi's `Word` and `Integer` layout.

Compilation and startup smoke tests do not yet prove full `mir2.exe` compatibility. Real-client validation and the W03 game-world implementation remain pending.
