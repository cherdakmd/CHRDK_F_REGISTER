import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from restore_chrdk_backup import restore, verify


class RestoreBackupTest(unittest.TestCase):
    def make_archive(self, folder: Path, name="snapshot.chrdk.zip"):
        archive = folder / name
        files = {"CHRDK_Core/data.json": b'{"accounts":{}}', "CHRDK_Bank/bank.json": b'{"balance":4}'}
        manifest = {"format": 1, "createdAt": "2026-11-10T12:00:00Z", "reason": "test", "entries": []}
        import hashlib
        for path, raw in files.items():
            manifest["entries"].append({"path": path, "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()})
        with zipfile.ZipFile(archive, "w") as output:
            output.writestr("CHRDK-BACKUP.json", json.dumps(manifest))
            for path, raw in files.items():
                output.writestr(path, raw)
        return archive

    def test_verify_and_offline_restore_preserves_previous_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self.make_archive(root)
            _, files = verify(archive)
            self.assertEqual(2, len(files))
            plugins = root / "plugins"
            current = plugins / "CHRDK_Core/data.json"
            current.parent.mkdir(parents=True)
            current.write_text('{"old":true}')
            restore(archive, plugins, "SERVER_STOPPED")
            self.assertEqual(json.loads(current.read_text()), {"accounts": {}})
            saved = list((plugins / "CHRDK_Core/backups/pre-restore").glob("before-restore-*/CHRDK_Core/data.json"))
            self.assertEqual(1, len(saved))
            self.assertEqual(json.loads(saved[0].read_text()), {"old": True})

    def test_restore_requires_stopped_server_confirmation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self.make_archive(root)
            (root / "plugins").mkdir()
            with self.assertRaisesRegex(ValueError, "SERVER_STOPPED"):
                restore(archive, root / "plugins", "")

    def test_rejects_symlinked_destination_ancestor_without_writing_outside_plugins(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self.make_archive(root)
            plugins = root / "plugins"
            plugins.mkdir()
            outside = root / "outside"
            outside.mkdir()
            sentinel = outside / "data.json"
            sentinel.write_text('{"must":"stay unchanged"}')
            (plugins / "CHRDK_Core").symlink_to(outside, target_is_directory=True)

            with self.assertRaisesRegex(ValueError, "симлинк"):
                restore(archive, plugins, "SERVER_STOPPED")

            self.assertEqual('{"must":"stay unchanged"}', sentinel.read_text())
            self.assertFalse((outside / "backups").exists())

    def test_rejects_traversal_and_wrong_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self.make_archive(root)
            with zipfile.ZipFile(archive) as source, zipfile.ZipFile(root / "bad.zip", "w") as output:
                for item in source.infolist():
                    raw = source.read(item)
                    if item.filename == "CHRDK-BACKUP.json":
                        manifest = json.loads(raw)
                        manifest["entries"][0]["path"] = "CHRDK_Core/../../outside.json"
                        raw = json.dumps(manifest).encode()
                    output.writestr(item, raw)
            with self.assertRaisesRegex(ValueError, "Небезопасный путь"):
                verify(root / "bad.zip")

    def test_rejects_secret_named_ancestor_even_when_file_name_is_generic(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self.make_archive(root)
            with zipfile.ZipFile(archive) as source, zipfile.ZipFile(root / "secret.zip", "w") as output:
                for item in source.infolist():
                    raw = source.read(item)
                    if item.filename == "CHRDK-BACKUP.json":
                        manifest = json.loads(raw)
                        manifest["entries"][0]["path"] = "CHRDK_Core/secrets/data.json"
                        raw = json.dumps(manifest).encode()
                    output.writestr(item, raw)
            with self.assertRaisesRegex(ValueError, "Конфиг/токен"):
                verify(root / "secret.zip")


if __name__ == "__main__":
    unittest.main()
