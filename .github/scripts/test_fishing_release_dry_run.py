import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from fishing_release_dry_run import pack_dry_run, verify_dry_run
from verify_fishing_bundle import JARS


class FishingDryRunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.plugins = self.root / "plugins"
        self.plugins.mkdir()
        self.version = "2.0.26-rc.18"
        self.commit = "ab" * 20
        for archive, name, required in JARS.values():
            with ZipFile(self.plugins / f"{archive}-{self.version}.jar", "w") as jar:
                jar.writestr("plugin.yml", f"name: {name}\nversion: {self.version}\napi-version: '26.3'\ndepend: [CHRDK_Core, CHRDK_Skills]\n")
                for entry in required:
                    jar.writestr(entry, b"fixture")
        self.pack = self.root / "pack.zip"
        with ZipFile(self.pack, "w") as pack:
            pack.writestr("pack.mcmeta", "{}")
            pack.writestr("assets/chrdk_fishing/item", "fish")
            pack.writestr("assets/chrdk_items/item", "custom")
        self.output = self.root / "dry-run.zip"

    def create(self, destination=None, version=None, commit=None):
        pack_dry_run(self.plugins, self.pack, version or self.version, commit or self.commit, destination or self.output)

    def rewrite(self, change):
        with ZipFile(self.output) as archive:
            contents = {name: archive.read(name) for name in archive.namelist()}
        change(contents)
        with ZipFile(self.output, "w") as archive:
            for name, data in contents.items():
                archive.writestr(name, data)

    def test_verified_reproducible_archive_contains_only_selected_files(self):
        self.create()
        verify_dry_run(self.output, self.version, self.commit)
        copy = self.root / "second.zip"
        self.create(destination=copy)
        self.assertEqual(self.output.read_bytes(), copy.read_bytes())
        with ZipFile(self.output) as archive:
            manifest = json.loads(archive.read("MANIFEST.json"))
            self.assertEqual(manifest["kind"], "experimental-dry-run")
            self.assertEqual(manifest["commit"], self.commit)
            self.assertEqual(len(manifest["sha256"]), 5)
            self.assertIn("НЕ РЕЛИЗ", archive.read("NOT-A-RELEASE.txt").decode())

    def test_changed_payload_extra_files_and_wrong_commit_are_rejected(self):
        self.create()
        with self.assertRaisesRegex(ValueError, "provenance"):
            verify_dry_run(self.output, self.version, "cd" * 20)
        self.rewrite(lambda files: files.__setitem__("resources/chrdk-custom-items-fishing.zip", b"tampered"))
        with self.assertRaisesRegex(ValueError, "checksum"):
            verify_dry_run(self.output, self.version, self.commit)
        self.output.unlink()
        self.create()
        self.rewrite(lambda files: files.__setitem__("unexpected.txt", b"unexpected"))
        with self.assertRaisesRegex(ValueError, "unexpected"):
            verify_dry_run(self.output, self.version, self.commit)

    def test_existing_output_is_not_overwritten_and_stable_versions_are_rejected(self):
        self.output.write_bytes(b"previous")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.create()
        self.assertEqual(self.output.read_bytes(), b"previous")
        self.output.unlink()
        with self.assertRaisesRegex(ValueError, "numbered"):
            self.create(version="2.0.26")
        self.assertFalse(self.output.exists())
        with self.assertRaisesRegex(ValueError, "commit"):
            self.create(commit="abc")
        self.assertFalse(self.output.exists())

    def test_wrong_jar_or_incomplete_pack_does_not_create_artifact(self):
        (self.plugins / f"chrdk-skills-{self.version}.jar").unlink()
        with self.assertRaises(ValueError):
            self.create()
        self.assertFalse(self.output.exists())
        # Restore a valid Skills jar, then check that a Fishing-only pack is refused.
        archive, name, required = JARS["skills"]
        with ZipFile(self.plugins / f"{archive}-{self.version}.jar", "w") as jar:
            jar.writestr("plugin.yml", f"name: {name}\nversion: {self.version}\n")
            for entry in required:
                jar.writestr(entry, b"fixture")
        with ZipFile(self.pack, "w") as pack:
            pack.writestr("pack.mcmeta", "{}")
            pack.writestr("assets/chrdk_fishing/item", "fish")
        with self.assertRaisesRegex(ValueError, "both Fishing and CustomItems"):
            self.create()
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
