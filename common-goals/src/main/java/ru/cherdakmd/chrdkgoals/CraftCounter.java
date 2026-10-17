package ru.cherdakmd.chrdkgoals;

/** Оценка числа скрафченных предметов для цели «мануфактура». Чистый класс — тестируется без Bukkit. */
public final class CraftCounter {

    /**
     * Обычный клик забирает один результат (resultAmount). Shift-клик крафтит всё возможное:
     * число крафтов ограничено самым «тонким» ингредиентом матрицы (верхняя оценка — часть
     * результата может не влезть в инвентарь, для общих целей это допустимая погрешность).
     *
     * @param shift         shift-клик
     * @param resultAmount  размер выдачи рецепта за один крафт
     * @param matrixAmounts количество предметов в каждой ячейке сетки крафта (0 — пусто)
     * @return сколько предметов засчитать цели
     */
    public static int craftedAmount(boolean shift, int resultAmount, int[] matrixAmounts) {
        if (resultAmount <= 0) {
            return 0;
        }
        if (!shift) {
            return resultAmount;
        }
        int min = Integer.MAX_VALUE;
        for (int amount : matrixAmounts) {
            if (amount > 0) {
                min = Math.min(min, amount);
            }
        }
        if (min == Integer.MAX_VALUE) {
            return 0;
        }
        return resultAmount * min;
    }

    private CraftCounter() {
    }
}
