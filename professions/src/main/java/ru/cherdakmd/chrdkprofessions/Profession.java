package ru.cherdakmd.chrdkprofessions;

import java.util.Locale;

/**
 * Четыре ремесленные профессии сервера (1.4.165).
 *
 * <p>Профессия — это не вторая прокачка и не класс: она не даёт ни урона, ни
 * брони. Она определяет, за какую работу игрок получает опыт ремесленника и
 * какая тихая прибавка у него включена: скидка на наковальню, лишняя склянка,
 * сытнее еда или второй блок. Поэтому сила по-прежнему только прокачивается,
 * а профессия экономит время и материалы.</p>
 *
 * <p>Иконки лежат здесь, а названия и описания — в {@code messages.yml}
 * ({@code profession.<ид>.name} и {@code profession.<ид>.desc}), чтобы тексты
 * можно было править без пересборки.</p>
 */
public enum Profession {

    SMITH("smith", "ANVIL"),
    ALCHEMIST("alchemist", "BREWING_STAND"),
    COOK("cook", "CAMPFIRE"),
    MASON("mason", "STONECUTTER"),
    FORESTER("forester", "OAK_LOG"), MINER("miner", "IRON_PICKAXE"),
    FARMER("farmer", "WHEAT"), FISHER("fisher", "FISHING_ROD");

    private final String id;
    private final String iconName;

    Profession(String id, String iconName) {
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
    public static Profession parse(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (Profession value : values()) {
            if (value.id.equals(key)) {
                return value;
            }
        }
        return null;
    }

    /** Есть ли профессия с таким идом. */
    public static boolean exists(String raw) {
        return parse(raw) != null;
    }
}
