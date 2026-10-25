# Аудит 1.4.25 — поиск багов класса «API-налом» по всему моно-репо

Контекст: в 1.4.24 починили краш `CHRDK_TierForge` — чтение
`ItemMeta#getEnchantmentGlintOverride()` без `hasEnchantmentGlintOverride()`
(контракт API 1.21.5+). Ниже — полный проход по всем 9 модулям на похожие
ловушки: незащищённые геттеры состояния, устаревшие/ломающиеся API и
паттерны, которые всплывали в прошлых инцидентах.

## Проведённые проверки и результат

### 1. Геттеры состояния без «has»-проверки (класс бага 1.4.24)
- **`getEnchantmentGlintOverride()`** — единственный геттер в проекте, уже
  защищён в 1.4.24. Добавлен CI-линтер `glint-contract`
  (`.github/scripts/check_messages.py`), который падёт, если паттерн вернётся.
- Аналогичных API-контрактов (`ItemMeta#hasBlockData` перед `getBlockData`,
  `hasCustomModelData` и т.д.) в коде нет — греп по всем модулям пуст.

### 2. Атрибуты 1.21 (AttributeModifier / EquipmentSlotGroup / Attribute enum)
- **`TierItems.add`** — обёрнут в `try { addAttributeModifier } catch
  (IllegalArgumentException)` с предупреждением в лог: несовместимость
  атрибут/слот больше не роняет крафт.
- Все обращения к атрибутам (`Attribute.MAX_HEALTH` и др.) — по новому enum
  API 1.21, null-безопасны (тернарные проверки).
- `Attribute.MAX_HEALTH` нигде не читают через `getAttribute(...).getValue()`
  без null-проверки — подтверждено в `artifacts`, `enchants`, `tier-forge`.

### 3. Purpur-специфичные переопределения
- `TotpQrMapService.MapRenderer` — `isExplorerMap()` переопределён (требование
  Purpur 1.21). Единственный MapRenderer в проекте.

### 4. MerchantRecipe
- Единственное использование (`enchants/VillagerListener`) — полный
  8-аргументный конструктор `(result, uses, maxUses, xpReward, villagerXp,
  priceMultiplier, demand, specialPrice)`. Соответствует стандартам проекта.

### 5. PDC-доступ к ItemMeta без `hasItemMeta()`
- `ArtifactItems.idOf` — проверка `item.hasItemMeta()` перед чтением PDC.
- Остальные `getItemMeta()` в проекте — сразу после `new ItemStack(...)` или
  после проверки материала, без чтения через старую обёртку.

### 6. Асинхронные сохранения / конкурентность
- `ClaimStore` — все мутаторы `synchronized`, `saveAsync` маркирует через
  volatile-флаг `dirty` до перехода в async-нить. Гонок нет.
- `AccessManager.pollDonationsSafely` — работает только со своими данными,
  Bukkit-API не трогает напрямую.

### 7. Численные края
- `/pass testpay` — сумма уже ограничена `[0; 1_000_000]`, parse-ошибки
  покрыты `error.amount-number`.
- Claims (цены, алмазные предметы) — проверки `amount <= 0`, `price > 0`
  присутствуют на входе.
- Potion-эффекты с длительностью из конфига (`EnchantParams.seconds`) — под
  админским контролем; злонамеренного игрока не касаются. Не правим.

### 8. Капча / регистрация / сессии
- Эскалационная модель — крайние случаи (пустой хост, «бесконечные» соль)
  покрыты тестами `CaptchaChallengeTest`, `RegisterPluginTest`.

### 9. plugin.yml / messages.yml контракт
- Линтер `check_messages.py` подтвердил: все модули зелёные, plugin.yml
  цитирует описания, ключи матчатся с Java-кодом.

## Выводы

Единственный баг класса «1.4.24-инцидент» в актуальном дереве отсутствует —
фикс уже зашит и покрыт линтером. Дополнительно:

1. **`TierItems.addAttributeModifier` теперь мягкий** — отказ одного бонуса
   в будущих версиях API не уронит крафт, а оставит запись в логе.
2. **CI-линтер «glint-contract»** — гарантия, что паттерн
   `get...Override()` без `has...Override()` не вернётся ни в один модуль.
3. Рекомендация на будущее: при добавлении поддержки новых версий API
   прогонять `grep -rn "\.get[A-Z][a-zA-Z]*Override()" src/` перед релизом —
   он ловит весь класс «компонент может отсутствовать» из 1.21.5+.

## Что НЕ нашлось (к сведению)

- Нет ни одного `Sound.valueOf` / `Particle.valueOf` / `Material.valueOf`
  из конфига (значит опечатка в конфиге не уронит плагин).
- Нет `getServer().getOnlinePlayers()` в горячем пути событий claims.
- Нет мест, где несохранённый offline-игрок (`Bukkit.getOfflinePlayer`)
  приводил бы к NPE — в коде claims все вызовы идут через store.
