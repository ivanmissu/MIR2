# MIR2 Java server · S0 protocol baseline

This is the first executable slice of the development plan: a dependency-free protocol core derived from `GameOfMir/Common/Grobal2.pas` and `EDcode.pas`.

## Implemented

- packed Delphi `TDefaultMessage` (12 bytes, little-endian)
- legacy OLDMODE 6-bit transport encoding/decoding
- DES compatibility wrapper with Delphi zero-padding semantics
- GBK boundary helpers with byte—not UTF-16 character—limits
- 20 deterministic message round-trip golden vectors plus binary and GBK edge tests
- 302 `CM_` / `SM_` constants mechanically extracted from `Grobal2.pas`

The repository environment does not include JDK/Maven, so CI is the authoritative build environment. Run with JDK 21 and Maven 3.9+:

```bash
mvn -f java-server/pom.xml test
```

## Compatibility notes

`EDcode.pas` currently has `ENDECODEMODE = OLDMODE`; therefore this baseline intentionally does not apply the NEWMODE substitution tables. Do not change this without Delphi golden captures. `DefaultMessage` uses unsigned 16-bit validation and signed 32-bit `Recog`, matching Delphi's `Word` and `Integer` layout.
