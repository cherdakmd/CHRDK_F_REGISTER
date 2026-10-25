package ru.cherdakmd.chrdkclasses;

import java.util.Locale;

/**
 * Четыре класса сервера (1.4.164).
 *
 * <p>Класс — это стиль игры, а не вторая профессия: он не даёт опыта и не
 * открывает рецептов, зато меняет то, как игрок держится в бою. Иконки лежат
 * здесь, а названия и описания — в {@code messages.yml} ({@code class.<ид>.name}
 * и {@code class.<ид>.desc}), поэтому тексты можно править без пересборки.</p>
 */
public enum PlayerClass {

    WARRIOR("warrior", "IRON_SWORD"),
    ARCHER("archer", "BOW"),
    MAGE("mage", "BLAZE_ROD"),
    CLERIC("cleric", "GOLDEN_APPLE");

    private final String id;
    private final String iconName;

    PlayerClass(String id, String iconName) {
        this.id = id;
        this.iconName = iconName;
    }

    public String id() {
        return id;
    }

    /** Имя материала иконки: разбирается в GUI, чтобы модель не тянула Bukkit. */
    public String iconName() {
        return iconName;
    }

    /** Разбор ида без исключений: мусор и {@code null} дают {@code null}. */
    public static PlayerClass parse(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (PlayerClass value : values()) {
            if (value.id.equals(key)) {
                return value;
            }
        }
        return null;
    }
}
