package ru.cherdakmd.chrdkpets;

/**
 * Трюки питомца — чистый справочник без Bukkit.
 *
 * <p>Трюк — это эмоция, а не сила: он не даёт урона, здоровья или ауры. Пять
 * трюков открываются по мере роста настроения, обучение стоит одно угощение из
 * руки, а шанс зависит от настроения: сытый и довольный питомец учится почти
 * всегда, голодный — редко. Выученный набор хранится в {@code pets.json}.</p>
 */
public enum PetTricks {

    SIT("sit", 15, "HEART"),
    PAW("paw", 25, "HEART"),
    SPIN("spin", 40, "CRIT"),
    FETCH("fetch", 55, "CRIT"),
    JUMP("jump", 70, "CRIT");

    private final String id;
    private final int minMood;
    private final String effect;

    PetTricks(String id, int minMood, String effect) {
        this.id = id;
        this.minMood = minMood;
        this.effect = effect;
    }

    /** Идентификатор для команд и хранилища. */
    public String id() {
        return id;
    }

    /** Настроение, при котором трюк вообще доступен. */
    public int minMood() {
        return minMood;
    }

    /** Имя частицы для показа трюка (без Bukkit-типа, чтобы класс был чистым). */
    public String effect() {
        return effect;
    }

    /** Доступен ли трюк при таком настроении. */
    public boolean availableAt(int mood) {
        return mood >= minMood;
    }

    /** Трюк по идентификатору; {@code null} — неизвестный. */
    public static PetTricks byId(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim();
        for (PetTricks trick : values()) {
            if (trick.id.equalsIgnoreCase(normalized)) {
                return trick;
            }
        }
        return null;
    }

    /**
     * Шанс выучить трюк: база плюс доля за каждое очко настроения,
     * но не больше 95 % — «гарантии» не бывает даже у легенды.
     */
    public static double learnChance(int mood, double baseChance, double perMood) {
        double chance = baseChance + Math.max(0, Math.min(100, mood)) * perMood;
        return Math.max(0.0, Math.min(0.95, chance));
    }
}
