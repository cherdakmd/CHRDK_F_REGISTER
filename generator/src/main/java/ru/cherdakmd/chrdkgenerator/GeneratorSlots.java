package ru.cherdakmd.chrdkgenerator;

/**
 * Чистая арифметика слотов генераторов (п.31, §3): базовый слот, слот за
 * перерождение и слот за платный ранг. Без состояния и Bukkit — только формулы.
 */
public final class GeneratorSlots {
    private GeneratorSlots() {}

    /**
     * Доступные игроку слоты: 1 базовый, +1 при {@code rebirthUnlockAt > 0}
     * и не меньше этого числа перерождений, +1 при {@code paidRankMinStep > 0}
     * и ступени платного ранга не ниже этой. Ноль в пороге — слот выключен.
     */
    public static int maxSlots(int rebirths, int paidRankStep, int rebirthUnlockAt, int paidRankMinStep) {
        int slots = 1;
        if (rebirthUnlockAt > 0 && rebirths >= rebirthUnlockAt) {
            slots++;
        }
        if (paidRankMinStep > 0 && paidRankStep >= paidRankMinStep) {
            slots++;
        }
        return slots;
    }
}
