package ru.cherdakmd.chrdkorders;

import org.bukkit.Material;

import java.util.Random;

/**
 * Один материал в заказе бота: сколько принести и сколько бот платит за штуку.
 *
 * <p>Диапазон количества и цена — из {@code config.yml}. Класс чистый: Bukkit
 * виден только как {@link Material}, поэтому границы и броски проверяются
 * юнит-тестами без сервера.</p>
 *
 * @param material      что просят
 * @param minAmount     минимальное количество
 * @param maxAmount     максимальное количество
 * @param rewardPerItem цена одной штуки в алмазах до наценки бота
 */
public record OrderItem(Material material, int minAmount, int maxAmount, double rewardPerItem) {

    /** Больше 576 предметов в заказ не просят: это девять полных стаков. */
    public static final int MAX_AMOUNT = 576;

    public OrderItem {
        if (material == null || material.isAir()) {
            throw new IllegalArgumentException("Материал заказа не может быть пустым");
        }
        minAmount = Math.clamp(minAmount, 1, MAX_AMOUNT);
        maxAmount = Math.clamp(maxAmount, minAmount, MAX_AMOUNT);
        rewardPerItem = Math.clamp(rewardPerItem, 0.0, 100.0);
    }

    /** Случайное количество в диапазоне; без {@link Random} — минимальное. */
    public int amount(Random random) {
        if (maxAmount <= minAmount || random == null) {
            return minAmount;
        }
        return minAmount + random.nextInt(maxAmount - minAmount + 1);
    }

    /** «Сырьевая» цена партии без наценки бота. */
    public double rawReward(int amount) {
        return Math.max(0, amount) * rewardPerItem;
    }
}
