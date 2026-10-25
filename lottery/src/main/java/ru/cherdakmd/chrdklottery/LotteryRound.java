package ru.cherdakmd.chrdklottery;

import java.util.Map;

/**
 * Чистая математика тиража лотереи: подсчёты банка, выплаты/комиссии и
 * взвешенный случайный выбор победителя. Без Bukkit — покрыто юнит-тестами.
 */
public final class LotteryRound {

    private LotteryRound() {
    }

    /** Сумма билетов всех участников (отрицательные значения игнорируются). */
    public static int totalTickets(Map<String, Integer> tickets) {
        int total = 0;
        if (tickets == null) return 0;
        for (Integer count : tickets.values()) {
            if (count != null && count > 0) total = Math.addExact(total, count);
        }
        return total;
    }

    /** Число участников тиража (у кого хотя бы один билет). */
    public static int participants(Map<String, Integer> tickets) {
        int count = 0;
        if (tickets == null) return 0;
        for (Integer value : tickets.values()) {
            if (value != null && value > 0) count++;
        }
        return count;
    }

    /**
     * Взвешенный выбор: каждый билет — равный шанс. {@code null}, если билетов нет.
     * Порядок итерации карты определяет раздачу броска — поэтому стор держит
     * LinkedHashMap (детерминированность для тестов с seeded Random).
     */
    public static String pickWinner(Map<String, Integer> tickets, java.util.Random random) {
        int total = totalTickets(tickets);
        if (total <= 0 || random == null) return null;
        int roll = random.nextInt(total);
        int passed = 0;
        if (tickets != null) {
            for (Map.Entry<String, Integer> entry : tickets.entrySet()) {
                passed += Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
                if (roll < passed) return entry.getKey();
            }
        }
        return null; // недостижимо при согласованных данных
    }

    /**
     * Выплата победителю: доля банка; &#39;пыль&#39; от округления достаётся комиссии,
     * чтобы суммы всегда сходились (payout + burn == pot).
     */
    public static int payoutOf(int pot, int payoutPercent) {
        if (pot < 0) throw new IllegalArgumentException("negative pot");
        int percent = Math.max(1, Math.min(100, payoutPercent));
        return (int) ((long) pot * percent / 100L);
    }

    /** Комиссия сервера, которая сгорает. */
    public static int burnOf(int pot, int payoutPercent) {
        return pot - payoutOf(pot, payoutPercent);
    }

    /** Шанс игрока в процентах с одним знаком после запятой * 10 (например 333 = 33.3%). */
    public static int chancePermille(int playerTickets, int totalTickets) {
        if (totalTickets <= 0 || playerTickets <= 0) return 0;
        return (int) Math.min(1000, Math.round(1000.0 * playerTickets / totalTickets));
    }
}
