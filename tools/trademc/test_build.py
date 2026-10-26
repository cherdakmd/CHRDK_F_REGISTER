"""The storefront is a delivery contract, not a manually maintained second catalog."""
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import yaml
from PIL import Image

import build as kit


class StorefrontTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cfg, cls.products = kit.catalog()
        cls.by_id = {p['id']: p for p in cls.products}

    def test_all_25_skus_and_periods(self):
        self.assertEqual(25, len(self.products))
        self.assertEqual(10, sum(bool(p.get('rank')) for p in self.products))
        for p in self.products:
            expected = 365 if p['id'].endswith('-year') else 90 if p['id'] == 'battlepass-premium' else 30
            self.assertEqual(expected, p['days'], p['id'])
            self.assertEqual(f"pass shopbuy %username% {p['id']}", kit.command(p))
            self.assertNotIn('payment=', kit.command(p))
        self.assertEqual([99, 159, 229, 299, 365],
                         [p['price'] for p in self.products if p.get('rank') and p['days'] == 30])

    def test_manifest_matches_live_catalog_and_individual_artwork(self):
        manifest = json.loads((kit.OUTPUT / 'manifest.json').read_text(encoding='utf-8'))
        self.assertEqual(kit.version(), manifest['pluginVersion'])
        self.assertEqual(set(self.by_id), {p['id'] for p in manifest['products']})
        names, hashes = set(), set()
        for entry in manifest['products']:
            p = self.by_id[entry['id']]
            self.assertEqual(p['price'], entry['priceRubles'])
            self.assertEqual(p['days'], entry['days'])
            self.assertEqual(kit.command(p), entry['command'])
            self.assertEqual(kit.description(p, self.cfg), entry['description'])
            image = kit.OUTPUT / entry['image']
            self.assertEqual(hashlib.sha256(image.read_bytes()).hexdigest(), entry['imageSha256'])
            with Image.open(image) as img:
                self.assertEqual((1200, 1200), img.size)
                self.assertEqual('PNG', img.format)
                img.verify()
            names.add(entry['image'])
            hashes.add(entry['imageSha256'])
        self.assertEqual(25, len(names))
        self.assertEqual(25, len(hashes))

    def test_txt_ready_commands_prices_durations_and_trust_boundary(self):
        txt = (kit.OUTPUT / kit.TEXT_NAME).read_text(encoding='utf-8')
        self.assertEqual(kit.document(self.cfg, self.products), txt)
        for index, p in enumerate(self.products, 1):
            self.assertEqual(1, txt.count(kit.command(p) + '\n'))
            self.assertIn(f"Цена: {p['price']} ₽\nСрок: {p['days']} дней", txt)
            self.assertIn('images/' + kit.image_name(index, p), txt)
        self.assertIn('не может отличить повтор RCON-команды от новой покупки', txt)
        self.assertIn('ровно ОДНА команда', txt)
        self.assertIn('shopgift <ник_получателя> <id_товара> [ник_дарителя]\n', txt)
        self.assertNotIn('shoppay <ник> <сумма> [дни]', txt)
        self.assertNotIn('[ник_дарителя] [дни]', txt)

    def test_rank_limits_come_from_configs_not_old_cards(self):
        for p in self.products:
            if not p.get('rank'):
                continue
            tier = p['rank']
            text = kit.description(p, self.cfg)
            slots = self.cfg['labels']['display']['slots'][tier]
            self.assertIn(f'Надписи после ника: {slots};', text)
            self.assertIn('Платные цвета ника не входят даже в V', text)
            self.assertIn('не гарантированное удвоение каждого блока', text)
        self.assertIn('90 дней', kit.document(self.cfg, self.products))

    def test_bundle_members_exist_and_are_not_additional_delivery_commands(self):
        for p in self.products:
            for member in p.get('includes', []):
                self.assertIn(member, self.by_id)
        self.assertEqual(5, len(self.by_id['trails-pack']['includes']))
        self.assertEqual(6, len(self.by_id['look-pack']['includes']))
        self.assertNotIn('join-effect', self.by_id['look-pack']['includes'])
        self.assertIn('Не добавляйте отдельные команды', kit.description(self.by_id['look-pack'], self.cfg))

    def test_duplicate_id_or_ambiguous_price_is_rejected(self):
        for key in ('id', 'price'):
            data = yaml.safe_load(kit.CATALOG.read_text(encoding='utf-8'))
            data['shop']['products'][1][key] = data['shop']['products'][0][key]
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                path = root / 'src/main/resources/config.yml'
                path.parent.mkdir(parents=True)
                path.write_text(yaml.safe_dump(data, allow_unicode=True), encoding='utf-8')
                with self.assertRaises(ValueError):
                    kit.catalog(root)

    def test_stale_txt_and_tampered_manifest_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'kit'
            shutil.copytree(kit.OUTPUT, output)
            with patch.object(kit, 'OUTPUT', output):
                original = (output / kit.TEXT_NAME).read_text(encoding='utf-8')
                (output / kit.TEXT_NAME).write_text(original + '\nwrong', encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'TXT is stale'):
                    kit.check()
                (output / kit.TEXT_NAME).write_text(original, encoding='utf-8')
                (output / 'manifest.json').write_text('{}', encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'manifest/catalog/hash mismatch'):
                    kit.check()

    def test_missing_or_duplicate_artwork_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'kit'
            shutil.copytree(kit.OUTPUT, output)
            images = sorted((output / 'images').glob('*.png'))
            with patch.object(kit, 'OUTPUT', output):
                images[1].write_bytes(images[0].read_bytes())
                with self.assertRaisesRegex(ValueError, 'Duplicate product artwork'):
                    kit.check()
                images[1].unlink()
                with self.assertRaisesRegex(ValueError, 'Missing/extra product images'):
                    kit.check()

    def test_zip_is_complete_crc_valid_and_reproducible(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = kit.pack(Path(tmp) / 'first.zip')
            second = kit.pack(Path(tmp) / 'second.zip')
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertIsNone(archive.testzip())
                names = archive.namelist()
                self.assertEqual(28, len(names))
                self.assertEqual(25, sum(n.endswith('.png') for n in names))
                self.assertIn(kit.TEXT_NAME, names)
                self.assertIn('README.md', names)
                self.assertIn('manifest.json', names)
                self.assertEqual(len(names), len(set(names)))
                self.assertFalse(any(n.endswith('.jar') or n.startswith('/') or '..' in Path(n).parts for n in names))
                self.assertEqual(kit.document(self.cfg, self.products), archive.read(kit.TEXT_NAME).decode('utf-8'))
                for info in archive.infolist():
                    self.assertEqual((1980, 1, 1, 0, 0, 0), info.date_time)


if __name__ == '__main__':
    unittest.main()
