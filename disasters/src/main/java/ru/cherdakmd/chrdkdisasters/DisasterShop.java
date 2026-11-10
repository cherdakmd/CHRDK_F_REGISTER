package ru.cherdakmd.chrdkdisasters;

import net.kyori.adventure.text.Component;
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
import org.bukkit.plugin.Plugin;
import ru.cherdakmd.chrdkregister.RegisterPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Магазин осколков: {@code /disasters shop} открывает GUI на 27 слотов,
 * {@code /disasters buy <id>} покупает товар без GUI. Три товара — алмазы на
 * счёт, ванильные припасы и временный пропуск катастроф.
 *
 * <p>Алмазы идут через {@code BankStore.deposit} ядра, а не физическим дропом:
 * катастрофа не влияет на dailyCap CHRDK_Skills и не создаёт «добычу». Если
 * банк недоступен, осколки возвращаются игроку. Припасы кладутся в инвентарь,
 * остаток дропается рядом обычным ванильным способом.</p>
 */
public final class DisasterShop implements Listener {

    /** Один товар: цена в осколках, иконка, слот и ключ названия в messages.yml. */
    public record Item(String id, int price, Material icon, int slot, String nameKey) {
    }

    private static final int SIZE = 27;
    private static final int SLOT_BALANCE = 4;
    private static final int SLOT_CLOSE = 26;

    private final DisastersPlugin plugin;

    public DisasterShop(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    public List<Item> items() {
        DisasterSettings.Shop shop = plugin.settings().shop();
        return List.of(
                new Item("diamonds", shop.diamondsPrice(), Material.DIAMOND, 10,
                        "shop-item-diamonds"),
                new Item("supplies", shop.suppliesPrice(), Material.GOLDEN_APPLE, 13,
                        "shop-item-supplies"),
                new Item("ward", shop.wardPrice(), Material.PRISMARINE_SHARD, 16,
                        "shop-item-ward"));
    }

    public Item byId(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        for (Item item : items()) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        return null;
    }

    public void open(Player player) {
        if (player == null) {
            return;
        }
        Holder holder = new Holder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("shop-title"));
        holder.inventory.setItem(SLOT_BALANCE, balanceIcon(player));
        for (Item item : items()) {
            holder.inventory.setItem(item.slot(), icon(player, item));
        }
        holder.inventory.setItem(SLOT_CLOSE,
                button(Material.BARRIER, "shop-close"));
        player.openInventory(holder.inventory);
    }

    /** @return удалось ли купить товар. */
    public boolean buy(Player player, String rawId) {
        if (player == null) {
            return false;
        }
        Item item = byId(rawId);
        if (item == null) {
            plugin.messages().send(player, "shop-unknown");
            return false;
        }
        DisasterProfile profile = plugin.progress().profile(player.getUniqueId());
        if (!profile.spend(item.price())) {
            plugin.messages().send(player, "shop-no-funds",
                    "price", item.price(), "shards", profile.shards());
            return false;
        }
        DisasterSettings.Shop shop = plugin.settings().shop();
        boolean delivered = switch (item.id()) {
            case "diamonds" -> deposit(player, shop.diamondsAmount());
            case "supplies" -> supplies(player, shop.suppliesDiamonds());
            case "ward" -> ward(player, shop.wardMinutes());
            default -> false;
        };
        if (!delivered) {
            profile.refund(item.price());
            plugin.progress().saveNow();
            plugin.messages().send(player, "shop-failed");
            return false;
        }
        plugin.progress().saveNow();
        plugin.messages().send(player, "shop-bought",
                "item", plugin.messages().raw(item.nameKey()),
                "price", item.price(), "shards", profile.shards());
        return true;
    }

    private boolean deposit(Player player, int amount) {
        Plugin core = Bukkit.getPluginManager().getPlugin("CHRDK_Core");
        if (!(core instanceof RegisterPlugin register)) {
            return false;
        }
        return register.bankStore().deposit(
                player.getUniqueId(), player.getName(), amount) >= 0L;
    }

    private boolean supplies(Player player, int diamonds) {
        List<ItemStack> goods = List.of(
                new ItemStack(Material.DIAMOND, Math.max(1, diamonds)),
                new ItemStack(Material.GOLDEN_APPLE, 1),
                new ItemStack(Material.OBSIDIAN, 4));
        for (Map.Entry<Integer, ItemStack> leftover
                : player.getInventory().addItem(goods.toArray(new ItemStack[0])).entrySet()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.getValue());
        }
        return true;
    }

    private boolean ward(Player player, int minutes) {
        plugin.progress().profile(player.getUniqueId())
                .grantWard(System.currentTimeMillis(), minutes * 60_000L);
        return true;
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    private ItemStack icon(Player player, Item item) {
        ItemStack stack = new ItemStack(item.icon());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.messages().get(item.nameKey()));
        long shards = plugin.progress().profile(player.getUniqueId()).shards();
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("shop-price", "price", item.price()));
        lore.add(plugin.messages().get("shop-balance-line", "shards", shards));
        lore.add(plugin.messages().get(
                shards >= item.price() ? "shop-can-buy" : "shop-cannot-buy"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack balanceIcon(Player player) {
        DisasterProfile profile = plugin.progress().profile(player.getUniqueId());
        DisasterSettings.Progression progression = plugin.settings().progression();
        int level = DisasterProgressMath.levelForXp(profile.xp(),
                progression.baseXp(), progression.maxLevel());
        long left = DisasterProgressMath.xpToNext(profile.xp(), level,
                progression.baseXp(), progression.maxLevel());
        ItemStack stack = new ItemStack(Material.PRISMARINE_SHARD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.messages().get("shop-balance-title"));
        meta.lore(List.of(
                plugin.messages().get("shop-balance-line", "shards", profile.shards()),
                plugin.messages().get("shop-level-line", "level", level, "xp", left),
                plugin.messages().get("shop-kinds-line", "kinds", profile.kindCount())));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack button(Material material, String key) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.messages().get(key));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)
                || !(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        for (Item item : items()) {
            if (item.slot() == slot) {
                if (buy(player, item.id())) {
                    player.closeInventory();
                }
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
