#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Localize the player-visible text of a Minecraft datapack.

The pack stores its text as Minecraft *text components* spread over
`data/**/*.json` (loot tables, advancements, enchantments, predicates) and
inline JSON/SNBT inside `data/**/*.mcfunction` (tellraw/title/actionbar,
CustomName, click-event menus).  There is no `assets/*/lang/*.json` in a
datapack, so a translation means rewriting those literal strings in place --
which is exactly what this tool does.

Usage
-----
    # 1. Pull every translatable string out of a pack and create a glossary
    #    pre-filled with the automatic translations (safe to edit by hand):
    python3 localize.py extract --source mine-treasure.zip --out ru.json

    # 2. Rebuild the pack with the glossary applied:
    python3 localize.py build --source mine-treasure.zip \
                              --glossary ru.json --out mine-treasure-ru.zip

    # 3. See what is still English (or just check coverage):
    python3 localize.py report --source mine-treasure.zip --glossary ru.json

`--source` may be a .zip/.txt-renamed-zip archive OR an unpacked directory.
Only text-component values are touched; item IDs, scoreboard names, tags,
functions, predicates and every technical identifier are left byte-for-byte.
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import shutil
import sys
import tempfile
import zipfile

# --------------------------------------------------------------------------
# Which JSON keys carry human-readable text.
# --------------------------------------------------------------------------

# Always a text component (or a plain text string).
ALWAYS_TEXT_KEYS = {"text", "fallback", "item_name", "custom_name"}
# Plain strings in advancement displays / book titles / enchantment descriptions.
PLAIN_TEXT_KEYS = {"title", "description"}
# `name` / `lore` are only translated inside `set_name` / `set_lore` functions.
CONDITIONAL_TEXT_KEYS = {"name": "set_name", "lore": "set_lore"}

# Values that must never be treated as human text even if they sit under a
# text key (technical markers used by the datapack itself).
TECHNICAL_RE = re.compile(
    r"^(?:[#$@][A-Za-z0-9_.]+|[a-z0-9_.\-]+:[a-z0-9_./\-]+|"
    r"[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)$"
)

# Inline JSON/SNBT text components inside .mcfunction files.
INLINE_RE = re.compile(
    r"(?P<q>[\"'])(?P<key>text|fallback|item_name|custom_name|title|description)"
    r"(?P=q)\s*:\s*(?P<val>\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*')"
)

MOJIBAKE_MARKERS = ("Ã", "â€", "Â")


# --------------------------------------------------------------------------
# Automatic translations for the very repetitive families of strings.
# --------------------------------------------------------------------------

RARITY = {
    "Common": ("обычное", "обычного"),
    "Rare": ("редкое", "редкого"),
    "Epic": ("эпическое", "эпического"),
    "Legendary": ("легендарное", "легендарного"),
    "Mythical": ("мифическое", "мифического"),
}

RARITY_ADJ = {
    "Common": "обычных",
    "Rare": "редких",
    "Epic": "эпических",
    "Legendary": "легендарных",
    "Mythical": "мифических",
}

# Genitive form, used after "сокровище".
BIOME = {
    "Armor": "доспехов",
    "Badlands": "Бесплодных земель",
    "Basalt": "Базальтовых дельт",
    "Basalt Deltas": "Базальтовых дельт",
    "Crimson": "Багрового леса",
    "Crimson F.": "Багрового леса",
    "Crimson Forest": "Багрового леса",
    "D. Forest": "Тёмного леса",
    "Dark Forest": "Тёмного леса",
    "Deep Dark": "Глубинного Тёмного",
    "Default": "обычных биомов",
    "Desert": "пустыни",
    "Dripstone": "Сталактитовой пещеры",
    "End": "Края",
    "Flower": "Цветочного леса",
    "Ice": "ледяных биомов",
    "Jungle": "джунглей",
    "Lush Cave": "Пышных пещер",
    "Lush Caves": "Пышных пещер",
    "Mountain": "гор",
    "Mushroom": "Грибного леса",
    "Nether": "Нижнего мира",
    "Ocean": "океана",
    "Savanna": "саванны",
    "Soul Valley": "Долины душ",
    "Swamp": "болота",
    "Taiga": "тайги",
    "Utility": "предметов",
    "Warped": "Искажённого леса",
    "Warped F.": "Искажённого леса",
    "Warped Forest": "Искажённого леса",
    "Weapon": "оружия",
    "Wind": "ветреных земель",
}

STAT_LABELS = {
    "Attack Damage": "урона атаки",
    "Attack Speed": "скорости атаки",
    "Reach": "радиуса действия",
    "Armor Toughness": "прочности брони",
    "Armor": "брони",
    "Entity Reach": "радиуса до сущностей",
}

_NUM = r"[0-9]+(?:\.[0-9]+)?"


def _rule_treasure(s: str):
    m = re.fullmatch(r"(Common|Rare|Epic|Legendary|Mythical)\s+(.+?)\s+Treasure", s)
    if not m:
        return None
    rarity, biome = m.group(1), m.group(2)
    if biome not in BIOME:
        return None
    return f"{RARITY[rarity][0]} сокровище {BIOME[biome]}"


def _rule_stat(s: str):
    m = re.fullmatch(r"([+-]?" + _NUM + r")\s+(Attack Damage|Attack Speed|Reach|Armor Toughness|Armor)", s)
    if m:
        return f"{m.group(1)} {STAT_LABELS[m.group(2)]}"
    m = re.fullmatch(r"([+-]?" + _NUM + r")% Speed", s)
    if m:
        return f"{m.group(1)}% скорости"
    return None


def _rule_find_first(s: str):
    m = re.fullmatch(
        r"Find your first (?:(Common|Rare|Epic|Legendary|Mythical)\s+)?(?:(.+?)\s+)?Treasure!", s
    )
    if not m:
        return None
    rarity, biome = m.group(1), m.group(2)
    if rarity is None:
        return "Найдите своё первое сокровище!"
    if biome and biome in BIOME:
        return f"Найдите первое {RARITY[rarity][0]} сокровище {BIOME[biome]}!"
    return f"Найдите своё первое {RARITY[rarity][0]} сокровище!"


def _rule_find_count(s: str):
    m = re.fullmatch(r"Find ([0-9]+) (Common|Rare|Epic|Legendary|Mythical) Treasures?!(.*)", s)
    if not m:
        return None
    n, rarity, tail = m.group(1), m.group(2), m.group(3)
    extra = ""
    if tail.strip():
        extra = " Интересно, сколько же времени это у вас заняло..."
    return f"Найдите {n} {RARITY_ADJ[rarity]} сокровищ!{extra}"


RULES = (_rule_treasure, _rule_stat, _rule_find_first, _rule_find_count)


def fix_mojibake(s: str) -> str:
    """Repair strings that were stored as double-encoded UTF-8 in the pack."""
    if not any(mark in s for mark in MOJIBAKE_MARKERS):
        return s
    try:
        fixed = s.encode("latin-1").decode("utf-8")
        return fixed if fixed != s else s
    except (UnicodeEncodeError, UnicodeDecodeError):
        return s


_PAD_LEAD = re.compile(r"^[ \t]*")
_PAD_TRAIL = re.compile(r"[ \t]*$")


def _with_padding(original: str, translated: str) -> str:
    lead = _PAD_LEAD.match(original).group(0)
    trail = _PAD_TRAIL.search(original).group(0)
    return lead + translated.strip(" \t") + trail


def should_translate(s: str) -> bool:
    if not s or not s.strip():
        return False
    if TECHNICAL_RE.fullmatch(s.strip()):
        return False
    # Needs at least one ASCII letter to be English prose.
    if not re.search(r"[A-Za-z]", s):
        return False
    # Brand / selector / command fragments.
    if s.strip() in {"Mine Treasure", "Frozytime", "@s", "mt_stats"}:
        return False
    return True


class Translator:
    def __init__(self, glossary: dict | None = None):
        self.glossary = glossary or {}
        self.hits: collections.Counter = collections.Counter()
        self.misses: collections.Counter = collections.Counter()

    def __call__(self, s: str) -> str:
        if not should_translate(s):
            return s
        repaired = fix_mojibake(s)
        core = repaired.strip(" \t")
        for candidate in (repaired, core, s, s.strip(" \t")):
            if candidate in self.glossary and self.glossary[candidate]:
                self.hits[candidate] += 1
                return _with_padding(s, self.glossary[candidate])
        for rule in RULES:
            val = rule(core)
            if val:
                self.hits[core] += 1
                return _with_padding(s, val)
        if repaired != s:
            self.misses[repaired] += 1
            return repaired  # at least fix the mojibake
        self.misses[s] += 1
        return s

    def suggestion(self, s: str) -> str:
        """Return the automatic value used for `extract` (never returns None)."""
        val = self(s)
        return val if val != s else ""


# --------------------------------------------------------------------------
# Walking the pack
# --------------------------------------------------------------------------


def walk_json(node, translate, parent_type=None):
    """Translate text-component strings inside a parsed JSON tree, in place."""
    if isinstance(node, dict):
        typ = node.get("type")
        for key, val in list(node.items()):
            if key in ALWAYS_TEXT_KEYS and isinstance(val, str):
                node[key] = translate(val)
            elif key in PLAIN_TEXT_KEYS and isinstance(val, str):
                node[key] = translate(val)
            elif key in CONDITIONAL_TEXT_KEYS and isinstance(val, str):
                if isinstance(typ, str) and typ.endswith(CONDITIONAL_TEXT_KEYS[key]):
                    node[key] = translate(val)
            else:
                walk_json(val, translate, typ)
    elif isinstance(node, list):
        for item in node:
            walk_json(item, translate, parent_type)


def transform_json(raw: str, translate) -> str:
    obj = json.loads(raw)
    walk_json(obj, translate)
    return json.dumps(obj, ensure_ascii=False, indent=2) + "\n"


def transform_mcfunction(raw: str, translate) -> str:
    def repl(m: re.Match) -> str:
        quote = m.group("q")
        val = m.group("val")
        try:
            decoded = json.loads(val) if quote == '"' else val[1:-1]
        except (ValueError, TypeError):
            return m.group(0)
        new = translate(decoded)
        if new == decoded:
            return m.group(0)
        encoded = json.dumps(new, ensure_ascii=False) if quote == '"' else "'" + new.replace("'", "\\'") + "'"
        return m.group(0)[: m.start("val") - m.start(0)] + encoded

    return INLINE_RE.sub(repl, raw)


def transform_file(path: str, raw: str, translate) -> str:
    name = os.path.basename(path)
    if name.endswith(".json") or name.endswith(".mcmeta"):
        return transform_json(raw, translate)
    if name.endswith(".mcfunction"):
        return transform_mcfunction(raw, translate)
    return raw


TEXT_EXTENSIONS = (".json", ".mcmeta", ".mcfunction")


def iter_source_files(root: str):
    for base, _dirs, files in os.walk(root):
        for f in sorted(files):
            path = os.path.join(base, f)
            yield os.path.relpath(path, root).replace(os.sep, "/"), path


def unpack_source(source: str) -> tuple[str, tempfile.TemporaryDirectory | None, list[str]]:
    """Return (directory, temp-handle, zip-member-order). Always work on a dir."""
    if os.path.isdir(source):
        return source, None, []
    tmp = tempfile.TemporaryDirectory(prefix="datapack-i18n-")
    order = []
    with zipfile.ZipFile(source) as zf:
        for info in zf.infolist():
            order.append(info.filename)
        zf.extractall(tmp.name)
    return tmp.name, tmp, order


def _decode(data: bytes) -> str:
    return data.decode("utf-8-sig")


def _safe_decode(m: re.Match) -> str:
    quote, val = m.group("q"), m.group("val")
    try:
        return json.loads(val) if quote == '"' else val[1:-1]
    except (ValueError, TypeError):
        return ""


# --------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------


def cmd_extract(args):
    root, tmp, _ = unpack_source(args.source)
    try:
        found, total = collect_strings_dir(root)
    finally:
        if tmp:
            tmp.cleanup()
    translator = Translator(load_glossary(args.glossary) if args.glossary else {})
    out = {}
    for s in sorted(found, key=lambda x: (x.strip(" \t").lower(), x)):
        auto = translator.suggestion(s)
        out[s] = auto
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    filled = sum(1 for v in out.values() if v)
    print(f"Extracted {len(out)} unique strings ({total} occurrences).")
    print(f"Auto-translated: {filled}/{len(out)} ({100*filled/max(len(out),1):.1f}%).")
    print(f"Glossary written to {args.out} — empty values still need a translation.")


def collect_strings_dir(source: str) -> tuple[dict, int]:
    found: collections.Counter = collections.Counter()

    def gather(s: str) -> str:
        if should_translate(s):
            found[s] += 1
        return s

    for member, path in iter_source_files(source):
        if not member.endswith(TEXT_EXTENSIONS):
            continue
        with open(path, "rb") as fh:
            raw = _decode(fh.read())
        if member.endswith(".json") or member.endswith(".mcmeta"):
            walk_json(json.loads(raw), gather)
        else:
            for m in INLINE_RE.finditer(raw):
                gather(_safe_decode(m))
    return dict(found), sum(found.values())


def load_glossary(path: str | None) -> dict:
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    return {k: v for k, v in data.items() if v}


def cmd_build(args):
    glossary = load_glossary(args.glossary)
    translator = Translator(glossary)

    root, tmp, order = unpack_source(args.source)
    try:
        if args.out.endswith(".zip") or args.out.endswith(".jar"):
            work = tempfile.mkdtemp(prefix="datapack-i18n-out-")
            _build_from_dir(args, translator, root, work)
            _zip_dir(work, args.out, order)
            shutil.rmtree(work)
        else:
            _build_from_dir(args, translator, root, args.out)
    finally:
        if tmp:
            tmp.cleanup()

    uniq = len(translator.misses)
    hits = sum(translator.hits.values())
    total = hits + sum(translator.misses.values())
    print(f"Replacements: {hits}/{total} occurrences "
          f"({100*hits/max(total,1):.1f}%), {uniq} distinct strings still untranslated.")
    if uniq:
        with open(args.missing, "w", encoding="utf-8") as fh:
            json.dump(dict(sorted(translator.misses.items(), key=lambda x: -x[1])),
                      fh, ensure_ascii=False, indent=2)
            fh.write("\n")
        print(f"Untranslated strings written to {args.missing}")
    print(f"Output: {args.out}")


def _zip_dir(root: str, out: str, order: list[str]):
    """Write a zip keeping the original member order, including directory entries."""
    present = {os.path.relpath(os.path.join(b, f), root).replace(os.sep, "/")
               for b, _d, fs in os.walk(root) for f in fs}
    dirs = {os.path.relpath(b, root).replace(os.sep, "/") + "/"
            for b, _d, _fs in os.walk(root) if os.path.relpath(b, root) != "."}
    names = [n for n in order if (n.endswith("/") and n in dirs) or (not n.endswith("/") and n in present)]
    names += sorted((present | dirs) - set(names))
    with zipfile.ZipFile(out, "w") as zout:
        for name in names:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 0
            if name.endswith("/"):
                info.external_attr = (0o40775 << 16) | 0x10
                zout.writestr(info, b"")
            else:
                with open(os.path.join(root, name), "rb") as fh:
                    zout.writestr(info, fh.read())


def _build_from_dir(args, translator, source: str, out: str):
    if os.path.abspath(source) == os.path.abspath(out):
        raise SystemExit("--out must differ from --source when --source is a directory")
    if os.path.exists(out):
        shutil.rmtree(out)
    shutil.copytree(source, out)
    for base, _dirs, files in os.walk(out):
        for f in files:
            path = os.path.join(base, f)
            if not f.endswith(TEXT_EXTENSIONS):
                continue
            with open(path, "r", encoding="utf-8-sig", newline="") as fh:
                raw = fh.read()
            try:
                new = transform_file(f, raw, translator)
            except Exception as exc:  # pragma: no cover - defensive
                print(f"  ! {path}: {exc}; copied unchanged", file=sys.stderr)
                continue
            if new != raw:
                with open(path, "w", encoding="utf-8", newline="") as fh:
                    fh.write(new)


def cmd_report(args):
    root, tmp, _ = unpack_source(args.source)
    try:
        found, total = collect_strings_dir(root)
    finally:
        if tmp:
            tmp.cleanup()
    translator = Translator(load_glossary(args.glossary))
    missing = []
    for s, n in found.items():
        if translator(s) == s and should_translate(s):
            missing.append((n, s))
    missing.sort(key=lambda x: (-x[0], x[1]))
    print(f"Unique strings: {len(found)}  occurrences: {total}")
    print(f"Still untranslated: {len(missing)} unique / {sum(n for n, _ in missing)} occurrences")
    for n, s in missing[: args.limit]:
        print(f"  {n:4d}  {s!r}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({s: "" for _, s in missing}, fh, ensure_ascii=False, indent=2)
            fh.write("\n")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)

    e = sub.add_parser("extract", help="create an editable glossary from a pack")
    e.add_argument("--source", required=True, help="pack .zip or unpacked directory")
    e.add_argument("--out", required=True, help="output glossary .json")
    e.add_argument("--glossary", help="existing glossary to preserve manual edits from")
    e.set_defaults(func=cmd_extract)

    b = sub.add_parser("build", help="apply a glossary and rebuild the pack")
    b.add_argument("--source", required=True, help="pack .zip or unpacked directory")
    b.add_argument("--glossary", required=True, help="glossary .json")
    b.add_argument("--out", required=True, help="output .zip or directory")
    b.add_argument("--missing", default="untranslated.json", help="where to dump leftovers")
    b.set_defaults(func=cmd_build)

    r = sub.add_parser("report", help="show translation coverage")
    r.add_argument("--source", required=True, help="pack .zip or unpacked directory")
    r.add_argument("--glossary", required=True, help="glossary .json")
    r.add_argument("--limit", type=int, default=40)
    r.add_argument("--json", help="write the remaining strings to this .json")
    r.set_defaults(func=cmd_report)

    args = p.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
