#!/usr/bin/env python3
"""Единый упаковщик workflow и интеграционного теста ZIP-only обновлятора."""
from pathlib import Path
import os
import sys
import tempfile
import zipfile

from verify_release import expected_plugins, verify

ZIP_LIMIT = 64 * 1024 * 1024
JAR_LIMIT = 32 * 1024 * 1024
MEMO_LIMIT = 2 * 1024 * 1024
MEMO_NAME = "ОБНОВЛЕНИЕ.txt"


def pack(directory: Path, version: str, destination: Path, expected: set[str]) -> None:
    if destination.exists():
        raise ValueError("Архив уже существует; старый ZIP не дополняется и не перезаписывается")
    # Сначала пределы: verify читает memo и проверяет внутренние CRC JAR.
    files = sorted(directory.glob("*.jar")) + [directory / MEMO_NAME]
    if any(not p.is_file() for p in files):
        raise ValueError("Отсутствует файл комплекта или памятка")
    if any(p.is_symlink() for p in files):
        raise ValueError("Ссылки вместо файлов комплекта запрещены")
    if any(p.stat().st_size > (MEMO_LIMIT if p.name == MEMO_NAME else JAR_LIMIT) for p in files):
        raise ValueError("Превышен допустимый размер файла комплекта")
    if sum(p.stat().st_size for p in files) > ZIP_LIMIT:
        raise ValueError("Распакованный комплект слишком большой")
    memo = files[-1].read_text(encoding="utf-8")
    if not memo.removeprefix("\ufeff").strip() or "\0" in memo:
        raise ValueError("Памятка должна содержать текст UTF-8")
    verify(directory, version, expected)
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".chrdk-release-", suffix=".tmp", dir=destination.parent)
    os.close(fd)
    temporary = Path(name)
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in files:
                archive.write(path, path.name)
        if temporary.stat().st_size > ZIP_LIMIT:
            raise ValueError("ZIP комплекта слишком большой")
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None or set(archive.namelist()) != {p.name for p in files}:
                raise ValueError("Ошибка целостности упакованного комплекта")
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: pack_release.py <directory> <version> <destination.zip>")
    root = Path(__file__).resolve().parents[2]
    pack(Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3]), expected_plugins(root))
    print(f"ZIP-only комплект {sys.argv[2]} проверен и упакован: {sys.argv[3]}")
