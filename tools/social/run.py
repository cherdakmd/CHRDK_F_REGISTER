#!/usr/bin/env python3
"""CLI: по умолчанию только файлы-превью, реальная отправка требует трёх явных предохранителей."""
from __future__ import annotations
import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import quote
from content import ROOT, Post, SafetyError, catalog, due_slot, news, tip, validate, MOSCOW, TAG
from cards import render
from publisher import GitHub, Ledger, VK, publish


def tagged_news(gh, release):
    tag = release['tag_name']
    if not TAG.fullmatch(tag):
        raise SafetyError('Неверный тег')
    data = gh.call('contents/docs/social/releases/' + tag[1:] + '.json?ref=' + quote(tag, safe=''))
    if data.get('encoding') != 'base64':
        raise SafetyError('Не найдена заметка в самом теге релиза')
    try:
        note = json.loads(base64.b64decode(data['content'], validate=False))
    except (ValueError, KeyError):
        raise SafetyError('Неверный формат заметки релиза') from None
    return news(release, note=note)


def release_for_run(gh, run):
    if (run.get('conclusion') != 'success' or run.get('event') != 'release'
            or run.get('head_repository', {}).get('full_name') != gh.repository):
        raise SafetyError('Разрешён только успешный Release своего репозитория')
    candidates = []
    for release in gh.call('releases?per_page=100'):
        tag = release['tag_name']
        if release['draft'] or release['prerelease'] or not TAG.fullmatch(tag):
            continue
        obj = gh.call('git/ref/tags/' + quote(tag, safe=''))['object']
        for _ in range(5):
            if obj['type'] != 'tag':
                break
            obj = gh.call('git/tags/' + obj['sha'])['object']
        if obj['type'] == 'commit' and obj['sha'] == run['head_sha']:
            candidates.append(release)
    if len(candidates) != 1:
        raise SafetyError('Нельзя однозначно определить релиз завершившейся сборки')
    return candidates[0]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument('--check', action='store_true')
    actions.add_argument('--publish', action='store_true')
    actions.add_argument('--check-connection', action='store_true')
    actions.add_argument('--test-post', action='store_true')
    parser.add_argument('--kind', choices=('tip', 'news'), default='tip')
    parser.add_argument('--release-tag', default='')
    parser.add_argument('--news-version', help='Локальное превью заметки до выпуска релиза')
    parser.add_argument('--at', help='Дата ISO только для локального превью')
    parser.add_argument('--out', type=Path, default=ROOT / 'social-preview')
    args = parser.parse_args(argv)
    event_name = os.environ.get('GITHUB_EVENT_NAME', '')
    if args.check_connection or args.test_post:
        if (os.environ.get('GITHUB_REF') != 'refs/heads/main' or event_name != 'workflow_dispatch'
                or args.at or args.news_version or args.kind != 'tip' or args.release_tag):
            raise SafetyError('Проверка VK разрешена только вручную на main, без даты/тега/новости')
    if args.check_connection:
        report = configured_vk().check_connection()
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / 'connection.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        print('Проверка чтения пройдена. wall.post и сохранение фотографии НЕ проверялись. Публикаций не было.')
        return 0
    validate()
    if args.check:
        with tempfile.TemporaryDirectory() as folder:
            for topic in catalog():
                render(tip(datetime(2026, 11, 1, 18, tzinfo=MOSCOW), topic), Path(folder) / (topic['id']+'.png'))
            for file in (ROOT / 'docs/social/releases').glob('*.json'):
                post = preview_news(file.stem)
                render(post, Path(folder) / (file.stem + '.png'))
        print('Социальные публикации: источники, заметки и все карточки проверены.')
        return 0
    if args.publish:
        if (os.environ.get('VK_AUTOPUBLISH_ENABLED') != 'true'
                or os.environ.get('GITHUB_REF') != 'refs/heads/main'
                or event_name not in ('schedule', 'workflow_run', 'workflow_dispatch')
                or args.at or args.news_version):
            raise SafetyError('Отправка запрещена: нужен main, разрешённый запуск и VK_AUTOPUBLISH_ENABLED=true')
        if not os.environ.get('VK_NEWS_ACCESS_TOKEN') or not re.fullmatch(r'[1-9][0-9]*', os.environ.get('VK_NEWS_GROUP_ID', '')):
            raise SafetyError('Настройте ключ доступа VK и числовой ID сообщества в Secrets/Variables')
    now = datetime.fromisoformat(args.at) if args.at else datetime.now(timezone.utc)
    if args.test_post:
        post = test_post(now)
    elif event_name == 'workflow_run' and not args.news_version:
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        gh = github()
        release = release_for_run(gh, event['workflow_run'])
        post = tagged_news(gh, release)
    elif args.kind == 'news' or args.news_version:
        if args.news_version:
            post = preview_news(args.news_version)
        else:
            if not TAG.fullmatch(args.release_tag):
                raise SafetyError('Укажите стабильный тег релиза')
            gh = github()
            post = tagged_news(gh, gh.call('releases/tags/' + quote(args.release_tag, safe='')))
    else:
        slot = due_slot(now)
        if slot is None:
            if args.publish or event_name == 'schedule':
                print('Сейчас нет актуального слота 12:00/18:00 по Москве: отправки нет.')
                return 0
            # Превью не зависит от дня запуска, но в подписи/ключе не притворяется публикацией.
            slot = datetime(2026, 11, 1, 18, tzinfo=MOSCOW)
        post = tip(slot)
    if len(post.text) > 8000:
        raise SafetyError('Публикация слишком длинная')
    args.out.mkdir(parents=True, exist_ok=True)
    render(post, args.out / 'card.png')
    (args.out / 'post.txt').write_text(post.text + '\n', encoding='utf-8')
    (args.out / 'metadata.json').write_text(json.dumps({'key': post.key, 'kind': post.kind, 'month': post.month}, indent=2)+'\n')
    if not (args.publish or args.test_post):
        print('PREVIEW: текст и карточка готовы; обращений к VK и записи журнала не было.')
        return 0
    gh = github()
    vk = configured_vk()
    if args.test_post:
        vk.check_connection()
        print('Разовый тест: один настоящий пост, расписание и его флаг не изменяются.')
    ledger = Ledger(gh, vk.group)
    post_id, created = publish(post, (args.out / 'card.png').read_bytes(), ledger, vk)
    print(('Опубликовано: ' if created else 'Уже опубликовано: ') + f'https://vk.com/wall-{vk.group}_{post_id}')
    return 0


def configured_vk():
    token = os.environ.get('VK_NEWS_ACCESS_TOKEN', '')
    group = os.environ.get('VK_NEWS_GROUP_ID', '')
    if not token or not re.fullmatch(r'[1-9][0-9]*', group):
        raise SafetyError('Нужны VK_NEWS_ACCESS_TOKEN в Secrets и положительный VK_NEWS_GROUP_ID в Variables')
    return VK(int(group), token)


def test_post(now):
    version = re.search(r'^plugin_version=(1\.\d+\.\d+)$', (ROOT/'gradle.properties').read_text(), re.M)[1]
    return Post('test:'+version, 'feature', 'Твой мир. Твоя история.',
                'Полезное о CHERDAK — в новом игровом формате.',
                'Готовим больше полезного о CHERDAK 🌿\n\n'
                'Советы, возможности сервера и напоминания — с новыми игровыми карточками.\n\n'
                'Это одна тестовая публикация: проверяем картинку и отправку в группу. '
                'Автоматическое расписание этим запуском не включается.',
                now.astimezone(MOSCOW).strftime('%Y-%m'))


def report_failure(message):
    print('Публикация остановлена: ' + message, file=sys.stderr)
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        # Только контролируемые сообщения SafetyError; секретных HTTP-ответов здесь нет.
        escaped = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error title=VK publication::' + escaped)


def github():
    token = os.environ.get('GH_TOKEN', '')
    repository = os.environ.get('GITHUB_REPOSITORY', '')
    if not token or not repository:
        raise SafetyError('Для чтения релиза/журнала нужны GitHub repository и служебный токен Actions')
    return GitHub(repository, token)


def preview_news(version):
    if not re.fullmatch(r'1\.\d+\.\d+', version):
        raise SafetyError('Неверная версия превью')
    return news({'tag_name': 'v'+version, 'draft': False, 'prerelease': False,
                 'assets': [{'name': f'chrdk-plugins-{version}.zip', 'size': 1, 'state': 'uploaded'}],
                 'published_at': '2026-11-01T15:00:00Z',
                 'html_url': 'https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/tag/v'+version})


if __name__ == '__main__':
    try:
        sys.exit(main())
    except SafetyError as error:
        report_failure(str(error))
        sys.exit(1)
    except Exception:
        # Не печатаем traceback: HTTP/upload-ответы могут содержать секретные параметры.
        report_failure('Неподдерживаемый ответ или конфигурация; секретные детали скрыты.')
        sys.exit(1)
