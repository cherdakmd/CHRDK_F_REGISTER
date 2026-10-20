package ru.cherdakmd.chrdktierforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * Чистая логика кастомных чар на тир-предметах (волна 18).
 *
 * <p>Класс ничего не знает ни о Bukkit, ни о классах аддона чар: ему передают
 * списки id из {@code ChrdkEnchantsApi} и функцию максимального уровня. Так
 * выбор чары и уровня можно проверить юнит-тестами, а {@link TierItems} лишь
 * применяет готовые {@link Pick} через API.</p>
 *
 * <p>Бросок детерминирован по переданному {@link Random} (сид строится от uid
 * предмета и тира), поэтому превью крафта не переролливается.</p>
 */
public final class TierCustomEnchants {

    /** Потолок уровня кастомной чары по лестнице тиров (максимум аддона — III). */
    public static final int HARD_LEVEL_CAP = 3;

    /** Выбранная чара: id из аддона и уровень. */
    public record Pick(String id, int level) {
    }

    private TierCustomEnchants() {
    }

    /**
     * Бросок кастомных чар.
     *
     * @param tierIndex индекс тира 0..6
     * @param random    уже засеянный генератор предмета
     * @param lightIds  включённые светлые чары аддона
     * @param curseIds  включённые проклятия аддона (пусто — проклятий нет)
     * @param config    текущие настройки {@code enchantments.custom.*}
     * @param maxLevel  максимальный уровень чары по id (0 — неизвестна)
     * @return 0..{@code max-per-item} чар; пустой список — шанс не прошёл
     */
    public static List<Pick> roll(int tierIndex, Random random, List<String> lightIds,
                                  List<String> curseIds, TierEnchantConfig config,
                                  ToIntFunction<String> maxLevel) {
        if (random == null || config == null || maxLevel == null || config.customItemLimit() <= 0) {
            return List.of();
        }
        int chance = TierEnchants.chanceFor(tierIndex, config.customChancePercent());
        if (chance <= 0 || random.nextInt(100) >= chance) {
            return List.of();
        }
        List<String> light = lightIds == null ? List.of() : lightIds;
        List<String> curses = curseIds == null ? List.of() : curseIds;
        boolean curseRoll = config.cursesAllowedAt(tierIndex)
                && !curses.isEmpty()
                && random.nextInt(100) < config.customCurseChancePercent();
        List<String> pool = new ArrayList<>(curseRoll ? curses : light);
        if (pool.isEmpty()) {
            return List.of();
        }
        Collections.shuffle(pool, random);
        int limit = Math.min(config.customItemLimit(), 2);
        int cap = TierEnchants.levelCap(tierIndex, HARD_LEVEL_CAP);
        List<Pick> result = new ArrayList<>(limit);
        for (String id : pool) {
            if (result.size() >= limit) {
                break;
            }
            int max = maxLevel.applyAsInt(id);
            if (max <= 0) {
                continue;
            }
            int level = 1 + random.nextInt(Math.max(1, Math.min(cap, max)));
            result.add(new Pick(id, level));
        }
        return List.copyOf(result);
    }
}
