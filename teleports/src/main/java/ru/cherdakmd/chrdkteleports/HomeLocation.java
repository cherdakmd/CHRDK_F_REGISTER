package ru.cherdakmd.chrdkteleports;

/**
 * Точка дома. name — оригинал (для показа), поиск — по нижнему регистру. Мир хранится
 * именем, проверка существования — при телепорте.
 */
public record HomeLocation(String name, String world, double x, double y, double z,
                           float yaw, float pitch) {
}
