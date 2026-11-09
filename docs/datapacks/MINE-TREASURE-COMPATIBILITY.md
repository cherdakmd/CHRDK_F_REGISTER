# Матрица совместимости: Mine Treasure 1.6.5 RU ↔ CHRDK (q9=all)

Покрыты **CHRDK_Core и все 24 активных аддона** ветки `1.5.2-rc.10`.
Retired `CHRDK_Treasures`, `CHRDK_Bosses`, `CHRDK_Streams`, `CHRDK_Queue`,
`CHRDK_Events`, `CHRDK_Lottery`, `CHRDK_Party`, `CHRDK_Referrals` **не
возрождаются**; `check_retired_addons.py` остаётся зелёным. `API_VERSION=42`
не повышался.

## Как читать матрицу

- **Путь** — событие/данные, через которые модуль пересекается с датапаком.
- **Действие** — что изменено в этом коммите или почему изменения не нужны.
- **Риск двойного дропа** — закрыт политикой `MineTreasureLootPolicy`,
  либо отсутствует, потому что модуль не создаёт предметы.

`MineTreasureLootPolicy` (Core, `ru.cherdakmd.chrdkregister.api`):

- `isMineTreasureTable(...)` — `mt:` / `mine_treasure:` лутовые таблицы сундуков;
- `isMineTreasureItem(...)` — PDC-метка `chrdk:mine_treasure_item` из патченого ZIP;
- `isForeignDrop(...)` — метка **или** консервативный fallback `hasItemMeta()`
  для уже выданных предметов без метки;
- `vanillaOnly(...)` — только простые ванильные стеки для perk-эффектов.

Paper 26.3: `LootGenerateEvent` вызывается из `LootTable#fill(Container...)`;
дроп блоков идёт через `LootTable#getRandomItems(...)` и `BlockDropItemEvent`.
Поэтому 43 переопределённые `minecraft:blocks/*` таблицы не проходят через
сундучный `LootGenerateEvent`, а обрабатываются на путях ломания блоков.

| # | Модуль | Путь пересечения | Действие / статус |
| --- | --- | --- | --- |
| 1 | **CHRDK_Core** | `MineTreasureIntegration` (reflection `DatapackManager`), `config.yml`, `ItemAttributeMerge`, `ExplorationAtlas#mine`, `PlayerRestrictionListener` | Автовключение `file/mine-treasure-1.6.5-ru.zip` оставлено как в q5. Добавлен `MineTreasureLootPolicy`. `ItemAttributeMerge` (`cd09e64`) не ломался: чужие атрибуты MT сохраняются на наковальне/крафте. Счётчики добычи/ограничения не создают дроп. |
| 2 | **CHRDK_Achievements** | `BlockBreakEvent`, `EntityDeathEvent`, `PlayerFishEvent` | Только прогресс достижений; предметов не создаёт. Конфликта с 43 таблицами нет. |
| 3 | **CHRDK_Artifacts** | `LootGenerateEvent` (`ArtifactLootListener`), `EntityDeathEvent`, `PlayerFishEvent` | `ArtifactsConfig#chestChancePercent` уже сопоставляет короткое имя: `mt:chests/ancient_city` → `minecraft:chests/ancient_city`. CHRDK-артефакт **добавляется** к MT-луту, а не заменяет его; комментарий о слиянии q10 добавлен. Мобы/рыбалка не тронуты. |
| 4 | **CHRDK_Auction** | инвентари/экономика, PDC | Лутовых событий нет. Уже выданные MT-предметы можно выставлять как обычные стеки; миграции PDC нет. |
| 5 | **CHRDK_BattlePass** | `BlockBreakEvent`, `EntityDeathEvent`, `PlayerFishEvent` | Только задания/опыт. Двойного дропа нет. |
| 6 | **CHRDK_Claims** | `BlockBreakEvent` (защита приватов) | Отмена ломания блоков сохраняется: MT-таблицы не обходят приваты, потому что дроп всё ещё идёт через штатный `BlockBreakEvent`/`BlockDropItemEvent`. |
| 7 | **CHRDK_Classes** | `EntityDeathEvent`, PDC происхождения | На добычу блоков не влияет; `MobRewardPolicy` не трогался. |
| 8 | **CHRDK_CommonGoals** | `BlockBreakEvent`, `EntityDeathEvent`, `PlayerFishEvent` | Только общие цели/прогресс. Предметы не создаёт. |
| 9 | **CHRDK_Cosmetics** | инвентарь/выдача косметики | Лутовых таблиц нет; PDC не переписывается. |
| 10 | **CHRDK_DailyRewards** | плановая выдача по командам | Не пересекается с `mt:init`/`mt:tick`; двойной выдачи нет. |
| 11 | **CHRDK_DiamondShop** | `/shop`, PDC `book_key`, цены | Каталог и PDC книг не менялись; MT-предметы в магазин автоматически не попадают. |
| 12 | **CHRDK_Disasters** | `Block.setType(..., false)` без `BlockBreakEvent` | Как и раньше, катаклизмы не порождают `BlockBreakEvent`, поэтому CHRDK_Skills и MT-таблицы на них не срабатывают. Изменений нет. |
| 13 | **CHRDK_Enchants** | `ToolEffectsListener` (`BlockDropItemEvent`), `PolarityEffectsListener`, `ForgeMiningCompat`, наковальня | `isForeignDrop(stack)` заменил голый `hasItemMeta()` в автоплавке/Мидасе/Золотой жиле: MT-награды не размножаются. «Порча» (`jinxed`) осознанно чистит весь список дропа, но не перевызывает таблицу. `ForgeMiningCompat`/`MiningCompatPolicy` (`quarry/hewer` ↔ megamine) не менялись. |
| 14 | **CHRDK_Guilds** | `EntityDeathEvent` | Только гильдейский прогресс; блочный лут не трогает. |
| 15 | **CHRDK_Hunt** | `EntityDeathEvent`, элиты/луна | На `mt:` сундуки и блоки не влияет; `HistoryClaims` не тронут. |
| 16 | **CHRDK_Orders** | заказы/экономика, PDC свидетелей | Лутовых событий нет. |
| 17 | **CHRDK_Pets** | питомцы/инвентарь | Лутовых событий нет. |
| 18 | **CHRDK_Professions** | `BlockBreakEvent`, `PlayerFishEvent` | Опыт профессий сохраняется; дроп не создаётся. Автопосадка (`CropReplanting`) не конфликтует: 43 таблицы MT — камень/руды/терракот, не зрелые культуры. |
| 19 | **CHRDK_Quests** | `BlockBreakEvent`, `EntityDeathEvent` | Только прогресс квестов. |
| 20 | **CHRDK_Ranks** | `RankBonus`, дневной лимит алмазов | `DiamondDrops.roll` и `excluded-materials` не менялись. Физический алмаз CHRDK_Skills не дублирует MT-награду: он исключён для `DIAMOND_ORE`/`DEEPSLATE_DIAMOND_ORE`. |
| 21 | **CHRDK_Skills** | `BlockBreakEvent` → `doubleDrop`, `diamondDrop`, `replayCrop` | **Ключевая правка q10:** `doubleDrop` берёт `MineTreasureLootPolicy.vanillaOnly(block.getDrops(...))`. Ванильный дроп всё ещё удваивается perk'ом, а `mt:` пул из 43 таблиц — нет. `diamondDrop`, лимиты и престижи не тронуты. |
| 22 | **CHRDK_Teleports** | перемещение/world policy | На лутовые таблицы не влияет; `worldWorldBlocked` тиров читается как раньше. |
| 23 | **CHRDK_TierForge** | `TierWorldListener` (`LootGenerateEvent`), `ForgeUpgradeListener` (`BlockDropItemEvent`), `TierItems`, наковальня | `TierWorldListener` пропускает `mt:` таблицы, чтобы не переписать имя/лор/атрибуты MT-награды. `ForgeUpgradeListener` использует `isForeignDrop(...)`: удвоение руды/урожая/смягчение работает только по ванильным стекам. `TierItems.apply` и `ItemAttributeMerge` не менялись; уже тированные предметы не мигрируются. |
| 24 | **CHRDK_VK** | `SocialGateListener`, `WallDigestService` (`BlockBreakEvent`) | Счётчик «добыто блоков» в дайджесте сохраняется: событие ломания не отменяется. Постеры/ledger/посты не связаны с MT-лутом; `vk-v1.5.2-rc.9` не перевыпускался. |

## Почему 43 таблицы блоков не дают двойной дропа

Каждая `data/minecraft/loot_table/blocks/*.json` в паке содержит **два пула**:

1. вложенный `mt:technical/...` → награда Mine Treasure (всегда с
   `minecraft:set_components`, `custom_data` и PDC-меткой CHRDK);
2. ванильный `minecraft:item` (камень, руда, булыжник и т. д.) — простой стек.

До правки `CHRDK_Skills#doubleDrop` повторно вызывал `block.getDrops(...)`, то
есть разыгрывал **оба пула** ещё раз и мог выдать второй MT-предмет. Теперь
`vanillaOnly(...)` отсекает первый пул; `ToolEffectsListener` и
`ForgeUpgradeListener` также не трогают чужие стеки. CHRDK-артефакты в
`mt:` сундуках добавляются один раз через изменяемый `LootGenerateEvent#getLoot()`.

## Миграция и откат (q6=migration)

**До обновления:** полный backup мира, `playerdata`, `scoreboard.dat`,
`datapacks/`, а также PDC-зависимых данных плагинов (`skills.json`,
`collection.json`, `tierforge stats.yml`, `shop-*.json`, `wall-digest.json`,
`daily-cards.json` и др.).

**Уже выданные предметы:** in-place миграции нет. Старые MT-предметы без
`chrdk:mine_treasure_item` остаются как есть; `isForeignDrop(...)` видит их по
`hasItemMeta()` и не даёт perk'ам размножить/перетереть награду. CHRDK-тиры,
PDC артефактов, улучшения Кузни и `book_key` не переписываются.

**Scoreboard:** `mt:init` создаёт цели `mt.*` и `forceload add 0 0 0 0`.
Не удаляйте `mt.*` вручную при откате: это часть состояния датапака. При
повторном включении `mt:init` создаст их снова; `#minecraft:load` и
`#minecraft:tick` не имеют `replace: true`.

**Откат:**

1. `/datapack disable "file/mine-treasure-1.6.5-ru.zip"` (или остановить сервер,
   убрать ZIP из `<мир>/datapacks`, запустить).
2. При необходимости восстановить мир/`playerdata`/PDC-данные из backup
   согласованным комплектом: плагины, конфиги и мир одной версии.
3. Уже розданные MT-предметы остаются у игроков; CHRDK не удаляет чужой
   `custom_data` и не снимает тир задним числом.
4. `CHRDK_VK` ledger и карточки возвращать вместе с конфигом, как в rc.6.

**Обновление ZIP:** заменить файл с новым SHA-256, перезапустить сервер,
проверить `/datapack list enabled` и лог. Installer идемпотентен; `--force`
только после backup.

## Живой smoke (q7=none, q12=balanced)

Агент **не может** выполнить эту часть: нет Purpur/RCON/клиента. Владелец
проверяет на изолированном staging после полного backup:

1. `/version` — подтвердить Purpur `26.3` и что `[121, 1]` подходит; если нет,
   пересобрать ZIP с новым хэшем (q8).
2. `/datapack list enabled` — присутствует
   `file/mine-treasure-1.6.5-ru.zip`; в логе нет ошибок кодека
   `water_movement_efficiency`, `slot`, `set_components` или
   `Could not pass event LootGenerateEvent`.
3. **Добыча:** сломать камень, алмазную руду, базальт/терракот с
   `DOUBLE_DROP`, «Мидасом», «Золотой жилой», «Карьером» и без них. Ванильный
   дроп удваивается perk'ами ровно один раз; MT-оберег/голова/предмет не
   появляется дважды и не получает чужой тир.
4. **Сундуки:** открыть сгенерированный MT-бочонок/сундук `mt:chests/...`;
   лут Mine Treasure на месте, CHRDK-артефакт может добавиться по шансу из
   `sources.chests`, дубля нет.
5. **Наковальня / чары / тир:** взять MT-предмет с атрибутами, переименовать,
   наложить чару, прогнать через Кузню/опознание. `ItemAttributeMerge`
   сохраняет чужие модификаторы; имя/лор MT не стираются CHRDK-тиром.
6. **Scoreboard/functions:** `scoreboard objectives list` содержит `mt.*`,
   `mt:init`/`mt:tick` выполняются без ошибок, `forceload` не ломает мир.
7. **Лог:** нет `SEVERE`/stacktrace от CHRDK_Core, Skills, Enchants, TierForge,
   Artifacts и загрузчика датапаков.

Только после зелёного CI **и** этого чеклиста можно говорить о серверной
совместимости. До него статус: «static/CI готово, живая проверка за владельцем».

## Чего здесь нет

- Компиляции Java/JUnit/Gradle против Purpur API jar.
- Подтверждения разрешения Frozytime (q1 — риск владельца).
- Смены `Latest`, Pages, PR, `vk-v1.5.2-rc.9`, стабильного `v1.5.2`.
- Bootstrap `DATAPACK_DISCOVERY`, копирования ассетов датапака внутрь JAR,
  resurrection `CHRDK_Treasures`.
- Тихих правок уже опубликованного ZIP: любое изменение даёт новый SHA-256.
