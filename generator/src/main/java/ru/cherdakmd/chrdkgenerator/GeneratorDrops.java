package ru.cherdakmd.chrdkgenerator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Продукция тиров — чистые роллы без Bukkit.
 *
 * <p>Каждая единица бункера при выдаче превращается в один предмет,
 * выбитый из таблицы тира по весам ({@link Drop#weight()}). Редкие
 * позиции ({@link Drop#rare()}) модуль «Удача» утяжеляет на 10% за
 * уровень — «шанс редких ресурсов» (п.31, §5). Материалы — строки
 * ({@code A-Z0-9_}), допустимость имени проверяет конфигурация.</p>
 */
final class GeneratorDrops {

    /** Позиция таблицы продукции: материал, вес, признак редкости. */
    record Drop(String material, int weight, boolean rare) {
    }

    private GeneratorDrops() {
    }

    /** Эффективный вес позиции с учётом удачи (редкие утяжеляются на 10% за уровень). */
    static int effectiveWeight(Drop drop, int luckLevel) {
        if (!drop.rare() || luckLevel <= 0) {
            return drop.weight();
        }
        return Math.max(1, (int) Math.round(drop.weight() * (1.0 + luckLevel / 10.0)));
    }

    /** Бросок одного предмета из таблицы. */
    static String roll(List<Drop> drops, int luckLevel, Random random) {
        if (drops == null || drops.isEmpty()) {
            throw new IllegalArgumentException("generator drops empty");
        }
        int total = 0;
        for (Drop drop : drops) {
            total += effectiveWeight(drop, luckLevel);
        }
        if (total <= 0) {
            throw new IllegalArgumentException("generator drops weight");
        }
        int ticket = random.nextInt(total);
        for (Drop drop : drops) {
            ticket -= effectiveWeight(drop, luckLevel);
            if (ticket < 0) {
                return drop.material();
            }
        }
        return drops.get(drops.size() - 1).material();
    }

    /**
     * Превращает единицы бункера в сводку предметов к выдаче.
     *
     * @return материал → количество (порядок первого появления)
     */
    static Map<String, Integer> materialize(long units, List<Drop> drops, int luckLevel, Random random) {
        if (units < 0) {
            throw new IllegalArgumentException("generator units: " + units);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (long i = 0; i < units; i++) {
            out.merge(roll(drops, luckLevel, random), 1, Integer::sum);
        }
        return out;
    }
}
