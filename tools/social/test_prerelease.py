#!/usr/bin/env python3
"""Офлайн-тесты объявления пререлиза CHRDK_VK: без VK, GitHub и сети."""
from __future__ import annotations

import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import prerelease
from content import SafetyError

TAG = 'vk-v1.5.2-rc.5'
VERSION = '1.5.2-rc.5'


class FakeVK:
    group = 123456
    peer = 2000000001

    def __init__(self):
        self.checked = False

    def check_connection(self):
        self.checked = True


class FakeLedger:
    group = 123456
    peer = 2000000001


class PrereleaseTest(unittest.TestCase):

    def test_post_is_stable_and_carries_marker(self):
        now = datetime(2026, 11, 8, 18, 0, tzinfo=timezone.utc)
        post = prerelease.build_post(TAG, VERSION, now=now)
        again = prerelease.build_post(TAG, VERSION, now=now)
        self.assertEqual(post.key, 'prerelease:' + TAG)
        self.assertEqual(post.month, '2026-11')
        self.assertEqual(post.marker, again.marker)
        self.assertEqual(post.text, again.text)
        self.assertIn(VERSION, post.text)
        self.assertIn(TAG, post.text)
        self.assertTrue(post.text.rstrip().endswith(post.marker))
        self.assertLessEqual(len(post.text), 4000)

    def test_rejects_foreign_or_mismatched_tag(self):
        for tag in ('v1.5.2', 'vk-v1.5.2', 'vk-v9.9.9-rc.1', TAG + '-extra'):
            with self.subTest(tag=tag), self.assertRaises(SafetyError):
                prerelease.build_post(tag, VERSION)
        for version in ('1.5.2', '1.5.3-rc.1', ''):
            with self.subTest(version=version), self.assertRaises(SafetyError):
                prerelease.build_post(TAG, version)

    def test_note_missing_or_oversized_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(SafetyError):
                prerelease.note_for(VERSION, root)
            directory = root / 'docs' / 'social' / 'prereleases'
            directory.mkdir(parents=True)
            (directory / (VERSION + '.json')).write_text(
                json.dumps({'version': VERSION, 'title': 'x' * 101,
                            'caption': 'ok', 'points': ['ok']}), encoding='utf-8')
            with self.assertRaises(SafetyError):
                prerelease.note_for(VERSION, root)

    def test_check_renders_real_png_without_network(self):
        post = prerelease.build_post(TAG, VERSION)
        length, image = prerelease.check(post)
        self.assertLessEqual(length, 4000)
        self.assertTrue(image.startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertGreater(len(image), 10_000)
        self.assertEqual(post.text.count('wall.post'), 0, 'В тексте новости нет технических методов')

    def test_publish_uses_existing_ledger_and_checks_connection(self):
        vk = FakeVK()
        post = prerelease.build_post(TAG, VERSION)
        with patch.object(prerelease, 'publish', return_value=(777, True)) as mocked:
            message_id, created = prerelease.publish_announcement(
                post, b'png', gh=object(), vk=vk, ledger=FakeLedger())
        self.assertTrue(vk.checked)
        self.assertEqual((message_id, created), (777, True))
        self.assertIs(mocked.call_args.args[0], post)
        self.assertIsInstance(mocked.call_args.args[2], FakeLedger)

    def test_publish_requires_explicit_opt_in(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(SafetyError):
                prerelease.configured()

    def test_recording_transport_keeps_only_field_names(self):
        transport = prerelease.RecordingTransport()
        with patch.object(prerelease.Transport, 'request',
                          return_value={'photo': 'secret', 'hash': 'secret', 'server': 1}):
            result = transport.request('https://example.invalid/upload', 'POST', b'x',
                                       {'Content-Type': 'multipart/form-data; boundary=x'})
        self.assertEqual(sorted(result.keys()), transport.upload_keys)
        self.assertNotIn('secret', str(transport.upload_keys))

    def test_publish_retries_three_times_without_changing_the_post(self):
        class VKWithTransport(FakeVK):
            transport = type('T', (), {'upload_keys': ['error_code', 'error_msg']})()

        failures = [SafetyError('загрузка не подтверждена')] * 2
        with patch.object(prerelease, 'configured', return_value=(object(), VKWithTransport())), \
             patch.object(prerelease, 'publish_announcement',
                          side_effect=failures + [(777, True)]) as mocked, \
             patch.object(prerelease.time, 'sleep') as sleeper:
            rc = prerelease.main(['--publish', '--tag', TAG, '--version', VERSION])
        self.assertEqual(rc, 0)
        self.assertEqual(mocked.call_count, 3)
        self.assertEqual(sleeper.call_count, 2)
        first = mocked.call_args_list[0]
        self.assertEqual(first.kwargs['gh'], mocked.call_args_list[2].kwargs['gh'])
        self.assertIs(first.args[0], mocked.call_args_list[2].args[0])


if __name__ == '__main__':
    unittest.main()
