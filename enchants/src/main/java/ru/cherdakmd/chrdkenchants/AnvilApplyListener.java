package ru.cherdakmd.chrdkenchants;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Наковальня: предмет + книга кастомной чары = предмет с чарой. Проверки лимитов/конфликтов —
 * в {@link EnchantService#canApplyFromBook}; цена опыта зависит от редкости.
 */
public final class AnvilApplyListener implements Listener {

    private final EnchantsPlugin plugin;

    public AnvilApplyListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (left == null || right == null || !plugin.items().isEnchantBook(right)) {
            return;
        }
        String problem = plugin.service().canApplyFromBook(left, right);
        if (problem != null) {
            Player viewer = event.getView().getPlayer() instanceof Player player ? player : null;
            if (viewer != null && "blocked".equals(problem)) {
                plugin.messages().send(viewer, "anvil-capped",
                        "max", plugin.config().maxPerItem());
            }
            // без результата: ванильная наковальня не даст рабочую выдачу этой паре
            return;
        }
        ItemStack result = left.clone();
        plugin.service().applyFromBook(result, right);
        event.setResult(result);
        int maxLevel = plugin.items().read(right).values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int rarityCost = plugin.items().read(right).keySet().stream()
                .map(EnchantRegistry::byId)
                .filter(java.util.Objects::nonNull)
                .map(EnchantDef::rarity)
                .map(rarity -> switch (rarity) {
                    case COMMON -> 2;
                    case RARE -> 4;
                    case EPIC -> 7;
                    case LEGENDARY -> 10;
                })
                .mapToInt(Integer::intValue).max().orElse(2);
        event.getInventory().setRepairCost(rarityCost * maxLevel + 3);
    }
}
