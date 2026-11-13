#!/usr/bin/env python3
"""Проверка и офлайн-восстановление CHRDK ZIP. Только стандартная библиотека Python 3.11+."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sys
import tempfile
import zipfile

MANIFEST = "CHRDK-BACKUP.json"
MAX_FILES = 20_000
MAX_FILE = 64 * 1024 * 1024
MAX_TOTAL = 512 * 1024 * 1024


def _path(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if (not name or name.startswith("/") or "\\" in name or ":" in name
            or ".." in path.parts or "." in path.parts or not name.startswith("CHRDK_")
            or name == MANIFEST):
        raise ValueError(f"Небезопасный путь: {name!r}")
    if any(part.lower() in {"config.yml", "config.yaml"} or "token" in part.lower() or "secret" in part.lower()
           for part in path.parts):
        raise ValueError(f"Конфиг/токен не разрешён в снимке: {name}")
    return path


def _assert_no_symlink_path(root: Path, target: Path) -> None:
    """Reject symlinks in every existing path component, not just the final file."""
    try:
        relative = target.relative_to(root)
    except ValueError as error:
        raise ValueError(f"Цель восстановления выходит за plugins-dir: {target}") from error
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError(f"Небезопасный симлинк в пути восстановления: {current}")


def _json(raw: bytes) -> object:
    return json.loads(raw.decode("utf-8"), parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def verify(archive: Path) -> tuple[dict, dict[str, bytes]]:
    if not archive.is_file() or archive.is_symlink():
        raise ValueError("Архив должен быть обычным файлом")
    total = 0
    with zipfile.ZipFile(archive) as source:
        names = source.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Повторяющиеся ZIP entries")
        if MANIFEST not in names:
            raise ValueError(f"Нет {MANIFEST}")
        manifest = _json(source.read(MANIFEST))
        if not isinstance(manifest, dict) or manifest.get("format") != 1 or not isinstance(manifest.get("entries"), list):
            raise ValueError("Неподдерживаемый манифест")
        expected = {}
        for entry in manifest["entries"]:
            if not isinstance(entry, dict):
                raise ValueError("Неверная запись манифеста")
            name = str(_path(str(entry.get("path", ""))))
            if name in expected:
                raise ValueError(f"Повтор в манифесте: {name}")
            size, digest = entry.get("size"), entry.get("sha256")
            if not isinstance(size, int) or isinstance(size, bool) or not 0 <= size <= MAX_FILE:
                raise ValueError(f"Неверный размер: {name}")
            if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                raise ValueError(f"Неверный SHA-256: {name}")
            expected[name] = (size, digest)
        if not 0 < len(expected) <= MAX_FILES or set(names) != {MANIFEST, *expected}:
            raise ValueError("Состав ZIP не совпадает с манифестом")
        data = {}
        for name, (size, digest) in expected.items():
            with source.open(name) as stream:
                raw = stream.read(size + 1)
                if stream.read(1):
                    raise ValueError(f"Файл больше манифеста: {name}")
            if len(raw) != size or hashlib.sha256(raw).hexdigest() != digest:
                raise ValueError(f"Размер/SHA-256 не совпал: {name}")
            if name.endswith(".json"):
                _json(raw)
            elif name.endswith(".jsonl"):
                for line in raw.decode("utf-8").splitlines():
                    if line.strip():
                        _json(line.encode())
            data[name] = raw
            total += size
            if total > MAX_TOTAL:
                raise ValueError("Слишком большой распакованный размер")
        print(f"OK: {archive.name}: {len(data)} файлов, {total} байт; CRC, SHA-256 и JSON верны")
        return manifest, data


def restore(archive: Path, plugins: Path, confirmation: str) -> None:
    if confirmation != "SERVER_STOPPED":
        raise ValueError("Для восстановления добавьте --confirm SERVER_STOPPED и сначала ПОЛНОСТЬЮ остановите сервер")
    _, files = verify(archive)
    plugins = plugins.resolve(strict=True)
    if not plugins.is_dir():
        raise ValueError("plugins-dir не является каталогом")

    # Preflight every destination before creating the safety copy or writing anything.
    # A symlink in an ancestor (for example plugins/CHRDK_Core -> another disk) is
    # just as dangerous as a symlink at the destination itself.
    destinations = {name: plugins / Path(*PurePosixPath(name).parts) for name in files}
    for target in destinations.values():
        _assert_no_symlink_path(plugins, target)
        if target.exists() and not target.is_file():
            raise ValueError(f"Небезопасная цель: {target}")
    safety = plugins / "CHRDK_Core" / "backups" / "pre-restore"
    _assert_no_symlink_path(plugins, safety)

    safety.mkdir(parents=True, exist_ok=True)
    safety_copy = Path(tempfile.mkdtemp(prefix="before-restore-", dir=safety))
    staging = Path(tempfile.mkdtemp(prefix="chrdk-restore-", dir=plugins.parent))
    try:
        for name, raw in files.items():
            current = destinations[name]
            _assert_no_symlink_path(plugins, current)
            if current.exists():
                saved = safety_copy / Path(*PurePosixPath(name).parts)
                saved.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(current, saved)
            staged = staging / Path(*PurePosixPath(name).parts)
            staged.parent.mkdir(parents=True, exist_ok=True)
            staged.write_bytes(raw)
        for target_name, target in destinations.items():
            _assert_no_symlink_path(plugins, target)
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(staging / Path(*PurePosixPath(target_name).parts), target)
            try:
                target.chmod(0o600)
            except OSError:
                pass
        print(f"Восстановлено: {len(files)} файлов. Прежние файлы: {safety_copy}")
        print("Проверьте состав plugins/CHRDK_* и только затем запускайте сервер.")
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--restore", action="store_true", help="Восстановить; без этого флага только проверка")
    parser.add_argument("--plugins-dir", type=Path)
    parser.add_argument("--confirm", default="")
    args = parser.parse_args()
    try:
        if args.restore:
            if args.plugins_dir is None:
                parser.error("--restore требует --plugins-dir")
            restore(args.archive, args.plugins_dir, args.confirm)
        else:
            verify(args.archive)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, json.JSONDecodeError, UnicodeDecodeError) as error:
        print(f"ОШИБКА: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
