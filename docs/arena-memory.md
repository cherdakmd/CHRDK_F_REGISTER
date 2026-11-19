# Память агентских сессий CHRDK_F_REGISTER

## 2026-11-10 · вырезка TIERFORGE (полная) · ветка arena/04a1bf63-chrdk-f-register

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
`AddonRetirement271Test` (MODULES 18, server-menu 44, auto-messages 37, API 46),
`ClanIntegrationContractTest`/`EventsRemovalTest` (API 46), `ResourcesYamlTest` (без tier-forge),
`DiamondShopSchemaV3Test` (схема 6/5 + remove-листы v6), `TradeAmountsTest` (scaleMinor-регрессия
без forge-пинов), `test_verify_release` (18 модулей, assertNotIn CHRDK_TierForge), `GuardPolicyTest`
(без tiersadmin; пример игровой команды вместо /tiers forge), `LabelPreferences/LabelStyleService/
RetiredAddonsTest` (без TIERFORGE; labels-usage чистит оба токена), vk-тесты (7 номинаций).

### Гейты (зелёные на момент коммита)
`.github/scripts` pytest 70 ✓ · `tools/wiki/test_build.py` 26 ✓ (было 28 − 2 вырезанных) ·
`check_retired_addons.py` ✓ · `freshness.py` ✓. Сборку компилирует только CI (services.gradle.org
заблокирован): Java-пины сверены python-харнессом по исходникам до push.

### Регламент на будущее (не делать без заказа)
- **Релиз 2.0.18 — «Пока только фиксы»**: всё уходит в main, релизную проводку НЕ трогать
  (`docs/releases/<v>.{json,md}` + `docs/social/releases/<v>.json` + мемо + current_kits +
  plugin_version + `gh release create <v> --target main` + VK workflow_dispatch) до явного заказа.
- VK-новости вручную через workflow_dispatch (`publish=true,kind=news,release_tag=…`) по заказу.
- Контракт сообщений: бамп `messages-version` в схеме модуля ⇒ remove-шаг + правка Java-пинов
  (см. DiamondShopSchemaV3Test); ядро messages.yml схемой не версионируется (BASE_VERSION).
- Вырезка модулей = паттерн `RetiredAddons` (SOURCES/COMMANDS/permission) + запись в
  `check_retired_addons.py` (ALIASES/PATTERNS/RETIRED_PATHS + тесты) + remove-шаги схем модулей.
