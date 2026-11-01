"""Отправка с устойчивым журналом GitHub Issues и fail-closed при неоднозначном messages.send."""
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
    def __init__(self, github, group, peer):
        if type(group) is not int or group <= 0 or type(peer) is not int or not 2_000_000_000 < peer <= 9_223_372_036_854_775_807:
            raise SafetyError('Журнал разрешён только для заданных сообщества и беседы')
        self.github, self.group, self.peer = github, group, peer
        self.shards = {}
        pattern = re.compile(r'\[CHRDK VK CHAT ' + str(group) + ' ' + str(peer) + r'\] (\d{4}-\d{2})')
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
                if (month in self.shards or body.get('schema') != 2 or body.get('group') != group or body.get('peer') != peer
                        or not isinstance(body.get('records'), dict)):
                    raise SafetyError('Конфликт или неподдерживаемая схема журнала')
                for key, record in body['records'].items():
                    if (not re.fullmatch(r'(tip:\d{4}-\d{2}-\d{2}(?::12)?|release:v1\.\d+\.\d+|test:1\.\d+\.\d+)', key)
                            or not isinstance(record, dict) or record.get('status') not in ('prepared', 'posting', 'posted')
                            or not re.fullmatch(r'[0-9a-f]{64}', record.get('digest', ''))
                            or 'attachment' in record
                            or (record['status'] == 'posted' and (type(record.get('message_id')) is not int or record['message_id'] <= 0))):
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
            body = {'schema': 2, 'group': self.group, 'peer': self.peer, 'records': {}}
            issue = self.github.call('issues', 'POST', {'title': f'[CHRDK VK CHAT {self.group} {self.peer}] {month}',
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
    METHODS = frozenset(('messages.getConversations', 'messages.getConversationsById',
                         'messages.getHistory', 'messages.send', 'photos.getMessagesUploadServer',
                         'photos.saveMessagesPhoto'))
    SETUP_MARKER = '#chrdk-news-setup'

    def __init__(self, group, token, transport=None, sleeper=time.sleep, *, peer=None):
        if type(group) is not int or group <= 0 or not token:
            raise SafetyError('Нужны положительный ID сообщества и ключ сообщества VK')
        if peer is not None and (type(peer) is not int or not 2_000_000_000 < peer <= 9_223_372_036_854_775_807):
            raise SafetyError('VK_NEWS_PEER_ID должен быть peer_id беседы для бота, больше 2000000000')
        self.group, self.token, self.peer = group, token, peer
        self.transport, self.sleep = transport or Transport(), sleeper

    def require_peer(self):
        if self.peer is None:
            raise SafetyError('Укажите VK_NEWS_PEER_ID; сначала можно выполнить find_chat')

    def call(self, method, params):
        if method not in self.METHODS:
            raise SafetyError('Этот клиент работает только с беседами; метод не разрешён')
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
            raise SafetyError(f'VK {method} отклонил запрос (код {code}); параметры и токен скрыты')
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

    @staticmethod
    def conversation_peer(conversation):
        peer = conversation.get('peer', {})
        value = peer.get('id')
        if peer.get('type') != 'chat' or type(value) is not int or not 2_000_000_000 < value <= 9_223_372_036_854_775_807:
            raise SafetyError('Получатель должен быть беседой, не личной страницей или сообществом')
        return value

    def discover_chat(self):
        """Находим только беседу с явно написанным маркером; чужие тексты/имена не выводим."""
        found = set()
        for offset in range(0, 1000, 100):
            data = self.call('messages.getConversations', {'group_id': self.group, 'filter': 'all',
                                                          'count': 100, 'offset': offset})
            if not isinstance(data, dict) or not isinstance(data.get('items'), list):
                raise SafetyError('VK не подтвердил список бесед')
            for item in data['items']:
                last = item.get('last_message', {})
                if last.get('text', '').strip() != self.SETUP_MARKER:
                    continue
                conversation = item.get('conversation', {})
                peer = self.conversation_peer(conversation)
                if last.get('peer_id') != peer or type(last.get('from_id')) is not int or last['from_id'] <= 0:
                    continue
                if conversation.get('can_write', {}).get('allowed') is not True:
                    raise SafetyError('Беседа с маркером найдена, но бот не может писать: проверьте права в беседе')
                found.add(peer)
            if len(data['items']) < 100:
                break
        else:
            raise SafetyError('Слишком много бесед для однозначной проверки; укажите peer_id вручную')
        if len(found) != 1:
            raise SafetyError('Нужна ровно одна беседа с последним сообщением #chrdk-news-setup; отправьте маркер и повторите find_chat')
        return {'group_id': self.group, 'peer_id': found.pop(), 'sent': False}

    def history(self, offset=0, count=100):
        self.require_peer()
        data = self.call('messages.getHistory', {'group_id': self.group, 'peer_id': self.peer,
                                                'count': count, 'offset': offset})
        if not isinstance(data, dict) or not isinstance(data.get('items'), list):
            raise SafetyError('VK messages.getHistory вернул неподдерживаемый ответ')
        return data['items']

    def check_connection(self, progress=print):
        self.require_peer()
        data = self.call('messages.getConversationsById', {'group_id': self.group, 'peer_ids': self.peer})
        if not isinstance(data, dict) or not isinstance(data.get('items'), list) or len(data['items']) != 1:
            raise SafetyError('VK не подтвердил выбранную беседу')
        conversation = data['items'][0]
        if self.conversation_peer(conversation) != self.peer:
            raise SafetyError('VK вернул другую беседу: отправка запрещена')
        if conversation.get('can_write', {}).get('allowed') is not True:
            raise SafetyError('Бот не может писать в беседу: добавьте сообщество и проверьте его права')
        progress('messages.getConversationsById: OK — выбрана нужная беседа, бот может писать')
        self.history(count=1)
        progress('messages.getHistory: OK — история доступна для защиты от дублей')
        server = self.call('photos.getMessagesUploadServer', {'peer_id': self.peer})
        if not isinstance(server, dict):
            raise SafetyError('VK photos.getMessagesUploadServer вернул неподдерживаемый ответ')
        self.validate_upload_url(server.get('upload_url'))
        progress('photos.getMessagesUploadServer: OK — адрес получен, файл не загружался')
        return {'group_id': self.group, 'peer_id': self.peer,
                'verified': ['messages.getConversationsById', 'messages.getHistory', 'photos.getMessagesUploadServer'],
                'not_verified': ['photos.saveMessagesPhoto', 'messages.send'], 'sent': False}

    def find(self, marker):
        for offset in range(0, 1000, 100):
            items = self.history(offset)
            matches = []
            for message in items:
                if (message.get('peer_id') != self.peer or message.get('from_id') != -self.group
                        or marker not in message.get('text', '').splitlines()):
                    continue
                receipt = message.get('id')
                if type(receipt) is not int or receipt <= 0:
                    receipt = message.get('conversation_message_id')
                if type(receipt) is not int or receipt <= 0:
                    raise SafetyError('Сообщение с маркером найдено без подтверждённого идентификатора')
                matches.append(receipt)
            if len(matches) > 1:
                raise SafetyError('В беседе несколько сообщений с одним маркером: нужна ручная сверка')
            if matches:
                return matches[0]
            if len(items) < 100:
                return None
        return None

    def upload(self, image):
        self.require_peer()
        server = self.call('photos.getMessagesUploadServer', {'peer_id': self.peer})
        url = server['upload_url']
        self.validate_upload_url(url)
        if len(image) > 5 * 1024 * 1024 or not image.startswith(b'\x89PNG\r\n\x1a\n'):
            raise SafetyError('Неверная PNG-карточка')
        boundary = 'chrdk' + uuid.uuid4().hex
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="photo"; filename="card.png"\r\n'
                'Content-Type: image/png\r\n\r\n').encode() + image + f'\r\n--{boundary}--\r\n'.encode()
        uploaded = self.transport.request(url, 'POST', data, {'Content-Type': 'multipart/form-data; boundary=' + boundary})
        photo = self.call('photos.saveMessagesPhoto', {'photo': uploaded['photo'],
                                                      'server': uploaded['server'], 'hash': uploaded['hash']})
        if (not isinstance(photo, list) or len(photo) != 1 or type(photo[0].get('owner_id')) is not int
                or type(photo[0].get('id')) is not int or photo[0]['owner_id'] == 0 or photo[0]['id'] <= 0):
            raise SafetyError('VK не подтвердил сохранение карточки для сообщения')
        attachment = f"photo{photo[0]['owner_id']}_{photo[0]['id']}"
        if 'access_key' in photo[0]:
            key = photo[0]['access_key']
            if not isinstance(key, str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,256}', key):
                raise SafetyError('Неверный ключ доступа фотографии')
            attachment += '_' + key
        return attachment

    def random_id(self, post):
        self.require_peer()
        # Стабилен для повторов, привязан к боту, беседе и ключу публикации.
        seed = f'{self.group}:{self.peer}:{post.key}'.encode()
        return int.from_bytes(sha256(seed).digest()[:4], 'big') % 2147483647 + 1

    def post(self, post, attachment):
        self.require_peer()
        if len(post.text) > 4000:
            raise SafetyError('Сообщение длиннее лимита 4000 символов: сократите текст, не разбивайте отправку вслепую')
        data = self.call('messages.send', {'group_id': self.group, 'peer_id': self.peer,
                                          'random_id': self.random_id(post), 'message': post.text,
                                          'attachment': attachment, 'dont_parse_links': 1})
        if type(data) is int and data == 0:
            # В беседах VK может вернуть 0: подтверждаем только сообщением бота в истории.
            found = self.find(post.marker)
            if found:
                return found
        if type(data) is not int or data <= 0:
            raise SafetyError('VK не вернул подтверждение отправки; повторять без сверки истории нельзя')
        return data


def publish(post, image, ledger, vk):
    if ledger.group != vk.group or ledger.peer != vk.peer:
        raise SafetyError('Журнал и получатель не совпадают: отправка запрещена')
    digest = sha256(post.text.encode() + b'\0' + image).hexdigest()
    month, record = ledger.locate(post.key)
    if record and record['status'] == 'posted':
        return record['message_id'], False
    # Прежде чем создавать новые записи, разбираем все неоднозначные отправки.
    for shard_month, (_, body) in list(ledger.shards.items()):
        for key, pending in list(body['records'].items()):
            if pending['status'] != 'posting':
                continue
            marker = '#chrdk_' + sha256(key.encode()).hexdigest()[:16]
            found = vk.find(marker)
            if not found:
                raise SafetyError('Исход прежней отправки неизвестен: проверьте беседу и журнал вручную; повтор запрещён')
            ledger.save(shard_month, key, {**pending, 'status': 'posted', 'message_id': found})
    month, record = ledger.locate(post.key)
    if record and record['status'] == 'posted':
        return record['message_id'], False
    if record and record['digest'] != digest:
        raise SafetyError('Содержимое подготовленной записи изменилось: нужна ручная сверка')
    # Маркер проверяется и при первой отправке: помогает восстановлению журнала.
    found = vk.find(post.marker)
    if found:
        ledger.save(month or post.month, post.key, {'digest': digest, 'status': 'posted', 'message_id': found})
        return found, False
    # access_key приватной фотографии не должен оказаться в публичном Issue.
    # После сбоя до отправки безопасно загрузить фото ещё раз; сообщения при этом нет.
    ledger.save(month or post.month, post.key, {'digest': digest, 'status': 'prepared'})
    attachment = vk.upload(image)
    prepared = {'digest': digest, 'status': 'prepared'}
    # Запись posting обязана быть подтверждена GitHub ДО messages.send.
    ledger.save(month or post.month, post.key, {**prepared, 'status': 'posting'})
    message_id = vk.post(post, attachment)
    ledger.save(month or post.month, post.key, {**prepared, 'status': 'posted', 'message_id': message_id})
    return message_id, True
