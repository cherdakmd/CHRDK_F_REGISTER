package ru.cherdakmd.chrdkbp;

/**
 * Состояние задания в списке {@code /battlepass} и полоса прогресса.
 *
 * <p>Чистая логика без Bukkit: состояние зависит только от прогресса, отметок о
 * получении наград и наличия активного ранга. Благодаря этому тексты и иконки
 * можно проверить тестами, не поднимая сервер.</p>
 */
final class BattlePassView {

    private BattlePassView() {
    }

    /** Что показывать игроку в строке задания. */
    enum State {
        /** Цель ещё не достигнута — показываем прогресс. */
        PROGRESS,
        /** Есть награда, которую можно забрать прямо сейчас. */
        READY,
        /** Цель достигнута, осталась только VIP-награда, а ранга нет. */
        VIP_LOCKED,
        /** Осталась только премиум-награда, а боевой пропуск не куплен. */
        PREMIUM_LOCKED,
        /** Все доступные награды уже забраны. */
        CLAIMED
    }

    /**
     * Снимок задания для одного игрока.
     *
     * @param def         описание задания из конфига
     * @param progress    накопленный прогресс (может быть больше цели у старых данных)
     * @param freeClaimed бесплатная награда уже забрана
     * @param vipClaimed  VIP-награда уже забрана
     * @param vipActive   у игрока есть активная ранг
     * @param premiumClaimed премиум-награда уже забрана
     * @param premiumActive  у игрока есть активный боевой пропуск
     */
    record Mission(BattlePassConfig.MissionDef def, int progress,
                   boolean freeClaimed, boolean vipClaimed, boolean vipActive,
                   boolean premiumClaimed, boolean premiumActive) {

        /** Совместимый конструктор: задание без премиум-трека. */
        Mission(BattlePassConfig.MissionDef def, int progress,
                boolean freeClaimed, boolean vipClaimed, boolean vipActive) {
            this(def, progress, freeClaimed, vipClaimed, vipActive, false, false);
        }

        boolean goalReached() {
            return progress >= def.target();
        }

        boolean hasFree() {
            return def.freeDays() > 0;
        }

        boolean hasVip() {
            return def.vipDays() > 0;
        }

        boolean freeLeft() {
            return hasFree() && !freeClaimed;
        }

        boolean vipLeft() {
            return hasVip() && !vipClaimed;
        }

        boolean hasPremium() {
            return def.hasPremium();
        }

        boolean premiumLeft() {
            return hasPremium() && !premiumClaimed;
        }

        State state() {
            if (!goalReached()) {
                return State.PROGRESS;
            }
            if (freeLeft() || (vipLeft() && vipActive) || (premiumLeft() && premiumActive)) {
                return State.READY;
            }
            if (premiumLeft()) {
                return State.PREMIUM_LOCKED;
            }
            if (vipLeft()) {
                return State.VIP_LOCKED;
            }
            return State.CLAIMED;
        }

        /** Сколько наград этой ветки уже забрано (0..3). */
        int claimedTracks() {
            return (hasFree() && freeClaimed ? 1 : 0) + (hasVip() && vipClaimed ? 1 : 0)
                    + (hasPremium() && premiumClaimed ? 1 : 0);
        }

        /** Сколько наград всего в задании (0..3). */
        int totalTracks() {
            return (hasFree() ? 1 : 0) + (hasVip() ? 1 : 0) + (hasPremium() ? 1 : 0);
        }

        /**
         * Сколько наград доступно этому игроку: премиум-ветка считается только
         * с боевым пропуском, иначе «волна пройдена» не наступила бы никогда.
         */
        int availableTracks() {
            return (hasFree() ? 1 : 0) + (hasVip() ? 1 : 0)
                    + (hasPremium() && premiumActive ? 1 : 0);
        }

        /** Прогресс для показа: от 0 до цели (битые данные не ломают строку). */
        int shownProgress() {
            return Math.max(0, Math.min(progress, def.target()));
        }
    }

    /**
     * Сколько секций полосы закрашено. Любой ненулевой прогресс виден хотя бы
     * одной секцией, иначе на длинных заданиях полоса выглядела бы пустой.
     */
    static int filledSegments(int progress, int target, int width) {
        if (progress <= 0 || target <= 0 || width <= 0) {
            return 0;
        }
        int filled = (int) Math.ceil(progress * (double) width / target);
        return Math.min(width, filled);
    }
}
