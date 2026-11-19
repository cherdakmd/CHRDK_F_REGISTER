#!/usr/bin/env python3
"""Детерминированная сборка CHRDK Treasure Addon — надстройки над Mine Treasure.

Исходники пака лежат в `datapacks/chrdk-treasure-addon/src/` (в git), скрипт
собирает `datapacks/chrdk-treasure-addon/chrdk-treasure-addon-1.0.zip` и
сверяет уже собранный ZIP с исходниками через `--check` (CI-проверка).

Принципы пака (см. CHRDK-TREASURE-ADDON.md):
  • все файлы — только в своих путях `data/chrdk_treasure/*`, поэтому пак
    не конфликтует с Mine Treasure: ни одного переопределённого файла;
  • теги `data/minecraft/tags/function/*` НЕ используются (инициализация не
    нужна) — load/tick Mine Treasure не трогаются вовсе;
  • предметы несут PDC-метку `PublicBukkitValues."chrdk:mine_treasure_item" = "1"`,
    и CHRDK_Core относится к ним как к наградам Mine Treasure.

`--check` требует, чтобы содержимое ZIP точно совпадало с src-деревом
(имёна и байты файлов), и прогоняет инварианты: валидность JSON, метку PDC
на каждом предмете, разрешимость ссылок `chrdk_treasure:*` между
advancement/function/predicate/loot/item_modifier.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADDON_DIR = ROOT / "datapacks" / "chrdk-treasure-addon"
DEFAULT_SOURCE = ADDON_DIR / "src"
DEFAULT_OUTPUT = ADDON_DIR / "chrdk-treasure-addon-1.0.zip"
ZIP_PREFIX = ""  # файлы кладутся в корень архива (pack.mcmeta в корне)
PACK_FORMAT = [121, 1]
PDC_MARKER = "chrdk:mine_treasure_item"

REGISTRIES = {
    "function": ("data/chrdk_treasure/function/", ".mcfunction"),
    "predicate": ("data/chrdk_treasure/predicate/", ".json"),
    "loot_table": ("data/chrdk_treasure/loot_table/", ".json"),
    "item_modifier": ("data/chrdk_treasure/item_modifier/", ".json"),
    "advancement": ("data/chrdk_treasure/advancement/", ".json"),
}
MC_REFS = (
    (re.compile(r"\bfunction\s+chrdk_treasure:([a-z0-9_./-]+)"), "function"),
    (re.compile(r"\bpredicate\s+chrdk_treasure:([a-z0-9_./-]+)"), "predicate"),
    (re.compile(r"\bloot\s+\S+\s+\S+\s+loot\s+chrdk_treasure:([a-z0-9_./-]+)"), "loot_table"),
    (re.compile(r"\bitem\s+modify\s+entity\s+\S+\s+\S+\s+chrdk_treasure:([a-z0-9_./-]+)"),
     "item_modifier"),
    (re.compile(r"\bonly\s+chrdk_treasure:([a-z0-9_./-]+)"), "advancement"),
)


class BuildError(Exception):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def source_files(source: Path) -> dict[str, bytes]:
    if not source.is_dir():
        raise BuildError(f"нет каталога исходников: {source}")
    out: dict[str, bytes] = {}
    for path in sorted(source.rglob("*")):
        if path.is_file():
            out[path.relative_to(source).as_posix()] = path.read_bytes()
    if "pack.mcmeta" not in out:
        raise BuildError("в исходниках нет pack.mcmeta")
    return out


def build_bytes(source: Path = DEFAULT_SOURCE) -> bytes:
    """Собирает ZIP детерминированно: сортированные записи, фиксированные дата/права."""
    import io

    files = source_files(source)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(files):
            info = zipfile.ZipInfo(ZIP_PREFIX + name, date_time=(2026, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, files[name])
    return buffer.getvalue()


def json_files(files: dict[str, bytes]) -> dict[str, object]:
    parsed = {}
    for name, data in files.items():
        if name.endswith(".json") or name.endswith(".mcmeta"):
            try:
                parsed[name] = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, ValueError) as error:
                raise BuildError(f"{name}: JSON не читается: {error}") from None
    return parsed


def walk_entries(node, found: list) -> None:
    """Собирает все minecraft:item-записи лут-таблиц (включая вложенные)."""
    if isinstance(node, dict):
        if node.get("type") == "minecraft:item":
            found.append(node)
        for value in node.values():
            walk_entries(value, found)
    elif isinstance(node, list):
        for value in node:
            walk_entries(value, found)


def check_references(files: dict[str, bytes], parsed: dict[str, object]) -> list:
    """Все ссылки chrdk_treasure:* из функций и JSON обязаны разрешаться в файлы."""
    problems = []
    known = {
        kind: {
            name[len(prefix): -len(suffix)]
            for name in files
            if name.startswith(prefix) and name.endswith(suffix)
        }
        for kind, (prefix, suffix) in REGISTRIES.items()
    }

    def resolve(kind: str, ref: str, where: str) -> None:
        if ref not in known[kind]:
            problems.append(f"{where}: {kind} chrdk_treasure:{ref} не найден")

    for name, data in files.items():
        if not name.endswith(".mcfunction"):
            continue
        text = data.decode("utf-8")
        for pattern, kind in MC_REFS:
            for match in pattern.finditer(text):
                resolve(kind, match.group(1), name)

    for name, doc in parsed.items():
        if "/advancement/" in name:
            fn = doc.get("rewards", {}).get("function")
            if isinstance(fn, str) and fn.startswith("chrdk_treasure:"):
                resolve("function", fn.split(":", 1)[1], name)
        if "/loot_table/" in name:
            entries = []
            walk_entries(doc, entries)
            for entry in doc.get("pools", []):
                for child in entry.get("entries", []):
                    if child.get("type") == "minecraft:loot_table":
                        value = child.get("value", "")
                        if value.startswith("chrdk_treasure:"):
                            resolve("loot_table", value.split(":", 1)[1], name)
    return problems


def invariants(source: Path = DEFAULT_SOURCE) -> dict:
    """Проверяет исходники пака; бросает BuildError при нарушении инварианта."""
    files = source_files(source)
    parsed = json_files(files)

    pack = parsed.get("pack.mcmeta")
    if not isinstance(pack, dict):
        raise BuildError("pack.mcmeta: нет описания пака")
    fmt = pack.get("pack", {})
    if fmt.get("min_format") != PACK_FORMAT or fmt.get("max_format") != PACK_FORMAT:
        raise BuildError(f"pack.mcmeta: формат {fmt.get('min_format')}/{fmt.get('max_format')}"
                         f" не совпадает с Mine Treasure {PACK_FORMAT}")

    # Никаких load/tick тегов: пак не должен пересекаться с Mine Treasure.
    for name in files:
        if name.startswith("data/minecraft/tags/"):
            raise BuildError(f"{name}: теги minecraft запрещены — пак живёт только в data/chrdk_treasure")

    items = []
    resources = []
    for name, doc in parsed.items():
        if "/loot_table/" in name:
            found = []
            walk_entries(doc, found)
            for entry in found:
                has_components = any(m.get("type") == "minecraft:set_components"
                                     for m in entry.get("modifier", []))
                (items if has_components else resources).append(entry)
    if not items:
        raise BuildError("в лут-таблицах нет ни одного сокровища с компонентами")
    for entry in items:
        comps = None
        for mod in entry.get("modifier", []):
            if mod.get("type") == "minecraft:set_components":
                comps = mod.get("components", {})
        if comps is None:
            raise BuildError(f"предмет {entry.get('name')}: нет set_components")
        custom = comps.get("minecraft:custom_data", {})
        if custom.get("PublicBukkitValues", {}).get(PDC_MARKER) != "1":
            raise BuildError(f"предмет {entry.get('name')}: нет PDC-метки {PDC_MARKER}")
        if not custom.get("cht_item"):
            raise BuildError(f"предмет {entry.get('name')}: нет cht_item в custom_data")
        if "minecraft:custom_name" not in comps or "minecraft:lore" not in comps:
            raise BuildError(f"предмет {entry.get('name')}: нет имени или лора")

    problems = check_references(files, parsed)
    if problems:
        raise BuildError("; ".join(problems))

    mcfunction = sum(1 for name in files if name.endswith(".mcfunction"))
    jsons = sum(1 for name in files if name.endswith(".json"))
    return {
        "files": len(files),
        "json_files": jsons,
        "mcfunction_files": mcfunction,
        "treasures": len(items),
        "resources": len(resources),
        "pdc_markers": sum(1 for e in items for m in e.get("modifier", [])
                           if m.get("type") == "minecraft:set_components"
                           and m.get("components", {}).get("minecraft:custom_data", {})
                           .get("PublicBukkitValues", {}).get(PDC_MARKER) == "1"),
    }


def check(output: Path = DEFAULT_OUTPUT, source: Path = DEFAULT_SOURCE) -> dict:
    """Сверяет собранный ZIP с исходниками и инвариантами."""
    report = invariants(source)
    if not output.is_file():
        raise BuildError(f"нет собранного ZIP: {output}")
    files = source_files(source)
    with zipfile.ZipFile(output) as archive:
        names = [name for name in archive.namelist() if not name.endswith("/")]
        if sorted(names) != sorted(files):
            missing = sorted(set(files) - set(names))
            extra = sorted(set(names) - set(files))
            raise BuildError(f"ZIP не совпадает с src: нет {missing}, лишние {extra}")
        for name in names:
            data = archive.read(name)
            if data != files[name]:
                raise BuildError(f"{name}: содержимое ZIP отличается от src")
    report["zip_sha256"] = sha256_bytes(output.read_bytes())
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Сборка CHRDK Treasure Addon")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true",
                        help="проверить собранный ZIP против исходников (CI)")
    args = parser.parse_args(argv)
    try:
        if args.check:
            report = check(args.output, args.source)
            print("CHRDK Treasure Addon: ZIP совпадает с исходниками, инварианты соблюдены")
        else:
            data = build_bytes(args.source)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(data)
            report = check(args.output, args.source)
            print(f"CHRDK Treasure Addon собран: {args.output.name}"
                  f" ({report['files']} файлов, {report['treasures']} сокровищ)")
        for key in sorted(report):
            print(f"  {key}={report[key]}")
        return 0
    except BuildError as error:
        print(f"ОШИБКА: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
