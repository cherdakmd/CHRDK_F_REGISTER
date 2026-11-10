#!/usr/bin/env python3
"""Generate database-schema.sql and the Java StorageSchema constants.

The DDL checksum is embedded in both artefacts and validated by ChrdkDatabase
at startup, so a hand-edited schema fails closed instead of silently drifting.
"""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SQL = ROOT / "src/main/resources/database-schema.sql"
JAVA = ROOT / "src/main/java/ru/cherdakmd/chrdkregister/storage/db/StorageSchema.java"
MARKER = "-- MIGRATION_MARKER"

COMMON = [
    "namespace VARBINARY(96) NOT NULL",
    "source_path VARBINARY(1024) NOT NULL",
    "source_sha256 BINARY(32) NOT NULL",
    "batch VARCHAR(64) NOT NULL",
    "schema_version INT NOT NULL DEFAULT 1",
    "row_revision BIGINT NOT NULL DEFAULT 1",
    "updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)",
]

# name, explicit columns, constraints, use_common
TABLES: list[tuple[str, list[str], list[str], bool]] = [
    ("chrdk_schema_migration", [
        "version INT NOT NULL",
        "checksum BINARY(32) NOT NULL",
        "description VARCHAR(255) NOT NULL",
        "applied_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)",
    ], ["PRIMARY KEY (version)"], False),
    ("chrdk_meta", [
        "meta_key VARBINARY(128) NOT NULL",
        "meta_value LONGBLOB NOT NULL",
    ], ["PRIMARY KEY (meta_key)"], False),
    ("chrdk_storage_file", [
        "relative_path VARBINARY(1024) NOT NULL",
        "content LONGBLOB NOT NULL",
        "sha256 BINARY(32) NOT NULL",
        "revision BIGINT NOT NULL DEFAULT 1",
    ], ["PRIMARY KEY (namespace, relative_path)"], True),
    ("chrdk_migration_source", [
        "batch VARCHAR(64) NOT NULL",
        "namespace VARBINARY(96) NOT NULL",
        "source_path VARBINARY(1024) NOT NULL",
        "sha256 BINARY(32) NOT NULL",
        "size BIGINT NOT NULL DEFAULT 0",
        "row_count BIGINT NOT NULL DEFAULT 0",
        "target VARCHAR(255) NOT NULL",
        "status VARCHAR(32) NOT NULL",
        "imported_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)",
        "report_json LONGTEXT NULL",
    ], ["PRIMARY KEY (batch, namespace, source_path)"], False),
    ("chrdk_namespace_revision", [
        "revision BIGINT NOT NULL DEFAULT 0",
    ], ["PRIMARY KEY (namespace)"], True),
    ("chrdk_player", [
        "uuid VARBINARY(128) NOT NULL",
        "canonical_name VARBINARY(256) NOT NULL",
        "last_name VARBINARY(256) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "auth_reset_required TINYINT NOT NULL DEFAULT 1",
        "merged_from VARBINARY(256) NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid)", "UNIQUE KEY uq1 (canonical_name)"], True),
    ("chrdk_account", [
        "uuid VARBINARY(128) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid)"], True),
    ("chrdk_entitlement", [
        "uuid VARBINARY(128) NOT NULL",
        "product_id VARBINARY(256) NOT NULL",
        "package_index INT NOT NULL",
        "until_ms BIGINT NOT NULL DEFAULT 0",
        "receipt_key VARBINARY(256) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid, product_id, package_index)"], True),
    ("chrdk_auth_attempt", [
        "kind VARBINARY(64) NOT NULL",
        "subject VARBINARY(256) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (kind, subject)"], True),
    ("chrdk_auth_reset", [
        "uuid VARBINARY(128) NOT NULL",
        "reason VARBINARY(255) NOT NULL",
        "issued_by VARBINARY(128) NOT NULL",
        "code_hash BINARY(32) NULL",
        "expires_at_ms BIGINT NOT NULL DEFAULT 0",
        "used_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid)"], True),
    ("chrdk_purchase", [
        "id VARBINARY(128) NOT NULL",
        "source VARBINARY(128) NOT NULL",
        "external_id VARBINARY(256) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "player_name VARBINARY(256) NOT NULL",
        "amount_kopecks BIGINT NOT NULL DEFAULT 0",
        "product_id VARBINARY(256) NOT NULL",
        "status VARBINARY(64) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)", "UNIQUE KEY uq1 (source, external_id)"], True),
    ("chrdk_purchase_receipt", [
        "receipt_key VARBINARY(256) NOT NULL",
        "purchase_id VARBINARY(128) NOT NULL",
        "actor VARBINARY(128) NOT NULL",
        "command VARBINARY(255) NOT NULL",
        "outcome VARBINARY(64) NOT NULL",
        "confirmed_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (receipt_key)"], True),
    ("chrdk_store_delivery", [
        "id VARBINARY(128) NOT NULL",
        "actor VARBINARY(128) NOT NULL",
        "command VARBINARY(255) NOT NULL",
        "outcome VARBINARY(64) NOT NULL",
        "confirmed_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_processed_donation", [
        "source VARBINARY(128) NOT NULL",
        "external_id VARBINARY(256) NOT NULL",
        "processed_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (source, external_id)"], True),
    ("chrdk_purchase_cursor", [
        "source VARBINARY(128) NOT NULL",
        "cursor_value BIGINT NOT NULL DEFAULT 0",
        "initialized TINYINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (source)"], True),
    ("chrdk_payment_code", [
        "code VARBINARY(128) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "used_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (code)"], True),
    ("chrdk_donation_journal", [
        "id VARBINARY(128) NOT NULL",
        "source VARBINARY(128) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id, source)"], True),
    ("chrdk_bank_account", [
        "uuid VARBINARY(128) NOT NULL",
        "balance BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid)"], True),
    ("chrdk_bank_ledger", [
        "id VARBINARY(128) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "delta BIGINT NOT NULL DEFAULT 0",
        "balance_after BIGINT NOT NULL DEFAULT 0",
        "kind VARBINARY(64) NOT NULL",
        "ref_key VARBINARY(256) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_bank_cash_intent", [
        "id VARBINARY(128) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "state VARBINARY(64) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_bank_cash_receipt", [
        "id VARBINARY(128) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_income_source", [
        "source VARBINARY(128) NOT NULL",
        "lineage VARBINARY(128) NOT NULL",
        "registered_at_ms BIGINT NOT NULL DEFAULT 0",
        "source_file_sha256 BINARY(32) NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (source)", "UNIQUE KEY uq1 (lineage)"], True),
    ("chrdk_entitlement_credit", [
        "source VARBINARY(128) NOT NULL",
        "lineage VARBINARY(128) NOT NULL",
        "receipt_key VARBINARY(256) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "credited BIGINT NOT NULL DEFAULT 0",
        "total BIGINT NOT NULL DEFAULT 0",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (source, lineage, receipt_key)"], True),
    ("chrdk_charge_journal", [
        "source VARBINARY(128) NOT NULL",
        "lineage VARBINARY(128) NOT NULL",
        "sequence_value BIGINT NOT NULL DEFAULT 0",
        "pending_json JSON NULL",
        "before_state_hash BINARY(32) NULL",
        "after_state_hash BINARY(32) NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (source, lineage)"], True),
    ("chrdk_charge_settlement", [
        "receipt_key VARBINARY(256) NOT NULL",
        "source VARBINARY(128) NOT NULL",
        "lineage VARBINARY(128) NOT NULL",
        "sequence_value BIGINT NOT NULL DEFAULT 0",
        "witness VARBINARY(128) NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (receipt_key)"], True),
    ("chrdk_shop_exchange", [
        "receipt_key VARBINARY(256) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (receipt_key)"], True),
    ("chrdk_claim_exchange", [
        "receipt_key VARBINARY(256) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (receipt_key)"], True),
    ("chrdk_auction_payment", [
        "receipt_key VARBINARY(256) NOT NULL",
        "market VARBINARY(128) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (receipt_key)"], True),
    ("chrdk_auction_import", [
        "market VARBINARY(128) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "amount BIGINT NOT NULL DEFAULT 0",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (market, uuid)"], True),
    ("chrdk_claim_import", [
        "receipt_key VARBINARY(256) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "amount BIGINT NOT NULL DEFAULT 0",
        "created_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (receipt_key)"], True),
    ("chrdk_shop_remainder", [
        "uuid VARBINARY(128) NOT NULL",
        "remainder VARBINARY(128) NOT NULL",
        "updated_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid)"], True),
    ("chrdk_milestone_source", [
        "source VARBINARY(128) NOT NULL",
        "lineage VARBINARY(128) NOT NULL",
        "imported TINYINT NOT NULL DEFAULT 0",
        "registered TINYINT NOT NULL DEFAULT 0",
        "source_file_sha256 BINARY(32) NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (source)", "UNIQUE KEY uq1 (lineage)"], True),
    ("chrdk_milestone_award", [
        "source VARBINARY(128) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "award_key VARBINARY(128) NOT NULL",
        "amount BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (source, uuid, award_key)"], True),
    ("chrdk_paid_action_source", [
        "lineage VARBINARY(128) NOT NULL",
        "registered TINYINT NOT NULL DEFAULT 0",
        "values_initialized TINYINT NOT NULL DEFAULT 0",
        "source_file_sha256 BINARY(32) NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (lineage)"], True),
    ("chrdk_paid_action", [
        "id VARBINARY(128) NOT NULL",
        "payer VARBINARY(128) NOT NULL",
        "actor VARBINARY(128) NOT NULL",
        "price BIGINT NOT NULL DEFAULT 0",
        "kind VARBINARY(64) NOT NULL",
        "phase VARBINARY(32) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_paid_refund", [
        "payer VARBINARY(128) NOT NULL",
        "amount BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (payer)"], True),
    ("chrdk_paid_resolution", [
        "id VARBINARY(128) NOT NULL",
        "action_key VARBINARY(128) NOT NULL",
        "payer VARBINARY(128) NOT NULL",
        "price BIGINT NOT NULL DEFAULT 0",
        "success TINYINT NOT NULL DEFAULT 0",
        "operator_name VARBINARY(128) NOT NULL",
        "at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_paid_value", [
        "value_key VARBINARY(256) NOT NULL",
        "value_text LONGTEXT NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (value_key)"], True),
    ("chrdk_external_binding", [
        "external_id VARBINARY(256) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (external_id)"], True),
    ("chrdk_moderation_global", [
        "id VARBINARY(64) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_sanction", [
        "id VARBINARY(256) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "kind VARBINARY(32) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_exploration", [
        "uuid VARBINARY(128) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid)"], True),
    ("chrdk_document", [
        "entity_key VARBINARY(512) NOT NULL",
        "revision BIGINT NOT NULL DEFAULT 1",
        "payload JSON NOT NULL",
        "source_checksum BINARY(32) NOT NULL",
    ], ["PRIMARY KEY (namespace, entity_key)"], True),
    ("chrdk_document_history", [
        "entity_key VARBINARY(512) NOT NULL",
        "revision BIGINT NOT NULL",
        "payload JSON NOT NULL",
        "source_checksum BINARY(32) NOT NULL",
        "replaced_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)",
        "reason VARCHAR(255) NOT NULL",
    ], ["PRIMARY KEY (namespace, entity_key, revision)"], True),
    ("chrdk_social_global", [
        "id VARBINARY(64) NOT NULL",
        "schema_value INT NOT NULL DEFAULT 0",
        "player_data_schema INT NOT NULL DEFAULT 0",
        "rollout_started_at BIGINT NOT NULL DEFAULT 0",
        "emergency_until BIGINT NOT NULL DEFAULT 0",
        "last_news_tag VARBINARY(255) NOT NULL",
        "max_chat_id VARBINARY(128) NOT NULL",
        "max_news_chat_id VARBINARY(128) NOT NULL",
        "max_bridge_chat_id VARBINARY(128) NOT NULL",
        "checksum BINARY(32) NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_social_chat", [
        "chat_id VARBINARY(128) NOT NULL",
        "provider VARBINARY(32) NOT NULL",
        "discovered_at BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (chat_id)"], True),
    ("chrdk_social_link", [
        "uuid VARBINARY(128) NOT NULL",
        "canonical_name VARBINARY(256) NOT NULL",
        "provider VARBINARY(32) NOT NULL",
        "external_id VARBINARY(128) NOT NULL",
        "external_name VARBINARY(256) NOT NULL",
        "linked_at_ms BIGINT NOT NULL DEFAULT 0",
        "source VARBINARY(64) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid)", "UNIQUE KEY uq1 (canonical_name)", "UNIQUE KEY uq2 (provider, external_id)"], True),
    ("chrdk_social_profile", [
        "provider VARBINARY(32) NOT NULL",
        "external_id VARBINARY(128) NOT NULL",
        "uuid VARBINARY(128) NOT NULL",
        "usage_rank INT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (provider, external_id, uuid)"], True),
    ("chrdk_social_player", [
        "uuid VARBINARY(128) NOT NULL",
        "canonical_name VARBINARY(256) NOT NULL",
        "first_seen_at BIGINT NOT NULL DEFAULT 0",
        "donor_granted_at BIGINT NOT NULL DEFAULT 0",
        "donor_source VARBINARY(128) NOT NULL",
        "reward_claimed_at BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid)"], True),
    ("chrdk_social_link_tombstone", [
        "uuid VARBINARY(128) NOT NULL",
        "provider VARBINARY(32) NOT NULL",
        "external_id VARBINARY(128) NOT NULL",
        "unlinked_at_ms BIGINT NOT NULL DEFAULT 0",
        "command VARBINARY(128) NOT NULL",
        "actor VARBINARY(128) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (uuid, provider, external_id)"], True),
    ("chrdk_vk_legacy_link", [
        "canonical_name VARBINARY(256) NOT NULL",
        "vk_user_id VARBINARY(128) NOT NULL",
        "linked_at BIGINT NOT NULL DEFAULT 0",
        "member TINYINT NOT NULL DEFAULT 0",
        "donut TINYINT NOT NULL DEFAULT 0",
        "donut_verified_at BIGINT NOT NULL DEFAULT 0",
        "notify_logins TINYINT NOT NULL DEFAULT 0",
        "allow_vk_chat_mentions TINYINT NOT NULL DEFAULT 0",
        "source_file_sha256 BINARY(32) NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (canonical_name)"], True),
    ("chrdk_vk_boost_claim", [
        "kind VARBINARY(64) NOT NULL",
        "player_or_vk_id VARBINARY(128) NOT NULL",
        "claimed_at_ms BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (kind, player_or_vk_id)"], True),
    ("chrdk_vk_chat_state", [
        "id VARBINARY(64) NOT NULL",
        "hidden_players JSON NULL",
        "banned_vk_ids JSON NULL",
        "last_announced_post_id BIGINT NOT NULL DEFAULT 0",
        "group_chat_peer_id BIGINT NOT NULL DEFAULT 0",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (id)"], True),
    ("chrdk_social_daily_card", [
        "card_key VARBINARY(256) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (card_key)"], True),
    ("chrdk_social_wall_digest", [
        "digest_key VARBINARY(256) NOT NULL",
        "payload JSON NOT NULL",
    ], ["PRIMARY KEY (digest_key)"], True),
]

EXCLUDED_FROM_PROJECTIONS = {
    "chrdk_schema_migration", "chrdk_meta", "chrdk_storage_file",
    "chrdk_migration_source", "chrdk_namespace_revision",
}


def column_name(definition: str) -> str:
    return definition.split(None, 1)[0]


def create_statement(name: str, columns: list[str], constraints: list[str], common: bool) -> str:
    existing = {column_name(c) for c in columns}
    merged = list(columns)
    if common:
        for definition in COMMON:
            if column_name(definition) not in existing:
                merged.append(definition)
    body = ",\n  ".join(merged + constraints)
    return f"CREATE TABLE IF NOT EXISTS {name} (\n  {body}\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"


def build_sql(checksum: str) -> str:
    statements = [create_statement(*spec) for spec in TABLES]
    ddl = "\n".join(statements) + "\n" + MARKER + "\n"
    inserts = (
        "INSERT IGNORE INTO chrdk_schema_migration (version, checksum, description)\n"
        f"VALUES (1, UNHEX('{checksum}'), 'chrdk-storage-schema-1');\n\n"
        "INSERT IGNORE INTO chrdk_meta (meta_key, meta_value)\n"
        "VALUES ('schema_version', '1');\n\n"
        "INSERT IGNORE INTO chrdk_meta (meta_key, meta_value)\n"
        "VALUES ('storage_epoch', UUID());\n\n"
        "INSERT IGNORE INTO chrdk_meta (meta_key, meta_value)\n"
        "VALUES ('active_batch', 'runtime');\n"
    )
    return ddl + inserts


def build_java(checksum: str) -> str:
    projected = [name for name, *_ in TABLES if name not in EXCLUDED_FROM_PROJECTIONS]
    lines = ",\n        ".join(f'"{name}"' for name in projected)
    return (
        "package ru.cherdakmd.chrdkregister.storage.db;\n\n"
        "import java.util.List;\n\n"
        "/** Generated by tools/db/generate_schema.py; do not edit by hand. */\n"
        "public final class StorageSchema {\n\n"
        f'    public static final String MIGRATION_MARKER = "{MARKER}";\n'
        f'    public static final String DDL_CHECKSUM = "{checksum}";\n\n'
        "    public static final List<String> PROJECTED_TABLES = List.of(\n"
        f"        {lines}\n"
        "    );\n\n"
        "    private StorageSchema() {\n"
        "    }\n"
        "}\n"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    statements = [create_statement(*spec) for spec in TABLES]
    ddl = "\n".join(statements) + "\n" + MARKER + "\n"
    checksum = hashlib.sha256(ddl.encode("utf-8")).hexdigest()
    sql = build_sql(checksum)
    java = build_java(checksum)
    expected = {SQL: sql, JAVA: java}
    if args.check:
        stale = [str(path) for path, text in expected.items()
                 if not path.exists() or path.read_text(encoding="utf-8") != text]
        if stale:
            print("Generated storage schema is stale: " + ", ".join(stale), file=sys.stderr)
            return 1
        print(f"storage schema is current: {len(TABLES)} tables, checksum {checksum}")
        return 0
    for path, text in expected.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    print(f"wrote {SQL} and {JAVA}: {len(TABLES)} tables, checksum {checksum}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
