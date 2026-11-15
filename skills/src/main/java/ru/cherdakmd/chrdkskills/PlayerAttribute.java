package ru.cherdakmd.chrdkskills;

/**
 * Атрибуты персонажа: очки за уровни навыков распределяет сам игрок.
 *
 * <p>Русские имена — в коде (по образцу имен мобов): сообщения и команды
 * работают и по идентификатору, и по русскому имени.</p>
 */
public enum PlayerAttribute {
    STRENGTH("strength", "Сила"),
    AGILITY("agility", "Ловкость"),
    STAMINA("stamina", "Стойкость"),
    WISDOM("wisdom", "Мудрость"),
    CUNNING("cunning", "Хитрость");

    private final String id;
    private final String name;

    PlayerAttribute(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String id() {
        return id;
    }

    /** Русское имя для сообщений и команд (имя метода {@code name()} занято {@link Enum}). */
    public String displayName() {
        return name;
    }

    /** По идентификатору или русскому имени; неизвестное — {@code null}. */
    public static PlayerAttribute byId(String id) {
        if (id == null) {
            return null;
        }
        String needle = id.trim();
        for (PlayerAttribute attribute : values()) {
            if (attribute.id.equalsIgnoreCase(needle) || attribute.name.equalsIgnoreCase(needle)) {
                return attribute;
            }
        }
        return null;
    }
}
