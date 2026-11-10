# Установка `v1.5.2-rc.10` на изолированный staging

**Статус:** инструкция для владельца. Агент **не выполнял** установку, не подключался к
RCON и не менял живой/production-сервер.

**Артефакт:** [GitHub prerelease `v1.5.2-rc.10`](https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/tag/v1.5.2-rc.10),
тег указывает на `e81640a721211e0cb0d06400d45ec535b6e0a950`. `Latest` остаётся `v1.5.2`;
это **общий RC**, а не VK-only и не datapack-only выпуск.

> **Граница.** Ставить только на отдельную staging-копию после полного backup.
> Установка на production, TradeMC, Pages, PR/merge в `main`, стабильная новость ВК,
> bump `API_VERSION`, NBT-правки уже выданных предметов и замеры MSPT текущим
> решением не разрешены. Production-установка требует отдельной явной команды
> владельца.

Полные заметки: [1.5.2-rc.10.md](releases/1.5.2-rc.10.md) ·
памятка из ZIP: [update-memo.txt](releases/update-memo.txt) ·
схема `players/*.yml`: [PLAYER-DATA-FILES.md](design/PLAYER-DATA-FILES.md) ·
датапак: [MINE-TREASURE-RU.md](datapacks/MINE-TREASURE-RU.md) ·
матрица/откат/smoke: [MINE-TREASURE-COMPATIBILITY.md](datapacks/MINE-TREASURE-COMPATIBILITY.md).

---

## 0. Важно про автообновлятор

**Не используйте `pass update download` / `pass update check` для этого RC.**

`PluginUpdateService` читает `/releases/latest`, отклоняет `prerelease` и работает
со стабильным `v1.5.2`. Для rc.10 обновление выполняется **вручную** из четырёх
ассетов Release. Фоновый автоапдейтер тоже не должен пытаться «понизить» staging
до `v1.5.2`.

---

## 1. Что скачать

Прямые ссылки и контрольные суммы из GitHub Releases API:

| Ассет | Размер, B | SHA-256 |
| --- | ---: | --- |
| [`chrdk-plugins-1.5.2-rc.10.zip`](https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/download/v1.5.2-rc.10/chrdk-plugins-1.5.2-rc.10.zip) | 4 904 293 | `6235b1619a7e2db5dc193248f95b23733ede7ba60f11bd22afda75b423ce10e9` |
| [`chrdk-vk-1.5.2-rc.10.jar`](https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/download/v1.5.2-rc.10/chrdk-vk-1.5.2-rc.10.jar) | 1 355 053 | `8f01475019abc407b2fce21788a0a2f8b2da88c6237b1581d27022f8d851ee6c` |
| [`chrdk-disasters-1.5.2-rc.10.jar`](https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/download/v1.5.2-rc.10/chrdk-disasters-1.5.2-rc.10.jar) | 81 754 | `e82fd832826a12e3771a58f81da1b546642c0669c57f6c165fedb02f67459821` |
| [`mine-treasure-1.6.5-ru.zip`](https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/download/v1.5.2-rc.10/mine-treasure-1.6.5-ru.zip) | 1 758 991 | `d51346e81b32fda61e7d4ba7a9475289815857451f4bea8a69d45f61527f3ec6` |

Внутри общего ZIP — **22 JAR одной версии + `ОБНОВЛЕНИЕ.txt`**. `CHRDK_VK` и
`CHRDK_Disasters` в этот ZIP **не входят**: они прикладываются отдельными JAR.
`ОБНОВЛЕНИЕ.txt` нужно прочитать, но **не копировать в `plugins/`**.

Пример загрузки и проверки на Linux:

```bash
SERVER=/path/to/staging-server
DOWNLOADS="$SERVER/../rc10-assets"
mkdir -p "$DOWNLOADS" && cd "$DOWNLOADS"

base=https://github.com/cherdakmd/CHRDK_F_REGISTER/releases/download/v1.5.2-rc.10
for f in \
  chrdk-plugins-1.5.2-rc.10.zip \
  chrdk-vk-1.5.2-rc.10.jar \
  chrdk-disasters-1.5.2-rc.10.jar \
  mine-treasure-1.6.5-ru.zip
do
  curl -fLO "$base/$f"
done

cat > SHA256SUMS <<'EOF'
6235b1619a7e2db5dc193248f95b23733ede7ba60f11bd22afda75b423ce10e9  chrdk-plugins-1.5.2-rc.10.zip
8f01475019abc407b2fce21788a0a2f8b2da88c6237b1581d27022f8d851ee6c  chrdk-vk-1.5.2-rc.10.jar
e82fd832826a12e3771a58f81da1b546642c0669c57f6c165fedb02f67459821  chrdk-disasters-1.5.2-rc.10.jar
d51346e81b32fda61e7d4ba7a9475289815857451f4bea8a69d45f61527f3ec6  mine-treasure-1.6.5-ru.zip
EOF
sha256sum -c SHA256SUMS
unzip -t chrdk-plugins-1.5.2-rc.10.zip
unzip -l chrdk-plugins-1.5.2-rc.10.zip
```

Если `sha256sum -c` не прошёл — **не устанавливайте**, скачайте ассеты заново.

---

## 2. Полный backup до остановки/замены

1. Закрыть доступ игрокам, завершить активные выплаты/операции магазина; при
   неоднозначных `WAL`/pending не обновляться до ручной сверки журналов.
2. Штатно остановить сервер. **Не использовать `/reload`** для замены JAR:
   миграции и `ConfigUpgrader` выполняются в `onEnable()` при полном рестарте.
3. Снять согласованную копию **всего** staging-сервера:
   - `plugins/CHRDK_*` целиком, включая `config.yml`, `messages.yml`,
     `players/`, `*.json`, `*.yml`, `update-backups/`, ledger и журналы;
   - `plugins/` целиком и настроенный `update-folder` / `plugins/update/`;
   - миры, `playerdata`, `scoreboard.dat`, `<мир>/datapacks/`;
   - `server.properties`, `bukkit.yml`, `spigot.yml`, `paper-*.yml`, `logs/latest.log`.

Backup обязателен **до первого запуска rc.10**: старые JAR могут не прочитать
новые `players/*.yml` и обновлённые глобальные JSON.

---

## 3. Чистка старых и снятых JAR

- Убедитесь, что `plugins/update/` (или нестандартный `update-folder`) **пуст**:
   stale-JAR от предыдущего автообновления может примениться Bukkit при старте.
- Удалите дубли старых версий: не должно быть двух JAR с одним `plugin.yml → name`.
- Вручную уберите снятые аддоны и их переименованные копии из `plugins/` и
  `plugins/update/`: `CHRDK_Queue`, `CHRDK_Treasures`, `CHRDK_Bosses`,
  `CHRDK_Streams`, `CHRDK_Events`, `CHRDK_Lottery`, `CHRDK_Party`,
  `CHRDK_Referrals`. Папки данных снятых модулей **архивируйте**, не удаляйте.
- Не удаляйте `plugins/CHRDK_Core/`, `plugins/CHRDK_VK/` и прочие действующие
  папки данных: конфиги и данные игроков должны остаться на месте.

---

## 4. Установка JAR плагинов

```bash
cd "$DOWNLOADS"
mkdir rc10-jars
unzip -q chrdk-plugins-1.5.2-rc.10.zip -d rc10-jars
find rc10-jars -maxdepth 1 -name '*.jar' | wc -l   # должно быть 22
```

Скопируйте **весь комплект одной версии**, включая ядро `CHRDK_Core`:

```bash
cp rc10-jars/*.jar "$SERVER/plugins/"
cp chrdk-vk-1.5.2-rc.10.jar "$SERVER/plugins/"
cp chrdk-disasters-1.5.2-rc.10.jar "$SERVER/plugins/"
```

Если `CHRDK_VK` / `CHRDK_Disasters` уже установлены — замените их старые JAR.
Если они не установлены, ставьте отдельные JAR только если они нужны на этом
staging; отсутствующие аддоны автоматически не добавляются. Не смешивайте JAR
`1.5.2`, `1.5.2-rc.6`, `vk-v1.5.2-rc.9` и `1.5.2-rc.10`.

Проверка версии внутри JAR, а не по имени файла:

```bash
unzip -p "$SERVER/plugins/chrdk-core-1.5.2-rc.10.jar" plugin.yml | grep '^version:'
unzip -p "$SERVER/plugins/chrdk-vk-1.5.2-rc.10.jar" plugin.yml | grep '^version:'
unzip -p "$SERVER/plugins/chrdk-disasters-1.5.2-rc.10.jar" plugin.yml | grep '^version:'
```

Ожидается `version: 1.5.2-rc.10`. `API_VERSION` остаётся `42`, новых модулей нет.

---

## 5. Установка patched Mine Treasure datapack

Пак кладётся **ZIP-файлом как есть** в `<мир>/datapacks/`, где `<мир>` —
`level-name` из `server.properties`. Имя файла менять нельзя: его ожидает
`integrations.mine-treasure.file-name` и id `file/mine-treasure-1.6.5-ru.zip`.

### Вариант A — штатным installer из тега

Installer **не входит в ассеты Release**; он есть в исходниках тега
`v1.5.2-rc.10`. Из корня checkout этого тега:

```bash
python3 tools/datapacks/build_mine_treasure.py --check
python3 tools/datapacks/install_mine_treasure.py --check
python3 tools/datapacks/install_mine_treasure.py --world "$SERVER"
```

`--world` принимает корень сервера или папку мира с `level.dat`; `level-name`
читается из `server.properties`. Повторный запуск идемпотентен. Если в
`datapacks` уже лежит другой файл с этим именем, `--force` — только после backup.

### Вариант B — вручную

```bash
WORLD="$SERVER/$(sed -n 's/^level-name=//p' "$SERVER/server.properties")"
mkdir -p "$WORLD/datapacks"
cp "$DOWNLOADS/mine-treasure-1.6.5-ru.zip" "$WORLD/datapacks/"
sha256sum "$WORLD/datapacks/mine-treasure-1.6.5-ru.zip"
```

Не распаковывайте ZIP и не подменяйте `pack.mcmeta`: диапазон формата оставлен
`[121, 1]`. Если живой `/version` покажет другой формат, требуется пересборка ZIP
с новым SHA-256, а не тихое редактирование уже опубликованного ассета.

---

## 6. Конфиги и миграция данных

**Не пересоздавайте `config.yml` / `messages.yml`.** При первом `onEnable()`:

- `ConfigUpgrader` аддитивно допишет недостающие ключи, сохранит владельческие
  значения и комментарии, создаст `config-before-schema-…yml` и проставит маркер;
  повторный старт ничего не меняет;
- старые `%chrdksocial_status%`, `%chrdksocial_link%`, `%chrdksocial_access%`
  во владельческом `tablist.footer` сливаются рантаймом в одну строку
  `%chrdksocial_tab%` **без перезаписи `config.yml`**; для полностью внешнего
  TAB добавьте эту строку вручную;
- `plugins/CHRDK_VK/players/social.yml`,
  `plugins/CHRDK_Core/players/warns.yml` и `players/bans.yml` создаются
  автоматически из старых checksummed JSON с backup
  `*.before-players-move-*.bak` / `*.before-players-split-*.bak`;
- нечитаемые YAML уходят в `*.yml.broken` и не затираются до ручной правки;
- `plugins/CHRDK_VK/social-links.json` и
  `plugins/CHRDK_Core/moderation.json` остаются с SHA-256;
- в `plugins/CHRDK_VK/config.yml` по умолчанию
  `fail-open-on-corruption: true` — штатный аварийный режим, а не разрешение
  удалять битые файлы.

**Ручную правку `players/*.yml` делайте только при остановленном сервере.**
Пароли, 2FA, TOTP-секреты и токены ботов в эти файлы не пишутся. Правила — в
`players/README.md` (создаётся плагином) и
[PLAYER-DATA-FILES.md](design/PLAYER-DATA-FILES.md).

`plugins/CHRDK_Core/config.yml` без секции `integrations.mine-treasure` работает
на дефолтах; bump схемы конфигов не требуется:

```yaml
integrations:
  mine-treasure:
    auto-enable: true
    refresh-on-start: false
    file-name: "mine-treasure-1.6.5-ru.zip"
    pack-id: ""
```

Если у владельца выставлено `auto-enable: false`, включите пак вручную после
старта (см. п. 7) или измените ключ на staging-копии до запуска.

---

## 7. Первый запуск и проверки

1. Запустите сервер **полным рестартом**. В логе не должно быть `SEVERE` /
   stacktrace от CHRDK и загрузчика датапаков; проверьте сообщения
   `[config-schema]`, backup'ы миграции `players/*.yml` и отсутствие
   `*.yml.broken`.
2. `/version` — Purpur `26.3` / Java 25; `API_VERSION=42`.
3. `/datapack list enabled` — присутствует `file/mine-treasure-1.6.5-ru.zip`.
   Если Paper API недоступен, из консоли:

   ```text
   /datapack enable "file/mine-treasure-1.6.5-ru.zip"
   /minecraft:reload
   ```

4. `version CHRDK_Core`, `version CHRDK_VK`, `version CHRDK_Disasters` и
   `/plugins` — все установленные плагины семейства версии `1.5.2-rc.10`.
5. `scoreboard objectives list` — цели `mt.*`; `mt:init` / `mt:tick` без ошибок.
6. TAB и `/link`: одна строка привязки, без дубля срока; после привязки —
   `✅ ВКонтакте привязан — можно играть` без дедлайна. Без PAPI/CHRDK_VK
   строка скрывается целиком.
7. Базовые команды: `/menu`, `/wiki`, `/profile`, `/bal`, `/enchants sources`,
   `/shop`, `/pass health` — без ошибок и без потери владельческих конфигов.
8. Живой smoke по Matrix: добыча камня/руд с `DOUBLE_DROP`, «Мидасом»,
   «Золотой жилой», «Карьером» и без них; `mt:chests/...`; наковальня/чары/тир
   с MT-предметом; отсутствие двойного лута и перетирания чужих атрибутов;
   чистый лог. Полный чеклист — в
   [MINE-TREASURE-COMPATIBILITY.md](datapacks/MINE-TREASURE-COMPATIBILITY.md).

---

## 8. Откат

**Надёжный откат — полное восстановление из backup п. 2 согласованным
комплектом:** JAR, конфиги, `players/`, глобальные JSON, миры, `playerdata`,
`scoreboard.dat` и `datapacks/` одной версии. Не смешивайте старые JAR с новыми
`players/*.yml` и `playerDataSchema: 1`.

Только датапак:

1. `/datapack disable "file/mine-treasure-1.6.5-ru.zip"` либо остановить сервер,
   убрать ZIP из `<мир>/datapacks`, запустить.
2. Не удаляйте `mt.*` вручную: это состояние датапака; при повторном включении
   `mt:init` создаст цели снова.
3. Уже розданные MT-предметы остаются у игроков; CHRDK не снимает чужой
   `custom_data` и не переписывает NBT/PDC задним числом.

Только данные игроков: восстановите `plugins/CHRDK_VK/` и
`plugins/CHRDK_Core/` **вместе** из одного backup, сделанного до первого старта
rc.10. Файлы `*.bak` и `*.yml.broken` не удаляйте до сверки.

---

## 9. Границы и невыполненное агентом

- Инструкция не является разрешением на production/RCON/live-установку; их
  выполняет владелец после отдельного решения и полного backup.
- `Latest`, `main`, Pages, PR, TradeMC, стабильная новость ВК и исторические
  ассеты/rc.9 не менялись.
- Разрешение Frozytime на patched Mine Treasure **не проверено**; риск принимает
  владелец. Перед боевым запуском выполните требование `LICENSE.md` об
  упоминании автора и ссылке на оригинал.
- Живой Minecraft-клиент, GUI, выдача предметов, `/bal`, RCON, игровой smoke,
  NBT-правки выданных предметов и MSPT агентом не проверялись.
- CI `Build 42695765058` и `Release 42696954642` прошли; JUnit/Gradle выполнялись
  в GitHub Actions, а не в песочнице агента.
