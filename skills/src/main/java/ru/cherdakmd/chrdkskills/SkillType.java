package ru.cherdakmd.chrdkskills;

import java.util.Locale;

/**
 * Навыки сервера: что качается, за что даётся опыт и какие перки открываются.
 *
 * <p>Это статический каталог — имена и описания живут в {@code messages.yml}
 * ({@code skill.<id>.name|desc}, {@code perk.<ид>.name|desc}), а числа перков и
 * пороги уровней — здесь, чтобы их можно было проверить тестами без Bukkit.</p>
 *
 * <p>Порядок констант — порядок витрины: сначала добыча, потом бой, затем
 * ремесло. Пороги перков внутри навыка строго возрастают — на этом держится
 * расчёт «какие перки активны на уровне».</p>
 */
public enum SkillType {

    MINING("mining", Category.GATHER),
    WOODCUTTING("woodcutting", Category.GATHER),
    DIGGING("digging", Category.GATHER),
    FARMING("farming", Category.GATHER),
    FISHING("fishing", Category.GATHER),
    COMBAT("combat", Category.COMBAT),
    ARCHERY("archery", Category.COMBAT),
    DEFENSE("defense", Category.COMBAT),
    ALCHEMY("alchemy", Category.CRAFT),
    ENCHANTING("enchanting", Category.CRAFT);

    /** Группы витрины — по ним раскладываются иконки и вкладки. */
    public enum Category {
        GATHER("gather"),
        COMBAT("combat"),
        CRAFT("craft");

        private final String id;

        Category(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final String id;
    private final Category category;

    SkillType(String id, Category category) {
        this.id = id;
        this.category = category;
    }

    public String id() {
        return id;
    }

    public Category category() {
        return category;
    }

    public static SkillType byId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SkillType type : values()) {
            if (type.id.equals(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return null;
    }
}
