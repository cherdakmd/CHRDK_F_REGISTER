"""Регрессии проверки остатков; намеренно не запрещают ванильную игру и историю."""
from pathlib import Path
import tempfile
import unittest
from check_retired_addons import check, ROOT, ALIASES


class RetiredAddonsGuardTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_current_repository_is_clean(self):
        self.assertEqual([], check(ROOT))

    def test_every_retired_command_alias_and_namespace_is_rejected(self):
        for alias in ALIASES.split("|"):
            for prefix in ("", "custom_namespace:"):
                with self.subTest(alias=alias, prefix=prefix):
                    self.write("wiki/commands.md", f"| `/{prefix}{alias} menu` | old |")
                    self.assertTrue(check(self.root))

    def test_plugin_namespace_artifact_and_deleted_api_are_rejected(self):
        for symbol in ("CHRDK_Bosses", "CHRDK_Treasures", "ru.cherdakmd.chrdktreasures",
                       "chrdk-bosses-1.4.238.jar", "ChrdkTreasureBookInfo", "CHRDK_Streams",
                       "chrdk-streams-1.4.240.jar", "ru.cherdakmd.chrdkstreams", "CHRDK_Events", "chrdk-events-1.4.241.jar",
                       "ru.cherdakmd.chrdkevents", "CHRDK_Queue", "chrdk-queue-1.4.247.jar", "ru.cherdakmd.chrdkqueue"):
            with self.subTest(symbol=symbol):
                self.write("enchants/src/main/java/Source.java", f"import {symbol};")
                self.assertTrue(check(self.root))

    def test_retired_pages_are_rejected_even_without_commands(self):
        for page in ("bosses", "treasures", "streams", "events", "queue"):
            with self.subTest(page=page):
                self.write("tools/wiki/assets/app.js", f'const href = "{page}.html";')
                self.assertTrue(check(self.root))

    def test_orphan_source_and_both_yaml_command_styles_are_checked(self):
        for text in ('command: boss menu', '{"command": "treasures"}',
                     'command: "/bossesadmin stop"', 'softdepend: [CHRDK_Treasures]'):
            with self.subTest(text=text):
                self.write("orphan/src/main/resources/config.yml", text)
                self.assertTrue(check(self.root))

    def test_settings_and_retired_files_cannot_return(self):
        self.write("settings.gradle", "include 'treasures'\n")
        self.assertTrue(check(self.root))
        (self.root / "settings.gradle").unlink()
        for name in ("bosses/build.gradle", "treasures/build/libs/old.jar", "wiki/bosses.md",
                     "wiki/treasures.md", "docs/TREASURES_ARSENAL.md", "streams/build.gradle", "wiki/streams.md", "events/build.gradle", "wiki/events.md", "queue/build.gradle", "wiki/queue.md"):
            with self.subTest(name=name):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    target = root / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text("", encoding="utf-8")
                    self.assertTrue(check(root))

    def test_legacy_snapshots_archives_and_test_fixtures_remain_allowed(self):
        for name in ("src/main/resources/presentation-legacy-1.4.238.yml",
                     "docs/releases/1.4.238.md", "docs/audits/old.md",
                     "src/test/java/LegacyTest.java"):
            self.write(name, "CHRDK_Treasures /boss")
        self.assertEqual([], check(self.root))

    def test_generated_browser_dependencies_are_not_server_addons(self):
        for name in ("tools/wiki/node_modules/vendor/events.js",
                     "tools/wiki/test-results/old/error-context.md",
                     "tools/wiki/playwright-report/index.html"):
            self.write(name, 'require("./events"); /stream /boss')
        self.assertEqual([], check(self.root))
        # Собственный код сайта по-прежнему проверяется.
        self.write("tools/wiki/assets/app.js", 'const removed = "events.html";')
        self.assertTrue(check(self.root))

    def test_vanilla_bosses_fishing_and_read_only_provenance_are_not_addons(self):
        self.write("wiki/hunt.md", "Ванильные боссы, сокровища рыбалки и /minecraft:bossbar list")
        self.write("hunt/src/main/resources/config.yml", 'command: "bossbar list"')
        self.write("src/main/java/Legacy.java", 'Set.of("bosses", "treasures"); TREASURE; SHARDS;')
        self.write("artifacts/src/main/java/Loot.java", 'BOSSES_KILLED; fish_treasure; treasure_hunter;')
        self.assertEqual([], check(self.root))

    def test_internal_and_payment_queues_remain_allowed(self):
        self.write("src/main/java/Worker.java", "ConcurrentLinkedQueue<Runnable> queue; getQueue(); queueSize();")
        self.write("wiki/payments.md", "Очередь решений /pass pending; асинхронная очередь записи")
        self.assertEqual([], check(self.root))

    def test_removed_login_priority_promise_is_rejected(self):
        self.write("src/main/resources/messages.yml", "ability: Вход без очереди")
        self.assertTrue(check(self.root))

    def test_shared_bukkit_and_core_event_paths_are_not_removed_modules(self):
        self.write("src/main/java/Listener.java",
                   "import org.bukkit.event.Event; import ru.cherdakmd.chrdkregister.api.events.PassGrantedEvent;")
        self.write("wiki/commands.md", "https://jd.papermc.io/paper/org/bukkit/event/player/PlayerJoinEvent.html")
        self.write("wiki/profile.md", "src/main/java/ru/cherdakmd/chrdkregister/api/events/PassGrantedEvent.java")
        self.assertEqual([], check(self.root))


if __name__ == "__main__":
    unittest.main()
