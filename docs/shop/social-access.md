# Товар «Вход без ВК и MAX» (`social-access`)

Карточка для сайта донатов (TradeMC): даёт играть без обязательной привязки к
ВКонтакте или MAX. Это **не ранг и не привилегия** — только снятие заморозки
лобби на оплаченный срок.

![Карточка товара 1200×1200](social-access.png)

| Поле | Значение |
| --- | --- |
| Название | Вход без ВК и MAX |
| `id` товара | `social-access` |
| Цена | **150 ₽** |
| Срок | **30 дней** |
| Продление | повторная покупка добавляет 30 дней, потолок 365 дней (`shop.max-total-days`) |
| Картинка | `docs/shop/social-access.png` (1200×1200, PNG) |
| Команда выдачи | см. ниже |

## Команда для товара на сайте

```
pass shopbuy %username% social-access
```

Без начального `/`: TradeMC выполняет команду через RCON/панель, а
`%username%` подставляет точный игровой nick, который покупатель указал при
оплате. Именно `%username%`, а не `%player%` и не чат игрока: подтверждением
оплаты служит только запись в `data.json` ядра, сделанная этой командой.

Команда уже работает без правок кода: `pass shopbuy <ник> <id_товара> [дней]`
читает товар из `shop.products` в `plugins/CHRDK_Core/config.yml`. Опциональный
`[дней]` для сайта **не нужен** — срок берётся из товара (30).

Альтернативу по сумме `pass shoppay %username% 150` рекламировать не следует:
`shoppay` выдаёт ранг/баланс по сумме, а не entitlement `social-access`, поэтому
не снимает заморозку лобби. Цена 150 ₽ уникальна среди товаров каталога, но
доставка должна идти по `id`.

## Что настроено в плагинах

**`plugins/CHRDK_Core/config.yml`** — товар в `shop.products`:

```yaml
    - id: social-access
      name: "<aqua>Вход без ВК и MAX</aqua>"
      price: 150
      days: 30
      group: ""
      description: "Играть без обязательной привязки к ВКонтакте или MAX — 30 дней. Не выдаёт ранг, привилегии и игровые бонусы"
      tag: ""
      permissions: []
      commands: []
      trademc-export: false
```

Если `shop.products` на сервере правился вручную, миграция презентационного
конфига намеренно не перезаписывает чужие цены — в этом случае добавьте блок
товара в `plugins/CHRDK_Core/config.yml` вручную.

`trademc-export: false` означает, что товар **не попадает в исторический комплект
из 26 карточек** `docs/trademc/`: у него своя карточка здесь. Тот же флаг уже
используют клановые лицензии. Экспорт TradeMC (`tools/trademc/build.py`)
фильтрует такие товары до проверки уникальности цен, поэтому архив, манифест и
26 PNG остаются неизменными.

**`plugins/CHRDK_VK/config.yml`** — режим платного доступа:

```yaml
social:
  donate-access:
    enabled: true
    mode: product-list
    product-ids: [social-access]
    permanent: false
    url: "https://chrdk.trademc.org/"
```

`product-list` означает, что другие товары и донатные ранги сами по себе **не**
дают обхода: заморозку снимает только активный `social-access` (или ручная
выдача `/social donor grant <ник>`, которая тоже пишется в `audit/*.jsonl`).
При недоступном `ChrdkRegisterApi` или пустом ответе ядра доступ закрывается
(fail-closed).

## Два счётчика в TAB

В `plugins/CHRDK_Core/config.yml` → `tablist.footer` добавлена строка:

```yaml
    - "<gray>Вход <aqua>%chrdksocial_access%</aqua> <dark_gray>│</dark_gray> Привязка <green>%chrdksocial_link%</green></gray>"
```

| Плейсхолдер | Что показывает | Примеры |
| --- | --- | --- |
| `%chrdksocial_access%` | остаток срока товара, персонально | `27 дн.`, `1 дн.`, `нет`, `∞` (бессрочная ручная выдача) |
| `%chrdksocial_link%` | персональный статус привязки | `ВК`, `MAX`, `ещё 12 дн.`, `обязательна`, `не требуется`, `аварийный вход` |

Оба приходят из `CHRDK_VK` через PlaceholderAPI: expansion
`ru.cherdakmd.chrdksocial.SocialPlaceholders` с идентификатором `chrdksocial`.
Регистрируется в `VkPlugin.onEnable()` только если PlaceholderAPI установлен;
без `CHRDK_VK`/PAPI в TAB останется пустая строка, но ядро работает как обычно.
Значения — обычный текст без MiniMessage, сутки округляются вверх (остался час →
`1 дн.`). Срок товара читается из `data.json` ядра через
`ChrdkRegisterApi.entitlementUntil(ник, "social-access")`, то есть переживает
рестарт и `/pass reload`.

`%chrdksocial_link%` считается от личного дедлайна
`max(общий дедлайн, первый вход + newcomer-grace-days)` — у двух игроков в один
и тот же момент значения могут отличаться.

## Проверки перед публикацией

```bash
python3 tools/trademc/build.py --check
python3 -m unittest discover -s tools/trademc -p 'test_*.py'
python3 tools/shop/build_social_access_card.py --check
```

Живая покупка, выдача через панель TradeMC и RCON, истечение срока, установка
на сервер и клиентский smoke в этом изменении **не выполнялись** — требуется
проверка на тестовом сервере после раскатки JAR.
