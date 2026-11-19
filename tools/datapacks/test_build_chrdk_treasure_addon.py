#!/usr/bin/env python3
"""Проверка CHRDK Treasure Addon: сборки, инвариантов и нулевых конфликтов с Mine Treasure."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("build_chrdk_treasure_addon.py")
ROOT = MODULE_PATH.resolve().parents[2]
spec = importlib.util.spec_from_file_location("build_chrdk_treasure_addon", MODULE_PATH)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

MT_ZIP = ROOT / "datapacks" / "mine-treasure-ru" / "mine-treasure-1.6.5-ru.zip"
TREASURE_IDS = {"lucky_coin", "heavy_glove", "spring_boots", "smoke_bomb",
                "phoenix_wing", "star_key"}


class BuildChrdkTreasureAddonTest(unittest.TestCase):

    def test_check_accepts_committed_zip(self) -> None:
        report = module.check()
        self.assertEqual(29, report["files"])
        self.assertEqual(20, report["json_files"])
        self.assertEqual(7, report["mcfunction_files"])
        self.assertEqual(6, report["treasures"])
        self.assertEqual(3, report["resources"])
        self.assertEqual(6, report["pdc_markers"])

    def test_build_is_deterministic(self) -> None:
        self.assertEqual(module.build_bytes(), module.build_bytes())

    def test_cli_check_is_green(self) -> None:
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--check"],
            cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_cli_build_writes_identical_zip(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder) / "addon.zip"
            result = subprocess.run(
                [sys.executable, str(MODULE_PATH), "--output", str(out)],
                cwd=ROOT, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(module.DEFAULT_OUTPUT.read_bytes(), out.read_bytes(),
                             "собранный ZIP обязан совпадать с закоммиченным")

    def test_every_treasure_has_pdc_marker_name_lore_and_id(self) -> None:
        seen = set()
        for path in sorted(module.DEFAULT_SOURCE.glob("data/chrdk_treasure/loot_table/*.json")):
            doc = json.loads(path.read_text(encoding="utf-8"))
            entries = []
            module.walk_entries(doc, entries)
            for entry in entries:
                comps = next((m["components"] for m in entry.get("modifier", [])
                              if m.get("type") == "minecraft:set_components"), None)
                if comps is None:
                    continue  # обычный ресурс тайника
                custom = comps["minecraft:custom_data"]
                self.assertEqual("1", custom["PublicBukkitValues"]["chrdk:mine_treasure_item"],
                                 f"{path.name}: нет PDC-метки")
                self.assertIn("minecraft:custom_name", comps)
                self.assertIn("minecraft:lore", comps)
                self.assertTrue(comps["minecraft:lore"], f"{path.name}: пустой лор")
                seen.add(custom["cht_item"])
        self.assertEqual(TREASURE_IDS, seen)

    def test_references_resolve(self) -> None:
        files = module.source_files(module.DEFAULT_SOURCE)
        parsed = module.json_files(files)
        self.assertEqual([], module.check_references(files, parsed))

    def test_pack_format_matches_mine_treasure(self) -> None:
        pack = json.loads((module.DEFAULT_SOURCE / "pack.mcmeta").read_text(encoding="utf-8"))
        self.assertEqual([121, 1], pack["pack"]["min_format"])
        self.assertEqual([121, 1], pack["pack"]["max_format"])

    def test_addon_does_not_override_any_mine_treasure_file(self) -> None:
        self.assertTrue(MT_ZIP.is_file(), "патченый Mine Treasure должен быть в репо")
        with zipfile.ZipFile(MT_ZIP) as archive:
            upstream = {name for name in archive.namelist()
                        if not name.endswith("/") and name.startswith("data/")}
        ours = {name for name in module.source_files(module.DEFAULT_SOURCE)
                if name.startswith("data/")}
        self.assertEqual(set(), upstream & ours,
                         "аддон не должен переопределять данные Mine Treasure")

    def test_no_minecraft_tag_files(self) -> None:
        files = module.source_files(module.DEFAULT_SOURCE)
        self.assertEqual([], [name for name in files if name.startswith("data/minecraft/tags/")])

    def test_invariants_reject_minecraft_tag_file(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            src = Path(folder)
            (src / "pack.mcmeta").write_text(json.dumps({"pack": {
                "description": "x", "min_format": [121, 1], "max_format": [121, 1]}}),
                encoding="utf-8")
            tags = src / "data/minecraft/tags/function"
            tags.mkdir(parents=True)
            (tags / "load.json").write_text('{"values": ["cht:init"]}', encoding="utf-8")
            with self.assertRaises(module.BuildError):
                module.invariants(src)

    def test_roll_function_revokes_all_advancements(self) -> None:
        roll = (module.DEFAULT_SOURCE
                / "data/chrdk_treasure/function/roll.mcfunction").read_text(encoding="utf-8")
        for name in ("roll_overworld", "roll_nether", "roll_end"):
            self.assertIn(f"advancement revoke @s only chrdk_treasure:{name}", roll)
        self.assertIn("execute if predicate chrdk_treasure:chance_common", roll)
        self.assertIn("execute if predicate chrdk_treasure:chance_rare", roll)
        self.assertIn("execute if predicate chrdk_treasure:chance_legendary", roll)

    def test_consumption_matches_mine_treasure_pattern(self) -> None:
        for name in ("smoke_bomb", "phoenix_wing", "star_key"):
            text = (module.DEFAULT_SOURCE
                    / f"data/chrdk_treasure/function/items/{name}.mcfunction").read_text(
                encoding="utf-8")
            self.assertIn(
                "item modify entity @s[gamemode=!creative] weapon.mainhand "
                "chrdk_treasure:consume_one", text, name)
            self.assertIn(
                "item modify entity @s[gamemode=!creative] weapon.offhand "
                "chrdk_treasure:consume_one", text, name)
            self.assertIn("unless predicate", text,
                          f"{name}: расход из offhand только при пустом mainhand")

    def test_advancements_are_silent(self) -> None:
        for path in module.DEFAULT_SOURCE.glob("data/chrdk_treasure/advancement/*.json"):
            doc = json.loads(path.read_text(encoding="utf-8"))
            self.assertNotIn("display", doc, f"{path.name}: advancement должен быть тихим")

    def test_active_items_match_their_use_advancements(self) -> None:
        pairs = {
            "use_smoke_bomb": ("minecraft:slime_ball", "smoke_bomb"),
            "use_phoenix_wing": ("minecraft:feather", "phoenix_wing"),
            "use_star_key": ("minecraft:tripwire_hook", "star_key"),
        }
        for adv_name, (base, item_id) in pairs.items():
            doc = json.loads((module.DEFAULT_SOURCE
                              / f"data/chrdk_treasure/advancement/{adv_name}.json").read_text(
                encoding="utf-8"))
            tool = doc["criteria"]["requirement"]["conditions"]["location"]["predicate"]
            self.assertEqual([base], tool["items"])
            self.assertEqual("{cht_item:\"" + item_id + "\"}",
                             tool["predicates"]["minecraft:custom_data"])


if __name__ == "__main__":
    unittest.main()
