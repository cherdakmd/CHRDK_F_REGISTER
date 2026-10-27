package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only справочник; серверный holder хранит фильтры, владельца и поколение reload. */
public final class EnchantGui implements Listener {
    private final EnchantsPlugin plugin;
    private boolean stopped;
    public EnchantGui(EnchantsPlugin plugin) { this.plugin = plugin; }
    private boolean eligible(Player player) {
        return !stopped && player.isOnline() && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player)
                && player.hasPermission("chrdkenchants.use");
    }
    public void open(Player player) { open(player, EnchantCatalog.Query.all(), 0); }
    void open(Player player, EnchantCatalog.Query query) { open(player, query, 0); }
    private void open(Player player, EnchantCatalog.Query query, int requested) {
        if (!eligible(player)) return;
        Map<String, String> names = EnchantRegistry.all().stream().collect(Collectors.toMap(EnchantDef::id,
                def -> plugin.messages().plain("en." + def.id() + ".name")));
        Set<String> applicable = query.hand() ? plugin.service().applicableTo(player.getInventory().getItemInMainHand())
                .stream().map(EnchantDef::id).collect(Collectors.toSet()) : Set.of();
        List<EnchantDef> selected = EnchantCatalog.select(query, names, applicable);
        int page = EnchantCatalog.page(requested, selected.size());
        var holder = new EnchantsHolder(player.getUniqueId(), plugin.sourceGeneration(), query, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, plugin.messages().get("gui-title",
                "page", page + 1, "pages", EnchantCatalog.pages(selected.size())));
        holder.inventory = inventory;
        List<EnchantDef> items = EnchantCatalog.slice(selected, page);
        for (int slot = 0; slot < items.size(); slot++) inventory.setItem(slot, enchantIcon(items.get(slot)));
        if (items.isEmpty()) inventory.setItem(22, button(Material.PAPER, "catalog-empty"));
        if (page > 0) inventory.setItem(45, button(Material.ARROW, "gui-prev"));
        if (page + 1 < EnchantCatalog.pages(selected.size())) inventory.setItem(53, button(Material.ARROW, "gui-next"));
        inventory.setItem(46, button(Material.HOPPER, "catalog-group", "value", Messages.trusted(query.group() == null
                ? plugin.messages().raw("catalog-all") : groupLabel(query.group()))));
        inventory.setItem(47, button(Material.AMETHYST_SHARD, "catalog-rarity", "value", query.rarity() == null
                ? plugin.messages().mini("catalog-all") : plugin.messages().mini(query.rarity().labelKey())));
        inventory.setItem(48, button(Material.SOUL_LANTERN, "catalog-polarity", "value", query.polarity() == null
                ? plugin.messages().mini("catalog-all") : plugin.messages().mini(query.polarity().labelKey())));
        inventory.setItem(49, button(Material.BOOK, "catalog-result", "count", selected.size(), "total", EnchantRegistry.size(),
                "search", query.search().isEmpty() ? "—" : query.search()));
        inventory.setItem(50, button(Material.ANVIL, "catalog-hand", "value", query.hand()
                ? plugin.messages().mini("catalog-on") : plugin.messages().mini("catalog-off")));
        inventory.setItem(51, button(Material.COMPARATOR, "catalog-sort", "value", plugin.messages().mini("catalog-sort-" + query.sort().name().toLowerCase(java.util.Locale.ROOT))));
        inventory.setItem(52, button(Material.BARRIER, "catalog-clear"));
        player.openInventory(inventory);
    }
    private ItemStack button(Material material, String key, Object... args) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(key, args));
            meta.lore(List.of(plugin.messages().get("catalog-hint")));
            item.setItemMeta(meta);
        }
        return item;
    }
    /** Иконка чары: материал по группе применимости, имя, редкость, предметы, описание. */
    private ItemStack enchantIcon(EnchantDef def) {
        Material icon = switch (def.supports().stream().min(java.util.Comparator.naturalOrder()).orElse(ItemGroup.SWORD)) {
            case SWORD, AXE -> Material.IRON_SWORD;
            case BOW, CROSSBOW -> Material.BOW;
            case HELMET -> Material.IRON_HELMET;
            case CHESTPLATE -> Material.IRON_CHESTPLATE;
            case LEGGINGS -> Material.IRON_LEGGINGS;
            case BOOTS -> Material.IRON_BOOTS;
            case PICKAXE -> Material.IRON_PICKAXE;
            case SHOVEL -> Material.IRON_SHOVEL;
            case HOE -> Material.IRON_HOE;
        };
        ItemStack item = new ItemStack(def.curse() ? Material.SOUL_LANTERN : icon);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(
                    def.curse() ? "lore-line-curse" : "lore-line",
                    "name", plugin.messages().mini("en." + def.id() + ".name"),
                    "levelroman", EnchantCodec.roman(def.maxLevel())));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(plugin.messages().get("gui-enchant-lore-polarity",
                    "polarity", plugin.messages().mini(def.polarity().labelKey())));
            lore.add(plugin.messages().get("gui-enchant-lore-rarity",
                    "rarity", plugin.messages().mini(def.rarity().labelKey()),
                    "maxlevel", def.maxLevel()));
            lore.add(plugin.messages().get("gui-enchant-lore-items",
                    "items", Messages.trusted(supportedLabel(def))));
            if (def.conflictGroup() != null) {
                lore.add(plugin.messages().get("info-conflicts",
                        "conflicts", Messages.trusted(conflictsLabel(def))));
            }
            lore.addAll(plugin.messages().getList("en." + def.id() + ".lore"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    String groupLabel(ItemGroup group) {
        return plugin.messages().raw("catalog-group-" + group.name().toLowerCase(java.util.Locale.ROOT));
    }
    public String supportedLabel(EnchantDef def) {
        if (def.supports().size() == ItemGroup.values().length) return plugin.messages().raw("item-any");
        return String.join(", ", def.supports().stream().sorted().map(this::groupLabel).toList());
    }
    String conflictsLabel(EnchantDef def) {
        return String.join(", ", EnchantCatalog.conflicts(def).stream()
                .map(id -> plugin.messages().raw("en." + id + ".name")).toList());
    }
    void stop() { stopped = true; closeAll(); }
    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof EnchantsHolder) player.closeInventory();
    }
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof EnchantsHolder holder)) return;
        boolean blocked = event.isCancelled();
        event.setCancelled(true); // Включая shift, number-key, double-click и клики нижнего инвентаря.
        if (blocked || !(event.getWhoClicked() instanceof Player player) || !eligible(player) || !holder.owner.equals(player.getUniqueId())
                || event.getRawSlot() < 0 || event.getRawSlot() >= 54 || holder.generation != plugin.sourceGeneration()) return;
        EnchantCatalog.Query query = holder.query;
        int page = holder.page;
        switch (event.getRawSlot()) {
            case 45 -> { if (page == 0) return; page--; }
            case 53 -> { if (event.getInventory().getItem(53) == null) return; page++; }
            case 46 -> { query = query.group(EnchantCatalog.next(query.group(), ItemGroup.values())); page = 0; }
            case 47 -> { query = query.rarity(EnchantCatalog.next(query.rarity(), EnchantDef.Rarity.values())); page = 0; }
            case 48 -> { query = query.polarity(EnchantCatalog.next(query.polarity(), EnchantDef.Polarity.values())); page = 0; }
            case 50 -> { query = query.hand(!query.hand()); page = 0; }
            case 51 -> { query = query.sort(EnchantCatalog.Sort.values()[(query.sort().ordinal() + 1) % EnchantCatalog.Sort.values().length]); page = 0; }
            case 52 -> { query = EnchantCatalog.Query.all(); page = 0; }
            default -> { return; }
        }
        EnchantCatalog.Query next = query; int nextPage = page;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (eligible(player) && holder.generation == plugin.sourceGeneration()
                    && player.getOpenInventory().getTopInventory().getHolder() == holder) open(player, next, nextPage);
        });
    }
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof EnchantsHolder) event.setCancelled(true);
    }
    private static final class EnchantsHolder implements InventoryHolder {
        final UUID owner;
        final long generation;
        final EnchantCatalog.Query query;
        final int page;
        Inventory inventory;
        EnchantsHolder(UUID owner, long generation, EnchantCatalog.Query query, int page) {
            this.owner = owner; this.generation = generation; this.query = query; this.page = page;
        }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
