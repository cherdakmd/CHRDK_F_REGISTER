package ru.cherdakmd.chrdkorders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Генератор заказов и мелкая арифметика доски (1.4.154).
 *
 * <p>Класс намеренно чистый: ни Bukkit, ни файлов. Всё случайное приходит через
 * {@link Random}, поэтому «сколько просят», «сколько платят» и «кому достаётся
 * заказ» проверяются юнит-тестами с фиксированным зерном. Наградная формула
 * тоже здесь: {@code amount × rewardPerItem × premium × (1 ± jitter)}; так
 * админ, меняя наценку, видит один и тот же расчёт в игре и в тесте.</p>
 */
public final class OrderBoard {

    /** Один сгенерированный заказ до присвоения номера и срока. */
    public record Draft(OrderBot bot, OrderCategory category, OrderItem item, int amount,
                        int rewardDiamonds, int rewardPassDays) {
    }

    private OrderBoard() {
    }

    /**
     * Собирает заказ: заказчик → категория → материал → количество → награда.
     *
     * @param bots        все боты из конфига
     * @param categories  категории по иду
     * @param premium     наценка бота (1.0 — «по цене»)
     * @param jitter      разброс награды в процентах (0..50)
     * @param minReward   нижняя граница награды в алмазах
     * @param maxReward   верхняя граница награды в алмазах
     * @return заказ или {@code null}, если в конфиге нет рабочих ботов и категорий
     */
    public static Draft draft(List<OrderBot> bots, Map<String, OrderCategory> categories,
                              double premium, int jitter, int minReward, int maxReward,
                              Random random) {
        if (bots == null || bots.isEmpty() || categories == null || categories.isEmpty()) {
            return null;
        }
        Random rng = random == null ? new Random() : random;
        List<OrderBot> candidates = new ArrayList<>();
        for (OrderBot bot : bots) {
            if (pickCategory(bot, categories, rng) != null) {
                candidates.add(bot);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        OrderBot bot = candidates.get(rng.nextInt(candidates.size()));
        OrderCategory category = pickCategory(bot, categories, rng);
        if (category == null) {
            return null;
        }
        OrderItem item = category.pick(rng);
        if (item == null) {
            return null;
        }
        int amount = item.amount(rng);
        int reward = reward(item, amount, premium, jitter, minReward, maxReward, rng);
        int passDays = passDays(category, rng);
        return new Draft(bot, category, item, amount, reward, passDays);
    }

    /**
     * Награда за партию: цена предметов, наценка бота и разброс.
     *
     * <p>Округление идёт «вниз после прибавки половины» (обычное
     * {@link Math#round(double)}), поэтому заказ на один алмаз не превращается
     * в ноль. Итог зажимается в границы, чтобы наценка не разгоняла экономику.</p>
     */
    public static int reward(OrderItem item, int amount, double premium, int jitter,
                             int minReward, int maxReward, Random random) {
        if (item == null) {
            return Math.max(1, minReward);
        }
        double raw = item.rawReward(amount);
        double multiplier = Double.isFinite(premium) ? Math.clamp(premium, 0.0, 10.0) : 1.0;
        int spread = Math.clamp(jitter, 0, 50);
        double factor = 1.0;
        if (spread > 0) {
            Random rng = random == null ? new Random() : random;
            factor += (rng.nextDouble() * 2.0 - 1.0) * spread / 100.0;
        }
        long reward = Math.round(raw * multiplier * factor);
        int low = Math.max(1, minReward);
        int high = Math.max(low, Math.min(maxReward, 576));
        return (int) Math.clamp(reward, low, high);
    }

    /** Дни проходки по шансу категории; 0 — заказ обычный. */
    public static int passDays(OrderCategory category, Random random) {
        if (category == null || category.passDays() <= 0 || category.passChance() <= 0.0) {
            return 0;
        }
        Random rng = random == null ? new Random() : random;
        return rng.nextDouble() * 100.0 < category.passChance() ? category.passDays() : 0;
    }

    /**
     * Категория из списка бота по весам. Бот без рабочих категорий возвращает
     * {@code null} и в выдачу не попадает.
     */
    public static OrderCategory pickCategory(OrderBot bot, Map<String, OrderCategory> categories,
                                             Random random) {
        if (bot == null || categories == null || categories.isEmpty()) {
            return null;
        }
        List<OrderCategory> pool = new ArrayList<>();
        double total = 0.0;
        for (String id : bot.categories()) {
            OrderCategory category = categories.get(id);
            if (category != null && category.usable()) {
                pool.add(category);
                total += category.weight();
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        if (random == null) {
            return pool.get(0);
        }
        double roll = random.nextDouble() * total;
        double passed = 0.0;
        for (OrderCategory category : pool) {
            passed += category.weight();
            if (roll < passed) {
                return category;
            }
        }
        // Погрешность double: последняя категория забирает остаток.
        return pool.get(pool.size() - 1);
    }

    /**
     * Превращает набросок в заказ с номером и сроком.
     *
     * @param ttlMillis базовый срок жизни; настоящий — 75..125 % от него,
     *                  чтобы заказы не уходили все разом
     */
    public static BotOrder toOrder(Draft draft, String id, long now, long ttlMillis, Random random) {
        if (draft == null) {
            return null;
        }
        Random rng = random == null ? new Random() : random;
        long ttl = Math.max(60_000L, ttlMillis);
        long life = Math.round(ttl * (0.75 + rng.nextDouble() * 0.5));
        return new BotOrder(id, draft.bot().id(), draft.bot().name(), draft.category().id(),
                draft.item().material().name(), draft.amount(), draft.rewardDiamonds(),
                draft.rewardPassDays(), draft.category().minReputation(),
                draft.category().reputation(), now, now + life);
    }

    /**
     * Человеческий остаток срока: «7 ч», «7 ч 20 мин», «12 мин», «меньше
     * минуты». Пустая строка — заказ бессрочный или уже истёк.
     */
    public static String formatDuration(long millis) {
        if (millis <= 0L) {
            return "";
        }
        long minutes = millis / 60_000L;
        if (minutes <= 0L) {
            return "меньше минуты";
        }
        if (minutes < 60L) {
            return minutes + " мин";
        }
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        return rest == 0L ? hours + " ч" : hours + " ч " + rest + " мин";
    }

    /** Номер заказа из счётчика: короткий и удобный для команды. */
    public static String nextId(long counter) {
        return Long.toString(Math.max(1L, counter));
    }
}
