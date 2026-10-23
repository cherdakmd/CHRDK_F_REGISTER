package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;

/**
 * Одна выпавшая вещь: материал и количество.
 *
 * <p>Чистая запись без Bukkit-вызовов: сервис превращает её в
 * {@code ItemStack} уже при выдаче, поэтому броски добычи проверяются тестами
 * без сервера.</p>
 */
public record HuntDrop(Material material, int amount) {

    public HuntDrop {
        if (material == null) {
            throw new IllegalArgumentException("Материал добычи не может быть пустым");
        }
        amount = Math.clamp(amount, 1, 64);
    }
}
