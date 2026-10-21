package ru.cherdakmd.chrdkachievements;

/**
 * Паспорт достижения.
 *
 * @param id       аски-идентификатор (ключ в файле данных и messages.yml)
 * @param category раздел витрины
 * @param metric   счётчик, по которому считается прогресс
 * @param goal     порог выполнения (для флагов — 1)
 * @param passDays дни проходки в награду (0 — без дней)
 * @param diamonds алмазы в награду (0 — без алмазов)
 * @param badge    ид бейджа из messages.yml ({@code badge.<id>}) или {@code null}
 */
public record AchievementDef(String id, Category category, AchievementMetric metric, long goal,
                             int passDays, int diamonds, String badge) {

    /** Разделы витрины. */
    public enum Category {
        PATH("path"),
        COMBAT("combat"),
        CRAFT("craft"),
        SOCIAL("social");

        private final String id;

        Category(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Category byId(String value) {
            if (value == null) {
                return null;
            }
            for (Category category : values()) {
                if (category.id.equalsIgnoreCase(value.trim())
                        || category.name().equalsIgnoreCase(value.trim())) {
                    return category;
                }
            }
            return null;
        }
    }

    public AchievementDef {
        if (goal <= 0) {
            throw new IllegalArgumentException("порог достижения должен быть > 0: " + id);
        }
        passDays = Math.max(0, Math.min(365, passDays));
        diamonds = Math.max(0, Math.min(576, diamonds));
    }

    /** Есть ли материальная награда. */
    public boolean hasReward() {
        return passDays > 0 || diamonds > 0;
    }
}
