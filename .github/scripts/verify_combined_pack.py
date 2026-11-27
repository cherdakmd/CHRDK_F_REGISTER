#!/usr/bin/env python3
"""Verify the exact CustomItems + Fishing ZIP and its copy embedded in the JAR.

This checks source/archive integrity, not Minecraft rendering or compatibility with
operator-modified resource packs. The Fishing JAR is not part of the resource pack.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import stat
import zipfile


def source_files(root: Path) -> dict[str, Path]:
    if not root.is_dir() or root.is_symlink():
        raise ValueError(f'invalid resource pack directory: {root}')
    result = {}
    for path in root.rglob('*'):
        if path.is_symlink():
            raise ValueError(f'symlink in source pack: {path}')
        if path.is_file():
            result[path.relative_to(root).as_posix()] = path
    if 'pack.mcmeta' not in result or not any(n.startswith('assets/') for n in result):
        raise ValueError(f'missing pack metadata or assets: {root}')
    return result


def verify(custom: Path, fishing: Path, archive: Path, jar: Path | None = None) -> int:
    custom_files = source_files(custom)
    fishing_files = source_files(fishing)
    custom_meta = json.loads(custom_files['pack.mcmeta'].read_text(encoding='utf-8'))['pack']
    fishing_meta = json.loads(fishing_files['pack.mcmeta'].read_text(encoding='utf-8'))['pack']
    for key in ('min_format', 'max_format'):
        if key not in custom_meta or custom_meta[key] != fishing_meta.get(key):
            raise ValueError(f'incompatible resource pack {key}')
    fishing_files.pop('pack.mcmeta')
    overlap = custom_files.keys() & fishing_files.keys()
    if overlap:
        raise ValueError(f'source packs collide: {sorted(overlap)[:5]}')
    expected = custom_files | fishing_files
    with zipfile.ZipFile(archive) as z:
        files = {}
        seen = set()
        for item in z.infolist():
            name = item.filename
            if (not name or '\\' in name or name.startswith('/') or
                    any(part in ('', '.', '..') for part in name.rstrip('/').split('/')) or
                    stat.S_ISLNK(item.external_attr >> 16)):
                raise ValueError(f'unsafe ZIP entry: {name}')
            if name in seen:
                raise ValueError(f'duplicate ZIP entry: {name}')
            seen.add(name)
            if not item.is_dir():
                files[name] = item
        missing, extra = expected.keys() - files.keys(), files.keys() - expected.keys()
        if missing or extra:
            raise ValueError(f'wrong pack contents: missing={sorted(missing)[:5]}, extra={sorted(extra)[:5]}')
        for name, path in expected.items():
            if z.read(files[name]) != path.read_bytes():
                raise ValueError(f'pack entry differs from source: {name}')
    if jar is not None:
        with zipfile.ZipFile(jar) as z:
            entries = [i for i in z.infolist() if i.filename == 'custom-items-pack.zip']
            if len(entries) != 1 or z.read(entries[0]) != archive.read_bytes():
                raise ValueError('JAR embedded resource pack missing, duplicated or different')
    return len(expected)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--custom', type=Path, default=Path('custom-items/resource-pack'))
    parser.add_argument('--fishing', type=Path, default=Path('fishing/resource-pack'))
    args = parser.parse_args()
    try:
        count = verify(args.custom, args.fishing, args.archive, args.jar)
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Combined resource pack rejected: {error}\n')
    print(f'Combined resource pack verified: {count} exact files' + ('; embedded JAR copy matches' if args.jar else ''))


if __name__ == '__main__':
    main()
