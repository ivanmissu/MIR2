#!/usr/bin/env python3
"""Extract the authoritative Mir2 1.76 baseline tables for the Java server.

Source of truth: the GEEM2 baseline dump of the official 1.76 Monster.DB /
StdItems.DB (GPL dump of the 2005 leak), i.e. the same provenance already cited
by MonsterTemplate/MonsterAppearanceTest for the verified RaceImg/Appr pairs:

  https://github.com/cjlaaa/Mir2-GeeM2  (branch main, commit 9981a8d7aecc…)
    数据库/GEEM2.db.sql   blob bfdc71d5943b7126a98604dca21bd62850f1cccc
    Envir/MonItems/<name>.txt

Outputs (UTF-8 TSV, regenerated deterministically — never hand-edit):

  java-server/world/src/main/resources/db/MagicDb.tsv       33 rows whose id/name pair is
                                                            shared by the 1.50 engine
  java-server/world/src/main/resources/db/MonsterDb.tsv    378 rows, native columns
  java-server/world/src/main/resources/db/StdItemsDb.tsv   686 rows, native columns
  java-server/world/src/main/resources/db/MonItems/*.txt   drop tables of the
                                                           engine-supported monsters

Usage:  python3 java-server/scripts/extract-geem2-db.py <GEEM2.db.sql> <MonItems-src-dir>

The MonItems source files are byte-faithful copies from the GEEM2 repo.  All ten
tables are UTF-8 upstream (a few *comment* lines were corrupted during upstream's
GBK→UTF-8 conversion; comments do not affect runtime); a legacy GBK-only file
would be decoded as GBK instead.  Names that do not resolve against the imported
StdItems catalog are kept and annotated (`# DEAD-ROW`), and wallet-gold rows are
annotated (`# GOLD-DROP`) so the loader can defer them to the gold-pile slice.
"""

from __future__ import annotations

import re
import sqlite3
import sys
from pathlib import Path

PROVENANCE_SOURCE = (
    "https://github.com/cjlaaa/Mir2-GeeM2 @ 9981a8d7aecc "
    "(数据库/GEEM2.db.sql blob bfdc71d5943b7126a98604dca21bd62850f1cccc)"
)
EXTRACTOR = "java-server/scripts/extract-geem2-db.py"

MONSTER_COLUMNS = [
    ("Name", "name"), ("Race", "race"), ("RaceImg", "raceImg"), ("Appr", "appr"),
    ("Lvl", "level"), ("Undead", "undead"), ("CoolEye", "coolEye"), ("Exp", "exp"),
    ("HP", "hp"), ("MP", "mp"), ("AC", "ac"), ("MAC", "mac"),
    ("DC", "dc"), ("DCMAX", "dcMax"), ("MC", "mc"), ("SC", "sc"),
    ("SPEED", "speed"), ("HIT", "hit"), ("WALK_SPD", "walkSpd"),
    ("WalkStep", "walkStep"), ("WalkWait", "walkWait"), ("ATTACK_SPD", "attackSpd"),
]

# The leaked 1.50 source and the later GEEM2 dump agree on both id and Chinese name for
# skills 1..33.  GEEM2 renumbered several later skills and also appends hero rows that reuse
# the same ids; importing either set as if it were 1.50 data would silently teach/cast the
# wrong skill.  Keep the mechanically provable intersection only until a matching Magic.DB
# is captured from the original server environment.
MAGIC_COLUMNS = [
    ("MagID", "magicId"), ("MagName", "name"),
    ("EffectType", "effectType"), ("Effect", "effect"),
    ("Spell", "spell"), ("Power", "power"), ("MaxPower", "maxPower"),
    ("DefSpell", "defSpell"), ("DefPower", "defPower"),
    ("DefMaxPower", "defMaxPower"), ("Job", "job"),
    ("NeedL1", "needL1"), ("L1Train", "l1Train"),
    ("NeedL2", "needL2"), ("L2Train", "l2Train"),
    ("NeedL3", "needL3"), ("L3Train", "l3Train"),
    ("Delay", "delay"), ("Descr", "description"),
]

STDITEMS_COLUMNS = [
    ("Idx", "idx"), ("Name", "name"), ("Stdmode", "stdMode"), ("Shape", "shape"),
    ("Weight", "weight"), ("Anicount", "aniCount"), ("Source", "source"),
    ("Reserved", "reserved"), ("Looks", "looks"), ("DuraMax", "duraMax"),
    ("Ac", "ac"), ("Ac2", "ac2"), ("Mac", "mac"), ("Mac2", "mac2"),
    ("Dc", "dc"), ("Dc2", "dc2"), ("Mc", "mc"), ("Mc2", "mc2"),
    ("Sc", "sc"), ("Sc2", "sc2"), ("Need", "need"), ("NeedLevel", "needLevel"),
    ("Price", "price"),
]

# Drop tables for the templates the Java engine currently implements (red line:
# the remaining 47 monsters are data-only until their AI slices land).
# monster name -> ASCII resource file name.  Classpath resources keep ASCII names so the
# loader works even when the JVM runs under a C/POSIX locale (sun.jnu.encoding=ANSI mangles
# non-ASCII file paths); the name->file indirection lives in the generated index.tsv.
MONITEM_FILES = {
    "鸡": "chicken.txt", "鹿": "deer.txt", "稻草人": "scarecrow.txt", "多钩猫": "hookcat.txt",
    "钉耙猫": "rakecat.txt", "洞蛆": "cavemaggot.txt", "蝎子": "scorpion.txt",
    "半兽人": "orc.txt", "半兽勇士": "orcwarrior.txt", "半兽战士": "orcfighter.txt",
}

GOLD_ITEM = "金币"


def fail(msg: str) -> None:
    print(f"extractor error: {msg}", file=sys.stderr)
    sys.exit(2)


def load_tables(sql_path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    conn.executescript(sql_path.read_text(encoding="utf-8"))
    return conn


def as_int(value, row) -> int:
    if value is None:
        fail(f"NULL integer in row {row!r}")
    if isinstance(value, int):
        return value
    text = str(value).strip()
    if text == "":
        return 0
    return int(text)


def write_tsv(path: Path, columns, rows, notes=()) -> None:
    header = [
        "# MIR2 authoritative database import — official 1.76 baseline (GEEM2 dump)",
        f"# source: {PROVENANCE_SOURCE}",
        f"# generated by: {EXTRACTOR} — do not hand-edit; re-run the extractor",
    ]
    header += [f"# note: {n}" for n in notes]
    header.append("# columns: " + "\t".join(alias for _, alias in columns))
    with path.open("w", encoding="utf-8", newline="\n") as out:
        out.write("\n".join(header) + "\n")
        for row in rows:
            out.write("\t".join(str(v) for v in row) + "\n")


def extract_magic(conn) -> list[list[str]]:
    cols = ", ".join(f'"{c}"' for c, _ in MAGIC_COLUMNS)
    rows = []
    for raw in conn.execute(
        f"SELECT {cols} FROM Magic WHERE MagID BETWEEN 1 AND 33 AND Descr = '' ORDER BY rowid"
    ):
        magic_id = as_int(raw[0], raw)
        name = str(raw[1]).strip()
        if len(name.encode("gbk")) > 12:
            fail(f"magic name does not fit TMagic String[12]: {name}")
        rows.append([magic_id, name] + [as_int(v, raw) for v in raw[2:-1]] + [str(raw[-1])])
    if len(rows) != 33 or [row[0] for row in rows] != list(range(1, 34)):
        fail("Magic 1.50 intersection is not the expected contiguous id range 1..33")
    return rows


def extract_monsters(conn) -> list[list[str]]:
    cols = ", ".join(f'"{c}"' for c, _ in MONSTER_COLUMNS)
    rows = []
    for raw in conn.execute(f"SELECT {cols} FROM Monster"):
        name = str(raw[0]).strip()
        rows.append([name] + [as_int(v, raw) for v in raw[1:]])
    names = [r[0] for r in rows]
    if len(names) != len(set(names)):
        dups = sorted({n for n in names if names.count(n) > 1})
        fail(f"duplicate monster names: {dups}")
    return rows


def extract_stditems(conn) -> tuple[list[list[str]], list[str]]:
    cols = ", ".join(f'"{c}"' for c, _ in STDITEMS_COLUMNS)
    rows, notes = [], []
    seen: dict[str, int] = {}
    for raw in conn.execute(f"SELECT {cols} FROM StdItems"):
        name = str(raw[1]).strip()
        if len(name.encode("gbk")) > 20:
            fail(f"item name does not fit TStdItem String[20]: {name}")
        if name in seen:
            notes.append(
                f"duplicate StdItems name {name!r} (idx {seen[name]} and {raw[0]}); first row wins by-name lookup"
            )
        else:
            seen[name] = as_int(raw[0], raw)
        rows.append([as_int(raw[0], raw), name] + [as_int(v, raw) for v in raw[2:]])
    return rows, sorted(set(notes))


MONITEM_LINE = re.compile(r"^\s*(\d+)\s*/\s*(\d+)\s+(.+?)(?:\s+(\d+))?\s*$")


def parse_monitems(path: Path) -> list[tuple[int, int, str, int]]:
    """Decode like Delphi (GBK ANSI) and parse `num/den name [count]` rows."""
    # Upstream GEEM2 MonItems files are stored as UTF-8 (verified for all ten
    # tables); a few comment lines were corrupted during their GBK→UTF-8
    # conversion but comments are skipped.  Fall back to GBK for any legacy file.
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        text = raw.decode("gbk")
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(";"):
            continue
        # Delphi allows quoted names: strip a surrounding pair of quotes.
        m = MONITEM_LINE.match(line)
        if not m:
            print(f"  [skip-unparsed] {path.name}: {line!r}", file=sys.stderr)
            continue
        num, den, name, count = int(m.group(1)), int(m.group(2)), m.group(3), m.group(4)
        name = name.strip()
        if name.startswith('"') and name.endswith('"') and len(name) >= 2:
            name = name[1:-1]
        rows.append((num, den, name, int(count) if count else 1))
    return rows


def normalise_monitems(src_dir: Path, out_dir: Path, std_names: set[str]) -> list[str]:
    notes = []
    out_dir.mkdir(parents=True, exist_ok=True)
    index_lines = [
        "# MonItems index — monster name to ASCII resource file (generated; do not hand-edit)",
        f"# source: {PROVENANCE_SOURCE} — Envir/MonItems/",
        "# columns: monsterName<TAB>fileName",
    ]
    for name, file_name in MONITEM_FILES.items():
        src = src_dir / f"{name}.txt"
        if not src.exists():
            fail(f"MonItems source missing: {src}")
        rows = parse_monitems(src)
        resolved_text = []
        for num, den, item, count in rows:
            if num != 1:
                notes.append(f"{name}.txt: numerator {num} != 1 for {item!r} (unsupported drop shape)")
            tag = ""
            if item == GOLD_ITEM:
                tag = "GOLD-DROP"
            elif item not in std_names:
                tag = "DEAD-ROW"
                notes.append(f"{name}.txt: {item!r} has no StdItems row (kept as faithful dead row)")
            resolved_text.append(f"{num}/{den}\t{item}\t{count}\t{tag}".rstrip())
        header = [
            f"# MonItems drop table for {name} — official 1.76 baseline (GEEM2 dump)",
            f"# source: {PROVENANCE_SOURCE} — Envir/MonItems/{name}.txt (stored upstream as UTF-8; comment-line corruption preserved only in history)",
            f"# generated by: {EXTRACTOR} — do not hand-edit; re-run the extractor",
            "# columns: num/den<TAB>itemName<TAB>count[<TAB>annotation GOLD-DROP|DEAD-ROW] ; '#' lines are comments",
        ]
        (out_dir / file_name).write_text("\n".join(header + resolved_text) + "\n", encoding="utf-8")
        index_lines.append(f"{name}\t{file_name}")
    (out_dir / "index.tsv").write_text("\n".join(index_lines) + "\n", encoding="utf-8")
    return notes


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: extract-geem2-db.py <GEEM2.db.sql> <MonItems-src-dir>")
    sql_path = Path(sys.argv[1])
    monitems_src = Path(sys.argv[2])
    out_db = Path(__file__).resolve().parent.parent / "world/src/main/resources/db"

    conn = load_tables(sql_path)
    magic = extract_magic(conn)
    monsters = extract_monsters(conn)
    stditems, std_notes = extract_stditems(conn)
    std_names = {r[1] for r in stditems}

    out_db.mkdir(parents=True, exist_ok=True)
    write_tsv(out_db / "MagicDb.tsv", MAGIC_COLUMNS, magic, notes=[
        "only id/name pairs 1..33 shared by the 1.50 source and GEEM2 are imported",
        "GEEM2 hero rows and renumbered post-33 skills are deliberately excluded",
    ])
    write_tsv(out_db / "MonsterDb.tsv", MONSTER_COLUMNS, monsters, notes=["rows in original dump order"])
    write_tsv(out_db / "StdItemsDb.tsv", STDITEMS_COLUMNS, stditems,
              notes=["rows in original dump order (= Idx order)"] + std_notes)
    mon_notes = normalise_monitems(monitems_src, out_db / "MonItems", std_names)

    for n in std_notes + mon_notes:
        print(f"note: {n}")
    print(f"wrote {len(magic)} magic definitions, {len(monsters)} monsters, "
          f"{len(stditems)} std items, {len(MONITEM_FILES)} drop tables -> {out_db}")


if __name__ == "__main__":
    main()
