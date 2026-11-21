"""Offline structural checks; Minecraft rendering still requires a client smoke test."""
import json
from pathlib import Path
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
