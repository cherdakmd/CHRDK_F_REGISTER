package ru.cherdakmd.chrdkskills;

import java.util.List;

/**
 * Перерождение (rebirth, п.30): добровольный полный сброс персонажа — уровней
 * всех навыков, атрибутов и веток — в обмен на постоянный множитель опыта и
 * уникальные награды. Повторять можно многократно.
 *
 * <p>Условие: все десять навыков на потолке ({@code 100}). Награда за каждое
 * перерождение: титул, физические алмазы и случайный уникальный предмет из
 * пула (без повторов, пока пул не исчерпан). На вехах — особые награды.</p>
 *
 * <p>Множитель опыта растёт с убывающей отдачей: {@code k}-е перерождение даёт
 * {@code xpPercentPerRebirth · decay^(k-1)} процентов, суммарно — не выше
 * {@code xpBonusCapPercent}. Защита от разгона: каждое следующее перерождение
 * чуть слабее предыдущего.</p>
 *
 * <p>Класс чистый, без Bukkit и состояния — проверяется юнит-тестами. Крутилки
 * приходят из {@code config.yml} и всегда проходят через местные {@code cap}-функции.</p>
 */
public final class RebirthMath {

    /** Десять навыков должны быть на потолке — иначе перерождения нет. */
    public static final int SKILLS = 10;

    /** Ранг приёмов/кирки: от первого перерождения, потолок силы — десятое. */
    public static final int MAX_RANK = 10;
    public static final int UNLOCK_REBIRTH = 1;

    /** Потолки крутилок множителя: конфиг не может их пробить. */
    public static final double MAX_XP_PERCENT_PER_REBIRTH = 25.0;
    public static final double MAX_XP_BONUS_CAP_PERCENT = 200.0;
    public static final double MIN_XP_DECAY = 0.5;
    public static final double MAX_XP_DECAY = 0.99;

    /** Значения по умолчанию (config.yml может их менять в рамках потолков). */
    public static final double DEFAULT_XP_PERCENT_PER_REBIRTH = 5.0;
    public static final double DEFAULT_XP_DECAY = 0.9;
    public static final double DEFAULT_XP_BONUS_CAP_PERCENT = 50.0;

    /** Потолок алмазов за перерождение/веху (предметом, не в /bal). */
    public static final int MAX_REBIRTH_DIAMONDS = 256;
    public static final int MAX_MILESTONE_DIAMONDS = 2048;

    /** Вехи особых наград; дальше — каждые двадцать пять перерождений. */
    public static final List<Integer> MILESTONES = List.of(5, 10, 25, 50);
    public static final int LATER_MILESTONE_STEP = 25;

    private RebirthMath() {
    }

    /** Все навыки на потолке — перерождение доступно. */
    public static boolean ready(List<Integer> skillLevels, int maxLevel) {
        if (skillLevels == null || skillLevels.size() != SKILLS) {
            return false;
        }
        for (Integer level : skillLevels) {
            if (level == null || level < maxLevel) {
                return false;
            }
        }
        return true;
    }

    /** Итоговый множитель опыта: 1.0 → не выше 1 + cap/100. */
    public static double xpFactor(int rebirths, double percentPerRebirth,
                                  double decay, double capPercent) {
        return 1.0 + xpBonusPercent(rebirths, percentPerRebirth, decay, capPercent) / 100.0;
    }

    /**
     * Суммарный бонус опыта в процентах: сумма убывающих прибавок
     * {@code percent · decay^(k-1)}, прижатая к потолку.
     */
    public static double xpBonusPercent(int rebirths, double percentPerRebirth,
                                        double decay, double capPercent) {
        int n = Math.max(0, rebirths);
        double percent = Math.min(Math.max(0.0, percentPerRebirth), MAX_XP_PERCENT_PER_REBIRTH);
        double step = Math.min(Math.max(decay, MIN_XP_DECAY), MAX_XP_DECAY);
        double cap = Math.min(Math.max(0.0, capPercent), MAX_XP_BONUS_CAP_PERCENT);
        if (n == 0 || percent == 0.0) {
            return 0.0;
        }
        double total = percent * (1.0 - Math.pow(step, n)) / (1.0 - step);
        return Math.min(cap, total);
    }

    /** Ранг приёмов и кирки: как и раньше — прижат к 1..{@link #MAX_RANK}. */
    public static int rank(int rebirths) {
        return Math.clamp(rebirths, UNLOCK_REBIRTH, MAX_RANK);
    }

    /** Титул перерождения: «Перерождение I..X», далее — числом. */
    public static String title(int rebirths) {
        int safe = Math.max(1, rebirths);
        return safe > MAX_RANK ? "Перерождение " + safe : "Перерождение " + roman(safe);
    }

    /** Веха с особыми наградами: 5, 10, 25, 50, далее каждые 25. */
    public static boolean milestone(int rebirths) {
        int safe = Math.max(0, rebirths);
        if (MILESTONES.contains(safe)) {
            return true;
        }
        return safe > MILESTONES.get(MILESTONES.size() - 1) && safe % LATER_MILESTONE_STEP == 0;
    }

    /** Алмазы за перерождение (предметом). */
    public static int rebirthDiamonds(int configured) {
        return clamp(configured, 0, MAX_REBIRTH_DIAMONDS);
    }

    /** Дополнительные алмазы на вехе. */
    public static int milestoneDiamonds(int configured) {
        return clamp(configured, 0, MAX_MILESTONE_DIAMONDS);
    }

    /** Все пороги вех: по ним отмечаем выданные награды. */
    public static List<Integer> milestoneThresholds() {
        return MILESTONES;
    }

    /** Римская запись для титула: I..X. */
    public static String roman(int value) {
        int safe = Math.clamp(value, 0, MAX_RANK);
        return switch (safe) {
            case 0 -> "—";
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            default -> "X";
        };
    }

    /**
     * Конвертация старого престижа (п.27): каждые десять ступеней суммарно по
     * навыкам — одно перерождение. Сотня ступеней ветерана даёт максимум десять.
     */
    public static int fromLegacySteps(int totalSteps) {
        return Math.max(0, totalSteps) / 10;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
