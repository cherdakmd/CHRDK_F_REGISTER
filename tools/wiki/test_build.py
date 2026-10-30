"""Контракт справочника: данные, покрытие модулей, ссылки и смысловая актуальность."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from facts import FactBook
import freshness

SPEC = importlib.util.spec_from_file_location("wiki_builder", HERE / "build.py")
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class WikiBuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with contextlib.redirect_stdout(io.StringIO()):
            if builder.build(check=True):
                raise AssertionError("Сайт не прошёл контракт")
        cls.root = HERE.parents[1]
        cls.book = FactBook(cls.root)

    def test_economy_guides_keep_consent_and_carry_distinct(self):
        faq = (self.root / "wiki/faq.md").read_text()
        self.assertIn("точно сохраняет неоплаченную долю", faq)
        self.assertNotIn("если они не\nувеличивают выплату", faq)
        self.assertIn("без `confirm`", faq)
        self.assertIn("30 секунд", faq)
        self.assertIn("личного `/bal` арендатора", " ".join(faq.split()))
        start = (self.root / "wiki/start.md").read_text()
        self.assertIn("/sethome base confirm", start)
        self.assertIn("/homes prices", start)
        commands = (self.root / "wiki/commands.md").read_text()
        self.assertIn("/pay Alex 100 preview", commands)
        self.assertIn("/pay Alex 100 confirm", commands)
        self.assertIn("/shop quote buy iron_ingot 3", commands)
        readme = (self.root / "README.md").read_text()
        shop = readme.split("## Аддон CHRDK_DiamondShop —", 1)[1].split("\n---\n", 1)[0]
        self.assertIn("249 товаров", shop)
        self.assertIn("/shop quote", shop)
        self.assertIn("точная неоплаченная доля", shop)
        self.assertNotIn("/shop withdraw 32", shop)
        self.assertIn("/pay <ник> <сумма> confirm", readme)


    def test_all_pages_and_modules_are_covered(self):
        self.assertEqual([], freshness.check(self.root))
        info = json.loads((self.root / "site/build-info.json").read_text())
        self.assertEqual(len(list((self.root / "wiki").glob("*.md"))), info["pages"])
        self.assertEqual(len(self.book.modules), info["plugins"])
        self.assertRegex(info["commit"], r"^[0-9a-f]{40}$")

    def test_homepage_uses_source_counts_without_claiming_live_server_status(self):
        page = (self.root / "site/index.html").read_text()
        self.assertIn(
            f'<strong>{self.book.value("plugins")}</strong><span>плагинов в исходниках',
            page,
        )
        self.assertIn(
            f'<strong>{self.book.value("shop_count")}</strong><span>товаров в /shop',
            page,
        )
        self.assertIn("настройки сервера могут отличаться", page)
        self.assertIn('href="start.html"', page)

    def test_contact_links_are_exact_and_visible_across_the_site(self):
        contacts = (
            "https://t.me/cherdakmd",
            "https://vk.ru/chrdk_reborn",
            "https://www.twitch.tv/dedworkshop",
        )
        for page in (self.root / "site").glob("*.html"):
            content = page.read_text()
            footer = content.split('<footer class="footer">', 1)[1].split("</footer>", 1)[0]
            for url in contacts:
                self.assertIn(f'href="{url}" target="_blank" rel="noopener noreferrer"', footer, page.name)
        for name in ("README.md", "wiki/index.md", "wiki/about.md"):
            content = (self.root / name).read_text()
            self.assertIn("## Как связаться", content)
            for url in contacts:
                self.assertIn(f"]({url})", content, name)
        search = json.loads((self.root / "site/search-index.json").read_text())
        for slug in ("index.html", "about.html"):
            entry = next(p for p in search if p["url"] == slug)
            self.assertIn("Как связаться", entry["text"])

    def test_full_catalog_is_rendered_and_searchable_even_at_end_of_page(self):
        page = (self.root / "site/shop.html").read_text()
        index = json.loads((self.root / "site/search-index.json").read_text())
        search = next(p["text"] for p in index if p["url"] == "shop.html")
        for row in self.book.configs["diamond-shop"]["items"]:
            self.assertIn(row["material"].lower(), page)
            self.assertIn(row["material"].lower(), search)
        self.assertIn("только купить", page)
        self.assertIn("скупка ограничена стоимостью", page)

    def test_complete_enchant_registry_is_published(self):
        rows = self.book.enchant_catalog()
        self.assertEqual(len(self.book.configs["enchants"]["enchants"]), len(rows))
        page = (self.root / "site/enchants.html").read_text()
        for row in rows:
            self.assertIn(row[0].strip("`"), page)
        self.assertIn("Бережная пересадка", page)
        self.assertIn("PvE-якорь", page)

    def test_bundle_terms_match_public_configuration(self):
        page = (self.root / "site/ranks.html").read_text()
        for field in [
            "bundle_price",
            "bundle_days",
            "bundle_includes_days",
            "bp_product_days",
            "home_extra",
            "home_product_days",
        ]:
            self.assertIn(str(self.book.value(field)), page)
        self.assertEqual(10, self.book.value("boost_percent"))

    def test_dynamic_numbers_follow_changed_in_memory_config(self):
        before = self.book.value("shop_count")
        saved = self.book.configs["diamond-shop"]["items"]
        try:
            self.book.configs["diamond-shop"]["items"] = [
                *saved,
                {"material": "TEST", "category": "blocks", "base-price": 1},
            ]
            self.assertEqual(before + 1, self.book.value("shop_count"))
            self.assertEqual(str(before + 1), self.book.expand("{{fact:shop_count}}"))
        finally:
            self.book.configs["diamond-shop"]["items"] = saved

    def test_unknown_or_secret_facts_are_not_exportable(self):
        for token in [
            "{{fact:api-token}}",
            "{{fact:core.donatepay.api-token}}",
            "{{table:no_such_table}}",
        ]:
            with self.assertRaises(ValueError):
                self.book.expand(token)
        with self.assertRaises(ValueError):
            self.book.expand("{{ typo:unknown }}")

    def test_admin_commands_are_not_presented_as_player_commands(self):
        rows = self.book.commands()
        self.assertNotIn("pass", {r[1] for r in rows})
        self.assertNotIn("guard", {r[1] for r in rows})
        self.assertNotIn("socialspy", {r[1] for r in rows})
        self.assertTrue(all("admin" not in r[1] for r in rows))
        self.assertIn("shop", {r[1] for r in rows})
        self.assertIn("prof", {r[1] for r in rows})

    def test_tokens_and_local_assets_are_valid_on_every_page(self):
        for path in (self.root / "site").glob("*.html"):
            text = path.read_text()
            self.assertNotIn("{{", text, path.name)
            self.assertIn("assets/style.css?v=", text)
            self.assertIn("assets/app.js?v=", text)
            self.assertIn('aria-current="page"', text)
            self.assertIn("<dialog", text)
            self.assertIn('href="#main"', text)
            self.assertIn("Текст страницы", text)

    def test_inline_links_are_safe_and_not_double_linked(self):
        r = builder.Renderer("index", {"index"})
        text = r.inline("[Источник](https://github.com/example/repo)")
        self.assertEqual(1, text.count("<a "))
        self.assertIn('rel="noopener"', text)
        with self.assertRaises(ValueError):
            r.inline("[Вредная ссылка](javascript:alert)")
        with self.assertRaises(ValueError):
            r.inline("[Скрытый переход](//example.com)")
        self.assertNotIn("<img", r.inline("`<img src=x onerror=x>`"))

    def test_escaped_pipe_does_not_split_a_command_into_extra_cells(self):
        r = builder.Renderer("index", {"index"})
        text = r.render(
            "| Команда | Назначение |\n|---|---|\n| `/test a\\|b` | выбор |"
        )
        self.assertEqual(2, text.count("<td"))
        self.assertIn("a&#124;b", text)


class FreshnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for directory in ["wiki", "src/main", "demo/src/main", "tools/wiki"]:
            (self.root / directory).mkdir(parents=True)
        (self.root / "settings.gradle").write_text("include 'demo'\n")
        (self.root / "src/main/Core.java").write_text("class Core {}")
        (self.root / "demo/src/main/Demo.java").write_text("class Demo {}")
        (self.root / "tools/wiki/facts.py").write_text("# generator")
        (self.root / "wiki/demo.md").write_text(
            "---\ntitle: Demo\nreview: [core, demo]\n---\nОписание правил.\n"
        )
        freshness.accept(["demo"], "Сверена реализация и пояснения страницы", self.root)

    def test_code_change_requires_review(self):
        self.assertEqual([], freshness.check(self.root))
        (self.root / "demo/src/main/Demo.java").write_text(
            "class Demo { int price=2; }"
        )
        self.assertTrue(freshness.check(self.root))
        freshness.accept(["demo"], "Проверена новая цена и её описание", self.root)
        self.assertEqual([], freshness.check(self.root))

    def test_new_source_file_and_generator_change_are_detected(self):
        for relative in ["demo/src/main/New.java", "tools/wiki/facts.py"]:
            (self.root / relative).write_text("changed")
            self.assertTrue(freshness.check(self.root))
            freshness.accept(
                ["demo"], "Сверена новая реализация и публичные факты", self.root
            )

    def test_text_change_requires_review_too(self):
        p = self.root / "wiki/demo.md"
        p.write_text(p.read_text() + "Новый текст.\n")
        self.assertTrue(freshness.check(self.root))

    def test_new_module_requires_documentation_coverage(self):
        (self.root / "settings.gradle").write_text(
            "include 'demo'\ninclude 'newmodule'\n"
        )
        self.assertTrue(any("newmodule" in e for e in freshness.check(self.root)))

    def test_short_reason_and_unknown_page_are_rejected(self):
        with self.assertRaises(ValueError):
            freshness.accept(["demo"], "OK", self.root)
        with self.assertRaises(ValueError):
            freshness.accept(["unknown"], "Проверены все связанные правила", self.root)

    def test_check_does_not_rewrite_review_baseline(self):
        before = (self.root / freshness.LOCK).read_bytes()
        freshness.check(self.root)
        self.assertEqual(before, (self.root / freshness.LOCK).read_bytes())


class PublicationTest(unittest.TestCase):
    def test_only_unified_pipeline_can_deploy_after_both_checks(self):
        import yaml

        root = HERE.parents[1]
        config = yaml.load(
            (root / ".github/workflows/build.yml").read_text(), Loader=yaml.BaseLoader
        )
        triggers = config["on"]
        self.assertNotIn("paths", triggers["push"])
        self.assertNotIn("paths-ignore", triggers["push"])
        self.assertIn("main", triggers["push"]["branches"])
        self.assertIn("arena/**", triggers["push"]["branches"])
        self.assertIn("pull_request", triggers)
        deploy = config["jobs"]["deploy"]
        self.assertEqual({"build", "wiki"}, set(deploy["needs"]))
        self.assertIn("github.ref == 'refs/heads/main'", deploy["if"])
        self.assertIn("github.event_name != 'pull_request'", deploy["if"])
        self.assertFalse((root / ".github/workflows/pages.yml").exists())
        steps = config["jobs"]["wiki"]["steps"]
        commands = "\n".join(step.get("run", "") for step in steps)
        self.assertIn("build.py --check", commands)
        self.assertIn("unittest discover", commands)
        self.assertIn("node --test", commands)
        self.assertIn("npm test", commands)
        self.assertNotIn("--accept", commands)


if __name__ == "__main__":
    unittest.main()
