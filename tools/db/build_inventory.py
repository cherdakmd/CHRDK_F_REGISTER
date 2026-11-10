#!/usr/bin/env python3
"""Generate the machine-readable CHRDK runtime storage inventory.

Only stdlib is required, so the CI contract can run before dependency setup.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "src/main/resources/storage-inventory.json"

# module, path, target, key_kind, schema, secret_class, checksum_policy, migration
ROWS: list[tuple[str, str, str, str, int, str, str, str]] = [
    ("core", "data.json", "chrdk_player/chrdk_account/chrdk_entitlement/chrdk_purchase", "canonical_name", 4, "none", "sha256+integrity-sidecar", "normalized_player"),
    ("core", "bank.json", "chrdk_bank_account/chrdk_bank_receipt", "uuid", 8, "none", "sha256", "normalized_finance"),
    ("core", "bank-charge.json", "chrdk_charge_journal", "source+lineage", 1, "none", "force+atomic", "normalized_finance"),
    ("core", "milestone-awards.json", "chrdk_milestone_source/chrdk_milestone_award", "source+uuid", 1, "none", "force+atomic", "normalized_finance"),
    ("core", "paid-actions.json", "chrdk_paid_action_*", "uuid+source", 1, "none", "force+atomic", "normalized_finance"),
    ("core", "boosty-bindings.json", "chrdk_external_binding", "external_id", 1, "none", "sha256", "normalized_player"),
    ("core", "boosty-tokens.json", "env/secret-config", "none", 1, "service_credentials", "exclude", "exclude"),
    ("core", "vk-second-factor.json", "chrdk_auth_reset", "player", 1, "login", "exclude", "auth_reset"),
    ("core", "moderation.json", "chrdk_moderation_global", "global", 1, "none", "canonical-json-sha256", "normalized_moderation"),
    ("core", "players/warns.yml", "chrdk_sanction", "player", 1, "none", "yaml-manual", "normalized_moderation"),
    ("core", "players/bans.yml", "chrdk_sanction", "player", 1, "none", "yaml-manual", "normalized_moderation"),
    ("core", "labels.json", "chrdk_document(core,labels)", "global", 1, "none", "sha256", "db_document"),
    ("core", "honors.json", "chrdk_document(core,honors)", "global", 1, "none", "sha256", "db_document"),
    ("core", "guide-data.json", "chrdk_document(core,guide)", "global", 1, "none", "sha256", "db_document"),
    ("core", "spawn-data.json", "chrdk_document(core,spawn)", "global", 1, "none", "sha256", "db_document"),
    ("core", "starter-kits.json", "chrdk_document(core,starter-kits)", "uuid", 1, "none", "sha256", "db_document"),
    ("core", "manifest.json", "chrdk_meta", "global", 1, "none", "sha256", "db_document"),
    ("achievements", "achievements.json", "chrdk_document(achievements,global)", "uuid", 1, "none", "sha256", "db_document"),
    ("achievements", "exploration.json", "chrdk_exploration", "uuid", 1, "none", "force+atomic", "normalized_exploration"),
    ("artifacts", "collection.json", "chrdk_document(artifacts,collection)", "uuid", 1, "none", "sha256", "db_document"),
    ("artifacts", "cooldowns.json", "chrdk_document(artifacts,cooldowns)", "uuid", 1, "none", "sha256", "db_document"),
    ("auction", "market.json", "chrdk_document(auction,market)+chrdk_auction_*", "uuid", 1, "none", "force+atomic", "normalized_finance"),
    ("battlepass", "battlepass-data.json", "chrdk_document(battlepass,global)", "uuid", 1, "none", "force+atomic", "db_document"),
    ("battlepass", "battlepass-money.json", "chrdk_income_source/chrdk_entitlement_credit", "source", 1, "none", "force+atomic", "normalized_finance"),
    ("battlepass", "exploration.json", "chrdk_exploration", "uuid", 1, "none", "force+atomic", "normalized_exploration"),
    ("claims", "claims-data.json", "chrdk_document(claims,global)+chrdk_financial_pending", "mixed", 1, "none", "force+atomic", "normalized_finance"),
    ("classes", "classes.json", "chrdk_document(classes,global)", "uuid", 1, "none", "force+atomic", "db_document"),
    ("classes", "classes-legacy-rules.json", "chrdk_document(classes,legacy-rules)", "global", 1, "none", "sha256", "db_document"),
    ("classes", "perk-trees.json", "chrdk_document(classes,perk-trees)", "global", 1, "none", "sha256", "db_document"),
    ("common-goals", "goals-data.json", "chrdk_document(common-goals,global)", "global", 1, "none", "force+atomic", "db_document"),
    ("cosmetics", "cosmetics-data.json", "chrdk_document(cosmetics,global)", "uuid", 1, "none", "sha256", "db_document"),
    ("daily-rewards", "daily-data.json", "chrdk_document(daily-rewards,global)", "uuid", 1, "none", "force+atomic", "db_document"),
    ("diamond-shop", "shop-data.json", "chrdk_document(diamond-shop,global)+chrdk_shop_exchange", "mixed", 1, "none", "force+atomic", "normalized_finance"),
    ("diamond-shop", "promotions-data.json", "chrdk_document(diamond-shop,promotions)", "uuid", 1, "none", "sha256", "db_document"),
    ("diamond-shop", "shop-overrides.json", "chrdk_document(diamond-shop,overrides)", "global", 1, "none", "sha256", "db_document"),
    ("disasters", "disasters-progress.json", "chrdk_document(disasters,global)", "uuid", 1, "none", "sha256", "db_document"),
    ("guilds", "guilds.json", "chrdk_document(guilds,global)+chrdk_personal_debt", "clan+uuid", 1, "none", "force+atomic", "normalized_finance"),
    ("guilds", "clan-styles.json", "chrdk_document(guilds,styles)", "clan", 1, "none", "sha256", "db_document"),
    ("hunt", "hunt.json", "chrdk_document(hunt,global)", "uuid", 1, "none", "force+atomic", "db_document"),
    ("hunt", "full-moon.json", "chrdk_document(hunt,full-moon)", "global", 1, "none", "force+atomic", "db_document"),
    ("orders", "orders.json", "chrdk_document(orders,global)", "uuid", 1, "none", "force+atomic", "db_document"),
    ("pets", "pets.json", "chrdk_document(pets,global)", "uuid", 1, "none", "sha256", "db_document"),
    ("professions", "professions.json", "chrdk_document(professions,global)", "uuid", 1, "none", "force+atomic", "db_document"),
    ("quests", "quests.json", "chrdk_document(quests,global)", "uuid", 1, "none", "sha256", "db_document"),
    ("ranks", "ranks-data.json", "chrdk_document(ranks,global)", "uuid", 1, "none", "sha256", "db_document"),
    ("skills", "skills.json", "chrdk_document(skills,global)", "uuid", 1, "none", "force+atomic", "db_document"),
    ("skills", "abilities.json", "chrdk_document(skills,abilities)", "uuid", 1, "none", "sha256", "db_document"),
    ("skills", "perk-trees.json", "chrdk_document(skills,perk-trees)", "global", 1, "none", "sha256", "db_document"),
    ("teleports", "teleports-data.json", "chrdk_document(teleports,global)", "uuid", 1, "none", "sha256", "db_document"),
    ("tier-forge", "stats.yml", "chrdk_document(tier-forge,global)+chrdk_milestone_source", "uuid", 1, "none", "force+atomic", "normalized_finance"),
    ("vk", "vk-links.json", "chrdk_vk_legacy_link/chrdk_vk_chat_state", "canonical_name+vk_id", 2, "none", "sha256", "normalized_social"),
    ("vk", "social-links.json", "chrdk_social_global/chrdk_social_chat", "global", 2, "none", "canonical-json-sha256", "normalized_social"),
    ("vk", "players/social.yml", "chrdk_social_link/chrdk_social_player", "canonical_name+profile", 1, "none", "yaml-manual", "normalized_social"),
    ("vk", "daily-cards.json", "chrdk_social_daily_card", "global", 1, "none", "sha256", "db_document"),
    ("vk", "wall-digest.json", "chrdk_social_wall_digest", "global", 1, "none", "sha256", "db_document"),
]

EXCLUDED = {
    "exact": ["config.yml", "messages.yml", "plugin.yml", "config-schema.yml", "database.yml", "README.md"],
    "prefix": ["assets/", "logs/", "work-journal/", "src/", "update/", "update-backups/", "paid-audit/", "catalog-legacy-", "config-before-"],
    "suffix": [".jsonl", ".md", ".png", ".jpg", ".jpeg", ".ttf", ".zip", ".jar", ".log", ".csv", ".tmp", ".bak", ".broken"],
    "comment": "Backups, .tmp, .bak and .broken siblings of runtime files are DB-backed and are discovered by runtime_globs.",
}
RUNTIME_GLOBS = [
    "*.json", "*.yml", "players/*.yml", "backups/*.json", "backups/*.yml",
    "*.json.tmp", "*.yml.tmp", "*.json.bak", "*.yml.bak", "*.json.broken", "*.yml.broken",
    "*.json.before-*", "*.yml.before-*", "*.json.sha256", "*.yml.sha256",
]
SECRET_EXCLUSIONS = [
    "passwordHash", "totpSecret", "totpEnabledAt", "lastTotpStep", "sessions",
    "vk-second-factor.json", "boosty-tokens.json",
]


def plugin_names() -> dict[str, str]:
    names: dict[str, str] = {}
    for path in sorted(ROOT.glob("*/src/main/resources/plugin.yml")) + [ROOT / "src/main/resources/plugin.yml"]:
        module = "core" if path.parents[3] == ROOT else path.parents[3].name
        text = path.read_text(encoding="utf-8")
        match = re.search(r"^name:\s*['\"]?([A-Za-z0-9_]+)['\"]?\s*$", text, re.MULTILINE)
        if not match:
            raise SystemExit(f"Cannot read plugin name from {path}")
        names[module] = match.group(1)
    return names


def build() -> dict:
    names = plugin_names()
    files = []
    for module, path, target, key_kind, schema, secret_class, checksum_policy, migration in ROWS:
        plugin = names[module]
        files.append({
            "module": module,
            "plugin": plugin,
            "owner": plugin,
            "path": path,
            "source_path": f"plugins/{plugin}/{path}",
            "target": target,
            "key_kind": key_kind,
            "schema": schema,
            "secret_class": secret_class,
            "secret": secret_class != "none",
            "checksum_policy": checksum_policy,
            "migration": migration,
            "strategy": migration,
            "optional": False,
        })
    return {
        "schema": 1,
        "generated_by": "tools/db/build_inventory.py",
        "api_version": 42,
        "storage_api_version": 1,
        "description": "Runtime JSON/YAML state files of CHRDK; config, messages, resources and append-only logs stay on the default filesystem.",
        "database_file_table": "chrdk_storage_file",
        "excluded": EXCLUDED,
        "secret_exclusions": SECRET_EXCLUSIONS,
        "runtime_globs": RUNTIME_GLOBS,
        "files": files,
    }


def render(data: dict) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail when the committed inventory is stale")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    expected = render(build())
    path = args.output if args.output.is_absolute() else ROOT / args.output
    if args.check:
        actual = path.read_text(encoding="utf-8") if path.exists() else ""
        if actual != expected:
            print(f"storage-inventory.json is stale: {path}", file=sys.stderr)
            return 1
        print(f"storage-inventory.json is current: {len(build()['files'])} entries")
        return 0
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(expected, encoding="utf-8")
    print(f"wrote {path}: {len(build()['files'])} entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
