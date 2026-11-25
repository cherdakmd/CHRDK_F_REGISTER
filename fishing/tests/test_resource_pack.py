"""Verify pack graph and image files without assuming a running Minecraft client."""
import json
import hashlib
import re
import struct
import unittest
from pathlib import Path

PACK = Path(__file__).resolve().parents[1] / 'resource-pack'
ASSETS = PACK / 'assets/chrdk_fishing'
RARITIES = ('common', 'uncommon', 'rare', 'very_rare', 'legendary', 'mythic')


class FishingResourcePackTest(unittest.TestCase):
    def read(self, name):
        return json.loads((ASSETS / name).read_text(encoding='utf-8'))

    def image(self, name):
        data = (ASSETS / 'textures/item' / (name + '.png')).read_bytes()
        self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
        self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))

    def model(self, name, parent):
        model = self.read('models/item/' + name + '.json')
        self.assertEqual(parent, model['parent'])
        self.assertEqual('chrdk_fishing:item/' + name, model['textures']['layer0'])
        self.image(name)

    def test_format_and_no_vanilla_overrides(self):
        pack = json.loads((PACK / 'pack.mcmeta').read_text(encoding='utf-8'))['pack']
        self.assertEqual([97, 1], pack['min_format']); self.assertEqual([97, 1], pack['max_format'])
        self.assertFalse((PACK / 'assets/minecraft').exists())

    def test_fish_item_models_for_all_rarities(self):
        for rarity in RARITIES:
            with self.subTest(rarity=rarity):
                name = 'fish/' + rarity
                self.model(name, 'minecraft:item/generated')
                self.assertEqual({'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + name},
                                 self.read('items/' + name + '.json')['model'])

    def test_every_catalog_species_has_a_distinct_texture_and_model(self):
        source = (PACK.parent / 'src/main/java/ru/cherdakmd/chrdkfishing/FishCatalog.java').read_text(encoding='utf-8')
        base = re.findall(r'fish\("([a-z_]+)","[^"]+",Rarity\.[A-Z_]+,(?:true|false),', source)
        premium = re.findall(r'new Premium\("([a-z_]+)", "[^"]+", "[a-z_]+"\)', source)
        species = base + premium
        self.assertEqual(70, len(species)); self.assertEqual(len(species), len(set(species)))
        images = set()
        for species_id in species:
            with self.subTest(species=species_id):
                name = 'fish/species/' + species_id
                self.model(name, 'minecraft:item/generated')
                self.assertEqual({'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + name},
                                 self.read('items/' + name + '.json')['model'])
                digest = hashlib.sha256((ASSETS / 'textures/item' / (name + '.png')).read_bytes()).hexdigest()
                self.assertNotIn(digest, images, 'species share a texture')
                images.add(digest)
        for kind in ('items', 'models/item', 'textures/item'):
            self.assertEqual(set(species), {p.stem for p in (ASSETS / kind / 'fish/species').iterdir()})

    def test_rods_cover_all_upgrade_levels_and_cast_state(self):
        for level in range(2, 11):
            with self.subTest(level=level):
                name = f'rod/level_{level}'
                self.model(name, 'minecraft:item/handheld_rod')
                self.model(name + '_cast', 'minecraft:item/handheld_rod')
                item = self.read('items/' + name + '.json')['model']
                self.assertEqual('minecraft:condition', item['type'])
                self.assertEqual('minecraft:fishing_rod/cast', item['property'])
                self.assertEqual('chrdk_fishing:item/' + name, item['on_false']['model'])
                self.assertEqual('chrdk_fishing:item/' + name + '_cast', item['on_true']['model'])


if __name__ == '__main__':
    unittest.main()
