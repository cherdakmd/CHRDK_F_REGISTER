package ru.cherdakmd.chrdkenchants;

/**
 * Числовые параметры всех чар (единый источник истины; индекс массива = уровень-1).
 * Чистые константы — в тестах проверяются без сервера.
 *
 * <p>1.4.128: добавлены 15 светлых чар и 15 проклятий. У проклятий те же методы,
 * но значения всегда «в минус» для игрока: обработчик сам решает, вычитать
 * процент или прибавлять.</p>
 */
public final class EnchantParams {

    private EnchantParams() {
    }

    /** Процент шанса срабатывания (громобой, паралич, мидас). */
    public static int chancePercent(String id, int level) {
        return switch (id) {
            case "thunderstrike" -> at(new int[]{10, 15, 20}, level);
            case "paralyze" -> at(new int[]{10, 14}, level);
            case "midas" -> 10;
            default -> 0;
        };
    }

    /** Проценты (вампиризм — от урона, берсерк — прибавка, ретрибьюкшн — возврат). */
    public static int percent(String id, int level) {
        return switch (id) {
            case "lifesteal" -> at(new int[]{10, 15, 20}, level);
            case "berserk" -> at(new int[]{10, 15, 20}, level);
            case "retribution" -> at(new int[]{8, 12, 16}, level);
            case "fortified" -> at(new int[]{6, 9, 12}, level);
            case "headhunter" -> 15;
            // --- светлые чары 1.4.128 ---
            case "keen_edge" -> at(new int[]{4, 6, 8}, level);
            case "duelist" -> at(new int[]{8, 12, 16}, level);
            case "steady_aim" -> at(new int[]{8, 12, 16}, level);
            case "quickdraw" -> at(new int[]{8, 12, 16}, level);
            case "bulwark" -> at(new int[]{5, 7, 9}, level);
            case "second_skin" -> at(new int[]{4, 6, 8}, level);
            case "grounded" -> at(new int[]{10, 15, 20}, level);
            case "featherstep" -> at(new int[]{8, 12, 16}, level);
            case "scholar" -> at(new int[]{5, 8, 11}, level);
            case "plunder" -> at(new int[]{10, 15, 20}, level);
            case "frenzy" -> at(new int[]{10, 15, 20}, level);
            case "fortunate" -> at(new int[]{10, 18}, level);
            case "prospector" -> at(new int[]{8, 12, 16}, level);
            // --- проклятия 1.4.128 ---
            case "dull_blade" -> at(new int[]{5, 8, 11}, level);
            case "weak_draw" -> at(new int[]{8, 12, 16}, level);
            case "sluggish_shot" -> at(new int[]{10, 20}, level);
            case "brittle" -> at(new int[]{15, 25, 35}, level);
            case "thin_padding" -> at(new int[]{4, 6, 8}, level);
            case "exposed" -> at(new int[]{8, 12, 16}, level);
            case "heavy_hull" -> at(new int[]{10, 15, 20}, level);
            case "fire_prone" -> at(new int[]{15, 25, 35}, level);
            case "fragile_bones" -> at(new int[]{10, 15, 20}, level);
            case "gluttony" -> at(new int[]{20, 35, 50}, level);
            case "slow_learner" -> at(new int[]{10, 18, 25}, level);
            case "cursed_blood" -> at(new int[]{25, 50, 75}, level);
            case "clumsy_swing" -> at(new int[]{8, 12, 16}, level);
            case "dull_pick" -> at(new int[]{10, 18, 25}, level);
            case "jinxed" -> at(new int[]{8, 14, 20}, level);
            default -> 0;
        };
    }

    /** Дробные величины: регенерация «Второго дыхания» — сердца за импульс. */
    public static double fraction(String id, int level) {
        return switch (id) {
            case "vitality" -> at(new double[]{0.5, 0.75, 1.0}, level);
            default -> 0.0;
        };
    }

    /** Уровень эффекта «Спешка» для frenzy/prospector: I, затем II (не III). */
    public static int hasteAmplifier(int level) {
        return Math.min(1, Math.max(0, level - 1));
    }

    /** Множитель урона (казнь). */
    public static double multiplier(String id, int level) {
        return switch (id) {
            case "execution" -> at(new double[]{2.0, 2.5}, level);
            default -> 1.0;
        };
    }

    /** Длительность в секундах (кровоподтёки, заморозки, баффы, свечение). */
    public static int seconds(String id, int level) {
        return switch (id) {
            case "bleeding" -> at(new int[]{2, 3, 4}, level);
            case "slayer" -> at(new int[]{6, 10}, level);
            case "frostarrow" -> at(new int[]{4, 6}, level);
            case "tracer" -> 10;
            case "lightningrod" -> 10;
            case "iceward" -> at(new int[]{2, 3}, level);
            case "phoenix-resist" -> 5;
            case "laststand" -> 25;
            // 1.4.128: светлая чара «Лёгкая поступь» — скорость после убийства
            case "lightfoot" -> at(new int[]{4, 6, 8}, level);
            default -> 0;
        };
    }

    /** Кулдауны в секундах (спасители). */
    public static int cooldownSeconds(String id) {
        return switch (id) {
            case "phoenix" -> 300;
            case "laststand" -> 240;
            default -> 0;
        };
    }

    /** Прочие значения. */
    public static int value(String id, int level) {
        return switch (id) {
            case "chainspark" -> at(new int[]{1, 2}, level);            // число целей цепи
            case "veinminer" -> at(new int[]{6, 10, 14}, level);        // предел блоков жилы
            case "souleater" -> at(new int[]{1, 2}, level);             // куски сытости
            case "stormarrow" -> at(new int[]{2, 3, 4}, level);         // урон аоэ (полсерча)
            case "explosive" -> at(new int[]{1, 2}, level);             // сила взрыва (×0.8)
            case "sniper" -> at(new int[]{3, 5, 7}, level);             // % за 10 блоков
            case "sniper-cap" -> at(new int[]{45, 60, 75}, level);      // потолок бонуса
            default -> 0;
        };
    }

    /** Урон, наносимый молнией громобоя (пол-сердецко на 1 единицу = 2). */
    public static double thunderDamage() {
        return 4.0;
    }

    private static int at(int[] table, int level) {
        return table[Math.min(Math.max(0, level - 1), table.length - 1)];
    }

    private static double at(double[] table, int level) {
        return table[Math.min(Math.max(0, level - 1), table.length - 1)];
    }
}
