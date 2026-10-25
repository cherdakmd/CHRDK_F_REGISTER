package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Позиция лавки охотника: что продаётся за монеты и сколько раз в сутки.
 *
 * <p>В лавке только бытовые вещи (еда, свет, расходники): оружия, брони,
 * инструментов, зачарований и алмазов здесь нет — охотничьи монеты не должны
 * превращаться в боевую силу. {@code minCoinsEarned} — порог «стажа» охотника,
 * а не покупка за реальные деньги.</p>
 *
 * @param id              ид позиции для {@code /hunt buy <id>}
 * @param material        что продаётся
 * @param amount          сколько штук в одной покупке
 * @param price           цена в монетах
 * @param dailyLimit      сколько покупок в сутки (0 — без лимита)
 * @param minCoinsEarned  сколько монет нужно заработать за всё время
 */
public record HuntShopOffer(String id, Material material, int amount, int price,
                            int dailyLimit, int minCoinsEarned) {

    /** Максимум штук в одной покупке — стак. */
    public static final int MAX_AMOUNT = 64;
    /** Потолок цены: защита от опечатки в конфиге. */
    public static final int MAX_PRICE = 100_000;

    public HuntShopOffer {
        id = HuntFamily.normalize(id);
        if (material == null) {
            throw new IllegalArgumentException("Материал лавки не может быть пустым");
        }
        amount = Math.clamp(amount, 1, MAX_AMOUNT);
        price = Math.clamp(price, 1, MAX_PRICE);
        dailyLimit = Math.clamp(dailyLimit, 0, 1_000);
        minCoinsEarned = Math.clamp(minCoinsEarned, 0, Integer.MAX_VALUE);
    }

    /** Цена за {@code quantity} покупок. */
    public long totalPrice(int quantity) {
        return (long) price * Math.max(1, quantity);
    }

    /** Сколько вещей игрок получит за {@code quantity} покупок. */
    public int totalItems(int quantity) {
        return amount * Math.max(1, quantity);
    }

    @Override
    public String toString() {
        return id + " (" + material.name().toLowerCase(Locale.ROOT) + " ×" + amount
                + " за " + price + ")";
    }
}
