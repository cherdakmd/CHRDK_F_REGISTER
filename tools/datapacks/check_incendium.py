#!/usr/bin/env python3
"""Проверка NBT структур Incendium: BlockState сущностей должен быть формата 26.3.

В Minecraft 26.3 поле BlockState у сущностей декодируется как строка либо
компаунд с ключом id. Старый вид {Name:..., Properties:...} вызывает при
загрузке чанка спам «EntityType: Failed to decode ... from field 'BlockState'».
Проверка читает ZIP напрямую и не изменяет датапак.
"""
from __future__ import annotations
from pathlib import Path
import sys
import tempfile
import zipfile

import nbtlib

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE = ROOT / 'datapacks/incendium-ru/Incendium_Legacy_v5.5.4+26.3_RU.zip'
LEGACY_KEYS = ('Name', 'Properties')


def main() -> int:
    if not ARCHIVE.is_file():
        print(f'Не найден датапак: {ARCHIVE}', file=sys.stderr)
        return 1
    problems: list[str] = []
    structures = blocks = entities = states = 0
    with zipfile.ZipFile(ARCHIVE) as archive:
        names = archive.namelist()
        if 'pack.mcmeta' not in names:
            problems.append('нет pack.mcmeta в корне датапака')
        for name in names:
            if not name.endswith('.nbt'):
                continue
            structures += 1
            # nbtlib.load определяет gzip по содержимому; временный файл нужен,
            # чтобы не распаковывать весь архив на диск.
            with tempfile.NamedTemporaryFile(suffix='.nbt') as tmp:
                tmp.write(archive.read(name))
                tmp.flush()
                data = nbtlib.load(tmp.name)
            blocks += len(data.get('blocks', []) or [])
            for entity in data.get('entities', []) or []:
                entities += 1
                nbt = entity.get('nbt', {})
                for key in ('BlockState', 'block_state'):
                    value = nbt.get(key)
                    if value is None:
                        continue
                    states += 1
                    if isinstance(value, nbtlib.Compound) and any(k in value for k in LEGACY_KEYS):
                        problems.append(f'{name}: {nbt.get("id")} → {key} = {value} (старый формат)')
    if problems:
        print('Устаревший BlockState в структурах Incendium:', file=sys.stderr)
        for problem in problems:
            print('  ' + problem, file=sys.stderr)
        return 1
    print(f'Incendium: {structures} структур, {blocks} блоков, {entities} сущностей; '
          f'BlockState в формате 26.3: {states}.')
    if not structures:
        print('В архиве не найдено ни одной NBT-структуры', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
