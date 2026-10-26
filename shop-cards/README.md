# Обложки карточек TradeMC

Квадратные картинки 1024×1024 для карточек витрины `chrdk.trademc.org` и
обложка магазина.

Состав витрины, цены и команды выдачи — в
[`docs/DONATE-CATALOG.md`](../docs/DONATE-CATALOG.md). Правило одно: **цена
карточки на панели должна точно совпадать с полем `price` товара**, иначе
ядро не найдёт товар по сумме платежа.

## Формат карточки

- 1024×1024, PNG без прозрачности: панель сама обрезает под свою сетку.
- Единый стиль серии: воксельный предмет на тёмном фоне, свечение по цвету
  товара, крупная цифра или иконка. Название и цену панель показывает текстом
  рядом — на миниатюре в списке мелкий текст всё равно не читается.
- В поле «команда при покупке»: `pass shoppay %username% <цена>` (без слэша).

## Обложка магазина

`shop-cover.png` — 1440×720, надпись «ЧРДК 2026». Ставится в
`panel.trademc.org` → «Настройка магазина» → оформление витрины/баннера.
Формат 2:1: при другой пропорции панель обрежет по центру, надпись и предмет
остаются в кадре.

## Что нужно нарисовать под витрину 1.4.188

| Карточка | Товар | Цена, ₽ | Команда |
|---|---|--:|---|
| ранг I | `rank-wanderer` | 99 | `pass shoppay %username% 99` |
| ранг I — год | `rank-wanderer-year` | 990 | `pass shoppay %username% 990` |
| ранг II | `rank-pathfinder` | 159 | `pass shoppay %username% 159` |
| ранг II — год | `rank-pathfinder-year` | 1 590 | `pass shoppay %username% 1590` |
| ранг III | `rank-keeper` | 229 | `pass shoppay %username% 229` |
| ранг III — год | `rank-keeper-year` | 2 290 | `pass shoppay %username% 2290` |
| ранг IV | `rank-patron` | 299 | `pass shoppay %username% 299` |
| ранг IV — год | `rank-patron-year` | 2 990 | `pass shoppay %username% 2990` |
| ранг V | `rank-founder` | 365 | `pass shoppay %username% 365` |
| ранг V — год | `rank-founder-year` | 3 650 | `pass shoppay %username% 3650` |
| дома | `homes-plus` | 109 | `pass shoppay %username% 109` |
| шлейфы | `trail-flame` … `trail-magic` | 39–79 | `pass shoppay %username% 39` … |
| торжественный вход | `join-effect` | 89 | `pass shoppay %username% 89` |
| цвета ника | `nick-aurora` … `nick-crimson` | 119–169 | `pass shoppay %username% 119` … |
| набор шлейфов | `trails-pack` | 199 | `pass shoppay %username% 199` |
| весь вид | `look-pack` | 599 | `pass shoppay %username% 599` |
| боевой пропуск | `battlepass-premium` | 249 | `pass shoppay %username% 249` |

Полный список с точными ценами печатает сервер: `/pass shop`.

## Старые файлы

`pass-30-days.png`, `pass-90-days.png`, `pass-365-days.png` — карточки
проходки 150/400/1400 ₽. С 1.4.167 вход бесплатный, эти карточки больше не
продаются; файлы оставлены как образец стиля (билет, металл, свечение).

## Как проверить без денег

1. Из консоли: `pass shopbuy TestPlayer homes-plus` — товар должен появиться в
   `/pass my TestPlayer`.
2. `pass remove TestPlayer homes-plus` — снять.
3. Если выдача не нашлась при оплате — почти всегда разошлась цена карточки и
   `price` товара (см. `/pass shop` и лог: ядро пишет предупреждение о дублях).
