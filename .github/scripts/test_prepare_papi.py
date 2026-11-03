import contextlib
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
import zipfile
import urllib.error
import prepare_papi as target


def artifact():
    out=io.BytesIO()
    with zipfile.ZipFile(out,"w") as archive:
        archive.writestr("me/clip/placeholderapi/PlaceholderAPI.class",b"fixture")
        archive.writestr("me/clip/placeholderapi/expansion/PlaceholderExpansion.class",b"fixture")
    return out.getvalue()


class PapiBuildInputTest(unittest.TestCase):
    def test_pin_is_official_version_digest_and_not_latest(self):
        self.assertEqual("2.12.3",target.VERSION)
        self.assertEqual("https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.12.3/PlaceholderAPI-2.12.3.jar",target.URL)
        self.assertEqual("fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437",target.SHA256)

    def test_verified_download_is_atomic_reused_and_bounded(self):
        data=artifact();digest=hashlib.sha256(data).hexdigest();calls=[]
        def opener(url,timeout):
            calls.append((url,timeout));return io.BytesIO(data)
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            root=Path(directory)
            path=target.prepare(root,opener=opener,expected=digest)
            self.assertEqual(data,path.read_bytes())
            self.assertFalse(path.with_suffix(".jar.tmp").exists())
            self.assertEqual(path,target.prepare(root,opener=opener,expected=digest))
        self.assertEqual([(target.URL,30)],calls)

    def test_checksum_mismatch_or_missing_classes_never_publish_a_file(self):
        for data,digest in [(artifact(),"0"*64),(b"not a jar",hashlib.sha256(b"not a jar").hexdigest())]:
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory)
                with self.assertRaises((ValueError,zipfile.BadZipFile)):
                    target.prepare(root,opener=lambda *a,**k:io.BytesIO(data),expected=digest)
                self.assertFalse((root/target.RELATIVE_PATH).exists())

    def test_wrong_archive_classes_are_rejected_even_with_matching_test_digest(self):
        data=io.BytesIO()
        with zipfile.ZipFile(data,"w") as archive:archive.writestr("other.class",b"no")
        data=data.getvalue()
        with self.assertRaises(ValueError):target.verify(data,hashlib.sha256(data).hexdigest())

    def test_oversize_input_and_tampered_cache_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with self.assertRaises(ValueError):target.prepare(root,opener=lambda *a,**k:io.BytesIO(b"x"*(target.MAX_BYTES+1)))
            path=root/target.RELATIVE_PATH;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b"tampered")
            with self.assertRaises(ValueError):target.prepare(root,opener=lambda *a,**k:self.fail("Do not silently replace changed cached input"))

    def test_network_failure_is_not_hidden_by_unverified_mirror_or_old_file(self):
        def offline(*a,**k):raise urllib.error.URLError("offline")
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with self.assertRaises(urllib.error.URLError):target.prepare(root,opener=offline)
            self.assertFalse((root/target.RELATIVE_PATH).exists())

    def test_every_papi_consumer_uses_pinned_compile_only_property(self):
        root=Path(__file__).resolve().parents[2]
        consumers=[root/"build.gradle",*(root/name/"build.gradle" for name in ["tier-forge","professions","quests","orders","hunt","guilds","classes"])]
        for path in consumers:
            text=path.read_text()
            self.assertIn("compileOnly(rootProject.findProperty('chrdk.papiJar')",text,str(path))
            self.assertIn(': "me.clip:placeholderapi:2.12.3")',text,str(path))
        for name in ["build.yml","release.yml"]:
            workflow=(root/".github/workflows"/name).read_text()
            self.assertIn("python3 .github/scripts/gradle_build.py",workflow,name)
            self.assertNotIn("continue-on-error",workflow,name)
            self.assertIn("test_prepare_papi.py",workflow,name)


if __name__=="__main__":unittest.main()
