#!/usr/bin/env python3
"""Локальная установка Mine Treasure v1.6.5 RU в <мир>/datapacks.

Скрипт НЕ публикует ZIP в репозитории и не меняет содержимое датапака: он
проверяет присланный архив (в том числе `.txt`, который на самом деле ZIP),
копирует его под корректным именем `.zip` в папку датапаков мира и печатает
Paper/Bukkit pack id `file/<имя>`. Для повторного запуска установка идемпотентна.

Пример:
  python3 tools/datapacks/install_mine_treasure.py --check \
      --source /tmp/arena-coding-uploads/mine-treasure-1.6.5-ru.txt
  python3 tools/datapacks/install_mine_treasure.py \
      --source /tmp/arena-coding-uploads/mine-treasure-1.6.5-ru.txt \
      --world /path/to/server
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

DEFAULT_NAME = "mine-treasure-1.6.5-ru.zip"
KNOWN_SHA256 = "0ff4bd2ad1a5db22920db23f1f2c88d577ceff607164638d0e1a45264075bf92"
KNOWN_SIZE = 1_747_330
REQUIRED_ENTRIES = (
    "data/mt/function/init.mcfunction",
    "data/minecraft/tags/function/load.json",
    "data/minecraft/tags/function/tick.json",
    "LICENSE.md",
)


class InstallError(Exception):
    """Ошибка валидации или установки, понятная владельцу сервера."""


@dataclass(frozen=True)
class Report:
    source: Path
    size: int
    sha256: str
    entries: int
    json_files: int
    min_format: tuple[int, int]
    max_format: tuple[int, int]
    description: str
    license_present: bool


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def flatten_description(value: object) -> str:
    """pack.mcmeta description бывает строкой или списком текст-компонентов."""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts = []
        for item in value:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                parts.append(str(item.get("text", item.get("fallback", ""))))
        return " ".join(parts)
    if isinstance(value, dict):
        return str(value.get("text", value.get("fallback", "")))
    return ""


def normalize_format(value: object, field: str) -> tuple[int, int]:
    """min_format/max_format: int либо [major] / [major, minor]."""
    if isinstance(value, bool):
        raise InstallError(f"{field}: ожидается число или список чисел")
    if isinstance(value, int):
        return (value, 0)
    if isinstance(value, list) and 1 <= len(value) <= 2:
        numbers = []
        for item in value:
            if not isinstance(item, int) or isinstance(item, bool):
                raise InstallError(f"{field}: нечисловой элемент {item!r}")
            numbers.append(item)
        return (numbers[0], numbers[1] if len(numbers) == 2 else 0)
    raise InstallError(f"{field}: ожидается число или список из 1–2 чисел")


def validate_archive(source: Path) -> Report:
    source = Path(source).expanduser()
    if not source.is_file():
        raise InstallError(f"не найден исходный архив: {source}")
    if not zipfile.is_zipfile(source):
        raise InstallError("исходный файл не является ZIP; расширение .txt допустимо, "
                           "но содержимое должно начинаться с PK")
    with zipfile.ZipFile(source) as archive:
        broken = archive.testzip()
        if broken is not None:
            raise InstallError(f"CRC архива не совпадает: {broken}")
        names = archive.namelist()
        if "pack.mcmeta" not in names:
            raise InstallError("в корне ZIP нет pack.mcmeta; возможно, архив завернут "
                               "в лишнюю папку — распакуйте и перепакуйте содержимое")
        try:
            meta = json.loads(archive.read("pack.mcmeta").decode("utf-8-sig"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise InstallError(f"pack.mcmeta не читается: {error}") from error
        pack = meta.get("pack") if isinstance(meta, dict) else None
        if not isinstance(pack, dict):
            raise InstallError("pack.mcmeta не содержит объекта pack")
        min_format = normalize_format(pack.get("min_format"), "min_format")
        max_format = normalize_format(pack.get("max_format"), "max_format")
        description = flatten_description(pack.get("description"))
        if "Mine Treasure" not in description:
            raise InstallError("pack.mcmeta не похож на Mine Treasure")
        missing = [name for name in REQUIRED_ENTRIES if name not in names]
        if missing:
            raise InstallError("в архиве нет обязательных записей: " + ", ".join(missing))
        json_files = 0
        for name in names:
            if not name.endswith(".json"):
                continue
            try:
                json.loads(archive.read(name).decode("utf-8-sig"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise InstallError(f"невалидный JSON: {name}: {error}") from error
            json_files += 1
    size = source.stat().st_size
    return Report(
        source=source.resolve(),
        size=size,
        sha256=sha256_file(source),
        entries=len(names),
        json_files=json_files,
        min_format=min_format,
        max_format=max_format,
        description=description.replace("\n", " ").strip(),
        license_present="LICENSE.md" in names,
    )


def safe_name(name: str) -> str:
    if not name or len(name) > 255 or not name.lower().endswith(".zip"):
        raise InstallError("имя датапака должно быть непустым и оканчиваться на .zip")
    if "/" in name or "\\" in name or name in {".", ".."} or ".." in name:
        raise InstallError("имя датапака не должно содержать путь или '..'")
    if any(ord(char) < 32 for char in name):
        raise InstallError("имя датапака содержит управляющий символ")
    return name


def read_level_name(root: Path) -> str:
    props = root / "server.properties"
    if props.is_file():
        for raw in props.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key.strip() == "level-name" and value.strip():
                return value.strip()
    return "world"


def resolve_datapacks_dir(world_arg: Path) -> Path:
    """Принимает папку мира либо корень сервера с level-name в properties."""
    root = Path(world_arg).expanduser().resolve()
    if (root / "level.dat").is_file():
        world = root
    else:
        candidate = root / read_level_name(root)
        if (candidate / "level.dat").is_file():
            world = candidate
        elif (root / "world" / "level.dat").is_file():
            world = root / "world"
        else:
            raise InstallError(f"не найден level.dat ни в {root}, ни в {candidate}; "
                               "передайте --world с папкой мира или корнем сервера")
    return world / "datapacks"


def install(source: Path, world_arg: Path, name: str, force: bool) -> tuple[Path, str]:
    """Возвращает (путь назначения, unchanged|installed|replaced)."""
    datapacks = resolve_datapacks_dir(world_arg)
    datapacks.mkdir(parents=True, exist_ok=True)
    destination = datapacks / name
    source_hash = sha256_file(source)
    existed = destination.exists()
    if existed:
        if sha256_file(destination) == source_hash:
            return destination, "unchanged"
        if not force:
            raise InstallError(f"{destination} уже существует с другим содержимым; "
                               "используйте --force только после резервной копии")
    temporary = destination.with_name(destination.name + ".tmp")
    if temporary.exists():
        temporary.unlink()
    shutil.copyfile(source, temporary)
    os.replace(temporary, destination)
    return destination, "replaced" if existed else "installed"


def print_report(report: Report) -> None:
    print(f"Источник:      {report.source}")
    print(f"Размер:        {report.size} байт")
    print(f"SHA-256:       {report.sha256}")
    print(f"Записей:       {report.entries}; JSON: {report.json_files}; "
          f"LICENSE.md: {'да' if report.license_present else 'нет'}")
    print(f"Формат пака:   min={report.min_format[0]}.{report.min_format[1]}, "
          f"max={report.max_format[0]}.{report.max_format[1]}")
    print(f"Описание:      {report.description}")
    if report.sha256 == KNOWN_SHA256 and report.size == KNOWN_SIZE:
        print("Контрольная сумма совпадает с присланным Mine Treasure v1.6.5 RU.")
    else:
        print("ВНИМАНИЕ: контрольная сумма отличается от присланного архива; "
              "убедитесь, что это нужная русская сборка.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, type=Path,
                        help="путь к присланному ZIP (расширение может быть .txt)")
    parser.add_argument("--world", type=Path,
                        help="папка мира с level.dat или корень сервера")
    parser.add_argument("--name", default=DEFAULT_NAME,
                        help=f"имя ZIP в datapacks (по умолчанию {DEFAULT_NAME})")
    parser.add_argument("--sha256", default=KNOWN_SHA256,
                        help="ожидаемый SHA-256; пустая строка отключает проверку")
    parser.add_argument("--force", action="store_true",
                        help="заменить существующий ZIP с другой контрольной суммой")
    parser.add_argument("--check", action="store_true",
                        help="только проверить архив, ничего не копировать")
    args = parser.parse_args(argv)
    try:
        name = safe_name(args.name)
        report = validate_archive(args.source)
        expected = (args.sha256 or "").strip().lower()
        if expected and report.sha256 != expected:
            raise InstallError("SHA-256 не совпадает с ожидаемым; передайте "
                               f"--sha256 {report.sha256} только если это нужная сборка "
                               "или --sha256 '' чтобы пропустить проверку")
        print_report(report)
        if args.check:
            return 0
        if args.world is None:
            parser.error("для установки нужен --world (или используйте --check)")
        destination, status = install(args.source, args.world, name, args.force)
        print(f"Назначение:    {destination} ({status})")
        print(f"Pack id:       file/{name}")
        print("Дальше:        остановить/запустить сервер, проверить /datapack list enabled; "
              "при отсутствии Paper API — /datapack enable \"file/" + name + "\" и /minecraft:reload")
        return 0
    except InstallError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
