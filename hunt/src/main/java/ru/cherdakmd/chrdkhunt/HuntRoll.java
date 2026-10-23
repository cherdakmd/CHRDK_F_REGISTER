package ru.cherdakmd.chrdkhunt;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Броски добычи охоты (1.4.155).
 *
 * <p>Класс намеренно чистый: ни Bukkit-событий, ни файлов. Всё случайное
 * приходит через {@link Random}, поэтому «сколько монет», «выпал ли осколок» и
 * «что в сундуке» проверяются юнит-тестами с фиксированным зерном. Награда за
 * убийство и содержимое сундука считаются в одном месте — в игре и в тесте
 * получается один и тот же результат.</p>
 */
public final class HuntRoll {

    private HuntRoll() {
    }

    /** Целое в диапазоне; перевёрнутые границы не ломают бросок. */
    public static int between(Random random, int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        if (low == high) {
            return low;
        }
        Random rng = random == null ? new Random() : random;
        return low + rng.nextInt(high - low + 1);
    }

    /** Сработал ли шанс в процентах. */
    public static boolean chance(Random random, double percent) {
        if (percent >= 100.0) {
            return true;
        }
        if (percent <= 0.0) {
            return false;
        }
        Random rng = random == null ? new Random() : random;
        return rng.nextDouble() * 100.0 < percent;
    }

    /**
     * Награда за убийство моба.
     *
     * <p>Монеты: случайное число из правила, умноженное на множитель семейства
     * и округлённое. Моб, у которого минимум больше нуля, всегда даёт хотя бы
     * одну монету — иначе «награда за охоту» выглядела бы поломкой. Осколок
     * выпадает по шансу, ключ — по своему шансу, поверх идёт ванильный дроп.</p>
     */
    public static KillResult kill(Random random, MobRule rule, HuntFamily family) {
        if (rule == null) {
            return new KillResult(0L, 0, 0, List.of());
        }
        Random rng = random == null ? new Random() : random;
        double multiplier = family == null ? 1.0 : family.coinMultiplier();
        long coins = 0L;
        if (rule.maxCoins() > 0) {
            int raw = between(rng, rule.minCoins(), rule.maxCoins());
            coins = Math.round(raw * multiplier);
            if (raw > 0) {
                coins = Math.max(1L, coins);
            }
        }
        int shards = 0;
        if (rule.givesShards() && chance(rng, rule.shardChance())) {
            shards = between(rng, Math.max(1, rule.minShards()), rule.maxShards());
        }
        int keys = rule.givesKey() && chance(rng, rule.keyChance()) ? 1 : 0;
        List<HuntDrop> drops = rollLoot(rng, rule.loot());
        return new KillResult(coins, shards, keys, drops);
    }

    /** Содержимое сундука за один ключ. */
    public static ChestResult chest(Random random, HuntChest chest) {
        if (chest == null) {
            return new ChestResult(0L, 0, 0, List.of());
        }
        Random rng = random == null ? new Random() : random;
        long coins = chest.maxCoins() > 0
                ? between(rng, chest.minCoins(), chest.maxCoins()) : 0L;
        int shards = chest.maxShards() > 0
                ? between(rng, chest.minShards(), chest.maxShards()) : 0;
        int diamonds = 0;
        if (chest.givesDiamonds() && chance(rng, chest.diamondChance())) {
            diamonds = between(rng, Math.max(1, chest.minDiamonds()), chest.maxDiamonds());
        }
        List<HuntDrop> drops = rollLoot(rng, chest.loot());
        return new ChestResult(coins, shards, diamonds, drops);
    }

    /** Отбирает выпавшие вещи: каждый элемент списка бросает свой шанс. */
    public static List<HuntDrop> rollLoot(Random random, List<HuntLoot> loot) {
        if (loot == null || loot.isEmpty()) {
            return List.of();
        }
        Random rng = random == null ? new Random() : random;
        List<HuntDrop> out = new ArrayList<>();
        for (HuntLoot entry : loot) {
            if (entry != null && entry.rolls(rng)) {
                out.add(new HuntDrop(entry.material(), entry.amount(rng)));
            }
        }
        return List.copyOf(out);
    }
}
