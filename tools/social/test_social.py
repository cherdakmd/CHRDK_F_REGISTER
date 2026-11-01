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
PEER = 2_000_000_007
CONVERSATION = {'peer': {'id': PEER, 'type': 'chat'}, 'can_write': {'allowed': True}}
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
        self.group, self.peer = 123, PEER
        self.uploads = self.posts = 0
        self.messages = {}
        self.fail = False
        self.lost_response = False

    def find(self, marker):
        return self.messages.get(marker)

    def upload(self, image):
        self.uploads += 1
        return 'photo-123_10'

    def post(self, post, attachment):
        self.posts += 1
        if not self.fail:
            self.messages[post.marker] = 42
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
    def test_daily_noon_and_evening_including_weekdays(self):
        noon = NOW.replace(hour=12)
        for days in range(7):
            for slot in (noon+timedelta(days=days), NOW+timedelta(days=days)):
                self.assertEqual(slot, due_slot(slot.astimezone(timezone.utc)))
        self.assertIsNone(due_slot(noon-timedelta(seconds=1)))
        self.assertEqual(noon, due_slot(NOW-timedelta(seconds=1)))

    def test_late_runs_do_not_catch_up_old_slots(self):
        self.assertEqual(NOW, due_slot(NOW+timedelta(hours=5, minutes=59)))
        self.assertIsNone(due_slot(NOW+timedelta(hours=6)))
        self.assertIsNone(due_slot(NOW+timedelta(hours=17)))
        noon = NOW.replace(hour=12)
        self.assertEqual(0, topic_index(noon))
        self.assertEqual(1, topic_index(NOW))
        self.assertEqual(2, topic_index(noon+timedelta(days=1)))
        self.assertEqual(3, topic_index(NOW+timedelta(days=1)))

    def test_slot_keys_are_distinct_and_evening_keeps_legacy_identity(self):
        noon, evening = tip(NOW.replace(hour=12)), tip(NOW)
        self.assertEqual('tip:2026-11-01:12', noon.key)
        self.assertEqual('tip:2026-11-01', evening.key)
        self.assertNotEqual(noon.guid, evening.guid)
        self.assertNotEqual(noon.title, evening.title)

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
        return Ledger(self.gh, 123, PEER)

    def test_success_persists_and_survives_restart_and_month_boundary(self):
        self.assertEqual((42, True), publish(POST, IMAGE, self.ledger(), self.vk))
        self.assertEqual((42, False), publish(POST, IMAGE, self.ledger(), self.vk))
        edited = Post(POST.key, POST.kind, POST.title, POST.caption, 'edited', '2027-01')
        self.assertEqual((42, False), publish(edited, IMAGE, self.ledger(), self.vk))
        self.assertEqual(1, self.vk.posts)
        self.assertEqual(1, self.vk.uploads)

    def test_both_daily_slots_publish_once_and_survive_restart(self):
        noon = tip(NOW.replace(hour=12))
        evening = tip(NOW)
        publish(noon, IMAGE, self.ledger(), self.vk)
        publish(evening, IMAGE, self.ledger(), self.vk)
        publish(noon, IMAGE, self.ledger(), self.vk)
        publish(evening, IMAGE, self.ledger(), self.vk)
        self.assertEqual(2, self.vk.posts)
        self.assertEqual(2, len(self.ledger().shards['2026-11'][1]['records']))

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
        self.assertEqual(2, self.vk.uploads, 'после prepared можно перезагрузить фото, не сохраняя приватный access_key')

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

    def test_existing_chat_marker_is_recovered_without_upload(self):
        self.vk.messages[POST.marker] = 42
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
        another = copy.deepcopy(self.gh.issues[0]); another['title'] = '[CHRDK VK CHAT 123 2000000007] 2026-12'
        self.gh.issues.append(another)
        with self.assertRaises(SafetyError): self.ledger()


class ApiTest(unittest.TestCase):
    def test_community_peer_random_id_and_attachment(self):
        t = QueueTransport({'response': 9})
        self.assertEqual(9, VK(123, 'SECRET', t, peer=PEER).post(POST, 'photo-123_10'))
        from urllib.parse import parse_qs
        form = parse_qs(t.calls[0][2].decode())
        self.assertEqual([str(PEER)], form['peer_id'])
        self.assertEqual(['123'], form['group_id'])
        self.assertTrue(1 <= int(form['random_id'][0]) <= 2147483647)
        self.assertNotIn('owner_id', form)
        self.assertTrue(t.calls[0][0].endswith('/messages.send'))
        self.assertIn(POST.marker, form['message'][0])
        self.assertNotIn('SECRET', t.calls[0][0])

    def test_retry_only_explicit_rate_limit(self):
        t = QueueTransport({'error': {'error_code': 6}}, {'response': 9})
        waits = []
        VK(123, 'secret', t, waits.append, peer=PEER).post(POST, 'photo-123_10')
        self.assertEqual([1], waits)
        self.assertEqual(2, len(t.calls))
        t = QueueTransport(SafetyError('timeout'))
        with self.assertRaises(SafetyError): VK(123, 'secret', t, peer=PEER).post(POST, 'photo-123_10')
        self.assertEqual(1, len(t.calls))

    def test_errors_never_echo_api_parameters(self):
        for code in (5, 7, 14, 15, {'access_token': 'SECRET'}):
            t = QueueTransport({'error': {'error_code': code, 'request_params': [{'value': 'SECRET'}]}})
            with self.assertRaises(SafetyError) as error:
                VK(123, 'SECRET', t, peer=PEER).post(POST, 'photo-123_10')
            self.assertNotIn('SECRET', str(error.exception))

    def test_upload_only_official_https_hosts_and_no_bearer_in_upload(self):
        for url in ('http://pu.vk.com/x', 'https://vk.com.evil.test/x', 'https://localhost/x',
                    'https://user:pass@pu.vk.com/x', 'https://pu.vk.com:444/x'):
            t = QueueTransport({'response': {'upload_url': url}})
            with self.assertRaises(SafetyError): VK(123, 'SECRET', t, peer=PEER).upload(IMAGE)
            self.assertEqual(1, len(t.calls))
        t = QueueTransport({'response': {'upload_url': 'https://pu.vk.com/upload'}},
                           {'photo': 'photo', 'server': 1, 'hash': 'h'},
                           {'response': [{'owner_id': -123, 'id': 9}]})
        self.assertEqual('photo-123_9', VK(123, 'SECRET', t, peer=PEER).upload(IMAGE))
        self.assertNotIn(b'SECRET', t.calls[1][2])
        self.assertNotIn('Authorization', t.calls[1][3])

    def test_history_scan_checks_sender_peer_and_whole_marker(self):
        t = QueueTransport({'response': {'items': [{'id': 3, 'from_id': -123, 'peer_id': PEER, 'text': POST.text}]}})
        self.assertEqual(3, VK(123, 'secret', t, peer=PEER).find(POST.marker))
        t = QueueTransport({'response': {'items': [{'id': 3, 'from_id': -999, 'peer_id': PEER, 'text': POST.text}]}})
        self.assertIsNone(VK(123, 'secret', t, peer=PEER).find(POST.marker))

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
        self.assertIn("cron: '0 9,15 * * *'", workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        self.assertIn('persist-credentials: false', workflow)
        self.assertIn('ref: main', workflow)
        self.assertNotIn('pull_request_target', workflow)

    def test_workflow_chat_modes_and_peer_configuration_match_cli(self):
        import yaml
        workflow = yaml.load((ROOT/'.github/workflows/vk-news.yml').read_text(), Loader=yaml.BaseLoader)
        inputs = workflow['on']['workflow_dispatch']['inputs']
        self.assertEqual(['preview', 'find_chat', 'check_connection', 'test_post'], inputs['mode']['options'])
        steps = workflow['jobs']['publish']['steps']
        for option in ('--find-chat', '--check-connection', '--test-post', '--publish'):
            step = next(step for step in steps if option in step.get('run', ''))
            self.assertEqual('${{ vars.VK_NEWS_PEER_ID }}', step['env']['VK_NEWS_PEER_ID'])
            self.assertEqual('${{ secrets.VK_NEWS_ACCESS_TOKEN }}', step['env']['VK_NEWS_ACCESS_TOKEN'])
        self.assertEqual('false', inputs['publish']['default'])
        self.assertEqual('issues', next(k for k, v in workflow['permissions'].items() if v == 'write'))

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


class ConnectionAndPosterTest(unittest.TestCase):
    def test_connection_check_never_uploads_or_posts_or_exposes_signed_url(self):
        t = QueueTransport({'response': {'items': [CONVERSATION]}}, {'response': {'items': []}},
                           {'response': {'upload_url': 'https://pu.vk.com/upload?private=SECRET'}})
        progress = []
        report = VK(123, 'SECRET', t, peer=PEER).check_connection(progress.append)
        self.assertEqual(3, len(t.calls))
        self.assertEqual(['messages.getConversationsById', 'messages.getHistory', 'photos.getMessagesUploadServer'], report['verified'])
        self.assertFalse(report['sent'])
        self.assertIn('messages.send', report['not_verified'])
        self.assertNotIn('SECRET', str(report)+str(progress))
        self.assertEqual(['messages.getConversationsById', 'messages.getHistory', 'photos.getMessagesUploadServer'], [c[0].rsplit('/', 1)[1] for c in t.calls])

    def test_method_error_is_clear_but_never_echoes_token(self):
        t = QueueTransport({'error': {'error_code': 27, 'error_msg': 'SECRET'}})
        with self.assertRaises(SafetyError) as error:
            VK(123, 'SECRET', t, peer=PEER).check_connection(lambda x: None)
        self.assertIn('messages.getConversationsById', str(error.exception))
        self.assertIn('27', str(error.exception))
        self.assertNotIn('SECRET', str(error.exception))
        self.assertEqual(1, len(t.calls))

    def test_connection_report_is_not_a_posting_permission_guarantee(self):
        t = QueueTransport({'response': {'items': [CONVERSATION]}},
                           {'error': {'error_code': 7}})
        progress = []
        with self.assertRaises(SafetyError):
            VK(123, 'secret', t, peer=PEER).check_connection(progress.append)
        self.assertEqual(1, len(progress))
        self.assertIn('messages.getConversationsById: OK', progress[0])

    def test_read_only_cli_works_with_schedule_disabled_and_without_github(self):
        class Client:
            def check_connection(self):
                return {'group_id': 123, 'sent': False, 'verified': [], 'not_verified': ['messages.send']}
        env = {'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'VK_AUTOPUBLISH_ENABLED': 'false'}
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, env, clear=True), \
                patch('run.configured_vk', return_value=Client()), patch('run.github', side_effect=AssertionError('journal')), \
                patch('run.validate', side_effect=AssertionError('content')), patch('run.render', side_effect=AssertionError('image')):
            self.assertEqual(0, run.main(['--check-connection', '--out', folder]))
            self.assertFalse(json.loads((Path(folder)/'connection.json').read_text())['sent'])
            self.assertFalse((Path(folder)/'card.png').exists())

    def test_checks_and_test_posts_require_manual_trusted_main(self):
        for option in ('--test-post', '--check-connection', '--find-chat'):
            for env in ({}, {'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'schedule'},
                        {'GITHUB_REF': 'refs/heads/other', 'GITHUB_EVENT_NAME': 'workflow_dispatch'}):
                with patch.dict(os.environ, env, clear=True), self.assertRaises(SafetyError):
                    run.main([option])
            env = {'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch'}
            with patch.dict(os.environ, env, clear=True), self.assertRaises(SafetyError):
                run.main([option, '--at', NOW.isoformat()])

    def test_one_test_post_is_durable_and_never_enables_schedule(self):
        class Client(FakeVK):
            def check_connection(self):
                return {'sent': False}
        gh, vk = FakeGH(), Client()
        env = {'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'VK_AUTOPUBLISH_ENABLED': 'false'}
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, env, clear=True), \
                patch('run.configured_vk', return_value=vk), patch('run.github', return_value=gh):
            self.assertEqual(0, run.main(['--test-post', '--out', folder]))
            self.assertEqual(0, run.main(['--test-post', '--out', folder]))
            self.assertEqual('false', os.environ['VK_AUTOPUBLISH_ENABLED'])
        self.assertEqual(1, vk.posts)
        self.assertEqual(1, vk.uploads)
        key = run.test_post(NOW).key
        self.assertTrue(key.startswith('test:1.'))
        self.assertEqual(key, run.test_post(NOW+timedelta(days=100)).key)
        self.assertEqual('posted', Ledger(gh, 123, PEER).locate(key)[1]['status'])

    def test_failed_preflight_never_creates_journal_or_post(self):
        class Client(FakeVK):
            def check_connection(self):
                raise SafetyError('VK messages.getConversationsById отклонил запрос (код 27)')
        vk = Client()
        env = {'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch'}
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, env, clear=True), \
                patch('run.configured_vk', return_value=vk), patch('run.github', return_value=FakeGH()), \
                patch('run.Ledger', side_effect=AssertionError('journal')), self.assertRaises(SafetyError):
            run.main(['--test-post', '--out', folder])
        self.assertEqual(0, vk.posts)
        self.assertEqual(0, vk.uploads)

    def test_failures_are_visible_in_github_annotations(self):
        import io
        from contextlib import redirect_stdout, redirect_stderr
        output = io.StringIO()
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'true'}), redirect_stdout(output), redirect_stderr(io.StringIO()):
            run.report_failure('messages.getConversationsById: отказ%\nпроверка')
        self.assertIn('::error title=VK publication::messages.getConversationsById: отказ%25%0Aпроверка', output.getvalue())

    def test_missing_illustration_stops_instead_of_fake_technical_fallback(self):
        with patch('cards.BACKGROUND', Path('/missing-approved-background.jpg')), self.assertRaises(SafetyError):
            render(POST, 'should-not-exist.png')

class ChatSafetyTest(unittest.TestCase):
    def candidate(self, peer=PEER, text=VK.SETUP_MARKER):
        return {'conversation': {**CONVERSATION, 'peer': {'id': peer, 'type': 'chat'}},
                'last_message': {'peer_id': peer, 'from_id': 42, 'text': text}}

    def test_find_chat_only_returns_id_not_private_messages_or_names(self):
        import io
        from contextlib import redirect_stdout
        private = {'conversation': {'peer': {'id': 42, 'type': 'user'}, 'title': 'PRIVATE_TITLE'},
                   'last_message': {'text': 'PRIVATE_MESSAGE'}}
        t = QueueTransport({'response': {'items': [private, self.candidate()]}})
        output = io.StringIO()
        with redirect_stdout(output):
            report = VK(123, 'SECRET', t).discover_chat()
        self.assertEqual({'group_id': 123, 'peer_id': PEER, 'sent': False}, report)
        self.assertNotIn('PRIVATE', json.dumps(report) + output.getvalue())
        self.assertEqual(1, len(t.calls))
        self.assertTrue(t.calls[0][0].endswith('/messages.getConversations'))

    def test_discovery_fail_closed_no_match_many_matches_and_scan_limit(self):
        for items in ([], [self.candidate(), self.candidate(PEER+1)]):
            t = QueueTransport({'response': {'items': items}})
            with self.assertRaises(SafetyError): VK(123, 'secret', t).discover_chat()
        t = QueueTransport(*[{'response': {'items': [self.candidate(text='private')]*100}} for _ in range(10)])
        with self.assertRaises(SafetyError): VK(123, 'secret', t).discover_chat()
        self.assertEqual(10, len(t.calls))

    def test_discovery_requires_human_marker_and_permission(self):
        candidate = self.candidate()
        candidate['last_message']['from_id'] = -123
        t = QueueTransport({'response': {'items': [candidate]}})
        with self.assertRaises(SafetyError): VK(123, 'secret', t).discover_chat()
        candidate = self.candidate()
        candidate['conversation']['can_write'] = {'allowed': False}
        t = QueueTransport({'response': {'items': [candidate]}})
        with self.assertRaises(SafetyError): VK(123, 'secret', t).discover_chat()

    def test_preflight_rejects_wrong_peer_type_or_missing_write_permission(self):
        for conversation in ({**CONVERSATION, 'peer': {'type': 'user', 'id': 123}},
                             {**CONVERSATION, 'peer': {'type': 'chat', 'id': PEER+1}},
                             {**CONVERSATION, 'can_write': {'allowed': False}},
                             {**CONVERSATION, 'can_write': {}}):
            t = QueueTransport({'response': {'items': [conversation]}})
            with self.assertRaises(SafetyError): VK(123, 'secret', t, peer=PEER).check_connection(lambda x: None)
            self.assertEqual(1, len(t.calls))

    def test_peer_bounds_and_no_wall_method_even_if_accidentally_called(self):
        for peer in (True, -123, 42, 2_000_000_000, 2**63, '2000000007'):
            with self.assertRaises(SafetyError): VK(123, 'secret', peer=peer)
            with self.assertRaises(SafetyError): Ledger(FakeGH(), 123, peer)
        t = QueueTransport()
        vk = VK(123, 'secret', t, peer=PEER)
        for method in ('wall.post', 'wall.get', 'photos.saveWallPhoto'):
            with self.assertRaises(SafetyError): vk.call(method, {})
        self.assertEqual([], t.calls)

    def test_random_id_stable_and_scoped_to_bot_and_chat(self):
        vk = VK(123, 'secret', peer=PEER)
        self.assertEqual(vk.random_id(POST), VK(123, 'other-secret', peer=PEER).random_id(POST))
        self.assertNotEqual(vk.random_id(POST), VK(124, 'secret', peer=PEER).random_id(POST))
        self.assertNotEqual(vk.random_id(POST), VK(123, 'secret', peer=PEER+1).random_id(POST))

    def test_zero_receipt_reconciled_by_conversation_message_id_without_resend(self):
        item = {'id': 0, 'conversation_message_id': 77, 'peer_id': PEER,
                'from_id': -123, 'text': POST.text}
        t = QueueTransport({'response': 0}, {'response': {'items': [item]}})
        self.assertEqual(77, VK(123, 'secret', t, peer=PEER).post(POST, 'photo-123_10_key'))
        self.assertEqual(['messages.send', 'messages.getHistory'], [c[0].rsplit('/', 1)[1] for c in t.calls])
        for data in (0, True, -1, {'message_id': 1}):
            t = QueueTransport({'response': data}, {'response': {'items': []}})
            with self.assertRaises(SafetyError): VK(123, 'secret', t, peer=PEER).post(POST, 'photo-123_10')
            self.assertEqual(1, sum(c[0].endswith('/messages.send') for c in t.calls))

    def test_history_ignores_other_authors_peers_and_partial_markers(self):
        base = {'id': 5, 'peer_id': PEER, 'from_id': -123, 'text': POST.text}
        for item in ({**base, 'from_id': 42}, {**base, 'peer_id': PEER+1},
                     {**base, 'text': POST.marker + 'suffix'}):
            t = QueueTransport({'response': {'items': [item]}})
            self.assertIsNone(VK(123, 'secret', t, peer=PEER).find(POST.marker))
        t = QueueTransport({'response': {'items': [base, {**base, 'id': 6}]}})
        with self.assertRaises(SafetyError): VK(123, 'secret', t, peer=PEER).find(POST.marker)

    def test_private_photo_access_key_sent_but_not_written_to_public_ledger(self):
        from urllib.parse import parse_qs
        t = QueueTransport({'response': {'items': []}},
                           {'response': {'upload_url': 'https://pu.vk.com/upload'}},
                           {'photo': 'private', 'server': 1, 'hash': 'private'},
                           {'response': [{'owner_id': -123, 'id': 9, 'access_key': 'PRIVATE_KEY'}]},
                           {'response': 42})
        gh = FakeGH()
        self.assertEqual((42, True), publish(POST, IMAGE, Ledger(gh, 123, PEER), VK(123, 'TOKEN', t, peer=PEER)))
        form = parse_qs(t.calls[-1][2].decode())
        self.assertEqual(['photo-123_9_PRIVATE_KEY'], form['attachment'])
        self.assertNotIn('PRIVATE_KEY', json.dumps(gh.issues)+json.dumps(gh.calls))
        self.assertNotIn('TOKEN', json.dumps(gh.issues)+json.dumps(gh.calls))
        self.assertTrue(t.calls[3][0].endswith('/photos.saveMessagesPhoto'))

    def test_wall_and_other_destination_ledgers_are_not_modified_or_reused(self):
        gh = FakeGH()
        gh.issues.append({'number': 1, 'title': '[CHRDK VK 123] 2026-11', 'body': 'legacy wall ledger'})
        vk = FakeVK()
        publish(POST, IMAGE, Ledger(gh, 123, PEER), vk)
        first_chat = copy.deepcopy(gh.issues[1])
        other = FakeVK(); other.peer = PEER+1
        publish(POST, IMAGE, Ledger(gh, 123, PEER+1), other)
        self.assertEqual(1, other.posts)
        self.assertEqual('legacy wall ledger', gh.issues[0]['body'])
        self.assertEqual(first_chat, gh.issues[1])
        self.assertEqual(3, len(gh.issues))
        with self.assertRaises(SafetyError): publish(POST, IMAGE, Ledger(gh, 123, PEER), other)
        self.assertEqual(1, other.posts)

    def test_discovery_cli_no_github_or_renderer_and_does_not_save_variables(self):
        import io
        from contextlib import redirect_stdout
        env = {'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'VK_AUTOPUBLISH_ENABLED': 'false', 'VK_NEWS_GROUP_ID': '123', 'VK_NEWS_ACCESS_TOKEN': 'secret'}
        t = QueueTransport({'response': {'items': [self.candidate()]}})
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, env, clear=True), \
                patch('run.configured_vk', return_value=VK(123, 'secret', t)), \
                patch('run.github', side_effect=AssertionError('GitHub write')), \
                patch('run.validate', side_effect=AssertionError('content')), redirect_stdout(io.StringIO()):
            self.assertEqual(0, run.main(['--find-chat', '--out', folder]))
            self.assertNotIn('VK_NEWS_PEER_ID', os.environ)
            self.assertEqual('false', os.environ['VK_AUTOPUBLISH_ENABLED'])
            self.assertEqual(PEER, json.loads((Path(folder)/'connection.json').read_text())['peer_id'])

    def test_configured_vk_requires_bot_peer_except_discovery(self):
        env = {'VK_NEWS_GROUP_ID': '123', 'VK_NEWS_ACCESS_TOKEN': 'secret'}
        with patch.dict(os.environ, env, clear=True):
            with self.assertRaises(SafetyError): run.configured_vk()
            self.assertIsNone(run.configured_vk(require_peer=False).peer)
            os.environ['VK_NEWS_PEER_ID'] = str(PEER)
            self.assertEqual(PEER, run.configured_vk().peer)


if __name__ == '__main__':
    unittest.main()
