import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid

TOOLS = Path(__file__).resolve().parents[1] / 'tools'
sys.path.insert(0, str(TOOLS))
import audit_sale_snapshot as audit


def uid():
    return str(uuid.uuid4())


class SaleAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.owner, self.lineage, self.transaction, self.fish = uid(), uid(), uid(), uid()
        self.source = 'fishing-horizons-sale'
        self.bank_file = self.root / 'bank.json'; self.db_file = self.root / 'sale.db'; self.lineage_file = self.root / 'sale-lineage.txt'
        self.bank = {'schema': 10, 'accounts': {self.owner: {}},
                     'entitlementSources': {self.source: self.lineage},
                     'entitlementCredits': {self.source + ':' + self.transaction: {'owner': self.owner, 'credited': 130}}}
        self.save_bank(); self.lineage_file.write_text(self.lineage + '\n')
        with sqlite3.connect(self.db_file) as db:
            db.execute('pragma application_id=1179206483'); db.execute('pragma user_version=5')
            db.execute('create table sales(id text primary key,owner text,amount integer,phase text,signed_count integer,vanilla_count integer)')
            db.execute('create table spent_fish(fish_id text primary key,sale_id text references sales(id))')
            db.execute('create table vanilla_fish(sale_id text references sales(id),slot integer,kind text,quantity integer,primary key(sale_id,slot))')
            db.execute('create table cooked_fish(fish_id text,owner text,phase text)')
            db.execute('create table find_rewards(fish_id text,owner text,phase text)')
            for statement in audit.INDEXES.values(): db.execute(statement)
            db.execute("insert into sales values(?,?,130,'PAID',1,3)", (self.transaction, self.owner))
            db.execute('insert into spent_fish values(?,?)', (self.fish, self.transaction))
            db.execute("insert into vanilla_fish values(?,3,'COD',3)", (self.transaction,))

    def save_bank(self): self.bank_file.write_text(json.dumps(self.bank))
    def run_audit(self): return audit.audit(self.bank_file, self.db_file, self.lineage_file, self.source)
    def hash_inputs(self): return [hashlib.sha256(path.read_bytes()).hexdigest() for path in (self.bank_file, self.db_file, self.lineage_file)]

    def test_v5_mixed_and_vanilla_only_snapshot_is_read_only(self):
        tx = uid()
        with sqlite3.connect(self.db_file) as db:
            db.execute("insert into sales values(?,?,20,'PAID',0,2)", (tx, self.owner))
            db.execute("insert into vanilla_fish values(?,4,'SALMON',2)", (tx,))
        self.bank['entitlementCredits'][self.source + ':' + tx] = {'owner': self.owner, 'credited': 20}; self.save_bank()
        before = self.hash_inputs(); result = self.run_audit()
        self.assertEqual(before, self.hash_inputs())
        self.assertEqual(5, result['schema']); self.assertEqual(2, result['sales']['PAID'])
        self.assertEqual(0, result['receipt_mismatches']); self.assertFalse(result['requires_review'])
        self.assertNotIn(self.owner, json.dumps(result)); self.assertNotIn(self.transaction, json.dumps(result))

    def test_v4_is_supported_without_modification(self):
        with sqlite3.connect(self.db_file) as db:
            db.execute('drop table vanilla_fish'); db.execute('alter table sales drop column signed_count')
            db.execute('alter table sales drop column vanilla_count'); db.execute('pragma user_version=4')
        before = self.hash_inputs(); result = self.run_audit()
        self.assertEqual(4, result['schema']); self.assertFalse(result['requires_review']); self.assertEqual(before, self.hash_inputs())

    def test_missing_signed_or_vanilla_records_fail_closed(self):
        with sqlite3.connect(self.db_file) as db: db.execute('delete from spent_fish')
        with self.assertRaises(audit.AuditError): self.run_audit()
        with sqlite3.connect(self.db_file) as db:
            db.execute('insert into spent_fish values(?,?)', (self.fish, self.transaction)); db.execute('delete from vanilla_fish')
        with self.assertRaises(audit.AuditError): self.run_audit()

    def test_old_journal_missing_receipt_and_wrong_lineage_require_review(self):
        self.bank['entitlementCredits'][self.source + ':' + uid()] = {'owner': self.owner, 'credited': 10}; self.save_bank()
        self.assertTrue(self.run_audit()['requires_review']); self.assertEqual(1, self.run_audit()['receipt_mismatches'])
        self.bank['entitlementCredits'].clear(); self.save_bank()
        self.assertGreater(self.run_audit()['receipt_mismatches'], 0)
        self.lineage_file.write_text(uid() + '\n'); self.assertFalse(self.run_audit()['lineage_matches'])

    def test_pending_or_early_receipt_is_not_reported_as_clean(self):
        with sqlite3.connect(self.db_file) as db:
            db.execute("update sales set phase='PREPARED' where id=?", (self.transaction,))
        result = self.run_audit()
        self.assertTrue(result['requires_review']); self.assertEqual(1, result['pending_operations']); self.assertEqual(1, result['receipt_mismatches'])
        cmd = [sys.executable, str(TOOLS / 'audit_sale_snapshot.py'), '--bank', str(self.bank_file), '--sale', str(self.db_file),
               '--lineage', str(self.lineage_file), '--source', self.source]
        run = subprocess.run(cmd, text=True, capture_output=True, check=False)
        self.assertEqual(2, run.returncode); self.assertNotIn(self.owner, run.stdout + run.stderr)
        self.assertNotIn(self.transaction, run.stdout + run.stderr)

    def test_vanilla_only_amount_must_equal_recorded_quantity_even_if_bank_agrees(self):
        with sqlite3.connect(self.db_file) as db:
            db.execute('delete from spent_fish')
            db.execute('update sales set signed_count=0,amount=30 where id=?', (self.transaction,))
        key = self.source + ':' + self.transaction
        self.bank['entitlementCredits'][key]['credited'] = 30; self.save_bank()
        self.assertFalse(self.run_audit()['requires_review'])
        for bad in (29, 31):
            with self.subTest(amount=bad):
                with sqlite3.connect(self.db_file) as db:
                    db.execute('update sales set amount=? where id=?', (bad, self.transaction))
                self.bank['entitlementCredits'][key]['credited'] = bad; self.save_bank()
                with self.assertRaises(audit.AuditError): self.run_audit()

    def test_mixed_amount_must_cover_vanilla_and_minimum_signed_price(self):
        key = self.source + ':' + self.transaction
        # Three vanilla fish plus one signed token priced at its minimum.
        with sqlite3.connect(self.db_file) as db:
            db.execute('update sales set amount=31 where id=?', (self.transaction,))
        self.bank['entitlementCredits'][key]['credited'] = 31; self.save_bank()
        self.assertFalse(self.run_audit()['requires_review'])
        with sqlite3.connect(self.db_file) as db:
            db.execute('update sales set amount=30 where id=?', (self.transaction,))
        self.bank['entitlementCredits'][key]['credited'] = 30; self.save_bank()
        with self.assertRaises(audit.AuditError): self.run_audit()
        before = self.hash_inputs()
        cmd = [sys.executable, str(TOOLS / 'audit_sale_snapshot.py'), '--bank', str(self.bank_file), '--sale', str(self.db_file),
               '--lineage', str(self.lineage_file), '--source', self.source]
        run = subprocess.run(cmd, text=True, capture_output=True, check=False)
        self.assertEqual(1, run.returncode)
        self.assertNotIn(self.owner, run.stdout + run.stderr)
        self.assertNotIn(self.transaction, run.stdout + run.stderr)
        self.assertEqual(before, self.hash_inputs())

    def test_more_than_36_signed_items_and_vanilla_stacks_is_impossible(self):
        with sqlite3.connect(self.db_file) as db:
            for _ in range(33):
                db.execute('insert into spent_fish values(?,?)', (uid(), self.transaction))
            db.execute('update sales set signed_count=34,vanilla_count=4 where id=?', (self.transaction,))
            db.execute("insert into vanilla_fish values(?,4,'SALMON',1)", (self.transaction,))
        self.assertFalse(self.run_audit()['requires_review'])  # 34 fish + 2 stacks
        with sqlite3.connect(self.db_file) as db:
            db.execute('insert into spent_fish values(?,?)', (uid(), self.transaction))
            db.execute('update sales set signed_count=35 where id=?', (self.transaction,))
        with self.assertRaises(audit.AuditError): self.run_audit()

    def test_index_tampering_sidecars_and_wrong_source_are_rejected(self):
        with self.assertRaises(audit.AuditError): audit.audit(self.bank_file, self.db_file, self.lineage_file, 'fishing-sale')
        sidecar = Path(str(self.db_file) + '-wal'); sidecar.symlink_to(self.root / 'missing-target')
        with self.assertRaises(audit.AuditError): self.run_audit()
        sidecar.unlink()
        with sqlite3.connect(self.db_file) as db:
            db.execute('drop index active_sale_owner'); db.execute('create index active_sale_owner on sales(owner)')
        with self.assertRaises(audit.AuditError): self.run_audit()

    def test_duplicates_missing_account_and_invalid_stacks_are_rejected(self):
        self.bank_file.write_text('{"schema":10,"schema":10}')
        with self.assertRaises(audit.AuditError): self.run_audit()
        self.save_bank(); self.bank['accounts'].clear(); self.save_bank()
        with self.assertRaises(audit.AuditError): self.run_audit()
        self.bank['accounts'][self.owner] = {}; self.save_bank()
        with sqlite3.connect(self.db_file) as db: db.execute("update vanilla_fish set kind='COOKED_COD'")
        with self.assertRaises(audit.AuditError): self.run_audit()


if __name__ == '__main__': unittest.main()
