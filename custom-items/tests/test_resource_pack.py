"""Offline structural checks; Minecraft rendering still requires a client smoke test."""
import hashlib
import json
from pathlib import Path
import re
import struct
import unittest

PACK = Path(__file__).resolve().parents[1] / "resource-pack"
ITEMS = {"iron_sword": "test_blade", "iron_pickaxe": "test_pick",
         "iron_helmet": "test_helm", "paper": "test_scroll",
         "golden_sword": "test_sunblade", "diamond_sword": "test_moonblade",
         "iron_chestplate": "test_chest", "iron_boots": "test_boots",
         "iron_shovel": "test_shovel", "iron_hoe": "test_hoe",
         "amethyst_shard": "test_rune", "echo_shard": "test_charm"}


class ResourcePackTest(unittest.TestCase):
    def test_pack_format_and_model_links(self):
        metadata = json.loads((PACK / "pack.mcmeta").read_text())
        self.assertEqual([97, 1], metadata["pack"]["min_format"])
        self.assertEqual([97, 1], metadata["pack"]["max_format"])
        for base, name in ITEMS.items():
            with self.subTest(item=name):
                root = PACK / "assets/chrdk_items"
                model = json.loads((root / f"models/item/{name}.json").read_text())
                self.assertEqual(f"chrdk_items:item/{name}", model["textures"]["layer0"])
                item = json.loads((root / f"items/item/{name}.json").read_text())
                self.assertEqual(f"chrdk_items:item/{name}", item["model"]["model"])
                dispatch = json.loads((PACK / f"assets/minecraft/items/{base}.json").read_text())["model"]
                self.assertEqual("minecraft:custom_model_data", dispatch["property"])
                self.assertEqual(f"minecraft:item/{base}", dispatch["fallback"]["model"])
                expected = [810001, 810002, 810003, 810004, 810005, 810006] if base == "iron_shovel" else [810001, 810002, 810003, 810004] if base == "iron_pickaxe" else [810001, 810002]
                self.assertEqual(expected, [e["threshold"] for e in dispatch["entries"]])
                self.assertEqual(f"chrdk_items:item/{name}", dispatch["entries"][0]["model"]["model"])
                self.assertEqual(dispatch["fallback"], dispatch["entries"][1]["model"])
                if base == "iron_shovel":
                    self.assertEqual("chrdk_items:item/mud_shovel", dispatch["entries"][2]["model"]["model"])
                    self.assertEqual(dispatch["fallback"], dispatch["entries"][3]["model"])
                    special = json.loads((root / "models/item/mud_shovel.json").read_text())
                    self.assertEqual("chrdk_items:item/mud_shovel", special["textures"]["layer0"])
                    image = (root / "textures/item/mud_shovel.png").read_bytes()
                    self.assertEqual(b"\x89PNG\r\n\x1a\n", image[:8])
                    self.assertEqual((64, 64), struct.unpack(">II", image[16:24]))
                data = (root / f"textures/item/{name}.png").read_bytes()
                self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8])
                self.assertEqual((64, 64), struct.unpack(">II", data[16:24]))

    def test_generator_core_model_is_complete(self):
        root = PACK / 'assets/chrdk_generator'
        model = json.loads((root / 'models/item/core.json').read_text())
        item = json.loads((root / 'items/core.json').read_text())
        self.assertEqual('minecraft:item/generated', model['parent'])
        self.assertEqual('chrdk_generator:item/core', model['textures']['layer0'])
        self.assertEqual({'type': 'minecraft:model', 'model': 'chrdk_generator:item/core'}, item['model'])
        data = (root / 'textures/item/core.png').read_bytes()
        self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
        self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))

    def test_mob_loot_models_cover_every_kind(self):
        source = (PACK.parents[1] / 'mobs/src/main/java/ru/cherdakmd/chrdkmobs/MobLootItems.java').read_text()
        ids = re.findall(r'^\s*(?:ESSENCE|VARIANT)_[A-Z]+\("([a-z-]+)",', source, re.MULTILINE)
        self.assertEqual(7, len(ids)); self.assertEqual(7, len(set(ids)))
        root = PACK / 'assets/chrdk_mobs'; hashes = set()
        for name in ids:
            path = 'loot/' + name
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            self.assertEqual('minecraft:item/generated', model['parent'])
            self.assertEqual('chrdk_mobs:item/' + path, model['textures']['layer0'])
            self.assertEqual({'type': 'minecraft:model', 'model': 'chrdk_mobs:item/' + path}, item['model'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
            self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(set(ids), {p.stem for p in (root / folder / 'loot').iterdir()})

    def test_skill_crystals_cover_all_skill_ids_with_distinct_original_textures(self):
        repo = PACK.parents[1]
        source = (repo / 'skills/src/main/java/ru/cherdakmd/chrdkskills/SkillType.java').read_text()
        skills = re.findall(r'^\s*[A-Z]+\("([a-z]+)", Category\.', source, re.MULTILINE)
        self.assertEqual(10, len(skills)); self.assertEqual(len(skills), len(set(skills)))
        root = PACK / 'assets/chrdk_skills'
        images = set()
        for skill in skills:
            with self.subTest(skill=skill):
                item = json.loads((root / f'items/crystal/{skill}.json').read_text())['model']
                self.assertEqual({'type': 'minecraft:model', 'model': f'chrdk_skills:item/crystal/{skill}'}, item)
                model = json.loads((root / f'models/item/crystal/{skill}.json').read_text())
                self.assertEqual('minecraft:item/generated', model['parent'])
                self.assertEqual(f'chrdk_skills:item/crystal/{skill}', model['textures']['layer0'])
                data = (root / f'textures/item/crystal/{skill}.png').read_bytes()
                self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
                self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
                digest = hashlib.sha256(data).hexdigest()
                self.assertNotIn(digest, images); images.add(digest)
        for directory in ('items', 'models/item', 'textures/item'):
            self.assertEqual(set(skills), {p.stem for p in (root / directory / 'crystal').iterdir()})

    def test_enchant_book_models_cover_catalogue_and_snapshot_without_vanilla_overrides(self):
        repo = PACK.parents[1]
        source = (repo / 'enchants/src/main/java/ru/cherdakmd/chrdkenchants/EnchantRegistry.java').read_text()
        custom = re.findall(r'register\("([a-z0-9_]+)",\s*(?:COMMON|RARE|EPIC|LEGENDARY),\s*\d+,\s*(?:null|"[^"]+"),\s*(?:LIGHT|CURSE)', source)
        self.assertEqual(75, len(custom)); self.assertEqual(len(custom), len(set(custom)))
        policy = (repo / 'enchants/src/main/java/ru/cherdakmd/chrdkenchants/BookVisualPolicy.java').read_text()
        snapshot = policy.split('static final Set<String> VANILLA = Set.of(', 1)[1].split(');', 1)[0]
        vanilla = re.findall(r'"([a-z_]+)"', snapshot)
        self.assertEqual(43, len(vanilla)); self.assertEqual(len(vanilla), len(set(vanilla)))
        reviewed = json.loads((repo / 'enchants/vanilla-enchantments-26.1.json').read_text())
        self.assertEqual(set(reviewed['ids']), set(vanilla))
        names = {f'custom/{name}' for name in custom + ['lavawalker']}
        names |= {f'vanilla/{name}' for name in vanilla} | {'mixed/custom', 'mixed/vanilla', 'mixed/both'}
        root = PACK / 'assets/chrdk_enchants'
        images = set()
        for name in names:
            with self.subTest(book=name):
                item = json.loads((root / f'items/book/{name}.json').read_text())['model']
                self.assertEqual({'type': 'minecraft:model', 'model': f'chrdk_enchants:item/book/{name}'}, item)
                model = json.loads((root / f'models/item/book/{name}.json').read_text())
                self.assertEqual('minecraft:item/generated', model['parent'])
                self.assertEqual(f'chrdk_enchants:item/book/{name}', model['textures']['layer0'])
                data = (root / f'textures/item/book/{name}.png').read_bytes()
                self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
                digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, images); images.add(digest)
        for directory in ('items', 'models/item', 'textures/item'):
            base = root / directory / 'book'
            self.assertEqual(names, {p.relative_to(base).with_suffix('').as_posix() for p in base.rglob('*') if p.is_file()})
        self.assertFalse((PACK / 'assets/minecraft/items/enchanted_book.json').exists())

    def test_artifact_collection_icons_are_complete_and_distinct(self):
        root = PACK / 'assets/chrdk_artifacts'
        names = {'unknown', 'previous', 'next', 'rewards', 'progress', 'close'}
        images = set()
        for name in names:
            with self.subTest(icon=name):
                key = 'collection/' + name
                item = json.loads((root / f'items/{key}.json').read_text())['model']
                self.assertEqual({'type': 'minecraft:model', 'model': f'chrdk_artifacts:item/{key}'}, item)
                model = json.loads((root / f'models/item/{key}.json').read_text())
                self.assertEqual('minecraft:item/generated', model['parent'])
                self.assertEqual(f'chrdk_artifacts:item/{key}', model['textures']['layer0'])
                data = (root / f'textures/item/{key}.png').read_bytes()
                self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
                self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
                digest = hashlib.sha256(data).hexdigest()
                self.assertNotIn(digest, images); images.add(digest)
        for directory in ('items', 'models/item', 'textures/item'):
            self.assertEqual(names, {p.stem for p in (root / directory / 'collection').iterdir()})

    def test_artifact_textures_cover_registry_with_distinct_models(self):
        repo = PACK.parents[1]
        source = (repo / 'artifacts/src/main/java/ru/cherdakmd/chrdkartifacts/ArtifactRegistry.java').read_text()
        ids = re.findall(r'register\("([a-z0-9-]+)",\s*(?:GOOD|CURSED),\s*(?:ACTIVE|PASSIVE),\s*Material\.[A-Z_]+', source)
        self.assertEqual(65, len(ids)); self.assertEqual(len(ids), len(set(ids)))
        root = PACK / 'assets/chrdk_artifacts'
        images = set()
        for artifact in ids:
            with self.subTest(artifact=artifact):
                name = 'artifact/' + artifact
                item = json.loads((root / f'items/{name}.json').read_text())['model']
                self.assertEqual({'type': 'minecraft:model', 'model': f'chrdk_artifacts:item/{name}'}, item)
                model = json.loads((root / f'models/item/{name}.json').read_text())
                self.assertEqual('minecraft:item/generated', model['parent'])
                self.assertEqual(f'chrdk_artifacts:item/{name}', model['textures']['layer0'])
                data = (root / f'textures/item/{name}.png').read_bytes()
                self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
                self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
                digest = hashlib.sha256(data).hexdigest()
                self.assertNotIn(digest, images); images.add(digest)
        for directory in ('items', 'models/item', 'textures/item'):
            self.assertEqual(set(ids), {p.stem for p in (root / directory / 'artifact').iterdir()})

    def test_menu_icons_have_unique_textures_and_complete_model_links(self):
        root = PACK / 'assets/chrdk_menu'
        repo = PACK.parents[1]
        manifest_text = (repo / 'src/main/resources/server-menu-visuals.yml').read_text(encoding='utf-8')
        manifest = [json.loads(line[line.index('{'):]) for line in manifest_text.splitlines()
                    if line.lstrip().startswith('- {')]
        self.assertEqual(45, len(manifest))
        stock_text = (repo / 'src/main/resources/config.yml').read_text(encoding='utf-8')
        menu = stock_text.split('server-menu:\n', 1)[1].split('\n# Алмазный банк:', 1)[0]
        stock = [json.loads(line[line.index('{'):]) for line in menu.splitlines()
                 if line.lstrip().startswith('- {') and '"id"' not in line]
        self.assertEqual(stock, [{k: v for k, v in entry.items() if k != 'visual'} for entry in manifest])
        ids = [entry['visual'] for entry in manifest]
        self.assertEqual(len(ids), len(set(ids)))
        names = {f'category/{x}' for x in ('account', 'progress', 'economy', 'world', 'gear', 'social')}
        names |= {'navigation/back', 'navigation/close'} | {f'entry/{name}' for name in ids}
        images = set()
        for name in names:
            with self.subTest(name=name):
                item = json.loads((root / f'items/{name}.json').read_text())['model']
                self.assertEqual({'type': 'minecraft:model', 'model': f'chrdk_menu:item/{name}'}, item)
                model = json.loads((root / f'models/item/{name}.json').read_text())
                self.assertEqual('minecraft:item/generated', model['parent'])
                self.assertEqual(f'chrdk_menu:item/{name}', model['textures']['layer0'])
                data = (root / f'textures/item/{name}.png').read_bytes()
                self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
                self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
                digest = hashlib.sha256(data).hexdigest()
                self.assertNotIn(digest, images); images.add(digest)
        for directory in ('items', 'models/item', 'textures/item'):
            files = (root / directory).rglob('*')
            self.assertEqual(names, {p.relative_to(root / directory).with_suffix('').as_posix() for p in files if p.is_file()})

    def test_new_builtin_models_and_bounded_dispatch(self):
        root = PACK / "assets/chrdk_items"
        for base, name, threshold in [("iron_shovel", "litter_scoop", 810005), ("iron_pickaxe", "weather_gauge", 810003), ("wooden_hoe", "cat_finder", 810003), ("stone_axe", "spawn_guide", 810003)]:
            with self.subTest(item=name):
                model = json.loads((root / f"models/item/{name}.json").read_text())
                self.assertEqual(f"chrdk_items:item/{name}", model["textures"]["layer0"])
                item = json.loads((root / f"items/item/{name}.json").read_text())
                self.assertEqual(f"chrdk_items:item/{name}", item["model"]["model"])
                data = (root / f"textures/item/{name}.png").read_bytes()
                self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8])
                self.assertEqual((64, 64), struct.unpack(">II", data[16:24]))
                dispatch = json.loads((PACK / f"assets/minecraft/items/{base}.json").read_text())["model"]
                entries = dispatch["entries"]
                self.assertEqual(sorted(e["threshold"] for e in entries), [e["threshold"] for e in entries])
                entry = next(i for i, e in enumerate(entries) if e["threshold"] == threshold)
                self.assertEqual(f"chrdk_items:item/{name}", entries[entry]["model"]["model"])
                self.assertEqual(threshold + 1, entries[entry + 1]["threshold"])
                self.assertEqual(dispatch["fallback"], entries[entry + 1]["model"])


if __name__ == "__main__":
    unittest.main()
