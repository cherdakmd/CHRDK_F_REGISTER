package ru.cherdakmd.chrdkpets;

/**
 * Сытость и настроение питомца — чистая арифметика без Bukkit.
 *
 * <p>Две шкалы 0..100 живут в {@code pets.json}. Сытость медленно падает со
 * временем, настроение тянется к нейтральной отметке; кормление и поглаживание
 * поднимают обе. Осознанное ограничение: шкалы влияют <b>только</b> на скорость
 * роста опыта и шанс выучить трюк. Боевые бонусы и аура хозяина от настроения
 * не зависят — иначе игрок, вернувшийся после отпуска, обнаружил бы, что его
 * волк бьёт вполсилы.</p>
 *
 * <p>Голодный и унылый питомец растёт на 28 % медленнее, сытый и довольный —
 * на 10 % быстрее. Множители перемножаются, но общий рост опыта всё равно
 * упирается в минутный потолок и в ночной/выходной бонусы из конфига.</p>
 */
public final class PetCare {

    /** Настройки заботы. Все числа клампятся при разборе конфига. */
    public record Settings(int startSatiety, int feedSatiety, int feedMood, int patMood,
                           int feedXp, int patXp,
                           int satietyDecayPerHour, int moodDecayPerHour, int moodNeutral,
                           int feedCooldownSeconds, int patCooldownSeconds, int healCooldownSeconds) {

        public Settings {
            startSatiety = clamp(startSatiety, 0, 100);
            feedSatiety = clamp(feedSatiety, 0, 100);
            feedMood = clamp(feedMood, 0, 100);
            patMood = clamp(patMood, 0, 100);
            feedXp = clamp(feedXp, 0, 10_000);
            patXp = clamp(patXp, 0, 10_000);
            satietyDecayPerHour = clamp(satietyDecayPerHour, 0, 100);
            moodDecayPerHour = clamp(moodDecayPerHour, 0, 100);
            moodNeutral = clamp(moodNeutral, 0, 100);
            feedCooldownSeconds = clamp(feedCooldownSeconds, 0, 86_400);
            patCooldownSeconds = clamp(patCooldownSeconds, 0, 86_400);
            healCooldownSeconds = clamp(healCooldownSeconds, 0, 86_400);
        }

        public static final Settings DEFAULT = new Settings(70, 35, 10, 6, 6, 2, 8, 5, 50, 45, 30, 120);
    }

    /** Текущие шкалы питомца. */
    public record State(int satiety, int mood) {

        public State {
            satiety = clamp(satiety, 0, 100);
            mood = clamp(mood, 0, 100);
        }

        public static State initial(Settings settings) {
            return new State(settings.startSatiety(), settings.moodNeutral());
        }
    }

    private PetCare() {
    }

    /**
     * Применяет течение времени: сытость падает, настроение движется к
     * нейтральной отметке. Повторный вызов с тем же {@code now} ничего не меняет.
     */
    public static State decayed(State state, long fromMillis, long nowMillis, Settings settings) {
        if (state == null || settings == null) {
            return state;
        }
        long elapsed = nowMillis - fromMillis;
        if (elapsed <= 0L) {
            return state;
        }
        double hours = elapsed / 3_600_000.0;
        int satiety = state.satiety() - (int) Math.round(hours * settings.satietyDecayPerHour());
        int mood = state.mood();
        int step = (int) Math.round(hours * settings.moodDecayPerHour());
        int neutral = settings.moodNeutral();
        if (mood > neutral) {
            mood = Math.max(neutral, mood - step);
        } else if (mood < neutral) {
            mood = Math.min(neutral, mood + step);
        }
        return new State(satiety, mood);
    }

    /** Кормление: обе шкалы вверх, без выхода за 100. */
    public static State fed(State state, Settings settings) {
        if (state == null || settings == null) {
            return state;
        }
        return new State(state.satiety() + settings.feedSatiety(),
                state.mood() + settings.feedMood());
    }

    /** Поглаживание: только настроение. */
    public static State patted(State state, Settings settings) {
        if (state == null || settings == null) {
            return state;
        }
        return new State(state.satiety(), state.mood() + settings.patMood());
    }

    /**
     * Множитель опыта за заботу: 0.72 (голоден и уныл) … 1.10 (сыт и счастлив).
     */
    public static double xpMultiplier(State state) {
        if (state == null) {
            return 1.0;
        }
        double satiety = 0.8 + state.satiety() / 100.0 * 0.2;
        double mood = 0.9 + state.mood() / 100.0 * 0.2;
        return round(satiety * mood, 4);
    }

    /** Округление до знаков — общий помощник для множителей и бонусов. */
    static double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
