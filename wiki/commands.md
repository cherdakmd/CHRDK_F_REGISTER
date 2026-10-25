---
title: "Все команды"
description: "Одна страница со всеми командами игрока: ядро, чат, экономика, прогресс, приключения и гильдии."
---

Команды сгруппированы по темам. Подробности, цены и ограничения — на странице
соответствующего раздела: команда без контекста часто выглядит проще, чем есть.

## Основное

| Команда | Что делает |
|---|---|
| `/menu` | Меню-карта: семь разделов и все возможности |
| `/guide` | Книга-путеводитель по серверу |
| `/profile [ник]` | Карточка игрока: ранг, прогресс, экономика |
| `/rank [shop\|my\|top\|rules]` | Витрина рангов, свой ранг, топ, правила |
| `/pass` | Статус аккаунта и 2FA |
| `/hat` | Надеть блок из руки на голову (ранг V) |
| `/wiki [страница]` | Ссылка на этот сайт в чат: команды, цены и лимиты |

## Вход и безопасность

| Команда | Что делает |
|---|---|
| `/register <пароль> <повтор>` | Зарегистрировать аккаунт |
| `/login <пароль> [код 2FA]` | Войти |
| `/logout` | Выйти и сбросить сессию по IP |
| `/changepassword <старый> <новый>` | Сменить пароль |
| `/2fa <код>` | Подтвердить вход из приложения |
| `/2fa setup` · `/2fa qr` | Показать секрет и карту с QR-кодом |
| `/2fa confirm <код>` | Завершить настройку 2FA |
| `/2fa disable <код\|пароль>` | Отключить 2FA (если это разрешено) |
| `/captcha <число>` | Ответ на проверку «ты не бот» |
| `/session` · `/session end` | Активные сессии и их завершение |

## Чат

| Команда | Что делает |
|---|---|
| `/chat` | Переключить канал: локальный ↔ глобальный |
| `/chat global` · `/chat local` | Выбрать канал |
| `/chat status` | Текущий канал и радиус локали |
| `!текст` | Одно сообщение в глобальный чат |
| `/msg <ник> <текст>` · `/reply <текст>` | Личные сообщения и ответ |
| `/ignore <ник>` · `/ignore list` | Скрыть игрока и посмотреть список |

## Экономика

| Команда | Что делает |
|---|---|
| `/bal` · `/baltop` | Баланс банка и топ |
| `/pay <ник> <сумма>` | Перевод алмазов |
| `/shop [balance\|deposit\|withdraw\|price\|buy\|sell\|stats\|help]` | Алмазный магазин |
| `/auction [sell <цена>\|buy <id> confirm\|cancel <id>\|mail\|claim <id>\|money\|mine]` | Аукцион игроков |
| `/orders [list\|deliver <номер>\|stats [ник]\|top\|help]` | Доска заказов от ботов |
| `/lottery [buy [n]\|info\|top\|last\|claim]` | Лотерея недели |
| `/ref [use <ник>\|top]` | Рефералы и бонусные дни |

## Мир

| Команда | Что делает |
|---|---|
| `/claim` | Купить чанк под приват |
| `/claim menu` · `/claim map` · `/claim list` | Приваты и карта окрестностей |
| `/claim info [x z]` · `/claim near` · `/claim forsale` | Сводка, ближние и продажа |
| `/claim balance` · `/claim deposit` · `/claim withdraw` | Казна приватов и рента |
| `/claim add <ник> [роль]` · `/claim remove <ник>` | Участники и роли |
| `/claim sell <цена>` · `/claim buy [x z]` · `/claim sellback` | Продажа, покупка, выкуп сервером |
| `/claim transfer <ник>` · `/claim unclaim` · `/claim borders` | Передача, снятие, границы |
| `/rtp` | Случайная точка в мире |
| `/tpa <ник>` · `/tpahere <ник>` · `/tpaccept` · `/tpdeny` | Запросы телепорта |
| `/sethome <имя>` · `/home [имя]` · `/homes` · `/delhome <имя>` | Дома |

## Прогресс

| Команда | Что делает |
|---|---|
| `/prof [menu\|choose <профессия>\|info\|stats\|top]` | Профессии |
| `/skills [menu\|list\|top\|<навык>]` | Навыки |
| `/class [menu\|choose <класс>\|info\|talents\|learn <талант>\|respec\|points\|top\|ability [1\|2]]` | Класс, дерево талантов и умения |
| `/ability [1\|2]` | применить умение класса: без аргумента — первое, `2` — второе |
| `/quests [menu\|list\|info\|claim\|join <фракция>\|leave\|faction]` | Задания и фракции |
| `/battlepass [menu\|top\|info <id>\|claim [id\|all]\|help]` | Боевой пропуск |
| `/achievements [menu\|list\|badge <ид\|off>]` | Достижения и бейджи |
| `/daily [claim\|info]` | Ежедневная награда |
| `/ranks [ник]` | Звание по стажу |
| `/goals` | Общие цели сообщества |
| `/stream <link\|check\|status\|unlink\|top\|viewers\|viewer\|watch\|watching\|code\|stats>` | Стримерская программа |

## Приключения

| Команда | Что делает |
|---|---|
| `/treasures [menu\|list\|loot\|set\|map\|stats\|top\|shards\|badge\|toggle]` | Клады, коллекция, осколки |
| `/hunt [stats\|shop\|buy\|exchange\|keys\|chest\|trophies\|trophy\|top\|help]` | Охота, монеты, ключи |
| `/boss [menu\|list\|info\|zones\|where]` | Боссы и зоны |
| `/events [info\|next\|list]` | Мировые события |

## Снаряжение

| Команда | Что делает |
|---|---|
| `/enchants [info <ид>]` | Кастомные чары |
| `/tiers [info\|stats\|top\|effects\|collection\|collection title\|collection top]` | Кузня тиров |
| `/artifacts [<ид>\|traits\|collection]` | Артефакты и коллекция |
| `/cosmetics [list\|off\|<эффект>]` | Шлейфы |
| `/pet [help\|list\|top\|name <имя>]` | Питомцы |

## Общество

| Команда | Что делает |
|---|---|
| `/party [create\|invite\|accept\|deny\|leave\|kick\|disband\|list\|info\|chat\|help]` | Группа |
| `/pc <текст>` | Чат группы |
| `/guild [menu\|create\|invite\|deposit\|war\|info\|top]` | Гильдия |
| `/gchat <текст>` | Чат гильдии |
| `/queue info` | Очередь на вход и своё место в ней |

> **Не помните синтаксис?** Введите команду без аргументов — сервер покажет
> подсказку. Полная карта — `/menu`.
