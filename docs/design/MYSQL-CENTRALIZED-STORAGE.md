# Централизованное хранилище CHRDK на MySQL

Статус: **каркас реализован** (NIO `FileSystemProvider`, `DocumentStore`, DDL 55 таблиц, inventory 55 файлов, Connector/J compileOnly, DDL checksum verification), нормализованный migrator и CI contracts в работе. Документ фиксирует архитектуру после
выбора владельца: один сервер Purpur + MySQL, полная миграция всех данных
игроков и покупок кроме секретов входа, офлайн-cutover с сохранением файлов
для read-only сверки и отката, гибридная модель таблиц.

Опубликованные `v1.5.2-rc.10` и `v1.5.2-rc.11`, их теги, Release, assets,
VK-анонсы, `Latest=v1.5.2`, `docs/releases/update-memo.txt`, Wiki и
`plugin_version=1.5.2-rc.11` этим проектом не изменяются. Для реализации
потребуется отдельный новый RC и отдельное решение о публикации.

## 1. Цели и границы

### Цели

1. Подключение к MySQL существует **только в `CHRDK_Core`**. Ни один аддон не
   знает JDBC URL, пользователя, пароль, `DataSource`, `Connection`, SQL и не
   содержит MySQL Connector/J или HikariCP в своём JAR.
2. Аддоны получают доступ к данным через версионированный сервис ядра в
   `Bukkit ServicesManager`, по образцу уже используемого `ChrdkRegisterApi`.
3. Перенести все известные runtime-данные игроков, балансов, покупок, прав,
   прогресса модулей, социальных связей, модерации, курсоров и идемпотентных
   receipt'ов.
4. Сохранить финансовые гарантии: lineage источников дохода, WAL/intents,
   уникальные квитанции, запрет повторной выдачи, запрет «мягкой» регистрации
   чужого lineage, отсутствие эмиссии алмазов при восстановлении.
5. Сохранить инварианты rc.11 для связей VK/MAX: лимит профиля, односторонний
   legacy-импорт, backup, отсутствие перезаписи повреждённых/ручных YAML,
   отсутствие воскрешения связи после `/vk unlink` или `/social unlink`.
6. Обеспечить быстрый откат к файловому бэкенду до момента, пока владелец
   явно не разрешит удаление файловых writer'ов.

### Не входит в первую реализацию

- Несколько Purpur-серверов за proxy и распределённая инвалидация кэша.
- Dual-write и shadow-read. Выбран офлайн-cutover.
- Перенос `passwordHash`, `totpSecret`, `lastTotpStep`, активных сессий и
  одноразовых 2FA-состояний: игроки перевыпускают пароль и 2FA.
- Перенос сервисных токенов VK/MAX/Boosty в таблицы игроков. Они остаются в
  env/конфигурации владельца; при необходимости позже делается отдельный
  encrypted secret store, а не `chrdk_document`.
- Автоматическая публикация нового Release, VK-анонса или смена `Latest`.
- Удаление исходных JSON/YAML: после cutover они становятся замороженным
  архивом и источником read-only сверки/rollback.
- Append-only JSONL аудит/логи как источник истины: сначала сохраняются
  файлами; перевод в таблицы — отдельная фаза после стабилизации.

## 2. Текущее состояние, которое нужно учесть

- Ядро регистрирует `ChrdkRegisterApiImpl` в `RegisterPlugin.java`;
  `ChrdkRegisterApi.API_VERSION = 42`. Плагины получают API через
  `Bukkit.getServicesManager().load(ChrdkRegisterApi.class)`.
- Почти все аддоны имеют `depend: [CHRDK_Core]`. Для DB-режима это становится
  строгим требованием для всех потребителей `ChrdkStorage`.
- Инвентаризация нашла 23 модуля с runtime-файлами и порядка 50 уникальных
  JSON/YAML, плюс `players/*.yml`, backup/quarantine и константы вида
  `FILE_NAME`, которые статический `resolve("...")` не всегда ловит.
- `'data.json'` ключуется в основном по каноническому нику; внутри есть UUID,
  пароли, TOTP, сессии, ранги, pass, `entitlements`,
  `entitlementPackages`, donation receipts, курсоры, `paymentCodes`,
  `shopSaleSequence`, счётчики попыток входа.
- `'bank.json'` ключуется по UUID, schema 8. Кроме балансов в нём лежат
  `cashPending`, `cashReceipts`, `shopSaleRemainders`, `shopSettlements`,
  `claimSettlements`, `claimsImports`, `chargeSettlements`, `chargeWitnesses`,
  `entitlementSources`, `entitlementCredits`, `auctionImports`,
  `auctionSettlements`. Это не «просто счета», а финансовый реестр.
- `'bank-charge.json'`, `'milestone-awards.json'`, `'paid-actions.json'`,
  `'shop-data.json'` хранят lineage, sequence, pending intent, before/after
  state, refunds и resolutions. Случайная генерация нового UUID lineage при
  первом DB-запуске недопустима: должны быть импортированы точные значения.
- `'vk-links.json'` остаётся историческим источником бонусов `/vk`;
  `'players/social.yml'` содержит текущие связи, доноров и награды;
  `'social-links.json'` — глобальные rollout/emergency/chat/news поля с
  SHA-256. rc.11 добавил безопасный односторонний импорт и read-only fallback.
  DB-миграция должна дедуплицировать оба источника, а не «выбрать один файл».
- `'vk-second-factor.json'`, `'boosty-tokens.json'`, `passwordHash`,
  `totpSecret`, `sessions` относятся к секретам входа/сервисным credentials и
  в общие таблицы игроков не переносятся.
- Текущая запись использует `SafeJsonWriter`, атомарную замену, checksum,
  `.broken`, `.bak` и синхронные `synchronized`-методы на главном потоке.
  Прямая замена этих вызовов на JDBC в главном потоке запрещена.

## 3. Архитектура

```text
plugins/CHRDK_Core/database.yml
        |
        v
+--------------------- CHRDK_Core JAR --------------------------+
| ChrdkDatabaseConfig (env interpolation, validation)           |
| HikariCP + relocated MySQL Connector/J                        |
| SchemaMigrations / TransactionRunner / HealthCheck            |
| Core repositories: Player, Purchase, Bank, Finance, Social    |
| DocumentStore for addon payloads                              |
| Main-thread cache + bounded JDBC executor                     |
| ChrdkStorage service -> Bukkit ServicesManager                |
+-------------------------------------------------------------+
        ^                         ^                         ^
        | typed futures           | typed futures           | typed futures
        |                         |                         |
   CHRDK_VK               CHRDK_DiamondShop          остальные аддоны
   (social/VK)            (shop/purchases)          (document payloads)
```

### Отдельный контракт, без bump API42

Добавляется новый публичный интерфейс, например
`ru.cherdakmd.chrdkregister.api.storage.ChrdkStorage`:

```java
public interface ChrdkStorage {
    int STORAGE_API_VERSION = 1;
    boolean healthy();
    StorageMode mode(); // MYSQL, FILE, FILE_READONLY
    long schemaVersion();

    PlayerRepository players();
    PurchaseRepository purchases();
    BankRepository bank();
    FinanceRepository finance();
    SocialRepository social();

    /** Handle is bound to the calling Plugin; namespace cannot be forged. */
    DocumentHandle documents(Plugin owner);
}
```

`ChrdkRegisterApi.API_VERSION` остаётся `42`; `ChrdkRegisterApiImpl` может
реализовать внутренний provider, но аддоны не должны получать DB-методы через
старый интерфейс. Это сохраняет совместимость с модулями, проверяющими API 22,
29, 30, 35, 38, 39, 41 и 42.

`DocumentHandle` ограничивает namespace идентификатором вызвавшего плагина.
Произвольный `namespace` в публичном методе запрещён: один аддон не должен
иметь возможность читать или перезаписать документ другого аддона.

### Что получают аддоны

- `CompletableFuture<Optional<Document>>` с `revision`, `schemaVersion`,
  `payload`, `sourceChecksum`, `updatedAt`.
- `compareAndSet(expectedRevision, newPayload)` для оптимистичной блокировки.
- Типизированные операции верхнего уровня для денег/покупок/соцсвязей, а не SQL:
  `creditOnce`, `settleCharge`, `grantEntitlement`, `recordPurchase`,
  `linkSocial`, `unlinkSocial`, `forgetLegacyVkBinding`.
- Никаких `Connection`, `PreparedStatement`, `ResultSet`, `DataSource`,
  JDBC URL или SQL-строк в API.

Горячие чтения gate/TAB/авторизации идут через in-memory cache ядра. Запись
идёт в bounded executor и возвращается на главный поток Bukkit через scheduler.
При одном Purpur-сервере достаточно локальной инвалидации кэша и `revision`;
распределённые блокировки и `chrdk_event` для proxy в первую версию не входят.

### Режимы backend

| `backend` | Назначение | Запись | Использование в игре |
| --- | --- | --- | --- |
| `FILE` | Legacy/rollback | JSON/YAML, как сейчас | да, до cutover и при откате |
| `MYSQL` | рабочий режим | только MySQL | да, после успешной сверки |
| `FILE_READONLY` | диагностика мигратора | запрещена | нет |

Автоматического «если MySQL упал, читаем старые файлы» быть не должно: после
cutover файлы заморожены и могут быть устаревшими. При недоступности MySQL ядро
и зависящие от него плагины завершают старт с явной ошибкой и подсказкой
про `FILE` rollback. `FILE_READONLY` доступен только CLI/админ-команде сверки.

## 4. Конфигурация и зависимости

Новый файл `plugins/CHRDK_Core/database.yml`, чтобы не переписывать
владельческий `config.yml`:

```yaml
backend: FILE
mysql:
  host: ${CHRDK_DB_HOST}
  port: 3306
  database: chrdk
  user: ${CHRDK_DB_USER}
  password: ${CHRDK_DB_PASSWORD}
  tls: true
  pool-size: 10
  connect-timeout-ms: 5000
  socket-timeout-ms: 10000
migration:
  source-root: plugins
  read-only-fallback: true
  allowed-collation: utf8mb4_0900_ai_ci
  timezone: UTC
```

Правила:

- Env-переменные обязательны; пароль не попадает в Git, логи, Release notes
  или `docs/`.
- `CHRDK_DB_USER` имеет права только на схему `chrdk`; runtime-пользователь
  не должен иметь `SUPER`, `FILE` или прав на другие базы.
- MySQL 8.0+ или MariaDB 10.6+, InnoDB, `utf8mb4`, strict SQL mode, UTC.
- Connector/J и HikariCP подключаются как `implementation` только корневого
  проекта и релоцируются/шейдятся в core JAR; в аддон-JAR не попадают.
- Версии зависимостей фиксируются в Gradle и проверяются CI, а не подтягиваются
  динамически.
- `database.yml` не содержит реальных секретов в репозитории; в примере только
  placeholder'ы env.

## 5. Модель MySQL

### Общие соглашения

- Все деньги — `BIGINT` целые алмазы/копейки; `DOUBLE` не используется для
  баланса и финансовых сумм. Существующие `double` истории цен магазина
  переносятся как `JSON`/`DECIMAL`, но не становятся деньгами.
- Время игровых полей — `BIGINT` Unix millis, как в текущих файлах.
  `TIMESTAMP(6)` используется только для audit `created_at`/`updated_at`.
- UUID — `CHAR(36)` с проверкой формата; канонический ник —
  `VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin`, уже lowercase.
- SHA-256 — `BINARY(32)`; payload — `JSON`, не `TEXT` с ручным парсингом.
- `ON DELETE RESTRICT`, никаких каскадных удалений игроков.
- Все идемпотентные ключи имеют `UNIQUE`; повторная выдача отвергается БД, а не
  только кодом.
- Таблицы хранят `source_path`, `source_sha256`, `migration_batch`,
  `schema_version`, `revision`, где это нужно для сверки.

### Базовые таблицы

```text
chrdk_schema_migration(version, applied_at, checksum, description)
chrdk_meta(key, value) -- backend epoch, migration batch, source root
chrdk_migration_source(namespace, source_path, sha256, size, rows, target,
                       status, imported_at, report_json)
chrdk_namespace_revision(namespace, revision, updated_at)

chrdk_player(uuid PK, canonical_name UNIQUE, last_name, created_at,
             updated_at, auth_reset_required, merged_from)
chrdk_account(uuid PK/FK, pass_until_ms, rank_level, rank_permanent,
              temp_rank_level, rank_granted_at_ms, trial_consumed,
              trial_granted_at_ms, trial_until_ms, last_grant_source,
              last_grant_at_ms, last_grant_days, last_grant_hours,
              reward_boost_millis, total_paid_kopecks, payments_count,
              last_payment_at_ms, login_frozen, login_frozen_at_ms,
              last_auth_ip, last_auth_at_ms, ignored_players JSON,
              expiry_warnings_sent JSON, reward_boost_receipts JSON,
              reward_boost_fractions JSON)
chrdk_entitlement(uuid, product_id, until_ms, package_index, receipt_key,
                  UNIQUE(uuid, product_id, package_index))
chrdk_auth_attempt(kind, subject, value_json)
chrdk_auth_reset(uuid, reason, issued_by, code_hash, expires_at_ms, used_at_ms)

chrdk_purchase(id, source, external_id UNIQUE(source, external_id),
               uuid, player_name, amount_kopecks, product_id, status,
               created_at_ms, raw_payload JSON)
chrdk_purchase_receipt(receipt_key PK, purchase_id, actor, command,
                       outcome, confirmed_at_ms)
chrdk_store_delivery(id PK, actor, command, outcome, confirmed_at_ms)
chrdk_processed_donation(source, external_id, processed_at_ms)
chrdk_purchase_cursor(source, cursor, initialized, extra_json)
chrdk_payment_code(code, uuid, created_at_ms, used_at_ms)
chrdk_donation_journal(id, source, payload JSON, created_at_ms)
```

### Финансовые таблицы

```text
chrdk_bank_account(uuid PK, name, balance, deposited, withdrawn, sent,
                   received, updated_at_ms, legacy_shop_imported,
                   legacy_shop_amount, revision)
chrdk_bank_ledger(id, uuid, delta, balance_after, kind, ref_key,
                  created_at_ms, metadata JSON)
chrdk_bank_cash_intent(id PK, uuid, payload JSON, state, created_at_ms)
chrdk_bank_cash_receipt(id PK, uuid, payload JSON, created_at_ms)

chrdk_income_source(source PK, lineage, registered_at_ms, source_file_sha256)
chrdk_entitlement_credit(source, lineage, receipt_key, uuid, credited,
                         total, created_at_ms, UNIQUE(source, lineage, receipt_key))
chrdk_charge_journal(source, lineage, sequence, pending_json,
                     before_state_hash, after_state_hash,
                     UNIQUE(source, lineage))
chrdk_charge_settlement(receipt_key PK, source, lineage, sequence,
                        payload JSON, witness UUID, created_at_ms)
chrdk_shop_exchange(receipt_key PK, uuid, payload JSON, created_at_ms)
chrdk_claim_exchange(receipt_key PK, uuid, payload JSON, created_at_ms)
chrdk_auction_payment(receipt_key PK, market, uuid, payload JSON, created_at_ms)
chrdk_auction_import(market, uuid, amount, created_at_ms)
chrdk_claim_import(receipt_key PK, uuid, amount, created_at_ms)
chrdk_shop_remainder(uuid PK, remainder, updated_at_ms)

chrdk_milestone_source(source PK, lineage, imported, registered,
                       source_file_sha256)
chrdk_milestone_award(source, uuid, award_key, amount,
                      UNIQUE(source, uuid, award_key))
chrdk_paid_action_source(lineage PK, registered, values_initialized,
                         source_file_sha256)
chrdk_paid_action(id PK, payer, actor, price, kind, payload, phase)
chrdk_paid_refund(payer PK, amount)
chrdk_paid_resolution(id, action, payer, price, success, operator, at_ms)
chrdk_paid_value(key PK, value)
```

Импорт обязан сохранить точные lineage из `bank.json`,
`milestone-awards.json`, `paid-actions.json`, `bank-charge.json` и
`shop-data.json`. Новый `UUID.randomUUID()` при наличии файла-источника
запрещён. `registerIncomeSource(source, lineage)` остаётся строгим: если
`source` уже закреплён за другим lineage, возвращается отказ/
`IllegalStateException("award lineage conflict")`, без подавления guard и без
переписывания чужих квитанций.

`ChargeJournal.prepare/complete/rollback`, `PaidActionStore.READY/EXECUTING`,
`MilestoneAwards.deliver`, `BankStore.creditEntitlement`, `settleAuction`,
`commitClaim`, `exchangeAccounts` переносятся как транзакционные операции с
`SELECT ... FOR UPDATE` и уникальными receipt'ами. Внешние эффекты
(инвентарь, VK, выдача предметов) не вызываются внутри DB-транзакции;
используется существующий контракт intents/recovery или transactional outbox.

### Универсальные документы модулей

```text
chrdk_document(namespace, entity_key, schema_version, revision,
               payload JSON, source_checksum, updated_at,
               PRIMARY KEY(namespace, entity_key))
chrdk_document_history(namespace, entity_key, revision, payload JSON,
                       replaced_at, reason)
```

`entity_key` — `global`, UUID игрока, клан, товар или иной стабильный ключ
модуля. Для нескольких сущностей одного типа можно использовать составной
логический ключ вида `player:<uuid>` / `clan:<id>`, но он фиксируется в
manifest миграции. `compareAndSet` не даёт потерять обновление при гонке.
История нужна для расследования, а не для автоматического отката денег.

### Социальные таблицы

```text
chrdk_social_global(id=1, schema, player_data_schema, rollout_started_at,
                    emergency_until, last_news_tag, max_chat_id,
                    max_news_chat_id, max_bridge_chat_id, checksum)
chrdk_social_chat(chat_id PK, provider, discovered_at)
chrdk_social_link(uuid PK, canonical_name UNIQUE, provider, external_id,
                  external_name, linked_at_ms, source,
                  UNIQUE(provider, external_id))
chrdk_social_profile(provider, external_id, uuid, usage_rank,
                     UNIQUE(provider, external_id, uuid))
chrdk_social_player(uuid PK, canonical_name, first_seen_at, donor_granted_at,
                    donor_source, reward_claimed_at)
chrdk_social_link_tombstone(uuid, provider, external_id, unlinked_at_ms,
                            command, actor, UNIQUE(uuid, provider, external_id))
chrdk_vk_legacy_link(canonical_name, vk_user_id, linked_at, member, donut,
                     donut_verified_at, notify_logins, allow_vk_chat_mentions,
                     source_file_sha256)
chrdk_vk_boost_claim(kind, player_or_vk_id, claimed_at_ms)
chrdk_vk_chat_state(hidden_players JSON, banned_vk_ids JSON,
                    last_announced_post_id, group_chat_peer_id)
chrdk_social_daily_card(...)
chrdk_social_wall_digest(...)
```

После cutover канонический источник — БД. Fallback к замороженному
`vk-links.json` в игровом режиме отключается, иначе `/vk unlink` и
`/social unlink` снова начнут «воскрешать» связь. Вместо этого оба пути
unlink пишут tombstone и удаляют запись в одной транзакции. Legacy-импорт
остаётся идемпотентным и запускается только мигратором.

## 6. Карта миграции файлов

Таблица — проектная; финальный CI-check должен сверять её с автоматическим
сканом `resolve("*.json|*.yml")`, констант `FILE_NAME` и `PlayerDataFiles`.

| Источник | Целевой слой | Особое правило |
| --- | --- | --- |
| `CHRDK_Core/data.json` | player, account, entitlement, purchase, receipts, cursors, attempts, donations, payment codes | исключить auth-секреты; сохранить `entitlementPackages`, trial UUID, курсоры, `shopSaleSequence` |
| `CHRDK_Core/bank.json` | bank_account, ledger, receipts, income_source, entitlement_credit, imports, settlements, remainders | точный lineage, schema 8, без повторной эмиссии |
| `CHRDK_Core/bank-charge.json` | charge_journal + charge_settlement | pending и sequence импортировать как есть |
| `CHRDK_Core/milestone-awards.json` | milestone_source/award | не переписывать lineage при обязательствах; сохранить rc.11 `adoptLineage` |
| `CHRDK_Core/paid-actions.json` | paid_action_* | READY/EXECUTING, refunds, resolutions, values |
| `CHRDK_Core/boosty-bindings.json` | external_binding | без токенов |
| `CHRDK_Core/boosty-tokens.json` | env/secret config | не импортировать в player/document tables |
| `CHRDK_Core/vk-second-factor.json` | auth_reset | перевыпуск 2FA |
| `CHRDK_Core/moderation.json`, `players/warns.yml`, `players/bans.yml` | moderation_global, sanction/history | YAML авторитетен при расхождении; снятые варны не удалять |
| `CHRDK_Core/labels.json`, `honors.json`, `guide-data.json`, `spawn-data.json`, `starter-kits.json`, `manifest.json` | `chrdk_document('core', ...)` | конфигурационные/глобальные payloads |
| `CHRDK_Core/bans.yml`, `warns.yml` из legacy | moderation history | не путать с ванильным `BanList` |
| `CHRDK_VK/vk-links.json` | vk_legacy_link, chat_state, boost_claim | источник бонусов `/vk`; не удалять, не fallback после cutover |
| `CHRDK_VK/players/social.yml` | social_link/player/profile | авторитетные ручные правки; лимит профиля |
| `CHRDK_VK/social-links.json` | social_global/chat | проверить SHA-256; corrupted -> abort, не перезаписывать |
| `CHRDK_VK/daily-cards.json`, `wall-digest.json` | social_daily_card/wall_digest | append-only журналы перевести в таблицы или документы после сверки |
| `CHRDK_DiamondShop/shop-data.json` | bank/shop_exchange + document | `market`, `sequence`, `pending`, `registered`, balances, items, limits, turnover |
| `CHRDK_DiamondShop/shop-overrides.json`, `promotions-data.json` | document | сохранить daily progress и `contractPaid`/`seriesPaid` |
| `CHRDK_Claims/claims-data.json` | document + financial_pending | отделить `finance.after()` и защищённые claims |
| `CHRDK_Skills/skills.json`, `abilities.json`, `perk-trees.json` | document | UUID/global ключи, schema versions |
| `CHRDK_Classes/classes.json`, `classes-legacy-rules.json`, `perk-trees.json` | document | не смешивать с skill perk-trees; namespace разный |
| `CHRDK_Achievements/achievements.json`, `exploration.json` | document + exploration | `exploration.json` есть и в BattlePass; импортировать в общий `exploration` namespace, не в два документа |
| `CHRDK_Artifacts/collection.json`, `cooldowns.json` | document | cooldown millis |
| `CHRDK_Auction/market.json` | document + auction_import/payment | сохранить idempotency auction |
| `CHRDK_BattlePass/battlepass-data.json`, `battlepass-money.json`, `exploration.json` | document + finance | денежный lineage и once-выдачи |
| `CHRDK_CommonGoals/goals-data.json`, `bank-charge.json` fixture | document + finance | не путать тестовые fixture с runtime |
| `CHRDK_Cosmetics/cosmetics-data.json` | document | выбранные косметики |
| `CHRDK_DailyRewards/daily-data.json` | document | streak и VIP |
| `CHRDK_Disasters/disasters-progress.json` | document | прогресс событий |
| `CHRDK_Guilds/guilds.json`, `clan-styles.json` | document | кланы, стили, долговой WAL согласовать с `PersonalDebtIndex` |
| `CHRDK_Hunt/hunt.json`, `full-moon.json` | document + ledger | лунный ledger |
| `CHRDK_Orders/orders.json` | document | заказы |
| `CHRDK_Pets/pets.json` | document | питомцы |
| `CHRDK_Professions/professions.json` | document | профессии |
| `CHRDK_Quests/quests.json` | document | квесты |
| `CHRDK_Ranks/ranks-data.json` | document | playtime |
| `CHRDK_Teleports/teleports-data.json` | document | дома |
| `CHRDK_TierForge/stats.yml` | document + milestone_source | lineage наград TierForge |
| `config.yml`, `messages.yml`, `plugin.yml`, datapacks, worlds, logs | файлы | не переносить в игровые таблицы |

Перед реализацией добавляется machine-readable `storage-inventory.json`:
каждый runtime-файл получает `module`, `path`, `owner`, `key_kind`, `target`,
`schema`, `secret_class`, `checksum_policy`. CI проверяет, что ни один новый
`resolve`/`FILE_NAME` не остался без записи в manifest.

## 7. Секреты входа и перевыпуск

Исключаются из миграции:

- `PlayerAccount.passwordHash`;
- `totpSecret`, `totpEnabledAt`, `lastTotpStep`;
- активные `sessions` и любые session tokens;
- `vk-second-factor.json`;
- `boosty-tokens.json` и VK/MAX bot tokens как сервисные credentials.

Для каждого существующего аккаунта выставляется `auth_reset_required=1`.
Игровые права, баланс, покупки, ранги, pass, прогресс и социальные связи
сохраняются. Процедура перевыпуска проектируется отдельно и должна быть
понятна игроку:

1. Игрок с привязанным VK/MAX получает одноразовый код через подтверждённый
   канал привязки.
2. Игрок без привязки получает код у администратора/поддержки; команда только
   создаёт `chrdk_auth_reset`, но не показывает старый пароль.
3. После установки нового пароля `auth_reset_required=0`, старые сессии
   недействительны, 2FA предлагается заново.
4. Ни один этап не создаёт новый игровой аккаунт и не обнуляет purchases.

Старые значения не пишутся в БД даже «временно», не попадают в migration
report и не логируются.

## 8. Офлайн-cutover, сверка и rollback

### Порядок

1. Полная остановка Purpur, затем backup `plugins/`, worlds, логов и текущего
   дампа MySQL.
2. `storage-inventory.json` + `--dry-run` мигратора на staging-копию MySQL.
   Любая неизвестная схема, битый checksum, повреждённый YAML, дубль UUID/ника
   или отсутствие lineage останавливает импорт.
3. Создание схемы через `SchemaMigrations`; запись `migration_batch` и
   `source_sha256` для каждого файла.
4. Импорт в транзакциях по доменам. Повторный запуск с тем же `sha256`
   идемпотентен и не создаёт новые деньги/права.
5. `--verify` сравнивает:
   - количество аккаунтов, UUID и канонические ники;
   - сумму балансов и каждый balance/deposited/withdrawn/sent/received;
   - все `processedDonationIds`, `paymentReceipts`, `storeDeliveries`,
     курсоры, `paymentCodes`, `shopSaleSequence`;
   - `entitlements` и `entitlementPackages` по каждому игроку;
   - lineage/sequence/pending для банка, milestone, paid actions, charge,
     shop, auction, claims;
   - social links из `vk-links.json` + `players/social.yml`, лимиты профилей,
     donor/reward/firstSeen, tombstones;
   - количество строк каждого `chrdk_document` и checksum payload;
   - количество warn/ban/moderation записей.
6. Отчёт `migration-report-<batch>.json` с таблицей «файл → target → rows →
   checksum → discrepancies». Любая discrepancy блокирует cutover.
7. `backend: MYSQL`, старт, health-check, smoke-команды, проверка TAB/social
   gate/`/bal`/`/vk`/`/shopadmin recovery`.
8. Файлы переводятся в read-only/immutable архив. Ни один writer не пишет в
   них в DB-режиме.

### Откат

`backend: FILE` возвращает сервер к замороженным исходникам. Так как dual-write
не было, файлы точно соответствуют моменту остановки. После отката нужно
сбросить `migration_batch` или использовать новый batch при повторной попытке;
старые DB-данные не должны автоматически перезаписывать файлы.

`FILE_READONLY` разрешает только чтение и сверку. Автоматического переключения
на него при недоступности MySQL нет.

## 9. Отказоустойчивость и финансовые инварианты

- Startup DB health-check обязателен. Нет соединения/схемы/миграции — ядро не
  регистрирует `ChrdkStorage`, аддоны с `depend` не стартуют, а не уходят в
  финансовый fallback.
- Все денежные операции требуют активной транзакции, уникального receipt и
  проверки lineage. Guard `award lineage conflict` сохраняется; исключение не
  подавляется, lineage не переписывается при существующих обязательствах.
- `wrong generation does not mint` остаётся контрактным тестом.
- JDBC вызовы не блокируют main thread; горячие чтения берутся из кэша.
- При restart незавершённые intents восстанавливаются из БД, а не из
  устаревшего файла.
- Для `SocialService` сохраняются: `loadFailed`/`playersFrozen` -> abort,
  backup перед первой записью, отсутствие corruption overwrite, уважение
  ручных YAML, лимит `max-accounts-per-profile`, отсутствие воскрешения после
  unlink.
- `chrdk_namespace_revision` резервируется для будущего multi-server, но в
  выбранной топологии одного Purpur не включает межсерверную синхронизацию.

## 10. Сборка, CI и тесты

### Сборка

- Gradle Shadow/релокация только для корневого core JAR.
- Все аддоны компилируются против `ChrdkStorage` интерфейсов; проверка
  запрещает `java.sql.*`, `DataSource`, `jdbc:mysql`, MySQL/Hikari классы вне
  `src/main/java/ru/cherdakmd/chrdkregister/...` или отдельного DB-пакета ядра.
- Версия плагинов остаётся единой; новый RC поднимает `plugin_version` один раз
  на весь комплект.
- `plugin.yml` всех потребителей `ChrdkStorage` требует `CHRDK_Core`.
- CI по-прежнему собирает Java 25/Purpur 26.3, JUnit, все JAR, wiki build,
  release contracts. Локальная JVM/MySQL в этой песочнице отсутствуют, поэтому
  компиляция и интеграционные тесты выполняются в CI.

### Автоматические проверки

1. Инвентаризация: ни одного runtime JSON/YAML без `storage-inventory.json`.
2. Golden migration fixtures из rc.11: полный импорт, повторный импорт,
   повреждённый checksum, дубль UUID/ника, пустой social store при непустом
   `vk-links.json`, ручной YAML при расхождении.
3. Финансы: concurrency debit/credit, duplicate receipt, другой namespace не
   крадёт lineage, wrong generation не минтит, `award lineage conflict`
   сохраняется, charge pending после restart.
4. Social: оба unlink не воскрешают связь, лимит профиля, legacy import
   идемпотентен, corrupted YAML блокирует импорт.
5. Secrets: в DB fixture нет `passwordHash`, `totpSecret`, session token,
   `vk-second-factor.json`, `boosty-tokens.json`; в логах нет пароля MySQL.
6. Backend: `FILE` -> `MYSQL` -> `FILE` rollback, `FILE_READONLY` не пишет,
   DB down -> fail-closed.
7. Contract: `ChrdkStorage.STORAGE_API_VERSION=1`, `API_VERSION=42`, сервис
   зарегистрирован, аддоны не содержат JDBC-зависимостей.
8. Release/docs: новый memo/notes/wiki/freshness только когда владелец явно
   разрешит публикацию; rc.10/rc.11 immutable.

### Живой staging

В песочнице нет Purpur/RCON/NBT/MSPT и рабочих данных владельца. Перед
публикацией владельцу нужно прогнать на staging: вход, reset пароля/2FA,
TAB/social gate, `/vk`, `/social status`, `/bal`, покупку, `/shopadmin
recovery`, auction/claim/milestone, restart, DB outage, rollback и MSPT под
нагрузкой.

## 11. План реализации по фазам

| Фаза | Результат | Критерий готовности |
| --- | --- | --- |
| 0. Проект | этот документ, `storage-inventory.json`, точный список файлов | владелец согласовал открытые вопросы ниже |
| 1. Контракт и схема | `ChrdkStorage`, `database.yml`, Hikari/Connector/J, SchemaMigrations, fake backend | API42 не изменён, аддоны компилируются, CI зелёный |
| 2. Мигратор | CLI `--inventory/--dry-run/--import/--verify/--report` | полный импорт фикстур rc.11 идемпотентен, discrepancy != 0 |
| 3. Ядро/финансы/social | DB-репозитории для data/bank/purchases/social, сохранены все guards | финансовые и social контрактные тесты зелёные |
| 4. Аддоны | DocumentStore adapters для остальных модулей, file writers выключены в MYSQL | инвентаризация пустая, ни одного JDBC в аддонах |
| 5. Staging RC | новый prerelease, docs/вики/memo только по явной команде | живой smoke владельца, CI Build/Release зелёные |
| 6. Удаление legacy | только после периода наблюдения | отдельное решение; не входит в первый RC |

## 12. Критерии приёмки всей задачи

- MySQL-подключение создаётся и закрывается только `CHRDK_Core`.
- Ни один аддон не имеет DB credentials, SQL, JDBC или connector dependency.
- Все данные из `storage-inventory.json` импортированы, для каждого файла есть
  checksum и отчёт; секреты входа отсутствуют в БД.
- Покупки, курсоры, receipts, entitlements, balances, lineage, intents и
  refunds после импорта совпадают с исходниками; повторный импорт ничего не
  выдаёт и не создаёт алмазы.
- Оба `/vk unlink` и `/social unlink` не воскрешают связь; legacy import
  безопасен и идемпотентен.
- `API_VERSION=42`, `STORAGE_API_VERSION=1`, rc.10/rc.11 immutable.
- `FILE` rollback и `FILE_READONLY` сверка работают; DB down fail-closed.
- CI собирает весь комплект, тесты MySQL и wiki/release contracts зелёные.
- Живой staging smoke пройден владельцем.
- Публикация нового Release/VK-анонса выполняется только после отдельной
  команды и зелёного CI.

## 13. Открытые вопросы перед кодом

1. Точная версия/хостинг MySQL и есть ли отдельная staging-база. По умолчанию
   проектируем MySQL 8.0+, InnoDB, `utf8mb4`, UTC.
2. Способ выдачи одноразовых кодов для игроков без VK/MAX при перевыпуске
   пароля: админ-команда, support-процесс или временный код из консоли.
3. Нужно ли в первом RC переводить JSONL аудит/логи и social wall/daily
   ledger'ы в полноценные append-only таблицы, или оставить их документами/
   файлами до фазы 6.
4. Имя/номер нового RC и момент публикации; текущий `1.5.2-rc.11` не меняется.
5. Политика хранения frozen files после периода наблюдения: архив вне сервера,
   read-only каталог или удаление по отдельному акту.

До ответа на эти вопросы код, `plugin_version`, теги, Release, VK и Wiki не
трогаем.
