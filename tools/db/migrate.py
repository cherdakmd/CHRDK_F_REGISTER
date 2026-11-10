#!/usr/bin/env python3
"""Offline migrator: legacy JSON/YAML → normalized MySQL tables.

Usage:
  python3 tools/db/migrate.py --dry-run --source-root plugins/CHRDK_Core
  python3 tools/db/migrate.py --import --source-root plugins/CHRDK_Core --batch rc11-20261103
  python3 tools/db/migrate.py --verify --batch rc11-20261103
  python3 tools/db/migrate.py --report --batch rc11-20261103 --output report.json

Requires: PyMySQL, PyYAML (pip install -r tools/db/requirements.txt)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import pymysql
except ImportError:
    pymysql = None

try:
    import yaml
except ImportError:
    yaml = None

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / "src/main/resources/storage-inventory.json"


def load_inventory() -> dict:
    return json.loads(INVENTORY.read_text(encoding="utf-8"))


def sha256_file(path: Path) -> bytes:
    return hashlib.sha256(path.read_bytes()).digest()


def sha256_hex(data: bytes) -> str:
    return data.hex()


def connect_db(config: dict):
    if pymysql is None:
        raise RuntimeError("PyMySQL not installed: pip install -r tools/db/requirements.txt")
    return pymysql.connect(
        host=config["host"],
        port=config["port"],
        user=config["user"],
        password=config["password"],
        database=config["database"],
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )


def dry_run(inventory: dict, source_root: Path) -> dict:
    report = {"mode": "dry-run", "source_root": str(source_root), "files": []}
    for entry in inventory["files"]:
        if entry["strategy"] == "exclude":
            continue
        path = source_root / entry["path"]
        if not path.exists():
            report["files"].append({
                "module": entry["module"],
                "path": entry["path"],
                "status": "missing",
            })
            continue
        sha = sha256_file(path)
        report["files"].append({
            "module": entry["module"],
            "path": entry["path"],
            "strategy": entry["strategy"],
            "size": path.stat().st_size,
            "sha256": sha256_hex(sha),
            "status": "ready",
        })
    return report


def import_files(inventory: dict, source_root: Path, batch: str, db_config: dict) -> dict:
    if pymysql is None:
        raise RuntimeError("PyMySQL not installed")
    conn = connect_db(db_config)
    report = {"mode": "import", "batch": batch, "source_root": str(source_root), "files": []}
    try:
        with conn.cursor() as cursor:
            for entry in inventory["files"]:
                if entry["strategy"] == "exclude":
                    continue
                path = source_root / entry["path"]
                if not path.exists():
                    report["files"].append({
                        "module": entry["module"],
                        "path": entry["path"],
                        "status": "missing",
                    })
                    continue
                sha = sha256_file(path)
                content = path.read_bytes()
                # For db_document strategy: copy raw JSON/YAML to chrdk_storage_file
                if entry["strategy"] == "db_document":
                    namespace = entry["owner"]
                    relative_path = entry["path"]
                    cursor.execute(
                        "INSERT INTO chrdk_storage_file "
                        "(namespace, relative_path, content, sha256, revision, "
                        "source_path, source_sha256, batch) "
                        "VALUES (%s, %s, %s, %s, 1, %s, %s, %s) "
                        "ON DUPLICATE KEY UPDATE content = VALUES(content), "
                        "sha256 = VALUES(sha256), revision = revision + 1, "
                        "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
                        "batch = VALUES(batch)",
                        (namespace.encode(), relative_path.encode(), content, sha,
                         str(path).encode(), sha, batch),
                    )
                    report["files"].append({
                        "module": entry["module"],
                        "path": entry["path"],
                        "strategy": "db_document",
                        "size": len(content),
                        "sha256": sha256_hex(sha),
                        "status": "imported",
                    })
                else:
                    # Normalized strategies require per-table parsers (TODO)
                    report["files"].append({
                        "module": entry["module"],
                        "path": entry["path"],
                        "strategy": entry["strategy"],
                        "size": len(content),
                        "sha256": sha256_hex(sha),
                        "status": "skipped",
                        "reason": "normalized strategy not yet implemented",
                    })
            # Record migration source
            for file_report in report["files"]:
                if file_report["status"] in ("imported", "skipped"):
                    cursor.execute(
                        "INSERT IGNORE INTO chrdk_migration_source "
                        "(batch, namespace, source_path, sha256, size, row_count, target, status) "
                        "VALUES (%s, %s, %s, %s, %s, 0, %s, %s)",
                        (batch, file_report["module"].encode(),
                         str(source_root / file_report["path"]).encode(),
                         bytes.fromhex(file_report["sha256"]),
                         file_report["size"], file_report["strategy"], file_report["status"]),
                    )
        conn.commit()
    finally:
        conn.close()
    return report


def verify(inventory: dict, batch: str, db_config: dict) -> dict:
    if pymysql is None:
        raise RuntimeError("PyMySQL not installed")
    conn = connect_db(db_config)
    report = {"mode": "verify", "batch": batch, "files": []}
    try:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT namespace, source_path, sha256, size, target, status "
                "FROM chrdk_migration_source WHERE batch = %s",
                (batch,),
            )
            rows = cursor.fetchall()
            for row in rows:
                report["files"].append({
                    "namespace": row["namespace"].decode(),
                    "source_path": row["source_path"].decode(),
                    "sha256": row["sha256"].hex(),
                    "size": row["size"],
                    "target": row["target"],
                    "status": row["status"],
                })
    finally:
        conn.close()
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--dry-run", action="store_true")
    group.add_argument("--import", dest="do_import", action="store_true")
    group.add_argument("--verify", action="store_true")
    group.add_argument("--report", action="store_true")
    parser.add_argument("--source-root", type=Path, default=Path("plugins/CHRDK_Core"))
    parser.add_argument("--batch", type=str, help="Migration batch ID (required for import/verify/report)")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=3306)
    parser.add_argument("--db-name", default="chrdk")
    parser.add_argument("--db-user", required=False)
    parser.add_argument("--db-password", required=False)
    parser.add_argument("--output", type=Path, help="Output file for report (JSON)")
    args = parser.parse_args()

    if args.do_import or args.verify or args.report:
        if not args.batch:
            parser.error("--batch is required for import/verify/report")
        if not args.db_user or not args.db_password:
            parser.error("--db-user and --db-password are required for import/verify/report")

    inventory = load_inventory()
    db_config = {
        "host": args.db_host,
        "port": args.db_port,
        "database": args.db_name,
        "user": args.db_user or "",
        "password": args.db_password or "",
    }

    if args.dry_run:
        report = dry_run(inventory, args.source_root)
    elif args.do_import:
        report = import_files(inventory, args.source_root, args.batch, db_config)
    elif args.verify:
        report = verify(inventory, args.batch, db_config)
    elif args.report:
        report = verify(inventory, args.batch, db_config)
    else:
        parser.error("No action specified")
        return 1

    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(output, encoding="utf-8")
        print(f"wrote {args.output}: {len(report['files'])} files")
    else:
        print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
