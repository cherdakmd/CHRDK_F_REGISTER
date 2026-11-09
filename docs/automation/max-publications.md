# MAX: подробная настройка бота, webhook и конфига

Документ для администратора сервера. Относится к единому плагину `CHRDK_VK`
(JAR `chrdk-vk-*.jar`): отдельного плагина или Gradle-модуля `max/` нет.
Здесь **нет секретов**: токен, secret и пароль keystore задаются на сервере и в
Secrets/Variables GitHub, в репозиторий не попадают. Живые запросы к MAX API,
установка сертификата и модерация бота в этом цикле не выполнялись — это
инструкция по включению, а не отчёт о боевой проверке.

Официальная документация:
[подготовка бота](https://dev.max.ru/docs/chatbots/bots-coding/prepare/),
[POST /subscriptions](https://dev.max.ru/docs-api/methods/POST/subscriptions/),
[GET /updates](https://dev.max.ru/docs-api/methods/GET/updates/).

---

## 0. Что получится

Игрок заходит на сервер, получает одноразовый код, отправляет его **в личный
диалог с ботом MAX** (или в ВК), и замороженное лобби снимается без релога. Тот
же бот публикует новости релизов, пересылает глобальный чат и шлет
уведомления о входах. Всё это — один `config.yml` в папке `plugins/CHRDK_VK/`.

## 1. Чеклист перед началом

| Что | Зачем | Где взять |
| --- | --- | --- |
| Верифицированный профиль организации, ИП или самозанятого — резидента РФ | без него токен бота не выдают | «MAX для партнёров», `business.max.ru/self` |
| Бот, прошедший модерацию | только такой бот доступен пользователям | там же, раздел **Чат-боты** |
| Токен бота | заголовок `Authorization` во всех запросах | настройки бота в партнёрском кабинете |
| Домен с **доверенным** TLS-сертификатом | MAX с 25 мая не принимает HTTP и самоподписные сертификаты | у регистратора / хостера; подойдёт сертификат Минцифры |
| Отдельный порт на хостинге | плагин поднимает свой приёмник | панель хостинга; для production публичный endpoint — HTTPS на 443 |
| `chat_id` канала/беседы | `GET /chats` с 2026 года не поддерживается | из событий `bot_added` / `bot_started`, см. шаг 6 |

Ограничения платформы: до 30 RPS суммарно на `platform-api2.max.ru`, не более
2 сообщений в секунду в один чат, ответ webhook'а `HTTP 200` не позднее чем
через 30 секунд. Long Polling официально **не подходит для production** и не
может использоваться одновременно с Webhook [1](https://dev.max.ru/docs-api/methods/POST/subscriptions).

---

## 2. Создайте и подготовьте бота

1. В [MAX для партнёров](https://business.max.ru/self) → **Чат-боты** создайте
   бота, заполните описание и отправьте на модерацию. До её завершения бот
   недоступен пользователям, а настройки приватности изменить нельзя.
2. **Приватность:** `⋮` → **Настройки** → **Приватность** → разрешите
   *добавление в групповые чаты*. По умолчанию оно запрещено, и без этого бота
   нельзя будет добавить в беседу моста чата или новостной канал.
3. **Права в чате/канале.** Боту нужны как минимум `read_all_messages` и
   `write`: без `read_all_messages` бот не будет получать обновления через
   Webhook, без `write` не сможет отвечать и публиковать посты. Назначьте бота
   администратором через интерфейс мессенджера (**Участники** → добавить →
   администратор); первого бота-администратора можно назначить **только через
   интерфейс**, через API — только от имени уже существующего бота-админа.
4. **Личный диалог.** Игрок сам открывает бота и нажимает «Начать»: приходит
   событие `bot_started`, после чего бот может отвечать ему в личку. Код
   привязки принимается **только из личного диалога** — в беседу/канал его
   писать нельзя (`MaxBotRouter` это отсекает).
5. **Диплинк.** Запишите публичную ссылку бота в `social.max-bot-url`, чтобы
   она была кликабельна в сообщении с кодом:
   ```
   https://max.ru/<botName>?start=chrdk
   ```
   Формат `https://max.ru/<botName>?start=<payload>`, `payload` ≤ 128 символов.
   Не передавайте в нём код входа, пароль или 2FA: диплинки не должны
   использоваться для авторизационных сообщений. Для нашего случая достаточно
   постоянного `start=chrdk` без параметров.

> Бот должен быть доступен пользователям и уметь отправлять личные сообщения —
> иначе игрок не сможет ввести код. Проверьте это вручную до включения
> обязательной привязки.

---

## 3. HTTPS и порт: два варианта

### Вариант А — TLS поднимает сам плагин (проще)

1. Выдайте на хостинге отдельный порт (по умолчанию `8443`) и направьте на
   него домен из `max.webhook.public-url`.
2. Соберите PKCS12-keystore с **сертификатом, полной цепочкой и закрытым
   ключом** и положите `max-webhook.p12` в `plugins/CHRDK_VK/`:
   ```bash
   openssl pkcs12 -export \
     -in fullchain.pem -inkey privkey.pem \
     -out max-webhook.p12 -name chrdk \
     -passout 'pass:ВАШ_ПАРОЛЬ_KEYSTORE'
   keytool -list -keystore max-webhook.p12 -storetype PKCS12
   ```
   Тот же пароль укажите в `max.webhook.keystore-password`.
3. Оставьте `max.webhook.require-tls: true`. Плагин сам создаст
   `SSLServerSocket` на `max.webhook.port`. Без файла keystore он **откажется**
   поднимать незащищённый приёмник и уйдёт в long polling с предупреждением.

### Вариант Б — TLS терминирует reverse-proxy

Если nginx/Caddy уже отдаёт HTTPS на 443 и проксирует на внутренний порт
плагина:

```nginx
server {
    listen 443 ssl;
    server_name bot.example.ru;
    ssl_certificate     /etc/letsencrypt/live/bot.example.ru/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/bot.example.ru/privkey.pem;
    location /max/webhook {
        proxy_pass http://127.0.0.1:8443;
        proxy_set_header X-Max-Bot-Api-Secret $http_x_max_bot_api_secret;
    }
}
```

Тогда keystore не нужен, выставьте `require-tls: false`, а `public-url`
обязан остаться `https://...`. MAX проверяет именно публичный endpoint:
домен должен совпадать с CN/SAN сертификата, сервер обязан отдать полную
цепочку, а webhook — вернуть `200` быстрее чем за 30 с [1](https://dev.max.ru/docs-api/methods/POST/subscriptions).
Для production используйте публичный **порт 443**; `8443` — внутренний порт
плагина, а не адрес подписки.

---

## 4. Конфиг `plugins/CHRDK_VK/config.yml`

Файл на сервере создаётся при первом запуске из эталонного
`vk/src/main/resources/config.yml`. **Не удаляйте существующие значения** —
правьте только нужные ключи, затем `/social reload` (право `chrdksocial.admin`
или op) либо перезапуск.

```yaml
social:
  enabled: true
  require-link: true
  grace-days: 14
  newcomer-grace-days: 14
  max-bot-url: "https://max.ru/<botName>?start=chrdk"
  vk-community-url: "https://vk.ru/chrdk_reborn"

max:
  enabled: true
  api-base-url: "https://platform-api2.max.ru/"
  access-token: "<ТОКЕН_БОТА>"          # только сюда, не в URL и не в Git
  transport: webhook                     # webhook | polling
  update-types: [message_created, message_callback, bot_started, bot_added, bot_removed]
  chat-id: 0                             # заполняется после /social setchat bot <id>

  webhook:
    enabled: true
    port: 8443                           # внутренний порт плагина
    bind-address: "0.0.0.0"
    path: "/max/webhook"
    public-url: "https://bot.example.ru/max/webhook"
    secret: "Chrdk-2026_webhook_secret" # A-Z a-z 0-9 _ -, 5..256 символов
    keystore: "max-webhook.p12"
    keystore-password: "<ПАРОЛЬ_KEYSTORE>"
    require-tls: true                   # false только с reverse-proxy на 443

  polling:
    enabled: true                       # fallback; в проде после проверки — false
    wait-seconds: 25
    reconnect-seconds: 5
    limit: 100

  group-chat:
    enabled: true
    minecraft-to-max: true
    max-to-minecraft: true
    require-linked: true
    mc-badge: "<#00C3FF>[MAX]</#00C3FF>"
    max-format: "[MC] {player}: {message}"
    sender-cooldown-seconds: 3
    max-message-length: 220

news:
  enabled: true
  poll-minutes: 30
  announce-in-game: true
  template: "🚀 CHERDAK — выпуск %tag%: %title%\nПодробности и скачивание: %url%"
  release-api-url: "https://api.github.com/repos/cherdakmd/CHRDK_F_REGISTER/releases/latest"
```

### Ключи, от которых зависит именно MAX

| Ключ | Правило |
| --- | --- |
| `max.access-token` | без пробелов, только заголовок `Authorization`; `platform-api.max.ru` и токен в query устарели |
| `max.transport` | `webhook` (продакшен) или `polling` (разработка). При `webhook` неудачный старт всё равно уходит в polling, если `polling.enabled: true` |
| `max.webhook.secret` | **только** `A-Z a-z 0-9 _ -`, длина 5..256. Иначе `webhookConfigured()` = false, подписка не создаётся, плагин уйдёт в polling |
| `max.webhook.public-url` | только `https://`, совпадает с CN/SAN, полная цепочка сертификатов |
| `max.webhook.port` | внутренний порт; публичный адрес для MAX — 443 |
| `max.update-types` | обязательно оставьте `bot_started` (первый запуск бота и диплинк), `bot_added` (chat_id), `message_created` (коды и мост) |
| `social.max-bot-url` | диплинк, показывается игроку в сообщении с кодом |
| `social.require-link` | сама обязательность привязки; подробнее — [wiki/social-link.md](../../wiki/social-link.md) |

Токен не должен появляться в логах: `MaxApiClient` передаёт его только
заголовком, запрещает редиректы и не пишет тела ошибок.

---

## 5. Подписка на события

При старте (и после `/social reload`) плагин сам вызывает
`POST https://platform-api2.max.ru/subscriptions`:

```json
{
  "url": "https://bot.example.ru/max/webhook",
  "update_types": ["message_created", "message_callback", "bot_started", "bot_added", "bot_removed"],
  "secret": "Chrdk-2026_webhook_secret"
}
```

с заголовком `Authorization: <токен>`. MAX затем присылает каждый webhook с
заголовком **`X-Max-Bot-Api-Secret`**; приёмник сверяет его constant-time,
проверяет метод `POST` и путь, ограничивает тело 64 КБ, сразу отвечает `200`
и только потом разбирает JSON в отдельном пуле. Некорректный JSON не роняет
приёмник. Старое имя `X-Max-Bot-Api-Secret-Token` тоже принимается для
совместимости с уже созданными подписками.

Проверьте активную подписку при необходимости:
`GET https://platform-api2.max.ru/subscriptions` с тем же заголовком.
Одновременно Webhook и Long Polling использовать нельзя — после успешного
HTTPS выключите `max.polling.enabled: false`.

---

## 6. Получите `chat_id` и назначьте чаты

`GET /chats` удалён, поэтому id приходит только из событий.

1. Добавьте бота в новостной канал и в беседу моста чата (права из шага 2).
   Откройте с ботом личный диалог и нажмите «Начать» — придёт `bot_started`.
2. Посмотрите обнаруженные id:
   ```
   /max chats
   /social chats
   ```
   Они же пишутся в `logs/social/social-YYYY-MM-DD.jsonl` как
   `social.max-chat-discovered`.
3. Зафиксируйте назначение:
   ```
   /social setchat news   <id>   # новости релизов
   /social setchat bridge <id>   # мост игрового чата
   /social setchat bot    <id>   # чат по умолчанию (max.chat-id)
   ```

---

## 7. Проверка после запуска

| Что сделать | Ожидаемый результат |
| --- | --- |
| Лог сервера | `MAX webhook слушает 0.0.0.0:8443/max/webhook (HTTPS)` и `MAX webhook подписка: OK` |
| `/social status [ник]` | привязка, заморозка, grace, донат-доступ, счётчики |
| `/max status` | тот же статус в сокращённом виде |
| Написать боту `/start` в личку | приветствие с инструкцией; в логе событие `bot_started` |
| Зайти на сервер, получить код, отправить его боту **в личку** | `✅ Аккаунт … привязан к MAX`, ограничения снимаются без релога |
| `/social testnews` | разовая проверка GitHub Releases → MAX/ВК/чат игры |
| `/link` | повторный показ действующего кода (TTL 5 мин, пауза 20 с) |

Команды бота в личке: `/start`, `/link`, `/status`, `/код <код>`, либо просто
шестизначный код одним сообщением. Пять неудачных попыток с одного профиля
временно блокируют ввод на минуту.

---

## 8. Автопостинг новостей: два канала

### GitHub Actions

Workflow `VK news and server tips` готовит тот же `Post`, что и для ВК, и при
включённом флаге отправляет текст в MAX через `POST /messages`.

| Переменная | Где | Назначение |
| --- | --- | --- |
| `MAX_AUTOPUBLISH_ENABLED` | Variables | `true` разрешает реальную отправку |
| `MAX_BOT_TOKEN` | Secrets | токен бота MAX |
| `MAX_NEWS_CHAT_ID` | Variables | `chat_id` из шага 6 |

Дополнительно нужны существующие `VK_AUTOPUBLISH_ENABLED`,
`VK_NEWS_GROUP_ID`, `VK_NEWS_PEER_ID`, `VK_NEWS_ACCESS_TOKEN`: публикация в
MAX привязана к тому же доверенному запуску на `main` и не обходит guard'ы ВК.
Идемпотентность — отдельный журнал в GitHub Issues с заголовком
`[CHRDK MAX NEWS <chat_id>] YYYY-MM`, записи `prepared → posting → posted`.
Зависший `posting` требует ручной сверки, повтор запрещён.

Офлайн-проверка без сети и секретов:
```bash
python3 tools/social/run.py --check
python3 -m unittest discover -s tools/social -p 'test_*.py'
```
Успешный `--check` **не означает**, что пост отправлен.

### Плагин

`news.*` из шага 4: раз в `poll-minutes` плагин читает последний стабильный
GitHub Release, пропускает черновики и пререлизы, один раз на тег шлёт анонс
в MAX-канал, беседу ВК и игровой чат. `lastNewsTag` хранится в
`social-links.json`, поэтому рестарт не создаёт дубль. `/social testnews`
запускает проверку вручную. Каналы независимы: Actions публикует полную
карточку с картинкой, плагин — короткий анонс. Если нужен только один,
выключите `MAX_AUTOPUBLISH_ENABLED` или `news.enabled`.

---

## 9. Диагностика

| Симптом | Причина | Что делать |
| --- | --- | --- |
| `MAX webhook требует HTTPS: положите PKCS12-keystore…` | нет `max-webhook.p12` или неверный путь | положите файл в `plugins/CHRDK_VK/`, проверьте пароль; либо вариант Б с `require-tls: false` |
| `MAX: включён Long Polling` при настроенном webhook | `webhookConfigured()` = false: пустой/невалидный `secret`, пустой `public-url`, `webhook.enabled: false` или `transport: polling` | исправьте ключи, секрет — только `A-Z a-z 0-9 _ -` 5..256 |
| Подписка не создаётся, в логе `Не удалось зарегистрировать подписку` | токен неверен, URL не HTTPS, сертификат не доверенный, CN/SAN не совпадает | проверьте `GET /me` тем же токеном и TLS публичного адреса |
| События не приходят при валидном TLS | у бота нет `read_all_messages`, он не администратор, либо запрещено добавление в групповые чаты | шаг 2, приватность и права |
| `401 unauthorized` в логе приёмника | secret в конфиге не совпадает с secret подписки | пересоздайте подписку (`/social reload`) с одним и тем же значением |
| `/max chats` пусто | бота ещё не добавили в чат / не нажали «Начать» | шаг 6 |
| Код из беседы не принимается | код принимается только из личного диалога | напишите боту в личку |
| Новости не уходят | `setchat news` не задан, `MAX_AUTOPUBLISH_ENABLED ≠ true`, `lastNewsTag` уже равен тегу | `/social setchat news <id>`, `/social testnews` |
| `MAX API HTTP 429` / отказы отправки | превышены 30 RPS или 2 сообщения/с в чат | плагин уже держит 550 мс между отправками в чат; снизьте частоту моста и `sender-cooldown-seconds` |

---

## 10. Безопасность и границы

- Токен, `secret` и пароль keystore не должны попадать в репозиторий, логи,
  Issue или чат игроков. Не передавайте токен в query: только `Authorization`.
- Не выключайте `social.require-link` «чтобы проверить» на боевом сервере без
  `/social emergency` и списка `chrdksocial.bypass` — иначе администрация
  может потерять доступ при сбое ботов.
- `social-links.json` (схема 2, SHA-256), редактируемый
  `players/social.yml` и `logs/social/*.jsonl` — данные, их нужно бэкапировать;
  повреждение любого файла включает аварийный режим, а не тихий обход
  обязательной привязки. Подозрительный файл копируется в `.broken`/`.bak`,
  а повреждённый YAML не затирается сохранениями до ручной правки.
- Успешный CI `Social automation (offline)` не доказывает постинг. Живые
  запросы к MAX, установка сертификата, публикация бота в каталоге и модерация
  **не выполнялись**: нет верифицированного профиля.
- RCON, Minecraft-клиент, поведение webhook за конкретным reverse-proxy,
  живой платёж TradeMC, установка на игровой сервер и production MSPT не
  проверялись.
- Исторические теги и заметки прошлых релизов не переписываются.

---

## 11. Товар «Вход без ВК и MAX» и счётчики TAB

Игрок без мессенджеров покупает доступ на сайте: **150 ₽ / 30 дней**,
`id: social-access`, команда выдачи для TradeMC
`pass shopbuy %username% social-access` (без `/`, `%username%` подставляет
панель). Карточка и картинка 1200×1200 —
[docs/shop/social-access.md](../shop/social-access.md).
Дневной пробный тариф **15 ₽ / 1 день** — `id: social-access-day`,
`pass shopbuy %username% social-access-day`,
[docs/shop/social-access-day.md](../shop/social-access-day.md). Он дополнительно
выдаёт временную группу LuckPerms `social-day` с правом `chrdksocial.bypass`.

Товар лежит в `plugins/CHRDK_Core/config.yml` → `shop.products` с
`trademc-export: false`: он **не** переиздаёт исторический комплект из 26
карточек `docs/trademc/`. В `plugins/CHRDK_VK/config.yml` включён
`social.donate-access.mode: either` и
`product-ids: [social-access, social-access-day]`, поэтому любой донатный ранг
(от 300 ₽) тоже снимает ограничение, а карточки за 150 ₽ и 15 ₽ остаются для
игроков без ВК/MAX.

В `tablist.footer` ядра оставлена **одна** цельная строка, которую
регистрирует этот же JAR через PlaceholderAPI:

```yaml
    - "%chrdksocial_tab%"
```

- Непривязанный игрок в grace видит один срок и явный следующий шаг:
  `⚠ Привяжите ВКонтакте: /link → код боту. Осталось 14 дн.`
- После привязки строка становится `✅ ВКонтакте привязан — можно играть`:
  счётчик и дедлайн исчезают полностью.
- Оплаченный «Вход без ВК», аварийный режим и закрытый вход тоже описаны
  одной строкой без двух повторяющихся счётчиков.

Старые `%chrdksocial_status%`, `%chrdksocial_access%` и `%chrdksocial_link%`
сохранены в expansion для внешних TAB/скорбордов, но эталонный footer их не
печатает. Если владельческий `config.yml` всё ещё содержит две старые строки,
`TabFooterNormalizer` сливает их в `%chrdksocial_tab%` при чтении конфига,
**не перезаписывая файл**. Строка-дубль «Вы в игре» рядом с `%auth_status%`
скрывается рантаймом.

`plugin.yml` объявляет `softdepend: PlaceholderAPI`: без этого PAPI мог
включиться позже `CHRDK_VK`, expansion не регистрировался. Без PAPI `VkPlugin`
пишет **SEVERE** в лог и делает одну повторную попытку через 5 секунд; ядро в
этом случае скрывает строку целиком, а не показывает сырой плейсхолдер.

### Неназойливое напоминание при входе

Повторные напоминания раньше шли только уже замороженным, поэтому новый
аккаунт в 14-дневном grace их не видел; `op`/bypass вообще выходил из
`onJoin` без единой строки. Затем информирование стало слишком частым: action
bar каждые 15 с, сообщение в чате раз в 3 минуты, пинг при каждом действии и
полный блок из пяти строк при каждом перезаходе. Теперь с первого входа и до
выбора способа заголовок на экране и звук, блок «СПОСОБ 1 — привязка к
ВКонтакте» с пошаговой инструкцией и «СПОСОБ 2 — проход без ВК» (**15 ₽ /
1 день** или 150 ₽ / 30 дней), action bar, напоминание в чате и пинг при
действиях делят **один общий кулдаун на игрока — раз в 60 минут**. Кулдаун
переживает выход, поэтому релог в течение часа не повторяет инструкцию;
короткое «действие доступно после привязки» у замороженных тоже не чаще раза в
час. MAX в подсказках не упоминается: модуль в разработке. Игра в grace **не**
блокируется; после привязки или покупки напоминания снимаются сразу.
Параметры — `social.reminder-seconds` и `social.reminder.*` в этом же конфиге
(3600 секунд, потолок 86400); `nagging: false` оставляет только блок при входе
без кулдауна. `/social reload` перезапускает таймеры с новыми интервалами.

Живая покупка, выдача панелью TradeMC и истечение срока на сервере не
проверялись; `/social reload` не требует рестарта из-за строк TAB.
