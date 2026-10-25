package ru.cherdakmd.diamondshop;

import java.util.Locale;
import java.util.Optional;

/**
 * Разделы каталога магазина. Порядок констант = порядок вкладок в витрине.
 *
 * <p>Раздел товара задаётся полем {@code category} в {@code config.yml}; если поле
 * не указано или содержит неизвестное значение, товар попадает в {@link #MISC} —
 * старые конфиги продолжают работать без миграции.</p>
 */
public enum ShopCategory {

    ALL("all", "shop.gui.tab-all", "CHEST"),
    BLOCKS("blocks", "shop.gui.tab-blocks", "BRICKS"),
    WOOD("wood", "shop.gui.tab-wood", "OAK_LOG"),
    MINERALS("minerals", "shop.gui.tab-minerals", "IRON_INGOT"),
    MOBS("mobs", "shop.gui.tab-mobs", "BONE"),
    FOOD("food", "shop.gui.tab-food", "BREAD"),
    UTILITY("utility", "shop.gui.tab-utility", "CRAFTING_TABLE"),
    MISC("misc", "shop.gui.tab-misc", "BARREL");

    /** Раздел для товаров без явной категории. */
    public static final ShopCategory DEFAULT = MISC;

    private final String id;
    private final String messageKey;
    private final String icon;

    ShopCategory(String id, String messageKey, String icon) {
        this.id = id;
        this.messageKey = messageKey;
        this.icon = icon;
    }

    /** Идентификатор для {@code config.yml} (нижний регистр). */
    public String id() {
        return id;
    }

    /** Ключ названия вкладки в messages.yml. */
    public String messageKey() {
        return messageKey;
    }

    /** Материал иконки вкладки (строка — чтобы enum оставался чистым Java). */
    public String icon() {
        return icon;
    }

    /** Раздел по идентификатору без учёта регистра. */
    public static Optional<ShopCategory> byId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ShopCategory category : values()) {
            if (category.id.equals(normalized)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
