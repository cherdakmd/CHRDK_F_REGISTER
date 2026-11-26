#!/usr/bin/env python3
"""Read-only reconciliation aid for a stopped server's Fishing snapshot.

Not a reset tool, not proof of a consistent backup, and never an authorization to
change or delete anything. It does not print owners, balances, items or snapshots.
Requires checkpointed databases without SQLite sidecars; never copies, opens or
modifies live server files. Use only local copies taken at the same stop point.
"""
from __future__ import annotations
import argparse
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import sys
import uuid


class AuditError(Exception):
    pass


def open_snapshot(path: Path, app_id: int, versions: set[int]) -> sqlite3.Connection:
    if not path.is_file() or path.is_symlink():
        raise AuditError('database missing or unsafe')
    sidecars = (Path(str(path) + suffix) for suffix in ('-wal', '-shm', '-journal'))
    if any(sidecar.exists() or sidecar.is_symlink() for sidecar in sidecars):
        raise AuditError('database has sidecars; offline reconciliation required')
    conn = sqlite3.connect(path.resolve().as_uri() + '?mode=ro&immutable=1', uri=True)
    try:
        if conn.execute('pragma quick_check').fetchone() != ('ok',):
            raise AuditError('database integrity check failed')
        if conn.execute('pragma application_id').fetchone()[0] != app_id:
            raise AuditError('wrong database application id')
        if conn.execute('pragma user_version').fetchone()[0] not in versions:
            raise AuditError('unsupported database version')
        if conn.execute('pragma foreign_key_check').fetchone() is not None:
            raise AuditError('database foreign key violation')
        return conn
    except Exception:
        conn.close()
        raise


def canonical_uuid(value: object) -> str:
    if not isinstance(value, str):
        raise AuditError('invalid UUID')
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError()
    except ValueError as e:
        raise AuditError('invalid UUID') from e
    return value


def nonnegative(value: object) -> int:
    if type(value) is not int or value < 0:
        raise AuditError('invalid amount or cursor')
    return value


def source(bank: dict, name: str) -> str:
    sources = bank.get('entitlementSources')
    if not isinstance(sources, dict):
        raise AuditError('invalid bank source table')
    return canonical_uuid(sources.get(name))


def charge_summary(bank: dict, db: sqlite3.Connection, name: str) -> dict:
    meta = db.execute('select lineage,cursor from metadata where id=1').fetchall()
    if len(meta) != 1 or canonical_uuid(meta[0][0]) != source(bank, name):
        raise AuditError('charge lineage mismatch')
    cursor = nonnegative(meta[0][1])
    records = bank.get('chargeSettlements')
    if not isinstance(records, dict):
        raise AuditError('invalid bank charge history')
    remote = records.get(name)
    if remote is None:
        seq = 0
    else:
        if not isinstance(remote, dict) or remote.get('source') != name or remote.get('lineage') != meta[0][0]:
            raise AuditError('invalid bank charge receipt')
        seq = nonnegative(remote.get('sequence'))
        canonical_uuid(remote.get('transaction')); canonical_uuid(remote.get('payer'))
        if seq < 1:
            raise AuditError('invalid bank charge sequence')
    pending = db.execute('select transaction_id,owner,sequence,phase,amount from pending').fetchall()
    if len(pending) > 1:
        raise AuditError('multiple charge intents')
    state = 'none'
    if pending:
        tx, owner, step, phase, amount = pending[0]
        canonical_uuid(tx); canonical_uuid(owner); nonnegative(amount)
        if step != cursor + 1 or phase not in ('PREPARED', 'PAID') or amount < 1:
            raise AuditError('invalid charge intent')
        if seq == cursor and phase == 'PREPARED':
            state = 'prepared_without_receipt'
        elif seq == step and remote and remote.get('transaction') == tx and remote.get('payer') == owner and remote.get('price') == amount:
            # Exact payment equality also includes hashes and other fields; leave it for native reconciliation.
            state = 'possible_receipt_requires_full_validation'
        else:
            state = 'conflict'
    elif seq != cursor:
        state = 'cursor_mismatch'
    result = {'local_cursor': cursor, 'bank_cursor': seq, 'pending': state}
    if name == 'fishing-bait':
        equipped = dict(db.execute('select phase,count(*) from equipped group by phase'))
        if set(equipped) - {'PREPARED', 'ARMED', 'RESERVED'}:
            raise AuditError('invalid bait phase')
        result['equipped_phases'] = equipped
    return result


def credit_rows(bank: dict, name: str) -> dict[str, tuple[str, int]]:
    source(bank, name)
    credits = bank.get('entitlementCredits')
    if not isinstance(credits, dict):
        raise AuditError('invalid bank entitlement table')
    out = {}
    prefix = name + ':'
    for key, value in credits.items():
        if not isinstance(key, str):
            raise AuditError('invalid receipt key')
        if not key.startswith(prefix):
            continue
        suffix = canonical_uuid(key[len(prefix):])
        if not isinstance(value, dict):
            raise AuditError('invalid bank receipt')
        out[suffix] = (canonical_uuid(value.get('owner')), nonnegative(value.get('credited')))
    return out


def sale_summary(bank: dict, db: sqlite3.Connection, lineage: str | None) -> dict:
    bank_lineage = source(bank, 'fishing-sale')
    match = None if lineage is None else canonical_uuid(lineage) == bank_lineage
    rows = db.execute('select id,owner,amount,phase from sales').fetchall()
    sales = {}
    phases = {'PREPARED': 0, 'REMOVED': 0, 'PAID': 0}
    for tx, owner, amount, phase in rows:
        tx = canonical_uuid(tx); owner = canonical_uuid(owner); amount = nonnegative(amount)
        if phase not in phases or tx in sales or not 1 <= amount <= 1_000_000:
            raise AuditError('invalid sale record')
        sales[tx] = (owner, amount, phase); phases[phase] += 1
    if db.execute('select count(*) from sales s where not exists (select 1 from spent_fish f where f.sale_id=s.id)').fetchone()[0]:
        raise AuditError('sale lacks fish identities')
    if db.execute('select count(*) from cooked_fish c join spent_fish f on f.fish_id=c.fish_id').fetchone()[0]:
        raise AuditError('fish both cooked and sold')
    receipts = credit_rows(bank, 'fishing-sale')
    mismatch = 0
    for tx, (owner, value) in receipts.items():
        row = sales.get(tx)
        if row is None or row[:2] != (owner, value) or row[2] == 'PREPARED':
            mismatch += 1
    for tx, (owner, value, phase) in sales.items():
        if phase == 'PAID' and receipts.get(tx) != (owner, value):
            mismatch += 1
    cooking = dict(db.execute('select phase,count(*) from cooked_fish group by phase'))
    finds = dict(db.execute('select phase,count(*) from find_rewards group by phase'))
    if (set(cooking) | set(finds)) - {'PREPARED', 'DONE'}:
        raise AuditError('invalid fish operation phase')
    return {'phases': phases, 'cooking': cooking, 'finds': finds, 'receipt_mismatches': mismatch,
            'sale_lineage_matches': match}


def reward_summary(bank: dict, db: sqlite3.Connection) -> dict:
    meta = db.execute('select lineage from metadata where id=1').fetchall()
    if len(meta) != 1 or canonical_uuid(meta[0][0]) != source(bank, 'fishing-album'):
        raise AuditError('album reward lineage mismatch')
    rows = db.execute('select owner,paid_stage,target_stage,confirmed,target from rewards').fetchall()
    rewards = {}
    pending = 0
    for owner, paid, target_stage, confirmed, target in rows:
        canonical_uuid(owner)
        for number in (paid, target_stage, confirmed, target): nonnegative(number)
        if not (paid <= target_stage <= 4 and confirmed <= target <= 1_000_000) or ((paid == target_stage) != (confirmed == target)):
            raise AuditError('invalid reward progress')
        if owner in rewards: raise AuditError('duplicate reward owner')
        rewards[owner] = (confirmed, target); pending += paid != target_stage
    receipts = credit_rows(bank, 'fishing-album')
    mismatch = 0
    for key, (owner, value) in receipts.items():
        if key != owner or owner not in rewards or not rewards[owner][0] <= value <= rewards[owner][1]: mismatch += 1
    for owner, (confirmed, target) in rewards.items():
        amount = receipts.get(owner, (owner, 0))[1]
        if not confirmed <= amount <= target: mismatch += 1
    return {'owners': len(rewards), 'pending': pending, 'receipt_mismatches': mismatch}


def audit(bank_path: Path, rod_path: Path, bait_path: Path, sale_path: Path, rewards_path: Path,
          sale_lineage_path: Path | None = None) -> dict:
    if not bank_path.is_file() or bank_path.is_symlink(): raise AuditError('bank snapshot missing or unsafe')
    bank = json.loads(bank_path.read_text(encoding='utf-8'))
    if not isinstance(bank, dict) or bank.get('schema') != 10: raise AuditError('invalid or unsupported bank snapshot')
    lineage = None
    if sale_lineage_path is not None:
        if not sale_lineage_path.is_file() or sale_lineage_path.is_symlink(): raise AuditError('sale lineage file invalid')
        raw = sale_lineage_path.read_text(encoding='ascii')
        if len(raw) != 37 or not raw.endswith('\n'): raise AuditError('sale lineage file invalid')
        lineage = raw[:-1]
    with closing(open_snapshot(rod_path, 1380930634, {1})) as rod, \
         closing(open_snapshot(bait_path, 1111585859, {2})) as bait, \
         closing(open_snapshot(sale_path, 1179206483, {4})) as sales, \
         closing(open_snapshot(rewards_path, 1178686034, {1})) as rewards:
        return {'rod': charge_summary(bank, rod, 'fishing-upgrades'),
                'bait': charge_summary(bank, bait, 'fishing-bait'),
                'sale': sale_summary(bank, sales, lineage),
                'album_rewards': reward_summary(bank, rewards)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('bank', 'rod', 'bait', 'sale', 'rewards'):
        parser.add_argument('--' + arg, type=Path, required=True)
    parser.add_argument('--sale-lineage', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(audit(args.bank, args.rod, args.bait, args.sale, args.rewards, args.sale_lineage), ensure_ascii=False, indent=2))
        print('READ ONLY: incomplete evidence; this output does not authorize a reset or prove backup consistency.')
        return 0
    except (AuditError, OSError, ValueError, sqlite3.Error):
        # Do not leak account or snapshot contents through exception messages.
        print('Audit failed: invalid, incomplete or inconsistent snapshot; manual review required.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
