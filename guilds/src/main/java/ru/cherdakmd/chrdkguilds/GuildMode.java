package ru.cherdakmd.chrdkguilds;

/** Старые кланы остаются боевыми: обновление не подключает платный режим. */
public enum GuildMode {
    BATTLE, PEACEFUL;
    public String label() { return this == PEACEFUL ? "Мирный" : "Боевой"; }
}
