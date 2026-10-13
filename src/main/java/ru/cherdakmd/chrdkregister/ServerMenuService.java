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

    private final AccessManager accessManager;
    private volatile Messages messages;

    public ServerMenuService(AccessManager accessManager, Messages messages) {
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
        ServerMenuSettings settings = accessManager.config().serverMenu();
        if (settings == null || !settings.enabled() || settings.entries().isEmpty()) {
            messages.send(player, "server-menu.disabled");
            return;
        }
        MenuLayout layout = layout(settings.entries());
        ServerMenuHolder holder = new ServerMenuHolder(layout.slots());
        Inventory inventory = Bukkit.createInventory(holder, layout.size(),
                messages.get("server-menu.title"));
        holder.bind(inventory);
        List<ServerMenuSettings.Entry> entries = settings.entries();
        for (int i = 0; i < entries.size() && i < layout.slots().size(); i++) {
            int slot = layout.slots().get(i);
            if (slot < 0 || slot >= inventory.getSize()) continue;
            inventory.setItem(slot, icon(entries.get(i)));
        }
        player.openInventory(inventory);
    }

    private ItemStack icon(ServerMenuSettings.Entry entry) {
        Material material = entry.icon() == null ? Material.PAPER : entry.icon();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Messages.format(entry.name()));
            if (!entry.lore().isBlank()) {
                meta.lore(List.of(Messages.format(entry.lore())));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ServerMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.inventory().equals(event.getClickedInventory())) return;
        ServerMenuSettings settings = accessManager.config().serverMenu();
        List<ServerMenuSettings.Entry> entries = settings.entries();
        List<Integer> slots = holder.slots();
        for (int i = 0; i < slots.size() && i < entries.size(); i++) {
            if (slots.get(i) != event.getRawSlot()) continue;
            ServerMenuSettings.Entry entry = entries.get(i);
            player.closeInventory();
            player.performCommand(entry.command());
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ServerMenuHolder) {
            event.setCancelled(true);
        }
    }

    /** Маркержолдер: отличает наше меню от чужих инвентарей, хранит слоты кликов. */
    static final class ServerMenuHolder implements InventoryHolder {
        private final List<Integer> slots;
        private Inventory inventory;

        ServerMenuHolder(List<Integer> slots) {
            this.slots = List.copyOf(slots);
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
