# 1.4.282-rc.16 — Кузня: восемь PDC-расходников и `/shop forge` (пререлиз)

**Пререлиз для проверки на изолированном staging. Не стабильный выпуск и не разрешение на автоматическую установку.**
Стабильная версия остаётся **1.4.281**. Игровой smoke настоящего Minecraft-клиента, native `CraftItemStack`/NBT-кодек на живом сервере, сохранение playerdata и нагрузочное измерение MSPT **не выполнялись**.

## Главное

- **Раздел `/shop` → «Кузня»** (слот 46) и команда `/shop forge` (алиас `kuznya`). Витрина показывает ровно восемь whitelist-SKU: `ascend`, `temper`, `reforge`, `seed`, `repair`, `inscribe`, `remove`, `name`. Цены за штуку: **128, 96, 160, 64, 24, 192, 32, 16** алмазов. Обратной скупки нет; неверная/нечисловая цена в конфиге **закрывает** SKU вместо подмены дефолтом.
- **Оплата прежняя:** сначала личный `/bal`, затем физическая недостача из 36 storage-слотов, целая сдача от крупных номиналов уходит на `/bal`. Штатный экран количества и отложенное подтверждение повторно проверяют цену и производителя; искра — максимум 1. Путь «списать деньги и отдельно выдать `addItem`/командой» **запрещён**.
- **Core API42:** `ForgeMarketCatalog` проверяет whitelist SKU, материал, PDC `forge_material_v1`, канонический UUID искры и `max-stack-size=1`. `ShopExchange` расширен nullable **frozen grant** с сохранением прежних конструкторов и receipts без grant. Грант — снимок ОДНОГО прототипа: слот 0, количество 1, остальные 35 слотов пустые.
- `ShopInventory.replay` воспроизводит платёж и exact `isSimilar` clone insertion со сравнением полного native after. Используются прежние **ShopStore/BankStore WAL** (`prepareForge` пишет pending без виртуального склада) — нового кошелька нет. Recovery принятой сделки не требует живого TierForge, текущих цен или нового источника расходников.
- **Искра `seed`:** только `QualifiedHuntKillEvent`, база 0.5% на ур.1 → 1% на ур.30 → 3% на элитном ур.30. Четыре остальных новых расходника по умолчанию **только в магазине**; неограниченного `EntityDeathEvent`-дропа не добавлено. Только искра расширяет eligibility до вещи без тира; первая редкость фиксированная «Обычный». Клон сохраняет чары, авторское имя, прочность и чужие PDC; случайные чары не добавляются. Семь тиров, прежние максимумы и PvP-формулы не расширялись.
- **Новые действия на наковальне:** ремонт тир-вещи (6 уровней XP), выбранная отсутствующая ковка I (10 XP), удаление выбранной ковки без возврата (4 XP), другая кузнечная кличка (2 XP); искра — 8 XP. XP — **уровни, не очки**. Сохранены preview, ПКМ-выбор, полная цена XP, отложенная exact-snapshot ревалидация, anti-spam и rollback; preview входные вещи не меняет.
- `TierItems.refreshOwnedLore` остаётся migration-only; новый owned-блок добавляется **один раз** после `refreshTierModifiers`, финальный append не удалялся. Custom/empty overrides владельца сохраняются; добавлен exact-stock снимок `tierforge-messages-legacy-1.4.282-rc15.yml`.
- RC16 **воссоздан заново** из закоммиченного RC14: утерянных некоммит-правок не использовалось. `plugin_version=1.4.282-rc.16`, Core API42, 22 модуля, Java 25 / Purpur 26.3 / Gradle 9.7.1.

## Скачать

- **`chrdk-plugins-1.4.282-rc.16.zip`** — общий комплект: **22 JAR одной версии + `ОБНОВЛЕНИЕ.txt`** внутри ZIP. Основные плагины отдельно не публикуются.
- **`chrdk-vk-1.4.282-rc.16.jar`** — отдельный опциональный `CHRDK_VK`, в общий ZIP не включён.

Платформа: **Purpur 26.3 / Java 25**. Оба файла собираются и проверяются Release workflow из этого тега: полный Gradle `build --continue` с JUnit, `report_tests.py`, `verify_release.py`, `pack_release.py`, `unzip -t`, standalone VK.

## Переход со стабильной 1.4.281

1. Не применять к production вслепую. Сначала отдельная staging-копия и полный backup: папки CHRDK, Core `data.json`/`bank.json` со всеми SHA/журналами/witness, BattlePass `battlepass-data.json`/`battlepass-money.json`, Guilds `clan-styles.json`, миры и playerdata.
2. Обновлять **весь комплект совместно**, не смешивать версии Core/DiamondShop/TierForge/Hunt/Enchants/BattlePass. Не удалять банковские квитанции и WAL-pending ради «исправления» обновления.
3. Сверить авторские конфиги с памяткой (`forge-shop.enabled`, `forge-shop.operations.*`, `forge-shop.prices.*`); не редактировать их параллельно с reload. Ошибки строгой миграции не обходить удалением файла.
4. Выполнить [игровую матрицу RC16](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/v1.4.282-rc.16/docs/audits/forge-shop-rc16.md) — раздел помечен «НЕ ПРОВОДИЛСЯ». Компонентные и source-inspection тесты **не заменяют** этот smoke; playerdata не объявляется crash-atomic ledger.

## Проверки и границы

Зелёный CI на исходном коммите: полная сборка всех модулей и тестов на Java 25 / Purpur 26.3, отдельный прогон `./gradlew test :diamond-shop:test :tier-forge:test`, `report_tests.py`, вики (33 страницы, сверка источников), offline social, TradeMC, clan_shop, `check_messages.py`, валидация упаковщика и целостности комплекта (`unzip -t`, CRC, 22 плагина одной версии, standalone VK).

**Не обещается:** общей crash-atomic гарантии для Minecraft playerdata и разных плагинных файлов; native `CraftItemStack` codec и живой серверный smoke; проверка WAL-recovery/цен/дропов в проде. Модельные и source-inspection тесты не являются рантайм-кодеком или серверной проверкой.

Подробные изменения RC1–RC16 и миграция: [release notes](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/v1.4.282-rc.16/docs/releases/1.4.282.md). Протокол сборки и проверки: [build-status](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/v1.4.282-rc.16/docs/audits/build-status-2026-11-03-rc16.md).

**Minecraft-сервер, RCON и TradeMC этим пререлизом не изменяются. PR не открывался, main не объединяется.**

## Проверенный исходный код

Коммит: `71aa86e54288c57722d25b30d834f8e9b3d69e54` (ветка `arena/49bc75f7-chrdk-f-register`).

- [Push CI — SUCCESS](https://github.com/cherdakmd/CHRDK_F_REGISTER/actions/runs/41620226157)
- [Сборка и упаковка комплекта — SUCCESS](https://github.com/cherdakmd/CHRDK_F_REGISTER/actions/runs/41619607311)

Файлы собраны и проверены из этого тега: [Release workflow — SUCCESS](https://github.com/cherdakmd/CHRDK_F_REGISTER/actions/runs/41667298126).

### SHA-256 загруженных файлов

По digest GitHub Release Assets (контроль целостности, не криптографическая подпись):

```text
bd8ad6d4272f5c904fd6ec1982ff83d5bafbd117f5007345b3f4c018dcdd247c  chrdk-plugins-1.4.282-rc.16.zip
41d7923149efed3a0f99a1891d1949072b7617513dfe175de45466e119b0c1b7  chrdk-vk-1.4.282-rc.16.jar
```

Эти хэши относятся к ассетам GitHub из Release workflow. Локальный комплект из песочницы собран тем же `pack_release.py` из того же коммита и проверен отдельно (`unzip -t`, `verify_release.py`), но ZIP содержит другие временные метки записей, поэтому его SHA-256 в протоколе аудита отличается.

