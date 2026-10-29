# Сквозной аудит экономики — 1.4.232 / API 22

> Исторический срез. Актуальное закрытие всех переходов: [итоговый реестр 237](economy-final-1.4.237.md).

Проверка исходников и стандартных конфигов после 231. Это не проверка остатков
работающего сервера и не live crash-test Paper. **Общий релиз ещё заблокирован.**

## Исправлено в 232

| Путь | Найденный риск | Исправление |
|---|---|---|
| Lottery, CommonGoals, Teleports, claim tp, Guilds через ChargeInventory/PaidActions | PDC nonce записывался, но recovery его не читал: один AFTER-snapshot мог закрыть журнал | Обязательная связка bank receipt + точный source + native witness; AFTER без nonce — CONFLICT |
| Те же предметные оплаты | Исключение saveData оставляет nonce только в памяти | При повторе nonce нужен успешный checkpoint текущего playerdata, без повторного списания и без замены актуального inventory старым снимком |
| Orders | Повтор с in-memory nonce мог сразу разрешить публикацию денежного дохода | InventoryIncome использует общий InventoryTransfer, повтор требует checkpoint |
| Guilds → Core commitBankCharge | `guilds` отсутствовал в allowlist; создание/взносы могли постоянно отклоняться | Разрешён только source `guilds` с `chrdkguilds.use`; auth/режим/NPC/точный план и другие отказы сохранены |
| Auction: доплата предметами | После банковского receipt восстановление доверяло AFTER без native nonce | Перед почтой/закрытием pending требуется native proof; bank-only не читает playerdata |
| Auction: выставление/получение предмета | Snapshot-only COMPLETE не доказывает native-переход | Новый transferId в pending, storage+nonce+saveData до публикации source |
| BankStore, ChargeJournal, PaidActionStore, AuctionStore | RuntimeException после rename не всегда останавливал writer | Карантин для IO и runtime-неопределённости; старое in-memory состояние не переписывает durable outcome |

`InventoryTransfer` — чистый production executor с native-портом. При своём nonce
сначала checkpoint; при отсутствии nonce применить можно только точный BEFORE,
не AFTER/не чужой inventory. Несовпадение UUID, недоступный игрок, чужой nonce и
неопределённый исход не превращаются в успешное списание. Для новых намерений
без nonce и с BEFORE допустимо завершить сохранённый запрос после restart.

Для аукциона используются ключи `chrdk_inventory:*`, отдельно от
`chrdk_core:charge_*`. Последние принадлежат Root BankChargeGuard и должны точно
совпадать с его банковскими receipts; создавать там orphan nonce нельзя.
Сам Root guard для оплаченных BankCharge остаётся включённым и без аддонов.

### Старые pending аукциона

Неоконченная старая предметная операция без transferId не получает случайно
сгенерированное «доказательство». Она остаётся для ручной сверки согласованного
market/playerdata. Не удалять pending и не выдавать предметы наугад.
Старый cash-pending имеет transaction в AuctionPayment: BEFORE допускает
подтверждённое завершение оплаты, AFTER без nonce не принимается.
Уже завершённые лоты/почта/деньги не переимпортируются из-за нового поля.

## Открытые блокирующие переходы

| Путь | Подтверждённое состояние кода | Что ещё необходимо |
|---|---|---|
| Core `/bal deposit` | Закрыто в 233: canonical bank.json v5 intent + native proof + receipt/баланс одним replace | [Протокол и границы 233](../releases/1.4.233.md) |
| Core `/bal withdraw` | Закрыто в 233: целый storage-only grant с резервом счёта/nonce/checkpoint; без drops | [Протокол и границы 233](../releases/1.4.233.md) |
| DiamondShop | Закрыто в 235: durable source intent → Core reservation/native proof → bank receipt → once stock/stats/accepted UTC effect | [Протокол, тесты и границы 235](../releases/1.4.235.md) |
| Claims земля/аренда/личные операции | Закрыто в 236: отдельная shadow-модель, canonical domain WAL, атомарная квитанция всех счетов и once domain ACK; native funding в личный /bal | [Протокол, проверки и границы 236](../releases/1.4.236.md) |

Укрепление ChargeInventory **не переподключает автоматически** эти старые API.
В частности, просто добавить saveData в callback недостаточно: сбой второго
независимого файла всё ещё может оставить подтверждённый банк и неподтверждённый домен.

## Дополнительные денежные выходы в конфигах

Предыдущий реестр «нет отдельного кошелька» не равен «нет алмазных наград».
Повторный проход обнаружил ещё два блокирующих конфигурационных пути:

- **DailyRewards**: обычные `DIAMOND 2/3/4/5/6` и VIP `DIAMOND 5/6/8/12` в
  `items`/`vip-items` стандартного config.yml. DailyService выдаёт их предметами
  после async claim. Нужен frozen daily source/день/цикл и /bal без повторов;
  остальные предметы, часы буста и право на VIP-набор не конвертируются.
- **Treasures loot.yml**: обычные безымянные DIAMOND/DIAMOND_BLOCK в таблицах
  редкостей/регионов — ещё один алмазный выход помимо закрытых в 231 rank/collection.
  Его нужно отделить от настоящих предметных находок и сохранить случайный денежный
  исход в source до /bal. Именованный алмаз «Эпический амулет», инструменты, броня,
  рецепты и XP не являются свободными деньгами и должны остаться предметами.

Это уточняет прежнюю классификацию DailyRewards/Treasures, а не закрывает gate
словами «лут не кошелёк». Изменение стандартного конфига само по себе не защищает
существующий пользовательский config/loot.yml — нужен production adapter.
BattlePass найденные DIAMOND_ORE — цели заданий, DIAMOND_SWORD/PICKAXE/CHESTPLATE —
экипировка; комментарий `icon: DIAMOND` — пример иконки, не найденная выплата.
В Enchants обычный DIAMOND — явно материальный ингредиент мастерской, остаётся рецептом.

## Условия общего релиза

- [x] 232: native proof общей оплаты, Orders и pending-аукциона; Guilds allowlist; runtime quarantine.
- [x] 233: Core deposit/withdraw с durable native protocol, без денежных drops.
- [x] 235: DiamondShop — native recovery и согласованность рынка/лимитов.
- [x] 236: Claims — финансовые source-переходы и recovery системной аренды.
- [ ] DailyRewards: обычные/VIP денежные награды в /bal, frozen once-claim.
- [ ] Treasures: отделение обычных алмазных roll-outcomes от именного/предметного loot.
- [ ] Повторить полный поиск прямых выдач/списаний и проверить стандартные/старые конфиги.
- [ ] Финальный SHA: Root + 29 модулей, контракт сообщений и проверка release ZIP зелёные.
- [ ] После этого только ZIP 30 JAR + `ОБНОВЛЕНИЕ.txt`, без установки и новых материалов TradeMC.

Независимый откат playerdata/банка/source не поддерживается. Native saveData
и atomic replace нескольких файлов не объявляются распределённой ACID-системой.
Аукционный nonce защищает незавершённый переход, но не является универсальным
Root receipt всех предметов после закрытия WAL. Согласованные резервные копии —
на остановленном сервере, единым комплектом, с журналами всех модулей.

## Уточнение после 233

Депозит/снятие больше не используют прежний callback. Canonical bank.json v5
резервирует только владельца, сохраняет intent до native-выдачи и latest receipt
вместе с изменением баланса. Остальные callback-пути (магазин и земельные Claims)
от этого не становятся автоматически журналируемыми. Общий релиз остаётся закрыт.

## Этап 234 — хранилище DiamondShop, gate остаётся открытым

Старые денежные входы сохраняются точно (BigDecimal), округление вверх делается
до любого double-preview; удалены неиспользуемые мутации второго кошелька.
ShopStore получил строгий all-or-nothing reader и общий монитор для snapshot,
force+atomic writer и dirty ACK, с карантином IO/runtime-неопределённости.
[Подробности и границы 234](../releases/1.4.234.md).

**Это ещё не durable-сделка магазина**: сохранённый intent, Core/playerdata proof
и идемпотентные stock/статистика/принятое UTC-окно остаются следующим gate.
Общий релиз по-прежнему заблокирован.

## Этап 235 — DiamondShop gate закрыт

Старый callback удалён из ShopService. Сохранённая заявка рынка связывается с
canonical bank.json v6 SHOP-intent и резервом владельца, native nonce/checkpoint,
квитанцией Core и однократным stock/статистикой/принятым UTC-окном. API 23.
Финансовая неопределённость не компенсируется guessed refunds; Core MISSING после
отказа intake позволяет отменить только ещё не принятую заявку без денег/предметов.
[Подробности 235](../releases/1.4.235.md). Claims, DailyRewards и Treasures loot
остаются gates общего релиза.

## Этап 236 — Claims

Земля, налог/выручка, суточные списания, долг и аренда имеют единый frozen
domain ACK. Системное снятие за долг входит в тот же переход. Неопределённость
не объявляется нехваткой; WAL блокирует interleaving и старый autosave. Native
недостача до заявки отдельно пополняет личный /bal и остаётся в нём при отказе.
[API 24, схемы, тесты и эксплуатационные границы](../releases/1.4.236.md).
Открыты DailyRewards, Treasures plain loot, итоговый recensus и общий релиз.
