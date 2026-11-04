"""Публикация новостей релиза в MAX из GitHub Actions (офлайн-тесты без сети).

Тот же подготовленный Post, что уходит в ВК, отправляется текстом в чат/канал
MAX через POST /messages. Токен — только заголовок Authorization, никогда не URL.
Идемпотентность обеспечивает отдельный журнал в GitHub Issues: дублирование
новости при повторном запуске workflow невозможно.
"""
from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path

from content import SafetyError
from publisher import Transport

BASE = 'https://platform-api2.max.ru'
CHAT_KEY = re.compile(r'[1-9][0-9]*')
RECORD_KEY = re.compile(r'(tip:\d{4}-\d{2}-\d{2}(?::12)?|release:v1\.\d+\.\d+|test:1\.\d+\.\d+)')
DIGEST = re.compile(r'[0-9a-f]{64}')


class MAX:
    METHODS = frozenset(('GET /me', 'GET /subscriptions', 'POST /subscriptions', 'POST /messages'))

    def __init__(self, token, transport=None):
        if not token or any(ch.isspace() for ch in token):
            raise SafetyError('Нужен MAX_BOT_TOKEN в Secrets без пробелов')
        self.token = token
        self.transport = transport or Transport()

    def call(self, method, path, payload=None):
        if method not in self.METHODS:
            raise SafetyError('Этот клиент работает только с разрешёнными методами MAX')
        data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
        headers = {'Authorization': self.token, 'Accept': 'application/json'}
        if data is not None:
            headers['Content-Type'] = 'application/json; charset=UTF-8'
        return self.transport.request(BASE + '/' + path, method, data, headers)

    def check_connection(self):
        result = self.call('GET /me', 'me')
        if not isinstance(result, dict) or not result:
            raise SafetyError('MAX API не вернул данные бота')
        return {'configured': True}

    def post_text(self, chat_id, text):
        if type(chat_id) is not int or chat_id <= 0:
            raise SafetyError('Нужен положительный MAX_NEWS_CHAT_ID из события bot_added')
        clean = (text or '').strip()
        if not clean or len(clean) > 4000:
            raise SafetyError('Текст публикации пуст или слишком длинный')
        result = self.call('POST /messages', 'messages', {'chat_id': chat_id, 'text': clean})
        if not isinstance(result, dict):
            raise SafetyError('Неверный ответ MAX API')
        mid = result.get('message_id')
        if not mid and isinstance(result.get('message'), dict):
            body = result['message'].get('body')
            mid = body.get('mid') if isinstance(body, dict) else result['message'].get('mid')
        return str(mid or 'sent')


class MaxLedger:
    """Месячные журналы в Issues, независимые от VK-журнала (иная схема id)."""

    def __init__(self, github, chat_id):
        if type(chat_id) is not int or chat_id <= 0:
            raise SafetyError('Журнал MAX разрешён только для заданного chat_id')
        self.github, self.chat_id = github, chat_id
        self.shards = {}
        pattern = re.compile(r'\[CHRDK MAX NEWS ' + str(chat_id) + r'\] (\d{4}-\d{2})')
        for page in range(1, 101):
            issues = github.call(f'issues?state=all&per_page=100&page={page}')
            if not isinstance(issues, list):
                raise SafetyError('Не удалось прочитать журнал MAX')
            for issue in issues:
                match = pattern.fullmatch(issue['title'])
                if not match or 'pull_request' in issue:
                    continue
                month = match[1]
                try:
                    body = json.loads(issue['body'])
                except (ValueError, TypeError):
                    raise SafetyError('Журнал MAX повреждён: публикация остановлена') from None
                if (month in self.shards or body.get('schema') != 2 or body.get('chat') != chat_id
                        or not isinstance(body.get('records'), dict)):
                    raise SafetyError('Конфликт или неподдерживаемая схема журнала MAX')
                for key, record in body['records'].items():
                    if (not RECORD_KEY.fullmatch(key)
                            or not isinstance(record, dict)
                            or record.get('status') not in ('prepared', 'posting', 'posted')
                            or not DIGEST.fullmatch(record.get('digest', ''))
                            or (record['status'] == 'posted'
                                and not isinstance(record.get('message_id'), str))):
                        raise SafetyError('Неверная запись журнала MAX')
                self.shards[month] = (issue['number'], body)
            if len(issues) < 100:
                break
        else:
            raise SafetyError('Слишком много Issues для безопасной сверки журнала MAX')
        keys = [key for _, body in self.shards.values() for key in body['records']]
        if len(keys) != len(set(keys)):
            raise SafetyError('Один ключ найден в нескольких журналах MAX')

    def locate(self, key):
        for month, (_, body) in self.shards.items():
            if key in body['records']:
                return month, body['records'][key]
        return None, None

    def save(self, month, key, record):
        if month not in self.shards:
            body = {'schema': 2, 'chat': self.chat_id, 'records': {}}
            issue = self.github.call('issues', 'POST', {
                'title': f'[CHRDK MAX NEWS {self.chat_id}] {month}',
                'body': json.dumps(body)})
            self.shards[month] = (issue['number'], body)
        number, body = self.shards[month]
        updated = {**body, 'records': {**body['records'], key: record}}
        encoded = json.dumps(updated, ensure_ascii=False, sort_keys=True, indent=2)
        if len(encoded.encode()) > 60000:
            raise SafetyError('Месячный журнал MAX заполнен: нужна ручная проверка')
        self.github.call('issues/' + str(number), 'PATCH', {'body': encoded})
        self.shards[month] = (number, updated)


def news_text(post, release_url=''):
    """Текст для MAX: заголовок, краткая карточка и тело поста."""
    lines = [post.title.strip(), post.caption.strip(), '', post.body.strip()]
    if release_url:
        lines += ['', release_url]
    return '\n'.join(line for line in lines if line is not None).strip()


def publish_max(post, ledger, client, chat_id, release_url=''):
    text = news_text(post, release_url)
    digest = sha256(text.encode()).hexdigest()
    month, record = ledger.locate(post.key)
    if record and record['status'] == 'posted':
        return record['message_id'], False
    if record and record['status'] == 'posting':
        # У MAX нет дешёвого чтения истории для восстановления: повтор запрещён.
        raise SafetyError('MAX: исход прежней отправки неизвестен; проверьте чат и журнал вручную')
    if record and record['digest'] != digest:
        raise SafetyError('Содержимое подготовленной записи MAX изменилось: нужна ручная сверка')
    target = month or post.month
    ledger.save(target, post.key, {'digest': digest, 'status': 'prepared'})
    ledger.save(target, post.key, {'digest': digest, 'status': 'posting'})
    message_id = client.post_text(chat_id, text)
    ledger.save(target, post.key, {'digest': digest, 'status': 'posted', 'message_id': message_id})
    return message_id, True


def check_env(env, root: Path | None = None):
    """Офлайн-валидация переменных окружения. Сетевых запросов нет."""
    flag = env.get('MAX_AUTOPUBLISH_ENABLED', '')
    if flag not in ('', 'true', 'false'):
        raise SafetyError('MAX_AUTOPUBLISH_ENABLED должен быть true/false')
    token = env.get('MAX_BOT_TOKEN', '')
    if token and any(ch.isspace() for ch in token):
        raise SafetyError('MAX_BOT_TOKEN не должен содержать пробелы')
    chat = env.get('MAX_NEWS_CHAT_ID', '')
    if chat and not CHAT_KEY.fullmatch(chat):
        raise SafetyError('MAX_NEWS_CHAT_ID должен быть положительным числом')
    if flag == 'true' and (not token or not chat):
        raise SafetyError('Для публикации в MAX нужны MAX_BOT_TOKEN и MAX_NEWS_CHAT_ID')
    return {'enabled': flag == 'true', 'configured': bool(token and chat)}


def release_url_for(post):
    if not post.key.startswith('release:'):
        return ''
    return 'https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/tag/' + post.key.split(':', 1)[1]
