package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/** Стол: финальная отмена и точное сравнение результата ванильной операции до добавления бонуса. */
public final class TableListener implements Listener {
    private final EnchantsPlugin plugin;
    private static final int LIMIT = 4096;
    private static final class Ticket { org.bukkit.scheduler.BukkitTask task; }
    private final java.util.Map<java.util.UUID, Ticket> pending = new java.util.HashMap<>();
    void clear() {
        var tickets = java.util.List.copyOf(pending.values()); pending.clear();
        for (Ticket ticket : tickets) if (ticket.task != null) ticket.task.cancel();
    }
    static boolean validVanilla(java.util.Map<?, Integer> entries) {
        return entries != null && !entries.isEmpty() && entries.size() <= 256
                && entries.entrySet().stream().allMatch(entry -> entry.getKey() != null && entry.getValue() != null
                        && entry.getValue() >= 1 && entry.getValue() <= 255);
    }
    public TableListener(EnchantsPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchantItem(EnchantItemEvent event) {
        if (!plugin.config().tableEnabled() || event.getItem().getAmount() != 1) return;
        Player player = event.getEnchanter();
        if (plugin.coreApi() == null || !player.hasPermission("chrdkenchants.use") || !plugin.coreApi().isAuthenticated(player)) return;
        if (pending.size() >= LIMIT || pending.containsKey(player.getUniqueId())) return;
        int tier = Math.clamp(event.whichButton() + 1, 1, 3);
        if (plugin.service().random().nextDouble() * 100 >= plugin.config().tableChancePercent() * tier / 3.0) return;
        ItemStack before = event.getItem().clone();
        var inventory = event.getInventory();
        long generation = plugin.sourceGeneration();
        Ticket ticket = new Ticket(); pending.put(player.getUniqueId(), ticket);
        try {
        ticket.task = Bukkit.getScheduler().runTask(plugin, () -> {
            if (!pending.remove(player.getUniqueId(), ticket)) return;
            if (event.isCancelled() || generation != plugin.sourceGeneration() || !player.isOnline() || player.isDead()
                    || !plugin.config().tableEnabled() || plugin.coreApi() == null || !player.hasPermission("chrdkenchants.use") || !plugin.coreApi().isAuthenticated(player)
                    || !player.getOpenInventory().getTopInventory().equals(inventory) || !validVanilla(event.getEnchantsToAdd())) return;
            // BOOK -> ENCHANTED_BOOK заменяется самой игрой; event.getItem() после этого уже не слот.
            ItemStack expected = before.clone();
            if (before.getType() == Material.BOOK) {
                expected.setType(Material.ENCHANTED_BOOK);
                if (!(expected.getItemMeta() instanceof EnchantmentStorageMeta storage)) return;
                event.getEnchantsToAdd().forEach((enchant, level) -> storage.addStoredEnchant(enchant, level, true));
                expected.setItemMeta(storage);
            } else expected.addUnsafeEnchantments(event.getEnchantsToAdd());
            ItemStack current = inventory.getItem(0);
            if (current == null || !current.equals(expected)) return;
            ItemStack result = current.clone();
            EnchantDef.Polarity polarity = plugin.config().cursesEnabled()
                    && plugin.service().random().nextInt(100) < plugin.config().tableCurseChancePercent()
                    ? EnchantDef.Polarity.CURSE : EnchantDef.Polarity.LIGHT;
            boolean book = result.getType() == Material.ENCHANTED_BOOK;
            EnchantService.RollResult roll = book ? plugin.service().rollBook(tier, polarity, tier)
                    : plugin.service().rollOnto(result, tier, polarity, tier);
            if (roll == null && polarity == EnchantDef.Polarity.CURSE) roll = book
                    ? plugin.service().rollBook(tier, EnchantDef.Polarity.LIGHT, tier)
                    : plugin.service().rollOnto(result, tier, EnchantDef.Polarity.LIGHT, tier);
            if (roll == null) return;
            if (book) {
                plugin.items().apply(result, roll.def(), roll.level());
                plugin.items().stampBook(result, ru.cherdakmd.chrdkregister.api.EnchantBookSource.TABLE);
            }
            inventory.setItem(0, result);
            // Слот стола ещё не собственный инвентарь: открытие библиотеки произойдёт только после забора.
            plugin.messages().send(player, roll.def().curse() ? "table-cursed" : "table-added",
                    "name", plugin.messages().mini("en." + roll.def().id() + ".name"),
                    "levelroman", EnchantCodec.roman(roll.level()));
        });
        } catch (RuntimeException | Error failure) { pending.remove(player.getUniqueId(), ticket); throw failure; }
    }
}
