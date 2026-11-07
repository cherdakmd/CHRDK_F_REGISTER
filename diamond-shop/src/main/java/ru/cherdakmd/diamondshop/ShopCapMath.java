package ru.cherdakmd.diamondshop;

import java.util.Locale;

/**
 * Персональный дневной лимит оборота {@code /shop} (7 ноября 2026).
 *
 * <p>Чистая арифметика без Bukkit: ранг, престиж и прочие прокачки складываются
 * <b>аддитивно</b> с базой, а не умножают её. Все коэффициенты лежат в config.yml
 * ({@code pricing.daily-cap.*}), поэтому владелец видит ровно те числа, которые
 * прибавляются к лимиту. Единица измерения — целый алмаз оборота за календарные
 * сутки UTC (окно {@link ShopStore}); дробных алмазов в магазине нет.</p>
 *
 * <p>Формула: {@code base + rankLevel*perRankLevel + xpPercent*perRankXpPercent
 * + dropPercent*perRankDropPercent + extraSkill*perRankExtraSkill
 * + prestige*perPrestige + (totalLevel/10)*perTenLevels
 * + pass*passBonus + boost*boostBonus + entitlement*entitlementBonus},
 * зажатая в {@code 1..max}. Все входные данные клампятся: некорректный или
 * отсутствующий API не может завысить лимит, а при недоступном ядре/Skills
 * игрок получает просто базу.</p>
 */
public final class ShopCapMath {

    /** Абсолютный потолок контракта: выше него config.yml поднять лимит не может. */
    public static final int HARD_MAX = 65_536;

    private ShopCapMath() {
    }

    /** Настройки лимита из {@code pricing.daily-cap}. Все значения уже зажаты. */
    public record Settings(
            boolean enabled,
            int base,
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
            int max
    ) {
        public Settings {
            base = clamp(base, 0, HARD_MAX);
            perRankLevel = clamp(perRankLevel, 0, HARD_MAX);
            perRankXpPercent = clamp(perRankXpPercent, 0, HARD_MAX);
            perRankDropPercent = clamp(perRankDropPercent, 0, HARD_MAX);
            perRankExtraSkill = clamp(perRankExtraSkill, 0, HARD_MAX);
            perPrestige = clamp(perPrestige, 0, HARD_MAX);
            perTenLevels = clamp(perTenLevels, 0, HARD_MAX);
            passBonus = clamp(passBonus, 0, HARD_MAX);
            boostBonus = clamp(boostBonus, 0, HARD_MAX);
            entitlementBonus = clamp(entitlementBonus, 0, HARD_MAX);
            entitlementId = entitlementId == null ? "" : entitlementId.trim().toLowerCase(Locale.ROOT);
            max = clamp(max, 1, HARD_MAX);
        }

        public static final Settings DEFAULT = new Settings(
                true, 2048, 256, 4, 8, 16, 16, 8, 256, 256, 128, "shop-limit-boost", 16_384);
    }

    /** Снимок прокачки игрока. Все поля зажимаются в контрактных границах. */
    public record Context(
            int rankLevel,
            int rankXpPercent,
            int rankDropPercent,
            int rankExtraSkill,
            int totalPrestige,
            int totalLevel,
            boolean pass,
            boolean boost,
            boolean entitlement
    ) {
        public Context {
            rankLevel = clamp(rankLevel, 0, 5);
            rankXpPercent = clamp(rankXpPercent, 0, 50);
            rankDropPercent = clamp(rankDropPercent, 0, 25);
            rankExtraSkill = clamp(rankExtraSkill, 0, 10);
            totalPrestige = clamp(totalPrestige, 0, 100);
            totalLevel = clamp(totalLevel, 0, 1000);
        }

        /** Игрок без прокачки: ранг 0, без бустов и доната. */
        public static final Context NONE = new Context(0, 0, 0, 0, 0, 0, false, false, false);

        /** Полностью прокачанный игрок: верхняя граница каждого источника. */
        public static Context full() {
            return new Context(5, 50, 25, 10, 100, 1000, true, true, true);
        }
    }

    /** Разбор лимита по источникам — для {@code /shopadmin cap} и wiki. */
    public record Breakdown(
            long base, long rank, long prestige, long levels,
            long pass, long boost, long entitlement, long total
    ) { }

    /** Считает лимит; {@code null} в любом аргументе означает деградацию до дефолтов/базы. */
    public static int compute(int base, Settings settings, Context context) {
        Settings s = settings == null ? Settings.DEFAULT : settings;
        Context c = context == null ? Context.NONE : context;
        if (!s.enabled()) {
            return 0;
        }
        long safeBase = Math.clamp((long) base, 0L, (long) HARD_MAX);
        long raw = safeBase + decomposition(s, c).totalWithoutBase();
        long ceiling = Math.min(s.max(), HARD_MAX);
        return (int) Math.clamp(raw, 1L, ceiling);
    }

    /** Полный разбор по источникам с уже применённым потолком. */
    public static Breakdown breakdown(int base, Settings settings, Context context) {
        Settings s = settings == null ? Settings.DEFAULT : settings;
        Context c = context == null ? Context.NONE : context;
        if (!s.enabled()) {
            return new Breakdown(0, 0, 0, 0, 0, 0, 0, 0);
        }
        long safeBase = Math.clamp((long) base, 0L, (long) HARD_MAX);
        Parts parts = decomposition(s, c);
        long ceiling = Math.min(s.max(), HARD_MAX);
        long total = Math.clamp(safeBase + parts.totalWithoutBase(), 1L, ceiling);
        return new Breakdown(safeBase, parts.rank(), parts.prestige(), parts.levels(),
                parts.pass(), parts.boost(), parts.entitlement(), total);
    }

    private record Parts(long rank, long prestige, long levels, long pass, long boost,
                         long entitlement) {
        long totalWithoutBase() {
            return rank + prestige + levels + pass + boost + entitlement;
        }
    }

    private static Parts decomposition(Settings s, Context c) {
        long rank = (long) c.rankLevel() * s.perRankLevel()
                + (long) c.rankXpPercent() * s.perRankXpPercent()
                + (long) c.rankDropPercent() * s.perRankDropPercent()
                + (long) c.rankExtraSkill() * s.perRankExtraSkill();
        long prestige = (long) c.totalPrestige() * s.perPrestige();
        long levels = (long) (c.totalLevel() / 10) * s.perTenLevels();
        long pass = c.pass() ? s.passBonus() : 0L;
        long boost = c.boost() ? s.boostBonus() : 0L;
        long entitlement = c.entitlement() ? s.entitlementBonus() : 0L;
        return new Parts(rank, prestige, levels, pass, boost, entitlement);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
