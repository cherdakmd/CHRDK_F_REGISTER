# Аудит RC16 — Кузня / `/shop forge`

Источник истины: фактические файлы, `git status` и CI, а не этот документ.
RC16 воссоздан заново из закоммиченного RC14 (`0a6e934`), без утерянных некоммит-правок.
Версия: `gradle.properties` → `plugin_version=1.4.282-rc.16`. Core `API_VERSION = 42`.
Java 25 / Purpur `26.3.build.2647-experimental` / Gradle 9.7.1; полноценный JDK с `javac`
доступен только в GitHub Actions (в песочнице — JRE без `javac`).

## 1. Спецификация и соответствие коду

| # | Контракт | Реализация | Статус |
|---|----------|------------|--------|
| 1 | `/shop` → «Кузня», `/shop forge` | `ShopLayout.SLOT_FORGE`, `ShopGui`, `ShopCommandHandler` (`forge`, `kuznya`), `ForgeShopGui` | код |
| 2 | 8 tagged PDC-SKU | `ForgeMarketCatalog.SKUS`, `ForgeMaterial.values()`, `ForgeMaterials.item` | код |
| 3 | Цены `128/96/160/64/24/192/32/16` | `ForgeMarketCatalog`, `diamond-shop/config.yml`, `ForgeShopPrices` | код + Python-тест |
| 4 | `forge-shop.enabled/prices`, неверная цена закрывает SKU | `ForgeShopPrices.parse`, лог warning, `price()==-1` | код + Python-тест |
| 5 | Нет обратной скупки | `ForgeShopGui` ПКМ → `forge.shop.no-buyback`; в `ShopService.sell` SKU отсутствуют | код |
| 6 | Отключённая операция/производитель не продаются | `forge-materials.operations`, `ForgeMaterialSourceImpl.available`, `ForgeShopService.offers` | код |
| 7 | Искра: только `QualifiedHuntKillEvent`, 0.5/1/3% | `ForgeMaterials.reward` перебирает `HUNT_DROPS`, база `SEED_BASE_DROP_PERCENT` | код + Python-тест |
| 8 | 4 новых расходника только в магазине | `SHOP_ONLY` не участвуют в `reward`; `EntityDeathEvent` не добавлен | код + Python-тест |
| 9 | Unique canonical UUID, stack=1, строго по одной | `ForgeMaterials.item` (PDC `forge_seed_uuid`, `forge_seed_stack1`, `setMaxStackOne`), админвыдача циклом по 1 | код |
| 10 | Стабильность после переоткрытия/relog | `ForgeMaterial.choice(identity, sequence, kind, selected)` детерминирован; UID из UUID | код + JUnit |
| 11 | `ForgeMaterial.seedUid`, 12-hex, не 36 в `validUid` | `ForgeMaterial.seedUid` (SHA-256 → 12 lowercase hex), `seedPlan` | код + JUnit |
| 12 | Только искра → вещь без тира, «Обычный» | `seedPlan` отклоняет `tierId != null`, `config.find("common")` | код |
| 13 | Отказы: неопознанные/tiered/неподходящие/стопки | `build`/`seedPlan` (`amount != 1`, `isUnidentified`, `kindAllowed`) | код |
| 14 | Сохранение чар/имени/прочности/чужих PDC, без новых чар | `TierItems.forgeMaterialCopy` → `assemble` без `apply`, клон | код |
| 15 | Семь тиров, прежние максимумы, PvP-формулы | `TierDef.LADDER` не изменён; `ASCEND_COSTS/XP` прежние | код + JUnit |
| 16 | XP уровнями: 8/6/10/4/2 | `ForgeMaterial.SEED_XP/REPAIR_XP/INSCRIBE_XP/REMOVE_XP/NAME_XP` | код + JUnit |
| 17 | Оплата `/bal` → физическая → сдача | `ForgeShopService.purchase` (`WholeTrades.take/payment`) | код |
| 18 | Штатный выбор количества + подтверждение, искра ≤1 | `ShopQuantityGui.openForge`, `cap()`, delayed `runTask` revalidation | код |
| 19 | Запрет «деньги отдельно + addItem» | один `ShopExchange` с `grant`; ни `addItem`, ни give-команды в пути покупки | код + source-тест |
| 20 | `ShopExchange` nullable frozen grant, прежние конструкторы/receipts | 18/20-арг конструкторы, `carriesGrant()`, `requireJson` | код + JUnit |
| 21 | Frozen grant = native снимок 1 прототипа (слот 0, 35 пустых) | `ForgeMarketCatalog.snapshot/validPrototype` | код |
| 22 | `ForgeMarketCatalog`: whitelist, материал, PDC, canonical UUID, stack 1 | `validPrototype` | код |
| 23 | Core replay: платёж + exact `isSimilar` clone + полный native after | `ShopInventory.replay/insertExact/valid`, `BankService` явная проверка | код |
| 24 | Прежний `ShopStore`/`BankStore` WAL, без нового кошелька | `ShopStore.prepareForge`, `ShopJournal.Pending.forge`, `finish` skip item rows | код |
| 25 | Recovery без живого TierForge/цен | грант заморожен в trade; `ShopJournal.recover` читает только Core | код |
| 26 | Наковальня: preview, выбор, полная цена XP, delayed exact revalidation, anti-spam, rollback | `ForgeAnvilListener` (расширен `choices`/`choiceName`), `AnvilCosts.price` | код |
| 27 | Preview не меняет входы | `Plan` собирается из clone; `render` только `setResult` | код |
| 28 | `refreshOwnedLore` migration-only, `forgeMaterialCopy` append ОДИН раз | `TierLore.refresh` возвращает `before` без строки тира; `assemble` финальный append | код + JUnit |
| 29 | Custom/empty overrides + rc15 legacy | `Messages.fromChecked`, `tierforge-messages-legacy-1.4.282-rc15.yml` | код + JUnit |
| 30 | Без обещания общей crash-atomic | явно зафиксировано в notes/memo/аудите | документ |

## 2. Матрица живого серверного smoke

> **Статус: НЕ ПРОВОДИЛСЯ.** Тестовый сервер/RCON/мир/TradeMC не трогались. Модельные и
> source-inspection тесты не заменяют native `CraftItemStack` codec, сеть клиента и запись
> playerdata. Ниже — что должен проверить администратор на staging перед выпуском.

| Группа | Проверка | Ожидание |
|--------|----------|----------|
| Витрина | 8 товаров, имена, цены, PDC `forge_material_v1` | ровно 8, цены как в конфиге, материал совпадает |
| Витрина | `/shop forge`, кнопка «Кузня», `/shop` без аргументов | все три пути открывают раздел |
| Цены | правка `forge-shop.prices.*` на 0/-1/мусор/`enabled: false` | SKU закрывается, дефолт не подставляется, лог warning |
| Оплата | Bank-only: `/bal` достаточно | списание только со счёта, физические предметы не тронуты |
| Оплата | сдача от `DIAMOND_BLOCK` при недостаче | целая сдача зачислена на `/bal`, лишнего предмета нет |
| Инвентарь | полные 36 слотов | покупка отклонена до списания, предметы не выброшены |
| Инвентарь | двойной клик / shift / цифры / drag / reload между выбором и подтверждением | повторная ревалидация, не более одной выдачи |
| Производитель | выключить `CHRDK_TierForge` или `operations.<sku>` | SKU не продаётся; уже принятая сделка восстанавливается |
| WAL | обрыв после `prepareForge`, restart | `ShopJournal.recover` → `COMMITTED`, без живого TierForge и новых цен |
| Искра | обычная / зачарованная / именованная / повреждённая / с чужими PDC вещь без тира | тир «Обычный», все метаданные сохранены, новых чар нет |
| Искра | уже tiered / неопознанная / неподходящая / стопка | отказ `forge-input`, ничего не списано |
| Искра | UUID и результат после переоткрытия наковальни и relog | тот же UID/выбор, `max-stack-size=1`, стак не сливается |
| Наковальня | preview → ПКМ → ЛКМ, полная цена XP, «Слишком дорого» мост | Уровни XP списаны ровно один раз, rollback при изменении входов |
| Дроп | только qualified Hunt-мобы ур.1/30/элитный | 0.5/1/3% для искры; 4 новых расходника не дропаются |
| Дроп | прежние три расходника | шансы 1.0/0.7/0.35 с прежними множителями |
| Регрессия | vanilla наковальня без расходников | обычное поведение не изменено |
| Регрессия | 7 тиров, потолки ковки, PvP-формулы | без расширений |
| Сообщения | custom и пустые overrides в `messages.yml` владельца | файл не переписан, значения сохранены |

## 3. Проверки, которые выполняются в этой сборке

- `.github/scripts/check_messages.py`, `check_retired_addons.py`.
- `.github/scripts/gradle_build.py` + Gradle `build --continue`, `report_tests.py` (CI, JDK25).
- `./gradlew test :diamond-shop:test :tier-forge:test --console=plain` (CI, JDK25).
- Все `tools/*/test_*.py`, `tools/wiki` unittest + `--check`, `node --test`, `git diff --check`.
- `wiki --check` по `commands`/`enchants`/`profile` сверен с кодом, `reviewed.json` принят
  осознанно после правки страниц, а не вслепую.
- `verify_release.py` / `pack_release.py` с точными аргументами workflow; `unzip -t`,
  состав ZIP (22 JAR одной версии + `ОБНОВЛЕНИЕ.txt`), `plugin.yml`, CRC, размеры, SHA256.

Исторические зелёные сборки RC14 / `v1.4.281` **не подтверждают** RC16.
Release, merge PR, установка на сервер и публикация TradeMC не выполнялись.
