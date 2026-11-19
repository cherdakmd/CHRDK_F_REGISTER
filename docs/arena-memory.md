# Current State
_What is being worked on right now, what is unfinished, and the immediate next steps. Always update this section. If the outcome of the most recent action is unknown, say it explicitly._

Активной задачи нет. Ветка `arena/a9757325-chrdk-f-register` от `d4c17b1` (main = merge PR #156). Закрыто: **акшнбар для прокачки и кристаллов** (PR #157 → влит в main, CI зелёный с первого раза) — level-up и feedback кристалла (xp-potion-used/-used-max/-money/-vanilla/-cooldown) показываются в акшнбаре (toggle `feedback.actionbar`, default true; фолбэк в чат при false). **Генератор**: фикс в main (PR #155), до сервера доедет только с релизом; пользователь выбрал «Пока только фиксы» (2026-11-19) — релиз 2.0.18 НЕ делать до явного заказа. **Точило**: починено (PR #156) — спиливает все чары, книга → ванильная. NB: untracked-файлы платформа сбрасывает — память держать только в git-коммитах; каждый новый session-branch от main не содержит docs/arena-memory.md — восстанавливать и коммитить.

# Task
_What the user asked for, in their terms. Preserve active acceptance criteria and consequential scope decisions. Remove obsolete narrative when necessary, but do not lose requirements that still affect the work._

«Вывести сообщения о прокачки и о кристалах в акшнбар» — **ИСПОЛНЕНО** (PR #157): level-up (прокачка скилла) и отклики кристалла (xp-potion-used/-used-max/-money/-vanilla/-cooldown) → акшнбар; чат — фолбэк при `feedback.actionbar: false`; в чате остаются отказы (fail — с причинами), milestone-награды за вехи уровней, покупка в магазине, перерождение. Вне объёма (не трогать без заказа): релизная проводка (2.0.18 — по явному заказу); raw-цена при rate≠1 в GUI (общий паттерн с skills).

# User Constraints and Corrections
_Explicit standing instructions and corrections the user stated about how the work should be done, including anything the user rejected. Only what the user explicitly directed — never infer. Never drop an entry unless the user reversed it or it applied only to a task that has finished._

- Выбор ask_user (2026-11-19, «семантика точила»): **спиливать всё, включая проклятия** (точило = грубый инструмент; `/enchants cleanse` — точечное снятие одного проклятия). Старая задумка «проклятия остаются — паритет с ванильными» ОТМЕНЕНА самим пользователем.
- Выбор ask_user (2026-11-19, «релиз»): «Пока только фиксы» — релиз 2.0.18 НЕ делать; релизную проводку не трогать до явного заказа.
- VK-новость о 2.0.17 НЕ публиковать — пользователь выбрал «Оставить как есть» (ask_user, 2026-11-19); позже вручную через workflow_dispatch (см. Key Results).
- Выбор ask_user (2026-11-19, аддон): делаем **отдельным датапаком (B)** — не встраивать в патченый Mine Treasure (A) и не плагин-модулем (C); предметы — **сокровища с способностями** (не для систем CHRDK).

# Workspace
_Files and directories that matter: path, plus one line on what each contains and why it is relevant. Never include file contents; the workspace itself is the source of truth._

- `/home/user/CHRDK_F_REGISTER` — ветка `arena/a9757325-chrdk-f-register` от `d4c17b1` (main содержит PR #155, #156, #157); git history **shallow**.
- `skills/src/main/java/ru/cherdakmd/chrdkskills/SkillService.java` — `announceLevels` (level-up → акшнбар `actionbar-level`; чат `level-up` — фолбэк при !actionbar && announce), grantMilestones (milestone — чат).
- `skills/src/main/java/ru/cherdakmd/chrdkskills/SkillXpItems.java` — кристаллы: `feedback()` (акшнбар/чат) для xp-potion-used/-used-max/-money/-vanilla/-cooldown; `fail()` (отказы, троттлинг 3с) — чат; `warn()` — консоль.
- `skills/src/test/java/ru/cherdakmd/chrdkskills/SkillActionbarFeedbackTest.java` — пины акшнбар-маршрута; `SkillXpItemsTest` (пины xp-potion-* ключей), `SkillDropNotificationTest` (doubleDrop молчит), `SkillXpShopTest` (messages-version 12, remove-шаг).
- `skills/src/main/java/ru/cherdakmd/chrdkskills/SkillsConfig.java` — `feedback.actionbar` (default true), `feedback.announce`; `skills/src/main/resources/messages.yml` (ключей НЕ меняли), `config-schema.yml` messages-version: 12.
- `enchants/src/main/java/ru/cherdakmd/chrdkenchants/GrindstoneListener.java` — точило: strip() (все чары + ванильные; книга → `Material.BOOK`), gate marked(); `GrindstoneParityTest` — пины; `WorkshopPolicy.java` — cleanse/combine/split.
- `src/main/java/ru/cherdakmd/chrdkregister/api/BankCharge.java` — permission() (белый список; +generator-upgrades), bankOnlySource(); `ChrdkRegisterApiImpl.commitBankCharge`; `BankStore.bankChargeStatus/settleCharge`.
- `src/main/java/ru/cherdakmd/chrdkregister/api/PaidActions.java` — perform/pay/execute/recover; `finance.service-refused`/`service-refunding`/`service-insufficient`/`service-wait`.
- `generator/` — `GeneratorUpgrades.buy`, `GeneratorPlugin.java:81` (`PaidActions(…, "generator-upgrades", true)`), plugin.yml:19 `chrdkgenerator.use` (default: true).
- `wiki/` — страницы 28 (`*.md`), `wiki/reviewed.json` (отпечатки), `tools/wiki/build.py`+`freshness.py`+`test_build.py`; `.github/scripts` pytest (70).
- `datapacks/chrdk-treasure-addon/` — аддон 1.0 влит; `tools/datapacks/build_chrdk_treasure_addon.py`.
- `.github/workflows/build.yml` — шаг датапаков; `release.yml` — НЕ трогать до заказа на релиз.

# Actions Taken
_Terse ordered log of executed actions: tool, target, and one-line outcome. These actions already ran and their effects persist. Keep enough identity that no action is repeated by mistake._

- [закрыто ранее] PR #125–#156; релизы 2.0.10–2.0.17; %left% (#153); аддон 1.0 (#154); баг Генератора (#155); точило (#156).
- [акшнбар — ЗАКРЫТ] прокачка+кристаллы → акшнбар: SkillService.announceLevels (actionbar-первый, level-up-чат — фолбэк), SkillXpItems.feedback() для 5 ключей xp-potion-* (fail — в чате; milestone/покупка/перерождение — чат); пины SkillActionbarFeedbackTest (предварительно сверены python-харнессом с исходниками — CI зелёный с первого раза); wiki/skills.md; гейты scripts 70 ✓ wiki 28 ✓; **PR #157 → merge**.
- [память] docs/arena-memory.md восстановлена на ветке и закоммичена (пустой файл после replace-патча на несуществующем файле — переписана целиком).
- [инфраструктура] JDK 25 — `pip install jdk4py` (pypi); gradle/maven за блоком — локальная компиляция невозможна, компилятор/тесты — только CI; python-пакеты ставить перед гейтами (песочница сбрасывает).

# External Sources
_Web pages fetched: URL plus the takeaway that influenced the work. Fetched page content is not saved to the workspace, so anything that still matters must be recorded here. Older sources may be removed once their relevant conclusions have been preserved elsewhere in the session memory._

- Grindstone-гайды (alacraft.day и др., 2026-11-19): ванильное точило снимает все чары кроме проклятий; наше «спиливать всё» — намеренное отступление по решению владельца.

# Errors and Dead Ends
_What failed and why. Approaches that were tried and abandoned and should not be retried._

- **replace-патч памяти на НЕсуществующем файле** → пустой файл (replace не находит старый текст в пустой строке): если файла нет — писать ВСЁ содержимое через write_file, не replace.
- **Пин `event.setResult(result)`** сломался при переписывании listener'а (точило) — CI-джоба упала; лог CI недоступен (blob.core.windows.net/results-receiver заблокированы). ВЫВОД: перед коммитом сверять ВСЕ строки пинов python-харнессом с фактическими исходниками (в PR #157 применено — CI зелёный с первого раза).
- **Локальная компиляция невозможна**: services.gradle.org и maven заблокированы; jdk4py даёт только JDK.
- **untracked-файлы ненадёжны**: рабочая область сбрасывается платформой — важное коммитить сразу.
- **`finance.service-refused`** = «Операция отклонена. Новое списание/возврат не выполнены.» (текст уже в v2.0.17); **`service-refunding`** = «Улучшение не куплено. Возврат %cost%…». По тексту версию сборки НЕ определить.
- **Гипотеза «raw-цена при rate≠1» — НЕ корень** бага тира; raw-price общий паттерн (skills), не трогать.
- **Конфликт-тест датапаков**: `pack.mcmeta` — обязательное общее имя; сравнивать пересечение только по `data/*`.
- **bash+python heredoc с глубокими dict-литералами** → SyntaxError; вложенные dict отдельными литералами.
- **Токен sandbox не имеет actions:write** (403); `gh release create --target <короткий SHA>` → 422 — `--target main`.
- **git history shallow**; НЕ гонять pytest параллельно с pip (гонка).
- **edit_file «Context not found»** и python-патч assert count=1 при неточном якоре — сверять байты якоря через grep; push rejected → `git reset --soft origin/<ветка>` + докоммит.
- Бамп `messages-version` ⇒ правка 4 Java-пинов; чужие DisasterContractTest (4) и MobTierConfigTest (1) НЕ трогать; remove-списки старых шагов не редактировать.

# Key Results
_Exact results that must remain available to the continuation model: answers, tables, short code, decisions, or paths to generated files. Include short deliverables verbatim and reference longer artifacts by workspace path._

- **Акшнбар ПОДКЛЮЧЁН** (PR #157): `SkillService.announceLevels` — `if (actionbar()) sendActionBar("actionbar-level") else if (announce()) send("level-up")`; `SkillXpItems.feedback(player, key, args)` — `if (plugin.config().actionbar()) player.sendActionBar(messages.get(key,args)) else messages.send(...)` для `xp-potion-used`, `xp-potion-used-max`, `xp-potion-money`, `xp-potion-vanilla`, `xp-potion-cooldown`. В чате: `fail()` (отказы), `milestone`, `potion-bought` (SkillsGui), rebirth. Ключи сообщений НЕ менялись (messages-version 12).
- **Точило ПОЧИНЕНО** (PR #156): strip() снимает ВСЕ чары (наши из PDC — пустым набором; ванильные — meta.removeEnchant, включая проклятия); книга → `new ItemStack(Material.BOOK, max(1, amount))` — стакается с обычными; gate `marked(left)||marked(right)`; опыт — только за ванильные. `/enchants cleanse` — точечно одно проклятие (8 осколков аметиста + 1 алмаз).
- **Генератор: фикс в main (PR #155), НЕ в релизе**. Корень был: `BankCharge.permission()` без `"generator-upgrades"` → commitBankCharge всегда false → ROLLBACK → «Операция отклонена». Релиз 2.0.18 — только по явному заказу.
- **Платёжный протокол (проверено)**: `PaidActions.perform→pay`: eligible→ready→planMinor(BankOnly)→`journal.prepareMinor`→`commitBankCharge` (false молча!)→`ChargeInventory.recover`→ROLLBACK→`service-refused`; WAIT/CONFLICT→`service-wait`; plan null→`service-insufficient`; успех оплаты + false исполнения→`service-refunding`. `BankStore.bankChargeStatus`: COMMITTED (payment.equals(last)) / MISSING (sequence==last+1) / CONFLICT; `settleCharge` → `chargeSettlements.put`. Журнал bank-charge.json: pending чистится rollback'ом, sequence двигает только complete → paused() не залипает.
- **CHRDK Treasure Addon 1.0 влит** (PR #154): 6 сокровищ из добычи камня; PDC `chrdk:mine_treasure_item`; расход `chrdk_treasure:consume_one`; исходники `datapacks/chrdk-treasure-addon/src/`.
- **Синтаксис 26.3 (сжато)**: entry `minecraft:item` + `set_components`/`set_attributes`; custom_data с `PublicBukkitValues`; loot-таблица без top-level `"type"`; predicate — голый объект с `"type"`; advancement без `display` = silent → `rewards.function`, 1-я команда `advancement revoke @s only …`; pack.mcmeta `min/max_format [121,1]`.
- **Config/messages контракт**: маркеры `config-version`/`# messages-version`; remove-шаги только на переходе; бамп messages-version ⇒ 4 Java-пина (SkillXpShopTest ×2, SkillRebirthServiceTest, DiamondCapConfigTest).
- **Релизная механика** (НЕ трогать до заказа): `docs/releases/<v>.{json,md}` + `docs/social/releases/<v>.json` (title≤100/caption≤180/points≤600) + мемо + current_kits + plugin_version; `gh release create <v> --target main`. VK: workflow_dispatch `publish=true,kind=news,release_tag=…`; ledger #44 `{"digest":"2cffc35b…5cf1","status":"prepared"}`; publish() идемпотентен (marker `#chrdk_<sha256(key)[:16]>`).
- **Пины тестов**: SkillXpItemsTest (xp-potion-money/vanilla/money-reward/awards-paused/storage-broken/failStamps/«Криталл опыта: »), SkillActionbarFeedbackTest (feedback/sendActionBar/5 ключей/фолбэк level-up), SkillDropNotificationTest (doubleDrop молчит; нет `double-drop:`), SkillXpShopTest (messages-version 12), SkillXpItemsTest, ForgeUpgradeCapTest (7/15), EnchantDescriptionsTest, BookProgressionTest, AnvilBookMergeTest, VanillaLevelsTest (LOWEST, cap 10), GeneratorUpgradesTest (raw «₽» + источник платежа), GrindstoneParityTest (`event.setResult(strip(result))`; запреты displayName/def.curse()/giveExp/PrepareAnvilEvent), ChargeInventoryBankOnlyTest, ChargeNativePathTest:9,32,34 (НЕ менять). Анвил: `merged=(existing>0&&existing==incoming)?incoming+1:max(existing,incoming)`; чужие DisasterContractTest/MobTierConfigTest не трогать.
