# Совместимое слияние атрибутов предметов (Mine Treasure + CHRDK)

## Симптом и гипотеза

Mine Treasure выдаёт кастомные предметы («Алмазный топор», «Меч урагана») с
собственной базой урона и скорости атаки. `CHRDK_Enchants` и
`CHRDK_TierForge` добавляют к ним модификаторы атрибутов, в том числе скорости
атаки. После наложения чара/тира скорость атаки становится `0` вместо `1.0`
или атрибут исчезает. Рабочая гипотеза — конфликт `NamespacedKey` или `UUID`
модификаторов.

Проверка по Paper API (ветка `main`, **не** точный снимок Purpur
`26.3.build.2647-experimental`) подтверждает: первичный идентификатор — ключ,
а не отдельный UUID:

- `AttributeModifier` хранит `NamespacedKey`, `amount`, `Operation`,
  `EquipmentSlotGroup`; `getUniqueId()` помечён `@Deprecated(since = "1.21",
  forRemoval = true)` и вычисляется из ключа: `UUID.fromString(...)` для
  UUID-подобного `minecraft:`-ключа, иначе
  `UUID.nameUUIDFromBytes(namespacedKey.toString())`. `equals/hashCode` считаются
  по `key/amount/operation/slot`. См.
  <https://raw.githubusercontent.com/PaperMC/Paper/main/paper-api/src/main/java/org/bukkit/attribute/AttributeModifier.java>.
- `CraftMetaItem#addAttributeModifier` бросает `IllegalArgumentException`,
  когда в том же `Attribute` уже есть модификатор с таким `getKey()`;
  `removeAttributeModifier(Attribute, AttributeModifier)` тоже сопоставляет
  ключ. См.
  <https://raw.githubusercontent.com/PaperMC/Paper/main/paper-server/src/main/java/org/bukkit/craftbukkit/inventory/CraftMetaItem.java>.
- `ItemMeta#getAttributeModifiers()` возвращает immutable `Multimap` или `null`;
  `setAttributeModifiers(null)` снимает компонент, а **пустая карта
  перекрывает ванильные defaults**. `CraftMetaItem#hasAttributeModifiers()`
  и `getAttributeModifiers()` одинаково возвращают `false`/`null` и для
  отсутствующего, и для пустого компонента. См.
  <https://raw.githubusercontent.com/PaperMC/Paper/main/paper-api/src/main/java/org/bukkit/inventory/meta/ItemMeta.java> и
  <https://raw.githubusercontent.com/PaperMC/Paper/main/paper-server/src/main/java/org/bukkit/craftbukkit/inventory/CraftMetaItem.java>.
- `NamespacedKey(Plugin, key)` приводит ключ к нижнему регистру и использует
  `plugin.namespace()`; `NamespacedKey(String, String)` требует уже валидный
  lowercase. См.
  <https://raw.githubusercontent.com/PaperMC/Paper/main/paper-api/src/main/java/org/bukkit/NamespacedKey.java>.
- `Plugin` реализует `Namespaced`; `Attribute` — registry-backed `Keyed`, поэтому
  стабильнее сравнивать `attribute.getKey()` и `modifier.getKey()`, а не имя
  enum/значение `Attribute#toString()`. См.
  <https://raw.githubusercontent.com/PaperMC/Paper/main/paper-api/src/main/java/org/bukkit/plugin/Plugin.java> и
  <https://raw.githubusercontent.com/PaperMC/Paper/main/paper-api/src/main/java/org/bukkit/Registry.java>.

**Вывод:** отдельного «UUID-конфликта» в 1.21+ нет. Конфликт идёт по паре
`(Attribute, NamespacedKey)`. `1.0 → 0` воспроизводится, когда плагин
перезаписывает базовый модификатор под тем же ключом, а исчезновение
атрибута — когда `setAttributeModifiers(null)`/пустая карта/пересборка мета
теряет компонент. Дополнительная ловушка: `ItemAttributes.keep()` возвращает
ванильные defaults по отсутствующему ключу и может добавить ванильную
скорость атаки **поверх** кастомной базы Mine Treasure, давая двойную базу.

## Что реализовано

### `ru.cherdakmd.chrdkregister.item.ItemAttributeMerge`

Утилита работает с полной `Multimap<Attribute, AttributeModifier>`, а не с
отдельными «своими» ключами:

1. `capture()` снимает явный компонент источника и аккуратно добавляет ванильные
   defaults. Default **не добавляется** для атрибута, который источник явно
   переопределил не-нашим, не-ванильным базовым ключом (Mine Treasure). Если
   ванильный ключ того же атрибута уже присутствует, рядом стоящий чужой
   модификатор считается аддитивным бонусом, а не заменой базы.
2. Стабильный идентификатор — `stableId(attribute, modifier)` =
   `attribute.getKey() + "|" + modifier.getKey()`. `amount`, `Operation` и
   `EquipmentSlotGroup` входят в полный `signature`, чтобы заметить подмену
   значения под тем же ключом (`1.0 → 0`). UUID не используется.
3. `merge()` собирает результат в три прохода:
   - foreign-база источника (Mine Treasure/другие плагины) — первой, с
     исходными ключами;
   - текущие модификаторы результата (чары, тиры, чужие плагины) — с удалением
     только ванильного default, который источник переопределил;
   - owned-модификаторы источника и ванильные defaults — только при отсутствии
     идентификатора. Явный ванильный default источника восстанавливает исходное
     значение, если пересборка записала под этим ключом другое число.
4. При реальном конфликте `(атрибут, ключ)` с разными значениями более поздний
   или owned-модификатор получает детерминированный
   `uniqueKey()` = `namespace:originalKey_compat_<sha256-12>`. Namespace и
   префикс исходного ключа сохраняются, поэтому штатная очистка чар/тиров по
   namespace/префиксу по-прежнему находит свой модификатор. Данные не
   склеиваются и не удаляются.
5. Пустой итог и случай «нужны только ванильные defaults» проходят через
   `clearEmptyComponent()`: утилита сравнивает `getAsComponentString()` до и
   после защитного `setAttributeModifiers(null)`. Так снимается скрытый пустой компонент,
   который API не отличает от `null`, но при отсутствии компонента лишняя запись
   в `ItemStack` не производится. Пустая карта никогда не записывается.

### `ru.cherdakmd.chrdkregister.item.ItemAttributeMergeListener`

`Listener` на `PrepareAnvilEvent`, `PrepareItemCraftEvent`,
`PrepareSmithingEvent`:

- `LOWEST`: снимает `Snapshot` у консервативного набора источников — левый слот
  наковальни, `inputEquipment` кузнечного стола, ингредиенты крафта. Книги,
  катализаторы и расходники в результат не копируются.
- `HIGHEST`: после всех HIGH-обработчиков чар/тиров объединяет снимки в
  `event.getResult()` / `CraftingInventory#getResult()`. Отбор источников — тот
  же тип предмета или апгрейд `DIAMOND_* → NETHERITE_*`.
- `MONITOR`: удаляет снимок из `WeakHashMap`; дополнительная очистка на
  `InventoryClickEvent`/`InventoryCloseEvent`.

Снимки живут только внутри prepare-события. PDC, конфиги, команды, чужие
плагины и `plugin.yml` не меняются; Mine Treasure не нужен как compile-зависимость.

### Порядок регистрации и совместимость с ценой наковальни

`RegisterPlugin` регистрирует `ItemAttributeMergeListener` **до**
`AnvilCostListener`. Оба имеют обработчики `HIGHEST`; при равном приоритете
Bukkit вызывает их в порядке регистрации. Поэтому слияние проходит до
`AnvilCosts.remember(view, result)` и котировка ядра запоминает уже
объединённый предмет.

`AnvilApplyListener` (`CHRDK_Enchants`) хранит строгий preview-quote на HIGH.
Его новый MONITOR-обработчик `refreshResult()` заменяет в quote только поле
`result` на `event.getResult().clone()`, сохраняя цену, исходные слоты,
generation и флаги. Без этого проверка `quote.output(...)` при клике отменила бы
выдачу предмета с восстановленной базой. Старые правила доплаты, проклятий и лимитов не меняются.

## Границы и риски

- Исходников Mine Treasure в репозитории нет; его внутренний формат компонента
  не проверен на живом сервере. Алгоритм опирается на публичный Bukkit/Paper
  `attribute_modifiers`, а не на приватный NBT плагина.
- Paper `main` — не точная версия Purpur `26.3.build.2647-experimental`.
  Локально нет `javac`, Gradle, Maven, Purpur API jar и Minecraft; Java-код
  проверен статически (tree-sitter, исходные контракты), но **не скомпилирован
  и не запущен на сервере**.
- Детерминированный `_compat_`-ключ — крайняя мера для уже существующего
  конфликта. Сторонний плагин, который удаляет модификатор строго по своему
  исходному ключу, может не найти переименованную копию; поэтому foreign-база
  источника обрабатывается первой и сохраняет исходный ключ, а переименованию
  подлежит более поздний/owned-модификатор.
- Слушатель чинит результаты наковальни/крафта/кузнечного стола. Уже выданные
  сломанные предметы в инвентаре лечатся существующими ленивыми путями
  (`AttributeRepairListener`, `TierRefreshListener`) только для ванильной базы;
  полная миграция старых Mine Treasure-стеков без исходного снимка требует
  отдельного серверного аудита.

## Офлайн-проверки

- `ItemAttributeMergeTest`: UUID производен от ключа; `stableId` различает
  атрибуты; merge сохраняет ванильную базу, Mine Treasure, чары, тиры и чужой
  плагин; конфликтующие ключи переименовываются без потери amount; owned-источник
  не перекрывает свежий результат; явная база `1.0` восстанавливается вместо `0`;
  пустой компонент снимается; `uniqueKey` детерминирован и валиден.
- `ItemAttributeMergeListenerTest`: LOWEST/HIGHEST/MONITOR, три prepare-события,
  регистрация до `AnvilCostListener`, `refreshResult()` в аддоне чар, отсутствие
  UUID-геттера и пустой карты в утилите.
- `tools/wiki/freshness.py`: после правки `src/main` core обновлены хэши
  `wiki/reviewed.json` для всех страниц с `review: [core, …]`; тексты страниц,
  команды и публичные цифры не менялись.
