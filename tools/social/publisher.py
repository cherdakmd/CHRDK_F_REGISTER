"""Отправка с устойчивым журналом GitHub Issues и fail-closed при неоднозначном wall.post."""
from __future__ import annotations
from datetime import datetime, timezone
from hashlib import sha256
import json
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, build_opener, HTTPRedirectHandler
import uuid
from content import SafetyError


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Transport:
    def request(self, url, method='GET', data=None, headers=None):
        # Никогда не следуем редиректу с Authorization/подписанным upload URL.
        try:
            request = Request(url, data=data, method=method, headers=headers or {})
            with build_opener(NoRedirect).open(request, timeout=35) as response:
                raw = response.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                raise SafetyError('Слишком большой ответ API')
            result = json.loads(raw)
            if not isinstance(result, (dict, list)):
                raise SafetyError('Неверный формат ответа API')
            return result
        except (HTTPError, URLError, TimeoutError, OSError, ValueError):
            # Исключения urllib содержат URL, в том числе секретный upload URL.
            raise SafetyError('Сетевая ошибка API; адрес и ответ скрыты') from None


class GitHub:
    def __init__(self, repository, token, transport=None):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
            raise SafetyError('Неверный репозиторий')
        self.repository = repository
        self.token = token
        self.transport = transport or Transport()

    def call(self, path, method='GET', data=None):
        return self.transport.request('https://api.github.com/repos/' + self.repository + '/' + path,
                                      method, json.dumps(data).encode() if data is not None else None,
                                      {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
                                       'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json'})


class Ledger:
    """Месячные журналы не переполняют Issue body; ключи ищутся и в старых журналах."""
    def __init__(self, github, group):
        self.github, self.group = github, group
        self.shards = {}
        pattern = re.compile(r'\[CHRDK VK ' + str(group) + r'\] (\d{4}-\d{2})')
        for page in range(1, 101):
            issues = github.call(f'issues?state=all&per_page=100&page={page}')
            if not isinstance(issues, list):
                raise SafetyError('Не удалось прочитать журнал')
            for issue in issues:
                match = pattern.fullmatch(issue['title'])
                if not match or 'pull_request' in issue:
                    continue
                month = match[1]
                try:
                    body = json.loads(issue['body'])
                except (ValueError, TypeError):
                    raise SafetyError('Журнал повреждён: публикация остановлена') from None
                if (month in self.shards or body.get('schema') != 1 or body.get('group') != group
                        or not isinstance(body.get('records'), dict)):
                    raise SafetyError('Конфликт или неподдерживаемая схема журнала')
                for key, record in body['records'].items():
                    if (not re.fullmatch(r'(tip:\d{4}-\d{2}-\d{2}(?::12)?|release:v1\.\d+\.\d+|test:1\.\d+\.\d+)', key)
                            or not isinstance(record, dict) or record.get('status') not in ('prepared', 'posting', 'posted')
                            or not re.fullmatch(r'[0-9a-f]{64}', record.get('digest', ''))
                            or (record['status'] == 'posted' and (type(record.get('post_id')) is not int or record['post_id'] <= 0))):
                        raise SafetyError('Неверная запись журнала')
                self.shards[month] = (issue['number'], body)
            if len(issues) < 100:
                break
        else:
            raise SafetyError('Слишком много Issues для безопасной сверки журнала')
        keys = [key for _, body in self.shards.values() for key in body['records']]
        if len(keys) != len(set(keys)):
            raise SafetyError('Один ключ найден в нескольких журналах')

    def locate(self, key):
        for month, (_, body) in self.shards.items():
            if key in body['records']:
                return month, body['records'][key]
        return None, None

    def save(self, month, key, record):
        if month not in self.shards:
            body = {'schema': 1, 'group': self.group, 'records': {}}
            issue = self.github.call('issues', 'POST', {'title': f'[CHRDK VK {self.group}] {month}',
                                                       'body': json.dumps(body)})
            self.shards[month] = (issue['number'], body)
        number, body = self.shards[month]
        # Копия: не сообщаем успех при сбое PATCH после записи.
        body = {**body, 'records': {**body['records'], key: record}}
        encoded = json.dumps(body, ensure_ascii=False, sort_keys=True, indent=2)
        if len(encoded.encode()) > 60000:
            raise SafetyError('Месячный журнал заполнен: нужна ручная проверка')
        self.github.call('issues/' + str(number), 'PATCH', {'body': encoded})
        self.shards[month] = (number, body)


class VK:
    def __init__(self, group, token, transport=None, sleeper=time.sleep):
        if type(group) is not int or group <= 0 or not token:
            raise SafetyError('Нужны положительный ID сообщества и ключ доступа VK')
        self.group, self.token = group, token
        self.transport, self.sleep = transport or Transport(), sleeper

    def call(self, method, params):
        # Повторяем только явный отказ по лимиту (6/9), но не неопределённый HTTP/timeout.
        for attempt in range(3):
            payload = {**params, 'v': '5.199', 'access_token': self.token}
            result = self.transport.request('https://api.vk.com/method/' + method, 'POST',
                                             urlencode(payload).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})
            if 'error' not in result:
                if 'response' not in result:
                    raise SafetyError('VK не подтвердил результат операции')
                return result['response']
            code = result['error'].get('error_code')
            if type(code) is not int:
                raise SafetyError('VK вернул неподдерживаемую ошибку; ответ скрыт')
            if code in (6, 9) and attempt < 2:
                self.sleep(2 ** attempt)
                continue
            label = method if method in ('wall.get', 'wall.post', 'photos.getWallUploadServer', 'photos.saveWallPhoto') else 'API'
            raise SafetyError(f'VK {label} отклонил запрос (код {code}); параметры и токен скрыты')
        raise SafetyError('VK не подтвердил операцию')

    @staticmethod
    def validate_upload_url(url):
        if not isinstance(url, str):
            raise SafetyError('VK не подтвердил адрес загрузки фото')
        parsed = urlparse(url)
        host = parsed.hostname or ''
        if (parsed.scheme != 'https' or parsed.username or parsed.password or parsed.port not in (None, 443)
                or parsed.fragment or not any(host.endswith('.' + d) for d in ('vk.com', 'userapi.com', 'vkuserphoto.ru'))):
            raise SafetyError('VK вернул недопустимый адрес загрузки фото')

    def check_connection(self, progress=print):
        """Только чтение и запрос адреса загрузки: ни фото, ни постов, ни журналов."""
        wall = self.call('wall.get', {'owner_id': -self.group, 'count': 1, 'filter': 'owner'})
        if not isinstance(wall, dict) or not isinstance(wall.get('items'), list):
            raise SafetyError('VK wall.get вернул неподдерживаемый ответ')
        progress('wall.get: OK — доступ к чтению стены подтверждён')
        server = self.call('photos.getWallUploadServer', {'group_id': self.group})
        if not isinstance(server, dict):
            raise SafetyError('VK photos.getWallUploadServer вернул неподдерживаемый ответ')
        self.validate_upload_url(server.get('upload_url'))
        progress('photos.getWallUploadServer: OK — адрес загрузки получен, фото не загружалось')
        return {'group_id': self.group, 'verified': ['wall.get', 'photos.getWallUploadServer'],
                'not_verified': ['photos.saveWallPhoto', 'wall.post'], 'published': False}

    def find(self, marker):
        # Серверная сверка после неоднозначной отправки; ограничена 1000 свежими постами.
        for offset in range(0, 1000, 100):
            data = self.call('wall.get', {'owner_id': -self.group, 'count': 100, 'offset': offset, 'filter': 'owner'})
            items = data['items']
            matches = [p['id'] for p in items if marker in p.get('text', '').splitlines()
                       and p.get('owner_id') == -self.group and type(p.get('id')) is int and p['id'] > 0]
            if len(matches) > 1:
                raise SafetyError('На стене несколько записей с одним маркером')
            if matches:
                return matches[0]
            if len(items) < 100:
                return None
        return None

    def upload(self, image):
        server = self.call('photos.getWallUploadServer', {'group_id': self.group})
        url = server['upload_url']
        self.validate_upload_url(url)
        if len(image) > 5 * 1024 * 1024 or not image.startswith(b'\x89PNG\r\n\x1a\n'):
            raise SafetyError('Неверная PNG-карточка')
        boundary = 'chrdk' + uuid.uuid4().hex
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="photo"; filename="card.png"\r\n'
                'Content-Type: image/png\r\n\r\n').encode() + image + f'\r\n--{boundary}--\r\n'.encode()
        uploaded = self.transport.request(url, 'POST', data, {'Content-Type': 'multipart/form-data; boundary=' + boundary})
        photo = self.call('photos.saveWallPhoto', {'group_id': self.group, 'photo': uploaded['photo'],
                                                  'server': uploaded['server'], 'hash': uploaded['hash']})
        if (not isinstance(photo, list) or len(photo) != 1 or type(photo[0].get('owner_id')) is not int or type(photo[0].get('id')) is not int
                or photo[0]['owner_id'] == 0 or photo[0]['id'] <= 0):
            raise SafetyError('VK не подтвердил сохранение карточки')
        return f"photo{photo[0]['owner_id']}_{photo[0]['id']}"

    def post(self, post, attachment):
        data = self.call('wall.post', {'owner_id': -self.group, 'from_group': 1, 'message': post.text,
                                       'attachments': attachment, 'guid': post.guid})
        if type(data.get('post_id')) is not int or data['post_id'] <= 0:
            raise SafetyError('VK не вернул ID опубликованной записи')
        return data['post_id']


def publish(post, image, ledger, vk):
    digest = sha256(post.text.encode() + b'\0' + image).hexdigest()
    month, record = ledger.locate(post.key)
    if record and record['status'] == 'posted':
        return record['post_id'], False
    # Прежде чем создавать новые записи, разбираем все неоднозначные отправки.
    for shard_month, (_, body) in list(ledger.shards.items()):
        for key, pending in list(body['records'].items()):
            if pending['status'] != 'posting':
                continue
            marker = '#chrdk_' + sha256(key.encode()).hexdigest()[:16]
            found = vk.find(marker)
            if not found:
                raise SafetyError('Исход прежней отправки неизвестен: проверьте стену и журнал вручную; повтор запрещён')
            ledger.save(shard_month, key, {**pending, 'status': 'posted', 'post_id': found})
    month, record = ledger.locate(post.key)
    if record and record['status'] == 'posted':
        return record['post_id'], False
    if record and record['digest'] != digest:
        raise SafetyError('Содержимое подготовленной записи изменилось: нужна ручная сверка')
    # Маркер проверяется и при первой отправке: помогает восстановлению журнала.
    found = vk.find(post.marker)
    if found:
        ledger.save(month or post.month, post.key, {'digest': digest, 'status': 'posted', 'post_id': found})
        return found, False
    attachment = record.get('attachment') if record else None
    if attachment and not re.fullmatch(r'photo-?\d+_\d+', attachment):
        raise SafetyError('Неверная карточка в журнале')
    if not attachment:
        # До загрузки фото убеждаемся, что журнал доступен на запись.
        ledger.save(month or post.month, post.key, {'digest': digest, 'status': 'prepared'})
        attachment = vk.upload(image)
    prepared = {'digest': digest, 'status': 'prepared', 'attachment': attachment}
    ledger.save(month or post.month, post.key, prepared)
    # Запись posting обязана быть подтверждена GitHub ДО wall.post.
    ledger.save(month or post.month, post.key, {**prepared, 'status': 'posting'})
    post_id = vk.post(post, attachment)
    ledger.save(month or post.month, post.key, {**prepared, 'status': 'posted', 'post_id': post_id})
    return post_id, True
