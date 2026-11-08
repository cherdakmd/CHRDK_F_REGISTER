# Дайджест на стену сообщества ВК (plugin-owned)

Статус: **реализовано в ветке, после стабильного 1.5.2**. Выпущен отдельный
пререлиз только модуля: тег `vk-v1.5.2-rc.5`, один ассет
`chrdk-vk-1.5.2-rc.5.jar`, заметки `docs/releases/vk-1.5.2-rc.5.md`.
Стабильный `v1.5.2` и его ассеты не переписаны, `plugin_version` остался
`1.5.2`. Установки на живой сервер не было.

## Что именно публикуется

Один оформленный пост **в сутки** на стену сообщества (не пост на каждое
событие). Оперативные события, советы и релизные новости по-прежнему уходят в
беседу и на стену не дублируются. Владелец стены ровно один — `CHRDK_VK`.

Карточка 1280×720 в стиле уже существующих постеров сообщества: фон
`world-background.jpg`, градиент, логотип «ЧЕРДАК», бейдж «ИТОГИ ДНЯ», дата,
3–4 блока и подвал «ТВОЙ МИР. ТВОЯ ИСТОРИЯ.». Блоки:

| Блок | Источник | Что уходит на публичную стену |
| --- | --- | --- |
| Престиж | `ChrdkSkillsApi.totalPrestige(Player)` через `ServicesManager`, опрос раз в `poll-minutes` | число новых ступеней; топ-3 ника только при `show-player-names: true` и только с учётом opt-out |
| Поддержка сервера | `PassGrantedEvent` из CHRDK_Core | число выдач проходки/ранга; тестовые платежи отсекаются по `source` |
| Сервер | `PlayerJoinEvent` + опрос онлайна | пик онлайна и число новичков |
| Тихий день | — | нейтральная строка, если порог `min-events` снижен до 0 |

Суммы, SKU, UUID, IP, время входа и названия товаров **не публикуются никогда** —
ни в текст, ни в картинку, ни в аудит. По умолчанию
`show-player-names: false`: на стене только обезличенные цифры. Opt-out игрока
берётся из уже существующего `VkStore.receivesGroupChat(...)` (тот же флаг, что
скрывает сообщения беседы) — отдельной команды не добавлялось.

## Где это работает

Весь рантайм — в плагине на сервере владельца:

```
Bukkit-события + ChrdkSkillsApi
        ↓  WallDigestCollector → wall-digest.json ( getDataFolder() )
        ↓  WallDigestCard (Java2D, шрифт и фон внутри JAR) → PNG
        ↓  VkWallPublisher: photos.getWallUploadServer
        ↓  multipart POST → photos.saveWallPhoto
        ↓  wall.post(owner_id=-group_id, from_group=1, attachments=photo…, guid=…)
        ↓  SocialAudit: social.wall.digest
```

GitHub Actions, GitHub Issues, служебный чат и write-токен репозитория **не
являются зависимостями**. CI используется только для сборки и тестов.
Python-контур `tools/social/` (`cards.py`, `publisher.py`) не трогался: его
allowlist по-прежнему запрещает `wall.post`, `wall.get` и
`photos.saveWallPhoto`, а `tools/social/test_social.py` этот запрет проверяет.
Два контура не пересекаются и не создают двух владельцев стены.

## Файлы

| Файл | Роль |
| --- | --- |
| `vk/src/main/java/ru/cherdakmd/chrdkvk/WallDigestConfig.java` | ключи `wall-digest.*`, дефолты, клампы, пояс владельца |
| `WallDigestStore.java` | персистентный ledger: счётчики, снимок престижа, дедуп, попытки |
| `WallDigestCollector.java` | чистая JVM-логика суток, приростов и топа |
| `WallDigestCard.java` | Java2D постер и короткий текст записи |
| `VkWallPublisher.java` | photos → wall.post, allowlist хостов загрузки |
| `WallDigestService.java` | слушатели, таймеры, окно повторов, аудит |
| `VkApiClient.java` | `call(...)`, `postMultipart(...)`; `Transport` получил default-метод, старые fake-транспорты не сломаны |
| `VkPlugin.java` | создание/старт/остановка сервиса |
| `vk/src/main/resources/config.yml` | секция `wall-digest` с комментариями |
| `vk/src/main/resources/plugin.yml` | `softdepend: CHRDK_Skills` |
| `vk/src/main/resources/assets/` | `DejaVuSans.ttf`, `DejaVuSans-Bold.ttf`, `world-background.jpg` |
| `vk/build.gradle` | `compileOnly/testImplementation project(':skills')` |
| `vk/src/test/java/.../WallDigest*Test.java`, `VkWallPublisherTest.java` | офлайн-тесты |

`ChrdkRegisterApi.API_VERSION` остался **42**: используется уже существующий
`default`-метод `ChrdkSkillsApi.totalPrestige(Player)` и существующее
`PassGrantedEvent`. `ChrdkRegisterApiImpl.grantEntitlement` (deny-stub) и
`AccessManager:1649` не трогались; произвольные SKU-покупки магазина в дайджест
намеренно не входят — для них понадобился бы новый ивент ядра.

## Включение и права

Новые ключи **необязательны**: при их отсутствии читаются дефолты, поэтому после
замены JAR feature поднимается без ручного редактирования чужого `config.yml`
и без перезаписи владельческих значений. Штатная перезагрузка конфига плагина
подхватывает новое расписание и ключ без рестарта сервера
(`WallDigestService.applyConfig`). Схема миграций `vk/config-schema.yml`
намеренно не вводилась — чтобы не возвращать владельцу все стоковые ключи
модуля, как это делает v2→v3 у магазина. Отключение: `wall-digest.enabled: false`.

По умолчанию публикация в **21:00 Europe/Moscow** (UTC+3), ключ игровых суток —
`YYYY-MM-DD` в этом поясе. `min-events: 1` подавляет пустой день. Окно повторов
— пять минут после назначенного времени, не более `MAX_ATTEMPTS = 3` попыток за
сутки: временный сбой VK не спамит стену и не приводит к дублям.

**Обязательные внешние условия** (без них плагин пишет предупреждение и пост не
уходит):

1. Ключ сообщества с правами `wall` **и** `photos`; текущего ключа для беседы
   недостаточно.
2. Бот должен быть администратором сообщества; иначе `wall.post` вернёт код 38.
3. `community.group-id` и `community.access-token` заданы (`apiConfigured()`).

`wall.get` с ключом сообщества по-прежнему не используется (в `VkConfig` он
выключен), `wall.announce-new-posts` по-прежнему делает «стена → игровой чат»,
а не наоборот.

## Дедупликация и отказы

* `wall-digest.json` пишется атомарно через `.tmp` + `move`, как остальные
  хранилища проекта. Битый JSON → чистые сутки + предупреждение в лог.
* Перед сетевой частью сохраняется `postingDay` и счётчик попыток; после успеха
  — `lastPostedDay` и `post_id`. Повтор за те же сутки не делается.
* Если процесс прервался между `wall.post` и записью ledger, при следующем
  старте такие сутки **пропускаются** с предупреждением: дубль на публичной
  стене хуже одного пропущенного поста.
* Дополнительно `wall.post` получает стабильный `guid = chrdk-wall-<день>`, а в
  конце текста записи стоит короткий маркер `#chrdk_<hash>` для ручной сверки.
* Адрес загрузки фото проверяется по allowlist `vk.com`, `userapi.com`,
  `vkuserphoto.ru` и только по HTTPS — ответ API не может отправить PNG на
  сторонний хост.

## Проверки

Офлайн-тесты (JUnit 5, модуль `vk`): агрегация суток и снимок престижа, opt-out
и топ, дедуп/попытки/рестарт, клампы конфига и пояс, реальный PNG нужного
размера, текст без приватных данных и вся цепочка `photos → wall.post` на
fake-транспорте, включая отказ чужого upload-хоста.

Живой `api.vk.com` из песочницы недоступен, `java`/`javac` в ней отсутствуют:
компиляция и JUnit выполняются в CI, а реальная публикация — только на сервере
владельца после выдачи прав ключа. RCON, TradeMC и живой smoke не выполнялись.
