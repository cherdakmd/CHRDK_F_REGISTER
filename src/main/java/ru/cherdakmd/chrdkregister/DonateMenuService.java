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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Инвентарное GUI-меню донатов {@code /pass donate}: сверху — кнопка «Оплатить»
 * (клик шлёт в чат кликабельную ссылку на страницу оплаты), второй ряд — тарифы
 * из {@code pass.tiers} (материал иконки растёт с ценой), третий ряд — последние
 * донаты самого игрока из журнала. Администратору дополнительно видна кнопка
 * «Журнал» (весь список, делегируется {@code /pass donations}).
 *
 * <p>Данные и тексты не дублируются: тарифы и ссылки берутся из конфига, история —
 * из {@link RegistrationStore}, ссылка на оплату — через {@code sendNoPassPrompt}.</p>
 */
public final class DonateMenuService implements Listener {

    /** Что означает клик по иконке. */
    private enum Kind {
        PAY,
        TIER,
        HISTORY,
        ADMIN_JOURNAL
    }

    /** Слот и действие одной иконки. */
    private record MenuEntry(int slot, Kind kind, int index) {
    }

    /** Размер инвентаря (3 ряда). */
    public static final int INVENTORY_SIZE = 27;
    /** Стартовый слот ряда тарифов (второй ряд). */
    private static final int TIERS_START = 10;
    /** Стартовый слот ряда истории (третий ряд: 18..25 — до восьми записей). */
    private static final int HISTORY_START = 18;
    private static final int HISTORY_SLOTS = 8;

    /** Материалы тарифов по восходящей стоимости. */
    private static final List<Material> TIER_MATERIALS = List.of(
            Material.COPPER_INGOT, Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND,
            Material.EMERALD, Material.NETHERITE_INGOT, Material.ECHO_SHARD, Material.NETHER_STAR);

    private final AccessManager accessManager;
    private volatile Messages messages;
    private volatile java.util.function.Consumer<Player> adminJournalOpener;

    public DonateMenuService(AccessManager accessManager, Messages messages) {
        this.accessManager = accessManager;
        this.messages = messages;
    }

    /** Куда вести админа по кнопке «Журнал» (по умолчанию — чат-команда, ядро — GUI). */
    public void setAdminJournalOpener(java.util.function.Consumer<Player> opener) {
        this.adminJournalOpener = opener;
    }

    /** Обновляет тексты после {@code /pass reload}. */
    public void setMessages(Messages messages) {
        this.messages = messages;
    }

    /** Открывает меню донатов игроку ({@code /pass donate}). */
    public void openMenu(Player player) {
        if (!accessManager.config().passGuiMenu()) {
            messages.send(player, "pass.menu.disabled");
            return;
        }
        boolean admin = player.hasPermission("chrdkregister.admin");
        List<PluginConfig.PassTier> tiers = accessManager.config().passTiers();
        List<RegistrationStore.DonationRecord> history =
                accessManager.store().listDonationsOf(player.getName(), HISTORY_SLOTS);

        List<MenuEntry> entries = new ArrayList<>();
        entries.add(new MenuEntry(4, Kind.PAY, -1));
        // до семи тарифов: начинаем со слота 10 и идём вправо по второму ряду
        for (int i = 0; i < tiers.size() && i < 7; i++) {
            entries.add(new MenuEntry(TIERS_START + i, Kind.TIER, i));
        }
        if (history.isEmpty()) {
            entries.add(new MenuEntry(HISTORY_START + 3, Kind.HISTORY, -1));
        } else {
            for (int i = 0; i < history.size() && i < HISTORY_SLOTS; i++) {
                entries.add(new MenuEntry(HISTORY_START + i, Kind.HISTORY, i));
            }
        }
        if (admin) {
            entries.add(new MenuEntry(8, Kind.ADMIN_JOURNAL, -1));
        }

        DonateMenuHolder holder = new DonateMenuHolder(entries);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE,
                messages.get("pass.donate.title"));
        holder.bind(inventory);
        for (MenuEntry entry : entries) {
            inventory.setItem(entry.slot(), icon(player, entry, tiers, history));
        }
        player.openInventory(inventory);
    }

    private ItemStack icon(Player player, MenuEntry entry, List<PluginConfig.PassTier> tiers,
                           List<RegistrationStore.DonationRecord> history) {
        return switch (entry.kind()) {
            case PAY -> item(Material.EMERALD,
                    messages.get("pass.donate.pay-name"),
                    List.of(messages.get("pass.donate.pay-lore")));
            case ADMIN_JOURNAL -> item(Material.BOOKSHELF,
                    messages.get("pass.donate.admin-journal-name"),
                    List.of(messages.get("pass.donate.admin-journal-lore")));
            case TIER -> {
                PluginConfig.PassTier tier = tiers.get(entry.index());
                yield item(TIER_MATERIALS.get(Math.min(entry.index(), TIER_MATERIALS.size() - 1)),
                        messages.get("pass.donate.tier-name",
                                "label", tier.label() == null || tier.label().isBlank()
                                        ? (tier.amountRubles() + " ₽") : tier.label()),
                        List.of(messages.get("pass.donate.tier-lore",
                                "amount", tier.amountRubles(),
                                "days", tier.days())));
            }
            case HISTORY -> {
                if (entry.index() < 0) {
                    yield item(Material.PAPER,
                            messages.get("pass.donate.history-empty-name"),
                            List.of(messages.get("pass.donate.history-empty-lore")));
                }
                RegistrationStore.DonationRecord record = history.get(entry.index());
                yield item(historyMaterial(record),
                        messages.get("pass.donate.history-name",
                                "amount", formatRubles(record.amountKopecks),
                                "status", statusLabel(record)),
                        List.of(
                                messages.get("pass.donate.history-info",
                                        "date", AccessManager.formatTimestamp(record.createdAt),
                                        "source", record.source == null || record.source.isBlank()
                                                ? "—" : record.source),
                                messages.get("pass.donate.history-donor",
                                        "donor", record.donorName == null || record.donorName.isBlank()
                                                ? "—" : record.donorName,
                                        "days", record.days)));
            }
        };
    }

    private String statusLabel(RegistrationStore.DonationRecord record) {
        RegistrationStore.DonationStatus status = RegistrationStore.DonationStatus.parse(record.status);
        String key = switch (status) {
            case GRANTED -> "donation.status-granted";
            case PENDING_NAME -> "donation.status-pending-name";
            case PENDING_AMOUNT -> "donation.status-pending-amount";
            case PENDING_REVIEW -> "donation.status-pending-review";
            case REJECTED -> "donation.status-rejected";
        };
        return Messages.stripFormatting(messages.raw(key));
    }

    private static Material historyMaterial(RegistrationStore.DonationRecord record) {
        RegistrationStore.DonationStatus status = RegistrationStore.DonationStatus.parse(record.status);
        return switch (status) {
            case GRANTED -> Material.SUNFLOWER;
            case REJECTED -> Material.BARRIER;
            default -> Material.CLOCK;
        };
    }

    /** Копейки → «150» или «99.50» без лишних нулей. */
    static String formatRubles(long kopecks) {
        return BigDecimal.valueOf(kopecks).movePointLeft(2)
                .setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void runAction(Player player, MenuEntry entry, List<PluginConfig.PassTier> tiers,
                           List<RegistrationStore.DonationRecord> history) {
        switch (entry.kind()) {
            case PAY -> {
                player.closeInventory();
                accessManager.sendNoPassPrompt(player, false);
            }
            case TIER -> {
                player.closeInventory();
                PluginConfig.PassTier tier = tiers.get(entry.index());
                messages.send(player, "pass.donate.tier-picked",
                        "label", tier.label() == null || tier.label().isBlank()
                                ? (tier.amountRubles() + " ₽") : tier.label(),
                        "amount", tier.amountRubles(),
                        "days", tier.days());
                accessManager.sendNoPassPrompt(player, false);
            }
            case ADMIN_JOURNAL -> {
                player.closeInventory();
                java.util.function.Consumer<Player> opener = adminJournalOpener;
                if (opener != null) {
                    opener.accept(player);
                } else {
                    player.performCommand("pass donations");
                }
            }
            case HISTORY -> { /* информационная иконка — клик без действия */ }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DonateMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.inventory().equals(event.getClickedInventory())) return;
        List<PluginConfig.PassTier> tiers = accessManager.config().passTiers();
        List<RegistrationStore.DonationRecord> history =
                accessManager.store().listDonationsOf(player.getName(), HISTORY_SLOTS);
        for (MenuEntry entry : holder.entries()) {
            if (entry.slot() == event.getRawSlot()) {
                runAction(player, entry, tiers, history);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof DonateMenuHolder) {
            event.setCancelled(true);
        }
    }

    /** Маркержолдер: отличает наше меню от чужих инвентарей и хранит раскладку кликов. */
    static final class DonateMenuHolder implements InventoryHolder {
        private final List<MenuEntry> entries;
        private Inventory inventory;

        DonateMenuHolder(List<MenuEntry> entries) {
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
