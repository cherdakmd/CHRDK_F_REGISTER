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
        self.assertEqual(30, len(names))


if __name__ == "__main__":
    unittest.main()
