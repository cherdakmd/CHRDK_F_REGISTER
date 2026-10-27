from pathlib import Path
import shutil
import tempfile
import unittest

import yaml

import build as kit
from audit import validate, report


class CrossModuleAuditTest(unittest.TestCase):
    def setUp(self):
        self.cfg, self.products = kit.catalog()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for module in ('cosmetics', 'battlepass', 'teleports', ''):
            source = kit.ROOT / module / 'src/main/resources'
            target = self.root / module / 'src/main/resources'
            target.mkdir(parents=True, exist_ok=True)
            filename = 'plugin.yml' if not module else 'config.yml'
            shutil.copyfile(source / filename, target / filename)

    def mutate_module(self, module, change):
        path = self.root / module / 'src/main/resources/config.yml'
        data = yaml.safe_load(path.read_text(encoding='utf-8'))
        change(data)
        path.write_text(yaml.safe_dump(data, allow_unicode=True), encoding='utf-8')

    def check(self):
        return validate(self.root, self.cfg, self.products)

    def test_all_25_products_and_audit_report_are_covered(self):
        self.assertEqual(25, len(self.check()))
        text = report(self.root, self.cfg, self.products, kit.version())
        self.assertEqual(25, text.count('| `'))
        for p in self.products:
            self.assertIn('`' + p['id'] + '`', text)
        self.assertIn('904 ₽', text)
        self.assertIn('не провер', text.lower())

    def test_rank_pair_price_mismatch_is_rejected(self):
        self.products[1]['price'] += 1
        with self.assertRaisesRegex(ValueError, 'yearly price'):
            self.check()

    def test_orphan_trail_is_rejected(self):
        self.mutate_module('cosmetics', lambda d: d['cosmetics']['trail']['defs'][0].update(product='not-sold'))
        with self.assertRaisesRegex(ValueError, 'five sold trails'):
            self.check()

    def test_orphan_premium_pass_is_rejected(self):
        self.mutate_module('battlepass', lambda d: d['battlepass']['premium'].update(product='wrong-product'))
        with self.assertRaisesRegex(ValueError, 'premium pass access'):
            self.check()

    def test_palette_render_must_match_product_image_palette(self):
        color = next(c for c in self.cfg['labels']['colors']['list'] if c.get('product'))
        color['format'] = '<red>%name%</red>'
        with self.assertRaisesRegex(ValueError, 'actual palette'):
            self.check()

    def test_bundle_cannot_silently_gain_join_effect_or_lose_a_color(self):
        bundle = next(p for p in self.products if p['id'] == 'look-pack')
        bundle['includes'][-1] = 'join-effect'
        with self.assertRaisesRegex(ValueError, 'five trails . five colors only'):
            self.check()

    def test_cycle_is_rejected(self):
        bundle = next(p for p in self.products if p['id'] == 'trails-pack')
        bundle['includes'].append('look-pack')
        with self.assertRaisesRegex(ValueError, 'cyclic bundle'):
            self.check()

    def test_home_quantity_must_match_description_and_picture(self):
        self.mutate_module('teleports', lambda d: d['homes'].update({'extra-limit': 7}))
        with self.assertRaisesRegex(ValueError, r'\+8 / 30 days'):
            self.check()

    def test_sold_trail_period_cannot_differ_from_bundle(self):
        next(p for p in self.products if p['id'] == 'trail-flame')['days'] = 90
        with self.assertRaisesRegex(ValueError, 'orphan trail/period'):
            self.check()

    def test_real_color_command_must_be_registered(self):
        path = self.root / 'src/main/resources/plugin.yml'
        data = yaml.safe_load(path.read_text(encoding='utf-8'))
        del data['commands']['labels']
        path.write_text(yaml.safe_dump(data, allow_unicode=True), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'player/store command missing'):
            self.check()


if __name__ == '__main__':
    unittest.main()
