#!/usr/bin/env python3
"""Проверка патченого Mine Treasure RU, манифеста и его проводки в CI/релизе."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("build_mine_treasure.py")
INSTALLER_PATH = Path(__file__).with_name("install_mine_treasure.py")
ROOT = MODULE_PATH.resolve().parents[2]
spec = importlib.util.spec_from_file_location("build_mine_treasure", MODULE_PATH)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

installer_spec = importlib.util.spec_from_file_location("install_mine_treasure_for_build_test", INSTALLER_PATH)
assert installer_spec and installer_spec.loader
installer = importlib.util.module_from_spec(installer_spec)
sys.modules[installer_spec.name] = installer
installer_spec.loader.exec_module(installer)


class BuildMineTreasureTest(unittest.TestCase):

    def test_check_mode_accepts_committed_zip_and_manifest(self) -> None:
        manifest = module.check(module.DEFAULT_OUTPUT, module.DEFAULT_MANIFEST)
        self.assertEqual(2, manifest["schema"])
        self.assertEqual(3034, manifest["entries"])
        self.assertEqual(825, manifest["json_files"])
        self.assertEqual(1132, manifest["set_attributes_blocks"])
        self.assertEqual(1507, manifest["set_components_blocks"])
        self.assertEqual(29, manifest["prefixed_water_attributes"])
        self.assertEqual(0, manifest["slot_arrays"])
        self.assertEqual(1507, manifest["pdc_markers"])
        self.assertEqual(43, len(manifest["block_loot_tables"]))
        self.assertEqual("0ff4bd2ad1a5db22920db23f1f2c88d577ceff607164638d0e1a45264075bf92",
                         manifest["source_sha256"])
        self.assertEqual(1_747_330, manifest["source_size"])
        self.assertEqual(29, manifest["fixed_unprefixed_attributes"])
        self.assertEqual(5, manifest["fixed_slot_arrays"])
        self.assertEqual(1507, manifest["added_pdc_markers"])

    def test_committed_zip_hash_matches_manifest_and_installer(self) -> None:
        manifest = json.loads(module.DEFAULT_MANIFEST.read_text(encoding="utf-8"))
        actual = hashlib.sha256(module.DEFAULT_OUTPUT.read_bytes()).hexdigest()
        self.assertEqual(actual, manifest["patched_sha256"])
        self.assertEqual(module.DEFAULT_OUTPUT.stat().st_size, manifest["patched_size"])
        self.assertEqual(actual, installer.KNOWN_SHA256)
        self.assertEqual(manifest["patched_size"], installer.KNOWN_SIZE)
        self.assertEqual(module.DEFAULT_OUTPUT.resolve(), installer.DEFAULT_SOURCE.resolve())
        self.assertEqual(actual, installer.sha256_file(installer.DEFAULT_SOURCE))

    def test_cli_check_is_green(self) -> None:
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--check"],
            cwd=ROOT, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("патченый ZIP проверен", result.stdout)

    def test_patch_document_adds_marker_without_losing_custom_data(self) -> None:
        document = {
            "pools": [{
                "entries": [
                    {"type": "minecraft:set_components", "components": {
                        "minecraft:custom_data": {"mt": "charm"}}},
                    {"type": "minecraft:set_components", "components": {}},
                    {"type": "minecraft:set_components", "components": {
                        "minecraft:custom_data": {
                            "PublicBukkitValues": {module.PDC_KEY: module.PDC_VALUE}}}},
                ]
            }]
        }
        stats = module.patch_document(document)
        self.assertEqual(3, stats["set_components"])
        self.assertEqual(2, stats["markers"])
        self.assertEqual(1, stats["markers_existing"])
        pdc = document["pools"][0]["entries"][0]["components"]["minecraft:custom_data"]["PublicBukkitValues"]
        self.assertEqual(module.PDC_VALUE, pdc[module.PDC_KEY])
        self.assertEqual("charm", document["pools"][0]["entries"][0]["components"]["minecraft:custom_data"]["mt"])

    def test_patch_json_fixes_codecs_and_serializes(self) -> None:
        original = json.dumps({
            "type": "minecraft:set_attributes",
            "modifiers": [{"attribute": "water_movement_efficiency", "slot": ["mainhand"]}],
        }, ensure_ascii=False)
        patched, attributes, slots, stats = module.patch_json(original, "fixture.json")
        self.assertEqual(1, attributes)
        self.assertEqual(1, slots)
        self.assertEqual(0, stats["set_components"])
        text = patched.decode("utf-8")
        self.assertIn('"minecraft:water_movement_efficiency"', text)
        self.assertIn('"slot": "mainhand"', text)
        self.assertNotIn('"slot": [', text)
        self.assertEqual("mainhand", json.loads(text)["modifiers"][0]["slot"])

    def test_block_table_keys_are_namespaced(self) -> None:
        keys = module.block_table_keys([
            "data/minecraft/loot_table/blocks/stone.json",
            "data/minecraft/loot_table/blocks/diamond_ore.json",
            "data/mt/loot_table/chests/ocean.json",
        ])
        self.assertEqual(["minecraft:blocks/diamond_ore", "minecraft:blocks/stone"], keys)

    def test_gitignore_allows_committed_patched_zip(self) -> None:
        ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertNotIn("/datapacks/mine-treasure-ru/*.zip", ignore)
        self.assertIn("/mine-treasure-1.6.5-ru.zip", ignore)

    def test_ci_and_release_workflows_reference_patched_zip(self) -> None:
        build = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("build_mine_treasure.py --check", build)
        self.assertIn("build_mine_treasure.py --check", release)
        self.assertIn("datapacks/mine-treasure-ru/mine-treasure-1.6.5-ru.zip", release)


if __name__ == "__main__":
    unittest.main()
