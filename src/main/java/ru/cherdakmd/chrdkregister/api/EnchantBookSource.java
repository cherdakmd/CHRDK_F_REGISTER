package ru.cherdakmd.chrdkregister.api;

/** Справочное происхождение новой книги, не подпись подлинности и не право на награду. */
public enum EnchantBookSource {
    TABLE, VILLAGER, MOB, TREASURE, SHARDS, ADMIN, API, WORKSHOP;

    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    public String encoded() { return "v1:" + id(); }
    public static EnchantBookSource decode(String value) {
        if (value == null || value.length() > 32) return null;
        for (var source : values()) if (source.encoded().equals(value)) return source;
        return null;
    }
}
