"""Регрессии сайта: реальные числа из каталога/модулей и согласованная витрина."""
import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
import yaml

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("wiki_builder", HERE / "build.py")
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class WikiCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with contextlib.redirect_stdout(io.StringIO()):
            if builder.build(check=True) != 0:
                raise AssertionError("Сайт не прошёл проверку")
        cls.root = HERE.parents[1]
        cls.catalog = yaml.safe_load((cls.root / "diamond-shop/src/main/resources/config.yml").read_text())

    def test_homepage_numbers_are_derived_from_shipped_sources(self):
        plugins, items = builder.catalog_counts()
        self.assertEqual(26, plugins)
        self.assertEqual(178, items)
        page = (self.root / "site/index.html").read_text()
        self.assertIn(f"<strong>{plugins}</strong><span>плагинов в комплекте", page)
        self.assertIn(f"<strong>{items}</strong><span>товаров в /shop", page)
        self.assertIn('class="shop-spotlight"', page)
        self.assertIn('href="shop.html"', page)
        self.assertIn("без обратной скупки", page)

    def test_shop_counts_and_buy_only_policy_match_actual_catalog(self):
        rows = self.catalog["items"]
        self.assertEqual(36, sum(r.get("sell-enabled", True) is False for r in rows))
        self.assertEqual(71, sum(r["category"] == "blocks" for r in rows))
        page = (self.root / "site/shop.html").read_text()
        self.assertIn("178", page)
        self.assertIn("71", page)
        self.assertIn("заметно ниже прежнего", page)
        self.assertIn("только купить", page)

    def test_reference_catalog_contains_every_material_exactly_once(self):
        doc = (self.root / "docs/SHOP_CATALOG.md").read_text()
        for row in self.catalog["items"]:
            self.assertEqual(1, doc.count(f"| `{row['material']}` |"), row["material"])


if __name__ == "__main__":
    unittest.main()
