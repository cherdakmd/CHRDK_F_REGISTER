package ru.cherdakmd.chrdkenchants;

/**
 * Числовые параметры всех 30 чар (единый источник истины; индекс массива = уровень-1).
 * Чистые константы — в тестах проверяются без сервера.
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
            default -> 0;
        };
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
