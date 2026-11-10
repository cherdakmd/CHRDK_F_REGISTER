# CHRDK_VK 1.5.2-rc.8 — отдельный пользовательский ключ для фото (пререлиз)

**Это пререлиз только модуля `CHRDK_VK`.** В релизе ровно один ассет —
`chrdk-vk-1.5.2-rc.8.jar`. Общий ZIP комплекта, `CHRDK_Disasters`, заметки и
ассеты `v1.5.2`, `v1.5.2-rc.6`, `vk-v1.5.2-rc.5` и `vk-v1.5.2-rc.7` не
пересобираются и не переписываются. `Latest` по-прежнему `v1.5.2`; `main`,
GitHub Pages и PR в `main` не затрагивались. Core API и `API_VERSION=42`
не менялись.

`plugin_version` в `gradle.properties` остаётся `1.5.2-rc.6`: версию
`1.5.2-rc.8` JAR получает через `-Pplugin_version` в `vk-prerelease.yml`.
Тег начинается с `vk-v`, поэтому общий `release.yml` не срабатывает.
**Анонс в беседу ВК не отправляется** — как и в rc.7, шаг живой отправки
пропускается; сохраняется только офлайн-превью заметки.

## Почему это нужно

На `vk-v1.5.2-rc.7` владелец выполнил `/vkadmin testcard` на сервере и получил:

```text
VK API error 27 in photos.getWallUploadServer; response hidden
```

Текстовый `/vkadmin testpost wall` тем же ключом сообщества проходил, значит
ключ живой, `community.group-id` и право `wall` в порядке. Ошибка возникает
только на загрузке PNG: VK не разрешает `photos.getWallUploadServer` с
авторизацией сообщества (group auth). Галочки Long Poll, «Типы событий»,
перевыпуск community key и `api-version: "5.199"` это не лечат.
`Fontconfig error: No writable cache directories` — отдельное предупреждение
кэша шрифтов Java2D и не причина 27.

## Что изменилось

Добавлено **разделение токенов**:

| Ключ | Где используется |
| --- | --- |
| `community.access-token` | ключ сообщества: сообщения, Bots Long Poll и финальный `wall.post` |
| `community.photo-user-token` | классический пользовательский ключ администратора: только `photos.getWallUploadServer`, multipart-загрузка и `photos.saveWallPhoto` |

* `VkConfig` читает `community.photo-user-token`, обрезает пробелы и даёт
  `photoPublishConfigured()`: постеры включаются только когда заданы и ключ
  сообщества, и пользовательский ключ.
* `VkPlugin.communityApiClient()` и `VkPlugin.photoApiClient()` создают два
  независимых `VkApiClient` с одним `api-base-url` и `api-version`, но разными
  токенами. Фото-клиент намеренно не подставляет community token вместо
  отсутствующего пользовательского ключа. Значения ключей по-прежнему уходят
  только в теле POST и маскируются в ошибках (`response hidden`).
* `VkWallPublisher` получил конструктор с двумя клиентами. `photos.*` и
  multipart идут через `photoApi`; `wall.post` с `from_group=1` и
  `owner_id=-<group-id>` — через `api` с ключом сообщества. Старый конструктор
  с одним клиентом сохранён для обратной совместимости и тестов.
* `VkService.publishTestCard`, `VkCommandHandler`, `DailyCardsService` и
  `WallDigestService` используют оба клиента. Без
  `community.photo-user-token` автопостинг постеров не планируется, а
  `/vkadmin testcard` сразу отвечает локализованным сообщением про
  `community.photo-user-token` вместо живого `error 27`.
* При старте плагин пишет предупреждение, если фото-ключ не задан, но ключ
  сообщества настроен. Текстовые посты, мост чата, привязки, 2FA и
  `wall.announce-new-posts` от этого не зависят.
* Обновлены `vk/src/main/resources/config.yml`, `messages.yml`,
  `docs/VK_WALL_DIGEST.md`, `wiki/vk.md`; добавлены офлайн-тесты
  `VkConfigTest`, `VkWallPublisherTest`, `VkPhotoTokenTest`, `VkTestCardTest` и
  заметка для игроков `docs/social/prereleases/1.5.2-rc.8.json`.

## Как получить пользовательский ключ

Нужен **классический пользовательский токен администратора сообщества** вида
`vk1.a.*` со scopes `wall,photos,offline`. Владелец получает его через
Standalone / implicit OAuth (`redirect_uri=https://oauth.vk.com/blank.html`)
от имени аккаунта, который является администратором группы
`community.group-id`. `offline` нужен, чтобы токен не протухал после закрытия
браузера.

Это **не** ключ сообщества, **не** Callback secret, **не** MAX-токен и **не**
токен VK ID вида `vk2.a.*`. Не присылайте ключ в чат, не коммитьте его и не
кладите в issues. Если токен отозван или истёк, замените строку в конфиге и
сделайте `/vkadmin reload`.

## Что сделать на сервере

1. В `plugins/CHRDK_VK/config.yml` оставить прежний `community.access-token`
   и добавить рядом:

   ```yaml
   community:
     group-id: 237779683
     access-token: "существующий ключ сообщества"
     photo-user-token: "пользовательский ключ администратора"
   ```

   Схема миграций `vk/config-schema.yml` намеренно не вводилась: новый ключ
   добавляется в владельческий `config.yml` вручную, чужие значения и
   комментарии не перезаписываются.
2. Сделать `/vkadmin reload` или полный рестарт. В логах должно исчезнуть
   предупреждение про `community.photo-user-token`.
3. Заменить только `chrdk-vk-1.5.2-rc.8.jar`; остальные JAR комплекта не
   обновляются.
4. Проверить текст: `/vkadmin testpost wall`.
5. Проверить фото: `/vkadmin testcard`. Успешный ответ вернёт `post_id` и
   ссылку на запись с PNG. `guid=chrdk-testcard-<UUID>`, маркер
   `#chrdk_test_card_<8 hex>`; суточные ledger'ы и
   `last_announced_post_id` не трогаются.

Если `error 27` останется и после этого — проверьте, что в
`photo-user-token` попал именно пользовательский `vk1.a.*`, а не ключ
сообщества, что scopes включают `photos`, что аккаунт всё ещё администратор
группы и что строка не обрезана при копировании. Само значение ключа в логи
не попадает.

## Границы

* Python-контур `tools/social/` по-прежнему не вызывает `wall.post`,
  `wall.get` и `photos.saveWallPhoto`; офлайн-превью без сети.
* `wall.get` с ключом сообщества не используется; `wall.announce-new-posts`
  по-прежнему «стена → игровой чат».
* Агент не делал живых `api.vk.com`, RCON, `wall.post`, установки на
  production или игрового smoke. Ошибку 27 на rc.7 получил владелец на своём
  сервере; пользовательский ключ получаете и вводите вы.
* Нет bump `config-schema.yml`, нет правок исторических release notes, нет
  изменения `main`/Pages/PR, прежних тегов/ассетов и `Latest`.

## Проверено

* Build ветки под Purpur 26.3 / Java 25: JUnit модуля `vk`, контракт
  сообщений, сборка вики (39 страниц), Social automation offline.
* Локально: `git diff --check`, tree-sitter Java, YAML/JSON,
  `check_messages.py`, `freshness.py`, `tools/wiki/build.py --check`, тесты
  `.github/scripts`, `tools/wiki`, `tools/social`, офлайн-превью
  `prerelease.py --check` для `vk-v1.5.2-rc.8`.
* В workflow: `./gradlew :vk:jar -Pplugin_version=1.5.2-rc.8`,
  `verify_release.py --standalone`, проверка встроенных шрифтов/фона и ровно
  одного ассета.

## Откат

Вернуть `chrdk-vk-1.5.2-rc.7.jar` (или стабильный `chrdk-vk-1.5.2.jar`) и
убрать `community.photo-user-token`. Постеры снова отключатся с предупреждением,
а текстовый контур останется рабочим. Если включены суточные посты,
`wall-digest.json` и `daily-cards.json` возвращать вместе с конфигом
`CHRDK_VK`, иначе возможны пропуски или дубли.
