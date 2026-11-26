#!/usr/bin/env python3
"""Reconcile one stopped-server sale snapshot, read-only. Never use on live files.

Use separately copied, checkpointed database, bank and lineage from one stop point.
This tool does not prove backups are simultaneous, validate playerdata or authorize
recovery, deletion, installation, payout, or a release. No sensitive rows are printed.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import sys

from audit_reset_snapshot import AuditError, canonical_uuid, credit_rows, nonnegative, open_snapshot, source

SOURCE_NAMES = {'fishing-sale', 'fishing-horizons-sale'}
KINDS = {'COD', 'SALMON', 'TROPICAL_FISH', 'PUFFERFISH'}
INDEXES = {
    'active_sale_owner': "CREATE UNIQUE INDEX active_sale_owner ON sales(owner) WHERE phase <> 'PAID'",
    'active_cook_owner': "CREATE UNIQUE INDEX active_cook_owner ON cooked_fish(owner) WHERE phase <> 'DONE'",
    'active_find_owner': "CREATE UNIQUE INDEX active_find_owner ON find_rewards(owner) WHERE phase <> 'DONE'",
}


def json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise AuditError('duplicate JSON key')
        result[key] = value
    return result


def read_file(path: Path) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise AuditError('snapshot file missing or unsafe')
    return path.read_bytes()


def audit(bank_path: Path, database_path: Path, lineage_path: Path, source_name: str) -> dict:
    if source_name not in SOURCE_NAMES:
        raise AuditError('unsupported sale source')
    bank = json.loads(read_file(bank_path).decode('utf-8'), object_pairs_hook=json_object)
    if not isinstance(bank, dict) or type(bank.get('schema')) is not int or bank['schema'] != 10:
        raise AuditError('unsupported bank format')
    accounts = bank.get('accounts')
    if not isinstance(accounts, dict):
        raise AuditError('missing bank accounts')
    identity = read_file(lineage_path).decode('ascii')
    if len(identity) != 37 or not identity.endswith('\n'):
        raise AuditError('invalid lineage file')
    lineage_matches = canonical_uuid(identity[:-1]) == source(bank, source_name)
    receipts = credit_rows(bank, source_name)
    for owner, amount in receipts.values():
        if owner not in accounts or not isinstance(accounts[owner], dict) or amount < 1 or amount > 1_000_000:
            raise AuditError('invalid sale receipt')

    with closing(open_snapshot(database_path, 1179206483, {4, 5})) as db:
        version = db.execute('pragma user_version').fetchone()[0]
        for name, expected in INDEXES.items():
            row = db.execute("select sql from sqlite_master where type='index' and name=?", (name,)).fetchone()
            if row != (expected,):
                raise AuditError('journal index mismatch')
        sales = {}
        phases = {'PREPARED': 0, 'REMOVED': 0, 'PAID': 0}
        query = 'select id,owner,amount,phase' + (',signed_count,vanilla_count' if version == 5 else '') + ' from sales'
        for row in db.execute(query):
            tx, owner, amount, phase = row[:4]
            tx = canonical_uuid(tx); owner = canonical_uuid(owner); amount = nonnegative(amount)
            if tx in sales or phase not in phases or not 1 <= amount <= 1_000_000:
                raise AuditError('invalid sale row')
            signed, vanilla = (nonnegative(row[4]), nonnegative(row[5])) if version == 5 else (None, 0)
            if (signed is not None and signed > 36) or vanilla > 2304:
                raise AuditError('invalid item counts')
            sales[tx] = (owner, amount, phase, signed, vanilla)
            phases[phase] += 1
        signed_counts = {tx: 0 for tx in sales}
        fish_ids = set()
        for fish_id, tx in db.execute('select fish_id,sale_id from spent_fish'):
            fish_id = canonical_uuid(fish_id); tx = canonical_uuid(tx)
            if fish_id in fish_ids or tx not in sales:
                raise AuditError('duplicate or orphaned fish')
            fish_ids.add(fish_id); signed_counts[tx] += 1
        vanilla_counts = {tx: 0 for tx in sales}
        slots = set()
        if version == 5:
            for tx, slot, kind, quantity in db.execute('select sale_id,slot,kind,quantity from vanilla_fish'):
                tx = canonical_uuid(tx); slot = nonnegative(slot); quantity = nonnegative(quantity)
                if tx not in sales or slot >= 36 or kind not in KINDS or not 1 <= quantity <= 64 or (tx, slot) in slots:
                    raise AuditError('invalid vanilla stack')
                slots.add((tx, slot)); vanilla_counts[tx] += quantity
        for tx, (_, _, _, signed, vanilla) in sales.items():
            count = signed_counts[tx]
            if count > 36 or (version == 5 and (signed != count or vanilla != vanilla_counts[tx])) or count + vanilla_counts[tx] == 0:
                raise AuditError('sale journal missing items')
        if db.execute('select count(*) from cooked_fish c join spent_fish f on f.fish_id=c.fish_id').fetchone()[0]:
            raise AuditError('fish both cooked and sold')
        cooking = dict(db.execute('select phase,count(*) from cooked_fish group by phase'))
        finds = dict(db.execute('select phase,count(*) from find_rewards group by phase'))
        if (set(cooking) | set(finds)) - {'PREPARED', 'DONE'}:
            raise AuditError('invalid fish operation phase')
        mismatches = 0
        for tx, (owner, amount) in receipts.items():
            row = sales.get(tx)
            if row is None or row[:2] != (owner, amount) or row[2] == 'PREPARED':
                mismatches += 1
        for tx, (owner, amount, phase, _, _) in sales.items():
            if phase == 'PAID' and receipts.get(tx) != (owner, amount):
                mismatches += 1
        pending = phases['PREPARED'] + phases['REMOVED'] + cooking.get('PREPARED', 0) + finds.get('PREPARED', 0)
        return {'source': source_name, 'schema': version, 'lineage_matches': lineage_matches,
                'sales': phases, 'cooking': cooking, 'finds': finds,
                'receipt_mismatches': mismatches, 'pending_operations': pending,
                'requires_review': bool(not lineage_matches or mismatches or pending)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bank', type=Path, required=True)
    parser.add_argument('--sale', type=Path, required=True)
    parser.add_argument('--lineage', type=Path, required=True)
    parser.add_argument('--source', choices=sorted(SOURCE_NAMES), required=True)
    args = parser.parse_args()
    try:
        result = audit(args.bank, args.sale, args.lineage, args.source)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print('READ ONLY: partial evidence, not authorization to recover or release.')
        return 2 if result['requires_review'] else 0
    except (AuditError, OSError, ValueError, UnicodeError, sqlite3.Error):
        print('Audit failed: invalid or incomplete snapshot; manual review required.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
