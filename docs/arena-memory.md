# Current State
_What is being worked on right now, what is unfinished, and the immediate next steps. Always update this section. If the outcome of the most recent action is unknown, say it explicitly._

Активной задачи нет. Ветка `arena/5a57191a-chrdk-f-register` от `2769a68` (main = merge PR #155). Закрыто в этой сессии: **точило починено** (PR #156 → влит в main, CI зелёный: Purpur/Java ✓, wiki ✓, social ✓) — спиливает ВСЕ чары (включая проклятия и ванильные), книга после точила — чисто ванильная (стакается с обычными). **Генератор**: фикс в main с прошлой сессии (PR #155), но на сервере старая сборка — пользователь по ask_user (2026-11-19) выбрал «Пока только фиксы»: релиз 2.0.18 НЕ делать, сборку он обновит сам/позже. Ждать новых задач. NB: untracked-файлы платформа сбрасывает — память держать только в git-коммитах (в main её нет: коммит памяти висит на старой ветке `arena/c02d5d58…`, PR на неё закрыт).

# Task
_What the user asked for, in their terms. Preserve active acceptance criteria and consequential scope decisions. Remove obsolete narrative when necessary, but do not lose requirements that still affect the work._

Пользователь прислал 2 скрина: (1) Генератор шлёт «Операция отклонена. Новое списание/возврат не выполнены.» ×12 — это старая сборка до фикса PR #155 (тот же баг «Улучшить тир»), фикс уже в main; пользователь выбрал «пока только фиксы» (без релиза 2.0.18); (2) при спиливании чар через точило «не все чары спиливаются и книги не стакаются с ванильными книгами» — **исполнено** (PR #156): по выбору пользователя (ask_user, «Спиливать всё, включая проклятия») точило снимает все чары, книга становится ванильной. Вне объёма: релизная проводка (до заказа); raw-цена при rate≠1 в GUI (общий паттерн, не трогать).

# User Constraints and Corrections
_Explicit standing instructions and corrections the user stated about how the work should be done, including anything the user rejected. Only what the user explicitly directed — never infer. Never drop an entry unless the user reversed it or it applied only to a task that has finished._

- Выбор ask_user (2026-11-19, «семантика точила»): **спиливать всё, включая проклятия** (точило = грубый инструмент; `/enchants cleanse` — точечное снятие одного проклятия). Старая задумка «проклятия остаются — паритет с ванильными» ОТМЕНЕНА самим пользователем.
- Выбор ask_user (2026-11-19, «релиз»): «Пока только фиксы» — релиз 2.0.18 НЕ делать; релизную проводку не трогать до явного заказа.
- VK-новость о 2.0.17 НЕ публиковать — пользователь выбрал «Оставить как есть» (ask_user, 2026-11-19); позже вручную через workflow_dispatch (см. Key Results).
- Выбор ask_user (2026-11-19, аддон): делаем **отдельным датапаком (B)** — не встраивать в патченый Mine Treasure (A) и не плагин-модулем (C); предметы — **сокровища с способностями** (не для систем CHRDK).

# Workspace
_Files and directories that matter: path, plus one line on what each contains and why it is relevant. Never include file contents; the workspace itself is the source of truth._

- `/home/user/CHRDK_F_REGISTER` — ветка `arena/5a57191a-chrdk-f-register` от `2769a68` (main содержит PR #155 и #156); git history **shallow**.
- `enchants/src/main/java/ru/cherdakmd/chrdkenchants/GrindstoneListener.java` — точило: strip() (все чары + ванильные снимаются; книга → `Material.BOOK`), build() (одиночный вход), gate `marked(left)||marked(right)`.
- `enchants/src/test/java/ru/cherdakmd/chrdkenchants/GrindstoneParityTest.java` — пины новой семантики (снимается всё; книга ванильная; `event.setResult(strip(result))`).
- `enchants/src/main/java/ru/cherdakmd/chrdkenchants/EnchantItems.java` — PDC `custom_enchants` («id:level;…»), `book_source_v1`, lore-маркеры «✶ » (легаси «✦ »), `makeEnchantBook` (ENCHANTED_BOOK + имя gui-book-name/-curse), write({}) чистит PDC+лор+атрибуты.
- `enchants/src/main/java/ru/cherdakmd/chrdkenchants/WorkshopPolicy.java` — мастерская: cleanse (одно проклятие), combine, split (1 книга + 8 лазурита).
- `src/main/java/ru/cherdakmd/chrdkregister/api/BankCharge.java` — permission() (белый список источников; +generator-upgrades в PR #155), bankOnlySource() (+generator-upgrades).
- `src/main/java/ru/cherdakmd/chrdkregister/ChrdkRegisterApiImpl.java` — `commitBankCharge` (permission→plan-сверка→settleCharge), `BankStore.bankChargeStatus/settleCharge` (sequence = last+1, MISSING/COMMITTED/CONFLICT).
- `src/main/java/ru/cherdakmd/chrdkregister/api/PaidActions.java` — perform/pay/execute/recover; `finance.service-refused` = ROLLBACK/цена вне 1..1e6; `finance.service-refunding` = успех оплаты + отказ исполнения (возврат).
- `generator/` — модуль CHRDK_Generator: `GeneratorUpgrades.buy` (ресурсы до платежа, consume в исполнении), `GeneratorPlugin.java:81` (`PaidActions(…, "generator-upgrades", true)`), plugin.yml:19 `chrdkgenerator.use` (default: true).
- `wiki/enchants.md` — раздел «Точило» и FAQ обновлены под новую семантику; `wiki/reviewed.json` — отпечатки.
- `tools/wiki/build.py` + `tools/wiki/freshness.py` + `tools/wiki/test_build.py` (28); `.github/scripts` pytest (70).
- `datapacks/chrdk-treasure-addon/` — ГОТОВО (аддон 1.0 влит); `tools/datapacks/build_chrdk_treasure_addon.py`.
- `.github/workflows/build.yml` — шаг датапаков; `release.yml` — НЕ трогать до заказа на релиз.

# Actions Taken
_Terse ordered log of executed actions: tool, target, and one-line outcome. These actions already ran and their effects persist. Keep enough identity that no action is repeated by mistake._

- [закрыто ранее] PR #125–#155; релизы 2.0.10–2.0.17; %left% (PR #153); аддон 1.0 (PR #154); баг Генератора «тира» (PR #155: BankCharge +generator-upgrades).
- [точило — ЗАКРЫТ] разведка GrindstoneListener/EnchantItems/WorkshopPolicy; ask_user: «спиливать всё» + «пока только фиксы»; GrindstoneListener переписан (strip() — всё снимается, книга → ванильная), пины теста переписаны, wiki/enchants.md обновлён; пин-промах `event.setResult(result)`→`strip(result)` поправлен (e3e4b2e); гейты: scripts 70 ✓ wiki 28 ✓; **PR #156 → merge** (CI: Purpur ✓ wiki ✓ social ✓).
- [память] docs/arena-memory.md восстановлена на ветке и закоммичена (untracked-файлы платформа сбрасывает).
- [инфраструктура] JDK 25 поставлен через `pip install jdk4py` (pypi); gradle-дистрибутив и maven-зависимости НЕ доступны (services.gradle.org/maven заблокированы) — локальная компиляция невозможна, компилятор/тесты — только CI.

# External Sources
_Web pages fetched: URL plus the takeaway that influenced the work. Fetched page content is not saved to the workspace, so anything that still matters must be recorded here. Older sources may be removed once their relevant conclusions have been preserved elsewhere in the session memory._

- Grindstone-гайды (alacraft.day и др., 2026-11-19): ванильное точило снимает все чары кроме проклятий, чинит/комбинирует предметы; наши изменения семантики — намеренное отступление (решение владельца).

# Errors and Dead Ends
_What failed and why. Approaches that were tried and abandoned and should not be retried._

- **Пин `event.setResult(result)`** сломался при переписывании listener'а (`event.setResult(strip(result))`) — Purpur-джоба упала; лог CI недоступен (blob.core.windows.net/results-receiver заблокированы) — искать поломку локальной сверкой строк пинов с исходником (python-харнесс).
- **Локальная компиляция невозможна**: gradle wrapper не качает дистрибутив (services.gradle.org), maven-резолю тоже за блоком; jdk4py (pypi) даёт только JDK. Проверять пины python-сверкой строк; компиляция/тесты — CI.
- **untracked-файлы ненадёжны**: рабочая область сбрасывается платформой — память и прочее важное коммитить в ветку сразу.
- **Теория «тихого отказа» опровергнута**: все отказы pay() говорят; диагноз по тексту сообщения (ask_user), не по молчанию.
- **`finance.service-refused`** = «Операция отклонена. Новое списание/возврат не выполнены.» (текст уже в v2.0.17); **`service-refunding`** = «Улучшение не куплено. Возврат %cost%…». По тексту версию сборки НЕ определить.
- **Гипотеза «raw-цена при rate≠1» — НЕ корень** бага тира; raw-price общий паттерн (skills), не трогать.
- **Конфликт-тест датапаков**: `pack.mcmeta` — обязательное общее имя; сравнивать пересечение только по `data/*`.
- **bash+python heredoc с глубокими dict-литералами** → SyntaxError; вложенные dict отдельными литералами.
- **Токен sandbox не имеет actions:write** (403); `gh release create --target <короткий SHA>` → 422 — `--target main`.
- **git history shallow**; Свежая песочница теряет pip-пакеты — `pip install --break-system-packages --user pyyaml pillow pytest` перед гейтами; НЕ гонять pytest параллельно с pip.
- **edit_file «Context not found»** и python-патч assert count=1 при неточном якоре — сверять байты якоря через grep; push rejected → `git reset --soft origin/<ветка>` + докоммит.
- Бамп `messages-version` ⇒ правка 4 Java-пинов; чужие DisasterContractTest (4) и MobTierConfigTest (1) НЕ трогать; remove-списки старых шагов не редактировать.

# Key Results
_Exact results that must remain available to the continuation model: answers, tables, short code, decisions, or paths to generated files. Include short deliverables verbatim and reference longer artifacts by workspace path._

- **Точило ПОЧИНЕНО** (PR #156): strip() снимает ВСЕ чары (наши из PDC пишутся пустым набором; ванильные — meta.removeEnchant, включая проклятия); книга (BOOK/ENCHANTED_BOOK на выходе) → `new ItemStack(Material.BOOK, max(1, amount))` — чисто ванильная, стакается с обычными; gate `marked(left)||marked(right)` (ванильные без наших меток не трогаем); опыт — только за ванильные. `/enchants cleanse` — точечное снятие одного проклятия (8 осколков аметиста + 1 алмаз).
- **Генератор: фикс в main (PR #155), НЕ в релизе**. Корень был: `BankCharge.permission()` без `"generator-upgrades"` → commitBankCharge всегда false → ROLLBACK → «Операция отклонена». Пользователь на старой сборке видит тот же баг; релиз 2.0.18 — только по явному заказу (сейчас «пока только фиксы»).
- **Платёжный протокол (проверено)**: `PaidActions.perform→pay`: eligible→ready (paused/регистрация источника)→planMinor(BankOnly)→`journal.prepareMinor`→`commitBankCharge` (вернёт false молча!)→`ChargeInventory.recover`→ROLLBACK→`service-refused`; WAIT/CONFLICT→`service-wait`; plan null→`service-insufficient`; успех оплаты + false исполнения→`service-refunding`. `BankStore.bankChargeStatus`: COMMITTED (payment.equals(last)) / MISSING (sequence==last+1) / CONFLICT иначе; `settleCharge` — сверка баланса + `chargeSettlements.put(source, payment)`. Журнал `bank-charge.json` (модуль): pending чистится rollback'ом, sequence двигает только complete → paused() не залипает.
- **CHRDK Treasure Addon 1.0 влит** (PR #154): 6 сокровищ из добычи камня; PDC `chrdk:mine_treasure_item`; расход `chrdk_treasure:consume_one`; исходники `datapacks/chrdk-treasure-addon/src/`.
- **Синтаксис 26.3 (сжато)**: entry `minecraft:item` + `set_components`/`set_attributes`; custom_data с `PublicBukkitValues`; loot-таблица без top-level `"type"`; predicate — голый объект с `"type"`; advancement без `display` = silent → `rewards.function`, 1-я команда `advancement revoke @s only …`; pack.mcmeta `min/max_format [121,1]`.
- **Config/messages контракт**: маркеры `config-version`/`# messages-version`; remove-шаги только на переходе; бамп messages-version ⇒ 4 Java-пина (SkillXpShopTest ×2, SkillRebirthServiceTest, DiamondCapConfigTest).
- **Релизная механика** (НЕ трогать до заказа): `docs/releases/<v>.{json,md}` + `docs/social/releases/<v>.json` (title≤100/caption≤180/points≤600) + мемо + current_kits + plugin_version; `gh release create <v> --target main`. VK: workflow_dispatch `publish=true,kind=news,release_tag=…`; ledger #44 `{"digest":"2cffc35b…5cf1","status":"prepared"}`; publish() идемпотентен (marker `#chrdk_<sha256(key)[:16]>`).
- **Пины тестов**: SkillXpItemsTest, ForgeUpgradeCapTest (7/15), EnchantDescriptionsTest, BookProgressionTest, AnvilBookMergeTest, VanillaLevelsTest (LOWEST, cap 10), GeneratorUpgradesTest (raw «₽» + источник платежа), GrindstoneParityTest (всё спиливается; книга ванильная; `event.setResult(strip(result))`; запреты displayName/def.curse()/giveExp/PrepareAnvilEvent), ChargeInventoryBankOnlyTest (право/bankOnly/lottery-null), ChargeNativePathTest:9,32,34 (НЕ менять). Анвил: `merged=(existing>0&&existing==incoming)?incoming+1:max(existing,incoming)`; чужие DisasterContractTest/MobTierConfigTest не трогать.
