import json
from pathlib import Path
import tempfile
import unittest

from release_spec import load_spec


ROOT = Path(__file__).resolve().parents[2]


class ReleaseSpecTest(unittest.TestCase):
    def test_203_is_an_explicit_full_release(self):
        self.assertEqual(
            {"version": "2.0.3", "kind": "full", "prerelease": False},
            load_spec(ROOT, "2.0.3"),
        )

    def test_rc7_manifest_selects_core_not_diamondshop(self):
        spec = load_spec(ROOT, "2.0.2-rc.7")
        self.assertEqual("standalone", spec["kind"])
        self.assertEqual("core", spec["module"])
        self.assertEqual("CHRDK_Core", spec["plugin"])
        self.assertEqual("build/libs/chrdk-core-2.0.2-rc.7.jar", spec["source"])
        self.assertEqual("chrdk-core-2.0.2-rc.7.jar", spec["jar"])

    def fixture(self, manifest):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "docs/releases").mkdir(parents=True)
        (root / "claims/src/main/resources").mkdir(parents=True)
        (root / "gradle.properties").write_text("archives_base_name=chrdk-core\n", encoding="utf-8")
        (root / "settings.gradle").write_text("include 'claims'\n", encoding="utf-8")
        (root / "src/main/resources").mkdir(parents=True)
        (root / "src/main/resources/plugin.yml").write_text("name: CHRDK_Core\n", encoding="utf-8")
        (root / "claims/src/main/resources/plugin.yml").write_text("name: CHRDK_Claims\n", encoding="utf-8")
        (root / "claims/build.gradle").write_text("base { archivesName = 'chrdk-claims' }\n", encoding="utf-8")
        (root / "docs/releases/1.2.3.json").write_text(json.dumps(manifest), encoding="utf-8")
        return root

    def manifest(self, **changes):
        value = {
            "version": "1.2.3",
            "kind": "standalone",
            "prerelease": False,
            "module": "claims",
            "plugin": "CHRDK_Claims",
            "archive": "chrdk-claims",
        }
        value.update(changes)
        return value

    def test_standalone_manifest_must_match_gradle_archive_and_plugin_descriptor(self):
        for field, value, message in [
            ("plugin", "CHRDK_Core", "does not match"),
            ("archive", "chrdk-core", "does not match Gradle archive"),
            ("module", "missing-module", "unknown Gradle module"),
        ]:
            with self.subTest(field=field):
                root = self.fixture(self.manifest(**{field: value}))
                with self.assertRaisesRegex(ValueError, message):
                    load_spec(root, "1.2.3")

    def test_manifest_rejects_rc_flag_mismatch_and_unlisted_versions(self):
        root = self.fixture(self.manifest(prerelease=True))
        with self.assertRaisesRegex(ValueError, "prerelease flag"):
            load_spec(root, "1.2.3")
        with self.assertRaisesRegex(ValueError, "missing release manifest"):
            load_spec(root, "1.2.4")

    def test_release_workflow_uses_manifest_kind_not_version_wildcards(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn(".github/scripts/release_spec.py", workflow)
        self.assertIn("steps.release-spec.outputs.kind == 'standalone'", workflow)
        self.assertIn("steps.release-spec.outputs.kind == 'full'", workflow)
        self.assertIn("MANIFEST_PRERELEASE: ${{ steps.release-spec.outputs.prerelease }}", workflow)
        self.assertIn("Verify published release kind matches manifest", workflow)
        for path in (".github/workflows/build.yml", ".github/workflows/release.yml"):
            ci = (ROOT / path).read_text(encoding="utf-8")
            self.assertIn("-p 'test_release_spec.py'", ci)
        self.assertNotIn("startsWith(steps.verify-version.outputs.version, '2.0.2-rc.')", workflow)
        self.assertNotIn("Pack DiamondShop-only prerelease", workflow)


if __name__ == "__main__":
    unittest.main()
