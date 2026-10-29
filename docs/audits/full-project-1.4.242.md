# Анализ CHRDK_F_REGISTER 1.4.242

- **Дата:** 29 октября 2026 года.
- **Проверенный исходный код:** `d32d6b9f4a3c0150b1a92840e06707fa7e3051dd`.
- **Ветка:** `arena/7bbabe8a-chrdk-f-register`.
- **Платформа сборки:** Purpur 26.3 / Java 25; Core API 27; 26 плагинов.

## Последующие исправления

Документ ниже фиксирует состояние **1.4.242 / d32d6b9**; это не перечень
неисправностей любой будущей версии. Первое исправление, **F01**, внесено
в **1.4.243**: [границы изменения и проверки](../releases/1.4.243.md).
Следующее исправление, **F02**, внесено в **1.4.244**:
[единый ZIP-only контракт](../releases/1.4.244.md).
Остальные находки этими шагами не закрываются.

## 1. Главный вывод

Проект — уже не один плагин регистрации, а большая модульная серверная система. Сильные стороны — выделенный Core API, предметно разделённые аддоны, единый личный банк, специальные протоколы восстановления платежей и существенная база регрессионных тестов.

**Однако текущую версию нельзя считать полностью проверенной для production только потому, что CI зелёный.** Найдены высокоприоритетные дефекты административной авторизации, контракта автообновления и сохранения данных. Первым делом рекомендовано исправить F01–F05, затем ограничить стоимость входа F06. Это не утверждение о произошедшем взломе, потере денег или аварии действующего сервера: доступа к его состоянию и журналам в этом анализе не было.

### Что проверено и что не проверено

Проведены инвентаризация всех 26 модулей, проверка дескрипторов и команд, обзор архитектуры и интеграций, целевое чтение критичных путей аутентификации, хранения, финансов, очереди, телепортов, обновления и снятия аддонов. Сопоставлены workflow релиза, тестовые фикстуры обновлятора и метаданные опубликованного релиза. Запущены доступные локальные Python-проверки.

**Это широкий статический инженерный аудит, а не построчная верификация всех 117 тысяч строк.** Сценарии дефектов ниже — трассы исполнения по коду. Новые Java-тесты для их воспроизведения не выполнялись: локально нет Java. Не проводились запуск Purpur, нагрузочный тест, принудительное падение процесса/ОС, пентест работающего сервера, проверка всех реальных клиентских версий и восстановление реального бэкапа. Для предметных обработчиков боя, инвентаря и приватов вывод «уязвимостей нет» не делается.

**Обозначения:**
- **P1** — высокий приоритет; исправить перед следующим production-развёртыванием.
- **P2** — значимый дефект или эксплуатационный риск; следующая очередь работ.
- **P3** — ограниченный дефект/технический долг.
- **Подтверждено кодом** — есть конкретная достижимая ветка или несовместимость контрактов; это не означает воспроизведение на работающем сервере.
- **Риск** — конструкция найдена, но частота/масштаб последствий требуют измерений либо зависят от окружения.

## 2. Состав и архитектура

По production Java: **644 файла, 117 605 строк**. Метод подсчёта — `splitlines()` по файлам `src/main/java` активных модулей, без тестов и генерируемой сборки. Небольшая разница с `wc -l` связана с отсутствующими завершающими переводами строки.

Найдено **327 тестовых Java-файлов** и **2 596 аннотаций `@Test`, `@ParameterizedTest`, `@RepeatedTest`**. Последнее число — не число реально исполненных случаев и не процент покрытия. Параметризация может давать несколько случаев, а условия запуска — пропуски.

| Модуль | Назначение | Java-файлы | Строки | Тестовые файлы | Тестовые аннотации |
|---|---|---:|---:|---:|---:|
| `core` | Вход, TOTP, банк, чат, профиль, магазин прав, API и обновление | 162 | 36,391 | 99 | 785 |
| `diamond-shop` | Предметный магазин и обмен через личный банк | 16 | 2,172 | 10 | 121 |
| `referrals` | Рефералы и выдача наград | 7 | 678 | 2 | 14 |
| `daily-rewards` | Ежедневные награды | 9 | 931 | 3 | 32 |
| `teleports` | Платные телепорты, дома, RTP и запросы | 13 | 1,781 | 7 | 42 |
| `enchants` | Собственные зачарования | 52 | 5,203 | 18 | 148 |
| `tier-forge` | Тиры, свойства, книги и кузница | 51 | 11,052 | 37 | 254 |
| `artifacts` | Артефакты, коллекции и эффекты | 34 | 5,022 | 18 | 94 |
| `achievements` | Достижения, награды и звания | 13 | 2,437 | 5 | 25 |
| `skills` | Игровые навыки и PvE-способности | 20 | 3,336 | 8 | 56 |
| `common-goals` | Общие цели и коллективный прогресс | 12 | 1,336 | 7 | 35 |
| `claims` | Чанковые приваты, казны, аренда и защита | 35 | 7,383 | 22 | 191 |
| `ranks` | Представление рангов и связанные возможности | 7 | 889 | 2 | 10 |
| `cosmetics` | Шлейфы и предпочтения эффектов | 5 | 771 | 3 | 26 |
| `battlepass` | Сезонный прогресс и награды | 17 | 3,221 | 10 | 71 |
| `queue` | Очередь входа, бронь и приоритет рангов | 5 | 555 | 0 | 0 |
| `lottery` | Билеты, розыгрыш и выплаты | 7 | 726 | 7 | 29 |
| `pets` | Кошки и волки, опыт и эффекты | 15 | 2,478 | 5 | 44 |
| `auction` | Предметные лоты, покупки и расчёты | 11 | 929 | 6 | 43 |
| `orders` | Заказы и взаимодействие с ботами | 15 | 2,946 | 5 | 54 |
| `hunt` | Охота, мобы, трофеи и награды | 47 | 7,713 | 18 | 214 |
| `party` | Группы игроков | 9 | 1,944 | 4 | 27 |
| `quests` | Задания, фракции и прогресс | 17 | 3,358 | 5 | 45 |
| `guilds` | Гильдии, коллективные средства и услуги | 18 | 4,549 | 7 | 79 |
| `classes` | Классы, таланты и способности | 29 | 5,336 | 11 | 87 |
| `professions` | Профессии, специализации и опыт | 18 | 4,468 | 8 | 70 |
| **Всего** | **26 плагинов** | **644** | **117,605** | **327** | **2 596** |

### Устройство системы

- Core публикует API; все 25 аддонов объявляют `depend: [CHRDK_Core]`. Дескрипторы всех 26 плагинов указывают Minecraft API `26.3`.
- Это **модульный монолит внутри одного JVM-сервера**, а не набор изолированных процессов. Ошибка на главном потоке одного аддона влияет на весь сервер.
- Состояние распределено между JSON-файлами модулей, банковскими квитанциями/журналами и native `playerdata`. Общей транзакции между всеми этими файлами нет; вместо неё применяются специализированные протоколы восстановления.
- Большинство игровых мутаций выполняется на серверном потоке; записи прогресса часто асинхронны. Наличие `synchronized` и атомарного rename само по себе не доказывает правильный порядок снимков — см. F04.
- У ряда чувствительных аддонов есть явные проверки минимальной версии Core API. При дальнейшем развитии важно сохранять согласованность этих проверок с фактически вызываемыми методами, а не полагаться только на `depend`.
- Самые крупные классы: `AccessManager` — 2 865 строк, `RegistrationStore` — 2 381, `RegisterCommandHandler` — 1 893, `ChatService` — 1 624. Это точки повышенного риска изменений, но размер класса не является самостоятельным доказательством ошибки.

**Рекомендация по архитектуре:** не переписывать всё сразу. Сначала закрыть конкретные дефекты и добавить тесты границ; затем выделять из Core отдельные координаторы входа, административной авторизации, сохранения и релизного контракта. В денежных путях нельзя заменять проверенный протокол простым «вынесем запись в async».

## 3. Высокоприоритетные находки

### F01 — P1. Административные действия доступны игроку с правом admin до завершения аутентификации

**Статус:** подтверждено кодом; реальная возможность подменить привилегированную личность зависит от режима сервера, прокси и выдачи прав до входа.

**Основание:**
- [src/main/java/ru/cherdakmd/chrdkregister/PlayerRestrictionListener.java:52-60](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/PlayerRestrictionListener.java#L52-L60): `/pass` и его алиасы разрешены на предварительных стадиях; `/2fa` разрешена на стадии второго фактора.
- [src/main/java/ru/cherdakmd/chrdkregister/PlayerRestrictionListener.java:113-121](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/PlayerRestrictionListener.java#L113-L121): разрешение проверяется по имени команды, не по административной подкоманде.
- [src/main/java/ru/cherdakmd/chrdkregister/RegisterCommandHandler.java:370-431](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegisterCommandHandler.java#L370-L431): перед административным `switch` проверяется `chrdkregister.admin`, но не `AUTHENTICATED`.
- [src/main/java/ru/cherdakmd/chrdkregister/RegisterCommandHandler.java:886-909](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegisterCommandHandler.java#L886-L909): сброс пароля и 2FA вызываются из этой ветки.
- [src/main/java/ru/cherdakmd/chrdkregister/RegisterCommandHandler.java:204-215](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegisterCommandHandler.java#L204-L215): административный reset в `/2fa` находится ещё до проверки стадии игрока.
- [src/main/java/ru/cherdakmd/chrdkregister/AccessManager.java:1742-1758](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/AccessManager.java#L1742-L1758): сброс 2FA может завершить вход игрока, уже ожидающего второй фактор.
- [src/main/resources/plugin.yml:140-142](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/resources/plugin.yml#L140-L142): административное право по умолчанию есть у OP.

**Трасса:** игрок ещё не прошёл весь вход, но его Bukkit/LuckPerms-права уже содержат `chrdkregister.admin` → разрешённая корневая команда попадает в обработчик → проверка permission проходит → изменение аккаунта выполняется без проверки завершённой аутентификации отправителя. В частности, на стадии `NEEDS_2FA` существует путь сброса собственной 2FA с последующим завершением входа.

**Важно:** обычный игрок без административного права этим путём не получает admin. Но отдельная защита паролем/TOTP не должна переставать работать именно для администратора. В offline-mode или при небезопасном прокси последствия могут быть существенно тяжелее.

**Исправление:** для игровых отправителей закрыть административные изменения до `AUTHENTICATED`; консоль и уже согласованный доверенный RCON оставить отдельными доверенными отправителями. Публичные подсказки и необходимые операции привязки 2FA должны оставаться доступны. Нужна защита и в маршрутизации, и в обработчиках чувствительных подкоманд.

**Приёмка:** матрица всех стадий × Player/Console × admin/no-admin; до полного входа запрещены сбросы пароля/2FA, административные изменения и чтение чувствительных чужих данных. Отдельный тест: admin на `NEEDS_2FA` не может административно обойти собственный второй фактор. Проверить алиасы и namespaced-команды.

### F02 — P1. Обновлятор несовместим с реальным форматом релизов ZIP-only

**Статус:** подтверждено исходниками, workflow и GitHub API; установка не запускалась.

**Основание:**
- [.github/workflows/release.yml:100-115](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/.github/workflows/release.yml#L100-L115): релиз собирает ZIP из JAR и `ОБНОВЛЕНИЕ.txt`, публикует только ZIP.
- [src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java:98-117](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java#L98-L117): метаданные должны содержать отдельный GitHub asset для каждого JAR, иначе выбрасывается ошибка.
- [src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java:196-205](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java#L196-L205): внутри ZIP разрешены только имена JAR; обязательная памятка тоже будет отклонена как неожиданная запись.
- [src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java:64-71](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java#L64-L71): `fetchRelease()` вызывается и для ручного `check`, и для `download`.
- [src/test/java/ru/cherdakmd/chrdkregister/PluginUpdateServiceTest.java:65-79](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/test/java/ru/cherdakmd/chrdkregister/PluginUpdateServiceTest.java#L65-L79): тестовая фикстура публикует отдельные JAR-asset и формирует ZIP без памятки, то есть проверяет другой контракт.

Дополнительно выполнено `gh release view v1.4.238 --json tagName,assets`: единственный asset — `chrdk-plugins-1.4.238.zip`. Это согласуется с принятым форматом распространения, но не с текущим парсером обновлятора.

**Последствие:** `/pass update check|download` не может нормально обработать такой релиз. Даже устранение первого отказа не решит вторую проблему с памяткой в ZIP. Обычный автоматический информатор новых версий — отдельный путь; этот вывод относится к `PluginUpdateService` и ручной команде.

**Исправление:** сохранить ZIP-only, не возвращать отдельные JAR в публикацию. Доверять проверенному digest общего ZIP; внутри строго валидировать полный набор имён, версии, plugin.yml, размеры, дубликаты и допустимую памятку. Если нужны отдельные хеши JAR — хранить проверяемый manifest в том же архиве, а не требовать несуществующие release assets.

**Приёмка:** интеграционный тест «упаковщик workflow → ZIP с памяткой → fixture API с единственным ZIP-asset → обновлятор». Сохранить защиту от Zip Slip, чужих файлов, подмены, несовместимой Java/API и неполного комплекта.

Отдельно: отказ при наличии снятых аддонов в `plugins` — **намеренная защита**, не этот дефект: [src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java:235-247](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/PluginUpdateService.java#L235-L247). Автоматическое удаление их JAR не предлагается.

### F03 — P1. Восстановление регистрационной базы может откатить исправные данные или начать с пустой базы

**Статус:** подтверждено кодом; частота аварийного окна не измерялась.

**Основание:**
- [src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java:251-292](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java#L251-L292): JSON заменяется первым, sidecar SHA-256 записывается отдельно. Ошибка sidecar логируется и не возвращается вызывающему коду как неуспех.
- [src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java:149-163](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java#L149-L163): усиленный checkpoint наград тоже делает два последовательных rename — сначала JSON, затем hash; между ними остаётся окно падения.
- [src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java:325-339](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java#L325-L339): несовпадение sidecar запускает восстановление как при повреждении данных.
- [src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java:348-368](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java#L348-L368): проверяется только самый новый backup; если он не подошёл либо отсутствует, создаётся пустая регистрационная база и выставляется `dirty`.

**Трассы:**
1. JSON успешно заменён, процесс остановился до обновления hash → после рестарта новый корректный JSON сравнивается со старым hash → запускается откат в backup.
2. Последний backup повреждён, но предыдущий исправен → код не перебирает остальные → переходит к пустой базе.
3. Существующая база не читается, пригодного backup нет → сервер продолжает работу с утраченными записями аккаунтов вместо блокировки авторизации.

Есть сохранение копии повреждённого файла — это хорошо, но оно не делает продолжение с пустыми аккаунтами безопасным. В free-entry/offline-сценарии утрата регистрации может также открыть повторную регистрацию известного ника; реальная эксплуатация зависит от идентификации на входе. Банк и мир при этом находятся в других файлах.

**Исправление:** при повреждении существующей базы — карантин авторизации и операторская сверка, а не автоматический «чистый лист». Отдельно различать первый запуск и потерю базы существующей установки. Обеспечить согласованное поколение JSON/hash: единый атомарный envelope либо журнал/manifest с доказуемым commit. При допустимом восстановлении проверять доступные поколения последовательно; не считать непроверенный откат аккаунтов безопасным по умолчанию.

**Приёмка:** падение между каждым шагом replace/hash; новый повреждённый backup при исправном старом; отсутствующие и нечитаемые файлы; невозможность записать восстановленное состояние. Нельзя допускать свободный вход, повторную регистрацию существующих игроков и автоматическую компенсацию денег при неопределённом состоянии.

### F04 — P1. Старый async-снимок может перезаписать более новый, включая shutdown-save

**Статус:** подтверждён допустимый порядок конкурентного исполнения; воспроизведение Java-тестом не выполнялось.

**Основание:**
- [professions/src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionStore.java:480-497](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/professions/src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionStore.java#L480-L497).
- [skills/src/main/java/ru/cherdakmd/chrdkskills/SkillStore.java:228-245](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/skills/src/main/java/ru/cherdakmd/chrdkskills/SkillStore.java#L228-L245).
- [quests/src/main/java/ru/cherdakmd/chrdkquests/QuestStore.java:375-392](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/quests/src/main/java/ru/cherdakmd/chrdkquests/QuestStore.java#L375-L392).
- [pets/src/main/java/ru/cherdakmd/chrdkpets/PetStore.java:407-420](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/pets/src/main/java/ru/cherdakmd/chrdkpets/PetStore.java#L407-L420).
- [src/main/java/ru/cherdakmd/chrdkregister/storage/SafeJsonWriter.java:71-90](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/storage/SafeJsonWriter.java#L71-L90): общая блокировка захватывается только после получения готового снимка.
- [professions/src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionsPlugin.java:60-61](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/professions/src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionsPlugin.java#L60-L61) и [professions/src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionsPlugin.java:83-95](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/professions/src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionsPlugin.java#L83-L95): async autosave и финальный flush действительно сосуществуют; cancel не дожидается уже исполняющегося runnable.

**Трасса:** A получил снимок S1 и был вытеснен до вызова writer → состояние изменилось → B получил S2 и записал его → A продолжил и записал S1. Оба JSON корректны, временный файл защищён, но итог на диске устаревший. Если `dirty` уже сброшен, следующий autosave не обязан исправить откат.

`SafeJsonWriter` защищает целостность записи и временного файла, **не хронологию создания снимков**.

**Исправление:** сериализовать получение снимка вместе с записью отдельным writer-координатором либо использовать монотонные номера поколений и отсечение устаревших записей. Shutdown должен дожидаться согласованного финального поколения. Не менять порядок блокировок наугад.

Положительный пример в том же проекте: [cosmetics/src/main/java/ru/cherdakmd/chrdkcosmetics/SelectionStore.java:52-61](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/cosmetics/src/main/java/ru/cherdakmd/chrdkcosmetics/SelectionStore.java#L52-L61) — writer захвачен до snapshot.

**Приёмка:** тест с latch/barrier, принудительно выполняющий S2 до попытки записи S1; на диске должен остаться S2. Повторить при shutdown, мутации во время сохранения и ошибке старого writer.

### F05 — P1. После неуспешного flush регистрационной базы флаг повторной записи теряется

**Статус:** подтверждено кодом; последствие возникает при ошибке записи и отсутствии последующей мутации.

**Основание:** [src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java:119-133](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java#L119-L133) сбрасывает `dirty` до записи; [src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java:251-277](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java#L251-L277) при ошибке только логирует её. `dirty` обратно не выставляется. Отказ executor также не восстанавливает состояние в этом пути.

**Последствие:** после кратковременной ошибки диска дальнейшие `flush()` могут ничего не делать, если новых изменений нет. Состояние остаётся в памяти до следующей мутации либо успешного принудительного flush; при последующем падении оно теряется. Это больше, чем нормальное согласованное окно debounce.

Связанный случай: [src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java:213-232](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java#L213-L232) сообщает успех `restoreBackup()` после void `flushBlocking()`, хотя внутренняя ошибка записи может быть проглочена. Администратор не получает достоверного подтверждения записи на диск.

**Исправление:** явный результат записи, корректное состояние pending/retry и подтверждение только после успешного сохранения нужного поколения. Учесть общий порядок `this`/`writeLock`: механическое добавление второго monitor в catch способно создать deadlock с другими путями.

**Приёмка:** искусственная ошибка записи → отсутствие новых мутаций → восстановление диска → повторный flush действительно сохраняет данные. Ошибка restore не должна превращаться в успешный ACK. Проверить одновременный успех более нового поколения и отказ старого.

### F06 — P1 по приоритету нагрузки. PBKDF2 выполняется на игровом потоке и под общим monitor базы

**Статус:** блокирующий путь подтверждён; реальная задержка и возможность отказа в обслуживании не измерялись.

**Основание:**
- [src/main/java/ru/cherdakmd/chrdkregister/AccessManager.java:891-909](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/AccessManager.java#L891-L909): после throttle/captcha командный путь синхронно проверяет пароль.
- [src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java:1100-1108](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/RegistrationStore.java#L1100-L1108): PBKDF2 выполняется внутри `synchronized verifyPassword`.
- [src/main/java/ru/cherdakmd/chrdkregister/PasswordHasher.java:11-16](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/PasswordHasher.java#L11-L16): 65 536 итераций PBKDF2-HMAC-SHA256.
- [src/main/java/ru/cherdakmd/chrdkregister/AccessManager.java:846-851](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/AccessManager.java#L846-L851) и [src/main/java/ru/cherdakmd/chrdkregister/PasswordPolicy.java:138-150](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/PasswordPolicy.java#L138-L150): при регистрации/смене пароля последовательно проверяются кандидаты из списка аккаунтов того же IP.

Это **не сканирование паролей всех аккаунтов сервера**: список ограничен общим IP и временным окном. Но стоимость растёт с числом кандидатов, а штатный anti-multialt в режиме notify не является жёсткой гарантией малого списка. Сама проверка выполняется последовательно, без отдельного лимита стоимости в этом цикле.

Throttle/captcha уже есть и уменьшают злоупотребление, но не убирают CPU-задержку разрешённых попыток и общую блокировку store.

**Исправление:** ограниченная очередь и пул вычисления хешей, rate limit до постановки работы, проверка неизменности аккаунта/сессии после возврата результата. На worker передавать снимок хеша; Bukkit-операции и смену стадии возвращать на серверный поток. Не держать весь RegistrationStore заблокированным во время KDF. Для same-IP проверки определить отдельный bounded budget.

**Приёмка:** latency p50/p95/p99 входа, MSPT/TPS при конкурентных разрешённых попытках, отказ от лишних задач при перегрузке, корректность logout/disconnect/password-reset во время проверки. Увеличение числа итераций без этой изоляции не является решением.

## 4. Остальные подтверждённые дефекты

### F07 — P2. Некоторые игровые хранилища продолжают работу после ошибки загрузки и могут затереть исходный файл

**Основание:**
- [professions/src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionStore.java:100-125](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/professions/src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionStore.java#L100-L125): ошибка чтения заканчивается сообщением «начну с чистого листа», без недоступного состояния.
- [pets/src/main/java/ru/cherdakmd/chrdkpets/PetStore.java:167-175](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/pets/src/main/java/ru/cherdakmd/chrdkpets/PetStore.java#L167-L175): исключение только логируется.
- [cosmetics/src/main/java/ru/cherdakmd/chrdkcosmetics/SelectionStore.java:28-47](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/cosmetics/src/main/java/ru/cherdakmd/chrdkcosmetics/SelectionStore.java#L28-L47): аналогично отсутствует блокировка последующей записи.

На новом запуске карты изначально пусты; очередная нормальная мутация делает их dirty, после чего обычная запись заменяет исходный файл неполным/новым состоянием. Для частично допустимых записей также важно различать намеренную миграцию и безвозвратное отбрасывание данных.

**Рекомендация:** единая политика загрузки прогресса: staging-parse, проверка схемы, quarantine/read-only при фатальной ошибке, сохранение оригинала и операторская процедура восстановления. Не распространять этот вывод на все store: банк уже использует другой безопасный режим — [src/main/java/ru/cherdakmd/chrdkregister/BankStore.java:387-396](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/BankStore.java#L387-L396).

### F08 — P2. Очередь допускает обход FIFO после истечения брони

**Основание:** [queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueService.java:201-208](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueService.java#L201-L208) снимает истёкшую бронь, но не выдаёт её следующему; [queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueService.java:63-80](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueService.java#L63-L80) пропускает входящего при свободном слоте и отсутствии брони, даже когда очередь не пуста. Следующая бронь выдаётся отдельным `maybeReserveNext()` — [queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueService.java:103-116](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueService.java#L103-L116).

**Трасса:** в очереди A и B → A получил бронь, но не пришёл → бронь истекла → новичок C пытается войти при свободном слоте → C разрешён раньше B.

**Исправление/тест:** при свободной ёмкости в том же критическом переходе продвигать следующего живого участника; FIFO не должен зависеть от нового события выхода. Для тестирования внедрить управляемые часы вместо ожидания реального таймера. Java-тестовых файлов в модуле `queue` сейчас нет.

### F09 — P2. Мягкий лимит очереди не учитывает входящие подключения

**Основание:** [queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueService.java:56-80](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueService.java#L56-L80) сериализует решения, но между ними не сохраняет количество разрешённых ещё не присоединившихся игроков. Кроме того, владелец брони получает ALLOW и при `onlineNow >= regularLimit` в строках 72–74.

**Последствие:** несколько pre-login при одном свободном месте могут увидеть один online и получить ALLOW. Это риск превышения **мягкого лимита обычных слотов**, не доказательство обхода hard `max-players` Bukkit.

**Исправление/тест:** учёт in-flight admissions с TTL и освобождением при успешном join, отказе и таймауте; явное правило для брони при заполнении слота. Не смешивать VIP-priority с бесконтрольным oversubscription. Проверить конкурентные pre-login и отмену другим плагином.

### F10 — P2. Два плагина объявляют одну командную метку `/prof`

**Основание:** [src/main/resources/plugin.yml:22-25](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/resources/plugin.yml#L22-L25) объявляет `prof` алиасом профиля; [professions/src/main/resources/plugin.yml:11-15](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/professions/src/main/resources/plugin.yml#L11-L15) объявляет её основной командой профессий.

Автоматическая инвентаризация всех дескрипторов обнаружила именно это межмодульное пересечение. Точное разрешение метки определяет Bukkit command map; по одному plugin.yml нельзя обещать обе функции на одном коротком имени. `/profile` и namespaced-команды не тождественны этому конфликту.

**Исправление:** закрепить `/prof` за профессиями и убрать неоднозначное обещание профиля либо выбрать другой согласованный алиас. Добавить общую проверку конфликтов основных команд и алиасов между модулями; приёмка на реальном command map Purpur.

### F11 — P2. Штатные платёжные ссылки не приведены к одному известному магазину

**Статус:** подтверждено для default-конфига; настройки работающего сервера неизвестны.

Новая `/donate` правильно показывает `https://chrdk.trademc.org/`: [src/main/java/ru/cherdakmd/chrdkregister/DonateCommandHandler.java:12-15](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/DonateCommandHandler.java#L12-L15). Но [src/main/resources/config.yml:117-122](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/resources/config.yml#L117-L122) оставляет `trademc.payment-url` пустым, а [src/main/resources/config.yml:32-33](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/resources/config.yml#L32-L33) содержит legacy `@your_page`. [src/main/java/ru/cherdakmd/chrdkregister/PluginConfig.java:599-606](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/PluginConfig.java#L599-L606) при пустом TradeMC URL возвращает DonatePay URL. Его используют меню ранга, текстовый каталог, MOTD/placeholder и подсказки продления.

**Последствие:** новая ссылка верна, но другая точка интерфейса на чистой установке может направлять на шаблонную/чужую страницу. Это не подтверждение неправильного зачисления платежа: обработка выдачи — другая граница.

**Исправление/тест:** единый resolver магазина с корректным штатным TradeMC URL, сохранением явно выбранных внешних провайдеров и пользовательских значений. Проверить весь путь игрока, а не только команду `/donate`. Не менять карточки TradeMC и не выпускать новые материалы в рамках аудита.

### F12 — P3. Диагностический `NotificationService.queueSize()` использует неверный cast

**Основание:** [src/main/java/ru/cherdakmd/chrdkregister/NotificationService.java:45-56](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/NotificationService.java#L45-L56) создаёт worker через `Executors.newSingleThreadExecutor`; [src/main/java/ru/cherdakmd/chrdkregister/NotificationService.java:168-170](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/NotificationService.java#L168-L170) приводит возвращённый wrapper к `ThreadPoolExecutor`. Фабрика возвращает делегирующую оболочку, не такой публичный concrete executor; при вызове метода ожидается `ClassCastException`.

**Граница влияния:** вызовов `queueSize()` в текущем production-коде не найдено, поэтому это не основание утверждать, что сервер сейчас падает. Исправить тип/счётчик, добавить unit-тест диагностики. Заодно полезна ограниченная очередь уведомлений; имеющийся throttle и HTTP-timeout уже снижают нагрузку.

## 5. Безопасность и финансы: что сделано хорошо и где границы уверенности

### Пароли и второй фактор

- Пароль хранится не открытым текстом: PBKDF2-HMAC-SHA256, случайная 16-байтовая соль, 256-битный результат и `MessageDigest.isEqual`; параметры итераций находятся в строке хеша. Это разумный базовый механизм, но не замена аудиту авторизации F01 и потоков F06.
- В TOTP есть случайный 160-битный секрет, шестизначные коды, 30-секундный шаг, формат otpauth и API проверки с учётом шага. HMAC-SHA1 в RFC-совместимом TOTP сам по себе не является доказательством взлома механизма.
- Стадии входа разделены, есть ограничения движения, мира, инвентаря, чата и команд. Разрешения для QR-карты сделаны отдельно; это полезная защита, но не покрывает административную ветку F01.
- Есть throttle по аккаунту/IP, backoff и captcha. Существование этих механизмов подтверждено; их устойчивость к распределённой нагрузке и параметры именно действующего сервера не проверены.
- Рекомендуется впоследствии измерить стоимость KDF на целевом CPU и предусмотреть upgrade/rehash политики без обнуления паролей. Не объявляется, что один лишь текущий параметр 65 536 позволяет практически расшифровать аккаунты.

### Единая экономика

Архитектура денег заметно сильнее обычного «удалить предмет → прибавить balance». Сохраняются согласованные границы: личный банк отдельно от коллективных фондов; предметные валюты/ресурсы не следует автоматически считать личными алмазными деньгами.

Подтверждённые положительные механизмы:

1. `BankStore` использует целочисленные суммы, ограничение баланса, проверку схем и числовых типов. При повреждении банка выключает изменения, а не создаёт пустой кошелёк — [src/main/java/ru/cherdakmd/chrdkregister/BankStore.java:227-238](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/BankStore.java#L227-L238), [src/main/java/ru/cherdakmd/chrdkregister/BankStore.java:387-396](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/BankStore.java#L387-L396).
2. В строгой банковской записи есть force временного файла, атомарный replace и force каталога — [src/main/java/ru/cherdakmd/chrdkregister/BankStore.java:834-848](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/BankStore.java#L834-L848). Нельзя переносить гарантии этого пути на обычный `SafeJsonWriter`, где force нет.
3. Для выдач/списаний используются source/lineage, последовательности, квитанции и pending-состояния; финансовые операции не сводятся к двум независимым изменениям памяти.
4. Native-переход storage привязан к nonce в `playerdata`, а совпадение AFTER без nonce не считается доказательством выполненной операции — [src/main/java/ru/cherdakmd/chrdkregister/api/InventoryTransfer.java:9-39](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/api/InventoryTransfer.java#L9-L39). В production-port вызывается `player.saveData()` — [src/main/java/ru/cherdakmd/chrdkregister/api/ChargeInventory.java:92-106](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/api/ChargeInventory.java#L92-L106).
5. `PaidActions` проверяет аутентификацию и режим игрока; отделяет READY от EXECUTING, сохраняет неопределённый исход, а не делает слепой повтор или возврат — [src/main/java/ru/cherdakmd/chrdkregister/api/PaidActions.java:41-84](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/api/PaidActions.java#L41-L84). Это правильная цена более строгой целостности.
6. Аренда проверяет оплаченный период, средства сторон и продление; нехватка прекращает аренду, а покрытие ренты учитывается вперёд — [claims/src/main/java/ru/cherdakmd/chrdkclaims/ClaimRentals.java:52-96](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/claims/src/main/java/ru/cherdakmd/chrdkclaims/ClaimRentals.java#L52-L96). Живая совместная проверка всех прав владельца/арендатора всё ещё нужна.

**Чего этот обзор не доказывает:** отсутствие всех вариантов дюпа, exactly-once при произвольном отказе файловой системы, корректность любого плагина-конкурента в цепочке Bukkit events и сбалансированность экономических цен. Не обнаружено оснований объявлять найденным конкретный double-spend в текущем банковском протоколе.

Для оценки экономического баланса нужны реальные источники и стоки алмазов за сутки, распределение состояний/оборота, частота выплат и объёмы торговли. По одному коду нельзя честно назвать экономику «неинфляционной». Если оптимизировать persistence, нельзя терять квитанции и source sequence: бездумная очистка старых записей может разрушить защиту от повторов.

### Приваты, предметы и бой

`ClaimProtectionListener` охватывает существенно больше ломания блоков: жидкости, взрывы, поршни, контейнеры, сущности, транспорт и другие взаимодействия. Это хороший охват поверхности, но не сертификат непроходимости всех границ чанка и внешних плагинов.

В новых активных способностях Skills явно отсекаются игроки, приручённые существа, стойки и NPC; результат проверяется после цепочки отмены события — [skills/src/main/java/ru/cherdakmd/chrdkskills/SkillAbilities.java:126-160](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/skills/src/main/java/ru/cherdakmd/chrdkskills/SkillAbilities.java#L126-L160). Это подтверждает PvE-ограничение **этого конкретного пути**, но не означает новый полный аудит всех классов/чар/артефактов и PvP-меты.

Нужен тестовый сервер с матрицей: границы соседних чанков, отрицательные координаты, арендованный чанк, world border, piston/hopper/liquid/explosion, shift-click/number-key/offhand/cursor/drag, смерть/выход/рестарт в незавершённой операции, финальная отмена события другим плагином. В рамках анализа механики PvP не менялись.

## 6. Эксплуатационные и архитектурные риски

### R01 — P2. RTP может синхронно загружать/генерировать случайные чанки

[teleports/src/main/java/ru/cherdakmd/chrdkteleports/TeleportCommandHandler.java:149-170](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/teleports/src/main/java/ru/cherdakmd/chrdkteleports/TeleportCommandHandler.java#L149-L170) ищет несколько точек циклом и вызывает `getHighestBlockYAt`/`getBlockAt` на серверном потоке, без предварительной асинхронной подготовки чанка. Warmup вызывает callback на основном потоке — [teleports/src/main/java/ru/cherdakmd/chrdkteleports/TeleportService.java:76-83](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/teleports/src/main/java/ru/cherdakmd/chrdkteleports/TeleportService.java#L76-L83).

На непрогретом мире это может создавать пики MSPT. Зависания на реальном мире не воспроизводились. Нужны предварительная генерация/асинхронная подготовка чанка, bounded-поиск и повторная проверка безопасности/прав перед платной операцией. Нельзя просто перенести обращения к Bukkit world в произвольный worker.

### R02 — P2. Строгая запись банка сериализует весь envelope

[src/main/java/ru/cherdakmd/chrdkregister/BankStore.java:821-842](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/src/main/java/ru/cherdakmd/chrdkregister/BankStore.java#L821-L842) копирует таблицы и сериализует весь банк перед строгой записью; предел файла — 64 MiB. Это даёт хорошую целостность, но стоимость растёт с количеством аккаунтов и квитанций. В финансовом командном пути synchronous fsync может стать видимой задержкой; время на целевом диске не измерялось.

Нужны измерения на реалистичных 1k/10k/50k аккаунтах и разных объёмах истории: размер, alloc/GC, длительность snapshot, force и операции, p95/p99 MSPT. Сначала мониторинг и предупреждение о приближении к лимиту; затем, если подтверждена необходимость, append-only WAL/компактация либо транзакционное хранилище с сохранением инвариантов. Не обрезать финансовые квитанции ради размера без формального протокола.

### R03 — P2. Зависание внешней проверки приоритета может удерживать pre-login

[queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueLoginListener.java:105-126](https://github.com/cherdakmd/CHRDK_F_REGISTER/blob/d32d6b9f4a3c0150b1a92840e06707fa7e3051dd/queue/src/main/java/ru/cherdakmd/chrdkqueue/QueueLoginListener.java#L105-L126) использует `loadUser(...).join()` без собственного timeout. Это не блокирует главный tick напрямую, но может удерживать pre-login worker при проблеме backend LuckPerms. Нужны таймаут, bounded concurrency и явная безопасная политика fallback. Фактическое зависание LuckPerms не наблюдалось.

### R04 — P2/P3. Воспроизводимость сборки и среда обновления требуют усиления

- Purpur API привязан к `26.3.build.2647-experimental`, Java release — 25. Клиентская совместимость через ViaVersion/ViaBackwards не означает, что эти JAR можно загрузить на сервер 1.21 или Java 21.
- В просмотренных Gradle-конфигах не найдено подключения JaCoCo, mutation testing или отдельного Java static-analysis gate; это не означает, что юнит-тесты отсутствуют.
- В `gradle/wrapper/gradle-wrapper.properties` есть проверка URL, но нет `distributionSha256Sum`. В каталоге Gradle не найдено dependency verification metadata/lockfile. Actions в workflow закреплены тегами версий, а не commit SHA. Это направления supply-chain hardening, не свидетельства компрометации.
- SHA-256 релизного asset защищает передачу относительно метаданных GitHub, но сам по себе не защищает от компрометации аккаунта/репозитория-издателя.
- Backup обновлятора содержит JAR, не целостный снимок мира, банка, playerdata и всех source-журналов. Он не заменяет согласованный backup данных и не гарантирует безопасный downgrade схем.
- Проверить реальные права доступа к `backups/*.json.gz`, конфигам с токенами, TOTP-секретам, audit и corrupt-копиям; chmod основного `data.json` не доказывает закрытость всех копий. Права/umask действующего хостинга в этом анализе неизвестны.

## 7. Снятые аддоны

Проверка активных ссылок на Bosses, Treasures, Streams и Events прошла. Исторические release notes, migration snapshots, общие Bukkit/Core events и Java Stream API не должны удаляться по совпадению слов.

Последствия снятия Events остаются эксплуатационной задачей: перед удалением старого JAR сверить pending `events-loot` в `plugins/CHRDK_Events/milestone-awards.json`, банковские квитанции и lineage. В новом комплекте нет старого исполнителя для автоматического завершения этих наград. Аналогично финансовые журналы других снятых модулей не являются обычным мусором.

Нельзя «исправлять» снятие удалением журналов, слепой повторной выплатой, массовым убийством обычных мобов или откатом мира. Старые JAR, включая переименованные копии и содержимое `plugins/update`, требуют явной ручной проверки. Новое удаление/установка в рамках этого аудита не выполнялось.

## 8. Проверки и доказательства

### Проверено в CI для исследуемого commit

- [Build 40856640364](https://github.com/cherdakmd/CHRDK_F_REGISTER/actions/runs/40856640364): **SUCCESS**, head SHA совпадает с исследуемым.
- [Wiki 40856640097](https://github.com/cherdakmd/CHRDK_F_REGISTER/actions/runs/40856640097): **SUCCESS**, тот же head SHA.
- Workflow Build выполняет Gradle `build`, Python-проверки и публикацию JUnit artifact. Это подтверждает результат последней сборки, но не отсутствие описанных логических пробелов тестов.

### Запущено локально при аудите

| Проверка | Результат |
|---|---|
| `check_retired_addons.py` | PASS |
| `unittest discover -s .github/scripts -p 'test_*.py'` | **20 тестов, OK** |
| `check_messages.py` | PASS во всех модулях |
| `tools/trademc/build.py --check` | PASS; 26 существующих товаров, без публикации/изменения материалов |
| `unittest discover -s tools/trademc -p 'test_*.py'` | **22 теста, OK** |
| Разбор 26 `plugin.yml`, команд и permissions | Выполнен; найден F10 |
| `git diff --check` | PASS; новый отчёт отдельно проверен через `--no-index --check` |

Messages-check сообщает кандидатов «мёртвых» ключей и динамические обращения. Это эвристика, а не доказательство, что каждую указанную строку нужно удалять. Контракт наличия распознанных сообщений прошёл.

### Ограничение JUnit-статистики

Artifact `junit-d32d6b9f4a3c0150b1a92840e06707fa7e3051dd` существует, но скачивание из этой среды не удалось: GitHub перенаправил запрос на недоступный storage host. Поэтому точное число выполненных/пропущенных Java-тестов и их суммарное время **не извлечены**. Число 2 596 в этом документе — только аннотации исходников. Процент покрытия строк/ветвей не установлен; зелёный `build` не заменяет такой отчёт.

### Самые важные пробелы тестов

- Нет Java-тестовых файлов модуля queue, хотя там есть часы, FIFO, резерв и конкурентные pre-login.
- Тестовый релиз обновлятора отличается от настоящего релиза; это объясняет сочетание зелёного CI и F02.
- Для выявленных F01/F03/F04/F05 требуется добавить негативные и fault-injection тесты; нынешний зелёный CI их корректность не доказывает.
- Чистые unit-тесты не заменяют интеграцию с native `playerdata`, порядком Bukkit listeners и поведением сервера при shutdown.

## 9. Рекомендуемый порядок работ и критерии выпуска

### Этап A — блокирующие исправления

1. **F01:** завершённая аутентификация обязательна для игровых административных операций; opt-in 2FA и доверенный Console/RCON сохраняются.
2. **F02:** один проверяемый ZIP-контракт между упаковкой и установщиком, с памяткой и без отдельных публичных JAR.
3. **F03:** безопасный отказ при неопределённом регистрационном состоянии, согласованные поколения JSON/hash, проверяемое восстановление.
4. **F04/F05:** общий порядок снимков и подтверждённый retry без потери pending-изменений.
5. **F06:** ограниченная обработка паролей вне tick-потока с защитой от устаревшего результата.

Каждый пункт — отдельные регрессионные тесты и понятная русская документация изменений. Не смешивать это с новым балансом, отключением регистрации, отменой 2FA или очередным удалением игровых механик.

### Этап B — короткая очередь надёжности

- Queue: FIFO, in-flight ёмкость, управляемое время, timeout LuckPerms.
- Quarantine для повреждённого прогресса и понятная процедура восстановления.
- Конфликт `/prof`, единая правильная ссылка на магазин, безопасная диагностика уведомлений.
- Тесты взаимодействия приватов/инвентаря/аренды и сохранение PvE-границ.

### Этап C — производительность и эксплуатационный допуск

До заявления «готово к production» получить:

- зелёный полный Gradle build всех оставшихся модулей и фактический JUnit summary;
- integration smoke на точной версии Purpur/Java с чистыми и мигрированными данными;
- успешное восстановление согласованного backup на отдельной копии сервера;
- fault injection вокруг write/replace/force/native save и перезапуска;
- измерения входа, RTP и финансов с реальными объёмами базы;
- проверку отменённых событий, отказов плагинов и прерванного shutdown;
- проверенный ZIP релиза и обновление через тот же ZIP;
- ручную сверку pending снятых аддонов и старых JAR перед установкой.

Полезные инварианты для тестов: «неаутентифицированный игрок не выполняет admin-мутации», «поколение данных на диске не убывает», «ошибка записи не считается подтверждением», «неопределённая операция не исполняется повторно без доказательства», «одна оплата не даёт две выдачи», «компенсация не возникает без доказанного отказа».

## 10. Что изменено этим анализом

Добавлен только этот отчёт. Production-код, конфигурации, версия, API и игровые механики не менялись. Коммит, push, release и установка не выполнялись. Пользовательский `tg-update-1.4.220.png` не изменён. Материалы TradeMC не готовились и не публиковались; выполнена только проверка уже существующих файлов.

**Итог:** проект имеет сильную функциональную и финансовую основу, но наиболее полезная следующая работа — не добавление контента и не тотальный рефакторинг. Это закрытие границ авторизации, единого релизного контракта и надёжного persistence с воспроизводимыми негативными тестами.
