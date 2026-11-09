#!/usr/bin/env python3
"""Офлайн-тесты объявления ОБЩЕГО пререлиза: без VK, GitHub и сети."""
from __future__ import annotations

import os
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

import prerelease_general
from content import SafetyError

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

    def test_ledger_key_is_distinct_from_the_vk_only_prerelease(self):
        post = prerelease_general.build_general_post(TAG, VERSION)
        self.assertNotIn('vk-v1.5.2-rc.5', post.key)
        self.assertEqual('prerelease:v1.5.2-rc.6', post.key)


if __name__ == '__main__':
    unittest.main()
