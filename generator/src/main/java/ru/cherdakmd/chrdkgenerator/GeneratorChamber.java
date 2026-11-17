package ru.cherdakmd.chrdkgenerator;

import java.util.List;

/**
 * Камера генератора — чистая проверка ячеек без Bukkit.
 *
 * <p>Ячейки — два блока рядом с ядром (по горизонтали или под ним):
 * один должен быть источником лавы, другой — источником воды (п.31, §2).
 * Пока оба на месте — генератор работает; сломали или залили — встаёт,
 * причина показывается в меню. Порядок кандидатов: N, E, S, W, под ядром.</p>
 */
final class GeneratorChamber {

    /** Содержимое одной ячейки-кандидата. */
    enum Cell {
        EMPTY,
        LAVA_SOURCE,
        WATER_SOURCE,
        OTHER
    }

    /** Причина остановки. */
    enum State {
        OK,
        NO_LAVA,
        NO_WATER,
        NO_CELLS
    }

    /** Итог скана: состояние и позиции найденных ячеек (−1 — нет). */
    record Result(State state, int lavaIndex, int waterIndex) {
        boolean working() {
            return state == State.OK;
        }
    }

    private GeneratorChamber() {
    }

    /**
     * Ищет лаву и воду среди кандидатов; обе находки независимы
     * (лава может быть хоть слева, хоть под ядром).
     */
    static Result scan(List<Cell> candidates) {
        int lava = -1;
        int water = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Cell cell = candidates.get(i);
            if (cell == Cell.LAVA_SOURCE && lava < 0) {
                lava = i;
            }
            if (cell == Cell.WATER_SOURCE && water < 0) {
                water = i;
            }
        }
        if (lava >= 0 && water >= 0) {
            return new Result(State.OK, lava, water);
        }
        State state = lava < 0 && water < 0 ? State.NO_CELLS
                : lava < 0 ? State.NO_LAVA : State.NO_WATER;
        return new Result(state, lava, water);
    }
}
