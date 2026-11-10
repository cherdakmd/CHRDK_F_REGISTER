#!/usr/bin/env python3
"""Новость об ОБЩЕМ пререлизе комплекта плагинов в беседу ВКонтакте.

Отдельный контур рядом с ``prerelease.py`` (тот навсегда привязан к тегам
``vk-v*`` и одному модулю CHRDK_VK). Здесь тег вида ``v1.5.2-rc.6`` и полный
комплект: ZIP + CHRDK_VK + CHRDK_Disasters.

Переиспользуются те же строительные блоки без каких-либо обходов guards:
заметка для игроков ``prerelease.note_for``, карточка ``cards.render``,
клиент ``publisher.VK`` с allowlist методов, отправка ``messages.send`` и
долговечный журнал идемпотентности ``publisher.Ledger`` в GitHub Issues.
Штатный ``run.py`` попрежнему готовит новости только по стабильному тегу, а
``content.news()`` отклоняет ``prerelease``; запрет ``wall.post`` в
Python-контуре не снимается.

По умолчанию скрипт ничего не отправляет: ``--check`` готовит текст и PNG
офлайн. Реальная отправка требует ``--publish`` плюс явное разрешение
``PRERELEASE_ANNOUNCE=true``, настроенных Secrets/Variables VK и GH_TOKEN.
"""
from __future__ import annotations

import argparse
import re
import sys
import time
from datetime import datetime, timezone

import prerelease
from content import MOSCOW, ROOT, Post, SafetyError

GENERAL_TAG = re.compile(r'v1\.\d+\.\d+-rc\.[1-9]\d*')
VERSION = re.compile(r'1\.\d+\.\d+-rc\.[1-9]\d*')
REPOSITORY = prerelease.REPOSITORY
MAX_ATTEMPTS = prerelease.MAX_ATTEMPTS
RETRY_SECONDS = prerelease.RETRY_SECONDS


class TextOnlyVK:
    """Отправка без постера: загрузчик фото не вызывается совсем.

    Для повтора после сбоя ``photos.*``: сообщение ещё не уходило (в журнале
    ``prepared``), маркер в истории не найден, поэтому текстовый анонс не
    создаёт дубль. Все проверки, журнал и ``messages.send`` остаются у обычного
    VK; перехватывается только ``upload()``.
    """

    def __init__(self, vk):
        self._vk = vk

    def __getattr__(self, name):
        return getattr(self._vk, name)

    def upload(self, image):
        return ''


def build_general_post(tag, version, root=ROOT, now=None):
    """Детерминированный Post: ключ привязан к тегу, маркер считается из ключа."""
    if (not GENERAL_TAG.fullmatch(tag or '') or not VERSION.fullmatch(version or '')
            or tag != 'v' + version):
        raise SafetyError('Нужен согласованный тег общего пререлиза вида v<version>')
    note = prerelease.note_for(version, root)
    body = f"CHERDAK · общий пререлиз {version}\n\n{note['title']}\n\n"
    body += '\n'.join('— ' + p for p in note['points'])
    body += '\n\nЭто пререлиз полного комплекта: Latest остаётся 1.5.2, стабильный выпуск не меняется.'
    body += '\nУстановка на игровой сервер и живая проверка выполняются отдельно.'
    body += '\nПодробности: https://github.com/' + REPOSITORY + '/releases/tag/' + tag
    moment = now or datetime.now(timezone.utc)
    month = moment.astimezone(MOSCOW).strftime('%Y-%m')
    return Post('prerelease:' + tag, 'feature', note['title'], note['caption'], body, month)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument('--check', action='store_true', help='Только офлайн-превью без сети и журнала')
    actions.add_argument('--publish', action='store_true', help='Отправить в беседу ВК с дедупликацией')
    parser.add_argument('--tag', required=True, help='Тег пререлиза, например v1.5.2-rc.6')
    parser.add_argument('--version', required=True, help='Версия комплекта, например 1.5.2-rc.6')
    parser.add_argument('--text-only', action='store_true',
                        help='Не загружать постер: отправить анонс текстом (для повтора после сбоя photos.*)')
    args = parser.parse_args(argv)
    post = build_general_post(args.tag, args.version)
    length, image = prerelease.check(post)
    if args.check:
        print(f'Превью общего пререлиза готово: ключ {post.key}, текст {length} символов, PNG {len(image)} байт.')
        print('Обращений к VK и записи журнала не было.')
        print(post.text)
        return 0
    gh, vk = prerelease.configured()
    if args.text_only:
        vk = TextOnlyVK(vk)
    sent, last_error = False, None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            message_id, created = prerelease.publish_announcement(
                post, image, gh=gh, vk=vk)
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
        fallback = prerelease.CardOptionalVK(vk)
        message_id, created = prerelease.publish_announcement(
            post, image, gh=gh, vk=fallback)
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
