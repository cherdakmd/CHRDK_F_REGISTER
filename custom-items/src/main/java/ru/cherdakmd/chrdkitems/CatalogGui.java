package ru.cherdakmd.chrdkitems;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import java.util.Map;
import java.util.UUID;

/** A purely read-only catalog; visual icons never carry item identity or grant anything. */
final class CatalogGui implements Listener {
    private static final int SIZE = 54;
    private static final int[] ITEMS = {19,20,21,22,23,24,25,31};
    private final CustomItemsPlugin plugin;
    private final Map<String, ItemCatalog.Definition> catalog;
    CatalogGui(CustomItemsPlugin plugin, Map<String, ItemCatalog.Definition> catalog) { this.plugin = plugin; this.catalog = catalog; }
    private static final class Holder implements InventoryHolder {
        final UUID owner; final ItemCatalog.Category category; final int page; Inventory inventory;
        Holder(UUID owner, ItemCatalog.Category category, int page) { this.owner=owner; this.category=category; this.page=page; }
        @Override public Inventory getInventory() { return inventory; }
    }
    static int itemSlot(int index) {
        if(index<0 || index>=ITEMS.length) throw new IllegalArgumentException("invalid item index");
        return ITEMS[index];
    }
    static int categorySlot(ItemCatalog.Category c) {
        return switch (c) { case WEAPON -> 10; case ARMOR -> 11; case TOOL -> 12; case CONSUMABLE -> 13; };
    }
    void open(Player player, ItemCatalog.Category category, int page) {
        if (!player.isOnline() || !player.hasPermission("chrdkcustomitems.use")) return;
        var data = CatalogView.search(catalog, new CatalogView.Query(category, page));
        if (page < 1 || page > data.pages()) return;
        var holder = new Holder(player.getUniqueId(), category, page);
        holder.inventory = Bukkit.createInventory(holder, SIZE, Component.text("Каталог предметов · " + page + "/" + data.pages()));
        holder.inventory.setItem(9, icon(Material.NETHER_STAR, "Все предметы", "Посмотреть все категории"));
        holder.inventory.setItem(10, icon(Material.IRON_SWORD, "Оружие", "Выбрать категорию"));
        holder.inventory.setItem(11, icon(Material.IRON_CHESTPLATE, "Броня", "Выбрать категорию"));
        holder.inventory.setItem(12, icon(Material.IRON_PICKAXE, "Инструменты", "Выбрать категорию"));
        holder.inventory.setItem(13, icon(Material.PAPER, "Расходники", "Выбрать категорию"));
        for (int i=0; i<data.items().size(); i++) {
            var def=data.items().get(i);
            var display = new ItemStack(def.material());
            var meta = display.getItemMeta();
            meta.displayName(Component.text(def.name()));
            var lore = new java.util.ArrayList<Component>();
            lore.add(Component.text("ID: " + def.id()));
            lore.add(Component.text("Категория: " + CatalogView.categoryName(def.category())));
            def.description().forEach(line -> lore.add(Component.text(line)));
            var effect=PrototypeEffects.effect(def);
            if (effect != null) lore.add(Component.text(effect.description));
            if (BuiltinUse.supports(def)) lore.add(Component.text("ПКМ в основной руке"));
            lore.add(Component.text("Только просмотр · рецепт не выдан"));
            meta.lore(lore);
            Integer model=PrototypeVisuals.modelData(def);
            if (model != null && (BuiltinItems.isBuiltin(def) || plugin.getConfig().getBoolean("prototype-textures",false))) meta.setCustomModelData(model);
            display.setItemMeta(meta); holder.inventory.setItem(itemSlot(i),display);
        }
        if(page>1) holder.inventory.setItem(45,icon(Material.ARROW,"Назад","Предыдущая страница"));
        if(page<data.pages()) holder.inventory.setItem(53,icon(Material.ARROW,"Далее","Следующая страница"));
        holder.inventory.setItem(49,icon(Material.BARRIER,"Закрыть","Каталог не выдаёт предметы"));
        player.openInventory(holder.inventory);
    }
    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) player.closeInventory();
    }
    private static ItemStack icon(Material material, String name, String line) {
        var item = new ItemStack(material); var meta=item.getItemMeta();
        meta.displayName(Component.text(name)); meta.lore(java.util.List.of(Component.text(line))); item.setItemMeta(meta); return item;
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        boolean alreadyCancelled=event.isCancelled();
        event.setCancelled(true);
        if (alreadyCancelled) return;
        if (!(event.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || !p.hasPermission("chrdkcustomitems.use")) return;
        if (event.getClick() != org.bukkit.event.inventory.ClickType.LEFT && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
        int slot=event.getRawSlot();
        if(slot==49) {
            Bukkit.getScheduler().runTask(plugin, () -> { if(p.isOnline() && p.getOpenInventory().getTopInventory().getHolder()==h) p.closeInventory(); });
            return;
        }
        ItemCatalog.Category category = switch(slot) { case 10 -> ItemCatalog.Category.WEAPON; case 11 -> ItemCatalog.Category.ARMOR; case 12 -> ItemCatalog.Category.TOOL; case 13 -> ItemCatalog.Category.CONSUMABLE; default -> null; };
        int page=slot==45?h.page-1:slot==53?h.page+1:1;
        if (!(slot==9 || category!=null || slot==45 || slot==53)) return;
        if(slot==45 || slot==53) category=h.category;
        var chosen=category;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder()==h) open(p,chosen,page);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=false)
    public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
