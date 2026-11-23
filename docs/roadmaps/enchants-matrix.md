# Инвентаризация 75 действующих и одной выведенной чары

Механически собранная карта реестра и буквальных ссылок в Java; наличие ссылки не доказывает корректность эффекта. Конфигурация, зависимости и серверные события требуют отдельной проверки. `ANY` — только группы из `ItemGroup`, а не все предметы.

| ID | Название | Полюс | Макс. | Группы | Конфликт | Прямые ссылки (не исчерпывающее доказательство) |
|---|---|---|---:|---|---|---|
| `thunderstrike` | Громобой | LIGHT | 3 | SWORD, AXE | lightning | MeleeEffectsListener |
| `lifesteal` | Вампиризм | LIGHT | 3 | SWORD, AXE | — | MeleeEffectsListener |
| `chainspark` | Цепная молния | LIGHT | 2 | SWORD, AXE | lightning | MeleeEffectsListener |
| `bleeding` | Кровоточность | LIGHT | 3 | SWORD, AXE | — | MeleeEffectsListener |
| `paralyze` | Паралич | LIGHT | 2 | SWORD, AXE | — | MeleeEffectsListener |
| `execution` | Казнь | LIGHT | 2 | SWORD, AXE | — | MeleeEffectsListener |
| `berserk` | Берсерк | LIGHT | 3 | SWORD, AXE | — | MeleeEffectsListener |
| `slayer` | Истребитель | LIGHT | 2 | SWORD, AXE | — | MeleeEffectsListener |
| `souleater` | Пожиратель душ | LIGHT | 2 | SWORD, AXE | — | MeleeEffectsListener |
| `stormarrow` | Грозовая стрела | LIGHT | 3 | BOW, CROSSBOW | impact-aoe | BowEffectsListener |
| `explosive` | Взрывная стрела | LIGHT | 2 | BOW, CROSSBOW | impact-aoe | BowEffectsListener |
| `sniper` | Снайперская стрела | LIGHT | 3 | BOW, CROSSBOW | — | BowEffectsListener |
| `frostarrow` | Морозная стрела | LIGHT | 2 | BOW, CROSSBOW | — | BowEffectsListener |
| `tracer` | Следящая стрела | LIGHT | 1 | BOW, CROSSBOW | — | BowEffectsListener |
| `headhunter` | Охотник за головами | LIGHT | 1 | BOW, CROSSBOW | — | BowEffectsListener |
| `retribution` | Возмездие | LIGHT | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | aura | ArmorEffectsListener |
| `laststand` | Последний шанс | LIGHT | 1 | CHESTPLATE, LEGGINGS | savior | ArmorEffectsListener |
| `fortified` | Крепость | LIGHT | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | — | ArmorEffectsListener |
| `lightningrod` | Громоотвод | LIGHT | 1 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | — | ArmorEffectsListener |
| `phoenix` | Феникс | LIGHT | 1 | CHESTPLATE | savior | ArmorEffectsListener |
| `iceward` | Страж льдов | LIGHT | 2 | LEGGINGS | aura | ArmorEffectsListener |
| `truesight` | Орлиный глаз | LIGHT | 1 | HELMET | — | AuraService |
| `seer` | Провидец | LIGHT | 1 | HELMET | — | AuraService |
| `swiftfeet` | Стремительные | LIGHT | 2 | BOOTS | — | AuraService |
| `froggy` | Лягушачьи лапки | LIGHT | 2 | BOOTS | — | AuraService |
| `lavawalker` | Лавоступ (выведена) | LIGHT | 1 | BOOTS | — | Только чтение старых записей; эффекта и новых источников нет |
| `autosmelt` | Автоплавка | LIGHT | 1 | PICKAXE | — | ToolEffectsListener |
| `veinminer` | Жилодробитель | LIGHT | 3 | PICKAXE | megamine | ToolEffectsListener |
| `excavator` | Дробитель | LIGHT | 2 | PICKAXE, SHOVEL, AXE | megamine | ToolEffectsListener |
| `midas` | Рука Мидаса | LIGHT | 1 | PICKAXE | — | ToolEffectsListener |
| `keen_edge` | Острый край | LIGHT | 3 | SWORD, AXE | — | EnchantAttributeMap |
| `frenzy` | Ярость | LIGHT | 3 | SWORD, AXE | — | PolarityEffectsListener |
| `plunder` | Мародёр | LIGHT | 3 | SWORD, AXE | — | PolarityEffectsListener |
| `duelist` | Дуэлянт | LIGHT | 3 | SWORD, AXE | — | PolarityEffectsListener |
| `steady_aim` | Верный прицел | LIGHT | 3 | BOW, CROSSBOW | — | BowEffectsListener |
| `quickdraw` | Скорый выстрел | LIGHT | 3 | BOW, CROSSBOW | — | BowEffectsListener |
| `bulwark` | Оплот | LIGHT | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | ward | EnchantAttributeMap |
| `second_skin` | Вторая кожа | LIGHT | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | ward | PolarityEffectsListener |
| `vitality` | Живучесть | LIGHT | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | — | AuraService |
| `grounded` | Устойчивость | LIGHT | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | — | PolarityEffectsListener |
| `lightfoot` | Лёгкая поступь | LIGHT | 3 | BOOTS | — | PolarityEffectsListener |
| `featherstep` | Пёрышко | LIGHT | 3 | BOOTS | — | EnchantAttributeMap |
| `prospector` | Старатель | LIGHT | 3 | PICKAXE, SHOVEL, HOE, AXE | — | AuraService |
| `fortunate` | Золотая жила | LIGHT | 2 | PICKAXE | — | ToolEffectsListener |
| `scholar` | Книжник | LIGHT | 3 | ANY | — | PolarityEffectsListener |
| `crippling_edge` | Подрезание | LIGHT | 3 | SWORD, AXE | pve-melee-control | PveEnchant |
| `sapping_edge` | Изнуряющий клинок | LIGHT | 2 | SWORD, AXE | pve-melee-control | PveEnchant |
| `pinning_shot` | Сковывающий выстрел | LIGHT | 3 | BOW, CROSSBOW | pve-ranged-control | PveEnchant |
| `sapping_shot` | Изнуряющий выстрел | LIGHT | 2 | BOW, CROSSBOW | pve-ranged-control | PveEnchant |
| `briar_guard` | Терновый заслон | LIGHT | 2 | CHESTPLATE, LEGGINGS | pve-guard-control | PveEnchant |
| `faltering_guard` | Ослабляющий заслон | LIGHT | 2 | CHESTPLATE, LEGGINGS | pve-guard-control | PveEnchant |
| `provoking_strike` | Провоцирующий удар | LIGHT | 2 | SWORD, AXE | pve-melee-control | PveEnchant |
| `signal_guard` | Сигнальный заслон | LIGHT | 2 | CHESTPLATE, LEGGINGS | pve-guard-control | PveEnchant |
| `careful_work` | Бережная работа | LIGHT | 3 | PICKAXE, SHOVEL, HOE, AXE | — | UtilityEnchant |
| `garden_step` | Садовая поступь | LIGHT | 1 | BOOTS | — | UtilityEnchant |
| `air_reserve` | Запас воздуха | LIGHT | 2 | HELMET | — | UtilityEnchant |
| `quiet_step` | Тихий шаг | LIGHT | 1 | BOOTS | — | UtilityEnchant |
| `tool_safeguard` | Предохранитель инструмента | LIGHT | 1 | PICKAXE, SHOVEL, HOE, AXE | — | UtilityEnchant |
| `frost_resistance` | Морозостойкость | LIGHT | 2 | BOOTS | — | UtilityEnchant |
| `pve_anchor` | PvE-якорь | LIGHT | 2 | BOOTS | pve-guard-control | AnchorListener, PveEnchant |
| `harvest_replant` | Бережная пересадка | LIGHT | 1 | HOE | — | HarvestReplantListener, UtilityEnchant |
| `dull_blade` | Тупое лезвие | CURSE | 3 | SWORD, AXE | — | EnchantAttributeMap |
| `clumsy_swing` | Неуклюжий замах | CURSE | 3 | SWORD, AXE | — | EnchantAttributeMap |
| `brittle` | Хрупкость | CURSE | 3 | SWORD, AXE, PICKAXE, SHOVEL, HOE | — | PolarityEffectsListener |
| `weak_draw` | Слабый натяг | CURSE | 3 | BOW, CROSSBOW | — | BowEffectsListener |
| `sluggish_shot` | Вялый выстрел | CURSE | 2 | BOW, CROSSBOW | — | BowEffectsListener |
| `thin_padding` | Тонкая подкладка | CURSE | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | ward | EnchantAttributeMap |
| `exposed` | Открытость | CURSE | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | — | PolarityEffectsListener |
| `heavy_hull` | Тяжёлый корпус | CURSE | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | — | EnchantAttributeMap |
| `fire_prone` | Боязнь огня | CURSE | 3 | HELMET, CHESTPLATE, LEGGINGS, BOOTS | — | PolarityEffectsListener |
| `fragile_bones` | Хрупкие кости | CURSE | 3 | BOOTS | — | PolarityEffectsListener |
| `dull_pick` | Тупой резец | CURSE | 3 | PICKAXE | — | AuraService |
| `jinxed` | Порча | CURSE | 3 | PICKAXE, SHOVEL, HOE, AXE | — | PolarityEffectsListener |
| `gluttony` | Обжорство | CURSE | 3 | ANY | — | AuraService |
| `slow_learner` | Тугодум | CURSE | 3 | ANY | — | PolarityEffectsListener |
| `cursed_blood` | Проклятая кровь | CURSE | 3 | ANY | — | AuraService |
