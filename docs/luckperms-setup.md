# Настройка LuckPerms для CHRDK

Права и группы сервера живут в [LuckPerms](https://luckperms.net/). Ядро
`CHRDK_Core` умеет выстроить схему одной командой; этот документ описывает и
автоматический путь, и ручную правку через веб-редактор luckperms.net — на
случай, когда авто-настройку запускать нежелательно.

## Автоматически: `/pass luckperms setup`

Команда доступна с правом `chrdkregister.admin` (из консоли — всегда),
идемпотентна: повторный запуск только достраивает недостающие узлы, чужие
группы и права не удаляет.

```
pass luckperms setup
pass luckperms status
```

`setup` создаёт группы базовой схемы **и группы товаров каталога** (`group:` в
`shop.products`), `status` показывает вес и число узлов каждой группы.
Правила аддонов подхватываются из установленных `plugin.yml`: все `chrdk*.use`
уходят в `default`, все `chrdk*.admin` — в `admin`, поэтому новые аддоны
покрываются без перевыпуска ядра.

| Группа | Вес | Родитель | Назначение |
| --- | ---: | --- | --- |
| `default` | 0 | — | базовые права игрока, явные запреты служебных команд |
| `<integrations.pass-group>` (`pass`) | 10 | `default` | активный донатный ранг/проходка |
| `social-day` | 20 | `default` | временная группа товара [`social-access-day`](shop/social-access-day.md) |
| `admin` | 100 | `default` | административные права, разрешения служебных команд |

## Права социального плагина

Один JAR `chrdk-vk-*.jar` регистрирует три узла:

| Узел | Кому | `default` в `plugin.yml` |
| --- | --- | --- |
| `chrdksocial.use` | всем игрокам (код привязки, свой статус) | `true` |
| `chrdksocial.admin` | администрации | `op` |
| `chrdksocial.bypass` | **только** временной группе товара `social-day` | `op` |

`chrdksocial.use` и `chrdksocial.admin` добавлены в базовые списки
`LuckPermsBootstrap` (`DEFAULT_PERMS` / `ADMIN_PERMS`), поэтому `/pass luckperms
setup` выставляет их даже если аддон не был загружен в момент запуска команды.

> ⚠ `chrdksocial.bypass` снимает **обязательную привязку к ВК/MAX**. Его нельзя
> выдавать в `default`, `pass` или `admin`: тогда ограничение обходится у всех
> игроков. `/pass luckperms setup` явно вырезает этот узел из базовых и
> административных списков — даже если владелец добавил его в
> `integrations.pass-group-permissions` или плагин зарегистрировал право под
> нестандартным именем. Узел выдаётся только группой `social-day` на 1 день.

Имя узла настраивается:

```yaml
# plugins/CHRDK_Core/config.yml
integrations:
  lp-social-bypass-permission: "chrdksocial.bypass"

# plugins/CHRDK_VK/config.yml — должно совпадать
social:
  bypass-permission: "chrdksocial.bypass"
```

Если переименовали узел, поправьте **оба** ключа и `permissions:` товара
`social-access-day` в `shop.products`.

## Вручную: веб-редактор luckperms.net

1. На сервере выполните `/lp editor` (или `/luckperms editor`). Команда
   вернёт одноразовую ссылку на <https://luckperms.net/editor/> — данные групп
   подгружаются в браузер, обратно импортируется готовый набор команд.
   Ссылка одноразовая, не публикуйте её в чате.
2. В редакторе проверьте четыре группы из таблицы выше. Для `social-day`
   выставьте **weight 20**, родителя `default` и ровно один узел
   `chrdksocial.bypass` со значением `true`.
3. Убедитесь, что `chrdksocial.bypass` **отсутствует** в `default`, `pass` и
   `admin`. Если он там есть — удалите узел (или выставьте `false` в `default`).
4. В `default` добавьте `chrdksocial.use`, в `admin` — `chrdksocial.admin`
   (при запущенном `/pass luckperms setup` это уже сделано).
5. Примените изменения из редактора и выполните `/lp sync` / `/lp reload`.
   Набор служебных команд (`/plugins`, `/op`, `/ban`, `/whitelist`, ...) должен
   остаться явно запрещённым в `default` — это `integrations.lp-command-protect`.

Для одного игрока группу можно проверить без редактора:

```
/lp user <ник> info
/lp user <ник> parent addtemp social-day 1d
/lp user <ник> parent removetemp social-day
```

Постоянно добавлять `social-day` вручную не нужно: покупка через
`pass shopbuy %username% social-access-day` выдаёт временную группу сама и снимает
её по истечении срока.

## Конфиг `integrations` (справка)

| Ключ | По умолчанию | Смысл |
| --- | --- | --- |
| `pass-group` | `""` | группа активного ранга; пусто — синк выключен |
| `pass-group-paid-only` | `true` | группа — признак реальной оплаты, пробный период её не даёт |
| `pass-group-permissions` | `[]` | доп. узлы группы проходки |
| `lp-command-protect` | `true` | явные запреты служебных команд в `default` |
| `lp-deny-extra` | `[]` | доп. запреты к встроенному списку |
| `lp-social-bypass-permission` | `chrdksocial.bypass` | узел, который setup никогда не выдаёт base-группам |

## Типичные проблемы

- **«LuckPerms=true» в startup-логе, но `/pass luckperms status` пуст** — с
  1.4.72 команда и сервис используют один и тот же объект хука; перезапустите
  сервер и повторите `setup`.
- **Игрок без ВК пускается в лобби, хотя товар не покупал** — проверьте
  `/lp user <ник> info`: вероятно, `chrdksocial.bypass` выдан вручную или
  унаследован от `default`/`admin`.
- **Купил товар, но лобби заморожено** — группа есть, а entitlement нет (или
  наоборот). Проверьте `product-ids` в `plugins/CHRDK_VK/config.yml` и запись в
  `data.json` ядра. При недоступном `ChrdkRegisterApi` вход закрывается
  намеренно (fail-closed).
- **Группа `social-day` не создалась** — выполните `/pass luckperms setup`
  или создайте её в редакторе luckperms.net вручную.

Связанные документы: [товар на 1 день](shop/social-access-day.md),
[товар на 30 дней](shop/social-access.md), [комплект TradeMC](trademc/README.md).
