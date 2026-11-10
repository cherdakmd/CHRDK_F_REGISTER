#!/usr/bin/env python3
"""Офлайн-тесты объявления ОБЩЕГО пререлиза: без VK, GitHub и сети."""
from __future__ import annotations

import json
import os
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

import prerelease_general
from content import SafetyError
from publisher import Ledger

TAG = 'v1.5.2-rc.6'
VERSION = '1.5.2-rc.6'


class GeneralPrereleaseTest(unittest.TestCase):

    def test_post_is_stable_and_carries_marker(self):
        now = datetime(2026, 11, 3, 12, 0, tzinfo=timezone.utc)
        post = prerelease_general.build_general_post(TAG, VERSION, now=now)
        again = prerelease_general.build_general_post(TAG, VERSION, now=now)
        self.assertEqual(post.key, 'prerelease:' + TAG)
        self.assertEqual(post.month, '2026-11')
        self.assertEqual(post.marker, again.marker)
        self.assertEqual(post.text, again.text)
        self.assertIn(VERSION, post.text)
        self.assertIn(TAG, post.text)
        self.assertTrue(post.text.rstrip().endswith(post.marker))
        self.assertLessEqual(len(post.text), 4000)
        self.assertNotIn('wall.post', post.text)

    def test_rejects_foreign_or_mismatched_tag(self):
        for tag in ('v1.5.2', 'vk-v1.5.2-rc.5', 'v1.5.2-rc.6-extra', 'v9.9.9-rc.1'):
            with self.subTest(tag=tag), self.assertRaises(SafetyError):
                prerelease_general.build_general_post(tag, VERSION)
        for version in ('1.5.2', '1.5.2-rc.5', ''):
            with self.subTest(version=version), self.assertRaises(SafetyError):
                prerelease_general.build_general_post(TAG, version)

    def test_stock_note_is_readable_and_bounded(self):
        post = prerelease_general.build_general_post(TAG, VERSION)
        self.assertIn('майнинг', post.text)
        self.assertIn('/vkadmin testpost', post.text)
        self.assertLessEqual(len(post.text), 4000)

    def test_check_renders_real_png_without_network(self):
        post = prerelease_general.build_general_post(TAG, VERSION)
        length, image = prerelease_general.prerelease.check(post)
        self.assertLessEqual(length, 4000)
        self.assertTrue(image.startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertGreater(len(image), 10_000)

    def test_publish_requires_explicit_opt_in(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(SafetyError):
                prerelease_general.main(['--publish', '--tag', TAG, '--version', VERSION])

    def test_check_mode_never_calls_configured(self):
        with patch.object(prerelease_general.prerelease, 'configured') as configured:
            self.assertEqual(0, prerelease_general.main(
                ['--check', '--tag', TAG, '--version', VERSION]))
            configured.assert_not_called()

    def test_text_only_never_calls_the_photo_uploader(self):
        class FakeVK:
            group, peer = 123456, 2000000001
            transport = None

            def upload(self, image):
                raise AssertionError('загрузчик фото не должен вызываться')

            def check_connection(self):
                return None

        fake = FakeVK()
        wrapper = prerelease_general.TextOnlyVK(fake)
        self.assertEqual(wrapper.upload(b'png'), '')
        self.assertEqual(wrapper.group, fake.group)
        with patch.object(prerelease_general.prerelease, 'configured',
                          return_value=(object(), fake)), \
             patch.object(prerelease_general.prerelease, 'publish_announcement',
                          return_value=(999, True)) as mocked:
            self.assertEqual(0, prerelease_general.main(
                ['--publish', '--text-only', '--tag', TAG, '--version', VERSION]))
            self.assertIsInstance(mocked.call_args.kwargs['vk'],
                                  prerelease_general.TextOnlyVK)

    def test_ledger_accepts_both_prerelease_key_formats(self):
        class FakeGH:
            def __init__(self, keys):
                self.keys, self.page = keys, 0

            def call(self, path, method='GET', data=None):
                if method != 'GET' or not path.startswith('issues?'):
                    raise AssertionError((method, path))
                self.page += 1
                if self.page > 1:
                    return []
                records = {key: {'digest': 'a' * 64, 'status': 'prepared'} for key in self.keys}
                return [{'number': 1,
                         'title': '[CHRDK VK CHAT 123456 2000000001] 2026-11',
                         'body': json.dumps({'schema': 2, 'group': 123456, 'peer': 2000000001,
                                             'records': records}, ensure_ascii=False)}]

        general = 'prerelease:' + TAG
        vk_only = 'prerelease:vk-v1.5.2-rc.5'
        ledger = Ledger(FakeGH([general, vk_only]), 123456, 2000000001)
        self.assertEqual(ledger.locate(general)[1]['status'], 'prepared')
        self.assertEqual(ledger.locate(vk_only)[1]['status'], 'prepared')
        for bad in ('prerelease:v1.5.2', 'prerelease:bogus', 'release:v1.5.2-rc.6',
                    'prerelease:v1.5.2-rc.0', general + '-extra'):
            with self.subTest(key=bad), self.assertRaises(SafetyError):
                Ledger(FakeGH([bad]), 123456, 2000000001)

    def test_ledger_key_is_distinct_from_the_vk_only_prerelease(self):
        post = prerelease_general.build_general_post(TAG, VERSION)
        self.assertNotIn('vk-v1.5.2-rc.5', post.key)
        self.assertEqual('prerelease:v1.5.2-rc.6', post.key)


if __name__ == '__main__':
    unittest.main()
