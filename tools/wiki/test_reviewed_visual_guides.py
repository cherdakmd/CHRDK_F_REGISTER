"""Independent checks for the reviewed visual guides, even while other pages are stale."""
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class ReviewedVisualGuidesTest(unittest.TestCase):
    def check_flags(self, page, config, keys):
        text = (ROOT / 'wiki' / f'{page}.md').read_text(encoding='utf-8')
        values = yaml.safe_load((ROOT / config).read_text(encoding='utf-8'))['textures']
        for key in keys:
            with self.subTest(page=page, key=key):
                self.assertIs(values[key], False)
                self.assertIn(f'textures.{key}', text)

    def test_catalog_navigation_is_opt_in(self):
        self.check_flags('custom-items', 'custom-items/src/main/resources/config.yml', ['catalog-menu'])

    def test_new_mob_loot_is_opt_in(self):
        self.check_flags('mobs', 'mobs/src/main/resources/config.yml', ['loot-items'])
        page = (ROOT / 'wiki/mobs.md').read_text(encoding='utf-8')
        self.assertIn('предмет всё равно расходуется', page)

    def test_fishing_visual_switches_are_separate_and_off(self):
        self.check_flags('fishing', 'fishing/src/main/resources/config.yml', [
            'enabled', 'bait-items', 'dish-items', 'menu', 'album-menu', 'bait-menus',
            'cooking-menu', 'orders-menu', 'sale-menu', 'rod-menu',
        ])
        page = (ROOT / 'wiki/fishing.md').read_text(encoding='utf-8')
        self.assertIn('стабильный выпуск пока заблокирован', page)


if __name__ == '__main__':
    unittest.main()
