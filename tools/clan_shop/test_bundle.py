"""Комплект — отдельная карточка, не повторная выдача трёх составляющих."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import yaml

SPEC = importlib.util.spec_from_file_location("clan_bundle", Path(__file__).with_name("build_bundle.py"))
kit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(kit)

class BundleCardTest(unittest.TestCase):
    def test_committed_files_are_current(self):
        kit.check()

    def test_exact_449_thirty_day_parts_and_bonus(self):
        self.assertEqual(449, kit.product()["price"])
        data = json.loads(kit.manifest())
        self.assertEqual(30, data['days'])
        self.assertEqual(kit.PARTS, set(data['parts']))
        self.assertEqual('clan-style-pack', data['bonusEntitlement'])
        self.assertEqual(['aurora', 'shine', 'pulse'], data['bonusAnimations'])
        self.assertFalse(data['publishedToStore'])

    def test_one_delivery_command_not_four(self):
        commands = [line for line in kit.RCON.splitlines() if line.startswith('pass ')]
        self.assertEqual([kit.COMMAND], commands)
        self.assertNotIn('pass shopbuy %username% clan-name-style', kit.RCON)

    def test_changed_terms_or_gameplay_grants_rejected(self):
        config = yaml.safe_load((kit.ROOT / 'src/main/resources/config.yml').read_text())
        p = next(row for row in config['shop']['products'] if row['id'] == kit.PID)
        for key, value in [('price',450),('days',7),('includes-days',31),('includes',[]),
                           ('trademc-export',True),('commands',['op somebody']),('permissions',['power']),('rank','V')]:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as folder:
                root = Path(folder); cfg = root / 'src/main/resources/config.yml'; cfg.parent.mkdir(parents=True)
                cfg.write_text(yaml.safe_dump({'shop':{'products':[{**p,key:value}]}},allow_unicode=True))
                with patch.object(kit,'ROOT',root), self.assertRaises(ValueError): kit.product()

    def test_separate_zip_contains_only_five_delivery_materials(self):
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder) / 'bundle.zip'; kit.pack(destination)
            with zipfile.ZipFile(destination) as archive:
                self.assertEqual({'README.md','ОПИСАНИЕ.txt','КОМАНДА_RCON.txt','manifest.json','clan-style-pack.png'},set(archive.namelist()))
                self.assertIsNone(archive.testzip())
                self.assertFalse(any(name.endswith('.jar') for name in archive.namelist()))
