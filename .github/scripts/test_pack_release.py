"""Контракт упаковщика, используемого workflow и Java integration-тестом."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
import zipfile

import pack_release


class PackReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "input"
        self.source.mkdir()
        self.destination = self.root / "bundle.zip"
        self.jar = self.source / "core-1.0.0.jar"
        with zipfile.ZipFile(self.jar, "w") as z:
            z.writestr("plugin.yml", "name: Core\nversion: 1.0.0\n")
        self.memo = self.source / pack_release.MEMO_NAME
        self.memo.write_text("Штатная остановка и backup\n", encoding="utf-8")

    def pack(self):
        pack_release.pack(self.source, "1.0.0", self.destination, {"Core"})

    def test_flat_zip_contains_jars_and_utf8_memo_only(self):
        self.pack()
        with zipfile.ZipFile(self.destination) as z:
            self.assertEqual({self.jar.name, self.memo.name}, set(z.namelist()))
            self.assertIsNone(z.testzip())
            self.assertEqual(self.memo.read_bytes(), z.read(self.memo.name))
            self.assertEqual(self.jar.read_bytes(), z.read(self.jar.name))
        self.assertEqual([], list(self.root.glob(".chrdk-release-*.tmp")))

    def test_existing_archive_is_not_updated_or_overwritten(self):
        self.destination.write_bytes(b"keep")
        with self.assertRaises(ValueError):
            self.pack()
        self.assertEqual(b"keep", self.destination.read_bytes())

    def test_incomplete_bundle_does_not_create_output(self):
        with self.assertRaises(ValueError):
            pack_release.pack(self.source, "1.0.0", self.destination, {"Core", "Missing"})
        self.assertFalse(self.destination.exists())

    def test_missing_empty_binary_or_bom_only_memo_is_rejected(self):
        for contents in (b"", b"\xc3(", b"Text\0", b"\xef\xbb\xbf"):
            with self.subTest(contents=contents):
                self.memo.write_bytes(contents)
                with self.assertRaises((ValueError, UnicodeError)):
                    self.pack()
                self.assertFalse(self.destination.exists())
        self.memo.unlink()
        with self.assertRaises(ValueError):
            self.pack()

    def test_symbolic_memo_is_not_packaged(self):
        target = self.root / "target.txt"
        target.write_text("keep")
        self.memo.unlink()
        self.memo.symlink_to(target)
        with self.assertRaises(ValueError):
            self.pack()
        self.assertFalse(self.destination.exists())

    def test_individual_and_total_sizes_are_bounded(self):
        for name in ("MEMO_LIMIT", "JAR_LIMIT", "ZIP_LIMIT"):
            with self.subTest(limit=name), patch.object(pack_release, name, 1):
                with self.assertRaises(ValueError):
                    self.pack()
                self.assertFalse(self.destination.exists())

    def test_updater_and_packager_share_limits_and_memo_name(self):
        import math
        import re
        root = Path(__file__).resolve().parents[2]
        java = (root / "src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java").read_text()
        for name in ("JAR_LIMIT", "MEMO_LIMIT", "ZIP_LIMIT"):
            expression = re.search(r"static final int " + name + r" = ([0-9 *]+);", java).group(1)
            self.assertEqual(getattr(pack_release, name), math.prod(int(x.strip()) for x in expression.split("*")))
        self.assertIn('static final String MEMO_NAME = "' + pack_release.MEMO_NAME + '";', java)

    def test_failed_publication_removes_temporary_file(self):
        with patch.object(pack_release.os, "replace", side_effect=OSError("write failed")):
            with self.assertRaises(OSError):
                self.pack()
        self.assertFalse(self.destination.exists())
        self.assertEqual([], list(self.root.glob(".chrdk-release-*.tmp")))
