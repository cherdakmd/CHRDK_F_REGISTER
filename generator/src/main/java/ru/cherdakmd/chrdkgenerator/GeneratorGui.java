package ru.cherdakmd.chrdkgenerator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.List;
import java.util.UUID;

/**
 * Единое окно {@code /gen} (п.31, §9): статус камеры, тир, бункер,
 * улучшения (ЛКМ — купить), снятие ядра. Сетка как в {@code /skills}:
 * один инвентарь на 54 слота, пустое закрыто рамкой.
 */
public final class GeneratorGui implements Listener {

    /** Состояние окна: владелец и сам инвентарь. */
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

    private static final int SLOT_STATUS = 4;
    private static final int SLOT_CLOSE = 8;
    private static final int SLOT_TIER = 10;
    private static final int SLOT_BUNKER = 12;
    private static final int SLOT_REBIRTH = 14;
    private static final int SLOT_UPGRADE_TIER = 20;
    private static final int SLOT_SPEED = 21;
    private static final int SLOT_CAPACITY = 22;
    private static final int SLOT_LUCK = 23;
    private static final int SLOT_CHUNK = 24;
    private static final int SLOT_TAKE = 49;

    private static final Material[] TIER_ICONS = {
            Material.COBBLESTONE, Material.CLAY_BALL, Material.RAW_IRON, Material.RAW_GOLD,
            Material.DIAMOND, Material.NETHERITE_INGOT, Material.NETHER_STAR
    };

    private final GeneratorPlugin plugin;
    private final java.util.Random random = new java.util.Random();

    public GeneratorGui(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    /** Открыть окно; повторный вызов перерисовывает уже открытое. */
    public void open(Player player) {
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId());
        if (entry == null) {
            plugin.messages().send(player, "status-none");
            return;
        }
        if (entry.installed() && !nearCore(player, entry)) {
            plugin.messages().send(player, "menu-too-far");
            return;
        }
        Holder holder = currentHolder(player);
        boolean fresh = holder == null;
        if (fresh) {
            holder = new Holder(player.getUniqueId());
            holder.inventory = Bukkit.createInventory(holder, 54, plugin.messages().get("menu-title"));
        }
        render(player, holder);
        if (fresh) {
            player.openInventory(holder.inventory);
        }
    }

    private Holder currentHolder(Player player) {
        var view = player.getOpenInventory();
        if (view != null && view.getTopInventory().getHolder() instanceof Holder holder
                && holder.owner.equals(player.getUniqueId())) {
            return holder;
        }
        return null;
    }

    /** Владелец у своего ядра: тот же чанк или в пределах радиуса доступа. */
    private boolean nearCore(Player player, GeneratorStore.Entry entry) {
        if (!player.getWorld().getName().equals(entry.world())) {
            return false;
        }
        var location = player.getLocation();
        return GeneratorMath.withinMenuRange(location.getBlockX() >> 4, location.getBlockZ() >> 4,
                location.getX(), location.getY(), location.getZ(),
                entry.x(), entry.y(), entry.z());
    }

    /* --------------------------------- отрисовка -------------------------------- */

    private void render(Player player, Holder holder) {
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId());
        Inventory inventory = holder.inventory;
        inventory.clear();
        if (entry != null) {
            inventory.setItem(SLOT_STATUS, statusItem(entry));
            inventory.setItem(SLOT_TIER, tierItem(entry));
            inventory.setItem(SLOT_BUNKER, bunkerItem(entry));
            inventory.setItem(SLOT_REBIRTH, rebirthItem(entry));
            inventory.setItem(SLOT_UPGRADE_TIER, upgradeTierItem(entry));
            inventory.setItem(SLOT_SPEED, moduleItem("speed", entry));
            inventory.setItem(SLOT_CAPACITY, moduleItem("capacity", entry));
            inventory.setItem(SLOT_LUCK, moduleItem("luck", entry));
            inventory.setItem(SLOT_CHUNK, chunkItem(entry));
            inventory.setItem(SLOT_TAKE, button(Material.ENDER_EYE, "menu-take", "menu-take-lore"));
        }
        inventory.setItem(SLOT_CLOSE, button(Material.BARRIER, "menu-close", null));
        fillEmpty(inventory);
    }

    private void fillEmpty(Inventory inventory) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, pane.clone());
            }
        }
    }

    private ItemStack statusItem(GeneratorStore.Entry entry) {
        String state = entry.installed() ? plugin.stateOf(entry.owner()) : "state-carried";
        ItemStack item = new ItemStack(Material.LODESTONE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("menu-status",
                "state", Messages.trusted(plugin.messages().raw(state)))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lines("menu-status-lore"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack tierItem(GeneratorStore.Entry entry) {
        ItemStack item = new ItemStack(TIER_ICONS[Math.max(0, Math.min(TIER_ICONS.length - 1, entry.tier() - 1))]);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("menu-tier",
                "tier", String.valueOf(entry.tier()),
                "tier_name", plugin.config().tier(entry.tier()).name())
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lines("menu-tier-lore", "units",
                String.valueOf(plugin.config().tier(entry.tier()).unitsPerCycle())));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack bunkerItem(GeneratorStore.Entry entry) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("menu-bunker",
                "bunker", String.valueOf(entry.bunker()),
                "capacity", String.valueOf(GeneratorStats.capacityUnits(entry, plugin.config())),
                "fill", String.valueOf(GeneratorStats.fillPercent(entry, plugin.config())))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lines("menu-bunker-lore"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack rebirthItem(GeneratorStore.Entry entry) {
        ItemStack item = new ItemStack(Material.NETHERITE_INGOT);
        ItemMeta meta = item.getItemMeta();
        double bonus = GeneratorMath.rebirthBonusPercent(
                plugin.config().rebirthPercentPerRebirth(), plugin.config().rebirthDecay(),
                plugin.config().rebirthBonusCapPercent(), entry.rebirths());
        meta.displayName(plugin.messages().get("menu-rebirth",
                "count", String.valueOf(entry.rebirths()),
                "bonus", String.format(java.util.Locale.ROOT, "%.1f", bonus))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lines("menu-rebirth-lore"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack upgradeTierItem(GeneratorStore.Entry entry) {
        String cost = entry.tier() >= GeneratorConfig.TIERS
                ? "—"
                : GeneratorUpgrades.describe(GeneratorUpgrades.tierCost(plugin.config(), entry.tier() + 1));
        return button(Material.LADDER, "menu-upgrade-tier", "menu-upgrade-lore", "cost", cost);
    }

    private ItemStack moduleItem(String name, GeneratorStore.Entry entry) {
        int level = entry.modules().getOrDefault(name, 0);
        String cost = level >= GeneratorStore.MAX_LEVEL
                ? "—"
                : GeneratorUpgrades.describe(GeneratorUpgrades.moduleCost(plugin.config(), level + 1));
        ItemStack item = new ItemStack(switch (name) {
            case "speed" -> Material.SUGAR;
            case "capacity" -> Material.BARREL;
            default -> Material.LAPIS_LAZULI;
        });
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get("menu-module",
                "module", plugin.messages().plain("upgrade-name-" + name),
                "level", String.valueOf(level))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lines("menu-module-lore",
                "cost", cost, "max", String.valueOf(GeneratorStore.MAX_LEVEL)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack chunkItem(GeneratorStore.Entry entry) {
        String cost = GeneratorUpgrades.describe(plugin.config().chunkCost());
        return button(Material.ENDER_PEARL, "menu-chunk", "menu-chunk-lore", "cost", cost);
    }

    private ItemStack button(Material material, String nameKey, String loreKey, String... placeholders) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().get(nameKey, (Object[]) placeholders)
                .decoration(TextDecoration.ITALIC, false));
        if (loreKey != null) {
            meta.lore(lines(loreKey, placeholders));
        }
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> lines(String key, String... placeholders) {
        return plugin.messages().getList(key, (Object[]) placeholders).stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    /* ---------------------------------- клики ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.inventory.getSize()) {
            return;
        }
        switch (slot) {
            case SLOT_CLOSE -> player.closeInventory();
            case SLOT_BUNKER -> withdrawBunker(player, holder);
            case SLOT_UPGRADE_TIER -> buy(player, holder, () -> plugin.upgrades().upgradeTier(player));
            case SLOT_SPEED -> buy(player, holder, () -> plugin.upgrades().upgradeModule(player, "speed"));
            case SLOT_CAPACITY -> buy(player, holder, () -> plugin.upgrades().upgradeModule(player, "capacity"));
            case SLOT_LUCK -> buy(player, holder, () -> plugin.upgrades().upgradeModule(player, "luck"));
            case SLOT_CHUNK -> buy(player, holder, () -> plugin.upgrades().buyChunk(player));
            case SLOT_TAKE -> take(player, holder);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void buy(Player player, Holder holder, Runnable action) {
        action.run();
        render(player, holder);
    }

    /** «Забрать всё»: бункер выдаётся предметами, излишек под ноги. */
    private void withdrawBunker(Player player, Holder holder) {
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId());
        if (entry == null || entry.bunker() <= 0) {
            plugin.messages().send(player, "bunker-empty");
            return;
        }
        long units = plugin.store().withdrawBunker(player.getUniqueId(), entry.bunker());
        GeneratorHandouts.giveBunker(player, plugin.config(), entry, units, random);
        plugin.messages().send(player, "bunker-withdrawn", "bunker", String.valueOf(units));
        render(player, holder);
    }

    /** Снятие ядра с двухшаговым подтверждением (TTL 30 секунд). */
    private void take(Player player, Holder holder) {
        GeneratorTake.Result result = plugin.take().request(player);
        switch (result.status()) {
            case NO_GENERATOR -> plugin.messages().send(player, "take-none");
            case PENDING -> {
                plugin.messages().send(player, "take-confirm");
                render(player, holder);
            }
            case DONE -> {
                plugin.messages().send(player, "take-ok", "bunker", String.valueOf(result.units()));
                player.closeInventory();
            }
        }
    }
}
