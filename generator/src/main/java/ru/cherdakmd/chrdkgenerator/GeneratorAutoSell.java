package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Автопродажа продукции (п.31, §6): заполненный бункер продаётся сам —
 * предметы катятся по таблице тира и оцениваются по текущим ценам магазина
 * алмазов (позиции вне каталога — fallback-цена конфига). Выручка идёт
 * игроку тем же денежным источником, что и выплаты генератора.
 */
final class GeneratorAutoSell {

    private final GeneratorPlugin plugin;
    private final Random random = new Random();

    GeneratorAutoSell(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Продаёт содержимое полного бункера генератора.
     *
     * @return {@code true} — бункер продан и опустошён
     */
    boolean trySell(UUID owner, int slot, long bunkerLevel) {
        if (!plugin.config().autoSellEnabled() || bunkerLevel <= 0) {
            return false;
        }
        Player player = Bukkit.getPlayer(owner);
        if (player == null || !player.isOnline()) {
            return false;
        }
        GeneratorStore.Entry entry = plugin.store().entry(owner, slot);
        if (entry == null || !entry.installed()
                || bunkerLevel < GeneratorStats.capacityUnits(entry, plugin.config())) {
            return false;
        }
        GeneratorConfig.Tier tier = plugin.config().tier(entry.tier());
        Map<String, Integer> items = GeneratorDrops.materialize(bunkerLevel, tier.drops(),
                entry.modules().getOrDefault("luck", 0), random);
        long total = 0L;
        for (Map.Entry<String, Integer> row : items.entrySet()) {
            long value = GeneratorShopHook.sellValue(plugin.getLogger(), row.getKey(), row.getValue());
            if (value < 0) {
                value = (long) row.getValue() * plugin.config().autoSellFallbackPrice();
            }
            total += value;
        }
        if (total <= 0) {
            return false;
        }
        String event = "auto-sell:" + owner + ":" + slot + ":" + System.currentTimeMillis();
        if (!plugin.moneyLoot().accept(player, event, total)) {
            return false;
        }
        long units = plugin.store().withdrawBunker(owner, slot, bunkerLevel);
        plugin.messages().send(player, "auto-sold",
                "units", String.valueOf(units),
                "money", String.valueOf(total));
        return true;
    }
}
