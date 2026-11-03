# Проверка и сборка предрелиза: фактический статус на 2026-11-03

**Итог: ZIP с плагинами не собран и не выдан.** В этой песочнице отсутствуют
исходники кандидата `1.4.282-rc.16`, а локальная Java-сборка заблокирована
сетевыми ограничениями. Бинарники исходниками не подменялись, архив исходников
готовой сборкой не выдавался, «готово» не объявляется. Ничего не устанавливалось
на сервер; RCON, TradeMC и мир не трогались; Release/PR/merge/push не выполнялись.

## 1. Источник истины: фактические файлы и git

| Проверка | Значение |
|---|---|
| `git branch --show-current` | `arena/49bc75f7-chrdk-f-register` (ветка текущей сессии) |
| `git status --short` | пусто (незакоммиченных изменений нет) |
| `git log -1` | `df63af7` (`grafted`), `tag: v1.4.281`, равен `origin/main` |
| `plugin_version` | `1.4.281` (стабильный релиз; не RC) |
| `gradle.properties` | Purpur `26.3.build.2647-experimental`, Minecraft `26.3`, Java 25 (`options.release = 25`) |
| `settings.gradle` | 22 `include`, из них `vk` — standalone ⇒ 22 плагина в общем комплекте |
| JDK / Gradle | `java`, `javac`, `JAVA_HOME`, `.gradle` — отсутствуют |
| `.venv` | отсутствует (создано локальное проверочное окружение в `/home/user`, в Git не входит) |

Документ задания описывает **другую ветку** (`arena/c99ef3d4-chrdk-f-register`),
статус `pr_open`, кандидат `1.4.282-rc.16`, Core API42 и «много изменений в
рабочей копии». В этой песочнице такого состояния нет. Ветку сессии переключать
и переименовывать нельзя, поэтому все проверки выполнены на фактическом дереве.

Отсутствуют файлы, перечисленные в задании:
`src/main/java/ru/cherdakmd/chrdkregister/api/ForgeMarketCatalog.java`,
`docs/audits/forge-shop-rc16.md`, `docs/releases/1.4.282.md`,
`tools/wiki/test_forge_market.py`, `.github/scripts/gradle_build.py`,
`.github/scripts/prepare_papi.py`, legacy-файл `...-1.4.282-rc15.yml`.
Восемь SKU Кузни, `forge-shop.enabled/prices`, `ForgeMaterial.seedUid` и
nullable frozen grant в коде не обнаруживаются.

## 2. Куда делись RC15/RC16

- Удалённая ветка `arena/c99ef3d4-chrdk-f-register` существует, PR #53 — `DRAFT`,
  `headRefOid = 0a6e93445233ff8da5cdcb59f50b4b568e622036`,
  `plugin_version = 1.4.282-rc.14`, последний коммит
  «feat(guilds): refine short-tag shine ... (RC14)», 2026-11-03 06:09 UTC.
- В её дереве есть только три прежних Hunt-расходника (`ForgeMaterial`,
  `ForgeMaterials`), `docs/releases/1.4.282.md`, `gradle_build.py`,
  `prepare_papi.py`; `ForgeMarketCatalog` и восьмитоварной витрины нет.
- Поиск GitHub: коммиты с `committer-date > 2026-11-03T06:00` — `total_count: 0`;
  code search `ForgeMarketCatalog` — 0; `"rc.16"` — 0.
- `gh release list`: последний предрелиз — `v1.4.282-rc.11` (2026-11-03 04:48),
  Latest — `v1.4.281`. RC16 не публиковался.
- CI: последние прогоны `Build` — RC14 (`0a6e934`, success, 2m44s); прогонов
  RC15/RC16 нет. Артефакты с `rc1*` отсутствуют.
- `git stash list`, `git reflog --all`, `git fsck --unreachable` — пусто;
  в `/home/user`, `/tmp` и других каталогах нет patch/diff/архива с RC16.

**Вывод:** незакоммиченные изменения RC15/RC16 из прежней среды не попали в
новую песочницу и никогда не были зафиксированы в Git. Восстановить их из
репозитория или CI невозможно. Исторические зелёные CI RC14 к RC16 отношения не
имеют (это и указано в задании).

## 3. Что реально проверено (все зелёное)

Полный Python-набор из актуального `.github/workflows/build.yml` + release.yml,
выполненный на фактическом дереве (логи: `/tmp/checks.log` вне репозитория):

| Шаг | Результат |
|---|---|
| `.github/scripts/check_retired_addons.py` | OK |
| `unittest ... test_retired_addons.py` | 13 tests, OK |
| `.github/scripts/check_messages.py` | OK, контракт сообщений всех модулей |
| `unittest ... 'test_*release.py'` | 23 tests, OK |
| `tools/trademc/build.py --check` + тесты | OK, 27 tests |
| `tools/clan_shop/build.py --check`, `build_bundle.py --check` + тесты | OK, 14 tests |
| `tools/wiki/build.py --check` | **OK** (в документе задания он красный из-за страниц commands/enchants/profile — это состояние другой рабочей копии RC16; здесь не воспроизводится, `reviewed.json` вслепую не принимался) |
| `unittest ... tools/wiki -p 'test_*.py'` | 20 tests, OK |
| `node --test tools/wiki/test_search.cjs` | OK |
| `tools/social/run.py --check` + тесты | OK, 53 tests |
| `git diff --check` | чисто |

Итого **150 unittest-тестов** + все `--check`/контрактные скрипты пройдены.
Это НЕ Java-компиляция и НЕ проверка текущего RC16.

Прочитаны штатные упаковщики: `pack_release.py <dir> <version> <dest.zip>`,
`verify_release.py <dir> <version>` и `--standalone <jar> <name> <version>`.
Контракт текущей версии скриптов: ровно 22 плагина одной версии + `ОБНОВЛЕНИЕ.txt`,
`vk` отдельно, проверка CRC/`plugin.yml`, запрет перезаписи существующего ZIP,
лимиты 64/32/2 МБ. Памятка берётся из `docs/releases/update-memo.txt`
(сейчас — для 1.4.281). Номер версии не повышался.

## 4. Почему нет Java-сборки и ZIP

1. `java`/`javac` не установлены. `jdk4py==25.0.2.1` с PyPI даёт **Temurin
   25.0.2 JRE**: есть `java`, нет `javac`, нет модуля `jdk.compiler` — это не
   полноценный JDK25, компилировать `--release 25` им нельзя.
2. Официальные JDK/PAPI/Gradle-ассеты с `github.com/.../releases/download/...`
   уходят на `release-assets.githubusercontent.com` — TLS-соединение обрывается.
   Аналогично CI-артефакты (`productionresultssa*.blob.core.windows.net`) и
   `gh release download`/`gh run download` — EOF. Готовые JAR/ZIP в песочницу
   получить нельзя.
3. `services.gradle.org` и `downloads.gradle.org` (`gradle-9.7.1-bin.zip`) —
   недоступны (code 000). `JAVA_HOME=<JRE25> ./gradlew --version` падает с
   `javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake`.
   Через `codeload.github.com` доступен только исходный tarball Gradle, не
   дистрибутив.
4. `repo.maven.apache.org`, `repo.purpurmc.org`, `repo.papermc.io`,
   `repo.extendedclip.com`, `jitpack.io`, `repo.luckperms.net` — вне allowlist.
   Без `purpur-api`, Gson, PAPI, VaultAPI, LuckPerms и JUnit-зависимостей
   `./gradlew build` невозможен. `prepare_papi.py` RC14 тоже грузит JAR с
   GitHub Release (заблокировано).
5. `gh workflow run build.yml --ref ...` → HTTP 403
   `Resource not accessible by integration`; запустить сборку через CI по
   `workflow_dispatch` не удалось. Последний успешный Java-build в CI — RC14.

Собирать RC16 не из чего, поэтому `./gradlew test :diamond-shop:test
:tier-forge:test`, полный `build`, `pack_release.py`/`verify_release.py`,
проверка состава ZIP/CRC/SHA256 и выдача архива **не выполнялись**.

## 5. Что НЕ проверялось (явно)

Вся игровая матрица `docs/audits/forge-shop-rc16.md`: восемь товаров и PDC,
Bank-only/сдача, полный инвентарь/двойной клик/reload, отключение TierForge,
WAL-recovery, искра на зачарованном/именованном/повреждённом снаряжении,
стабильность preview, отказы, дроп `QualifiedHuntKillEvent`, полная цена XP,
vanilla-наковальня. Также не проверялись native `CraftItemStack` codec и
серверная часть; модельные ItemStack-тесты их не заменяют.
Smoke-тест на тестовом сервере не проводился. Crash-atomic гарантия
playerdata/файлов плагинов не обещается.

## 6. Что нужно для завершения

1. **Восстановить исходники RC16**: приложить patch/архив рабочей копии прежней
   среды (или возобновить ту среду), затем сверить `git status` и состав файлов.
   Альтернатива — закоммитить их в ветку сессии.
2. **Обеспечить.toolchain**: полноценный JDK25 с `javac`+`jdk.compiler` и доступ
   к `services.gradle.org` + Maven-репозиториям (или выполнить сборку в среде с
   таким доступом и передать сюда готовые JAR).
3. Далее по штатному сценарию: целевые тесты модулей → полный `./gradlew build`
   с параметрами workflow (`gradle_build.py`, `prepare_papi.py`, если они есть в
   восстановленном дереве) → весь Python-набор → `ОБНОВЛЕНИЕ.txt` из
   актуального `docs/releases/update-memo.txt` → `verify_release.py` →
   `pack_release.py` → проверка состава/версий/CRC/размера/SHA256 → выдача ZIP
   через вложение; optional VK отдельно. Сгенерированные ZIP/JAR в Git не
   добавлять.
