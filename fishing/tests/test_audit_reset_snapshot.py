import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid

script = Path(__file__).resolve().parents[1] / 'tools' / 'audit_reset_snapshot.py'
spec = importlib.util.spec_from_file_location('audit_reset_snapshot', script)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def uid():
    return str(uuid.uuid4())


class ResetSnapshotAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)
        self.owner, self.sale_id = uid(), uid()
        self.sources = {name: uid() for name in ('fishing-upgrades', 'fishing-bait', 'fishing-sale', 'fishing-album')}
        self.bank = {'schema': 10, 'entitlementSources': self.sources, 'chargeSettlements': {},
                     'entitlementCredits': {
                         'fishing-sale:' + self.sale_id: {'owner': self.owner, 'credited': 100},
                         'fishing-album:' + self.owner: {'owner': self.owner, 'credited': 50}}}
        self.save_bank()
        self.rod = self.db('rod.db', 1380930634, 1, [
            'create table metadata(id integer, lineage text, cursor integer)',
            'create table pending(transaction_id text, owner text, sequence integer, phase text, amount integer)'])
        self.bait = self.db('bait.db', 1111585859, 2, [
            'create table metadata(id integer, lineage text, cursor integer)',
            'create table pending(transaction_id text, owner text, sequence integer, phase text, amount integer)',
            'create table equipped(phase text)'])
        self.sale = self.db('sale.db', 1179206483, 4, [
            'create table sales(id text, owner text, amount integer, phase text)',
            'create table spent_fish(fish_id text,sale_id text)',
            'create table cooked_fish(fish_id text,phase text)', 'create table find_rewards(phase text)'])
        self.rewards = self.db('reward.db', 1178686034, 1, [
            'create table metadata(id integer,lineage text)',
            'create table rewards(owner text,paid_stage integer,target_stage integer,confirmed integer,target integer)'])
        with sqlite3.connect(self.rod) as c: c.execute('insert into metadata values(1,?,0)', (self.sources['fishing-upgrades'],))
        with sqlite3.connect(self.bait) as c: c.execute('insert into metadata values(1,?,0)', (self.sources['fishing-bait'],))
        with sqlite3.connect(self.sale) as c:
            c.execute('insert into sales values(?,?,100,\'PAID\')', (self.sale_id,self.owner))
            c.execute('insert into spent_fish values(?,?)', (uid(),self.sale_id))
        with sqlite3.connect(self.rewards) as c:
            c.execute('insert into metadata values(1,?)', (self.sources['fishing-album'],))
            c.execute('insert into rewards values(?,1,1,50,50)', (self.owner,))
        self.lineage = self.path / 'sale-lineage.txt'; self.lineage.write_text(self.sources['fishing-sale']+'\n')

    def db(self, name, app, version, ddl):
        path = self.path/name
        with sqlite3.connect(path) as c:
            c.execute('pragma application_id='+str(app)); c.execute('pragma user_version='+str(version))
            for statement in ddl: c.execute(statement)
        return path

    def save_bank(self):
        self.bank_path = self.path / 'bank.json'; self.bank_path.write_text(json.dumps(self.bank))

    def run_audit(self, lineage=True):
        return module.audit(self.bank_path, self.rod, self.bait, self.sale, self.rewards, self.lineage if lineage else None)

    def test_consistent_snapshot_reports_no_pending_and_changes_nothing(self):
        inputs = [self.bank_path, self.rod, self.bait, self.sale, self.rewards, self.lineage]
        hashes = [hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs]
        result = self.run_audit()
        self.assertEqual('none', result['rod']['pending']); self.assertEqual('none', result['bait']['pending'])
        self.assertTrue(result['sale']['sale_lineage_matches']); self.assertEqual(0, result['sale']['receipt_mismatches'])
        self.assertEqual(0, result['album_rewards']['pending']); self.assertEqual(0, result['album_rewards']['receipt_mismatches'])
        self.assertEqual(hashes, [hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs])
        self.assertIsNone(self.run_audit(False)['sale']['sale_lineage_matches'])

    def test_uncancelled_missing_charge_is_never_reported_as_completed(self):
        with sqlite3.connect(self.rod) as c:
            c.execute('insert into pending values(?,?,1,\'PREPARED\',100)', (uid(), self.owner))
        self.assertEqual('prepared_without_receipt', self.run_audit()['rod']['pending'])

    def test_paid_or_conflicting_charge_requires_manual_validation(self):
        tx = uid()
        with sqlite3.connect(self.rod) as c:
            c.execute('insert into pending values(?,?,1,\'PREPARED\',100)', (tx, self.owner))
        self.bank['chargeSettlements']['fishing-upgrades'] = {'source':'fishing-upgrades', 'lineage':self.sources['fishing-upgrades'],
                'sequence':1, 'transaction':tx, 'payer':self.owner, 'price':100}
        self.save_bank()
        self.assertEqual('possible_receipt_requires_full_validation', self.run_audit()['rod']['pending'])
        self.bank['chargeSettlements']['fishing-upgrades']['payer'] = uid(); self.save_bank()
        self.assertEqual('conflict', self.run_audit()['rod']['pending'])

    def test_pending_operations_and_receipt_divergence_are_reported(self):
        with sqlite3.connect(self.sale) as c:
            c.execute('insert into cooked_fish values(?,\'PREPARED\')', (uid(),)); c.execute('insert into find_rewards values(\'PREPARED\')')
        with sqlite3.connect(self.rewards) as c:
            c.execute('update rewards set target_stage=2,target=150')
        self.bank['entitlementCredits']['fishing-sale:'+self.sale_id]['credited'] = 101; self.save_bank()
        result = self.run_audit()
        self.assertEqual(1,result['sale']['cooking']['PREPARED']); self.assertEqual(1,result['sale']['finds']['PREPARED'])
        self.assertGreater(result['sale']['receipt_mismatches'],0); self.assertEqual(1,result['album_rewards']['pending'])

    def test_tampered_wrong_lineage_wal_and_database_type_fail_closed(self):
        self.bank['entitlementSources']['fishing-album'] = uid(); self.save_bank()
        with self.assertRaises(module.AuditError): self.run_audit()
        self.bank['entitlementSources']['fishing-album'] = self.sources['fishing-album']; self.save_bank()
        sidecar = Path(str(self.rod)+'-wal'); sidecar.write_bytes(b'x')
        with self.assertRaises(module.AuditError): self.run_audit()
        sidecar.unlink()
        with sqlite3.connect(self.rod) as c: c.execute('pragma application_id=123')
        with self.assertRaises(module.AuditError): self.run_audit()

    def test_lost_sale_fish_identity_and_cook_collision_fail_closed(self):
        with sqlite3.connect(self.sale) as c: c.execute('delete from spent_fish')
        with self.assertRaises(module.AuditError): self.run_audit()
        fish = uid()
        with sqlite3.connect(self.sale) as c:
            c.execute('insert into spent_fish values(?,?)', (fish, self.sale_id))
            c.execute('insert into cooked_fish values(?,\'DONE\')', (fish,))
        with self.assertRaises(module.AuditError): self.run_audit()

    def test_reward_receipt_from_other_owner_is_flagged(self):
        key='fishing-album:'+self.owner
        self.bank['entitlementCredits'][key]['owner']=uid(); self.save_bank()
        self.assertGreater(self.run_audit()['album_rewards']['receipt_mismatches'],0)


if __name__ == '__main__': unittest.main()
