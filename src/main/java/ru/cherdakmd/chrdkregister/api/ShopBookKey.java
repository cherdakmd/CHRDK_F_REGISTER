package ru.cherdakmd.chrdkregister.api;

import java.util.Locale;

/**
 * Чистая модель ключа книги в каталоге алмазного магазина. Без Bukkit:
 * используется и при разборе конфига, и при проверке замороженной сделки
 * {@link ShopExchange}, и при создании предмета в {@link ShopBooks}.
 *
 * <p>Ключ выглядит как {@code BOOK_CUSTOM_VEINMINER_3} или
 * {@code BOOK_VANILLA_PROTECTION_4} и одновременно служит
 * {@code material} сделки: формат проходит проверку {@code ShopExchange#valid()}.</p>
 */
public final class ShopBookKey {

    public static final String PREFIX_CUSTOM = "BOOK_CUSTOM_";
    public static final String PREFIX_VANILLA = "BOOK_VANILLA_";

    private static final String PATTERN = "BOOK_(CUSTOM|VANILLA)_[A-Z][A-Z0-9_]*_([1-9]|10)";

    private ShopBookKey() {
    }

    /** Одна книга каталога: идентификатор чары, уровень и вид (кастомная/ванильная). */
    public record Spec(String id, int level, boolean custom) {
        public Spec {
            if (id == null || !id.matches("[a-z][a-z0-9_]{0,63}") || level < 1 || level > 10) {
                throw new IllegalArgumentException("book spec");
            }
        }

        /** Ключ товара в каталоге и в {@link ShopExchange#material()}. */
        public String key() {
            return (custom ? PREFIX_CUSTOM : PREFIX_VANILLA) + id.toUpperCase(Locale.ROOT) + "_" + level;
        }

        /** Раздел каталога для книги. */
        public String category() {
            return custom ? "books-custom" : "books-vanilla";
        }
    }

    /** Ключ книги каталога? */
    public static boolean isKey(String key) {
        return key != null && key.matches(PATTERN);
    }

    /** Разбор ключа товара; {@code null} — это не книга или ключ повреждён. */
    public static Spec fromKey(String key) {
        if (!isKey(key)) {
            return null;
        }
        boolean custom = key.startsWith(PREFIX_CUSTOM);
        String body = key.substring((custom ? PREFIX_CUSTOM : PREFIX_VANILLA).length());
        int split = body.lastIndexOf('_');
        if (split <= 0 || split == body.length() - 1) {
            return null;
        }
        try {
            int level = Integer.parseInt(body.substring(split + 1));
            return new Spec(body.substring(0, split).toLowerCase(Locale.ROOT), level, custom);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /** Ключ из конфига: {@code custom:veinminer:3} / {@code vanilla:protection:4}. */
    public static Spec parse(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split(":", -1);
        if (parts.length != 3 || !(parts[0].equals("custom") || parts[0].equals("vanilla"))) {
            return null;
        }
        try {
            return new Spec(parts[1], Integer.parseInt(parts[2]), parts[0].equals("custom"));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
