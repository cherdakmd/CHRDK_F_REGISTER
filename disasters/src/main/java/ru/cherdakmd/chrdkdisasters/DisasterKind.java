package ru.cherdakmd.chrdkdisasters;

import java.util.Locale;
import java.util.Optional;

/** Три катастрофы первой версии. Ни одна не использует NMS и Bukkit-взрывы. */
public enum DisasterKind {
    /** Только частицы и подсказка: блоки не меняются вообще. */
    DUST_STORM("dust-storm", false),
    /** Точечные кратеры по поверхности, восстанавливаются по таймеру. */
    METEOR_SHOWER("meteor-shower", true),
    /** Площадное разрушение: самая тяжёлая проверка приватов. */
    EARTHQUAKE("earthquake", true);

    private final String id;
    private final boolean destructive;

    DisasterKind(String id, boolean destructive) {
        this.id = id;
        this.destructive = destructive;
    }

    public String id() {
        return id;
    }

    /** Меняет ли катастрофа блоки (а значит, требует снимка для отката). */
    public boolean destructive() {
        return destructive;
    }

    public static Optional<DisasterKind> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (DisasterKind kind : values()) {
            if (kind.id.equals(needle) || kind.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
