package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.plugin.Plugin;

/** Central read-only navigation; menu icons are not real inventory items. */
final class FishMainGui implements Listener {
    @FunctionalInterface interface Open { void open(Player player) throws Exception; }
    private final Plugin plugin;
    private final Predicate<Player> authenticated;
    private final Open album, sale, rods, cooking;
    private Open shop, baitEquip, orders;
    private static final class Holder implements InventoryHolder {
        final UUID owner; Inventory inventory;
        Holder(UUID owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    FishMainGui(Plugin plugin, Predicate<Player> authenticated, Open album, Open sale, Open rods, Open cooking) {
        this.plugin = Objects.requireNonNull(plugin); this.authenticated = Objects.requireNonNull(authenticated);
        this.album = Objects.requireNonNull(album); this.sale = Objects.requireNonNull(sale); this.rods = Objects.requireNonNull(rods); this.cooking = Objects.requireNonNull(cooking);
    }
    void setBaitSections(Open shop, Open baitEquip) {
        this.shop = Objects.requireNonNull(shop); this.baitEquip = Objects.requireNonNull(baitEquip);
    }
    void setOrders(Open orders) { this.orders = Objects.requireNonNull(orders); }
    void open(Player player) {
        if (!player.isOnline() || !authenticated.test(player)) { player.sendMessage("§cСначала авторизуйтесь."); return; }
        Holder holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("Рыбалка"));
        boolean visuals = plugin.getConfig().getBoolean("textures.menu", false);
        holder.inventory.setItem(10, menuIcon(FishMenuVisuals.Section.ALBUM, true, visuals, Material.BOOK, "Альбом рыбака", "Коллекция и личные рекорды"));
        holder.inventory.setItem(12, menuIcon(FishMenuVisuals.Section.SALE, true, visuals, Material.CHEST, "Продажа рыбы", "Нажмите на рыбу в инвентаре, затем продайте"));
        holder.inventory.setItem(14, menuIcon(FishMenuVisuals.Section.ROD, true, visuals, Material.FISHING_ROD, "Улучшение удочки", "Выберите удочку, чтобы увидеть цену, ресурсы и бонусы.", "Оплата с /bal после подтверждения."));
        holder.inventory.setItem(16, menuIcon(FishMenuVisuals.Section.BAIT_SHOP, shop != null, visuals, Material.CHEST, "Магазин приманок", shop == null ? "Пока недоступен" : "Купить приманку с банкового счёта /bal"));
        holder.inventory.setItem(18, menuIcon(FishMenuVisuals.Section.BAIT_EQUIP, baitEquip != null, visuals, Material.PAPER, "Выбор приманки", baitEquip == null ? "Пока недоступен" : "Выбрать купленную приманку"));
        holder.inventory.setItem(20, menuIcon(FishMenuVisuals.Section.ORDERS, orders != null, visuals, Material.WRITABLE_BOOK, "Заказы рыбаку", "Выберите заказ, подходящую рыбу и подтвердите сдачу."));
        holder.inventory.setItem(22, menuIcon(FishMenuVisuals.Section.COOKING, true, visuals, Material.FURNACE, "Готовка", "Выберите рецепт и рыбу; для ухи нужна миска."));
        player.openInventory(holder.inventory);
    }
    private static ItemStack menuIcon(FishMenuVisuals.Section section, boolean available, boolean enabled,
                                      Material material, String name, String... lore) {
        var item = icon(material, name, lore);
        if (enabled) {
            var meta = item.getItemMeta();
            FishMenuVisuals.apply(meta, section, available, true);
            if (meta != null) item.setItemMeta(meta);
        }
        return item;
    }
    // Shared by other Fishing GUIs: keep this helper and their items unchanged.
    static ItemStack icon(Material material, String name, String... lore) {
        var item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.Arrays.stream(lore).map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta); return item;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        int slot = event.getRawSlot();
        Open target = switch (slot) { case 10 -> album; case 12 -> sale; case 14 -> rods; case 16 -> shop; case 18 -> baitEquip; case 20 -> orders; case 22 -> cooking; default -> null; };
        if (target == null) { if (slot == 16 || slot == 18) player.sendMessage("§eЭтот раздел ещё в разработке."); return; }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !authenticated.test(player)
                    || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            try { target.open(player); }
            catch (Exception error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Раздел рыбалки недоступен", error);
                player.sendMessage("§cРаздел недоступен. Обратитесь к администрации.");
            }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
