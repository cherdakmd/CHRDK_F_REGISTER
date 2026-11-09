# CHRDK_VK 1.5.2-rc.9 — текстовые посты стены и PNG в беседу (пререлиз)

**Это пререлиз только модуля `CHRDK_VK`.** В релизе ровно один ассет —
`chrdk-vk-1.5.2-rc.9.jar`. Общий ZIP комплекта, `CHRDK_Disasters`, заметки и
ассеты `v1.5.2`, `v1.5.2-rc.6`, `vk-v1.5.2-rc.5`, `vk-v1.5.2-rc.7` и
`vk-v1.5.2-rc.8` не пересобираются и не переписываются. `Latest` по-прежнему
`v1.5.2`; `main`, GitHub Pages и PR в `main` не затрагивались. Core API и
`API_VERSION=42` не менялись.

`plugin_version` в `gradle.properties` остаётся `1.5.2-rc.6`: версию
`1.5.2-rc.9` JAR получает через `-Pplugin_version` в `vk-prerelease.yml`.
Тег начинается с `vk-v`, поэтому общий `release.yml` не срабатывает.
**Анонс в беседу ВК не отправляется** — шаг живой отправки `messages.send`
пропускается; офлайн-превью заметки для игроков остаётся в репозитории.

## Что изменилось

По умолчанию пять штатных постов стены — дайджест `WallDigestService` и четыре
карточки `DailyCardsService` — публикуются **текстом**, без PNG на стене. К
каждому из них и к `/vkadmin testpost wall` добавляется подпись:

```text
обсудить — https://vk.me/join/IAYORcZm0ZVtjPdoDazBHP5yymulHst4fxM=
```

PNG-карточки уходят **в беседу ВК** силами плагина. Для этого добавлен
`VkChatPhotoPublisher`: `photos.getMessagesUploadServer(peer_id)` → проверка
адреса загрузки по allowlist доменов VK → multipart →
`photos.saveMessagesPhoto` → `messages.send` с вложением `photo<owner>_<id>`,
`group_id`, `dont_parse_links=1` и стабильным положительным `random_id` из guid
поста. Весь контур работает на одном `community.access-token`; отдельный
`community.photo-user-token` здесь не нужен. В сообщения беседы подпись
«обсудить» не добавляется.

Новые ключи в `vk/src/main/resources/config.yml`:

| Ключ | Значение по умолчанию | Назначение |
| --- | --- | --- |
| `wall.post-mode` | `text` | текст на стене; `photo` включает прежние PNG на стене rc.8 |
| `wall.discuss-label` | `обсудить` | надпись перед ссылкой |
| `wall.discuss-url` | `https://vk.me/join/IAYORcZm0ZVtjPdoDazBHP5yymulHst4fxM=` | ссылка на беседу; пусто = без подписи |
| `wall.chat-cards` | `true` | отправлять PNG дайджеста и четырёх карточек в беседу |

Dual-token код rc.8 сохранён как опция. `wall.post-mode: photo` использует
`community.photo-user-token` только для `photos.getWallUploadServer` /
`photos.saveWallPhoto`, а финальный `wall.post` остаётся на community token.
Если выбрать `photo`, но не задать пользовательский ключ, плагин пишет
предупреждение и **автоматически работает как `text`**: постеры не отключаются,
стена продолжает получать пять текстовых постов, а PNG уходят в беседу. Ключи не
запрашиваются в чате, не коммитятся и не вставляются агентом в конфиг.

`/vkadmin testcard` переведён с проверки wall-upload на проверку PNG в беседу:
рисует техническую карточку `CHRDK_TEST`, отправляет её через messages-upload и
**не создаёт запись на стене**, не пишет суточные ledger'ы, аудит и
`last_announced_post_id`. Команда теперь требует настроенную беседу, а не
`community.photo-user-token`; при `wall.chat-cards: false` или неправильном
`peer_id` она отвечает локализованной причиной до обращения к VK. Расширять
`/vkadmin testpost photo` не требовалось: `testpost wall` остаётся текстовым и
получает подпись.

## Границы и совместимость

* `VkWallPublisher` по-прежнему умеет PNG на стену в режиме `photo`; его
  `validateUploadUrl`, лимит 5 MiB, PNG-сигнатура и multipart переиспользуются
  беседным публикатором. Два разных upload-контура не смешиваются.
* `VkApiClient.sendMessage` получил перегрузки с `attachment`, `groupId` и
  стабильным `randomId`; прежние вызовы сообщений, клавиатур, callback-кнопок и
  `dont_parse_links=1` не изменились.
* Ошибка отправки PNG в беседу логируется отдельно и не отменяет уже
  опубликованную запись стены; повтор стены не создаётся. Дедуп по
  `wall-digest.json` / `daily-cards.json` и стабильные `guid` сохранены.
* Схема миграций `vk/config-schema.yml` не вводилась. Новые `wall.*` ключи
  читаются из дефолтов JAR при старте и `/vkadmin reload`; владельческий
  `config.yml` не перезаписывается. Токен администратор вводит в свой
  `plugins/CHRDK_VK/config.yml` сам.
* Python-контур `tools/social/` по-прежнему не вызывает `wall.post`, `wall.get`
  и `photos.saveWallPhoto`; офлайн-превью RC без сети и без анонса.
* Агент не делал живых `api.vk.com`, RCON, `wall.post`, `/vkadmin testcard`,
  `messages.send`, установки на production или игрового smoke. Живые проверки
  выполняет администратор на своём сервере.

## Что сделать на сервере

1. Заменить только `chrdk-vk-1.5.2-rc.9.jar`; остальные JAR комплекта не
   обновляются.
2. Убедиться, что в `plugins/CHRDK_VK/config.yml` заданы
   `community.group-id`, `community.access-token`, `group-chat.enabled` и
   привязанная беседа: `group-chat.peer-id` либо сохранённый `/vkadmin setchat`
   `peer_id`. Ссылка `vk.me/join/...` из подписи сама по себе не подставляет
   числовой ID беседы.
3. Оставить `wall.post-mode: text` и `wall.chat-cards: true`; при необходимости
   поправить `wall.discuss-label` / `wall.discuss-url`. Пустой `discuss-url`
   отключает подпись.
4. Сделать `/vkadmin reload` или полный рестарт. В логах должно быть
   «стена: текстовые посты с подписью «обсудить», PNG-карточки уходят в беседу».
5. По желанию проверить текст: `/vkadmin testpost wall`. Проверить PNG в беседе:
   `/vkadmin testcard` (или `/vkadmin testcard Мой текст`). Успешный ответ
   сообщает об отправке в беседу, а не возвращает `post_id`.
6. Если позже понадобятся PNG на стене, администратор сам добавляет classic
   `vk1.a.*` с `wall,photos,offline` в `community.photo-user-token` и ставит
   `wall.post-mode: photo`. Не присылайте ключ в чат, issues или коммит. Без
   ключа режим photo автоматически остаётся текстовым.

## Проверено

* Локально: `git diff --check`, tree-sitter Java, YAML/JSON, контракт
  сообщений, retired/Incendium, тесты `.github/scripts`, `tools/wiki`,
  `tools/social`, офлайн-превью `prerelease.py --check` для
  `vk-v1.5.2-rc.9`, `freshness.py` для `profile`, `social-link` и `vk`.
* JUnit модуля `vk`: конфиг `text`/`photo`/fallback, подпись только на стене,
  цепочка messages-upload на fake-транспорте, отказ чужого upload-хоста,
  `VkWallPublisher` и dual-token маршрутизация rc.8, контракт `/vkadmin
  testcard`.
* В workflow: `./gradlew :vk:jar -Pplugin_version=1.5.2-rc.9`,
  `verify_release.py --standalone`, проверка встроенных шрифтов/фона и ровно
  одного ассета.

## Откат

Вернуть `chrdk-vk-1.5.2-rc.8.jar` (или стабильный `chrdk-vk-1.5.2.jar`) —
текстовый режим rc.9, подпись «обсудить» и беседные PNG при этом пропадут.
Чтобы временно выключить только картинки в беседе, достаточно
`wall.chat-cards: false` и `/vkadmin reload`. Если включены суточные посты,
`wall-digest.json` и `daily-cards.json` возвращать вместе с конфигом
`CHRDK_VK`, иначе возможны пропуски или дубли.
