package ru.cherdakmd.chrdkskills;

import java.util.Arrays;

/**
 * Шесть веток билда вместо классов. Игрок вкладывает очки в любые ветки в
 * любых сочетаниях: очки веток и атрибутов делят один общий пул за уровни
 * навыков и ступени престижа.
 */
public enum BuildBranch {
    WARRIOR("warrior", "Воин"),
    ARCHER("archer", "Стрелок"),
    MAGE("mage", "Маг"),
    CLERIC("cleric", "Клирик"),
    PRIEST("priest", "Жрец"),
    ROGUE("rogue", "Разбойник");

    /** Потолок уровня ветки: полная специализация — 150 очков в одну ветку. */
    public static final int MAX_LEVEL = 150;

    private final String id;
    private final String name;

    BuildBranch(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String id() {
        return id;
    }

    /** Русское имя для сообщений (метод {@code name()} занят {@link Enum}). */
    public String displayName() {
        return name;
    }

    public static BuildBranch byId(String id) {
        if (id == null) return null;
        String needle = id.trim().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(branch -> branch.id.equals(needle))
                .findFirst()
                .orElse(null);
    }
}
