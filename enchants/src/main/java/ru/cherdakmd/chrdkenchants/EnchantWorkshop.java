package ru.cherdakmd.chrdkenchants;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import ru.cherdakmd.chrdkregister.api.EnchantBookSource;
import java.util.*;

/** Только подготовленные снимки собственного инвентаря: цена/результат фиксируются до подтверждения. */
final class EnchantWorkshop implements Listener {
    private record Plan(String action, UUID world, long generation, int slot, ItemStack[] before, ItemStack off,
                        ItemStack[] after, ItemStack nextOff) { }
    private final EnchantsPlugin plugin;
    private final Confirmations<Plan> pending = new Confirmations<>();
    EnchantWorkshop(EnchantsPlugin plugin) { this.plugin = plugin; }
    void clear() { pending.clear(); }
    boolean allowed(Player player, String action) {
        return permitted(player, action) && player.getOpenInventory().getType() == InventoryType.CRAFTING;
    }
    boolean permitted(Player player, String action) {
        return plugin.isEnabled() && player.isOnline() && !player.isDead() && player.getGameMode() == GameMode.SURVIVAL
                && !player.hasMetadata("NPC") && player.hasPermission("chrdkenchants.use") && plugin.coreApi() != null
                && plugin.coreApi().isAuthenticated(player) && plugin.getConfig().getBoolean("workshop.enabled", true)
                && plugin.getConfig().getBoolean("workshop." + (action.equals("splitvanilla") ? "split" : action) + ".enabled", true);
    }
    boolean command(Player player, String[] args) { return command(player, args, false); }
    /** Called after a GUI confirmation window is closed; rebuild and validate the complete plan. */
    void confirmedGui(Player player, String action, String id) {
        if (!Set.of("split", "splitvanilla", "cleanse", "combine").contains(action)) throw new IllegalArgumentException("workshop action");
        command(player, action.equals("combine") ? new String[]{action} : new String[]{action, id}, true);
    }
    private boolean command(Player player, String[] args, boolean confirmed) {
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("confirm")) {
            if (args.length != 2) { fail(player); return true; }
            UUID code;
            try { code = UUID.fromString(args[1]); } catch (IllegalArgumentException invalid) { fail(player); return true; }
            var plan = pending.take(player.getUniqueId(), code, System.currentTimeMillis());
            if (plan == null || !allowed(player, plan.action()) || !plan.world().equals(player.getWorld().getUID())
                    || plan.generation() != plugin.sourceGeneration() || plan.slot() != player.getInventory().getHeldItemSlot()
                    || !Arrays.equals(plan.before(), player.getInventory().getStorageContents())
                    || !Objects.equals(plan.off(), player.getInventory().getItemInOffHand())) { fail(player); return true; }
            // Main thread; никаких событий/команд/выдачи между расходом и результатом. Оба массива заранее готовы.
            try {
                player.getInventory().setStorageContents(copy(plan.after()));
                player.getInventory().setItemInOffHand(clone(plan.nextOff()));
            } catch (RuntimeException failure) {
                player.getInventory().setStorageContents(copy(plan.before()));
                player.getInventory().setItemInOffHand(clone(plan.off()));
                throw failure;
            }
            plugin.messages().send(player, "workshop-done"); return true;
        }
        if (!Set.of("cleanse", "combine", "split", "splitvanilla").contains(action)) return false;
        pending.remove(player.getUniqueId());
        if (!allowed(player, action) || (action.equals("combine") ? args.length != 1 : args.length != 2)) { fail(player); return true; }
        var inventory = player.getInventory(); int slot = inventory.getHeldItemSlot();
        var before = copy(inventory.getStorageContents()); var off = clone(inventory.getItemInOffHand());
        var built = build(action, args, before, off, slot);
        if (built == null) { fail(player); return true; }
        var after = built.after(); var nextOff = built.nextOff(); var cost = built.cost();
        var plan = new Plan(action, player.getWorld().getUID(), plugin.sourceGeneration(), slot, before, off, after, nextOff);
        var quote = pending.put(player.getUniqueId(), System.currentTimeMillis(), plan);
        if (quote == null) { fail(player); return true; }
        if (confirmed) return command(player, new String[]{"confirm", quote.code().toString()}, false);
        plugin.messages().send(player, "workshop-quote", "action", switch (action) { case "cleanse" -> "Очищение"; case "combine" -> "Объединение"; default -> "Разделение"; }, "cost", cost, "code", quote.code());
        if (action.equals("combine")) for (var entry : plugin.items().read(after[slot]).entrySet())
            plugin.messages().send(player, "workshop-result", "name", plugin.messages().mini("en." + entry.getKey() + ".name"), "level", entry.getValue());
        else if (action.equals("splitvanilla")) plugin.messages().send(player, "workshop-vanilla-selected", "id", args[1]);
        else plugin.messages().send(player, "workshop-selected", "name", plugin.messages().mini("en." + args[1] + ".name"));
        return true;
    }
    private record Built(ItemStack[] after, ItemStack nextOff, String cost) { }
    private Built build(String action, String[] args, ItemStack[] before, ItemStack off, int slot) {
        var main = before[slot]; var data = plugin.items().validated(main);
        if (main == null || main.getAmount() != 1 || data == null
                || (!WorkshopPolicy.valid(data) && !(action.equals("splitvanilla") && data.isEmpty()))) { return null; }
        var after = copy(before); ItemStack nextOff = clone(off); String cost;
        switch (action) {
            case "cleanse" -> {
                var result = WorkshopPolicy.cleanse(data, args[1]);
                if (result == null) { return null; }
                plugin.items().write(after[slot], result);
                if (!pay(after, slot, Material.AMETHYST_SHARD, 8) || !pay(after, slot, Material.DIAMOND, 1)) { return null; }
                cost = "8 осколков аметиста + 1 алмаз";
            }
            case "combine" -> {
                if (!book(main) || !book(off) || off.getAmount() != 1 || !main.isSimilar(off)
                        || vanilla(main) || vanilla(off)) { return null; }
                var result = WorkshopPolicy.combine(data, plugin.items().validated(off));
                if (result == null || result.keySet().stream().anyMatch(id -> !plugin.config().isEnabled(id))) { return null; }
                plugin.items().write(after[slot], result); plugin.items().stampBook(after[slot], EnchantBookSource.WORKSHOP);
                nextOff = new ItemStack(Material.AIR);
                if (!pay(after, slot, Material.LAPIS_LAZULI, 8) || !pay(after, slot, Material.DIAMOND, 1)) { return null; }
                cost = "8 лазурита + 1 алмаз";
            }
            case "split" -> {
                if (!book(main)) { return null; }
                var result = WorkshopPolicy.split(data, args[1], vanilla(main));
                if (result == null || !plugin.config().isEnabled(args[1])) { return null; }
                plugin.items().write(after[slot], result.remainder()); // Ванильные чары/чужая PDC остаются только здесь.
                if (!pay(after, slot, Material.BOOK, 1) || !pay(after, slot, Material.LAPIS_LAZULI, 8)) { return null; }
                int empty = -1;
                for (int i = 0; i < after.length; i++) if (after[i] == null || after[i].getType().isAir()) { empty = i; break; }
                if (empty < 0) { return null; }
                after[empty] = plugin.items().makeEnchantBook(EnchantRegistry.byId(args[1]), data.get(args[1]), EnchantBookSource.WORKSHOP);
                cost = "1 книга + 8 лазурита";
            }
            case "splitvanilla" -> {
                if (!book(main) || !(main.getItemMeta() instanceof EnchantmentStorageMeta meta)
                        || !meta.hasStoredEnchants()) { return null; }
                String id = args[1].toLowerCase(Locale.ROOT);
                if (!id.contains(":")) id = "minecraft:" + id;
                org.bukkit.enchantments.Enchantment selected = null;
                for (var enchantment : meta.getStoredEnchants().keySet()) {
                    if (enchantment.getKey().toString().equals(id)) { selected = enchantment; break; }
                }
                var result = WorkshopPolicy.splitVanilla(meta.getStoredEnchants(), selected);
                if (result == null || result.remainder().isEmpty() && data.isEmpty()) { return null; }
                // Original book retains all non-selected components, lore and foreign PDC.
                var originalMeta = (EnchantmentStorageMeta) after[slot].getItemMeta();
                if (!originalMeta.removeStoredEnchant(selected) || !originalMeta.getStoredEnchants().equals(result.remainder())
                        || !after[slot].setItemMeta(originalMeta)) { return null; }
                ItemStack extracted = new ItemStack(Material.ENCHANTED_BOOK);
                if (!(extracted.getItemMeta() instanceof EnchantmentStorageMeta extractedMeta)
                        || !extractedMeta.addStoredEnchant(selected, result.level(), true)
                        || !extractedMeta.getStoredEnchants().equals(Map.of(selected, result.level()))
                        || !extracted.setItemMeta(extractedMeta)) { return null; }
                if (!pay(after, slot, Material.BOOK, 1) || !pay(after, slot, Material.LAPIS_LAZULI, 8)) { return null; }
                int empty = -1;
                for (int i = 0; i < after.length; i++) if (after[i] == null || after[i].getType().isAir()) { empty = i; break; }
                if (empty < 0) { return null; }
                after[empty] = extracted;
                cost = "1 книга + 8 лазурита";
            }
            default -> throw new IllegalStateException("Unknown workshop operation");
        }
        return new Built(after, nextOff, cost);
    }
    /** Build against input slots and storage without moving items into the player's hands. */
    boolean applyGui(Player player, String action, String id, org.bukkit.inventory.Inventory top, int first, int second) {
        if (!Set.of("cleanse", "combine", "split", "splitvanilla").contains(action) || !permitted(player, action)) { fail(player); return false; }
        var original = copy(player.getInventory().getStorageContents());
        var before = Arrays.copyOf(copy(original), original.length + 1);
        int slot = original.length;
        before[slot] = clone(top.getItem(first));
        ItemStack off = clone(top.getItem(second));
        String[] args = action.equals("combine") ? new String[]{action} : new String[]{action, id};
        Built built = build(action, args, before, off, slot);
        if (built == null) { fail(player); return false; }
        try {
            player.getInventory().setStorageContents(Arrays.copyOf(built.after(), original.length));
            top.setItem(first, clone(built.after()[slot]));
            if (action.equals("combine")) top.setItem(second, clone(built.nextOff()));
        } catch (RuntimeException failure) {
            player.getInventory().setStorageContents(original);
            top.setItem(first, clone(before[slot])); top.setItem(second, clone(off));
            throw failure;
        }
        plugin.messages().send(player, "workshop-done");
        return true;
    }
    private static boolean book(ItemStack item) { return item != null && item.getType() == Material.ENCHANTED_BOOK; }
    private static boolean vanilla(ItemStack item) { return item.getItemMeta() instanceof EnchantmentStorageMeta meta && (meta.hasStoredEnchants() || meta.hasEnchants()); }
    private static boolean pay(ItemStack[] items, int excluded, Material material, int amount) {
        int[] available = new int[items.length];
        for (int i = 0; i < items.length; i++) {
            var item = items[i];
            if (i != excluded && item != null && item.getType() == material && !item.hasItemMeta()) available[i] = item.getAmount();
        }
        var next = MaterialDebit.plan(available, amount);
        if (next == null) return false;
        for (int i = 0; i < items.length; i++) if (next[i] != available[i]) {
            if (next[i] == 0) items[i] = null; else items[i].setAmount(next[i]);
        }
        return true;
    }
    private static ItemStack clone(ItemStack item) { return item == null ? null : item.clone(); }
    private static ItemStack[] copy(ItemStack[] items) { return Arrays.stream(items).map(EnchantWorkshop::clone).toArray(ItemStack[]::new); }
    private void fail(Player player) { plugin.messages().send(player, "workshop-rejected"); }
    @EventHandler public void quit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void death(PlayerDeathEvent event) { pending.remove(event.getEntity().getUniqueId()); }
    @EventHandler public void close(InventoryCloseEvent event) { pending.remove(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { pending.remove(event.getPlayer().getUniqueId()); }
}
