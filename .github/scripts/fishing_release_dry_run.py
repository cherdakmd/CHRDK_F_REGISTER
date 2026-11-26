#!/usr/bin/env python3
"""Stage an RC-only Fishing Horizons packaging rehearsal. Never publishes or creates tags."""
from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import re
import sys
import tempfile
import zipfile

from verify_fishing_bundle import JARS, verify_bundle

PACK_NAME = "chrdk-custom-items-fishing.zip"
MANIFEST_NAME = "MANIFEST.json"
NOTICE_NAME = "NOT-A-RELEASE.txt"
MAX_FILE = 64 * 1024 * 1024


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def notice(version: str, commit: str) -> bytes:
    return (f"FISHING HORIZONS — СУХОЙ ПРОГОН, НЕ РЕЛИЗ И НЕ ИНСТРУКЦИЯ ПО УСТАНОВКЕ.\n"
            f"Кандидат: {version}; исходный коммит: {commit}.\n"
            "Комплект проверяет состав и хэши, но не гарантирует совместимость и восстановление.\n"
            "Стабильный выпуск заблокирован; не заменяйте файлы рабочего сервера.\n"
            "Ресурс-пак содержит CustomItems и Fishing; свои ресурсы нельзя перезаписывать без слияния.\n"
            "Автоматического садка в этом кандидате нет.\n").encode("utf-8")


def verify_resource_pack(data: bytes) -> None:
    # Validate the exact bytes that are packaged, not a second read of the source.
    with zipfile.ZipFile(io.BytesIO(data)) as pack:
        infos = pack.infolist()
        names = [info.filename for info in infos]
        if not names or len(names) > 10000 or len(names) != len(set(names)):
            raise ValueError("resource pack is empty, too large or duplicated")
        if (sum(info.file_size for info in infos) > MAX_FILE
                or any(info.file_size > MAX_FILE for info in infos)):
            raise ValueError("resource pack contents are oversized")
        if any(name.startswith('/') or '\\' in name or '\x00' in name
               or any(part in ('.', '..') for part in name.split('/')) for name in names):
            raise ValueError("unsafe resource pack path")
        if pack.testzip() is not None:
            raise ValueError("resource pack is corrupt")
        files = {info.filename for info in infos if not info.is_dir()}
        if ("pack.mcmeta" not in files or not any(n.startswith("assets/chrdk_fishing/") for n in files)
                or not any(n.startswith("assets/chrdk_items/") for n in files)):
            raise ValueError("resource pack does not contain both Fishing and CustomItems resources")


def pack_dry_run(plugins: Path, resource_pack: Path, version: str, commit: str, destination: Path) -> None:
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("a full lowercase 40-character commit SHA is required")
    if destination.exists() or destination.is_symlink():
        raise ValueError("destination already exists")
    verify_bundle(plugins, version)  # Also rejects stable versions.
    files = {f"plugins/{archive}-{version}.jar": plugins / f"{archive}-{version}.jar"
             for archive, _, _ in JARS.values()}
    files[f"resources/{PACK_NAME}"] = resource_pack
    payload = {}
    for name, path in files.items():
        if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_FILE:
            raise ValueError(f"missing, symlinked or oversized input: {path}")
        payload[name] = path.read_bytes()
    verify_resource_pack(payload[f"resources/{PACK_NAME}"])
    payload[NOTICE_NAME] = notice(version, commit)
    manifest = {"format": 1, "kind": "experimental-dry-run", "version": version,
                "commit": commit, "sha256": {name: digest(data) for name, data in sorted(payload.items())}}
    payload[MANIFEST_NAME] = (json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation avoids modifying an earlier artifact on reruns.
    created = False
    try:
        output = destination.open("xb")
        created = True
        with output, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, data in sorted(payload.items()):
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data)
        verify_dry_run(destination, version, commit)
    except Exception:
        if created:
            destination.unlink(missing_ok=True)
        raise


def verify_dry_run(path: Path, version: str, commit: str) -> None:
    if not re.fullmatch(r"\d+\.\d+\.\d+-rc\.[1-9]\d*", version) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("invalid candidate version or commit")
    expected = {f"plugins/{archive}-{version}.jar" for archive, _, _ in JARS.values()}
    expected |= {f"resources/{PACK_NAME}", NOTICE_NAME, MANIFEST_NAME}
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(expected) or set(names) != expected or archive.testzip() is not None:
            raise ValueError("unexpected, missing, duplicate or corrupt archive entries")
        if any(info.file_size > MAX_FILE for info in archive.infolist()):
            raise ValueError("oversized archive entry")
        manifest = json.loads(archive.read(MANIFEST_NAME))
        if not isinstance(manifest, dict) or set(manifest) != {"format", "kind", "version", "commit", "sha256"}:
            raise ValueError("invalid manifest")
        if (manifest["format"], manifest["kind"], manifest["version"], manifest["commit"]) != (1, "experimental-dry-run", version, commit):
            raise ValueError("wrong provenance")
        actual = {name: digest(archive.read(name)) for name in sorted(expected - {MANIFEST_NAME})}
        if manifest["sha256"] != actual or archive.read(NOTICE_NAME) != notice(version, commit):
            raise ValueError("checksum or notice mismatch")
        # Hashes embedded in this same archive are not an authenticity guarantee.
        # Re-check the packaged bytes as well, even if the manifest was regenerated.
        verify_resource_pack(archive.read(f"resources/{PACK_NAME}"))
        with tempfile.TemporaryDirectory(prefix="fishing-dry-run-") as temp:
            plugins = Path(temp)
            for name, _, _ in JARS.values():
                filename = f"{name}-{version}.jar"
                (plugins / filename).write_bytes(archive.read(f"plugins/{filename}"))
            verify_bundle(plugins, version)


def main() -> None:
    if len(sys.argv) != 6:
        raise SystemExit("Usage: fishing_release_dry_run.py <plugins-dir> <combined-pack.zip> <rc-version> <commit> <output.zip>")
    plugins, pack, version, commit, output = sys.argv[1:]
    path = Path(output)
    pack_dry_run(Path(plugins), Path(pack), version, commit, path)
    print(f"Dry run only: {path.name} SHA-256 {digest(path.read_bytes())}; no tag or release created")


if __name__ == "__main__":
    main()
