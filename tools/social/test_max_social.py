"""Офлайн-тесты публикации в MAX: ни одного настоящего запроса и секрета в логах."""
from __future__ import annotations

import copy
import json
import unittest

from content import Post
from max_publisher import MAX, MaxLedger, check_env, news_text, publish_max, release_url_for

POST = Post('release:v1.4.287', 'news', '1.4.287 — заголовок', 'Краткая карточка',
            'Полный текст новости для игроков.', '2026-11')
CHAT = 72000001
TOKEN = 'MAX-TOKEN-fixture'


class FakeTransport:
    def __init__(self, responses=None):
        self.calls = []
        self.responses = responses or {}

    def request(self, url, method='GET', data=None, headers=None):
        self.calls.append({'url': url, 'method': method, 'data': data, 'headers': headers or {}})
        key = method + ' ' + url
        if key in self.responses:
            return copy.deepcopy(self.responses[key])
        if url.endswith('/messages'):
            return {'message': {'body': {'mid': 'mid-001'}}}
        if url.endswith('/me'):
            return {'user_id': 1, 'name': 'chrdk_bot'}
        return {}


class FakeGH:
    def __init__(self, issues=None):
        self.issues = issues or []
        self.calls = []

    def call(self, path, method='GET', data=None):
        self.calls.append((path, method, copy.deepcopy(data)))
        if method == 'GET':
            return copy.deepcopy(self.issues)
        if method == 'POST':
            issue = {'number': len(self.issues) + 1, 'title': data['title'], 'body': data['body']}
            self.issues.append(issue)
            return copy.deepcopy(issue)
        self.issues[int(path.split('/')[-1]) - 1]['body'] = data['body']
        return {}


class MaxClientTests(unittest.TestCase):
    def test_token_only_in_authorization_header(self):
        transport = FakeTransport()
        client = MAX(TOKEN, transport)
        client.check_connection()
        url = transport.calls[0]['url']
        self.assertEqual('https://platform-api2.max.ru/me', url)
        self.assertNotIn(TOKEN, url)
        self.assertEqual(TOKEN, transport.calls[0]['headers']['Authorization'])

    def test_post_text_body_and_message_id(self):
        transport = FakeTransport()
        mid = MAX(TOKEN, transport).post_text(CHAT, '  Привет, MAX  ')
        self.assertEqual('mid-001', mid)
        payload = json.loads(transport.calls[0]['data'])
        self.assertEqual({'chat_id': CHAT, 'text': 'Привет, MAX'}, payload)

    def test_bad_chat_or_empty_text_rejected(self):
        client = MAX(TOKEN, FakeTransport())
        with self.assertRaises(Exception):
            client.post_text(0, 'текст')
        with self.assertRaises(Exception):
            client.post_text(CHAT, '   ')

    def test_disallowed_method_blocked(self):
        with self.assertRaises(Exception):
            MAX(TOKEN, FakeTransport()).call('DELETE', 'messages')

    def test_token_with_spaces_rejected(self):
        with self.assertRaises(Exception):
            MAX('a b', FakeTransport())


class MaxLedgerTests(unittest.TestCase):
    def test_locate_and_save_are_isolated_from_vk(self):
        gh = FakeGH([{'number': 1, 'title': '[CHRDK VK CHAT 123 2000000007] 2026-11',
                     'body': json.dumps({'schema': 2, 'group': 123, 'peer': 2000000007, 'records': {}})}])
        ledger = MaxLedger(gh, CHAT)
        self.assertIsNone(ledger.locate(POST.key)[0])
        ledger.save('2026-11', POST.key, {'digest': 'a' * 64, 'status': 'posted', 'message_id': 'mid'})
        month, record = ledger.locate(POST.key)
        self.assertEqual('2026-11', month)
        self.assertEqual('posted', record['status'])

    def test_corrupted_journal_stops_publication(self):
        gh = FakeGH([{'number': 1, 'title': f'[CHRDK MAX NEWS {CHAT}] 2026-11',
                     'body': json.dumps({'schema': 2, 'chat': CHAT,
                                        'records': {'bad-key': {'status': 'posted'}}})}])
        with self.assertRaises(Exception):
            MaxLedger(gh, CHAT)


class PublishMaxTests(unittest.TestCase):
    def test_idempotent_and_fail_closed_on_unknown_posting(self):
        transport = FakeTransport()
        client = MAX(TOKEN, transport)
        ledger = MaxLedger(FakeGH(), CHAT)
        first, created = publish_max(POST, ledger, client, CHAT)
        self.assertTrue(created)
        self.assertEqual('mid-001', first)
        second, created_again = publish_max(POST, ledger, client, CHAT)
        self.assertFalse(created_again)
        self.assertEqual(first, second)
        self.assertEqual(1, sum(1 for call in transport.calls if call['url'].endswith('/messages')))

    def test_posting_without_ack_requires_manual_check(self):
        gh = FakeGH([{'number': 1, 'title': f'[CHRDK MAX NEWS {CHAT}] 2026-11',
                      'body': json.dumps({'schema': 2, 'chat': CHAT, 'records': {
                          POST.key: {'digest': 'b' * 64, 'status': 'posting'}}})}])
        with self.assertRaises(Exception):
            publish_max(POST, MaxLedger(gh, CHAT), MAX(TOKEN, FakeTransport()), CHAT)

    def test_text_contains_title_body_and_release_url(self):
        text = news_text(POST, release_url_for(POST))
        self.assertIn('1.4.287 — заголовок', text)
        self.assertIn('Полный текст новости для игроков.', text)
        self.assertIn('releases/tag/v1.4.287', text)


class EnvTests(unittest.TestCase):
    def test_check_mode_without_secrets_is_fine(self):
        self.assertEqual({'enabled': False, 'configured': False}, check_env({}))

    def test_enabled_requires_token_and_chat(self):
        with self.assertRaises(Exception):
            check_env({'MAX_AUTOPUBLISH_ENABLED': 'true', 'MAX_BOT_TOKEN': TOKEN})

    def test_bad_chat_id_rejected(self):
        with self.assertRaises(Exception):
            check_env({'MAX_NEWS_CHAT_ID': 'not-a-number'})

    def test_enabled_config(self):
        result = check_env({'MAX_AUTOPUBLISH_ENABLED': 'true',
                            'MAX_BOT_TOKEN': TOKEN, 'MAX_NEWS_CHAT_ID': str(CHAT)})
        self.assertTrue(result['enabled'])
        self.assertTrue(result['configured'])


if __name__ == '__main__':
    unittest.main()
