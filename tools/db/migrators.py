"""Normalized migrator parsers: legacy JSON/YAML → normalized MySQL tables.

Each strategy function parses a specific file format and writes to normalized tables.
Requires: PyMySQL, PyYAML (pip install -r tools/db/requirements.txt)
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:
    yaml = None


def normalize_player(content: bytes, path: Path, cursor, batch: str, namespace: str) -> int:
    """Parse data.json → chrdk_player/account/entitlement/purchase/receipt/donation/code/cursor.

    Schema 4: accounts Map<uuid, PlayerAccount>, paymentReceipts, storeDeliveries, donations, etc.
    """
    data = json.loads(content.decode("utf-8"))
    if data.get("version") != 4:
        raise ValueError(f"data.json version {data.get('version')} != 4")

    rows = 0
    accounts = data.get("accounts", {})
    for uuid, account in accounts.items():
        # chrdk_player
        cursor.execute(
            "INSERT INTO chrdk_player (uuid, canonical_name, last_name, created_at_ms, "
            "auth_reset_required, merged_from, payload, "
            "namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE last_name = VALUES(last_name), "
            "payload = VALUES(payload), source_path = VALUES(source_path), "
            "source_sha256 = VALUES(source_sha256), batch = VALUES(batch)",
            (uuid.encode(), account.get("canonicalName", "").encode(),
             account.get("playerName", "").encode(),
             account.get("registeredAt", 0), 1, None, json.dumps(account).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        # chrdk_account
        cursor.execute(
            "INSERT INTO chrdk_account (uuid, payload, "
            "namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (uuid.encode(), json.dumps(account).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        # chrdk_entitlement (entitlements + entitlementPackages)
        for product_id, until_ms in account.get("entitlements", {}).items():
            receipt_key = f"{uuid}:{product_id}"
            cursor.execute(
                "INSERT INTO chrdk_entitlement (uuid, product_id, package_index, until_ms, "
                "receipt_key, payload, namespace, source_path, source_sha256, batch) "
                "VALUES (%s, %s, 0, %s, %s, %s, %s, %s, %s, %s) "
                "ON DUPLICATE KEY UPDATE until_ms = VALUES(until_ms), "
                "payload = VALUES(payload), source_path = VALUES(source_path), "
                "source_sha256 = VALUES(source_sha256), batch = VALUES(batch)",
                (uuid.encode(), product_id.encode(), until_ms, receipt_key.encode(),
                 json.dumps({"until_ms": until_ms}).encode(),
                 namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
            )
        for product_id, packages in account.get("entitlementPackages", {}).items():
            for idx, until_ms in enumerate(packages):
                receipt_key = f"{uuid}:{product_id}:{idx}"
                cursor.execute(
                    "INSERT INTO chrdk_entitlement (uuid, product_id, package_index, until_ms, "
                    "receipt_key, payload, namespace, source_path, source_sha256, batch) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
                    "ON DUPLICATE KEY UPDATE until_ms = VALUES(until_ms), "
                    "payload = VALUES(payload), source_path = VALUES(source_path), "
                    "source_sha256 = VALUES(source_sha256), batch = VALUES(batch)",
                    (uuid.encode(), product_id.encode(), idx, until_ms, receipt_key.encode(),
                     json.dumps({"until_ms": until_ms}).encode(),
                     namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
                )
        rows += 1

    # chrdk_purchase_receipt
    for receipt_key, receipt in data.get("paymentReceipts", {}).items():
        cursor.execute(
            "INSERT INTO chrdk_purchase_receipt (receipt_key, purchase_id, actor, command, "
            "outcome, confirmed_at_ms, payload, namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (receipt_key.encode(), receipt.get("purchaseId", "").encode(),
             receipt.get("actor", "").encode(), receipt.get("command", "").encode(),
             receipt.get("outcome", "").encode(), receipt.get("confirmedAt", 0),
             json.dumps(receipt).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    # chrdk_store_delivery
    for delivery_id, delivery in data.get("storeDeliveries", {}).items():
        cursor.execute(
            "INSERT INTO chrdk_store_delivery (id, actor, command, outcome, confirmed_at_ms, "
            "payload, namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (delivery_id.encode(), delivery.get("actor", "").encode(),
             delivery.get("command", "").encode(), delivery.get("outcome", "").encode(),
             delivery.get("confirmedAt", 0), json.dumps(delivery).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    # chrdk_donation_journal
    for donation in data.get("donations", []):
        donation_id = str(donation.get("id", ""))
        source = donation.get("source", "donatepay")
        cursor.execute(
            "INSERT INTO chrdk_donation_journal (id, source, created_at_ms, payload, "
            "namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (donation_id.encode(), source.encode(), donation.get("createdAt", 0),
             json.dumps(donation).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    # chrdk_processed_donation
    for donation_id in data.get("processedDonationIds", []):
        cursor.execute(
            "INSERT INTO chrdk_processed_donation (source, external_id, processed_at_ms, "
            "payload, namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            ("donatepay".encode(), donation_id.encode(), 0,
             json.dumps({"id": donation_id}).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    # chrdk_payment_code
    for code, uuid in data.get("paymentCodes", {}).items():
        cursor.execute(
            "INSERT INTO chrdk_payment_code (code, uuid, created_at_ms, used_at_ms, payload, "
            "namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE uuid = VALUES(uuid), payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (code.encode(), uuid.encode(), 0, 0,
             json.dumps({"code": code, "uuid": uuid}).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    # chrdk_purchase_cursor
    for source, cursor_value in data.get("donationCursorsBySource", {}).items():
        initialized = 1 if cursor_value > 0 else 0
        cursor.execute(
            "INSERT INTO chrdk_purchase_cursor (source, cursor_value, initialized, payload, "
            "namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE cursor_value = VALUES(cursor_value), "
            "initialized = VALUES(initialized), payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (source.encode(), cursor_value, initialized,
             json.dumps({"cursor": cursor_value}).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    return rows


def normalize_finance(content: bytes, path: Path, cursor, batch: str, namespace: str) -> int:
    """Parse bank.json → chrdk_bank_account/receipt/cash_intent/cash_receipt/income_source.

    Schema 8: accounts Map<uuid, BankAccount>, cashReceipts, incomeSources, etc.
    """
    data = json.loads(content.decode("utf-8"))
    if data.get("schema") != 8:
        raise ValueError(f"bank.json schema {data.get('schema')} != 8")

    rows = 0
    accounts = data.get("accounts", {})
    for uuid, account in accounts.items():
        cursor.execute(
            "INSERT INTO chrdk_bank_account (uuid, balance, payload, "
            "namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE balance = VALUES(balance), payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (uuid.encode(), account.get("balance", 0), json.dumps(account).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    # cashReceipts → chrdk_bank_cash_receipt
    for receipt in data.get("cashReceipts", []):
        receipt_id = receipt.get("id", "")
        uuid = receipt.get("uuid", "")
        cursor.execute(
            "INSERT INTO chrdk_bank_cash_receipt (id, uuid, created_at_ms, payload, "
            "namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (receipt_id.encode(), uuid.encode(), receipt.get("createdAt", 0),
             json.dumps(receipt).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    # incomeSources → chrdk_income_source
    for source, income in data.get("incomeSources", {}).items():
        lineage = income.get("lineage", source)
        cursor.execute(
            "INSERT INTO chrdk_income_source (source, lineage, registered_at_ms, "
            "source_file_sha256, payload, namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE lineage = VALUES(lineage), "
            "registered_at_ms = VALUES(registered_at_ms), payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (source.encode(), lineage.encode(), income.get("registeredAt", 0),
             None, json.dumps(income).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    return rows


def normalize_social(content: bytes, path: Path, cursor, batch: str, namespace: str) -> int:
    """Parse social-links.json → chrdk_social_global/chat.

    Schema 2: global settings, chats, etc.
    """
    data = json.loads(content.decode("utf-8"))
    if data.get("schema") != 2:
        raise ValueError(f"social-links.json schema {data.get('schema')} != 2")

    rows = 0
    # chrdk_social_global
    cursor.execute(
        "INSERT INTO chrdk_social_global (id, schema_value, player_data_schema, "
        "rollout_started_at, emergency_until, last_news_tag, max_chat_id, "
        "max_news_chat_id, max_bridge_chat_id, checksum, payload, "
        "namespace, source_path, source_sha256, batch) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
        "ON DUPLICATE KEY UPDATE schema_value = VALUES(schema_value), "
        "player_data_schema = VALUES(player_data_schema), payload = VALUES(payload), "
        "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
        "batch = VALUES(batch)",
        ("global".encode(), data.get("schema", 0), data.get("playerDataSchema", 0),
         data.get("rolloutStartedAt", 0), data.get("emergencyUntil", 0),
         data.get("lastNewsTag", "").encode(), data.get("maxChatId", "").encode(),
         data.get("maxNewsChatId", "").encode(), data.get("maxBridgeChatId", "").encode(),
         None, json.dumps(data).encode(),
         namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
    )
    rows += 1

    # chrdk_social_chat (if chats exist)
    for chat_id, chat in data.get("chats", {}).items():
        cursor.execute(
            "INSERT INTO chrdk_social_chat (chat_id, provider, discovered_at, payload, "
            "namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE provider = VALUES(provider), "
            "discovered_at = VALUES(discovered_at), payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (chat_id.encode(), chat.get("provider", "vk").encode(),
             chat.get("discoveredAt", 0), json.dumps(chat).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        rows += 1

    return rows


def normalize_social_players(content: bytes, path: Path, cursor, batch: str, namespace: str) -> int:
    """Parse players/social.yml → chrdk_social_link/player/profile.

    Schema 1: players Map<uuid, SocialPlayer>, profiles, etc.
    """
    if yaml is None:
        raise RuntimeError("PyYAML not installed")
    data = yaml.safe_load(content.decode("utf-8"))
    if not isinstance(data, dict):
        raise ValueError("social.yml is not a dict")
    if data.get("schema") != 1:
        raise ValueError(f"social.yml schema {data.get('schema')} != 1")

    rows = 0
    players = data.get("players", {})
    for uuid, player in players.items():
        # chrdk_social_player
        cursor.execute(
            "INSERT INTO chrdk_social_player (uuid, canonical_name, first_seen_at, "
            "donor_granted_at, donor_source, reward_claimed_at, payload, "
            "namespace, source_path, source_sha256, batch) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE canonical_name = VALUES(canonical_name), "
            "first_seen_at = VALUES(first_seen_at), payload = VALUES(payload), "
            "source_path = VALUES(source_path), source_sha256 = VALUES(source_sha256), "
            "batch = VALUES(batch)",
            (uuid.encode(), player.get("canonicalName", "").encode(),
             player.get("firstSeenAt", 0), player.get("donorGrantedAt", 0),
             player.get("donorSource", "").encode(), player.get("rewardClaimedAt", 0),
             json.dumps(player).encode(),
             namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
        )
        # chrdk_social_link (if link exists)
        link = player.get("link")
        if link:
            cursor.execute(
                "INSERT INTO chrdk_social_link (uuid, canonical_name, provider, external_id, "
                "external_name, linked_at_ms, source, payload, "
                "namespace, source_path, source_sha256, batch) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
                "ON DUPLICATE KEY UPDATE provider = VALUES(provider), "
                "external_id = VALUES(external_id), linked_at_ms = VALUES(linked_at_ms), "
                "payload = VALUES(payload), source_path = VALUES(source_path), "
                "source_sha256 = VALUES(source_sha256), batch = VALUES(batch)",
                (uuid.encode(), player.get("canonicalName", "").encode(),
                 link.get("provider", "").encode(), link.get("externalId", "").encode(),
                 link.get("externalName", "").encode(), link.get("linkedAt", 0),
                 link.get("source", "manual").encode(), json.dumps(link).encode(),
                 namespace.encode(), str(path).encode(), b'\x00' * 32, batch),
            )
        rows += 1

    return rows


# Strategy registry
STRATEGIES = {
    "normalized_player": normalize_player,
    "normalized_finance": normalize_finance,
    "normalized_social": normalize_social,
    "normalized_social_players": normalize_social_players,
}
