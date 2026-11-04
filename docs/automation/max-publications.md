# MAX: бот, webhook и автопостинг новостей

Документ для администратора. Здесь нет секретов: токены задаются в Secrets/
Variables репозитория и в `plugins/CHRDK_VK/config.yml` на сервере, в репозиторий
не попадают. Живая проверка не выполнялась — это инструкция по включению.

## 1. Что нужно от платформы MAX

MAX Bot API доступен юридическим лицам, ИП и самозанятым — резидентам РФ с
**верифицированным профилем организации**. Бот создаётся в «MAX для партнёров»,
проходит модерацию, после чего становится доступен пользователям. Без этого
пункта токен получить нельзя; код и офлайн-тесты при этом работают полностью.

Актуальная база API — `https://platform-api2.max.ru/` (с 19 июля 2026 года).
Токен передаётся только заголовком `Authorization`, передача в query больше не
поддерживается. Лимиты: 30 RPS суммарно и 2 сообщения в секунду в один чат.
`GET /chats` не поддерживается с июня 2026 года: `chat_id` канала/беседы нужно
поймать из событий `bot_added` / `bot_started`.

## 2. Webhook на отдельном порту хостинга

Плагин поднимает собственный приёмник, посредник не нужен:

```yaml
max:
  enabled: true
  api-base-url: "https://platform-api2.max.ru/"
  access-token: "<токен бота>"
  transport: webhook
  update-types: [message_created, message_callback, bot_started, bot_added, bot_removed]
  webhook:
    enabled: true
    port: 8443
    bind-address: "0.0.0.0"
    path: "/max/webhook"
    public-url: "https://bot.example.ru/max/webhook"
    secret: "<длинный случайный секрет>"
    keystore: "max-webhook.p12"
    keystore-password: "<пароль keystore>"
    require-tls: true
```

Порядок:

1. Выдайте на хостинге отдельный порт и направьте на него домен с **доверенным
   HTTPS-сертификатом** (reverse-proxy или сам плагин — см. ниже). MAX с 25 мая
   не принимает webhook по HTTP и самоподписные сертификаты.
2. Соберите PKCS12-keystore с сертификатом и закрытым ключом, положите
   `max-webhook.p12` в `plugins/CHRDK_VK/`. Тогда плагин сам поднимет
   `SSLServerSocket` на указанном порту. Если TLS отдаёт внешний reverse-proxy,
   можно снять `require-tls` — но публичный адрес всё равно обязан быть HTTPS.
3. Задайте `public-url` и `secret`. При старте плагин вызовет
   `POST /subscriptions` с этими значениями и набором `update_types`.
4. Проверьте лог: `MAX webhook слушает ... (HTTPS)`. Если keystore не найден,
   плагин **откажется** поднимать незащищённый webhook и уйдёт в long polling
   (`max.polling.enabled`) с предупреждением. Polling официально допускается
   только для разработки — в продакшене доведите HTTPS.

Входящий запрос проверяется constant-time по secret из заголовка, тело
ограничено 64 КБ, `HTTP 200` отправляется до обработки (MAX ждёт ответ не дольше
30 секунд), некорректный JSON не роняет приёмник.

## 3. Как получить chat_id

1. Добавьте бота в нужный чат/канал или откройте с ним диалог.
2. Событие `bot_added` / `bot_started` сохранит `chat_id` — посмотрите его
   командой `/max chats` (или `/social chats`) и в
   `logs/social/social-YYYY-MM-DD.jsonl`.
3. Зафиксируйте назначение:
   `/social setchat news <id>` — новости релизов,
   `/social setchat bridge <id>` — мост игрового чата,
   `/social setchat bot <id>` — чат по умолчанию.

## 4. Автопостинг из GitHub Actions

Workflow `VK news and server tips` готовит тот же `Post`, что и для ВК, и при
включённом флаге отправляет текст в MAX через `POST /messages`.

| Переменная | Где | Назначение |
| --- | --- | --- |
| `MAX_AUTOPUBLISH_ENABLED` | Variables | `true` разрешает реальную отправку |
| `MAX_BOT_TOKEN` | Secrets | токен бота MAX |
| `MAX_NEWS_CHAT_ID` | Variables | `chat_id` канала/чата из п. 3 |

Дополнительно нужны уже существующие `VK_AUTOPUBLISH_ENABLED`,
`VK_NEWS_GROUP_ID`, `VK_NEWS_PEER_ID`, `VK_NEWS_ACCESS_TOKEN`: публикация в MAX
привязана к тому же доверенному запуску на `main` и не обходит guard'ы ВК.

Идемпотентность обеспечивает отдельный журнал в GitHub Issues с заголовком
`[CHRDK MAX NEWS <chat_id>] YYYY-MM`: записи `prepared → posting → posted`.
Если предыдущая отправка зависла в `posting`, повтор **запрещён** — нужна ручная
сверка чата и журнала. VK-журнал при этом не изменяется, схемы независимы.

Офлайн-проверка без сети и без секретов:

```bash
python3 tools/social/run.py --check
python3 -m unittest discover -s tools/social -p 'test_*.py'
```

`--check` рендерит все карточки, сверяет заметки со свежестью вики и проверяет
формат MAX-переменных. Успешный `--check` **не означает**, что пост отправлен.

## 5. Автопостинг из плагина

```yaml
news:
  enabled: true
  poll-minutes: 30
  announce-in-game: true
  template: "🚀 CHERDAK — выпуск %tag%: %title%\nПодробности и скачивание: %url%"
  release-api-url: "https://api.github.com/repos/cherdakmd/CHRDK_F_REGISTER/releases/latest"
```

Плагин раз в `poll-minutes` читает последний стабильный GitHub Release,
пропускает черновики и пререлизы, один раз на тег шлёт анонс в MAX-канал,
беседу ВК и игровой чат. `lastNewsTag` хранится в `social-links.json`, поэтому
рестарт не создаёт дубль. `/social testnews` запускает проверку вручную.

Два канала не конфликтуют: Actions публикует полную карточку с картинкой,
плагин — короткий анонс. Если нужен только один, выключите соответствующий
флаг (`MAX_AUTOPUBLISH_ENABLED` или `news.enabled`).

## 6. Границы

- Живые запросы к MAX, установка сертификата, публикация бота в каталоге и
  модерация **не выполнялись** в этом цикле: нет верифицированного профиля.
- Токен, secret и пароль keystore не должны попадать в репозиторий, логи или
  Issue. Клиенты MAX скрывают URL и тела ошибок.
- Успешный CI/on-line лог `Social automation (offline)` не доказывает постинг.
- RCON, Minecraft-клиент, поведение webhook за конкретным reverse-proxy и
  production MSPT не проверялись.
