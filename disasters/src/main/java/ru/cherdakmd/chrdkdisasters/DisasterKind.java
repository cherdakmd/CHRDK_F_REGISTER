package ru.cherdakmd.chrdkdisasters;

import java.util.Locale;
import java.util.Optional;

/**
 * Шесть катастроф второй итерации. Ни одна не использует NMS, Bukkit-взрывы
 * и урон игрокам: эффекты состояния накладываются мягкие и снимаются на фазе
 * затухания. {@code destructive} означает «меняет блоки» и требует снимка для
 * отката; {@code stockEnabled} — разрешена ли катастрофа в эталонном config.yml.
 *
 * <p>Разрушительные площадные виды выключены по умолчанию даже при включённом
 * модуле: владелец разрешает их явно. Идентификаторы стабильны — по ним
 * строятся ключи {@code kinds.*}, {@code sounds.*} и тексты {@code kind-*},
 * {@code hint-*} в messages.yml.</p>
 */
public enum DisasterKind {

    DUST_STORM("dust-storm", false, true,
            "BLOCK_NOTE_BLOCK_BASS", "SLOWNESS", 0),
    METEOR_SHOWER("meteor-shower", true, true,
            "ENTITY_LIGHTNING_BOLT_IMPACT", "NAUSEA", 0),
    EARTHQUAKE("earthquake", true, false,
            "ENTITY_LIGHTNING_BOLT_THUNDER", "MINING_FATIGUE", 0),
    TORNADO("tornado", true, false,
            "ITEM_TRIDENT_RIPTIDE_1", "SLOW_FALLING", 0),
    FLOOD("flood", true, false,
            "BLOCK_BEACON_ACTIVATE", "SLOWNESS", 0),
    BLIZZARD("blizzard", false, true,
            "BLOCK_SNOW_BREAK", "SLOWNESS", 1);

    private final String id;
    private final boolean destructive;
    private final boolean stockEnabled;
    private final String sound;
    private final String effect;
    private final int amplifier;

    DisasterKind(String id, boolean destructive, boolean stockEnabled,
                 String sound, String effect, int amplifier) {
        this.id = id;
        this.destructive = destructive;
        this.stockEnabled = stockEnabled;
        this.sound = sound;
        this.effect = effect;
        this.amplifier = amplifier;
    }

    public String id() {
        return id;
    }

    /** Меняет ли катастрофа блоки (а значит, требует снимка для отката). */
    public boolean destructive() {
        return destructive;
    }

    /** Значение {@code disasters.kinds.<id>} в эталонном конфиге. */
    public boolean stockEnabled() {
        return stockEnabled;
    }

    /** Имя звука Bukkit по умолчанию; владелец может переопределить в YAML. */
    public String sound() {
        return sound;
    }

    /** Мягкий эффект состояния без урона; {@code NONE} отключает его. */
    public String effect() {
        return effect;
    }

    public int amplifier() {
        return amplifier;
    }

    public String titleKey() {
        return "kind-" + id;
    }

    public String hintKey() {
        return "hint-" + id;
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
