#!/usr/bin/env python3
"""Тесты локального установщика Mine Treasure RU (без самого ZIP в репозитории)."""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("install_mine_treasure.py")
spec = importlib.util.spec_from_file_location("install_mine_treasure", MODULE_PATH)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


def pack_bytes(marker: str = "one", *, nested: bool = False) -> bytes:
    payload = io.BytesIO()
    prefix = "wrapper/" if nested else ""
    meta = {
        "pack": {
            "description": "Mine Treasure v1.6.5\nот Frozytime " + marker,
            "min_format": [121, 1],
            "max_format": [121, 1],
        }
    }
    with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(prefix + "pack.mcmeta", json.dumps(meta, ensure_ascii=False))
        archive.writestr("data/mt/function/init.mcfunction", "scoreboard objectives add mt.main dummy\n")
        archive.writestr("data/minecraft/tags/function/load.json", '{"values":["mt:init"]}')
        archive.writestr("data/minecraft/tags/function/tick.json", '{"values":["mt:tick"]}')
        archive.writestr("LICENSE.md", "© 2026 Frozytime\n")
    return payload.getvalue()


def source_file(directory: Path, name: str = "mine-treasure-1.6.5-ru.txt",
                marker: str = "one") -> Path:
    data = pack_bytes(marker)
    path = directory / name
    path.write_bytes(data)
    return path


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(argv: list[str]) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        status = module.main(argv)
    return status, out.getvalue(), err.getvalue()


class InstallMineTreasureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def test_validate_archive_accepts_russian_txt_zip(self) -> None:
        source = source_file(self.root, marker="RU")
        report = module.validate_archive(source)
        self.assertIn("Mine Treasure", report.description)
        self.assertIn("от Frozytime", report.description)
        self.assertEqual((121, 1), report.min_format)
        self.assertEqual((121, 1), report.max_format)
        self.assertEqual(2, report.json_files)
        self.assertTrue(report.license_present)
        self.assertEqual(64, len(report.sha256))

    def test_check_mode_reports_and_rejects_hash_mismatch(self) -> None:
        data = pack_bytes("RU")
        source = self.root / "upload.txt"
        source.write_bytes(data)
        status, out, _ = run(["--source", str(source), "--sha256", digest(data), "--check"])
        self.assertEqual(0, status)
        self.assertIn("Формат пака", out)
        self.assertNotIn("Pack id", out)  # --check только проверяет архив
        self.assertEqual(1, run(["--source", str(source), "--sha256", "0" * 64, "--check"])[0])

    def test_install_renames_txt_and_is_idempotent(self) -> None:
        data = pack_bytes("RU")
        source = self.root / "upload.txt"
        source.write_bytes(data)
        world = self.root / "world"
        world.mkdir()
        (world / "level.dat").write_bytes(b"level")
        common = ["--source", str(source), "--world", str(world), "--sha256", digest(data)]
        self.assertEqual(0, run(common)[0])
        destination = world / "datapacks" / module.DEFAULT_NAME
        self.assertTrue(destination.is_file())
        self.assertEqual(digest(data), module.sha256_file(destination))
        status, out, _ = run(common)
        self.assertEqual(0, status)
        self.assertIn("(unchanged)", out)
        self.assertFalse((world / "datapacks" / (module.DEFAULT_NAME + ".tmp")).exists())

    def test_existing_zip_requires_force_and_force_replaces(self) -> None:
        first = pack_bytes("first")
        second = pack_bytes("second")
        source = self.root / "upload.txt"
        source.write_bytes(first)
        world = self.root / "world"
        world.mkdir()
        (world / "level.dat").write_bytes(b"level")
        base = ["--world", str(world)]
        self.assertEqual(0, run(["--source", str(source), *base, "--sha256", digest(first)])[0])
        destination = world / "datapacks" / module.DEFAULT_NAME

        source.write_bytes(second)
        status, _, err = run(["--source", str(source), *base, "--sha256", digest(second)])
        self.assertEqual(1, status)
        self.assertIn("--force", err)
        self.assertEqual(digest(first), module.sha256_file(destination))

        status, out, _ = run(["--source", str(source), *base,
                             "--sha256", digest(second), "--force"])
        self.assertEqual(0, status)
        self.assertIn("(replaced)", out)
        self.assertEqual(digest(second), module.sha256_file(destination))

    def test_custom_level_name_and_missing_world_are_handled(self) -> None:
        data = pack_bytes("RU")
        source = self.root / "upload.txt"
        source.write_bytes(data)
        server = self.root / "server"
        server.mkdir()
        (server / "server.properties").write_text("level-name=custom\n", encoding="utf-8")
        custom = server / "custom"
        custom.mkdir()
        (custom / "level.dat").write_bytes(b"level")
        self.assertEqual(0, run(["--source", str(source), "--world", str(server),
                                 "--sha256", digest(data)])[0])
        self.assertTrue((custom / "datapacks" / module.DEFAULT_NAME).is_file())

        empty = self.root / "empty"
        empty.mkdir()
        self.assertEqual(1, run(["--source", str(source), "--world", str(empty),
                                 "--sha256", digest(data)])[0])

    def test_nested_pack_and_unsafe_name_are_rejected(self) -> None:
        nested = self.root / "nested.zip"
        nested.write_bytes(pack_bytes("nested", nested=True))
        with self.assertRaises(module.InstallError):
            module.validate_archive(nested)

        source = source_file(self.root, name="valid.txt")
        world = self.root / "world"
        world.mkdir()
        (world / "level.dat").write_bytes(b"level")
        status, _, err = run(["--source", str(source), "--world", str(world),
                              "--name", "../evil.zip", "--sha256", ""])
        self.assertEqual(1, status)
        self.assertIn("имя датапака", err)

    def test_normalize_format_accepts_scalar_and_pair(self) -> None:
        self.assertEqual((121, 0), module.normalize_format(121, "min_format"))
        self.assertEqual((121, 1), module.normalize_format([121, 1], "max_format"))
        with self.assertRaises(module.InstallError):
            module.normalize_format({"min_inclusive": 121}, "min_format")


if __name__ == "__main__":
    unittest.main()
