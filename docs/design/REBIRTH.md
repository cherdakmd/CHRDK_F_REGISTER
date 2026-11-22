# Перерождение (п.30): дизайн и спецификация

Статус: реализовано в `skills` (PR #119). Заменяет систему престижа
(`docs/design/PRESTIGE.md` остаётся историей п.17/1.5.2).

## 1. Суть

Когда **все десять навыков** на 100-м уровне (`RebirthMath.ready`), игрок
может полностью переродиться: уровни всех навыков, атрибуты и ветки билда
сбрасываются под ноль (`SkillStore.resetForRebirth`), взамен — постоянные
награды. Операция повторяется многократно.

Команда: `/skills rebirth confirm` (двухшаговое подтверждение через
`EconomyConfirmations`, TTL 30 секунд), `/skills rebirth` — вкладка меню,
`/skills rebirthtop` — топ.

## 2. Множитель опыта (убывающая отдача)

`RebirthMath.xpBonusPercent(n) = min(cap, percent × (1 − decayⁿ) / (1 − decay))`:

| Параметр конфига | Дефолт | Вилка |
| --- | --- | --- |
| `rebirth.xp-percent-per-rebirth` | 5.0 | 0…25 |
| `rebirth.xp-decay` | 0.9 | 0.5…0.99 |
| `rebirth.xp-bonus-cap-percent` | 50.0 | 0…200 |

Дефолты: n=1 → +5 %, n=10 → ≈ +32.5 %, асимптота +50 % (потолок прежнего
`×1.5`). Множитель `xpFactor = 1 + bonus/100` общий для всех навыков,
применяется в `SkillService.award` после бустеров ядра и до клампа действия.
Перки от престижа (`perk-per-step`) удалены — множитель питает только XP.

## 3. Награды

За каждое перерождение (технический потолок счётчика — 1 000 000):

1. Алмазы на банковский счёт — `rebirth.diamonds-per-rebirth` (дефолт 32,
   потолок 256 базовых единиц в `RebirthMath.rebirthDiamonds`, далее банковский курс).
2. Случайный **уникальный предмет** из пула `rebirth.unique-items`
   (id/material/name/lore). Каждый id выдаётся игроку один раз
   (`skills.json` → `rebirthUniques`); исчерпание пула — удвоенные алмазы.

Вехи (`RebirthMath.milestone`): 5, 10, 25, 50, далее каждые 25. На вехе —
доплата `rebirth.milestone-diamonds` (дефолт 128, потолок 2048), второй
уникальный предмет и анонс `rebirth-milestone-announce`
(`rebirth.announce-milestones`). Пороги отмечаются в `claimedRebirthMilestones`
один раз (прецедент legacyTiers). Денежная сумма, включая компенсацию за
исчерпанный пул, фиксируется вместе со сбросом и счётчиком в `skills.json`;
журнал `skill-rebirth-diamonds` принимает её с ключом `rebirth:<счётчик>`.
После сбоя ожидающее обязательство восстанавливается при входе игрока.
Уникальные предметы и `RebirthEvent` не участвуют в банковской транзакции;
их доставку после аварии нужно проверять отдельно.

Титул: `RebirthMath.title(n)` — «Перерождение I..X» (римские), дальше числом.

## 4. Конвертация старого престижа

При загрузке `skills.json` (одноразово, метка `rebirthConverted`):

- `rebirths = max(rebirths, fromLegacySteps(totalPrestige()))`, где
  `fromLegacySteps(steps) = steps / 10` — каждые 10 ступеней суммарно по
  навыкам дают одно перерождение (100 ступеней → 10 перерождений);
- вехи ≤ полученного счётчика помечаются выданными без выплат (задним числом
  награды не идут);
- поля `prestige`/`claimedTiers` сохраняются в файле как история.

Паритет ветерана: атрибуты `points = totalLevel/5 + 20 × rebirths`
(`AttributeMath.POINTS_PER_REBIRTH = 20` = 10 ступеней × 2) — владелец
100 ступеней получает те же 200 очков.

## 5. Контракты аддонов

`ChrdkSkillsApi.totalPrestige(player)` сохранён и теперь равен
`rebirths × 10` (прежняя шкала ступеней). Потребители без правок:
diamond-shop (`ShopCapMath`, кламп 0…100), mobs (`SkillsHook`, вес снапшота),
`DiamondCapMath.Context`, `DiamondDrops.roll`. Новые модули используют
`rebirths()`.

Ранг приёмов: `SkillAbility.UNLOCK_REBIRTH = 1`, `MASTER_REBIRTH = 10`,
`rank = clamp(rebirths, 1, 10)` — формулы силы/откатов/лимитов не менялись.

## 6. Вырезано

`PrestigeMath`, `PrestigeXpGate`, ступени по навыкам, звания и их разовые
награды, перки за ступени, `/skills balance` и правило баланса прокачки
(мягкая зона) — при перерождении все навыки сбрасываются разом, отрыв
отдельного навыка невозможен по построению. Ключи `prestige.*` и
`prestige.balance.*` config.yml заменены блоком `rebirth:`; старые тексты
вычищены remove-миграцией `messages-version: 6`.

## 7. Открытые вопросы

- Дефолт `diamonds-per-rebirth: 32` и `milestone-diamonds: 128` — предмет
  баланса экономики сервера; при необходимости правятся в config.yml.
- Уникальный пул мал (3 предмета в эталоне) — владельцы расширяют
  `rebirth.unique-items` своими трофеями.
