# Привязка эфирных каналов и анонсы стримов — дизайн CHRDK_Live

**Статус:** проект, не реализация. Дата: 6 ноября 2026 года.
**Решение владельца:** новый модуль `CHRDK_Live`, уведомления идут в общий чат
(повтор ~раз в 20 минут) и в беседу ВК; старт эфира хочется определять
автоматически; на этом шаге — только документ.
**Площадки этапа 1 (решение владельца):** **Twitch** и **VK Play Live**.
Автоматика у них разная: у Twitch есть официальный EventSub, у VK Play Live
публичного вебхука старта эфира нет — нужен внешний адаптер (см. § 3).
**Связанные документы:** `docs/releases/1.4.241.md` (снятие старой программы),
`docs/design/PVE-ROADMAP.md` (граница снятых аддонов),
`docs/releases/1.4.186.md` / `1.4.190.md` (`/labels` и лимит меток),
`docs/releases/1.5.1.md` (транспорт CHRDK_VK), `wiki/updates.md`.

---

## 1. Жёсткая граница: это НЕ возврат CHRDK_Streams

В 1.4.241 старый `CHRDK_Streams` удалён целиком: привязка Twitch / VK Play
Live, API-клиенты, подтверждения, опросы, таймеры, зрительские заявки,
награды за эфиры и просмотр, раздел `/profile`, метка в `/labels`, кнопка
`/menu`, страница вики. `check_retired_addons.py` (Build, Wiki, Release) и
`RetiredAddons` в ядре по-прежнему запрещают:

| Что запрещено | Почему важно здесь |
| --- | --- |
| каталог `streams/`, `wiki/streams.md`, `streams.html` | нельзя так называть модуль и страницу |
| `CHRDK_Streams`, `ru.cherdakmd.chrdkstreams`, `chrdk-streams-*.jar` | нельзя имя плагина и namespace |
| `/stream`, `/streams`, `/strimer` и их namespaced-виды | нельзя команды |
| `id=streams` в реестрах `NameTags`, `ProfileRegistry`, `NotificationService`, `ChatService`, `ShopService`, `/wiki` | регистрация молча отбрасывается |
| слова `twitch`, `vk play live`, «стримерская программа» в **автосообщениях** | `RetiredAddons.announcement()` срезает объявление |

`docs/design/PVE-ROADMAP.md`: «Так же сняты Streams/Events/Queue/Lottery/Party/
Referrals». Возврат старой программы — отдельное решение владельца об отмене
этой политики, миграции старых данных и наград; **в этот дизайн не входит**.

**Новый модуль — другой продукт:**

| | |
| --- | --- |
| Gradle-модуль / плагин | `live` / `CHRDK_Live`, namespace `ru.cherdakmd.chrdklive` |
| Команды | `/live`, `/liveadmin` (никаких `/stream*`) |
| id во всех реестрах | `live` |
| Страница вики (на этапе реализации) | `wiki/live.md` |
| Данные | `plugins/CHRDK_Live/live-links.json`, свой аудит |

**Чего в модуле нет:** наград за эфир/просмотр, минут эфира, бустов, зрительских
заявок, рейтингов, «программы стримов», донатных привилегий, переноса старых
`plugins/CHRDK_Streams/` (там могли быть секреты площадок — архив не
импортируется). `API_VERSION` на первом этапе **не повышается**: новый публичный
контракт оформляется Bukkit-событиями и ServicesManager, а не методами
`ChrdkRegisterApi`. Если позже понадобится метод в API — это отдельный bump
(43) с регрессиями.

---

## 2. Цели и термины

**Цели:** игрок-стример один раз подтверждает владение каналом; сервер узнаёт,
что эфир начался; подписанные на анонсы игроки получают уведомление в общем
чате с настраиваемым периодом и в беседу ВК; в TAB у стримера на время эфира
отображается плашка **СТРИМ**; после конца эфира плашка гаснет, повторы
прекращаются.

**Не-цели:** определять «кто смотрел», начислять что-либо, считать онлайн
площадки, модерировать чат стрима, заменять бота площадки.

| Термин | Значение |
| --- | --- |
| **Площадка** | внешний сервис, где идёт эфир. На этапе 1 ровно два: `twitch` и `vkplay`. Имена в коде нейтральные; в **текстах автосообщений** названия и домены не встречаются (см. § 5.1). Другие площадки добавляются адаптерами, не правкой ядра. |
| **Канал** | `(platformId, channelId, channelHandle)` — один эфирный канал. `channelId` — стабильный идентификатор площадки, не ник (ник меняется): у Twitch это `broadcaster_user_id`, у VK Play Live — числовой/строковый id канала. |

**Две площадки этапа 1:**

| `platformId` | Что храним | Ссылка в анонсе | Источник события |
| --- | --- | --- | --- |
| `twitch` | `broadcaster_user_id`, `broadcaster_login` | `https://www.twitch.tv/<login>` | EventSub `stream.online` / `stream.offline` ([1](https://hookdeck.com/webhooks/platforms/guide-to-twitch-webhooks-features-and-best-practices), [2](https://hookdeck.com/webhooks/skills/twitch-webhooks)) |
| `vkplay` | id канала, `channelHandle` (слаг в `live.vkvideo.ru`) | `https://live.vkvideo.ru/<handle>` | внешний адаптер: событие `stream_start` / `stream_end` чат-клиента ([1](https://github.com/thearturca/vkplay-live-message-client)) |

`vkplay` выбран как id **намеренно**: `RetiredAddons.REMOVED_PROGRAM` ищет
`vk\s*play\s*live`, а `vkplay` под этот шаблон не подпадает. Названия площадок
допустимы в конфиге, GUI, вики и ссылках, но не в строках ротации чата.
| **Привязка** | подтверждённая пара `UUID игрока → канал`. Один игрок ≤ `max-channels-per-player` (по умолчанию 2), один канал → ровно один игрок. |
| **Эфир** | состояние `LiveSession(streamerUuid, channel, startedAt, eventId, state)`. Живёт **в памяти**, не в durable-файле. |
| **Событие** | `LiveStartedEvent` / `LiveEndedEvent` / `LiveHeartbeatEvent` — Bukkit-события, единственный вход в машину состояний. |
| **Каналы зрителей** | **каналы доставки**, а не аккаунты зрителей: `chat` (общий чат сервера) и `vk` (беседа сообщества). Подписки игроков на конкретных стримеров в первый этап не входят — анонс получают все онлайн с правом `chrdklive.receive`, кроме явно отключивших (`/live mute`). |
| **Плашка** | метка `LIVE` в системе `/labels`, текст `<red>СТРИМ</red>`, рядом с ником в чате и TAB. |

---

## 3. Ответ на вопрос «можно ли автоматически?»

**Коротко: да для обеих площадок, но по-разному.** Площадка не знает о
существовании Minecraft-сервера, поэтому нужен внешний источник. У **Twitch**
есть официальный EventSub с событиями `stream.online` / `stream.offline`; у
**VK Play Live** публичного вебхука старта эфира нет, но статус можно получить
из чата канала внешним ботом. Оба источника сводятся в один `LiveSignal`.

| Вариант | Twitch | VK Play Live | Оценка |
| --- | --- | --- | --- |
| **A. Официальный webhook** | EventSub: `stream.online`/`stream.offline` на HTTPS-колбэк, подписка через Helix с app access token ([1](https://hookdeck.com/webhooks/platforms/guide-to-twitch-webhooks-features-and-best-practices), [2](https://hookdeck.com/webhooks/skills/twitch-webhooks), [4](https://github.com/katogaming88/WGA-Raid-Hub/issues/1262)) | публичного вебхука разработчика нет; в интерфейсе ВК есть только «Оповестить о начале трансляции» для подписчиков ВК, раз в час, и оно не приходит на сервер ([4](https://vk.ru/@authors-create-stream), [5](https://vk.company/ru/press/releases/9937/)) | **основной путь для Twitch** |
| **C. Внешний адаптер-бот** | не нужен | сообщественный чат-клиент отдаёт `stream-status` с `stream_start` / `stream_end`; работает даже вне эфира ([1](https://github.com/thearturca/vkplay-live-message-client)). Embed `postMessage` из iframe — браузерный путь, на сервере недоступен ([3](https://qna.habr.com/q/1405876)) | **основной путь для VK Play Live** |
| **B. Опрос статуса** | `GET /helix/streams?user_id=…` как медленная страховка раз в 30 мин, если webhook потерян ([4](https://github.com/katogaming88/WGA-Raid-Hub/issues/1262)) | неофициальный опрос страницы канала — хрупкий, не рекомендуется | только safety net, не основной источник |

### 3.1 Twitch EventSub: что именно приходит

- Подписка создаётся `POST /helix/eventsub/subscriptions` с **app access
  token** (client credentials); авторизация самого стримера для
  `stream.online` / `stream.offline` **не требуется**. В `condition` —
  `broadcaster_user_id`, в `transport` — `method: webhook`, публичный
  **HTTPS-колбэк строго на порту 443** и `secret` длиной 10–100 ASCII-символов
  ([1](https://hookdeck.com/webhooks/platforms/guide-to-twitch-webhooks-features-and-best-practices),
  [2](https://hookdeck.com/webhooks/skills/twitch-webhooks)).
- Три типа сообщений: `webhook_callback_verification` (challenge, надо вернуть
  `challenge` из тела), `notification` (событие), `revocation` (подписка
  отозвана). Доставка at-least-once, есть `Twitch-Eventsub-Message-Retry`
  ([2](https://hookdeck.com/webhooks/skills/twitch-webhooks),
  [3](https://github.com/hookdeck/webhook-skills/blob/main/skills/twitch-webhooks/SKILL.md)).
- Подпись: `sha256=<hex>` HMAC-SHA256 по конкатенации
  `Twitch-Eventsub-Message-Id` + `Twitch-Eventsub-Message-Timestamp` +
  **сырое** тело (пересериализация JSON ломает подпись). Сравнение
  constant-time; сообщения старше 10 минут отклоняются; дедупликация по
  message id ([2](https://hookdeck.com/webhooks/skills/twitch-webhooks),
  [3](https://github.com/hookdeck/webhook-skills/blob/main/skills/twitch-webhooks/SKILL.md)).
- `stream.online` несёт `broadcaster_user_id`, `broadcaster_user_login` и
  `started_at`; названия трансляции и ссылки в событии **нет** — их берём из
  `live-links.json`. Ссылка на канал всегда `https://www.twitch.tv/<login>`.
- Обязательна **ежедневная сверка подписок** (reconcile): при `/live link`
  создаём подписку, при `unlink` удаляем, раз в сутки пересоздаём потерянные
  после неудачной доставки ([4](https://github.com/katogaming88/WGA-Raid-Hub/issues/1262)).
  Медленный опрос Helix раз в 30 минут — страховка от пропущенного `offline`.
- **Важно:** EventSub не шлёт heartbeat во время эфира. Поэтому правило
  «15 минут без heartbeat → OFFLINE» к Twitch **не применяется**; там OFFLINE
  приходит явным событием, а страховкой служит опрос статуса (см. § 3.3).

### 3.2 VK Play Live: почему без внешнего адаптера никак

Открытый API для трансляций анонсировался ещё в 2017 году
([5](https://vk.company/ru/press/releases/9937/)), но общедоступного
серверного webhook'а `stream_start` у площадки нет. Рабочие варианты:

1. **Внешний адаптер на базе чат-клиента** (рекомендуется): процесс рядом с
   сервером подключается к чату канала `live.vkvideo.ru/<handle>` и по
   `stream-status` (`stream_start` / `stream_end`) шлёт подписанный `LiveSignal`
   на внутренний приёмник плагина. Клиент требует access token и список
   каналов ([1](https://github.com/thearturca/vkplay-live-message-client)).
2. **Бот-мост через уже работающий CHRDK_VK**: адаптер пишет служебную команду
   в ВК-бота, `VkBotRouter` / `SocialCallback` передаёт её на сервер. Новый
   порт не нужен, но добавляется задержка LongPoll.
3. **Ручной `/live start|stop`** — обязательный fallback на случай, если
   неофициальный чат-API площадки изменился.

Риск фиксируем явно: адаптер опирается на **неофициальный** протокол чата.
Поэтому для `vkplay` сохраняется heartbeat от адаптера каждые 5 минут и TTL
15 минут; при молчании эфир гасится без ложного «эфир завершён» в ВК.

### 3.3 Единый приёмник и контракт `LiveSignal`

Плагин **не слушает 443**: на игровом хостинге это обычно занято/закрыто.
Схема: `публичный HTTPS : 443 (reverse proxy или отдельный приёмник) →
внутренний : 8765 (CHRDK_Live)`. У приёмника **два разных секрета**: секрет
подписки Twitch и секрет внутреннего `LiveSignal`; ни один не лежит в git.

```
Twitch EventSub ──HTTPS 443──> [внешний приёмник: HMAC + challenge]
                                     │  LiveSignal (внутренний HMAC)
VK Play адаптер ────────────────────┤
ВК-бот /live start (fallback) ──────┤
Helix safety-net (30 мин) ──────────┘──> CHRDK_Live : 8765 ──> LiveSession
```

`LiveSignal`: `platformId, channelId, kind(ONLINE|OFFLINE|HEARTBEAT|STATUS),
startedAt, eventId, signature`. Источник значения не имеет — дальше одна
машина состояний; площадки добавляются адаптерами без правки анонсов и TAB.

**Защита:** HMAC-SHA256 по сырому телу, constant-time; окно метки времени
±5 минут (для Twitch — 10 минут по его правилам); `eventId` в LRU (message id
Twitch / id события адаптера / `status:<время>`); whitelist
`(platformId, channelId)` по `live-links.json`; лимит тела 64 КБ, максимум 16
заголовков; ответ 200 до обработки; обработка в отдельном пуле, в игровой
поток — только `Bukkit.getScheduler().runTask(...)`. Секреты и `client-id` —
в `plugins/CHRDK_Live/secrets.yml` (владелец создаёт вручную, в `.gitignore`).

**Гистерезис и деградация:**

```
OFFLINE ──ONLINE──> STARTING ──(анонс отправлен)──> ONLINE
   ^                                                  │
   │                    Twitch: stream.offline / Helix STATUS (30 мин)
   │                    VK Play: stream_end / HEARTBEAT (5 мин)
   │                    Ручной: /live stop
   └──────────────────────────────────────────────────┘
```

- Повторный `ONLINE` с тем же `eventId` или `startedAt` **не** создаёт новый
  эфир и **не** шлёт новый анонс.
- `OFFLINE` принимается не раньше `min-session-seconds` (60 с) после старта —
  защита от дребезга переподключения.
- Twitch: OFFLINE от EventSub; если пропал — safety-net `STATUS` из Helix
  раз в 30 минут. VK Play: 15 минут без heartbeat → гасим молча.
- После рестарта сервера память пуста: **никаких фантомных эфиров и анонсов**;
  восстановление — только новым `ONLINE`/`HEARTBEAT`/`STATUS`.
- Ручной источник требует `chrdklive.manual`, помечается `source=manual` в
  аудите и не раскрывается в тексте анонса.

**Этап 1:** ручной `/live start|stop` + приёмник `LiveSignal` + адаптер VK Play
+ подписки Twitch (выключены до появления client-id, секрета и HTTPS-домена).
**Этап 2:** включаем EventSub, reconcile и safety-net; прямой опрос страницы
VK Play не реализуем — он хрупок и не проверяем в песочнице.

---

## 4. Привязка канала к аккаунту игрока

Паттерн берём из `SocialLinkCodeManager` + `EconomyConfirmations` (одноразовое
согласие с котировкой, TTL 30 с, одноразовое `consume`, лимит попыток).

**Поток:**

1. `/live link <площадка> <канал>` — игрок должен быть авторизован
   (`AccessManager.PlayerStage.AUTHENTICATED`), право `chrdklive.link`.
   Проверяем лимит каналов на игрока и свободность канала.
2. Плагин выдаёт одноразовый код (`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, 8
   символов, TTL 10 мин, кулдаун перевыпуска, не более 5 неверных попыток с
   блокировкой) и «котировку»: `uuid, platformId, channelId, handle, issuedAt`.
   `EconomyConfirmations.offer(uuid, session, quote, now)`.
3. Подтверждение владения — **внешнее**, по площадке:
   - `twitch`: игрок ставит код в название трансляции (или в панель канала).
     Проверка — `GET /helix/channels?broadcaster_id=…` с тем же app access
     token: поле `title` должно содержать код. Авторизация стримера не нужна,
     но нужен исходящий HTTPS к Helix; в песочнице это не проверяем.
   - `vkplay`: игрок пишет код в чат канала; внешний адаптер (§ 3.2) видит
     сообщение и присылает `link.confirm` с тем же кодом.
   - **Общий fallback:** `/live confirm <код>` **плюс** ручная проверка
     администратором (`/liveadmin verify <игрок>`, право `chrdklive.admin`).
     «Сам подтвердил» без внешней проверки даёт только `PENDING`, не
     `CONFIRMED`: иначе любой игрок может привязать чужой канал и получить
     плашку СТРИМ.
   - При `CONFIRMED` для `twitch` сразу создаём подписки EventSub; при
     `unlink` — удаляем (§ 3.1). Для `vkplay` адаптер добавляет канал в список
     прослушивания.
4. `EconomyConfirmations.consume(...)` — строго одноразово; смена сессии,
   канала или истечение TTL отклоняет. Статус `CONFIRMED`, пишем аудит.
5. `/live unlink <канал>` — мгновенно снимает привязку, гасит активный эфир
   этого канала и плашку. `/liveadmin unlink <игрок>` — то же для модерации.

**Хранилище** `live-links.json` (схема 1), по образцу `SocialLinkStore`:

```json
{
  "schema": 1,
  "links": { "<uuid>": [ { "platform": "...", "channelId": "...",
      "handle": "...", "status": "CONFIRMED", "confirmedAt": 0 } ] },
  "owners": { "<platform>:<channelId>": "<uuid>" },
  "muted": ["<uuid>"],
  "checksum": "<sha256 канонического JSON без поля checksum>"
}
```

- Запись атомарная через `SafeJsonWriter`, контрольная сумма SHA-256;
  расхождение → копия в `.broken`, оригинал не перезаписывается, модуль
  стартует в read-only и громко пишет в лог (fail-closed).
- Durable только привязки и `muted`; состояния эфиров, коды, квоты анонсов —
  в памяти.
- Никаких OAuth-токенов, cookies, IP, UUID ВК и координат в этом файле.
- Аудит `logs/live/links.jsonl` (append-only): `link.issue`, `link.confirm`,
  `link.reject`, `unlink`, `signal.received`, `signal.rejected`, `announce.*`,
  `manual.*`. Без текста сообщений и секретов.

---

## 5. Каналы зрителей: общий чат + беседа ВК

Ключевая идея: **одно событие → один `AnnouncementPlan` → два независимых
канала с разными правилами и общим `eventId`**. Ни один канал не блокирует
другой: нет CHRDK_VK — чат работает; упал VK API — анонс в чат уже ушёл.

### 5.1 Общий чат

- При `ONLINE` — **сразу один** анонс; затем повторы каждые
  `announce.chat.repeat-minutes: 20`, **только пока сессия `ONLINE`**.
- Ограничители повторов: `max-repeats: 6` (≈2 часа), `max-lifetime-minutes:
  180`, `min-online: 1`. По достижении любого лимита — тишина до конца эфира.
- `quiet-hours` (МСК, по умолчанию `02:00–09:00`): повторы не шлём, первый
  анонс при старте шлём; при выходе из тихого окна эфир не «догоняет»
  пропущенные повторы.
- Получатели: все онлайн с `chrdklive.receive`, **кроме** `/live mute`
  (персональный opt-out, хранится в `muted`), и кроме неавторизованных
  (`PlayerStage`): гостю в лобби анонс не нужен.
- Текст — **нейтральный**, без названий площадок: «🔴 <ник> в эфире:
  <название>». Ссылка — кликабельный `Component` с URL из привязки:
  `https://www.twitch.tv/<login>` или `https://live.vkvideo.ru/<handle>`.
  **Важно:** анонс **не регистрируется как `AutoMessage`** —
  `AutoMessage.eligible()` вызывает `RetiredAddons.announcement(text)`, который
  ищет `(?iu)twitch|vk\s*play\s*live|стримерская программа`. Ссылка на
  `twitch.tv` **тоже** содержит `twitch` и была бы срезана, поэтому URL
  вставляется компонентом, а не текстом ротации; `vkplay` в id и
  `live.vkvideo.ru` в ссылке под шаблон не подпадают. Рассылка идёт напрямую
  (`Component` + `player.sendMessage`) или через
  `ChatService.broadcastAnnouncement`, минуя ротацию автосообщений. В ротации
  чата место не занимается, чужие списки и интервалы не перезаписываются.
  Это прямо отвечает на пожелание «Нам надо Twitch и VK Play Live»: обе
  площадки поддерживаются, но их названия не попадают в строки, которые ядро
  фильтрует как рекламу снятой программы.
- Анти-спам: `SocialEventThrottle`-подобный `LiveAnnounceThrottle` с часами
  `LongSupplier` (проверяемо офлайн), ключ `chat:<eventId>`; отдельный
  `chat:global` кулдаун (по умолчанию 60 с), чтобы несколько стримеров не
  выдали подряд пять анонсов.
- Плашка и анонс живут от одного и того же `LiveSession`; рассинхрона нет.

### 5.2 Беседа ВК

- При `ONLINE` — **один** анонс через `VkService.sendEventToGroupChat(String)`
  (уже есть общий `VkChatBatcher` против VK flood control и свои флаги).
  Повторы в ВК по умолчанию **выключены** (`announce.vk.repeat: false`):
  беседа не должна получать одно и то же каждые 20 минут.
- Опционально: «эфир завершён» при явном `OFFLINE` (не при TTL-деградации).
- `CHRDK_VK` — **standalone JAR**, его может не быть: мягкая зависимость
  (`softdepend: [CHRDK_VK]`), интеграция через Bukkit-событие
  `LiveBroadcastEvent`, которое слушает адаптер внутри VK-модуля. Прямого
  `VkApiClient` из `CHRDK_Live` нет; при отсутствии модуля канал помечается
  `SKIPPED:no-vk` в аудите, чат не страдает.
- Приватность: в беседу уходит только безопасный ник и нейтральный текст,
  без UUID, IP, координат, инвентаря (как в `SocialEventAnnouncer`).
- Событие фиксируется в `logs/social/*.jsonl` существующим аудитом.

### 5.3 Таблица решений

| Ситуация | Чат | ВК | Плашка |
| --- | --- | --- | --- |
| `ONLINE`, первый раз | сразу 1 анонс | сразу 1 анонс | загорается |
| Повтор через 20 мин, эфир активен | 1 повтор, пока не исчерпан лимит | тишина | горит |
| `HEARTBEAT` | тишина | тишина | горит |
| Повторный `ONLINE` с тем же `eventId` | тишина | тишина | без изменений |
| Явный `OFFLINE` | тишина (или «эфир завершён» по флагу) | опционально | гаснет |
| 15 мин без heartbeat | тишина | тишина | гаснет |
| Рестарт сервера | тишина | тишина | погашена |
| Нет CHRDK_VK | работает | `SKIPPED:no-vk` | работает |
| Игрок `/live mute` | не получает | не влияет | его метка не меняется |

---

## 6. Плашка СТРИМ в TAB

**Дефолт (рекомендуемый):** новая метка `LIVE("live", Group.NAME)` в
`LabelPreferences.Label`, текст `<red>СТРИМ</red>`, показывается рядом с ником
в чате и в `%player_tagged%` TAB **только пока `LiveSession.state == ONLINE`**.
Тогда она подчиняется уже существующим правилам: лимит мест, приоритет,
персональное скрытие `/labels live`, сохранение выбора в `labels.json`.

Требования к реализации:

- `LabelPreferences.Label` — **append-only**: `EXPEDITIONS` уже помечен как
  «старые ordinal-биты HEADER не сдвигать». Новую константу добавлять строго в
  конец; `labels.json` версии 4 не сдвигать ordinal-биты, миграция — только
  добавление нового id в конец порядка по умолчанию. Места снятого `streams`
  в старых пресетах остаются пустыми, подставлять туда `live` **нельзя**.
- Провайдер регистрации: `ChrdkRegisterApi.registerNameTag("live", provider)`
  (API v6). `NameTags.register` отбрасывает `RetiredAddons.source(id)`; `live`
  не входит в `SOURCES`, поэтому проходит. Повторная регистрация с тем же id
  заменяет провайдера; при `onDisable` — `unregisterNameTag("live")`.
- Плашка не трогает имя игрока: `NameTagProvider.decorate` добавляет метку
  после ника, MiniMessage-значения экранируются `Messages.escape`, как остальные
  метки. Скрытие метки — только оформление, права/эфир не меняются.
- Обновление: `TabListService` уже перерисовывает `player-name-format` каждые
  `refresh-ticks` (40) и имеет heartbeat `animation-refresh-ticks` (5) для
  «активных» имён. `NameTagProvider.animated(playerName)` (API35) возвращает
  `true` только для стримеров в эфире — frequent-кадр не включается для
  всего сервера.
- **«Гореть»** — два режима на выбор конфигом:
  `tab.badge.mode: static` (дефолт, красная плашка без мигания, минимальная
  нагрузка) или `pulse` (два кадра `🔴 СТРИМ` / `Стрим` через существующий
  `TabFrameState` / `Messages.fill`, с жёстким лимитом
  `tab.badge.max-animated-players: 8`). Мигание не должно перетирать клановую
  анимацию: `%player_tagged%` остаётся единственным источником имени.
- PAPI на будущее: `%chrdklive_status%` (`в эфире` / пусто),
  `%chrdklive_badge%` (плашка / пусто) для сторонних TAB-плагинов. Без
  PlaceholderAPI — пустая строка, без SEVERE.

**Альтернативы, если владелец захочет иначе:** отдельная строка в
`tablist.footer` (проще, но не персонально отключаемая и не в лимите меток)
или только PAPI-плейсхолдер без правки ядра (быстрее, но плашка не появится в
чате и `/labels`). Документ фиксирует дефолт; смена — до начала кода.

---

## 7. Команды, права, конфиг

| Команда | Право | Действие |
| --- | --- | --- |
| `/live link <площадка> <канал>` | `chrdklive.link` | выдать код подтверждения |
| `/live confirm <код>` | `chrdklive.link` | погасить код (только `PENDING` без внешней проверки) |
| `/live unlink [канал]` | `chrdklive.link` | снять привязку, погасить эфир |
| `/live status` | `chrdklive.use` | свои каналы, статус эфира, режим уведомлений |
| `/live mute` / `unmute` | `chrdklive.use` | персональный opt-out чат-анонсов |
| `/live start` / `stop` | `chrdklive.manual` | ручной источник, `source=manual` в аудите |
| `/liveadmin reload` | `chrdklive.admin` | перечитать конфиг, сбросить квоты, НЕ гасить эфиры |
| `/liveadmin list` / `verify <ник>` / `unlink <ник>` / `end <ник>` | `chrdklive.admin` | модерация привязок и эфиров |
| `/liveadmin test <ник>` | `chrdklive.admin` | сухой прогон анонса без отправки в ВК (диагностика) |

Все команды — в `plugin.yml` модуля; `chrdklive.*` не пересекается с
`chrdkstreams.*` (последнее `RetiredAddons.permission()` всё равно блокирует).

Пример `live/config.yml` (значения по умолчанию):

```yaml
live:
  enabled: true
  max-channels-per-player: 2
  link-code-seconds: 600
  # Ровно две площадки этапа 1; остальные добавляются адаптерами.
  platforms:
    twitch:
      enabled: true
      link-url: "https://www.twitch.tv/{handle}"
      api-base: "https://api.twitch.tv/helix"
      client-id-file: secrets.yml      # Twitch app: client id + client secret
      # Подписки EventSub создаются при link, удаляются при unlink,
      # сверяются раз в сутки; колбэк — публичный HTTPS строго :443.
      eventsub:
        callback: "https://example.org/live/eventsub"
        subscription-secret-file: secrets.yml
        reconcile-hours: 24
        safety-net-minutes: 30        # GET /helix/streams?user_id=
    vkplay:
      enabled: true
      link-url: "https://live.vkvideo.ru/{handle}"
      # Официального серверного webhook нет: внешний адаптер чата шлёт
      # stream_start / stream_end подписанным LiveSignal (§ 3.2).
      adapter:
        heartbeat-minutes: 5
        timeout-minutes: 15
  announce:
    chat:
      enabled: true
      repeat-minutes: 20        # «раз в 20 минут например»
      max-repeats: 6
      max-lifetime-minutes: 180
      min-online: 1
      global-cooldown-seconds: 60
      quiet-hours: "02:00-09:00"
      quiet-timezone: "Europe/Moscow"
    vk:
      enabled: true             # мягкая зависимость от CHRDK_VK
      repeat: false             # беседу не спамим
      announce-end: false
  signal:
    manual: true                # /live start|stop — обязательный fallback
    # ВНУТРЕННИЙ приёмник. Публичный HTTPS :443 (reverse proxy / внешний
    # приёмник) проверяет подпись Twitch и отвечает на challenge, затем
    # пересылает уже нормализованный LiveSignal сюда со своим HMAC.
    receiver:
      enabled: false            # включается после настройки секретов и прокси
      bind: 127.0.0.1
      port: 8765
      path: /live/signal
      secret-file: secrets.yml  # внутренний секрет, НЕ секрет подписки Twitch
      max-body-bytes: 65536
      timestamp-window-seconds: 300
      twitch-timestamp-window-seconds: 600   # правило EventSub: 10 минут
    min-session-seconds: 60
    replay-cache-size: 2048
  tab:
    badge:
      enabled: true
      mode: static              # static | pulse
      text: "<red>СТРИМ</red>"
      max-animated-players: 8
```

`messages.yml` модуля — отдельный набор ключей `live.*` (обязательная проверка
`check_messages.py` на этапе реализации). Названия площадок в **текстах чата не
используются**: только `%player%`, `%title%`, кликабельная ссылка.

---

## 8. Интеграции с ядром (без bump API_VERSION)

| Куда | Как | Зачем |
| --- | --- | --- |
| `AccessManager` | `PlayerStage.AUTHENTICATED` через Bukkit-событие входа / ServicesManager `ChrdkRegisterApi.isAuthenticated` | не слать анонсы в лобби |
| `NameTags` | `registerNameTag("live", ...)` (API v6, уже есть) | плашка в чате и TAB |
| `LabelPreferences` | новая константа `LIVE` (правка ядра, append-only) | `/labels`, лимит, сохранение выбора |
| `TabListService` | существующий `%player_tagged%`, `animated()` | отрисовка без нового таймера |
| `ChatService` / `player.sendMessage` | прямая рассылка, **минуя `AutoMessage`** | обход фильтра `RetiredAddons.announcement` |
| `VkService.sendEventToGroupChat` | адаптер в CHRDK_VK слушает `LiveBroadcastEvent` | беседа ВК, общий батчер |
| `NotificationService` | опционально `live.started` как `NotificationListener` | Discord при желании, по флагу |
| `RetiredAddons` | **не меняем** | политика снятых аддонов остаётся |
| Внешний HTTPS-приёмник `:443` | проверяет подпись EventSub, отвечает на `webhook_callback_verification`, нормализует в `LiveSignal` | Twitch не умеет в произвольный порт; плагин слушает только `127.0.0.1:8765` |
| Адаптер `vkplay` (внешний процесс) | `stream_start`/`stream_end` чата → подписанный `LiveSignal` | у VK Play Live нет серверного webhook'а |
| Helix (только вне игрового потока) | `POST /helix/eventsub/subscriptions`, `DELETE` при unlink, `GET /helix/channels` для кода, `GET /helix/streams` как safety-net | app access token из `secrets.yml`, ни одного токена в git |

Bukkit-события модуля (в `ru.cherdakmd.chrdklive.api`, не в Core API):
`LiveStartedEvent`, `LiveEndedEvent`, `LiveHeartbeatEvent`,
`LiveBroadcastEvent(channel, component)` — синхронные, read-only, не
отменяемые (по образцу `AnvilPaidResultEvent`). Сторонние аддоны могут на них
подписаться без изменения `API_VERSION`.

---

## 9. Безопасность, приватность, отказоустойчивость

- **Два разных секрета** (подписки EventSub и внутреннего `LiveSignal`) плюс
  `client-id` / `client-secret` Twitch-app и access token адаптера VK Play —
  только в `secrets.yml` вне репозитория; в логах и аудите не появляются.
  HMAC constant-time по **сырому** телу, окно метки времени (Twitch — 10 мин),
  replay-cache по message id, whitelist каналов, лимит тела и заголовков.
  App access token Twitch обновляется по client credentials и не требует
  авторизации стримера; токен адаптера VK Play ротируется владельцем вручную.
- Один канал → один игрок; лимит каналов на игрока; кулдаун и блокировка кодов;
  любая попытка подтверждения одноразова (`EconomyConfirmations.consume`).
- Fail-closed на повреждённом `live-links.json`: плашка не загорается, анонсы
  не шлются, пока администратор не разберёт `.broken`.
- Никаких координат, IP, UUID, инвентаря, содержимого чата в ВК и аудите.
- Никаких вызовов внешних API из игрового потока: HTTP — в отдельном пуле, в
  главный поток — только через scheduler. Ошибки транспорта логируются и не
  роняют сервер; повторная отправка не дублирует анонс благодаря `eventId`.
- `/liveadmin reload` не перевыпускает уже отправленные анонсы и не гасит
  активные эфиры; квоты сбрасываются явно и только по команде админа.
- `onDisable`: снять `NameTagProvider`, закрыть внутренний порт приёмника,
  остановить пул, **не** писать «эфир завершён» в ВК (ложное событие при
  рестарте). Внешний приёмник на :443 живёт отдельно и продолжает отвечать
  Twitch 200, пока не будут удалены подписки — иначе Twitch отзовёт их за
  повторные ошибки доставки.

---

## 10. Тесты и QA (без живого API)

Юнит-тесты с подставляемыми часами, без Minecraft-сервера и без сети:

1. `TwitchEventSubVerifierTest` — HMAC `sha256=` по
   `message-id + timestamp + raw body` (пересериализованный JSON отклоняется),
   constant-time, окно 10 минут, повтор message id, `Retry > 0`,
   `webhook_callback_verification` → возврат `challenge`, `revocation` →
   пометка подписки, whitelist `broadcaster_user_id`.
2. `VkPlayAdapterContractTest` — `stream_start` / `stream_end` / heartbeat
   → `LiveSignal(kind=…)`, подпись, TTL 15 минут, потеря адаптера не шлёт
   ложный «эфир завершён»; `twitch` и `vkplay` дают одинаковый `LiveSession`.
3. `LiveSignalVerifierTest` — внутренний HMAC, окно ±5 минут, replay,
   whitelist, битое тело, лимит размера.
4. `LiveSessionMachineTest` — переходы состояний, повтор `ONLINE` с тем же
   `eventId`, дребезг `OFFLINE` до `min-session-seconds`, для Twitch —
   `STATUS` из Helix вместо heartbeat-TTL, для VK Play — TTL heartbeat,
   рестарт = пустая память, ручной источник помечен в аудите.
5. `LiveAnnounceThrottleTest` — первый анонс, ровно через 20 минут повтор,
   `max-repeats`, `max-lifetime`, тихие часы, `min-online`, `mute`,
   глобальный кулдаун, отсутствие ВК не блокирует чат.
6. `LiveLinkStoreTest` — schema 1 round-trip, SHA-256, `.broken`, атомарность,
   один канал → один игрок, лимит, `unlink` гасит эфир, код одноразов.
7. `LiveLabelTest` — `LIVE` добавлен в конец `LabelPreferences.Label`,
   ordinal-биты `HEADER`/`EXPEDITIONS` не сдвинулись, `labels.json` v4 читается,
   скрытие метки не влияет на эфир.
8. `LiveRetiredContractTest` — в исходниках модуля нет запрещённых имён,
   команд, путей и `id=streams`; `RetiredAddons.source("live") == false`;
   анонс не регистрируется как `AutoMessage`.
9. `check_retired_addons.py`, `check_messages.py`, все YAML, tree-sitter,
   `git diff --check`, freshness 37 страниц и `wiki/live.md` (на этапе
   реализации), `tools/social/run.py --check`, TradeMC/clan_shop — в CI.

**Не проверяем и не обещаем:** живые вызовы Helix/EventSub и чата VK Play,
настоящий HTTPS-колбэк на 443 с доверенным сертификатом, получение app access
token, работу внешнего адаптера на неофициальном протоколе, LongPoll ВК-бота,
кликабельные ссылки в реальном клиенте, `CraftItemStack`, playerdata, MSPT,
RCON, установку на production. В песочнице исходящий интернет ограничен `github.com`, `pypi.org`
и npm — поэтому автоматика определяется контрактом и офлайн-тестами, а живой
smoke выносится в отдельную задачу на staging.

---

## 11. Этапы

| Этап | Состав | Риск |
| --- | --- | --- |
| **1. Каркас** | модуль `live`, `platforms: twitch + vkplay`, хранилище привязок, ручной `/live start|stop`, внутренний приёмник `LiveSignal` с HMAC, плашка `LIVE`, чат-анонсы с 20-минутным повтором, адаптер ВК через событие, офлайн-тесты обоих адаптеров, `wiki/live.md`, freshness | низкий: всё проверяемо без сети |
| **2. Автоматика** | Twitch EventSub (client credentials, HTTPS :443, challenge, reconcile, Helix safety-net) + внешний адаптер чата VK Play (`stream_start`/`stream_end`, heartbeat), гистерезис, `LiveBroadcastEvent` для сторонних | средний: нужен публичный HTTPS, сертификат и живой smoke; адаптер VK Play опирается на неофициальный протокол |
| **3. Опционально** | PAPI `%chrdklive_*%`, подписки зрителей на конкретных стримеров, «эфир завершён» в ВК, дополнительные площадки адаптерами | высокий: приватность подписок, новые секреты |

Каждый этап — отдельная задача с версией и заметками; пререлиз
`v1.5.2-rc.1` и стабильная `v1.5.1` этими правками не затрагиваются.

---

## 12. Что принято и что осталось уточнить

**Принято владельцем («звучит хорошо») как дефолты:** плашка `LIVE` рядом с
 ником через `/labels` (статичная, не `pulse`); повтор в общем чате раз в
 **20 минут** с `max-repeats: 6` и тихими часами `02:00–09:00` МСК; один анонс
 при старте в общую беседу ВК без повторов; `/live mute` как opt-out; код
 подтверждения владения; площадки этапа 1 — **Twitch и VK Play Live**.

**Осталось три инфраструктурных вопроса** (без них этап 2 не включается, но
этап 1 реализуем уже сейчас):

1. **Twitch-приложение и домен.** Есть ли зарегистрированное Twitch
   application (`client id` + `client secret`) для app access token и
   публичный HTTPS-адрес **на порту 443** с доверенным сертификатом для
   колбэка EventSub? Если нет — кто его поднимает: reverse proxy на
   существующем домене или отдельный внешний приёмник?
2. **Адаптер VK Play Live.** Где будет жить внешний процесс с чат-клиентом
   (`stream_start`/`stream_end`) и кто его обслуживает? Альтернатива —
   слать события через уже работающего ВК-бота `CHRDK_VK`; тогда нужен
   служебный формат команды и право на неё.
3. **Подтверждение владения.** Автоматическая проверка кода (заголовок
   трансляции Twitch через Helix / сообщение в чате VK Play) или на этапе 1
   оставить только `/liveadmin verify` администратором?

Ответы не блокируют документ и каркас: они нужны лишь для включения живой
автоматики и честного smoke-отчёта на staging.

---

## 13. Источники (проверено 6 ноября 2026)

- Twitch EventSub, подпись, подписки, challenge, replay: [1](https://hookdeck.com/webhooks/platforms/guide-to-twitch-webhooks-features-and-best-practices), [2](https://hookdeck.com/webhooks/skills/twitch-webhooks), [3](https://github.com/hookdeck/webhook-skills/blob/main/skills/twitch-webhooks/SKILL.md).
- Практика `stream.online`/`stream.offline`, reconcile и safety-net опрос: [4](https://github.com/katogaming88/WGA-Raid-Hub/issues/1262).
- VK Play Live: события `stream_start`/`stream_end` в сообщественном чат-клиенте: [1](https://github.com/thearturca/vkplay-live-message-client).
- VK Play Live: embed-события плеера (браузерный путь, не серверный): [3](https://qna.habr.com/q/1405876); оповещение подписчиков ВК «раз в час»: [4](https://vk.ru/@authors-create-stream); анонс открытого API 2017 года: [5](https://vk.company/ru/press/releases/9937/).

Источники использованы только для проектирования контрактов; ни один внешний
API в песочнице не вызывался и в коде не реализован.
