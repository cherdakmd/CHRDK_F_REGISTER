package ru.cherdakmd.chrdkregister;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Полноценное серверное меню {@code /menu} для игроков: конфиг-driven инвентарь,
 * из которого одним кликом уходят в меню проходки, донатов, ежедневок, телепортов
 * и любых аддонов — ядро про аддоны не знает, пункты задаются в
 * {@code server-menu.items} (slot/icon/name/lore/command).
 *
 * <p>Авто-раскладка: явные слоты уважаются, остальные иконки встают рядами
 * 10..16 / 19..25 / 28..34 / 37..43 / 46..52. Размер инвентаря подбирается
 * минимальный, куда всё помещается (27/36/45/54).</p>
 */
public final class ServerMenuService implements Listener {

    /** Авто-слоты по рядам (центральные 7 колонок каждого ряда). */
    private static final int[] AUTO_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
            46, 47, 48, 49, 50, 51, 52};

    /** Итог раскладки: размер инвентаря и слот каждого пункта. */
    public record MenuLayout(int size, List<Integer> slots) {
    }

    private final RegisterPlugin plugin;
    private final AccessManager accessManager;
    private volatile Messages messages;

    public ServerMenuService(RegisterPlugin plugin, AccessManager accessManager, Messages messages) {
        this.plugin = plugin;
        this.accessManager = accessManager;
        this.messages = messages;
    }

    /** Обновляет тексты после {@code /pass reload}. */
    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    /**
     * Раскладка пунктов по слотам: явный слот достаётся первому претенденту,
     * остальные берут ближайшие свободные из {@link #AUTO_SLOTS} по порядку.
     * Размер — минимальный сундук, куда влезают и явные слоты, и авто-иконки.
     * Отделена для юнит-тестов.
     */
    public static MenuLayout layout(List<ServerMenuSettings.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new MenuLayout(27, List.of());
        }
        java.util.List<Integer> slots = new java.util.ArrayList<>(
                java.util.Collections.nCopies(entries.size(), (Integer) null));
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        int maxExplicit = -1;
        for (int i = 0; i < entries.size(); i++) {
            int slot = entries.get(i).slot();
            if (slot >= 0 && taken.add(slot)) {
                slots.set(i, slot);
                maxExplicit = Math.max(maxExplicit, slot);
            }
        }
        int size = sizeFor(maxExplicit);
        long autoNeeded = slots.stream().filter(java.util.Objects::isNull).count();
        while (size < 54 || autoNeeded > 0) {
            long freeAuto = 0;
            for (int autoSlot : AUTO_SLOTS) {
                if (autoSlot < size && !taken.contains(autoSlot)) {
                    freeAuto++;
                }
            }
            if (freeAuto >= autoNeeded) {
                break;
            }
            if (size >= 54) {
                break;
            }
            size = sizeFor(size); // 27→36→45→54
        }
        int autoIndex = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) != null) continue;
            while (autoIndex < AUTO_SLOTS.length
                    && (AUTO_SLOTS[autoIndex] >= size || taken.contains(AUTO_SLOTS[autoIndex]))) {
                autoIndex++;
            }
            if (autoIndex < AUTO_SLOTS.length) {
                slots.set(i, AUTO_SLOTS[autoIndex]);
                taken.add(AUTO_SLOTS[autoIndex]);
            } else {
                slots.set(i, -1); // совсем не влезло — иконку пропускаем
            }
        }
        return new MenuLayout(size, java.util.List.copyOf(slots));
    }

    /** Минимальный размер сундука, куда влезают все явные слоты. */
    static int sizeFor(int maxExplicitSlot) {
        if (maxExplicitSlot < 0) return 27;
        if (maxExplicitSlot < 27) return 27;
        if (maxExplicitSlot < 36) return 36;
        if (maxExplicitSlot < 45) return 45;
        return 54;
    }

    /** Сколько авто-слотов помещается в инвентарь данного размера. */
    static int autoCapacity(int size) {
        int capacity = 0;
        for (int slot : AUTO_SLOTS) {
            if (slot < size) capacity++;
        }
        return capacity;
    }

    /** Открывает серверное меню игроку ({@code /menu}). */
    public void openMenu(Player player) {
        if (!player.hasPermission("chrdkregister.menu")) { messages.send(player, "server-menu.no-permission"); return; }
        if (accessManager.getStage(player) != AccessManager.PlayerStage.AUTHENTICATED) {
            messages.send(player, "server-menu.need-auth"); return;
        }
        ServerMenuSettings settings = accessManager.config().serverMenu();
        if (settings == null || !settings.enabled() || settings.entries().isEmpty()) {
            messages.send(player, "server-menu.disabled");
            return;
        }
        List<ServerMenuSettings.Entry> entries = settings.entries().stream().filter(entry -> available(entry, player)).toList();
        if (entries.isEmpty()) { messages.send(player, "server-menu.disabled"); return; }
        MenuLayout layout = layout(entries);
        ServerMenuHolder holder = new ServerMenuHolder(player.getUniqueId(), layout.slots(), entries);
        Inventory inventory = Bukkit.createInventory(holder, layout.size(),
                messages.get("server-menu.title"));
        holder.bind(inventory);
        for (int i = 0; i < entries.size() && i < layout.slots().size(); i++) {
            int slot = layout.slots().get(i);
            if (slot < 0 || slot >= inventory.getSize()) continue;
            inventory.setItem(slot, icon(entries.get(i), player));
        }
        player.openInventory(inventory);
    }

    private boolean available(ServerMenuSettings.Entry entry, Player player) {
        return entry.available(name -> Bukkit.getPluginManager().isPluginEnabled(name), player::hasPermission);
    }

    private ItemStack icon(ServerMenuSettings.Entry entry, Player player) {
        Material material = entry.icon() == null ? Material.PAPER : entry.icon();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.renderTemplate(entry.name(), menuPlaceholders(player)));
            if (!entry.lore().isBlank()) {
                meta.lore(java.util.Arrays.stream(entry.lore().split("\\R")).map(line -> messages.renderTemplate(line, menuPlaceholders(player))).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private Object[] menuPlaceholders(Player player) {
        var account = accessManager.store().getAccount(player.getName()).orElse(null);
        long now = System.currentTimeMillis();
        return new Object[]{"player", player.getName(), "pass_left", PlaceholderValues.passLeft(account, now),
                "pass_days", account == null ? 0 : PlaceholderValues.wholeDaysLeft(account.passUntil, now),
                "trial_days", accessManager.config().trialDays()};
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ServerMenuHolder holder)) return;
        event.setCancelled(true); // also bottom inventory/shift/hotbar/double-click
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        if (event.getClick() != org.bukkit.event.inventory.ClickType.LEFT && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
        for (int i = 0; i < holder.slots.size(); i++) {
            if (holder.slots.get(i) != event.getRawSlot()) continue;
            ServerMenuSettings.Entry entry = holder.entries.get(i);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != holder.inventory) return;
                if (!accessManager.config().serverMenu().enabled()
                        || !accessManager.config().serverMenu().entries().contains(entry)) {
                    player.closeInventory(); messages.send(player, "server-menu.changed"); return;
                }
                if (!player.hasPermission("chrdkregister.menu") || !available(entry, player)
                        || accessManager.getStage(player) != AccessManager.PlayerStage.AUTHENTICATED) {
                    player.closeInventory(); messages.send(player, "server-menu.unavailable"); return;
                }
                player.closeInventory();
                player.performCommand(entry.command());
            });
            return;
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ServerMenuHolder) event.setCancelled(true);
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ServerMenuHolder) player.closeInventory();
    }

    /** Маркержолдер: отличает наше меню от чужих инвентарей, хранит слоты кликов. */
    static final class ServerMenuHolder implements InventoryHolder {
        private final List<Integer> slots;
        private final List<ServerMenuSettings.Entry> entries;
        private final java.util.UUID owner;
        private Inventory inventory;

        ServerMenuHolder(java.util.UUID owner, List<Integer> slots, List<ServerMenuSettings.Entry> entries) {
            this.owner = owner;
            this.slots = List.copyOf(slots);
            this.entries = List.copyOf(entries);
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        List<Integer> slots() {
            return slots;
        }

        Inventory inventory() {
            return inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
