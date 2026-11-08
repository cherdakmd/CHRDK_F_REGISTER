#!/usr/bin/env python3
"""Новость о пререлизе отдельного аддона CHRDK_VK в беседу ВКонтакте.

Штатный ``run.py`` намеренно готовит новости только для опубликованного
стабильного релиза комплекта: ``content.news()`` отклоняет ``prerelease`` и
требует ассет ``chrdk-plugins-<version>.zip``. Пререлиз одного модуля под
это правило не подходит, поэтому здесь отдельный, явно включаемый контур.

Используются те же строительные блоки, что и у стабильных новостей: карточка
``cards.render``, клиент ``publisher.VK`` с allowlist методов, отправка
``messages.send`` и долговечный журнал идемпотентности ``publisher.Ledger``
в GitHub Issues. Запрет ``wall.post`` в Python-контуре не снимается.

По умолчанию скрипт ничего не отправляет: ``--check`` готовит текст и PNG
офлайн. Реальная отправка требует ``--publish`` плюс трёх предохранителей:
``PRERELEASE_ANNOUNCE=true``, настроенных Secrets/Variables VK и GH_TOKEN.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

from cards import render
from content import MOSCOW, ROOT, Post, SafetyError
from publisher import GitHub, Ledger, Transport, VK, publish

TAG = re.compile(r'vk-v1\.\d+\.\d+-rc\.[1-9]\d*')
VERSION = re.compile(r'1\.\d+\.\d+-rc\.[1-9]\d*')
REPOSITORY = 'cherdakmd/CHRDK_F_REGISTER'
MAX_ATTEMPTS = 3
RETRY_SECONDS = 15


class RecordingTransport(Transport):
    """Запоминает только имена полей ответа загрузчика, никогда не значения."""

    def __init__(self):
        super().__init__()
        self.upload_keys = None

    def request(self, url, method='GET', data=None, headers=None):
        result = super().request(url, method, data, headers)
        if method == 'POST' and 'multipart/form-data' in (headers or {}).get('Content-Type', ''):
            self.upload_keys = (sorted(result.keys()) if isinstance(result, dict)
                               else [type(result).__name__])
        return result


class CardOptionalVK:
    """Если загрузчик ВК не подтвердил фото, новость уходит текстом.

    Все проверки, маркер, журнал и messages.send остаются у обычного VK:
    перехватывается только отказ upload(), поэтому дубль невозможен.
    """

    def __init__(self, vk):
        self._vk = vk
        self.card = True

    def __getattr__(self, name):
        return getattr(self._vk, name)

    def upload(self, image):
        try:
            return self._vk.upload(image)
        except SafetyError as error:
            self.card = False
            print('Карточка не загружена, отправляем новость текстом: ' + str(error),
                  file=sys.stderr)
            return ''


def note_for(version, root=ROOT):
    """Читает заметку для игроков с теми же ограничениями объёма, что и news()."""
    if not VERSION.fullmatch(version or ''):
        raise SafetyError('Неверная версия пререлиза')
    path = Path(root) / 'docs' / 'social' / 'prereleases' / (version + '.json')
    if not path.is_file():
        raise SafetyError('Нет отдельной заметки для игроков о пререлизе')
    try:
        note = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError) as error:
        raise SafetyError('Заметка пререлиза не читается') from error
    if (not isinstance(note, dict) or note.get('version') != version
            or not isinstance(note.get('points'), list) or not 1 <= len(note['points']) <= 6
            or any(not isinstance(p, str) or not p.strip() or len(p) > 600 for p in note['points'])
            or any(not isinstance(note.get(k), str) or not note[k].strip() or len(note[k]) > limit
                   for k, limit in (('title', 100), ('caption', 180)))):
        raise SafetyError('Неверная версия или объём заметки пререлиза')
    return note


def build_post(tag, version, root=ROOT, now=None):
    """Детерминированный Post: ключ привязан к тегу, маркер считается из ключа."""
    if (not TAG.fullmatch(tag or '') or not VERSION.fullmatch(version or '')
            or tag != 'vk-v' + version):
        raise SafetyError('Нужен согласованный тег вида vk-v<version>')
    note = note_for(version, root)
    body = f"CHERDAK · пререлиз CHRDK_VK {version}\n\n{note['title']}\n\n"
    body += '\n'.join('— ' + p for p in note['points'])
    body += '\n\nЭто пререлиз только модуля CHRDK_VK: стабильный комплект 1.5.2 не меняется.'
    body += '\nУстановка на игровой сервер выполняется отдельно и требует прав ключа wall + photos.'
    body += '\nПодробности: https://github.com/' + REPOSITORY + '/releases/tag/' + tag
    moment = now or datetime.now(timezone.utc)
    month = moment.astimezone(MOSCOW).strftime('%Y-%m')
    return Post('prerelease:' + tag, 'feature', note['title'], note['caption'], body, month)


def render_bytes(post):
    """PNG карточки в памяти; заодно срабатывают overflow-guard'ы cards.render."""
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / 'card.png'
        render(post, path)
        return path.read_bytes()


def check(post):
    if len(post.text) > 4000:
        raise SafetyError('Публикация слишком длинная')
    image = render_bytes(post)
    if not image.startswith(b'\x89PNG\r\n\x1a\n'):
        raise SafetyError('Карточка пререлиза не закодирована в PNG')
    return len(post.text), image


def configured():
    """Явное разрешение + Secrets/Variables. Без этого отправки не будет."""
    if os.environ.get('PRERELEASE_ANNOUNCE') != 'true':
        raise SafetyError('Отправка новости пререлиза не включена явно')
    token = os.environ.get('VK_NEWS_ACCESS_TOKEN', '')
    group = os.environ.get('VK_NEWS_GROUP_ID', '')
    peer = os.environ.get('VK_NEWS_PEER_ID', '')
    gh_token = os.environ.get('GH_TOKEN', '')
    repository = os.environ.get('GITHUB_REPOSITORY', '')
    if not token or not re.fullmatch(r'[1-9][0-9]*', group):
        raise SafetyError('Нужны VK_NEWS_ACCESS_TOKEN и положительный VK_NEWS_GROUP_ID')
    if not re.fullmatch(r'[1-9][0-9]*', peer) or not 2_000_000_000 < int(peer) <= 9_223_372_036_854_775_807:
        raise SafetyError('Нужен VK_NEWS_PEER_ID беседы для бота')
    if not gh_token or not repository:
        raise SafetyError('Нужны GH_TOKEN и GITHUB_REPOSITORY для журнала дедупликации')
    return GitHub(repository, gh_token), VK(int(group), token, peer=int(peer),
                                           transport=RecordingTransport())


def publish_announcement(post, image, *, gh, vk, ledger=None):
    """Та же идемпотентная отправка, что и у стабильных новостей."""
    vk.check_connection()
    return publish(post, image, ledger if ledger is not None else Ledger(gh, vk.group, vk.peer), vk)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument('--check', action='store_true', help='Только офлайн-превью без сети и журнала')
    actions.add_argument('--publish', action='store_true', help='Отправить в беседу ВК с дедупликацией')
    parser.add_argument('--tag', required=True, help='Тег пререлиза, например vk-v1.5.2-rc.5')
    parser.add_argument('--version', required=True, help='Версия JAR, например 1.5.2-rc.5')
    args = parser.parse_args(argv)
    post = build_post(args.tag, args.version)
    length, image = check(post)
    if args.check:
        print(f'Превью пререлиза готово: ключ {post.key}, текст {length} символов, PNG {len(image)} байт.')
        print('Обращений к VK и записи журнала не было.')
        print(post.text)
        return 0
    gh, vk = configured()
    sent, last_error = False, None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            message_id, created = publish_announcement(post, image, gh=gh, vk=vk)
            sent = True
            break
        except SafetyError as error:
            last_error = error
            keys = getattr(getattr(vk, 'transport', None), 'upload_keys', None)
            if keys:
                print('Ответ загрузчика (только имена полей): ' + ', '.join(keys), file=sys.stderr)
            print(f'Попытка {attempt} из {MAX_ATTEMPTS} с карточкой не удалась: {error}',
                  file=sys.stderr)
            if attempt < MAX_ATTEMPTS:
                time.sleep(RETRY_SECONDS)
    if not sent:
        # Текстовый фолбэк только когда загрузчик реально ответил, но не дал
        # photo/hash/server. Сетевые сбои и ошибки журнала так не маскируем.
        if getattr(getattr(vk, 'transport', None), 'upload_keys', None) is None:
            raise last_error
        print(f'Отправляем новость текстом без постера: {last_error}', file=sys.stderr)
        fallback = CardOptionalVK(vk)
        message_id, created = publish_announcement(post, image, gh=gh, vk=fallback)
        if not fallback.card:
            print('Внимание: постер не прикреплён, ВК не подтвердил загрузку фото.')
    print(('Отправлено в беседу: ' if created else 'Уже отправлено в беседу: ') + str(vk.peer)
          + f' (id {message_id})')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except SafetyError as error:
        print('Публикация остановлена: ' + str(error), file=sys.stderr)
        sys.exit(1)
