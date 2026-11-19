# Память агентских сессий CHRDK_F_REGISTER

## 2026-11-10 · вырезка TIERFORGE (полная) · ветка arena/04a1bf63-chrdk-f-register

### Статус
**PR #158** (base main, MERGEABLE/CLEAN): коммиты `8338581` (вырезка, 215 файлов) + волны тестовых
пинов `d4b1577`/`d8c3a04`/`9c56e44` + свежесть вики. **CI зелёный полностью** (Purpur 26.3/Java 25 +
Build wiki + Social automation). Готов к вливанию в main по отдельному заказу.

### Что сделано
Полностью вырезан модуль `CHRDK_TierForge` (тиры и кузня) и прекращена его разработка:
- сборка: удалён каталог `tier-forge/` (119 файлов) и `include 'tier-forge'` из `settings.gradle`;
- API ядра: удалены `ChrdkTierApi`, `ForgeMaterialSource`, `ChrdkRegisterApi.forgeMaterialSource()`
  (API_VERSION 45 → 46, прецедент API26/27 — снятие контрактов тоже отмечается в истории API);
- diamond-shop: удалены `ForgeShopService/Gui/Prices/Text` + 3 теста, раздел «Кузня» (`/shop forge`)
  из витрины/команд/сообщений; схема diamond-shop `config-version: 5→6`, `messages-version: 4→5`
  с remove-шагами `[forge-shop]` и `[market.home.forge, market.home.forge-lore, forge.shop]`;
- enchants: удалён `ForgeMiningCompat` (+тест) и все вызовы (megamine↔колонна «Карьер»/«Забойщик»
  больше не существует — обе стороны механики вырезаны);
- vk: удалена карточка «МАСТЕР ТИРОВ» (`DailyCardKind.TIERS`) вертикально (config/ledger/poll/
  `DailyNomination.note`/`tierLabel`), card-арность 8→7 часов;
- ядро: `PluginUpdateService` 19→18 модулей, `LuckPermsBootstrap`, `Messages` legacy-loaders rc8/rc9,
  `MilestoneMigration.tierForge`, `AccessManager`, `GuardSettings` (tiersadmin из console-only),
  сервер-меню «Редкости снаряжения» и авто-сообщение о `/tiers` из config.yml, tip-2 и карточка
  профиля без `/tiers`, usage-токены tierforge из `plugin.yml`/`messages.yml`;
- ярлык «Титул кузни» (`Label.TIERFORGE`, ключи `labels.name/desc-tierforge`) удалён по прецеденту
  treasures/streams; id `tierforge` остался в легаси-списках `LabelPreferences` (старые файлы
  читаются без сдвига), regex `GameplayRewardTexts.labels.usage` чистит tierforge из старых usage;
- ретайер: `RetiredAddons.SOURCES += tierforge`, `COMMANDS += tier/tiers/tieradmin/tiersadmin`
  (меню/объявления/права пользовательских конфигов самоочищаются; `chrdktierforge.*` не выдаётся);
- wiki: `tierforge.md` удалена, страницы (index/shop/commands/enchants/artifacts/generator/
  profile/cosmetics) обновлены, факты `forge_materials/forge_shop/forge_costs/tiers` удалены из
  `tools/wiki/facts.py`, `test_forge_market.py` удалён, freshness все 34 страницы приняты;
- гейт остатков `.github/scripts/check_retired_addons.py` расширен под TierForge (aliases,
  namespaces, `ChrdkTierApi/ForgeMaterialSource/ForgeMiningCompat`, страницы, RETIRED_PATHS,
  исключение `ForgeMarketCatalog.java` — namespace чужой PDC замороженных грантов) + его тесты.

### Что НАМЕРЕННО оставлено (история/финансовая сверка)
- `api/ForgeMarketCatalog` + гранты `ShopExchange/ShopInventory` + `ForgeMarketContractTest`:
  реконсиляция старых сделок `shop-data.json` (18 SKU, frozen grant, skill_xp_*), работает
  «без живого модуля»; `ShopJournal.Pending.forge` и `finish()/matches()` довершают зависшие
  forge-квитанции WAL (иначе карантин магазина), но `ShopStore.prepareForge` удалён — новых нет;
- `ItemAttributeMergeTest`/`ItemAttributeMerge`: PDC-namespace `chrdktierforge` старых предметов;
- `SkillXpShopTest:47` — негативный пин `assertFalse(plugin.contains("ForgeMaterialSource"))`;
- README.md, wiki/updates.md, docs/releases, presentation-legacy-* и docs/social/prereleases —
  исторические документы (прецедент снятых Orders/Hunt/Professions), не трогаются;
- `LabelPreferences` legacy-списки (treasures/tierforge/streams) — чтение старых конфигов.

### Пины, которые поменяны
`AddonRetirement271Test` (MODULES 18, server-menu 44, auto-messages 37, API 46; миграция сообщений —
ключи, вырезанные из стока, у владельца остаются как есть: `expected = fresh.getOrDefault(k, legacy)`),
`ClanIntegrationContractTest`/`EventsRemovalTest` (API 46), `ResourcesYamlTest` (без tier-forge),
`DiamondShopSchemaV3Test` (схема 6/5 + remove-листы v6), `TradeAmountsTest` (scaleMinor-регрессия
без forge-пинов), `test_verify_release` (18 модулей, assertNotIn CHRDK_TierForge), `GuardPolicyTest`
(без tiersadmin; пример игровой команды вместо /tiers forge), `LabelPreferences/LabelStyleService/
RetiredAddonsTest` (без TIERFORGE; labels-usage чистит оба токена), vk-тесты (7 номинаций,
`DailyCardsConfig` 13 арг.).

Волны пинов после CI (семейство `*RemovalTest` пинит текущие сток-счётчики!):
- `PluginUpdateService.MODULES.size()` 19→18 в 7 файлах (Auction/Events/Orders/Pets/Queue/Streams
  Removal + PluginUpdateServiceTest); стейджинг JAR `result.count()`/`files.count()` 19→18;
- сервер-меню 45→44: `items.size()`/`live.getMapList(...)`/`stock.getMapList(...)` +
  `PresentationUpdateTest:214`; дельты legacy−stock: `PresentationUpdateTest` −5→−6/−6→−7/−7→−8
  (в 2×), `DonateCommandHandlerTest:116` −5→−6, `StreamsRemovalTest` 6→7, `EventsRemovalTest` 5→6;
  ВНИМАНИЕ: `PresentationMigration.replacements()` НЕ мутирует `live` — «oldItems/live» = сам
  legacy-файл (дельты сдвигаются при вырезке пункта);
- авто-сообщения 38→37: `AutoMessages276Test` (+5-дельта самоотрегулировалась — фильтр
  `RetiredAddons.announcement` режет tiers с обеих сторон), `AutoMessagesUpdateTest` ×2,
  `EconomyPresentation255Test` (+6 сохранилась — `previous` фильтрует `RetiredAddons.plugin`),
  Orders/Pets/Queue Removal;
- `API_VERSION` 45→46: `QueueRemovalTest:35`, `StreamsRemovalTest:37`, `VkCoreBridgeTest:34`,
  `StarterKitContractTest:73` (исходник `int API_VERSION = 46;`);
- ярлыки: `ExpeditionLabelsTest` ordinals HEADER=6/EXPEDITIONS=7 (форматы файлов именные —
  маски не страдают), `LabelsMenuService.ENTRY_SLOTS` без слота 37 (позиционный маппинг
  `labelAt`), `StreamsRemovalTest` nameTags 7/values 8, `LabelPreferencesTest:352` nameTags 7.

### Диагностика CI (артефакты блокированы!)
Логи/артефакты (`blob.core.windows.net`) не скачать, НО есть два рабочих канала:
1. **`gh api repos/…/check-runs/<id>/annotations`** — до 10 падений тестов с точными assert-ошибками
   (cap 10/уровень — за ними могут быть скрытые падения!);
2. **fetch_page run-summary** `https://github.com/<owner>/<repo>/actions/runs/<id>` — аннотации в HTML
   + «Gradle failure excerpt» (tail build.log) + имена шагов (`#step:N`).
Упавший за 15 секунд job = pre-check (гейты), не тесты.

### Гейты (зелёные на момент коммита)
`.github/scripts` pytest 70 ✓ · `tools/wiki/test_build.py` 26 ✓ (было 28 − 2 вырезанных) ·
`check_retired_addons.py` ✓ · `freshness.py` ✓. Сборку компилирует только CI (services.gradle.org
заблокирован): Java-пины сверены python-харнессом по исходникам до push.
**Гейт ретайера сканирует `wiki/reviewed.json`** — причины сверки (`--reason`) должны быть нейтральными
(без слов tier/tiers/tierforge/кузня латиницей), иначе «остатки» в самой причине валят pre-check.
Freshness принимать ПОСЛЕДНИМ шагом; ЛЮБАЯ поздняя правка `src/` сдвигает отпечатки → повторный accept.

### Регламент на будущее (не делать без заказа)
- **Релиз 2.0.18 — «Пока только фиксы»**: всё уходит в main, релизную проводку НЕ трогать
  (`docs/releases/<v>.{json,md}` + `docs/social/releases/<v>.json` + мемо + current_kits +
  plugin_version + `gh release create <v> --target main` + VK workflow_dispatch) до явного заказа.
- VK-новости вручную через workflow_dispatch (`publish=true,kind=news,release_tag=…`) по заказу.
- Контракт сообщений: бамп `messages-version` в схеме модуля ⇒ remove-шаг + правка Java-пинов
  (см. DiamondShopSchemaV3Test); ядро messages.yml схемой не версионируется (BASE_VERSION).
- Вырезка модулей = паттерн `RetiredAddons` (SOURCES/COMMANDS/permission) + запись в
  `check_retired_addons.py` (ALIASES/PATTERNS/RETIRED_PATHS + тесты) + remove-шаги схем модулей.
