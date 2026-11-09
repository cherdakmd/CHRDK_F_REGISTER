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
from urllib.parse import parse_qs

import prerelease
from content import SafetyError
from publisher import Ledger, VK

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


class FakeGH:
    """Один месячный журнал Issues и пустая вторая страница."""

    def __init__(self, records):
        self.records, self.page = records, 0

    def call(self, path, method='GET', data=None):
        assert method == 'GET' and path.startswith('issues?'), (path, method)
        self.page += 1
        if self.page > 1:
            return []
        return [{'number': 1, 'title': '[CHRDK VK CHAT 123456 2000000001] 2026-11',
                 'body': json.dumps({'schema': 2, 'group': 123456, 'peer': 2000000001,
                                     'records': self.records}, ensure_ascii=False)}]


class FailingCardVK:
    group, peer = 123456, 2000000001

    def upload(self, image):
        raise SafetyError('VK не подтвердил загрузку карточки')


class RecordingVKTransport:
    def __init__(self):
        self.calls = []

    def request(self, url, method='GET', data=None, headers=None):
        self.calls.append((url, method, data))
        return {'response': 9}


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

    def test_ledger_accepts_prerelease_key_but_rejects_foreign_key(self):
        record = {'digest': 'a' * 64, 'status': 'prepared'}
        ledger = Ledger(FakeGH({'prerelease:' + TAG: record}), 123456, 2000000001)
        self.assertEqual(ledger.locate('prerelease:' + TAG)[1]['status'], 'prepared')
        for key in ('prerelease:bogus', 'prerelease:v1.5.2', 'prerelease:vk-v1.5.2-rc.0',
                    'release:vk-v1.5.2-rc.5', 'prerelease:' + TAG + '-extra'):
            with self.subTest(key=key), self.assertRaises(SafetyError):
                Ledger(FakeGH({key: record}), 123456, 2000000001)

    def test_card_optional_adapter_falls_back_to_plain_text(self):
        wrapper = prerelease.CardOptionalVK(FailingCardVK())
        self.assertEqual(wrapper.group, 123456)
        self.assertEqual(wrapper.upload(b'png'), '')
        self.assertFalse(wrapper.card)

    def test_empty_attachment_is_omitted_from_messages_send(self):
        transport = RecordingVKTransport()
        vk = VK(123456, 'TOKEN', transport, peer=2000000001)
        self.assertEqual(9, vk.post(prerelease.build_post(TAG, VERSION), ''))
        form = parse_qs(transport.calls[0][2].decode())
        self.assertNotIn('attachment', form)
        self.assertIn('message', form)

    def test_text_fallback_only_after_real_upload_response(self):
        class VKWithTransport(FakeVK):
            transport = type('T', (), {'upload_keys': None})()

            def check_connection(self):
                return None

        with patch.object(prerelease, 'configured', return_value=(object(), VKWithTransport())), \
             patch.object(prerelease, 'publish_announcement',
                          side_effect=SafetyError('журнал не прочитан')) as mocked, \
             patch.object(prerelease.time, 'sleep'):
            with self.assertRaises(SafetyError):
                prerelease.main(['--publish', '--tag', TAG, '--version', VERSION])
        self.assertEqual(mocked.call_count, prerelease.MAX_ATTEMPTS)


class Rc7NoteTest(unittest.TestCase):
    """Новая заметка для VK-only пререлиза: читается, короткая, без сети."""

    TAG = 'vk-v1.5.2-rc.7'
    VERSION = '1.5.2-rc.7'

    def test_note_is_readable_and_check_renders_png(self):
        post = prerelease.build_post(self.TAG, self.VERSION)
        self.assertEqual(post.key, 'prerelease:' + self.TAG)
        self.assertIn(self.VERSION, post.text)
        self.assertIn('/vkadmin testcard', post.text)
        self.assertNotIn('wall.post', post.text)
        length, image = prerelease.check(post)
        self.assertLessEqual(length, 4000)
        self.assertTrue(image.startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertGreater(len(image), 10_000)

    def test_check_mode_never_touches_vk(self):
        with patch.object(prerelease, 'configured') as configured:
            self.assertEqual(0, prerelease.main(
                ['--check', '--tag', self.TAG, '--version', self.VERSION]))
            configured.assert_not_called()


if __name__ == '__main__':
    unittest.main()
