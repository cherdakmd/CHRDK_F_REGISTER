"""Storage contracts: inventory/schema must match Java constants."""
from __future__ import annotations

import hashlib
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / "src/main/resources/storage-inventory.json"
SCHEMA_SQL = ROOT / "src/main/resources/database-schema.sql"
STORAGE_API = ROOT / "src/main/java/ru/cherdakmd/chrdkregister/api/storage/ChrdkStorage.java"
STORAGE_SCHEMA = ROOT / "src/main/java/ru/cherdakmd/chrdkregister/storage/db/StorageSchema.java"


class StorageContractsTest(unittest.TestCase):
    def test_storage_api_version_matches_inventory(self) -> None:
        inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
        java = STORAGE_API.read_text(encoding="utf-8")
        match = re.search(r"STORAGE_API_VERSION\s*=\s*(\d+)", java)
        self.assertIsNotNone(match, "STORAGE_API_VERSION not found in ChrdkStorage.java")
        java_version = int(match.group(1))
        self.assertEqual(inventory["storage_api_version"], java_version,
                         "storage-inventory.json.storage_api_version != ChrdkStorage.STORAGE_API_VERSION")

    def test_inventory_api_version_matches_java(self) -> None:
        inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
        java = (ROOT / "src/main/java/ru/cherdakmd/chrdkregister/api/ChrdkRegisterApi.java"
                ).read_text(encoding="utf-8")
        match = re.search(r"API_VERSION\s*=\s*(\d+)", java)
        self.assertIsNotNone(match, "API_VERSION not found in ChrdkRegisterApi.java")
        java_version = int(match.group(1))
        self.assertEqual(inventory["api_version"], java_version,
                         "storage-inventory.json.api_version != ChrdkRegisterApi.API_VERSION")

    def test_ddl_checksum_matches_java(self) -> None:
        sql = SCHEMA_SQL.read_text(encoding="utf-8")
        marker = "-- MIGRATION_MARKER\n"
        self.assertIn(marker, sql, "database-schema.sql lacks MIGRATION_MARKER")
        ddl = sql[:sql.index(marker) + len(marker)]
        expected = hashlib.sha256(ddl.encode("utf-8")).hexdigest()
        java = STORAGE_SCHEMA.read_text(encoding="utf-8")
        match = re.search(r'DDL_CHECKSUM\s*=\s*"([a-f0-9]+)"', java)
        self.assertIsNotNone(match, "DDL_CHECKSUM not found in StorageSchema.java")
        java_checksum = match.group(1)
        self.assertEqual(expected, java_checksum,
                         "database-schema.sql SHA-256 != StorageSchema.DDL_CHECKSUM")

    def test_projected_tables_exist_in_ddl(self) -> None:
        sql = SCHEMA_SQL.read_text(encoding="utf-8")
        created = set(re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)", sql))
        java = STORAGE_SCHEMA.read_text(encoding="utf-8")
        projected = re.findall(r'"(chrdk_[^"]+)"', java)
        missing = [name for name in projected if name not in created]
        self.assertEqual(missing, [],
                         f"StorageSchema.PROJECTED_TABLES missing from DDL: {missing}")

    def test_inventory_files_have_valid_targets(self) -> None:
        inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
        valid_strategies = {"db_document", "normalized_player", "normalized_finance",
                            "normalized_social", "normalized_moderation", "normalized_exploration",
                            "auth_reset", "exclude"}
        for entry in inventory["files"]:
            with self.subTest(path=entry["path"]):
                self.assertIn(entry["strategy"], valid_strategies,
                              f"invalid strategy: {entry['strategy']}")
                self.assertIn(entry["secret_class"], {"none", "login", "service_credentials"},
                              f"invalid secret_class: {entry['secret_class']}")
                self.assertIn(entry["checksum_policy"],
                              {"sha256", "sha256+integrity-sidecar", "canonical-json-sha256",
                               "yaml-manual", "force+atomic", "exclude"},
                              f"invalid checksum_policy: {entry['checksum_policy']}")

    def test_inventory_secret_exclusions_match_secret_files(self) -> None:
        inventory = json.loads(INVENTORY.read_text(encoding="utf-8"))
        secret_files = {entry["path"] for entry in inventory["files"] if entry["secret"]}
        exclusions = set(inventory["secret_exclusions"])
        for path in secret_files:
            with self.subTest(path=path):
                self.assertIn(path, exclusions,
                              f"secret file {path} not in secret_exclusions")


if __name__ == "__main__":
    unittest.main()
