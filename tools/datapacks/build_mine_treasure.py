#!/usr/bin/env python3
"""Детерминированная сборка патченого Mine Treasure v1.6.5 RU для CHRDK.

Исходный присланный ZIP не публикуется: скрипт читает его по --source и
собирает `datapacks/mine-treasure-ru/mine-treasure-1.6.5-ru.zip` с исправлениями
совместимости и корневым маркером `CHRDK-COMPAT.md`. Функции, scoreboard,
LICENSE.md, pack.mcmeta и pack.png переносятся побайтово; правятся только JSON.

Исправления (ответы владельца q11=fix_all, q10=merge):
  • "attribute": "water_movement_efficiency" → "minecraft:water_movement_efficiency";
  • "slot": ["mainhand"|"offhand"] → "slot": "mainhand"|"offhand";
  • в каждый `minecraft:set_components` добавляется PDC-метка CHRDK внутри
    `minecraft:custom_data.PublicBukkitValues."chrdk:mine_treasure_item" = "1"`.
    По ней CHRDK_Core отличает награды Mine Treasure от ванильного дропа и не
    дублирует их perk-эффектами; уже выданные предметы без метки не мигрируются.

`--check` проверяет уже собранный ZIP по tools/datapacks/mine_treasure_manifest.json
без исходного архива — это и есть CI-проверка.
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
DEFAULT_OUTPUT = ROOT / "datapacks/mine-treasure-ru/mine-treasure-1.6.5-ru.zip"
DEFAULT_MANIFEST = ROOT / "tools/datapacks/mine_treasure_manifest.json"
COMPAT_MARKER = "CHRDK-COMPAT.md"
BLOCK_TABLE_PREFIX = "data/minecraft/loot_table/blocks/"
UNPREFIXED = re.compile(r'("attribute"\s*:\s*)"water_movement_efficiency"')
SLOT_ARRAY = re.compile(r'("slot"\s*:\s*)\[\s*"([a-z_]+)"\s*\]')
PDC_CONTAINER = "PublicBukkitValues"
PDC_KEY = "chrdk:mine_treasure_item"
PDC_VALUE = "1"
EXPECTED_UNPREFIXED = 29
EXPECTED_SLOT_ARRAYS = 5
EXPECTED_BLOCK_TABLES = 43
EXPECTED_JSON = 825
EXPECTED_SET_ATTRIBUTES = 1132
EXPECTED_SET_COMPONENTS = 1507
INVARIANT_ENTRIES = (
    "data/mt/function/init.mcfunction",
    "data/mt/function/tick.mcfunction",
    "data/minecraft/tags/function/load.json",
    "data/minecraft/tags/function/tick.json",
    "LICENSE.md",
    COMPAT_MARKER,
)


class BuildError(Exception):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_json(text: str, name: str):
    """Строгий разбор: дубликаты ключей и NaN/Infinity ломают сборку."""
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError(f"дубликат ключа {key!r}")
            result[key] = value
        return result

    def constant(value):
        raise ValueError(f"недопустимая константа {value!r}")

    try:
        return json.loads(text, object_pairs_hook=pairs, parse_constant=constant)
    except (json.JSONDecodeError, ValueError) as error:
        raise BuildError(f"невалидный JSON {name}: {error}") from error


def dump_json(data) -> bytes:
    return (json.dumps(data, ensure_ascii=False, indent=2, sort_keys=False) + "\n").encode("utf-8")


def patch_document(data) -> dict:
    """Точечно добавляет PDC-метку во все set_components; возвращает счётчики."""
    stats = {"set_components": 0, "markers": 0, "markers_existing": 0}

    def walk(value):
        if isinstance(value, dict):
            if value.get("type") == "minecraft:set_components":
                stats["set_components"] += 1
                components = value.get("components")
                if not isinstance(components, dict):
                    components = {}
                    value["components"] = components
                custom = components.get("minecraft:custom_data")
                if not isinstance(custom, dict):
                    custom = {}
                    components["minecraft:custom_data"] = custom
                pdc = custom.get(PDC_CONTAINER)
                if not isinstance(pdc, dict):
                    pdc = {}
                    custom[PDC_CONTAINER] = pdc
                if pdc.get(PDC_KEY) == PDC_VALUE:
                    stats["markers_existing"] += 1
                else:
                    pdc[PDC_KEY] = PDC_VALUE
                    stats["markers"] += 1
            for nested in value.values():
                walk(nested)
        elif isinstance(value, list):
            for nested in value:
                walk(nested)

    walk(data)
    return stats


def patch_json(text: str, name: str) -> tuple[bytes, int, int, dict]:
    fixed_attributes = len(UNPREFIXED.findall(text))
    fixed_slots = len(SLOT_ARRAY.findall(text))
    patched = UNPREFIXED.sub(r'\1"minecraft:water_movement_efficiency"', text)
    patched = SLOT_ARRAY.sub(r'\1"\2"', patched)
    document = parse_json(patched, name)
    stats = patch_document(document)
    return dump_json(document), fixed_attributes, fixed_slots, stats


def block_table_keys(names: list[str]) -> list[str]:
    keys = []
    for name in names:
        if name.startswith(BLOCK_TABLE_PREFIX) and name.endswith(".json"):
            keys.append("minecraft:blocks/" + Path(name).stem)
    return sorted(keys)


def compat_marker(source_hash: str, block_keys: list[str]) -> str:
    return "\n".join([
        "# CHRDK ↔ Mine Treasure v1.6.5 RU",
        "",
        "Этот ZIP собран tools/datapacks/build_mine_treasure.py из присланного",
        "русского перевода. В JSON исправлены 29 полей attribute и 5 полей slot,",
        "а также добавлена PDC-метка chrdk:mine_treasure_item во все",
        "minecraft:set_components. Функции, scoreboard, LICENSE.md, pack.mcmeta",
        "и pack.png перенесены без правок.",
        "",
        f"SHA-256 исходного архива: {source_hash}",
        f"Переопределено ванильных таблиц блоков: {len(block_keys)}",
        "",
        "CHRDK_Core, CHRDK_Skills, CHRDK_Enchants и CHRDK_TierForge не",
        "дублируют награды Mine Treasure: double-drop и бонусы дропа работают",
        "только по ванильным стекам без ItemMeta/PDC-метки. CHRDK_Artifacts",
        "добавляет свои артефакты в mt: сундуки по короткому имени таблицы;",
        "CHRDK_TierForge не ставит свой тир на mt: таблицы лута.",
        "",
        "Уже выданные предметы, PDC и scoreboard mt.* не мигрируются.",
        "Публикация ZIP выполнена по решению владельца проекта; агент не",
        "подтверждал наличие письменного разрешения Frozytime.",
        "",
    ])


def scan_archive(archive: zipfile.ZipFile) -> dict:
    names = archive.namelist()
    if "pack.mcmeta" not in names:
        raise BuildError("нет pack.mcmeta в корне")
    meta = parse_json(archive.read("pack.mcmeta").decode("utf-8-sig"), "pack.mcmeta")
    pack = meta.get("pack") if isinstance(meta, dict) else None
    if not isinstance(pack, dict) or pack.get("min_format") != [121, 1] \
            or pack.get("max_format") != [121, 1]:
        raise BuildError("pack.mcmeta должен сохранять min/max_format [121,1]")
    json_files = 0
    set_attributes = 0
    set_components = 0
    unprefixed = 0
    prefixed_attributes = 0
    slot_arrays = 0
    slot_strings = 0
    pdc_markers = 0
    block_tables = []
    for name in names:
        if name.startswith(BLOCK_TABLE_PREFIX) and name.endswith(".json"):
            block_tables.append(name)
        if not name.endswith(".json"):
            continue
        json_files += 1
        text = archive.read(name).decode("utf-8-sig")
        data = parse_json(text, name)
        unprefixed += text.count('"water_movement_efficiency"')
        prefixed_attributes += text.count('"minecraft:water_movement_efficiency"')
        slot_arrays += len(SLOT_ARRAY.findall(text))
        slot_strings += len(re.findall(r'"slot"\s*:\s*"[a-z_]+"', text))

        def walk(value):
            nonlocal set_attributes, set_components, pdc_markers
            if isinstance(value, dict):
                if value.get("type") == "minecraft:set_attributes":
                    set_attributes += 1
                if value.get("type") == "minecraft:set_components":
                    set_components += 1
                    components = value.get("components")
                    custom = components.get("minecraft:custom_data") if isinstance(components, dict) else None
                    pdc = custom.get(PDC_CONTAINER) if isinstance(custom, dict) else None
                    if isinstance(pdc, dict) and pdc.get(PDC_KEY) == PDC_VALUE:
                        pdc_markers += 1
                for nested in value.values():
                    walk(nested)
            elif isinstance(value, list):
                for nested in value:
                    walk(nested)

        walk(data)
        if name.startswith(BLOCK_TABLE_PREFIX):
            refs = []
            vanilla = 0

            def walk_table(value):
                nonlocal vanilla
                if isinstance(value, dict):
                    if value.get("type") == "minecraft:loot_table" \
                            and isinstance(value.get("value"), str):
                        refs.append(value["value"])
                    if value.get("type") == "minecraft:item" \
                            and str(value.get("name", "")).startswith("minecraft:"):
                        vanilla += 1
                    for nested in value.values():
                        walk_table(nested)
                elif isinstance(value, list):
                    for nested in value:
                        walk_table(nested)

            walk_table(data)
            if not any(ref.startswith("mt:") for ref in refs) or vanilla == 0:
                raise BuildError(f"{name}: нет совместного ванильного дропа и MT-пула")
    if json_files != EXPECTED_JSON:
        raise BuildError(f"JSON-файлов {json_files}, ожидалось {EXPECTED_JSON}")
    if set_attributes != EXPECTED_SET_ATTRIBUTES:
        raise BuildError(f"set_attributes {set_attributes}, ожидалось {EXPECTED_SET_ATTRIBUTES}")
    if set_components != EXPECTED_SET_COMPONENTS:
        raise BuildError(f"set_components {set_components}, ожидалось {EXPECTED_SET_COMPONENTS}")
    if unprefixed or slot_arrays:
        raise BuildError(f"остались кодеки: unprefixed={unprefixed}, slot-массивов={slot_arrays}")
    if prefixed_attributes != EXPECTED_UNPREFIXED:
        raise BuildError(f"исправлено attribute {prefixed_attributes}, ожидалось {EXPECTED_UNPREFIXED}")
    if pdc_markers != EXPECTED_SET_COMPONENTS:
        raise BuildError(f"PDC-меток {pdc_markers}, ожидалось {EXPECTED_SET_COMPONENTS}")
    if len(block_tables) != EXPECTED_BLOCK_TABLES:
        raise BuildError(f"таблиц блоков {len(block_tables)}, ожидалось {EXPECTED_BLOCK_TABLES}")
    invariants = {}
    for entry in INVARIANT_ENTRIES:
        if entry not in names:
            raise BuildError(f"нет инвариантной записи {entry}")
        invariants[entry] = sha256_bytes(archive.read(entry))
    return {
        "entries": len(names),
        "json_files": json_files,
        "set_attributes_blocks": set_attributes,
        "set_components_blocks": set_components,
        "unprefixed_attributes": 0,
        "prefixed_water_attributes": prefixed_attributes,
        "slot_arrays": 0,
        "slot_strings": slot_strings,
        "pdc_markers": pdc_markers,
        "block_loot_tables": block_table_keys(names),
        "invariants": invariants,
    }


def build(source: Path, output: Path, manifest_path: Path) -> dict:
    source = Path(source).expanduser().resolve()
    if not zipfile.is_zipfile(source):
        raise BuildError("исходный файл не является ZIP")
    source_hash = sha256_file(source)
    output.parent.mkdir(parents=True, exist_ok=True)
    fixed_attributes = fixed_slots = added_markers = 0
    with zipfile.ZipFile(source) as incoming, zipfile.ZipFile(output, "w") as outgoing:
        names = incoming.namelist()
        if "pack.mcmeta" not in names:
            raise BuildError("в исходнике нет pack.mcmeta в корне")
        block_keys = block_table_keys(names)
        if len(block_keys) != EXPECTED_BLOCK_TABLES:
            raise BuildError(f"в исходнике таблиц блоков {len(block_keys)}, ожидалось {EXPECTED_BLOCK_TABLES}")
        for info in incoming.infolist():
            data = incoming.read(info.filename)
            if info.filename.endswith(".json"):
                data, attributes, slots, stats = patch_json(
                    data.decode("utf-8-sig"), info.filename)
                fixed_attributes += attributes
                fixed_slots += slots
                added_markers += stats["markers"]
            outgoing.writestr(info, data)
        marker = zipfile.ZipInfo(COMPAT_MARKER, date_time=(2026, 11, 3, 0, 0, 0))
        marker.compress_type = zipfile.ZIP_DEFLATED
        marker.external_attr = 0o644 << 16
        marker.create_system = 3
        outgoing.writestr(marker, compat_marker(source_hash, block_keys).encode("utf-8"))
    if fixed_attributes != EXPECTED_UNPREFIXED or fixed_slots != EXPECTED_SLOT_ARRAYS:
        output.unlink(missing_ok=True)
        raise BuildError(f"исправлено {fixed_attributes}/{fixed_slots}, "
                         f"ожидалось {EXPECTED_UNPREFIXED}/{EXPECTED_SLOT_ARRAYS}")
    with zipfile.ZipFile(output) as archive:
        report = scan_archive(archive)
    report.update({
        "schema": 2,
        "source_sha256": source_hash,
        "source_size": source.stat().st_size,
        "patched_sha256": sha256_file(output),
        "patched_size": output.stat().st_size,
        "fixed_unprefixed_attributes": fixed_attributes,
        "fixed_slot_arrays": fixed_slots,
        "added_pdc_markers": added_markers,
    })
    manifest_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                             encoding="utf-8")
    return report


def check(output: Path, manifest_path: Path) -> dict:
    if not output.is_file():
        raise BuildError(f"нет патченого ZIP: {output}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    actual_hash = sha256_file(output)
    if actual_hash != manifest["patched_sha256"]:
        raise BuildError("SHA-256 патченого ZIP не совпадает с манифестом")
    if output.stat().st_size != manifest["patched_size"]:
        raise BuildError("размер патченого ZIP не совпадает с манифестом")
    if manifest.get("source_sha256") != "0ff4bd2ad1a5db22920db23f1f2c88d577ceff607164638d0e1a45264075bf92":
        raise BuildError("манифест должен ссылаться на присланный оригинал")
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise BuildError("CRC патченого ZIP не совпадает")
        report = scan_archive(archive)
    for key in ("entries", "json_files", "set_attributes_blocks", "set_components_blocks",
                "prefixed_water_attributes", "slot_strings", "pdc_markers",
                "block_loot_tables", "invariants"):
        if report[key] != manifest[key]:
            raise BuildError(f"манифест расходится по полю {key}")
    return manifest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, help="присланный исходный ZIP/.txt")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--check", action="store_true", help="проверить готовый ZIP по манифесту")
    args = parser.parse_args(argv)
    try:
        if args.check:
            manifest = check(args.output, args.manifest)
            print(f"Mine Treasure RU: патченый ZIP проверен; {manifest['entries']} записей, "
                  f"SHA-256 {manifest['patched_sha256']}")
            return 0
        if args.source is None:
            parser.error("для сборки нужен --source")
        report = build(args.source, args.output, args.manifest)
        print(f"Собран {args.output}: {report['entries']} записей, "
              f"исправлено {report['fixed_unprefixed_attributes']} attribute и "
              f"{report['fixed_slot_arrays']} slot, добавлено {report['added_pdc_markers']} PDC-меток, "
              f"SHA-256 {report['patched_sha256']}")
        return 0
    except BuildError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
