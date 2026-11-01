"""Материалы должны соответствовать трём существующим SKU и не исполнять выдачу."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest
import zipfile

from PIL import Image
import yaml

SPEC = importlib.util.spec_from_file_location("clan_shop_builder", Path(__file__).with_name("build.py"))
kit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(kit)


class ClanShopTest(unittest.TestCase):
    def setUp(self):
        self.products = kit.catalog()

    def test_three_existing_personal_thirty_day_cosmetics(self):
        self.assertEqual({"clan-name-style": 189, "clan-emblem": 179, "clan-card-frame": 219},
                         {p["id"]: p["price"] for p in self.products})
        self.assertTrue(all(p["days"] == 30 for p in self.products))
        live = yaml.safe_load((kit.ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8"))["shop"]["products"]
        for p in live:
            if p["id"] in kit.DETAILS:
                self.assertIs(False, p["trademc-export"], "общий экспорт не включается подготовкой отдельного комплекта")
                self.assertFalse(p["permissions"])
                self.assertFalse(p["commands"])

    def test_rejects_changed_duration_or_gameplay_grants(self):
        config = yaml.safe_load((kit.ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8"))
        original = next(p for p in config["shop"]["products"] if p["id"] == "clan-name-style")
        for field, value in (("days", 7), ("price", 0), ("rank", "V"),
                             ("permissions", ["test.permission"]), ("commands", ["test-command"]),
                             ("includes", ["other-product"])):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as folder:
                changed = dict(original)
                changed[field] = value
                fixture = Path(folder) / "src/main/resources/config.yml"
                fixture.parent.mkdir(parents=True)
                fixture.write_text(yaml.safe_dump({"shop": {"products": [
                    changed if p["id"] == changed["id"] else p for p in config["shop"]["products"]
                ]}}, allow_unicode=True), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "Изменились условия"):
                    kit.catalog(Path(folder))

    def test_rejects_missing_or_duplicate_product(self):
        config = yaml.safe_load((kit.ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8"))
        rows = config["shop"]["products"]
        original = next(p for p in rows if p["id"] == "clan-emblem")
        for changed in ([p for p in rows if p["id"] != "clan-emblem"], rows + [original]):
            with tempfile.TemporaryDirectory() as folder:
                fixture = Path(folder) / "src/main/resources/config.yml"
                fixture.parent.mkdir(parents=True)
                fixture.write_text(yaml.safe_dump({"shop": {"products": changed}}, allow_unicode=True), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "ровно один SKU"):
                    kit.catalog(Path(folder))

    def test_artwork_is_three_unique_real_square_pngs(self):
        hashes = set()
        for p in self.products:
            path = kit.OUT / p["image"]
            with Image.open(path) as image:
                self.assertEqual((1200, 1200), image.size)
                self.assertEqual("PNG", image.format)
                self.assertEqual("RGB", image.mode)
                image.verify()
            hashes.add(hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertEqual(3, len(hashes))

    def test_exactly_one_recommended_command_per_product(self):
        docs = kit.documents(self.products)
        lines = [line for line in docs["КОМАНДЫ_RCON.txt"].splitlines() if line.startswith("pass ")]
        self.assertEqual([p["command"] for p in self.products], lines)
        for p in self.products:
            self.assertEqual(f"pass shopbuy %username% {p['id']}", p["command"])
            self.assertEqual(1, docs["ТОВАРЫ.md"].count(p["command"]))
            self.assertNotIn("payment=", p["command"])
            self.assertNotIn("30", p["command"])
            self.assertFalse(p["command"].startswith("/"))
        self.assertFalse(any(line.startswith("pass shoppay") for line in docs["КОМАНДЫ_RCON.txt"].splitlines()))
        self.assertIn("не распознаётся автоматически как дубль", docs["КОМАНДЫ_RCON.txt"])

    def test_each_customer_description_explains_limits_without_power(self):
        for p in self.products:
            text = "\n".join(kit.descriptions(p))
            for phrase in ("30 дней", "Срок начинается с выдачи", "24 часа", "2 минут", "не заменяет", "Только внешний вид", "365 дней", "художественная иллюстрация"):
                self.assertIn(phrase, text)
            self.assertIn(f"/clan style {p['kind']} {p['example']}", text)
            self.assertNotIn("безлимит", text.lower())

    def test_number_of_options_matches_runtime_definitions(self):
        source = (kit.ROOT / "guilds/src/main/java/ru/cherdakmd/chrdkguilds/GuildStyleDefs.java").read_text(encoding="utf-8")
        colors = source.split("COLORS =", 1)[1].split("static final", 1)[0]
        emblems = source.split("EMBLEMS =", 1)[1].split("static final", 1)[0]
        frames = source.split("FRAMES =", 1)[1].split("static List", 1)[0]
        self.assertEqual(12, len(re.findall(r'Map.entry\("([a-z]+)"', colors)))
        self.assertEqual(8, len(re.findall(r'"([a-z]+)",\s*"', emblems)))
        self.assertEqual(6, len(re.findall(r'"([a-z]+)",\s*"', frames)))
        for p in self.products:
            self.assertIn('"' + p["example"] + '"', source)

    def test_committed_documents_and_manifest_match_catalog(self):
        kit.check()
        saved = json.loads((kit.OUT / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("1.4.278", saved["sourcePluginVersion"])
        self.assertIs(False, saved["publishedToStore"])
        self.assertEqual(3, len(saved["products"]))
        for row, p in zip(saved["products"], self.products):
            self.assertEqual(p["command"], row["command"])
            self.assertEqual(p["price"], row["priceRubles"])
            self.assertEqual(hashlib.sha256((kit.OUT / p["image"]).read_bytes()).hexdigest(), row["sha256"])

    def test_archive_has_three_images_and_only_ready_materials(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "clan-products.zip"
            kit.pack(archive)
            with zipfile.ZipFile(archive) as z:
                self.assertIsNone(z.testzip())
                names = z.namelist()
                self.assertEqual(8, len(names))
                self.assertEqual(3, sum(name.endswith(".png") for name in names))
                self.assertIn("ТОВАРЫ.md", names)
                self.assertIn("ОПИСАНИЯ_ТОВАРОВ.txt", names)
                self.assertIn("КОМАНДЫ_RCON.txt", names)
                self.assertFalse(any("art/" in name or name.endswith((".py", ".jar")) for name in names))
                self.assertTrue(all(not name.startswith("/") and ".." not in Path(name).parts for name in names))


if __name__ == "__main__":
    unittest.main()
