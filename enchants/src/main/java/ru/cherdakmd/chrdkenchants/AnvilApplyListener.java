package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Наковальня: предмет + книга кастомной чары = предмет с чарой. Проверки лимитов/конфликтов —
 * в {@link EnchantService#canApplyFromBook}; цена опыта зависит от редкости.
 *
 * <p>1.4.128: проклятие с наковальни накладывается так же, как светлая чара, но
 * предупреждает игрока. Снять проклятие наковальней или другой книгой нельзя —
 * для этого у админа есть {@code /enchantsadmin purify}.</p>
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

    /**
     * Предупреждение в момент, когда игрок забирает из наковальни предмет,
     * получивший новое проклятие. Считаем разницу «было → стало», поэтому
     * повторная починка того же проклятия предупреждение не повторяет.
     */
    @EventHandler(ignoreCancelled = true)
    public void onTakeResult(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof org.bukkit.inventory.AnvilInventory)) {
            return;
        }
        if (event.getRawSlot() != 2 || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        ItemStack left = event.getInventory().getItem(0);
        if (result == null || left == null || !result.hasItemMeta()) {
            return;
        }
        Map<String, Integer> before = plugin.items().read(left);
        Map<String, Integer> after = plugin.items().read(result);
        boolean newCurse = after.entrySet().stream()
                .anyMatch(entry -> EnchantRegistry.byId(entry.getKey()) != null
                        && EnchantRegistry.byId(entry.getKey()).curse()
                        && entry.getValue() > before.getOrDefault(entry.getKey(), 0));
        if (!newCurse) {
            return;
        }
        // клик ещё не перенёс предмет — предупреждаем следующим тиком
        Bukkit.getScheduler().runTask(plugin,
                () -> plugin.messages().send(player, "anvil-curse-applied"));
    }
}
