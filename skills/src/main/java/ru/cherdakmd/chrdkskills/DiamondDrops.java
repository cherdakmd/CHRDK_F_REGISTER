package ru.cherdakmd.chrdkskills;

/**
 * Формула выпадения алмазов предметом из блоков и мобов.
 *
 * <p>Класс без Bukkit-типов и без состояния: всё считается из параметров и
 * одного броска {@code dice}, поэтому баланс проверяется юнит-тестами без
 * сервера. Решение владельца от 6 ноября 2026: платный ранг <b>осознанно</b>
 * влияет и на шанс, и на количество алмазов; потолки зашиты здесь, чтобы
 * конфиг не смог продать бесконечную экономику.</p>
 *
 * <p>Алмазы приходят <b>физическим предметом</b> в мир, а не деньгами в
 * {@code /bal}: существующий {@code MilestoneAwards} остаётся разовым
 * источником вех и не смешивается с новым лутом.</p>
 */
public final class DiamondDrops {

    /** Потолки контракта: выше них конфиг поднять значения не может. */
    public static final double MAX_CHANCE_LIMIT = 0.50;
    public static final int MAX_AMOUNT_LIMIT = 16;
    /**
     * Жёсткий потолок персонального дневного лимита. С 7 ноября 2026 вырос
     * с 1024 до 4096: лимит стал аддитивно зависеть от ранга, престижа и
     * прочей прокачки (см. {@link DiamondCapMath}), и прежний потолок срезал бы
     * верхние ступени. Конфиг всё равно не может пробить это значение.
     */
    public static final int MAX_DAILY_CAP = 4096;
    /** Сколько ступеней престижа вообще влияют на формулу. */
    public static final int MAX_PRESTIGE = 10;

    /**
     * Настройки дропа. Все значения клампятся в конструкторе: кривой конфиг
     * не должен превратить шанс в гарантию или выдать отрицательный стак.
     */
    public record Settings(
            boolean enabled,
            double baseChance,
            double levelChanceBonus,
            double rankChancePerLevel,
            double rankXpChanceBonus,
            double prestigeChance,
            double maxChance,
            int baseAmount,
            int levelAmountBonus,
            int prestigeAmountBonus,
            int maxAmount,
            int dailyCap,
            double mobChanceFactor,
            int mobAmountBonus) {

        public Settings {
            baseChance = clamp(baseChance, 0.0, 1.0);
            levelChanceBonus = clamp(levelChanceBonus, 0.0, 1.0);
            rankChancePerLevel = clamp(rankChancePerLevel, 0.0, 0.20);
            rankXpChanceBonus = clamp(rankXpChanceBonus, 0.0, 0.20);
            prestigeChance = clamp(prestigeChance, 0.0, 0.20);
            maxChance = clamp(maxChance, 0.0, MAX_CHANCE_LIMIT);
            baseAmount = (int) clamp(baseAmount, 0, MAX_AMOUNT_LIMIT);
            levelAmountBonus = (int) clamp(levelAmountBonus, 0, MAX_AMOUNT_LIMIT);
            prestigeAmountBonus = (int) clamp(prestigeAmountBonus, 0, MAX_AMOUNT_LIMIT);
            maxAmount = (int) clamp(maxAmount, 1, MAX_AMOUNT_LIMIT);
            dailyCap = (int) clamp(dailyCap, 1, MAX_DAILY_CAP);
            mobChanceFactor = clamp(mobChanceFactor, 0.0, 5.0);
            mobAmountBonus = (int) clamp(mobAmountBonus, 0, MAX_AMOUNT_LIMIT);
        }

        /** База лимита 256 (было 64); сверху считается {@link DiamondCapMath}. */
        public static final Settings DEFAULT = new Settings(
                true, 0.01, 0.04, 0.01, 0.03, 0.01, 0.20,
                1, 2, 1, 4, 256, 1.5, 1);

        /**
         * Персональный снимок настроек с подставленным дневным лимитом игрока.
         * Шанс и количество не меняются — прокачка влияет только на дневной
         * бюджет, а не на силу одного выпадения.
         */
        public Settings withDailyCap(int newCap) {
            return new Settings(enabled, baseChance, levelChanceBonus,
                    rankChancePerLevel, rankXpChanceBonus, prestigeChance, maxChance,
                    baseAmount, levelAmountBonus, prestigeAmountBonus, maxAmount,
                    newCap, mobChanceFactor, mobAmountBonus);
        }
    }

    /** Результат одного броска. {@code amount == 0} — алмаз не выпал. */
    public record Result(int amount, double chance, String reason) {

        public boolean dropped() {
            return amount > 0;
        }

        static Result miss(double chance, String reason) {
            return new Result(0, chance, reason);
        }
    }

    private DiamondDrops() {
    }

    /**
     * Один бросок.
     *
     * @param level          уровень профильного навыка (1..maxLevel)
     * @param maxLevel       потолок кривой (100)
     * @param prestige       ступени престижа этого навыка
     * @param rankLevel      ступень платного ранга (0..5)
     * @param rankXpPercent  {@code RankBonus.xpPercent()} — влияет на шанс
     * @param rankDropPercent {@code RankBonus.dropPercent()} — влияет на количество
     * @param droppedToday   сколько алмазов уже выпало игроку за сегодня
     * @param mob            {@code true} для смерти моба, {@code false} для блока
     * @param dice           случайное число [0,1), приходит из вызывающего кода
     */
    public static Result roll(int level, int maxLevel, int prestige, int rankLevel,
                              int rankXpPercent, int rankDropPercent,
                              long droppedToday, boolean mob, double dice, Settings settings) {
        if (settings == null || !settings.enabled()) {
            return Result.miss(0.0, "disabled");
        }
        long remaining = settings.dailyCap() - Math.max(0L, droppedToday);
        if (remaining <= 0L) {
            return Result.miss(0.0, "daily-cap");
        }
        double ratio = levelRatio(level, maxLevel);
        int safeRank = (int) clamp(rankLevel, 0, 5);
        int safePrestige = (int) clamp(prestige, 0, MAX_PRESTIGE);

        // Ранг влияет на шанс двумя независимыми частями контракта RankBonus:
        // ступенью и процентом опыта. Количество — процентом добычи.
        double chance = settings.baseChance()
                + settings.levelChanceBonus() * ratio
                + settings.rankChancePerLevel() * safeRank
                + settings.rankXpChanceBonus() * clamp(rankXpPercent, 0, 50) / 50.0
                + settings.prestigeChance() * safePrestige;
        if (mob) {
            chance *= settings.mobChanceFactor();
        }
        chance = clamp(chance, 0.0, settings.maxChance());
        if (!Double.isFinite(dice) || dice < 0.0 || dice >= 1.0 || dice >= chance) {
            return Result.miss(chance, dice >= 1.0 || dice < 0.0 || !Double.isFinite(dice)
                    ? "bad-dice" : "miss");
        }

        int rankAmount = rankDropPercent >= 25 ? 2 : rankDropPercent >= 10 ? 1 : 0;
        int amount = settings.baseAmount()
                + (int) Math.round(ratio * settings.levelAmountBonus())
                + rankAmount
                + (int) Math.floor(safePrestige / 2.0) * settings.prestigeAmountBonus()
                + (mob ? settings.mobAmountBonus() : 0);
        amount = (int) clamp(amount, 0, settings.maxAmount());
        amount = (int) Math.min(amount, remaining);
        if (amount <= 0) {
            return Result.miss(chance, "daily-cap");
        }
        return new Result(amount, chance, "ok");
    }

    /** Доля пути 1..maxLevel, защищённая от деления на ноль. */
    public static double levelRatio(int level, int maxLevel) {
        int top = Math.max(2, maxLevel);
        return clamp((Math.max(1, level) - 1) / (double) (top - 1), 0.0, 1.0);
    }

    /** Ключ дня для дневного лимита: календарная дата в Europe/Moscow. */
    public static String today() {
        return java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).toString();
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.min(max, Math.max(min, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.min(max, Math.max(min, value));
    }
}
