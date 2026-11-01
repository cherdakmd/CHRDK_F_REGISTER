"""Публичные тексты: только проверенные выдержки вики и отдельные заметки игрокам."""
from __future__ import annotations
from dataclasses import dataclass
from datetime import datetime, timedelta
from hashlib import sha256
import json
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/wiki'))
from facts import FactBook
from freshness import check as freshness_check

MOSCOW = ZoneInfo('Europe/Moscow')
TAG = re.compile(r'v1\.\d+\.\d+')


class SafetyError(RuntimeError):
    """Ожидаемая безопасная остановка, без секретов/HTTP-адресов в сообщении."""


@dataclass(frozen=True)
class Post:
    key: str
    kind: str
    title: str
    caption: str
    body: str
    month: str

    @property
    def guid(self):
        return sha256(self.key.encode()).hexdigest()[:32]

    @property
    def marker(self):
        return '#chrdk_' + self.guid[:16]

    @property
    def text(self):
        return self.body.rstrip() + '\n\n' + self.marker


def normalize(text):
    return ' '.join(text.split())


def plain(text):
    # Ссылки остаются кликабельными URL, команды с аргументами не считаются HTML.
    text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'\1 (\2)', text)
    return re.sub(r'[`*]', '', text).strip()


def due_slot(now: datetime):
    if now.tzinfo is None:
        raise SafetyError('Нужна дата с часовым поясом')
    local = now.astimezone(MOSCOW)
    for days in range(8):
        slot = (local - timedelta(days=days)).replace(hour=18, minute=0, second=0, microsecond=0)
        if slot.weekday() in (2, 6) and slot <= local:
            # Допускаем задержку Actions, но не недельный догоняющий спам.
            return slot if local - slot <= timedelta(hours=24) else None
    return None


def topic_index(slot):
    sunday = slot.date() - timedelta(days=(slot.weekday() + 1) % 7)
    anchor = datetime(2026, 11, 1).date()
    return ((sunday - anchor).days // 7) * 2 + (slot.weekday() == 2)


def catalog(root=ROOT):
    topics = json.loads((root / 'tools/social/topics.json').read_text(encoding='utf-8'))
    if not topics or len({t['id'] for t in topics}) != len(topics):
        raise SafetyError('Каталог тем пуст или содержит повторяющиеся ID')
    return topics


def tip(slot, topic=None, root=ROOT):
    topics = catalog(root)
    topic = topic or topics[topic_index(slot) % len(topics)]
    slug = topic['source']
    if not re.fullmatch(r'[a-z][a-z-]*', slug):
        raise SafetyError('Неверный источник совета')
    source = root / 'wiki' / (slug + '.md')
    if not source.is_file():
        raise SafetyError('Страница совета удалена: нужна сверка каталога')
    excerpt = topic['excerpt']
    if normalize(excerpt) not in normalize(source.read_text(encoding='utf-8')):
        raise SafetyError('Выдержка совета устарела: нужна сверка с вики')
    book = FactBook(root)
    text = re.sub(r'\{\{fact:([a-z_]+)\}\}', lambda m: str(book.value(m[1])), excerpt)
    if '{{' in text or '{{' in topic['caption']:
        raise SafetyError('Не раскрыты публичные факты совета')
    site = yaml.safe_load((root / 'wiki/config.yml').read_text(encoding='utf-8'))['site']['url']
    label = {'tip': 'Совет', 'feature': 'Возможности сервера', 'reminder': 'Напоминание'}[topic['kind']]
    body = f"{label} CHERDAK · {topic['title']}\n\n{plain(text)}\n\nПодробнее: {site.rstrip('/')}/{slug}.html"
    body += '\n\nПо стандартным настройкам проекта. Доступность и точные условия проверяйте в игре.'
    return Post('tip:' + slot.date().isoformat(), topic['kind'], topic['title'], topic['caption'], body, slot.strftime('%Y-%m'))


def news(release, root=ROOT, note=None):
    tag = release['tag_name']
    if not TAG.fullmatch(tag) or release['draft'] or release['prerelease']:
        raise SafetyError('Нужен опубликованный стабильный релиз плагинов')
    archive = f'chrdk-plugins-{tag[1:]}.zip'
    if not any(a['name'] == archive and a.get('size', 0) > 0 and a.get('state') == 'uploaded'
               for a in release.get('assets', [])):
        raise SafetyError('Основной ZIP ещё не загружен: новость не публикуется')
    path = root / 'docs/social/releases' / (tag[1:] + '.json')
    if note is None:
        if not path.is_file():
            raise SafetyError('Нет отдельной заметки для игроков: технический changelog не публикуется')
        note = json.loads(path.read_text(encoding='utf-8'))
    if (not isinstance(note, dict) or note.get('version') != tag[1:]
            or not isinstance(note.get('points'), list) or not 1 <= len(note['points']) <= 6
            or any(not isinstance(p, str) or not p.strip() or len(p) > 600 for p in note['points'])
            or any(not isinstance(note.get(k), str) or not note[k].strip() or len(note[k]) > limit
                   for k, limit in (('title', 100), ('caption', 180)))):
        raise SafetyError('Неверная версия или объём новости')
    body = f"CHERDAK · вышла версия {tag[1:]}\n\n{note['title']}\n\n"
    body += '\n'.join('— ' + p for p in note['points'])
    # Статус установки не выводится из выпуска исходников/ZIP.
    body += '\n\nЭто выпуск комплекта. Установка на игровой сервер выполняется отдельно.'
    body += '\nПодробности: ' + release['html_url']
    date = datetime.fromisoformat(release['published_at'].replace('Z', '+00:00')).astimezone(MOSCOW)
    return Post('release:' + tag, 'news', note['title'], note['caption'], body, date.strftime('%Y-%m'))


def validate(root=ROOT):
    problems = freshness_check(root)
    if problems:
        raise SafetyError('Вики требует сверки: публикация остановлена')
    for topic in catalog(root):
        tip(datetime(2026, 11, 1, 18, tzinfo=MOSCOW), topic, root)
    version = re.search(r'^plugin_version=(.+)$', (root / 'gradle.properties').read_text(), re.M)[1]
    if not (root / 'docs/social/releases' / (version + '.json')).is_file():
        raise SafetyError('Для текущей версии нужна краткая заметка игрокам')
    for path in (root / 'docs/social/releases').glob('*.json'):
        tag = 'v' + path.stem
        news({'tag_name': tag, 'draft': False, 'prerelease': False,
              'assets': [{'name': f'chrdk-plugins-{tag[1:]}.zip', 'size': 1, 'state': 'uploaded'}],
              'published_at': '2026-11-01T15:00:00Z', 'html_url': 'https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/tag/' + tag}, root)
