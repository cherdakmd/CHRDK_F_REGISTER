"""Тесты упаковщика эталона Grim-конфигов для ассета релиза."""
from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

import pack_grim_configs


class PackGrimConfigsTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / "grim"
        self.source.mkdir()
        (self.source / "config.yml").write_text(
            "alerts:\n    print-to-console: true\n", encoding="utf-8")
        (self.source / "punishments.yml").write_text(
            'Punishments:\n  Simulation:\n    commands:\n'
            '      - "1:1 chrdkac flag %player% %check_name% %vl% %verbose%"\n',
            encoding="utf-8")
        (self.source / "README.md").write_text("# Grim\n", encoding="utf-8")
        self.destination = self.root / "chrdk-grim-configs-1.0.0.zip"

    def test_packs_three_files_under_grim_prefix(self) -> None:
        pack_grim_configs.pack(self.source, self.destination)
        with zipfile.ZipFile(self.destination) as archive:
            self.assertEqual(
                {"grim/config.yml", "grim/punishments.yml", "grim/README.md"},
                set(archive.namelist()))
            self.assertIn("chrdkac flag", archive.read("grim/punishments.yml").decode("utf-8"))

    def test_rejects_incomplete_source(self) -> None:
        (self.source / "README.md").unlink()
        with self.assertRaisesRegex(ValueError, "неполный"):
            pack_grim_configs.pack(self.source, self.destination)

    def test_rejects_punishments_without_bridge(self) -> None:
        (self.source / "punishments.yml").write_text("Punishments:\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "мост"):
            pack_grim_configs.pack(self.source, self.destination)

    def test_rejects_punishments_with_other_commands(self) -> None:
        (self.source / "punishments.yml").write_text(
            'commands:\n  - "1:1 chrdkac flag %player% %check_name% %vl% %verbose%"\n'
            '  - "5:5 [alert]"\n',
            encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "\\[alert\\]"):
            pack_grim_configs.pack(self.source, self.destination)

    def test_repository_ethalon_passes_the_contract(self) -> None:
        repository = Path(__file__).resolve().parents[2] / "anticheat" / "grim"
        pack_grim_configs.pack(repository, self.destination)
        with zipfile.ZipFile(self.destination) as archive:
            self.assertEqual(
                {"grim/config.yml", "grim/punishments.yml", "grim/README.md"},
                set(archive.namelist()))


if __name__ == "__main__":
    unittest.main()
