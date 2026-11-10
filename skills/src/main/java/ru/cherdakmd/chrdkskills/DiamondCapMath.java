package ru.cherdakmd.chrdkskills;

import java.util.Locale;

/**
 * Персональный дневной лимит физического дропа алмазов. Чистая арифметика без
 * Bukkit: все входные данные приходят record'ами, поэтому шкала целиком покрыта
 * JUnit и её можно показать игроку в {@code /profile} даже оффлайн.
 *
 * <p>Формула аддитивная (решение владельца, 7 ноября 2026):</p>
 * <pre>{@code
 * лимит = база (diamonds.daily-cap)
 *       + ступень ранга × k + %опыта ранга × k + %добычи ранга × k
 *       + extraSkill ранга × k
 *       + ступени престижа × k
 *       + (суммарный уровень / 10) × k
 *       + активный Pass + активный буст наград + донатный entitlement
 * }</pre>
 *
 * <p>Итог зажимается в {@code 1..min(settings.max, DiamondDrops.MAX_DAILY_CAP)}:
 * кривой конфиг не продаёт бесконечную экономику. Никаких умножителей базы —
 * владелец видит в конфиге ровно те числа, которые прибавляются.</p>
 */
public final class DiamondCapMath {

    /** Максимум суммарного уровня: 10 навыков по 100. */
    public static final int MAX_TOTAL_LEVEL = 1000;
    /** Максимум суммы ступеней престижа (см. PrestigeMath.MAX_TOTAL). */
    public static final int MAX_PRESTIGE = 100;

    private DiamondCapMath() {
    }

    /** Коэффициенты надбавок; все клампятся, чтобы YAML не смог сломать баланс. */
    public record Settings(
            int perRankLevel,
            int perRankXpPercent,
            int perRankDropPercent,
            int perRankExtraSkill,
            int perPrestige,
            int perTenLevels,
            int passBonus,
            int boostBonus,
            int entitlementBonus,
            String entitlementId,
            int max) {

        /** Потолок одного коэффициента: выше него сумма всё равно упрётся в max. */
        public static final int MAX_BONUS_LIMIT = 4096;

        public Settings {
            perRankLevel = clamp(perRankLevel);
            perRankXpPercent = clamp(perRankXpPercent);
            perRankDropPercent = clamp(perRankDropPercent);
            perRankExtraSkill = clamp(perRankExtraSkill);
            perPrestige = clamp(perPrestige);
            perTenLevels = clamp(perTenLevels);
            passBonus = clamp(passBonus);
            boostBonus = clamp(boostBonus);
            entitlementBonus = clamp(entitlementBonus);
            entitlementId = entitlementId == null ? "" : entitlementId.trim().toLowerCase(Locale.ROOT);
            max = (int) Math.max(1L, Math.min(DiamondDrops.MAX_DAILY_CAP, max));
        }

        public static final Settings DEFAULT = new Settings(
                32, 1, 2, 4, 4, 2, 64, 64, 32, "diamond-cap-boost", 4096);

        private static int clamp(int value) {
            return Math.max(0, Math.min(MAX_BONUS_LIMIT, value));
        }
    }

    /** Срез прокачки игрока на момент расчёта. */
    public record Context(
            int rankLevel,
            int rankXpPercent,
            int rankDropPercent,
            int rankExtraSkill,
            int totalPrestige,
            int totalLevel,
            boolean passActive,
            boolean boostActive,
            boolean entitlementActive) {

        public Context {
            rankLevel = Math.max(0, Math.min(5, rankLevel));
            rankXpPercent = Math.max(0, Math.min(50, rankXpPercent));
            rankDropPercent = Math.max(0, Math.min(25, rankDropPercent));
            rankExtraSkill = Math.max(0, Math.min(10, rankExtraSkill));
            totalPrestige = Math.max(0, Math.min(MAX_PRESTIGE, totalPrestige));
            totalLevel = Math.max(0, Math.min(MAX_TOTAL_LEVEL, totalLevel));
        }

        /** Игрок без ранга и прокачки: лимит равен базе. */
        public static final Context NONE = new Context(0, 0, 0, 0, 0, 0,
                false, false, false);
    }

    /** Разбор лимита по источникам — для профиля, отладки и тестов. */
    public record Breakdown(int base, int rank, int prestige, int levels,
                            int pass, int boost, int entitlement, int total) {
    }

    public static int compute(int base, Settings settings, Context context) {
        return breakdown(base, settings, context).total();
    }

    public static Breakdown breakdown(int base, Settings settings, Context context) {
        Settings safe = settings == null ? Settings.DEFAULT : settings;
        Context ctx = context == null ? Context.NONE : context;
        int safeBase = Math.max(1, base);

        int rank = safe.perRankLevel() * ctx.rankLevel()
                + safe.perRankXpPercent() * ctx.rankXpPercent()
                + safe.perRankDropPercent() * ctx.rankDropPercent()
                + safe.perRankExtraSkill() * ctx.rankExtraSkill();
        int prestige = safe.perPrestige() * ctx.totalPrestige();
        int levels = safe.perTenLevels() * (ctx.totalLevel() / 10);
        int pass = ctx.passActive() ? safe.passBonus() : 0;
        int boost = ctx.boostActive() ? safe.boostBonus() : 0;
        int entitlement = ctx.entitlementActive() ? safe.entitlementBonus() : 0;

        // long на промежуточном шаге: сумма коэффициентов не переполняет int.
        long raw = (long) safeBase + rank + prestige + levels + pass + boost + entitlement;
        int ceiling = Math.max(1, Math.min(safe.max(), DiamondDrops.MAX_DAILY_CAP));
        int total = (int) Math.max(1L, Math.min(ceiling, raw));
        return new Breakdown(safeBase, rank, prestige, levels, pass, boost,
                entitlement, total);
    }
}
