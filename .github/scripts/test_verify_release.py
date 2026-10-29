"""The release guard must reject missing, duplicate, broken and mixed-version JARs."""
from pathlib import Path
import tempfile
import unittest
import zipfile
from verify_release import expected_plugins, verify


class ReleaseValidationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        (self.directory / "ОБНОВЛЕНИЕ.txt").write_text("Порядок обновления", encoding="utf-8")

    def jar(self, filename, name="Core", version="1.4.189", descriptor=True):
        with zipfile.ZipFile(self.directory / filename, "w") as archive:
            if descriptor:
                archive.writestr("plugin.yml", f"name: '{name}'\nversion: {version}\n")
            archive.writestr("dummy.class", b"test fixture")

    def test_complete_release(self):
        self.jar("core.jar")
        self.jar("addon.jar", "Addon")
        self.assertEqual({"Core", "Addon"}, set(verify(self.directory, "1.4.189", {"Core", "Addon"})))

    def test_missing_or_unexpected_module(self):
        self.jar("core.jar")
        for expected in ({"Core", "Addon"}, {"Addon"}):
            with self.subTest(expected=expected), self.assertRaises(ValueError):
                verify(self.directory, "1.4.189", expected)

    def test_mixed_versions(self):
        self.jar("core.jar", version="1.4.188")
        with self.assertRaisesRegex(ValueError, "version"):
            verify(self.directory, "1.4.189", {"Core"})

    def test_duplicate_plugin(self):
        self.jar("core.jar")
        self.jar("copy.jar")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            verify(self.directory, "1.4.189", {"Core"})

    def test_descriptor_required(self):
        self.jar("core.jar", descriptor=False)
        with self.assertRaisesRegex(ValueError, "plugin.yml"):
            verify(self.directory, "1.4.189", {"Core"})

    def test_broken_jar(self):
        (self.directory / "core.jar").write_bytes(b"not a zip")
        with self.assertRaises(zipfile.BadZipFile):
            verify(self.directory, "1.4.189", {"Core"})

    def test_memo_required(self):
        self.jar("core.jar")
        (self.directory / "ОБНОВЛЕНИЕ.txt").unlink()
        with self.assertRaisesRegex(ValueError, "ОБНОВЛЕНИЕ"):
            verify(self.directory, "1.4.189", {"Core"})

    def test_snapshot_is_not_a_stable_release(self):
        self.jar("core.jar")
        with self.assertRaises(ValueError):
            verify(self.directory, "1.4.189-SNAPSHOT", {"Core"})

    def test_source_modules_have_unique_names(self):
        names = expected_plugins(Path(__file__).resolve().parents[2])
        self.assertIn("CHRDK_Core", names)
        self.assertIn("CHRDK_BattlePass", names)
        self.assertIn("CHRDK_Professions", names)
        self.assertEqual(28, len(names))
        self.assertNotIn("CHRDK_Bosses", names)
        self.assertNotIn("CHRDK_Treasures", names)


class ReleaseWorkflowContractTest(unittest.TestCase):
    def test_sixty_roadmap_criteria_are_separately_accounted_for(self):
        import re
        root = Path(__file__).resolve().parents[2]
        source = (root / "docs/roadmaps/mobs-enchants-60.md").read_text()
        for prefix, expected in (("I", 25), ("N", 35)):
            rows = re.findall(r"^\| (" + prefix + r"\d{2}) \|.*\| Реализовано \d+ \|$", source, re.M)
            self.assertEqual(len(set(rows)), expected)
            self.assertEqual(len(rows), expected)

    def test_memo_is_external_so_run_step_stays_below_github_limit(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / ".github/workflows/release.yml").read_text()
        self.assertIn("cp docs/releases/update-memo.txt", source)
        self.assertIn("1.4.{200,201,{220..240}}.md", source)
        self.assertIn('unzip -t "chrdk-plugins-${version}.zip"', source)
        self.assertNotIn("--zip", source)
        self.assertIn('"${{ env.zip }}" --clobber', source)
        # GitHub limits each run script to 21000 characters. Avoid parsing YAML/dependencies.
        for section in source.split("run: |\n")[1:]:
            script = section.split("\n      - name:", 1)[0]
            self.assertLess(len(script), 21000)
        self.assertTrue((root / "docs/releases/update-memo.txt").read_text().startswith("CHRDK"))


if __name__ == "__main__":
    unittest.main()
