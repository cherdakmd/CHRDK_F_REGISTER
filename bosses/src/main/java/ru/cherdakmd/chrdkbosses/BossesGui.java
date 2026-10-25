package ru.cherdakmd.chrdkbosses;

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
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Меню боссов и зон (1.4.162).
 *
 * <p>Первый экран — боссы со статусом и фазами, ниже — зоны опасности. Меню
 * только показывает и печатает подробности в чат: спавн и награды живут в
 * {@link BossService}, поэтому клик не может выдать победу.</p>
 */
public final class BossesGui implements Listener {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int CLOSE_SLOT = 49;
    private static final int[] BOSS_SLOTS = {10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};
    private static final int[] ZONE_SLOTS = {37, 38, 39, 40, 41, 42, 43};

    /** Владелец меню: чужой игрок не может нажимать кнопки. */
    static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BossesPlugin plugin;

    public BossesGui(BossesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Открывает меню боссов. */
    public void open(Player player) {
        if (player == null) {
            return;
        }
        Holder holder = new Holder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("menu-title"));
        holder.inventory = inventory;
        inventory.setItem(INFO_SLOT, infoItem(player));

        long now = System.currentTimeMillis();
        List<BossDefinition> bosses = plugin.config().bossList();
        for (int index = 0; index < bosses.size() && index < BOSS_SLOTS.length; index++) {
            inventory.setItem(BOSS_SLOTS[index], bossItem(bosses.get(index), now));
        }
        List<DangerZone> zones = plugin.config().zoneList();
        for (int index = 0; index < zones.size() && index < ZONE_SLOTS.length; index++) {
            inventory.setItem(ZONE_SLOTS[index], zoneItem(zones.get(index)));
        }
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "menu-close"));
        player.openInventory(inventory);
    }

    /** Перерисовывает открытые меню после перезагрузки конфига. */
    public void refresh() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                open(player);
            }
        }
    }

    /* --------------------------------- предметы -------------------------------- */

    private ItemStack infoItem(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("menu-info-alive",
                "alive", String.valueOf(plugin.service().aliveCount()),
                "total", String.valueOf(plugin.config().bossList().size())));
        lore.add(plugin.messages().get("menu-info-kills",
                "kills", String.valueOf(plugin.store().total()),
                "bosses", String.valueOf(plugin.store().killsAll())));
        lore.add(plugin.messages().get("menu-info-zones",
                "zones", String.valueOf(plugin.config().activeZones().size())));
        lore.add(plugin.messages().get("menu-info-hint"));
        return item(Material.WITHER_SKELETON_SKULL, "menu-info-name", null, lore);
    }

    private ItemStack bossItem(BossDefinition definition, long now) {
        ActiveBoss active = plugin.service().current(definition.id());
        List<Component> lore = new ArrayList<>();
        if (!definition.description().isEmpty()) {
            lore.add(plugin.messages().get("menu-boss-description",
                    "description", definition.description()));
        }
        lore.add(plugin.messages().get("menu-boss-arena",
                "arena", definition.arena().label()));
        lore.add(plugin.messages().get("menu-boss-health",
                "health", String.valueOf((int) definition.health()),
                "phases", String.valueOf(definition.phases().size())));
        if (active != null) {
            lore.add(plugin.messages().get("menu-boss-alive",
                    "phase", active.phase().name(),
                    "percent", String.valueOf(Math.round(active.progress() * 100))));
            lore.add(plugin.messages().get("menu-boss-participants",
                    "count", String.valueOf(active.damage().size())));
        } else {
            long seconds = plugin.service().secondsToSpawn(definition.id(), now);
            if (seconds > 0) {
                lore.add(plugin.messages().get("menu-boss-cooldown",
                        "time", BossMath.clock(seconds)));
            } else {
                lore.add(plugin.messages().get("menu-boss-ready"));
            }
        }
        lore.add(plugin.messages().get("menu-boss-reward",
                "xp", String.valueOf(definition.reward().xp()),
                "items", String.valueOf(definition.reward().items().size())));
        lore.add(plugin.messages().get("menu-boss-click"));
        return item(definition.icon(), null, definition.name(), lore);
    }

    private ItemStack zoneItem(DangerZone zone) {
        List<Component> lore = new ArrayList<>();
        if (!zone.description().isEmpty()) {
            lore.add(plugin.messages().get("menu-zone-description",
                    "description", zone.description()));
        }
        lore.add(plugin.messages().get("menu-zone-level",
                "level", zone.levelLabel(),
                "radius", String.valueOf(Math.round(zone.radius()))));
        lore.add(plugin.messages().get("menu-zone-bonus",
                "percent", String.valueOf(zone.xpBonusPercent())));
        if (zone.damagePerSecond() > 0) {
            lore.add(plugin.messages().get("menu-zone-damage",
                    "damage", String.valueOf(zone.damagePerSecond())));
        }
        lore.add(plugin.messages().get(zone.enabled()
                ? "menu-zone-enabled" : "menu-zone-disabled"));
        return item(zone.icon(), null, zone.name(), lore);
    }

    private ItemStack button(Material material, String nameKey) {
        return item(material, nameKey, null, List.of());
    }

    private ItemStack item(Material material, String nameKey, String plainName,
                           List<Component> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (plainName != null) {
                meta.displayName(plugin.messages().get("menu-item-name", "name", plainName));
            } else if (nameKey != null) {
                meta.displayName(plugin.messages().get(nameKey));
            }
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /* --------------------------------- клики ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId())
                || event.getRawSlot() >= SIZE) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        int bossIndex = indexOf(BOSS_SLOTS, slot);
        if (bossIndex >= 0) {
            List<BossDefinition> bosses = plugin.config().bossList();
            if (bossIndex < bosses.size()) {
                describe(player, bosses.get(bossIndex));
            }
            return;
        }
        int zoneIndex = indexOf(ZONE_SLOTS, slot);
        if (zoneIndex >= 0) {
            List<DangerZone> zones = plugin.config().zoneList();
            if (zoneIndex < zones.size()) {
                describeZone(player, zones.get(zoneIndex));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void describe(Player player, BossDefinition definition) {
        ActiveBoss active = plugin.service().current(definition.id());
        plugin.messages().send(player, "boss-info-header", "name", definition.name());
        if (!definition.description().isEmpty()) {
            plugin.messages().send(player, "boss-info-description",
                    "description", definition.description());
        }
        plugin.messages().send(player, "boss-info-arena",
                "arena", definition.arena().label());
        plugin.messages().send(player, "boss-info-health",
                "health", String.valueOf((int) definition.health()),
                "phases", String.valueOf(definition.phases().size()));
        for (BossPhase phase : definition.phases()) {
            plugin.messages().send(player, "boss-info-phase",
                    "percent", String.valueOf(phase.fromPercent()),
                    "name", phase.name(),
                    "abilities", String.valueOf(phase.abilities().size()));
        }
        if (active != null) {
            plugin.messages().send(player, "boss-info-alive",
                    "phase", active.phase().name(),
                    "percent", String.valueOf(Math.round(active.progress() * 100)));
        } else {
            long seconds = plugin.service().secondsToSpawn(definition.id(),
                    System.currentTimeMillis());
            plugin.messages().send(player, seconds > 0 ? "boss-info-cooldown" : "boss-info-ready",
                    "time", BossMath.clock(seconds));
        }
        plugin.messages().send(player, "boss-info-reward",
                "xp", String.valueOf(definition.reward().xp()),
                "items", String.valueOf(definition.reward().items().size()));
    }

    private void describeZone(Player player, DangerZone zone) {
        plugin.messages().send(player, "zone-info-header", "name", zone.name());
        if (!zone.description().isEmpty()) {
            plugin.messages().send(player, "zone-info-description",
                    "description", zone.description());
        }
        plugin.messages().send(player, "zone-info-level",
                "level", zone.levelLabel(),
                "radius", String.valueOf(Math.round(zone.radius())));
        plugin.messages().send(player, "zone-info-bonus",
                "percent", String.valueOf(zone.xpBonusPercent()));
        if (zone.damagePerSecond() > 0) {
            plugin.messages().send(player, "zone-info-damage",
                    "damage", String.valueOf(zone.damagePerSecond()));
        }
        for (ZoneEffect effect : zone.effects()) {
            plugin.messages().send(player, "zone-info-effect",
                    "effect", effect.effect(),
                    "level", String.valueOf(effect.amplifier() + 1),
                    "seconds", String.valueOf(effect.durationSeconds()));
        }
    }

    private static int indexOf(int[] slots, int slot) {
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == slot) {
                return index;
            }
        }
        return -1;
    }
}
