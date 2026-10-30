package ru.cherdakmd.chrdkclasses;

import java.util.Locale;

/**
 * Расчёты классов (1.4.164): уровень по навыкам, очки, потолки эффектов и
 * форматы для текстов.
 *
 * <p>Уровень класса не хранится отдельно от навыков, а выводится из их суммы:
 * так «класс» и «навыки» не могут разъехаться, а смена кривой в конфиге не
 * требует миграции. Очки тоже не копятся в файле — они всегда
 * {@code выданные − потраченные}, поэтому дюп очков невозможен.</p>
 */
final class ClassMath {

    /**
     * Потолок уровня класса. При шаге 30 это ровно 1000 уровней навыков
     * (десять навыков по 100), то есть полное дерево — цель эндгейма.
     */
    static final int MAX_LEVEL = 34;
    /** Потолок очков талантов: защита от правки файла руками. */
    static final int MAX_POINTS = 300;
    /** Ярусов в ветке. */
    static final int MAX_TIER = 5;

    /** Уровень класса по сумме уровней навыков. */
    static int level(int totalSkill, int perLevel) {
        int step = Math.clamp(perLevel, 5, 100);
        int total = Math.max(0, totalSkill);
        return Math.clamp(1 + total / step, 1, MAX_LEVEL);
    }

    /** Сколько очков даёт уровень. */
    static int points(int level, int starting, int perLevel) {
        int start = Math.clamp(starting, 0, 10);
        int step = Math.clamp(perLevel, 0, 5);
        long total = (long) start + (long) (Math.clamp(level, 1, MAX_LEVEL) - 1) * step;
        return (int) Math.clamp(total, 0L, MAX_POINTS);
    }

    /**
     * Очки талантов за открытые достижения (1.4.175).
     *
     * <p>Достижения — единственный источник очков сверх уровней, и он не
     * продаётся: очко даётся за каждые {@code every} открытых, но не больше
     * {@code max}. Верхняя граница нужна, чтобы дерево оставалось деревом:
     * даже с бонусом очков хватает ровно на все 36.</p>
     */
    static int achievementBonus(int unlocked, int every, int max) {
        if (every <= 0 || max <= 0 || unlocked <= 0) {
            return 0;
        }
        return Math.clamp(unlocked / every, 0, Math.max(0, max));
    }

    /** Сколько очков осталось после изученных талантов. */
    static int available(int points, int spent) {
        return Math.max(0, points - Math.max(0, spent));
    }

    /** Сколько уровней навыков нужно до следующего уровня класса. */
    static int nextLevelIn(int totalSkill, int perLevel) {
        int step = Math.clamp(perLevel, 5, 100);
        int total = Math.max(0, totalSkill);
        int level = level(total, step);
        if (level >= MAX_LEVEL) {
            return 0;
        }
        return (level * step) - total;
    }

    /** Сумма эффекта с учётом потолка вида. */
    static double total(EffectKind kind, double raw) {
        if (kind == null) {
            return 0.0;
        }
        return Math.clamp(raw, 0.0, kind.cap());
    }

    /** Доля в проценты без знака «%»: {@code 0.125} → {@code "12,5"}. */
    static String percent(double value) {
        return decimal(value * 100.0);
    }

    /** Величина эффекта в единицах своего вида — без подписи. */
    static String amount(EffectKind kind, double value) {
        if (kind == null) {
            return "0";
        }
        // Пульс исторически добавляет regen / 2 единиц здоровья, то есть regen / 4 сердец.
        // Исправляем только отображение: сила и частота лечения остаются прежними.
        if (kind == EffectKind.REGEN) {
            return java.math.BigDecimal.valueOf(value).divide(java.math.BigDecimal.valueOf(4))
                    .stripTrailingZeros().toPlainString().replace('.', ',');
        }
        return switch (kind.unit()) {
            case PERCENT -> percent(value);
            case HALF_HEARTS -> decimal(value / 2.0);
            case POINTS -> decimal(value);
        };
    }

    /** Время до кулдауна: «3 дн. 4 ч», «2 ч 5 мин», «12 мин», «30 с». */
    static String clock(long seconds) {
        long safe = Math.max(0L, seconds);
        long days = safe / 86_400;
        long hours = (safe % 86_400) / 3_600;
        long minutes = (safe % 3_600) / 60;
        if (days > 0) {
            return days + " дн." + (hours > 0 ? " " + hours + " ч" : "");
        }
        if (hours > 0) {
            return hours + " ч" + (minutes > 0 ? " " + minutes + " мин" : "");
        }
        return minutes > 0 ? minutes + " мин" : safe + " с";
    }

    /** То же время, но из миллисекунд: кулдауны умений считаются в них. */
    static String clockMillis(long millis) {
        long seconds = Math.max(0L, millis) / 1_000L;
        return clock(seconds);
    }

    /** Число без лишних нулей и с запятой в дробной части. */
    static String decimal(double value) {
        double safe = Math.abs(value) < 0.0001 ? 0.0 : value;
        String text = String.format(Locale.ROOT, "%.1f", safe);
        if (text.endsWith(".0")) {
            text = text.substring(0, text.length() - 2);
        }
        return text.replace('.', ',');
    }

    private ClassMath() {
    }
}
