# Mine Treasure v1.6.5 RU — патченый датапак CHRDK

Это **не Incendium** и не новый перевод: присланный
`/tmp/arena-coding-uploads/mine-treasure-1.6.5-ru.txt` — уже русский ZIP
Mine Treasure v1.6.5. Повторный перевод не делался. По 12 ответам владельца
(q1…q12) в репозиторий теперь **закоммичен исправленный ZIP**, а следующий общий
пререлиз плагинов подготовлен, но **тег/Release сейчас не создаются**.

## Решения владельца

| Вопрос | Выбор | Что это значит здесь |
| --- | --- | --- |
| q1 лицензия | `owner_risk` | ZIP публикуется по решению владельца; **разрешение Frozytime не проверено и не выдаётся за полученное**. Риск принимает владелец. |
| q2 артефакт | `patched_zip` | В Git идёт не побайтовый оригинал, а патченый ZIP с новым SHA-256. |
| q3 цель релиза | `plugin_rc` | Новый **общий** RC после `1.5.2-rc.6` (`1.5.2-rc.10`), не rc.9, не стабильный `1.5.2`, не смена `Latest`. Тег/Release — позже, отдельной командой. |
| q4 ассеты | `zip_only` | Как ассет датапака — только готовый ZIP; SHA-256 пишется в notes. Installer, docs и отдельный JAR датапака не являются release-ассетами. Обычные ассеты комплекта плагинов остаются. |
| q5 включение | `keep_reflection` | `MineTreasureIntegration` с Paper `DatapackManager` через рефлексию и fallback `/datapack enable "file/<name>"` не менялся; bootstrap/`DATAPACK_DISCOVERY` не добавлялся. |
| q6 откат | `migration` | План учитывает уже выданные предметы, PDC и scoreboard `mt.*`; in-place миграции нет. |
| q7 сервер | `none` | Живого Purpur/RCON/тестового сервера у агента нет. |
| q8 версия | `keep_121_1` | `min_format`/`max_format` остаются `[121, 1]`; править только если реальный `/version` покажет иное. |
| q9 охват | `all` | Матрица по всем 24 активным модулям + Core: [MINE-TREASURE-COMPATIBILITY.md](MINE-TREASURE-COMPATIBILITY.md). |
| q10 конфликт лута | `merge` | CHRDK-лут/клады/артефакты и MT-лут работают вместе без двойного дропа; 43 таблицы блоков не оставлены «как есть». |
| q11 кодеки | `fix_all` | Исправлены все 29 `water_movement_efficiency` и все 5 массивов `slot`; ZIP пересобран. |
| q12 приёмка | `balanced` | CI/static + живой `/datapack list`, добыча, наковальня/чары/тир, чистый лог. Живую часть выполняет владелец. |

Старое решение «ZIP не коммитить из-за лицензии» **отменено** q1+q2. При этом
`LICENSE.md` внутри пака (© 2026 Frozytime) сохранён без правок: он разрешает
использование на публичном сервере с упоминанием автора и ссылкой, но прямо
запрещает выкладывать пак на другие сайты/в модпаки без явного разрешения.
Перед боевым запуском владельцу нужно выполнить требование об упоминании
Frozytime и ссылке в описании сервера. Оригинальные страницы:
[Planet Minecraft](https://www.planetminecraft.com/data-pack/mine-treasure/),
[Modrinth](https://modrinth.com/datapack/mine-treasure),
[mine-treasure.com](https://mine-treasure.com).

## Что лежит в репозитории

```text
datapacks/mine-treasure-ru/mine-treasure-1.6.5-ru.zip
tools/datapacks/build_mine_treasure.py
tools/datapacks/mine_treasure_manifest.json
tools/datapacks/install_mine_treasure.py
docs/datapacks/MINE-TREASURE-RU.md
docs/datapacks/MINE-TREASURE-COMPATIBILITY.md
```

| | Исходный `.txt` | Патченый ZIP |
| --- | --- | --- |
| Размер | `1 747 330` байт | `1 758 991` байт |
| SHA-256 | `0ff4bd2ad1a5db22920db23f1f2c88d577ceff607164638d0e1a45264075bf92` | `d51346e81b32fda61e7d4ba7a9475289815857451f4bea8a69d45f61527f3ec6` |
| Записей | 3033 | 3034 (`+CHRDK-COMPAT.md`) |

Контрольные данные патченого ZIP зафиксированы в
`tools/datapacks/mine_treasure_manifest.json` и проверяются в CI:
`python3 tools/datapacks/build_mine_treasure.py --check`. Сборка детерминирована:
повторный запуск даёт тот же SHA-256. Исходный `.txt` в репозиторий не копируется.

### Что именно исправлено

1. 29 полей `"attribute": "water_movement_efficiency"` →
   `"minecraft:water_movement_efficiency"`.
2. 5 полей `"slot": ["mainhand"|"offhand"]` → строку.
3. В каждый из 1507 блоков `minecraft:set_components` добавлена PDC-метка
   `minecraft:custom_data.PublicBukkitValues."chrdk:mine_treasure_item" = "1"`.
   По ней `MineTreasureLootPolicy` отличает награды MT от ванильного дропа.
   Макросы MT читают именованные ключи `custom_data`, лишний compound им не
   мешает; уже выданные предметы без метки не переписываются.
4. Добавлен корневой `CHRDK-COMPAT.md` с описанием патча и границ.

`pack.mcmeta` (`[121, 1]`), `LICENSE.md`, `pack.png`, `mt:init`, `mt:tick`,
scoreboard-цели, `forceload add 0 0 0 0`, `#minecraft:load`/`tick`, 825 JSON,
1132 `set_attributes` и 43 `data/minecraft/loot_table/blocks/*` сохранены.
В каждой из 43 таблиц есть и ванильный дроп, и вложенный `mt:` пул — это база
для слияния q10, а не «оставить как есть».

## Как достигается слияние без двойного дропа

Paper 26.3 вызывает `LootGenerateEvent` из `LootTable#fill(Container...)`;
дроп блоков идёт через `LootTable#getRandomItems(...)` и `BlockDropItemEvent`.
Поэтому:

- `CHRDK_Skills#doubleDrop` больше не разыгрывает `block.getDrops()` целиком:
  `MineTreasureLootPolicy.vanillaOnly(...)` оставляет только простые ванильные
  стеки. MT-награды с PDC/ItemMeta не дублируются.
- `CHRDK_Enchants` (`ToolEffectsListener`) и `CHRDK_TierForge`
  (`ForgeUpgradeListener`) проверяют `MineTreasureLootPolicy.isForeignDrop(...)`
  перед автоплавкой/бонусами/удвоением дропа.
- `CHRDK_TierForge#TierWorldListener` не ставит свой тир на `mt:` таблицы:
  иначе переписались бы имя, лор и атрибуты сбалансированной MT-награды.
- `CHRDK_Artifacts` по-прежнему добавляет артефакт в `mt:chests/...` благодаря
  совпадению короткого имени таблицы в `ArtifactsConfig` — это слияние, а не замена.
- `ItemAttributeMerge` из `cd09e64` сохраняет чужие модификаторы при
  наковальне/крафте/кузнечном столе; слой не ломался.
- «Порча» (`jinxed`) осознанно очищает и ванильный, и MT-дроп, но не создаёт
  копий и не перевызывает таблицу.

Полная матрица по Core и 24 аддонам, миграция/откат и smoke-чеклист — в
[MINE-TREASURE-COMPATIBILITY.md](MINE-TREASURE-COMPATIBILITY.md).

## Установка

1. **Остановите сервер и сделайте backup мира**, `playerdata`, PDC-зависимых
   данных плагинов и scoreboard. Скрипт пишет в `<мир>/datapacks`, но не
   проверяет, что сервер выключен.
2. Проверьте ZIP без копирования (по умолчанию берётся файл из репозитория):

   ```bash
   python3 tools/datapacks/install_mine_treasure.py --check
   python3 tools/datapacks/build_mine_treasure.py --check
   ```

3. Установите в мир. `--world` принимает папку с `level.dat` либо корень сервера;
   `level-name` читается из `server.properties`.

   ```bash
   python3 tools/datapacks/install_mine_treasure.py --world /path/to/server
   ```

   Повторный запуск идемпотентен. Если в `datapacks` уже лежит другой файл с
   этим именем, нужен `--force` после backup. Для другой сборки передайте её
   `--sha256` (или `--sha256 ''`, чтобы пропустить сравнение).
4. Запустите сервер с обновлённым `CHRDK_Core`. Ожидаемый id:

   ```text
   file/mine-treasure-1.6.5-ru.zip
   ```

   Если Paper API недоступен, включите вручную:

   ```text
   /datapack enable "file/mine-treasure-1.6.5-ru.zip"
   /minecraft:reload
   ```

## Конфигурация `CHRDK_Core/config.yml`

Старый `config.yml` без секции работает со значениями по умолчанию; bump схемы
не требуется.

```yaml
integrations:
  mine-treasure:
    auto-enable: true
    refresh-on-start: false
    file-name: "mine-treasure-1.6.5-ru.zip"
    pack-id: ""
```

`auto-enable: false` отключает только автовключение; `refresh-on-start: true`
дёргает `DatapackManager#refreshPacks()` при отсутствии пака. `pack-id` нужен
лишь для паков, зарегистрированных другим Paper-плагином. `setEnabled(true)`
сам перезагружает ресурсы; отдельного `Server#reloadData()` из CHRDK нет.

## Релиз (подготовлено, не опубликовано)

- `plugin_version` подготовлен как `1.5.2-rc.10`; заметки
  `docs/releases/1.5.2-rc.10.md`, краткий анонс
  `docs/social/prereleases/1.5.2-rc.10.json` и памятка
  `docs/releases/update-memo.txt` содержат SHA-256 ZIP.
- `.github/workflows/release.yml` проверяет патченый ZIP и отдельным шагом
  прикладывает `datapacks/mine-treasure-ru/mine-treasure-1.6.5-ru.zip` к общему RC.
- **Тег `v1.5.2-rc.10`, GitHub Release, `Latest`, Pages и PR не создавались и не
  двигались.** `vk-v1.5.2-rc.9`, стабильный `v1.5.2`, rc.6 и прежние ассеты не
  переписывались. Публикация RC — отдельная явная команда владельца.
- Installer, docs и JAR не прикладываются как ассеты датапака; готовый ZIP и
  SHA-256 в notes — весь scope q4.

## Границы и статус проверки

- `q7=none`: живой Purpur, RCON, Java/javac, Gradle, Purpur API jar и
  Minecraft-клиент в песочнице отсутствуют. Paper `main` использовался только
  как справочник API и **не равен** Purpur `26.3.build.2647-experimental`.
- Новые/правленые Java-классы не скомпилированы и JUnit/Gradle не запускались;
  выполнены Python-тесты, статический разбор Java, wiki/freshness и
  `git diff --check`. Это **не «проверено на сервере»** и не заявление
  «полностью совместимо» до живого smoke.
- `q12=balanced`: владелец выполняет `/datapack list enabled`, `/version`,
  добычу камня/руд, открытие MT-сундука, наковальню/чары/тир и читает лог.
  Чеклист — в [матрице совместимости](MINE-TREASURE-COMPATIBILITY.md).
- `q8`: диапазон `[121, 1]` оставлен целевым для 26.3. Если `/version` живого
  Purpur покажет другой формат, меняется `pack.mcmeta` и пересобирается ZIP с
  новым хэшем — не «тихое» редактирование уже опубликованного ассета.
- `q6`: уже выданные предметы, PDC и `mt.*` scoreboard не мигрируются;
  откат и обновление описаны в матрице.
