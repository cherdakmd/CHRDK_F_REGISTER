package ru.cherdakmd.chrdktreasures;

import java.util.Locale;

/**
 * Правило показа звания кладоискателя рядом с ником (1.4.166).
 *
 * <p>Звание выдаётся за находки, а не покупается: «Легенда» в имени — это
 * заслуга, поэтому в отличие от титулов её не нужно «надевать вручную». Но и
 * навязывать её нельзя: игрок может скрыть звание у себя, и тогда оно исчезает
 * и из чата, и из таба.</p>
 *
 * <p>Вся логика — чистая, без Bukkit: юнит-тест прогоняет матрицу
 * «порог × выбор игрока × выключатель» и не поднимает сервер.</p>
 */
final class TreasureRankTags {

    /** Выбор игрока: как на сервере, показывать всегда, скрыть. */
    enum Choice {

        AUTO("auto"),
        ON("on"),
        OFF("off");

        private final String id;

        Choice(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Разбор без учёта регистра; {@code null} — неизвестное значение. */
        static Choice byId(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Choice choice : values()) {
                if (choice.id.equals(normalized)) {
                    return choice;
                }
            }
            return null;
        }

        /** Следующее состояние для клика в меню: авто → включено → скрыто → авто. */
        Choice next() {
            return switch (this) {
                case AUTO -> ON;
                case ON -> OFF;
                case OFF -> AUTO;
            };
        }
    }

    private TreasureRankTags() {
    }

    /**
     * Показывать ли звание в имени.
     *
     * @param enabled      включены ли звания в имени на сервере
     * @param defaultShown серверное умолчание для тех, кто не выбирал
     * @param minRank      минимальное звание для показа
     * @param rank         звание игрока (может быть {@code null})
     * @param choice       выбор игрока ({@code null} — не выбирал, «авто»)
     */
    static boolean shows(boolean enabled, boolean defaultShown, TreasureRank minRank,
                         TreasureRank rank, Choice choice) {
        if (!enabled || rank == null) {
            return false;
        }
        TreasureRank floor = minRank == null ? TreasureRank.SEEKER : minRank;
        if (rank.ordinal() < floor.ordinal()) {
            return false;
        }
        return switch (choice == null ? Choice.AUTO : choice) {
            case AUTO -> defaultShown;
            case ON -> true;
            case OFF -> false;
        };
    }
}
