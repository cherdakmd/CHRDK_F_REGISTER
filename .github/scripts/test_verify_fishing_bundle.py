import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from verify_fishing_bundle import JARS, verify_bundle


class FishingBundleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.version = "2.0.26-rc.16"
        for kind in JARS:
            self.write_jar(kind)

    def write_jar(self, kind, *, missing=None, extra=None, depends="[CHRDK_Core, CHRDK_Skills]", version=None):
        archive, plugin, required = JARS[kind]
        with ZipFile(self.directory / f"{archive}-{self.version}.jar", "w") as jar:
            descriptor = f"name: {plugin}\nversion: {version or self.version}\napi-version: '26.3'\ndepend: {depends}\n"
            jar.writestr("plugin.yml", descriptor)
            for entry in required - set(missing or []):
                jar.writestr(entry, b"fixture")
            for entry in extra or []:
                jar.writestr(entry, b"fixture")

    def test_complete_rc_bundle_passes(self):
        verify_bundle(self.directory, self.version)

    def test_absent_wrong_version_or_stable_bundle_is_rejected(self):
        self.write_jar("skills", version="2.0.26-rc.15")
        with self.assertRaisesRegex(ValueError, "version"):
            verify_bundle(self.directory, self.version)
        with self.assertRaisesRegex(ValueError, "numbered"):
            verify_bundle(self.directory, "2.0.26")
        (self.directory / f"chrdk-skills-{self.version}.jar").unlink()
        with self.assertRaisesRegex(ValueError, "missing"):
            verify_bundle(self.directory, self.version)

    def test_old_core_or_skills_api_is_rejected(self):
        for kind, missing in (("core", "ru/cherdakmd/chrdkregister/api/FishingSnapshotChargeProvider.class"),
                              ("skills", "ru/cherdakmd/chrdkskills/FishingCatchBonus.class")):
            self.write_jar(kind, missing=[missing])
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, "missing required"):
                verify_bundle(self.directory, self.version)
            self.write_jar(kind)

    def test_wrong_dependencies_and_shaded_api_are_rejected(self):
        self.write_jar("fishing", depends="[CHRDK_Core]")
        with self.assertRaisesRegex(ValueError, "dependencies"):
            verify_bundle(self.directory, self.version)
        self.write_jar("fishing", extra=["ru/cherdakmd/chrdkskills/FishingCatchBonus.class"])
        with self.assertRaisesRegex(ValueError, "shaded"):
            verify_bundle(self.directory, self.version)

    def test_recovery_harness_classes_never_ship_in_core_or_skills(self):
        for kind in ("core", "skills"):
            self.write_jar(kind, extra=["ru/cherdakmd/chrdkfishing/FishSaleJournal.class"])
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, "unexpected Fishing"):
                verify_bundle(self.directory, self.version)
            self.write_jar(kind)

    def test_missing_sqlite_is_rejected(self):
        self.write_jar("fishing", missing=["org/sqlite/JDBC.class"])
        with self.assertRaisesRegex(ValueError, "missing required"):
            verify_bundle(self.directory, self.version)


if __name__ == "__main__":
    unittest.main()
