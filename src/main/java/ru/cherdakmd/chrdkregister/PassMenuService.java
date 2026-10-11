package ru.cherdakmd.chrdkregister;

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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Инвентарное GUI-меню {@code /pass menu} (пункт из docs/IMPROVEMENTS.md, Приложение B):
 * вместо длинных текстовых справок игрок кликает по иконкам — «Статус», «Оплатить / продлить»,
 * «Тарифы», «2FA», «Сменить пароль», «Сессии» и «Статистика» (только для администраторов).
 *
 * <p>Действия делегируются обычным командам ({@code /pass}, {@code /2fa status}, {@code /session} и
 * т.д.), поэтому тексты и логика не дублируются. Состав меню зависит от стадии игрока: без
 * проходки видны только «Оплатить», «Тарифы» и «Статус».</p>
 */
public final class PassMenuService implements Listener {

    /** Действие по клику на иконку меню. */
    public enum Action {
        STATUS,
        PAYMENT,
        TIERS,
        TWO_FACTOR,
        PASSWORD,
        SESSIONS,
        STATS
    }

    /** Слот и действие одной иконки (выделено для юнит-тестов раскладки). */
    public record MenuEntry(int slot, Action action) {
    }

    /** Размер инвентаря (3 ряда). */
    public static final int INVENTORY_SIZE = 27;

    private final AccessManager accessManager;
    private volatile Messages messages;

    public PassMenuService(AccessManager accessManager, Messages messages) {
        this.accessManager = accessManager;
        this.messages = messages;
    }

    /** Обновляет тексты после {@code /pass reload}. */
    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    /**
     * Раскладка иконок: без проходки — только оплата, тарифы и статус; авторизованному —
     * все шесть пунктов; администратору дополнительно «Статистика».
     */
    public static List<MenuEntry> layout(boolean authenticated, boolean admin) {
        List<MenuEntry> entries = new ArrayList<>();
        if (!authenticated) {
            entries.add(new MenuEntry(11, Action.PAYMENT));
            entries.add(new MenuEntry(13, Action.TIERS));
            entries.add(new MenuEntry(15, Action.STATUS));
            return entries;
        }
        entries.add(new MenuEntry(10, Action.STATUS));
        entries.add(new MenuEntry(11, Action.PAYMENT));
        entries.add(new MenuEntry(12, Action.TIERS));
        entries.add(new MenuEntry(13, Action.TWO_FACTOR));
        entries.add(new MenuEntry(14, Action.PASSWORD));
        entries.add(new MenuEntry(15, Action.SESSIONS));
        if (admin) {
            entries.add(new MenuEntry(16, Action.STATS));
        }
        return entries;
    }

    /** Открывает меню игроку ({@code /pass menu}). */
    public void openMenu(Player player) {
        if (!accessManager.config().passGuiMenu()) {
            messages.send(player, "pass.menu.disabled");
            return;
        }
        boolean authenticated = accessManager.getStage(player) == AccessManager.PlayerStage.AUTHENTICATED;
        boolean admin = player.hasPermission("chrdkregister.admin");
        PassMenuHolder holder = new PassMenuHolder(layout(authenticated, admin));
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, messages.get("pass.menu.title"));
        holder.bind(inventory);
        for (MenuEntry entry : holder.entries()) {
            inventory.setItem(entry.slot(), icon(entry.action()));
        }
        player.openInventory(inventory);
    }

    private ItemStack icon(Action action) {
        ItemStack item = new ItemStack(materialOf(action));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.get(keyOf(action) + "-name"));
            meta.lore(List.of(messages.get(keyOf(action) + "-lore")));
            item.setItemMeta(meta);
        }
        return item;
    }

    static Material materialOf(Action action) {
        return switch (action) {
            case STATUS -> Material.CLOCK;
            case PAYMENT -> Material.EMERALD;
            case TIERS -> Material.PAPER;
            case TWO_FACTOR -> Material.SHIELD;
            case PASSWORD -> Material.TRIPWIRE_HOOK;
            case SESSIONS -> Material.COMPASS;
            case STATS -> Material.BOOK;
        };
    }

    /** Префикс текстов иконки в messages.yml ({@code pass.menu.item-*}). */
    static String keyOf(Action action) {
        return "pass.menu.item-" + switch (action) {
            case STATUS -> "status";
            case PAYMENT -> "payment";
            case TIERS -> "tiers";
            case TWO_FACTOR -> "2fa";
            case PASSWORD -> "password";
            case SESSIONS -> "sessions";
            case STATS -> "stats";
        };
    }

    private void runAction(Player player, Action action) {
        player.closeInventory();
        switch (action) {
            case STATUS -> player.performCommand("pass");
            case PAYMENT -> accessManager.sendNoPassPrompt(player, false);
            case TIERS -> {
                messages.send(player, "pass.menu.tiers-header");
                for (PluginConfig.PassTier tier : accessManager.config().passTiers()) {
                    player.sendMessage(messages.get("pass.tier-line",
                            "price", tier.amountRubles(),
                            "days", tier.days(),
                            "label", tier.label() == null || tier.label().isBlank()
                                    ? "" : " (" + tier.label() + ")"));
                }
            }
            case TWO_FACTOR -> player.performCommand("2fa status");
            case PASSWORD -> player.performCommand("changepassword");
            case SESSIONS -> player.performCommand("session");
            case STATS -> player.performCommand("pass stats");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PassMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.inventory().equals(event.getClickedInventory())) return;
        for (MenuEntry entry : holder.entries()) {
            if (entry.slot() == event.getRawSlot()) {
                runAction(player, entry.action());
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PassMenuHolder) {
            event.setCancelled(true);
        }
    }

    /** Маркержолдер, чтобы отличать меню от чужих инвентарей; хранит раскладку кликов. */
    static final class PassMenuHolder implements InventoryHolder {
        private final List<MenuEntry> entries;
        private Inventory inventory;

        PassMenuHolder(List<MenuEntry> entries) {
            this.entries = entries;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        List<MenuEntry> entries() {
            return entries;
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
