"""Integrity checks for the pack actually delivered and embedded in CustomItems."""
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import warnings
from zipfile import ZipFile

import verify_combined_pack


class CombinedPackTest(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.custom, self.fishing = self.root / 'custom', self.root / 'fishing'
        metadata = json.dumps({'pack': {'min_format': [97, 1], 'max_format': [97, 1]}})
        for root, asset in ((self.custom, 'assets/chrdk_items/model.json'),
                            (self.fishing, 'assets/chrdk_fishing/fish.json')):
            (root / asset).parent.mkdir(parents=True)
            (root / 'pack.mcmeta').write_text(metadata)
            (root / asset).write_bytes(asset.encode())
        self.archive = self.root / 'pack.zip'
        self.jar = self.root / 'plugin.jar'

    def make_zip(self, change=None, extra=()):
        entries = {'pack.mcmeta': (self.custom / 'pack.mcmeta').read_bytes(),
                   'assets/chrdk_items/model.json': b'assets/chrdk_items/model.json',
                   'assets/chrdk_fishing/fish.json': b'assets/chrdk_fishing/fish.json'}
        if change:
            change(entries)
        with warnings.catch_warnings(), ZipFile(self.archive, 'w') as z:
            warnings.simplefilter('ignore', UserWarning)
            for name, value in list(entries.items()) + list(extra):
                z.writestr(name, value)
        with ZipFile(self.jar, 'w') as z:
            z.writestr('custom-items-pack.zip', self.archive.read_bytes())

    def verify(self):
        return verify_combined_pack.verify(self.custom, self.fishing, self.archive, self.jar)

    def test_exact_union_and_embedded_copy(self):
        self.make_zip()
        self.assertEqual(3, self.verify())

    def test_missing_extra_and_modified_are_rejected(self):
        for change in (lambda e: e.pop('assets/chrdk_fishing/fish.json'),
                       lambda e: e.update({'unexpected': b'x'}),
                       lambda e: e.update({'assets/chrdk_items/model.json': b'wrong'})):
            with self.subTest(change=change):
                self.make_zip(change)
                with self.assertRaises(ValueError): self.verify()

    def test_duplicate_or_unsafe_entry_is_rejected(self):
        for name in ('pack.mcmeta', '../escape', '/absolute', 'bad\\name'):
            with self.subTest(name=name):
                self.make_zip(extra=[(name, b'x')])
                with self.assertRaises(ValueError): self.verify()

    def test_source_collision_is_rejected_even_if_contents_match(self):
        self.make_zip()
        conflict = self.fishing / 'assets/chrdk_items/model.json'
        conflict.parent.mkdir(parents=True)
        conflict.write_bytes((self.custom / 'assets/chrdk_items/model.json').read_bytes())
        with self.assertRaisesRegex(ValueError, 'collide'): self.verify()

    def test_source_symlink_or_mismatched_format_is_rejected(self):
        self.make_zip()
        (self.fishing / 'assets/link').symlink_to(self.custom / 'pack.mcmeta')
        with self.assertRaisesRegex(ValueError, 'symlink'): self.verify()
        (self.fishing / 'assets/link').unlink()
        (self.fishing / 'pack.mcmeta').write_text(json.dumps({'pack': {'min_format': [1, 0], 'max_format': [1, 0]}}))
        with self.assertRaisesRegex(ValueError, 'incompatible'): self.verify()

    def test_wrong_missing_or_duplicate_embedded_pack_is_rejected(self):
        self.make_zip()
        for values in ([], [b'wrong'], [self.archive.read_bytes()] * 2):
            with self.subTest(values=len(values)):
                with warnings.catch_warnings(), ZipFile(self.jar, 'w') as z:
                    warnings.simplefilter('ignore', UserWarning)
                    for value in values: z.writestr('custom-items-pack.zip', value)
                with self.assertRaises(ValueError): self.verify()


if __name__ == '__main__':
    unittest.main()
