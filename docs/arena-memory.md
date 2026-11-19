# Current State
_What is being worked on right now, what is unfinished, and the immediate next steps. Always update this section. If the outcome of the most recent action is unknown, say it explicitly._

Активной задачи нет: **баг Генератора — ЗАКРЫТ** (PR #155 → влит в main `e251a08`+merge, CI зелёный: .github/scripts 70 ✓, wiki 28 ✓, Purpur/Java 25 ✓). Ветка `arena/c02d5d58-chrdk-f-register`. **Корень бага**: `BankCharge.permission(source)` — белый список источников для `commitBankCharge`; `"generator-upgrades"` туда не добавили при вводе модуля (2.0.14) → `commitBankCharge` всегда `false` → ROLLBACK → «Операция отклонена» на 100% покупок (тир/модули/чанк) независимо от баланса. Фикс (`b11b769`): `case "generator-upgrades" -> "chrdkgenerator.use"` (узел в plugin.yml, default: true) + `bankOnlySource` += generator-upgrades. Журнал не застревал (rollback не двигает `data.sequence`) — ручное вмешательство не нужно. Открыт вопрос (не навязывать): фикс до сервера доедет только с релизом — пользователь не ответил, делать ли **2.0.18**. NB: рабочая область была сброшена платформой (untracked-файлы пропали, git цел) — память восстановлена из сводки и теперь закоммичена.

# Task
_What the user asked for, in their terms. Preserve active acceptance criteria and consequential scope decisions. Remove obsolete narrative when necessary, but do not lose requirements that still affect the work._

Баг `%left%` (PR #153) закрыт; **CHRDK Treasure Addon 1.0 готов и влит** (PR #154 → `e251a08`), пользователь принял. Баг «Улучшить тир» Генератора **исполнен и влит** (PR #155): покупки тира/модулей/чанка снова работают. Вне объёма (не трогать без заказа): честная цена при rate≠1 в GUI (raw-price — общий паттерн с skills, косметика). Релиз 2.0.18 предложен — ответа нет; релизную проводку не трогать до заказа.

# User Constraints and Corrections
_Explicit standing instructions and corrections the user stated about how the work should be done, including anything the user rejected. Only what the user explicitly directed — never infer. Never drop an entry unless the user reversed it or it applied only to a task that has finished._

- VK-новость о 2.0.17 НЕ публиковать — пользователь выбрал «Оставить как есть» (ask_user, 2026-11-19); позже вручную через workflow_dispatch (см. Key Results).
- Выбор ask_user (2026-11-19): аддон делаем **отдельным датапаком (B)** — не встраивать предметы в сам патченый Mine Treasure (A) и не плагин-модулем (C); предметы — **сокровища с способностями** (не для систем CHRDK).

# Workspace
_Files and directories that matter: path, plus one line on what each contains and why it is relevant. Never include file contents; the workspace itself is the source of truth._

- `/home/user/CHRDK_F_REGISTER` — ветка `arena/c02d5d58-chrdk-f-register` от `e251a08` (main = merge PR #154; PR #155 уже влит); git history **shallow**.
- `src/main/java/ru/cherdakmd/chrdkregister/api/BankCharge.java` — белый список источников платежей (`permission()`) + `bankOnlySource()`; фикс b11b769: +generator-upgrades.
- `src/main/java/ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.java` — `commitBankCharge` (permission→valid→plan-сверка→bank.open+settleCharge), `spendableBankBalance*`, `auctionBankReady`.
- `src/main/java/ru/cherdakmd/chrdkregister/api/` — `PaidActions.java` (perform/pay/execute), `PaidActionStore.java`, `ChargeRecovery.java`, `ChargeJournal.java`, `ChargeInventory.java`, `DiamondMoney.java`, `DiamondExchangePolicy.java`.
- `src/test/java/ru/cherdakmd/chrdkregister/` — `ChargeInventoryBankOnlyTest.java` (пины фикса: право/bankOnly/lottery-null), `ChargeNativePathTest.java:9,32,34` (строковые пины commitBankCharge/PaidActions — НЕ менять), `AddonRetirement271Test.java:33,147` (`assertNull(permission("lottery"))`).
- `generator/` — модуль CHRDK_Generator: `GeneratorUpgrades.java` (buy/hasResources/consume/upgradeTier), `GeneratorGui.java`, `GeneratorPlugin.java:81` (`new PaidActions(..., "generator-upgrades", true)`), `src/main/resources/plugin.yml:19` (`chrdkgenerator.use`, default: true), `messages.yml:55,93–97`; `src/test/.../GeneratorUpgradesTest.java` — пины describe() raw «25000 ₽ + IRON_INGOT × 256» + пин источника платежа.
- `skills/.../SkillXpShop.java:38` — `"skill-xp"` (в permission-таблице ✓ — референс рабочего пути).
- `datapacks/chrdk-treasure-addon/` — ГОТОВО (src/ 29 файлов, zip 1.0); `tools/datapacks/build_chrdk_treasure_addon.py` + тесты.
- `.github/workflows/build.yml` — шаг датапаков; `release.yml:92,194–203` — НЕ трогать до релиза; `docs/RELEASE.md` — регламент; `tools/release/` — гейты.
- `tools/wiki/freshness.py` + `wiki/reviewed.json` (отпечатки страниц) + `wiki/pages` (28) + `tools/wiki/build.py` (Проверено/Источники).
- `docs/releases/2.0.x.{json,md}` + `docs/social/releases/*.json` + `docs/RELEASE_NOTES.md` + `tools/release/update-memo.txt` + `plugin/docs/changelog/latest.md` + `plugin/docs/README.md` + `plugin/docs/guides/current-kits.md` + `plugin/docs/support/plugin-version.txt`.

# Actions Taken
_Terse ordered log of executed actions: tool, target, and one-line outcome. These actions already ran and their effects persist. Keep enough identity that no action is repeated by mistake._

- [закрыто] PR #125–#153; релизы 2.0.10–2.0.17; генератор закрыт; orders удалён; фиксы A/B/C (`3a4472c`); релизные файлы 2.0.17 (`a2e7da5`); тег+релиз v2.0.17 (`--target main`).
- [баг %left% — ЗАКРЫТ] config-schema.yml `messages-version: 12` + шаг `12: remove: [...]` + 4 Java-пина → 12; `.github/scripts` 70 ✓; freshness `--accept skills` + wiki 28 ✓; `3f9099d`+`cf5620b`; PR #153 → merge `a323015`.
- [аддон — ЗАКРЫТ] 28 src-файлов + md + ОБНОВЛЕНИЕ.txt; build_chrdk_treasure_addon.py (`--check` ✓, 29 файлов); тест конфликтов починен (только `data/*`); build.yml +`--check`; `f2e8d77` → **PR #154 → merge `e251a08`**.
- [баг Генератора — ЗАКРЫТ] разведка×3 → ask_user (2026-11-19): «Операция отклонена…»/«Улучшение не куплено. Возврат…» → корень `BankCharge.permission` без `"generator-upgrades"`; фикс `b11b769` (BankCharge permission+bankOnlySource, пины ChargeInventoryBankOnlyTest + GeneratorUpgradesTest) → **PR #155 → merge** (CI: scripts 70 ✓ wiki 28 ✓ Purpur ✓).
- [память] `docs/arena-memory.md` пропал при сбросе рабочей области (untracked) → восстановлена из сводки сессии и закоммичена в ветку.

# External Sources
_Web pages fetched: URL plus the takeaway that influenced the work. Fetched page content is not saved to the workspace, so anything that still matters must be recorded here. Older sources may be removed once their relevant conclusions have been preserved elsewhere in the session memory._

# Errors and Dead Ends
_What failed and why. Approaches that were tried and abandoned and should not be retried._

- **Теория «тихого отказа» опровергнута**: в `pay()` `say("wait"); return false;` безусловны внутри WAIT/CONFLICT; begin-false тоже шлёт «Возврат» — все отказы видимы; диагноз по тексту сообщения, не по молчанию.
- **Гипотеза «raw-цена при rate≠1» — НЕ корень бага тира** (отклонена диагнозом пользователя о явном отказе); raw-price остаётся общим паттерном (skills тоже), не трогать.
- **untracked-файлы ненадёжны**: рабочая область сбрасывается платформой — память и прочее важное коммитить в ветку сразу.
- **Конфликт-тест датапаков**: `pack.mcmeta` — обязательное общее имя, НЕ конфликт; сравнивать пересечение только по `data/*`.
- **bash+python heredoc с глубокими dict-литералами** → SyntaxError; вложенные dict отдельными литералами.
- **Токен sandbox не имеет actions:write** (403) — workflow только из UI/gh пользователя; `gh release create --target <короткий SHA>` → 422 — `--target main`.
- **git history shallow** — ретро-правки через git не проверить.
- Свежая песочница теряет pip-пакеты — `pip install --break-system-packages --user pyyaml pillow pytest` перед гейтами; НЕ гонять pytest параллельно с pip (гонка).
- **edit_file «Context not found»** и python-патч assert count=1 при неточном якоре — сверять байты якоря через grep; push rejected → `git reset --soft origin/<ветка>` + докоммит.
- Бамп `messages-version` ⇒ правка 4 Java-пинов; чужие DisasterContractTest (4) и MobTierConfigTest (1) НЕ трогать; фикстуры со снятыми аддонами ломают CI.
- `describe()` в GUI показывает СЫРЫЕ деньги — тесты GeneratorUpgradesTest это пинят (обновлять пины, если менять).

# Key Results
_Exact results that must remain available to the continuation model: answers, tables, short code, decisions, or paths to generated files. Include short deliverables verbatim and reference longer artifacts by workspace path._

- **Баг Генератора ПОЧИНЕН (PR #155)**: корень — `BankCharge.permission()` не знал `"generator-upgrades"` → commitBankCharge всегда false → ROLLBACK → «Операция отклонена». Фикс `b11b769`: `case "generator-upgrades" -> "chrdkgenerator.use"` + `bankOnlySource` += `"generator-upgrades"`. Урок: **новый платёжный источник ОБЯЗАН получить строку в `BankCharge.permission()`** (и `bankOnlySource`, если платит только банком) — иначе 100% отказов. Журнал не застревал (rollback не двигает `data.sequence`).
- **Платёжный протокол (проверено)**: `PaidActions.perform(payer,actor,price,kind,payload,op)`; minor-режим (store schema==2): `charged=quote.scaleMinor(price*100L)` = `price*100*rateBps/10000` (rateBps 5000–20000) — GUI показывает raw price (не трогать). Цепочка: `journal.prepareMinor` → `commitBankCharge` → `ChargeInventory.recover` → `ChargeRecovery.decide`: MISSING+stateBefore&&!stateAfter&&cash==0→ROLLBACK (`rollback` чистит pending, НЕ двигает `data.sequence` → `paused()` не залипает); COMMITTED+cash==0→COMPLETE→`publish(beforeState,afterState)` + `journal.complete`. Сообщения: plan null→`service-insufficient`; ROLLBACK→`refused`; WAIT/CONFLICT→`wait`+lock при cash>0.
- **CHRDK Treasure Addon 1.0 влит** (PR #154 → `e251a08`): 6 сокровищ из добычи камня (0.02/0.005/0.001): lucky_coin, heavy_glove, spring_boots, smoke_bomb, phoenix_wing, star_key. PDC `chrdk:mine_treasure_item`; расход `chrdk_treasure:consume_one`. Исходники `datapacks/chrdk-treasure-addon/src/`.
- **Синтаксис 26.3 (сжато)**: entry `minecraft:item` + `set_components`/`set_attributes`; custom_data с `PublicBukkitValues`; loot-таблица без top-level `"type"`; predicate — голый объект с `"type"`; advancement без `display` = silent → `rewards.function`, 1-я команда `advancement revoke @s only …`; pack.mcmeta `min/max_format [121,1]`; `MineTreasureLootPolicy` (ядро): PDC → isForeignDrop → перки не дублируют.
- **%left% фикс** (`3f9099d`+`cf5620b`): skills messages-version 12 + шаг `12: remove: [messages.xp-potion-daily-limit, messages.xp-potion-used]` + 4 пина; remove-списки старых шагов не редактировать.
- **Config/messages контракт**: маркеры `config-version`/`# messages-version`; remove-шаги только на переходе; бамп messages-version ⇒ 4 Java-пина (SkillXpShopTest ×2, SkillRebirthServiceTest, DiamondCapConfigTest).
- **Релизная механика**: `docs/releases/<v>.{json,md}` + `docs/social/releases/<v>.json` (title≤100/caption≤180/points≤600) + мемо + current_kits + plugin_version; `gh release create <v> --target main`. VK: workflow_dispatch `publish=true,kind=news,release_tag=…`; ledger #44 `{"digest":"2cffc35b…5cf1","status":"prepared"}`; publish() идемпотентен (marker `#chrdk_<sha256(key)[:16]>`).
- **Пины тестов**: SkillXpItemsTest, ForgeUpgradeCapTest (7/15, `max-per-item: 7`), EnchantDescriptionsTest, BookProgressionTest, AnvilBookMergeTest, VanillaLevelsTest (LOWEST, `vanilla-level-cap: 10`), GeneratorUpgradesTest (raw «₽» в describe + источник платежа). Анвил: `merged=(existing>0&&existing==incoming)?incoming+1:max(existing,incoming)`; пины (2,2,5)=3,(5,5,5)=5,(4,1,5)=4,(10,3,10)=10,(10,10,10)=10,(5,5,10)=6,(15,3,10)=15,(2,2,2)=2; слои core LOWEST→наш LOWEST→tier NORMAL→enchants HIGH→core HIGHEST. Чужие DisasterContractTest/MobTierConfigTest не трогать.
