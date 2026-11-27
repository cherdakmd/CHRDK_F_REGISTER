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

    def test_shop_book_model_allowlists_match_the_pack(self):
        source = (PACK.parents[1] / 'diamond-shop/src/main/java/ru/cherdakmd/diamondshop/ShopBookVisuals.java').read_text(encoding='utf-8')
        for kind, constant in [('custom', 'CUSTOM'), ('vanilla', 'VANILLA')]:
            match = re.search(r'private static final Set<String> ' + constant + r' = Set.of\((.*?)\);', source, re.DOTALL)
            self.assertIsNotNone(match)
            ids = re.findall(r'"([a-z_]+)"', match.group(1))
            files = {p.stem for p in (PACK / 'assets/chrdk_enchants/items/book' / kind).glob('*.json')}
            self.assertEqual(len(ids), len(set(ids)))
            self.assertEqual(files, set(ids))

    def test_battlepass_mission_menu_models_are_complete_and_distinct(self):
        root = PACK / 'assets/chrdk_battlepass'
        types = ('mine', 'kill', 'craft', 'fish', 'place', 'smelt', 'enchant', 'breed')
        states = ('progress', 'ready', 'vip_locked', 'premium_locked', 'claimed')
        names = {f'mission_{kind}_{state}' for kind in types for state in states}
        names.update(('mission_future', 'empty', 'prev', 'next', 'claim_all', 'back', 'summary', 'refresh', 'premium', 'close'))
        names.update('filter_' + f for f in ('all', 'active', 'ready', 'future', 'done'))
        self.assertEqual(55, len(names)); hashes = set()
        for name in names:
            path = 'menu/' + name; ref = 'chrdk_battlepass:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type':'minecraft:model','model':ref}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent']); self.assertEqual(ref, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32,32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(names, {p.stem for p in (root / folder / 'menu').iterdir() if p.is_file()})
        config = (PACK.parents[1] / 'battlepass/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(config, r'(?m)^  mission-menu: false\s*$')

    def test_guild_party_models_cover_controls_and_status(self):
        root = PACK / 'assets/chrdk_guilds'
        names = {'empty', 'group', 'invite', 'accept', 'decline', 'leave', 'close', 'prev', 'back', 'next'}
        hashes = set()
        for name in names:
            path = 'menu/party/' + name; ref = 'chrdk_guilds:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type':'minecraft:model','model':ref}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent']); self.assertEqual(ref, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32,32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(names, {p.stem for p in (root / folder / 'menu/party').iterdir() if p.is_file()})
        config = (PACK.parents[1] / 'guilds/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(config, r'(?m)^  party-menu: false\s*$')

    def test_battlepass_expedition_models_cover_controls_and_states(self):
        root = PACK / 'assets/chrdk_battlepass'
        names = {f'license_{level}_{state}' for level in ('free', 'premium') for state in ('missing', 'owned')}
        names.update(('effect_on', 'effect_off', 'title_off', 'title_free', 'title_premium',
                      'mark_current', 'marks', 'routes', 'journal', 'labels', 'claim', 'back', 'journal_back',
                      'close', 'route_create', 'waypoint', 'saved_route'))
        self.assertEqual(21, len(names)); hashes = set()
        for name in names:
            path = 'menu/expedition/' + name; ref = 'chrdk_battlepass:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type':'minecraft:model','model':ref}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent']); self.assertEqual(ref, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32,32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(names, {p.stem for p in (root / folder / 'menu/expedition').iterdir() if p.is_file()})
        config = (PACK.parents[1] / 'battlepass/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(config, r'(?m)^  expedition-menu: false\s*$')

    def test_battlepass_guide_models_cover_buttons_tracks_and_states(self):
        root = PACK / 'assets/chrdk_battlepass'
        names = {'season', 'tasks', 'rewards', 'help', 'claim_all', 'premium', 'atlas', 'expeditions', 'bank',
                 'recommended', 'close', 'reward_page', 'free', 'vip', 'premium_label', 'mission', 'back', 'prev',
                 'next', 'claim_one', 'fair', 'targets'}
        names.update(f'track_{track}_{state}' for track in ('free', 'vip', 'premium')
                     for state in ('no_reward', 'received', 'future', 'access_locked', 'in_progress', 'ready'))
        self.assertEqual(40, len(names)); hashes = set()
        for name in names:
            path = 'menu/guide/' + name; ref = 'chrdk_battlepass:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type':'minecraft:model','model':ref}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent']); self.assertEqual(ref, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32,32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(names, {p.stem for p in (root / folder / 'menu/guide').iterdir() if p.is_file()})
        config = (PACK.parents[1] / 'battlepass/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(config, r'(?m)^  guide-menu: false\s*$')

    def test_generator_menu_models_cover_controls_and_states(self):
        root = PACK / 'assets/chrdk_generator'
        names = {f'status_{s}' for s in ('working', 'no_lava', 'no_water', 'no_cells', 'unloaded', 'carried')}
        names.update(f'tier_{i}' for i in range(1, 16))
        names.update(f'module_{m}_{state}' for m in ('speed','capacity','luck') for state in ('available','max'))
        names.update(('upgrade_tier_available','upgrade_tier_max','chunk_available','chunk_owned','bunker_empty','bunker_partial','bunker_full'))
        names.update(('rebirth','take','close','fuel_help','fuel_total','container_refresh','fuel_open','fuel_back','container_settings','container_close'))
        self.assertEqual(44,len(names)); hashes=set()
        for name in names:
            path = 'menu/' + name; ref = 'chrdk_generator:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type':'minecraft:model','model':ref},item['model'])
            self.assertEqual('minecraft:item/generated',model['parent']); self.assertEqual(ref,model['textures']['layer0'])
            data=(root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n',data[:8]); self.assertEqual((32,32),struct.unpack('>II',data[16:24]))
            digest=hashlib.sha256(data).hexdigest(); self.assertNotIn(digest,hashes); hashes.add(digest)
        for folder in ('items','models/item','textures/item'):
            self.assertEqual(names,{p.stem for p in (root / folder / 'menu').iterdir()})
        config=(PACK.parents[1] / 'generator/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(config,r'(?m)^  menu: false\s*$')

    def test_claim_menu_icons_have_complete_models(self):
        root = PACK / 'assets/chrdk_claims'
        names = {'info', 'prev', 'next', 'clan', 'close', 'empty_mine', 'empty_sale', 'entry_mine', 'entry_other_world', 'entry_sale', 'entry_access'}
        names.update(f'tab_{tab}_{state}' for tab in ('mine', 'sale', 'access') for state in ('normal', 'selected'))
        self.assertEqual(17, len(names)); hashes = set()
        for name in names:
            path = 'menu/' + name; ref = 'chrdk_claims:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type': 'minecraft:model', 'model': ref}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent']); self.assertEqual(ref, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(names, {p.stem for p in (root / folder / 'menu').iterdir()})
        config = (PACK.parents[1] / 'claims/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(config, r'(?m)^textures:\s*\n  menu: false\s*$')

    def test_vk_menu_icons_have_complete_fixed_models(self):
        root = PACK / 'assets/chrdk_vk'
        names = {'link_unlinked', 'link_linked', 'notify_unlinked', 'notify_off', 'notify_on', 'chat_hidden',
                 'chat_visible', 'badge', 'security_active', 'security_frozen', 'unlink_confirm', 'close'}
        source = (PACK.parents[1] / 'vk/src/main/java/ru/cherdakmd/chrdkvk/VkMenuVisuals.java').read_text(encoding='utf-8')
        enum = re.search(r'enum Icon \{(.*?);', source, re.DOTALL)
        self.assertIsNotNone(enum); self.assertEqual(names, {n.lower() for n in re.findall(r'[A-Z_]+', enum.group(1))})
        hashes = set()
        for name in names:
            path = 'menu/' + name; ref = 'chrdk_vk:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type': 'minecraft:model', 'model': ref}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent']); self.assertEqual(ref, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(names, {p.stem for p in (root / folder / 'menu').iterdir()})
        config = (PACK.parents[1] / 'vk/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(config, r'(?m)^textures:\s*\n  menu: false\s*$')

    def test_catalog_navigation_icons_are_complete_and_distinct(self):
        root = PACK / 'assets/chrdk_items'
        names = {f'{category}_{state}' for category in ('all', 'weapon', 'armor', 'tool', 'consumable') for state in ('normal', 'selected')}
        names.update(('prev', 'next', 'close'))
        self.assertEqual(13, len(names)); hashes = set()
        for name in names:
            path = 'menu/catalog/' + name; ref = 'chrdk_items:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type': 'minecraft:model', 'model': ref}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent']); self.assertEqual(ref, model['textures']['layer0'])
            image = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', image[:8]); self.assertEqual((32, 32), struct.unpack('>II', image[16:24]))
            digest = hashlib.sha256(image).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(names, {p.stem for p in (root / folder / 'menu/catalog').iterdir()})
        config = (PACK.parents[1] / 'custom-items/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(config, r'(?m)^textures:\s*\n  catalog-menu: false\s*$')

    def test_cosmetics_gui_stock_states_and_controls(self):
        root = PACK / 'assets/chrdk_cosmetics'
        trails = ('flame', 'hearts', 'notes', 'sparkles', 'magic', 'gold', 'aurora')
        states = ('available', 'selected', 'locked', 'suspended', 'unavailable')
        controls = ('summary', 'trails', 'join', 'colors', 'labels', 'slots', 'presets', 'hat', 'pets',
                    'menu', 'close', 'empty', 'prev', 'next', 'back', 'clear', 'trails_info')
        paths = {f'menu/trail/{name}/{state}' for name in trails for state in states}
        paths.update(f'menu/{name}' for name in controls)
        self.assertEqual(52, len(paths)); hashes = set()
        for path in paths:
            reference = 'chrdk_cosmetics:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type': 'minecraft:model', 'model': reference}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent']); self.assertEqual(reference, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder, suffix in [('items', '.json'), ('models/item', '.json'), ('textures/item', '.png')]:
            actual = {str(p.relative_to(root / folder).with_suffix('')) for p in (root / folder / 'menu').rglob('*' + suffix)}
            self.assertEqual(paths, actual)
        source = (PACK.parents[1] / 'cosmetics/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(source, r'(?m)^textures:\s*\n  menu: false\s*$')

    def test_daily_calendar_models_cover_every_state(self):
        root = PACK / 'assets/chrdk_daily'
        names = ('header', 'claimed', 'claimed_today', 'today', 'locked')
        hashes = set()
        for name in names:
            path = 'menu/' + name; reference = 'chrdk_daily:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type': 'minecraft:model', 'model': reference}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent'])
            self.assertEqual(reference, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(set(names), {p.stem for p in (root / folder / 'menu').iterdir()})
        source = (PACK.parents[1] / 'daily-rewards/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(source, r'(?m)^textures:\s*\n  menu: false\s*$')

    def test_achievement_menu_models_cover_tabs_and_entry_states(self):
        root = PACK / 'assets/chrdk_achievements'
        categories = ('path', 'combat', 'craft', 'social')
        paths = {f'menu/entry/{name}_{state}' for name in categories for state in ('locked', 'unlocked')}
        paths.update(f'menu/tab/{name}_{state}' for name in (*categories, 'all') for state in ('inactive', 'selected'))
        paths.update(f'menu/{name}' for name in ('atlas', 'progress', 'prev', 'next', 'close'))
        self.assertEqual(23, len(paths))
        source = (PACK.parents[1] / 'achievements/src/main/java/ru/cherdakmd/chrdkachievements/AchievementDef.java').read_text(encoding='utf-8')
        self.assertEqual(list(categories), re.findall(r'^        [A-Z_]+\("([a-z_]+)"\)', source, re.MULTILINE))
        hashes = set()
        for path in paths:
            reference = 'chrdk_achievements:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type': 'minecraft:model', 'model': reference}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent'])
            self.assertEqual(reference, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder, suffix in [('items', '.json'), ('models/item', '.json'), ('textures/item', '.png')]:
            actual = {str(p.relative_to(root / folder).with_suffix('')) for p in (root / folder / 'menu').rglob('*' + suffix)}
            self.assertEqual(paths, actual)
        source = (PACK.parents[1] / 'achievements/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(source, r'(?m)^textures:\s*\n  menu: false\s*$')

    def test_skills_menu_navigation_and_states_are_complete(self):
        root = PACK / 'assets/chrdk_skills'
        tabs = ('skills', 'attributes', 'top', 'rebirth', 'potions', 'builds')
        controls = ('guide', 'back', 'close', 'steps', 'summary', 'perk_active', 'perk_locked',
                    'reset_attributes', 'confirm_attributes', 'reset_builds', 'confirm_builds',
                    'rank_entry', 'rank_empty', 'rebirth_title', 'rebirth_top', 'shop_open', 'shop_closed')
        paths = {f'menu/tab/{name}{suffix}' for name in tabs for suffix in ('', '_selected')}
        paths.update(f'menu/{name}' for name in controls)
        skills = ('mining', 'woodcutting', 'digging', 'farming', 'fishing',
                  'combat', 'archery', 'defense', 'alchemy', 'enchanting')
        attributes = ('strength', 'agility', 'stamina', 'wisdom', 'cunning')
        branches = ('warrior', 'archer', 'mage', 'cleric', 'priest', 'rogue')
        paths.update(f'menu/skill/{name}' for name in skills)
        paths.update(f'menu/ability/{name}' for name in skills)
        paths.update(f'menu/rebirth/{name}_{state}' for name in skills for state in ('waiting', 'ready'))
        paths.update(f'menu/attribute/{name}' for name in attributes)
        paths.update(f'menu/branch/{name}' for name in branches)
        self.assertEqual(80, len(paths))
        for cls, ids in [('SkillType', skills), ('PlayerAttribute', attributes), ('BuildBranch', branches)]:
            enum_source = (PACK.parents[1] / f'skills/src/main/java/ru/cherdakmd/chrdkskills/{cls}.java').read_text(encoding='utf-8')
            entries = re.findall(r'^    [A-Z_]+\("([a-z_]+)"', enum_source, re.MULTILINE)
            self.assertEqual(list(ids), entries, cls)
        hashes = set()
        for path in paths:
            reference = 'chrdk_skills:item/' + path
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            self.assertEqual({'type': 'minecraft:model', 'model': reference}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent'])
            self.assertEqual(reference, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
            self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest()
            self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder, suffix in [('items', '.json'), ('models/item', '.json'), ('textures/item', '.png')]:
            actual = {str(p.relative_to(root / folder).with_suffix('')) for p in (root / folder / 'menu').rglob('*' + suffix)}
            self.assertEqual(paths, actual)
        source = (PACK.parents[1] / 'skills/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(source, r'(?ms)^textures:\s*\n.*?^  menu: false\s*$')

    def test_quests_menu_models_cover_every_type_and_state(self):
        root = PACK / 'assets/chrdk_quests'
        types = ('kill', 'mine', 'gather')
        states = ('available', 'in_progress', 'ready', 'done', 'locked', 'wrong_faction', 'rank')
        paths = {f'menu/quest/{kind}_{state}' for kind in types for state in states}
        paths.update({'menu/info', 'menu/back', 'menu/close'})
        hashes = set()
        for path in paths:
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            reference = 'chrdk_quests:item/' + path
            self.assertEqual({'type': 'minecraft:model', 'model': reference}, item['model'])
            self.assertEqual('minecraft:item/generated', model['parent'])
            self.assertEqual(reference, model['textures']['layer0'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8])
            self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest()
            self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder, suffix in [('items', '.json'), ('models/item', '.json'), ('textures/item', '.png')]:
            actual = {str(p.relative_to(root / folder).with_suffix('')) for p in (root / folder / 'menu').rglob('*' + suffix)}
            self.assertEqual(paths, actual)
        source = (PACK.parents[1] / 'quests/src/main/resources/config.yml').read_text(encoding='utf-8')
        self.assertRegex(source, r'(?m)^textures:\s*\n  menu: false\s*$')

    def test_guide_book_model_is_complete(self):
        root = PACK / 'assets/chrdk_core'
        model = json.loads((root / 'models/item/guide_book.json').read_text())
        item = json.loads((root / 'items/guide_book.json').read_text())
        self.assertEqual('minecraft:item/generated', model['parent'])
        self.assertEqual('chrdk_core:item/guide_book', model['textures']['layer0'])
        self.assertEqual({'type': 'minecraft:model', 'model': 'chrdk_core:item/guide_book'}, item['model'])
        image = (root / 'textures/item/guide_book.png').read_bytes()
        self.assertEqual(b'\x89PNG\r\n\x1a\n', image[:8])
        self.assertEqual((32, 32), struct.unpack('>II', image[16:24]))
        self.assertFalse((PACK / 'assets/minecraft/items/written_book.json').exists())

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

    def test_rebirth_reward_models_cover_stock_config(self):
        source = (PACK.parents[1] / 'skills/src/main/resources/config.yml').read_text(encoding='utf-8')
        section = source.split('  unique-items:', 1)[1].split('\n# Награды навыков', 1)[0]
        ids = re.findall(r'^\s*- id: ([a-z_]+)', section, re.MULTILINE)
        self.assertEqual({'heart_of_the_sea', 'nether_star', 'dragon_egg'}, set(ids)); self.assertEqual(3, len(ids))
        root = PACK / 'assets/chrdk_skills'; hashes = set()
        for name in ids:
            path = 'rebirth/' + name
            model = json.loads((root / 'models/item' / (path + '.json')).read_text())
            item = json.loads((root / 'items' / (path + '.json')).read_text())
            self.assertEqual('minecraft:item/generated', model['parent'])
            self.assertEqual('chrdk_skills:item/' + path, model['textures']['layer0'])
            self.assertEqual({'type': 'minecraft:model', 'model': 'chrdk_skills:item/' + path}, item['model'])
            data = (root / 'textures/item' / (path + '.png')).read_bytes()
            self.assertEqual(b'\x89PNG\r\n\x1a\n', data[:8]); self.assertEqual((32, 32), struct.unpack('>II', data[16:24]))
            digest = hashlib.sha256(data).hexdigest(); self.assertNotIn(digest, hashes); hashes.add(digest)
        for folder in ('items', 'models/item', 'textures/item'):
            self.assertEqual(set(ids), {p.stem for p in (root / folder / 'rebirth').iterdir()})

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
        paper_api = json.loads((repo / 'enchants/vanilla-enchantments-26.3-api.json').read_text())
        self.assertEqual(len(paper_api['ids']), len(set(paper_api['ids'])))
        self.assertEqual(set(paper_api['ids']), set(vanilla))
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
