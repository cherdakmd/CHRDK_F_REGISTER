"""Тесты с fake API: настоящий VK/Issues никогда не вызываются."""
import copy
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from PIL import Image
from content import ROOT, MOSCOW, Post, SafetyError, catalog, due_slot, news, tip, topic_index, validate
from cards import render
from publisher import GitHub, Ledger, Transport, VK, publish
import run

NOW = datetime(2026, 11, 1, 18, tzinfo=MOSCOW)
POST = Post('tip:2026-11-01', 'tip', 'Проверяем безопасность', 'Простая карточка для теста.', 'Тестовый пост.', '2026-11')
IMAGE = b'\x89PNG\r\n\x1a\nfixture'


class FakeGH:
    def __init__(self):
        self.issues = []
        self.fail_status = None
        self.calls = []
        self.repository = 'owner/repo'

    def call(self, path, method='GET', data=None):
        self.calls.append((path, method, copy.deepcopy(data)))
        if method == 'GET':
            return copy.deepcopy(self.issues)
        if method == 'POST':
            issue = {'number': len(self.issues)+1, 'title': data['title'], 'body': data['body']}
            self.issues.append(issue)
            return copy.deepcopy(issue)
        if self.fail_status and f'"status": "{self.fail_status}"' in data['body']:
            raise SafetyError('Ошибка сохранения журнала')
        self.issues[int(path.split('/')[-1])-1]['body'] = data['body']
        return {}


class FakeVK:
    def __init__(self):
        self.group = 123
        self.uploads = self.posts = 0
        self.wall = {}
        self.fail = False
        self.lost_response = False

    def find(self, marker):
        return self.wall.get(marker)

    def upload(self, image):
        self.uploads += 1
        return 'photo-123_10'

    def post(self, post, attachment):
        self.posts += 1
        if not self.fail:
            self.wall[post.marker] = 42
        if self.fail or self.lost_response:
            raise SafetyError('Неизвестный результат')
        return 42


class QueueTransport:
    def __init__(self, *answers):
        self.answers = list(answers)
        self.calls = []

    def request(self, *args):
        self.calls.append(args)
        answer = self.answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return answer


class ScheduleTest(unittest.TestCase):
    def test_exact_wednesday_and_sunday_1800_moscow(self):
        self.assertEqual(NOW, due_slot(NOW))
        self.assertIsNone(due_slot(NOW-timedelta(seconds=1)))
        wed = NOW+timedelta(days=3)
        self.assertEqual(wed, due_slot(wed.astimezone(timezone.utc)))
        self.assertIsNone(due_slot(wed+timedelta(days=1, seconds=1)))

    def test_late_run_uses_same_slot_no_catchup_spam(self):
        self.assertEqual(NOW, due_slot(NOW+timedelta(hours=23)))
        self.assertIsNone(due_slot(NOW+timedelta(hours=25)))
        self.assertEqual(0, topic_index(NOW))
        self.assertEqual(1, topic_index(NOW+timedelta(days=3)))
        self.assertEqual(2, topic_index(NOW+timedelta(days=7)))

    def test_naive_time_is_rejected(self):
        with self.assertRaises(SafetyError):
            due_slot(datetime(2026, 11, 1))


class ContentTest(unittest.TestCase):
    def test_catalog_only_current_verified_wiki(self):
        validate()
        self.assertGreaterEqual(len(catalog()), 16)
        for topic in catalog():
            post = tip(NOW, topic)
            self.assertNotIn('{{', post.text)
            self.assertNotIn('`', post.text)
            self.assertLess(len(post.text), 8000)
            self.assertIn('проверяйте в игре', post.text)

    def test_removed_and_changed_source_are_blocked(self):
        topic = {**catalog()[0], 'source': 'missing-page'}
        with self.assertRaises(SafetyError):
            tip(NOW, topic)
        with self.assertRaises(SafetyError):
            tip(NOW, {**catalog()[0], 'excerpt': 'Несуществующее обещание'})
        with self.assertRaises(SafetyError):
            tip(NOW, {**catalog()[0], 'source': '../private'})

    def test_news_requires_public_zip_and_no_installation_claim(self):
        p = run.preview_news('1.4.272')
        self.assertIn('Установка на игровой сервер выполняется отдельно', p.text)
        release = {'tag_name': 'v1.4.272', 'draft': False, 'prerelease': False, 'assets': []}
        with self.assertRaises(SafetyError):
            news(release)
        for tag in ('v1.4.272-beta', 'incendium-1', '../secret'):
            with self.assertRaises(SafetyError):
                news({**release, 'tag_name': tag})

    def test_all_cards_fit_and_render_deterministically(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'card.png'
            for topic in catalog():
                render(tip(NOW, topic), path)
                with Image.open(path) as image:
                    self.assertEqual((1280, 720), image.size)
                    self.assertEqual('PNG', image.format)
            render(POST, path); first = path.read_bytes()
            render(POST, path); self.assertEqual(first, path.read_bytes())
            with self.assertRaises(SafetyError):
                render(Post(POST.key, 'tip', 'Очень длинный заголовок ' * 40, 'Подпись', 'x', '2026-11'), path)


class DurablePublishingTest(unittest.TestCase):
    def setUp(self):
        self.gh, self.vk = FakeGH(), FakeVK()

    def ledger(self):
        return Ledger(self.gh, 123)

    def test_success_persists_and_survives_restart_and_month_boundary(self):
        self.assertEqual((42, True), publish(POST, IMAGE, self.ledger(), self.vk))
        self.assertEqual((42, False), publish(POST, IMAGE, self.ledger(), self.vk))
        edited = Post(POST.key, POST.kind, POST.title, POST.caption, 'edited', '2027-01')
        self.assertEqual((42, False), publish(edited, IMAGE, self.ledger(), self.vk))
        self.assertEqual(1, self.vk.posts)
        self.assertEqual(1, self.vk.uploads)

    def test_timeout_after_commit_reconciles_without_second_post(self):
        self.vk.lost_response = True
        with self.assertRaises(SafetyError):
            publish(POST, IMAGE, self.ledger(), self.vk)
        self.assertEqual('posting', self.ledger().locate(POST.key)[1]['status'])
        self.assertEqual((42, False), publish(POST, IMAGE, self.ledger(), self.vk))
        self.assertEqual(1, self.vk.posts)

    def test_uncertain_missing_post_is_not_blindly_retried_and_blocks_new_posts(self):
        self.vk.fail = True
        with self.assertRaises(SafetyError):
            publish(POST, IMAGE, self.ledger(), self.vk)
        with self.assertRaises(SafetyError):
            publish(POST, IMAGE, self.ledger(), self.vk)
        next_post = Post('tip:2026-11-04', 'tip', 'Следующий', '', 'x', '2026-11')
        with self.assertRaises(SafetyError):
            publish(next_post, IMAGE, self.ledger(), self.vk)
        self.assertEqual(1, self.vk.posts)

    def test_no_post_when_journal_cannot_confirm_posting(self):
        self.gh.fail_status = 'posting'
        with self.assertRaises(SafetyError):
            publish(POST, IMAGE, self.ledger(), self.vk)
        self.assertEqual(0, self.vk.posts)
        self.gh.fail_status = None
        publish(POST, IMAGE, self.ledger(), self.vk)
        self.assertEqual(1, self.vk.uploads, 'уже сохранённая карточка переиспользуется')

    def test_lost_final_ledger_write_is_reconciled(self):
        self.gh.fail_status = 'posted'
        with self.assertRaises(SafetyError):
            publish(POST, IMAGE, self.ledger(), self.vk)
        self.gh.fail_status = None
        self.assertEqual((42, False), publish(POST, IMAGE, self.ledger(), self.vk))
        self.assertEqual(1, self.vk.posts)

    def test_changed_prepared_content_is_not_silently_reissued(self):
        ledger = self.ledger()
        ledger.save(POST.month, POST.key, {'digest': '0'*64, 'status': 'prepared'})
        with self.assertRaises(SafetyError):
            publish(POST, IMAGE, self.ledger(), self.vk)
        self.assertEqual(0, self.vk.posts)

    def test_existing_wall_marker_is_recovered_without_upload(self):
        self.vk.wall[POST.marker] = 42
        self.assertEqual((42, False), publish(POST, IMAGE, self.ledger(), self.vk))
        self.assertEqual(0, self.vk.uploads)

    def test_corrupt_or_duplicate_ledger_is_not_reset(self):
        publish(POST, IMAGE, self.ledger(), self.vk)
        good = copy.deepcopy(self.gh.issues)
        self.gh.issues[0]['body'] = '{invalid'
        with self.assertRaises(SafetyError): self.ledger()
        self.gh.issues = good + copy.deepcopy(good)
        with self.assertRaises(SafetyError): self.ledger()

    def test_closed_months_are_kept_and_same_key_cannot_exist_twice(self):
        publish(POST, IMAGE, self.ledger(), self.vk)
        self.gh.issues[0]['state'] = 'closed'
        self.assertIsNotNone(self.ledger().locate(POST.key)[1])
        another = copy.deepcopy(self.gh.issues[0]); another['title'] = '[CHRDK VK 123] 2026-12'
        self.gh.issues.append(another)
        with self.assertRaises(SafetyError): self.ledger()


class ApiTest(unittest.TestCase):
    def test_community_owner_from_group_guid_and_attachment(self):
        t = QueueTransport({'response': {'post_id': 9}})
        self.assertEqual(9, VK(123, 'SECRET', t).post(POST, 'photo-123_10'))
        from urllib.parse import parse_qs
        form = parse_qs(t.calls[0][2].decode())
        self.assertEqual(['-123'], form['owner_id'])
        self.assertEqual(['1'], form['from_group'])
        self.assertEqual([POST.guid], form['guid'])
        self.assertIn(POST.marker, form['message'][0])
        self.assertNotIn('SECRET', t.calls[0][0])

    def test_retry_only_explicit_rate_limit(self):
        t = QueueTransport({'error': {'error_code': 6}}, {'response': {'post_id': 9}})
        waits = []
        VK(123, 'secret', t, waits.append).post(POST, 'photo-123_10')
        self.assertEqual([1], waits)
        self.assertEqual(2, len(t.calls))
        t = QueueTransport(SafetyError('timeout'))
        with self.assertRaises(SafetyError): VK(123, 'secret', t).post(POST, 'photo-123_10')
        self.assertEqual(1, len(t.calls))

    def test_errors_never_echo_api_parameters(self):
        for code in (5, 7, 14, 15, {'access_token': 'SECRET'}):
            t = QueueTransport({'error': {'error_code': code, 'request_params': [{'value': 'SECRET'}]}})
            with self.assertRaises(SafetyError) as error:
                VK(123, 'SECRET', t).post(POST, 'photo-123_10')
            self.assertNotIn('SECRET', str(error.exception))

    def test_upload_only_official_https_hosts_and_no_bearer_in_upload(self):
        for url in ('http://pu.vk.com/x', 'https://vk.com.evil.test/x', 'https://localhost/x',
                    'https://user:pass@pu.vk.com/x', 'https://pu.vk.com:444/x'):
            t = QueueTransport({'response': {'upload_url': url}})
            with self.assertRaises(SafetyError): VK(123, 'SECRET', t).upload(IMAGE)
            self.assertEqual(1, len(t.calls))
        t = QueueTransport({'response': {'upload_url': 'https://pu.vk.com/upload'}},
                           {'photo': 'photo', 'server': 1, 'hash': 'h'},
                           {'response': [{'owner_id': -123, 'id': 9}]})
        self.assertEqual('photo-123_9', VK(123, 'SECRET', t).upload(IMAGE))
        self.assertNotIn(b'SECRET', t.calls[1][2])
        self.assertNotIn('Authorization', t.calls[1][3])

    def test_wall_scan_checks_owner_and_whole_marker(self):
        t = QueueTransport({'response': {'items': [{'id': 3, 'owner_id': -123, 'text': POST.text}]}})
        self.assertEqual(3, VK(123, 'secret', t).find(POST.marker))
        t = QueueTransport({'response': {'items': [{'id': 3, 'owner_id': -999, 'text': POST.text}]}})
        self.assertIsNone(VK(123, 'secret', t).find(POST.marker))

    def test_transport_sanitizes_network_error_and_disallows_redirect(self):
        from urllib.error import URLError
        from publisher import NoRedirect
        with patch('publisher.build_opener') as opener:
            opener.return_value.open.side_effect = URLError('https://secret?token=SECRET')
            with self.assertRaises(SafetyError) as error:
                Transport().request('https://secret?token=SECRET')
            self.assertNotIn('SECRET', str(error.exception))
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, '', {}, 'https://evil.test'))


class EntryPointTest(unittest.TestCase):
    def test_default_preview_never_constructs_remote_clients(self):
        with tempfile.TemporaryDirectory() as folder, patch('run.github', side_effect=AssertionError('network')), patch('run.VK', side_effect=AssertionError('network')):
            self.assertEqual(0, run.main(['--at', NOW.isoformat(), '--out', folder]))
            self.assertTrue((Path(folder)/'card.png').is_file())
            self.assertTrue((Path(folder)/'post.txt').is_file())
            self.assertNotIn('token', (Path(folder)/'metadata.json').read_text())

    def test_publish_requires_main_flag_and_allowed_event(self):
        combinations = ({}, {'VK_AUTOPUBLISH_ENABLED': 'true'},
                        {'VK_AUTOPUBLISH_ENABLED': 'true', 'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'pull_request'},
                        {'VK_AUTOPUBLISH_ENABLED': 'true', 'GITHUB_REF': 'refs/heads/other', 'GITHUB_EVENT_NAME': 'schedule'})
        for env in combinations:
            with patch.dict(os.environ, env, clear=True), self.assertRaises(SafetyError):
                run.main(['--publish'])

    def test_workflow_gates_are_present(self):
        workflow = (ROOT/'.github/workflows/vk-news.yml').read_text()
        self.assertIn("cron: '0 15 * * 0,3'", workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        self.assertIn('persist-credentials: false', workflow)
        self.assertIn('ref: main', workflow)
        self.assertNotIn('pull_request_target', workflow)

    def test_untrusted_or_failed_release_run_rejected(self):
        gh = FakeGH()
        for event in ({'conclusion': 'failure'}, {'conclusion': 'success', 'event': 'push'},
                      {'conclusion': 'success', 'event': 'release', 'head_repository': {'full_name': 'fork/repo'}}):
            with self.assertRaises(SafetyError): run.release_for_run(gh, event)
        self.assertEqual([], gh.calls)


class ReleaseBindingTest(unittest.TestCase):
    def release(self):
        return {'tag_name': 'v1.4.272', 'draft': False, 'prerelease': False,
                'assets': [{'name': 'chrdk-plugins-1.4.272.zip', 'state': 'uploaded', 'size': 123}],
                'published_at': '2026-11-01T15:00:00Z',
                'html_url': 'https://github.com/owner/repo/releases/tag/v1.4.272'}

    def test_run_resolves_annotated_tag_and_rejects_other_commit(self):
        release = self.release()
        class API:
            repository = 'owner/repo'
            def call(self, path):
                return {'releases?per_page=100': [release],
                        'git/ref/tags/v1.4.272': {'object': {'type': 'tag', 'sha': 'tag-object'}},
                        'git/tags/tag-object': {'object': {'type': 'commit', 'sha': 'built-commit'}}}[path]
        event = {'conclusion': 'success', 'event': 'release', 'head_sha': 'built-commit',
                 'head_repository': {'full_name': 'owner/repo'}}
        self.assertEqual(release, run.release_for_run(API(), event))
        with self.assertRaises(SafetyError):
            run.release_for_run(API(), {**event, 'head_sha': 'different-commit'})

    def test_news_comes_from_release_tag_not_main_or_release_body(self):
        import base64
        release = self.release()
        release['body'] = 'Технический журнал не должен попасть в пост'
        note = {'version': '1.4.272', 'title': 'Заметка из тега', 'caption': 'Подпись', 'points': ['Понятное игрокам изменение.']}
        class API:
            def call(self, path):
                if path != 'contents/docs/social/releases/1.4.272.json?ref=v1.4.272':
                    raise AssertionError(path)
                return {'encoding': 'base64', 'content': base64.b64encode(json.dumps(note).encode()).decode()}
        post = run.tagged_news(API(), release)
        self.assertEqual('Заметка из тега', post.title)
        self.assertNotIn('Технический журнал', post.text)
        self.assertIn('Понятное игрокам изменение.', post.text)

    def test_malformed_and_empty_news_cannot_be_published(self):
        for points in ('oops', [], [''], ['x' * 601], ['x'] * 7):
            with self.assertRaises(SafetyError):
                news(self.release(), note={'version': '1.4.272', 'title': 'title', 'caption': 'caption', 'points': points})


if __name__ == '__main__':
    unittest.main()
