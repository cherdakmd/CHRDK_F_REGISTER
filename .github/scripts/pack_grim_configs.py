#!/usr/bin/env python3
"""Упаковка эталона Grim-конфигов (anticheat/grim) в ассет релиза.

Ассет chrdk-grim-configs-<версия>.zip содержит grim/config.yml,
grim/punishments.yml и grim/README.md: оба файла кладут в plugins/Grim/
взамен сгенерированных Гримом при первом запуске (см. README в архиве).
"""
from __future__ import annotations

import sys
import zipfile
from pathlib import Path

NAMES = ("README.md", "config.yml", "punishments.yml")
BRIDGE = "chrdkac flag %player% %check_name% %vl% %verbose%"
BANNED = ("[alert]", "[webhook]", "[proxy]")


def pack(source: Path, destination: Path) -> None:
    names = sorted(path.name for path in source.iterdir() if path.is_file())
    if names != sorted(NAMES):
        raise ValueError(f"Эталон Grim-конфигов неполный: {names}")
    text = (source / "punishments.yml").read_text(encoding="utf-8")
    if BRIDGE not in text:
        raise ValueError("punishments.yml обязан слать срабатывания в мост chrdkac")
    for token in BANNED:
        if token in text:
            raise ValueError(f"punishments.yml не должен исполнять {token}")
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(NAMES):
            archive.write(source / name, f"grim/{name}")
    with zipfile.ZipFile(destination) as archive:
        expected = {f"grim/{name}" for name in NAMES}
        if archive.testzip() is not None or set(archive.namelist()) != expected:
            raise ValueError("Ошибка целостности архива конфигов")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: pack_grim_configs.py <anticheat/grim> <destination.zip>")
    pack(Path(sys.argv[1]), Path(sys.argv[2]))
    print(f"Конфиги Grim упакованы: {sys.argv[2]}")
