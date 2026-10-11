package ru.cherdakmd.diamondshop;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Торговые операции магазина: единственное место, где пересекаются баланс, запас и инвентарь.
 */
public final class ShopService {

    private final ShopConfig.ShopConfigData config;
    private final ShopStore store;

    public ShopService(ShopConfig.ShopConfigData config, ShopStore store) {
        this.config = config;
        this.store = store;
    }

    public ShopConfig.ShopConfigData config() {
        return config;
    }

    public ShopStore store() {
        return store;
    }

    /** Цена единицы товара прямо сейчас (с применённым возвратом запаса). */
    public double priceOf(ShopConfig.ShopItem item) {
        long stock = store.stockOf(item, System.currentTimeMillis(), config.stockRecoveryPerHour());
        return PriceEngine.price(item.basePrice(), stock, item.refStock(), item.minMultiplier(), item.maxMultiplier());
    }

    /** Алмазный эквивалент {@code count} единиц материала по действующему курсу. */
    public double currencyWorth(Material material, int count) {
        Integer rate = config.currencyRates().get(material.name());
        return rate == null ? 0d : rate * (double) count;
    }

    public boolean isCurrency(Material material) {
        return config.currencyRates().containsKey(material.name());
    }

    /* ------------------------------- пополнение ------------------------------- */

    /** Изымает из инвентаря ВСЮ валюту и зачисляет на счёт; возвращает сумму в алмазах. */
    public double depositAll(Player player, boolean handOnly) {
        PlayerInventory inventory = player.getInventory();
        double credited = 0d;
        if (handOnly) {
            ItemStack hand = inventory.getItemInMainHand();
            if (isCurrency(hand.getType())) {
                credited = currencyWorth(hand.getType(), hand.getAmount());
                inventory.setItemInMainHand(null);
            }
        } else {
            ItemStack[] contents = inventory.getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null || !isCurrency(stack.getType())) continue;
                credited += currencyWorth(stack.getType(), stack.getAmount());
                inventory.setItem(slot, null);
            }
        }
        if (credited > 0) {
            store.addBalance(player.getUniqueId(), credited);
        }
        return credited;
    }

    /* ------------------------------- вывод ------------------------------- */

    /**
     * Выдаёт игроку физические алмазы на целое число единиц (блоки по курсу 9 + самоцветы).
     * Возвращает фактически списанную сумму (целую) или -1, если средств не хватило.
     */
    public long withdraw(Player player, long diamonds) {
        if (diamonds < 1 || !store.trySpend(player.getUniqueId(), diamonds)) {
            return -1;
        }
        long blocks = diamonds / 9;
        int gems = (int) (diamonds % 9);
        Map<Integer, ItemStack> leftover = new HashMap<>();
        while (blocks > 0) {
            int take = (int) Math.min(64, blocks);
            leftover.putAll(player.getInventory().addItem(new ItemStack(Material.DIAMOND_BLOCK, take)));
            blocks -= take;
        }
        while (gems > 0) {
            int take = Math.min(64, gems);
            leftover.putAll(player.getInventory().addItem(new ItemStack(Material.DIAMOND, take)));
            gems -= take;
        }
        leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        return diamonds;
    }

    /* ------------------------------- сделки ------------------------------- */

    public record TradeResult(boolean success, double money, String errorKey) {
    }

    /** Покупка игроком {@code units} единиц товара у магазина. */
    public TradeResult buy(Player player, ShopConfig.ShopItem item, long units) {
        if (units <= 0) return new TradeResult(false, 0d, "shop.invalid-number");
        double price = priceOf(item);
        double cost = PriceEngine.revenue(price, units);
        if (!store.trySpend(player.getUniqueId(), cost)) {
            return new TradeResult(false, cost, "shop.not-enough-balance");
        }
        Material material = Material.matchMaterial(item.material());
        if (material == null) {
            store.addBalance(player.getUniqueId(), cost);
            return new TradeResult(false, 0d, "shop.unknown-item");
        }
        giveItems(player, material, units);
        long now = System.currentTimeMillis();
        store.adjustStock(item, -units, now, config.stockRecoveryPerHour());
        store.recordTrade(item, units, false, cost);
        return new TradeResult(true, cost, null);
    }

    /** Продажа игроком {@code units} единиц товара магазину. */
    public TradeResult sell(Player player, ShopConfig.ShopItem item, long units) {
        if (units <= 0) return new TradeResult(false, 0d, "shop.invalid-number");
        Material material = Material.matchMaterial(item.material());
        if (material == null) return new TradeResult(false, 0d, "shop.unknown-item");
        if (countItems(player.getInventory(), material) < units) {
            return new TradeResult(false, 0d, "shop.not-enough-items");
        }
        double price = priceOf(item);
        double income = PriceEngine.revenue(price, units);
        removeItems(player.getInventory(), material, units);
        store.addBalance(player.getUniqueId(), income);
        long now = System.currentTimeMillis();
        store.adjustStock(item, units, now, config.stockRecoveryPerHour());
        store.recordTrade(item, units, true, income);
        return new TradeResult(true, income, null);
    }

    /* ------------------------------- инвентарная математика ------------------------------- */

    /** Сколько единиц материала лежит в инвентаре (без брони/оффхенда в основных слотах). */
    public static long countItems(PlayerInventory inventory, Material material) {
        long total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }

    /** Снимает ровно {@code units} единиц материала (вызывать после проверки countItems). */
    public static void removeItems(PlayerInventory inventory, Material material, long units) {
        long remaining = units;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) continue;
            if (stack.getAmount() <= remaining) {
                remaining -= stack.getAmount();
                inventory.setItem(slot, null);
            } else {
                stack.setAmount((int) (stack.getAmount() - remaining));
                remaining = 0;
            }
        }
    }

    /** Выдаёт предметы игроку; не влезшее бросается под ноги. */
    public static void giveItems(Player player, Material material, long units) {
        long remaining = units;
        Map<Integer, ItemStack> leftover = new HashMap<>();
        while (remaining > 0) {
            int take = (int) Math.min(material.getMaxStackSize(), remaining);
            leftover.putAll(player.getInventory().addItem(new ItemStack(material, take)));
            remaining -= take;
        }
        leftover.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    /** Итоговый список имён валют для подсказок сообщений. */
    public String currencyNames() {
        return String.join(", ", config.currencyRates().keySet().stream()
                .map(String::toLowerCase).toList());
    }

    /** Товар по пользовательскому вводу (регистронезависимо). */
    public Optional<ShopConfig.ShopItem> findItem(String input) {
        return config.item(input);
    }
}
