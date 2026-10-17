package ru.cherdakmd.chrdkdaily;

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

import java.util.List;

/**
 * Инвентарный календарь стрика: месячная сетка 7 колонок × до 5 рядов (дни месяца
 * укладываются по порядку слева направо, сверху вниз, начиная с ряда 2). Верхний ряд —
 * шапка с книгой стрика, прочие слоты заполнены рамкой. Сегодняшний доступный день —
 * сундук с подсветкой; взятые — зелёная панель; будущие — серая. Клик по календарю
 * забирает сегодняшнюю награду и перерисовывает статусы.
 */
public final class DailyCalendarGui implements Listener {

    private static final int SIZE = 54;
    private static final int FIRST_DAY_SLOT = 9;   // начало месячной сетки (7 колонок)
    private static final int HEADER_SLOT = 4;      // книга стрика в шапке
    private static final int MAX_DAY_CELLS = 35;   // 5 рядов по 7 колонок

    private final DailyRewardsPlugin plugin;

    public DailyCalendarGui(DailyRewardsPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        DailyHolder holder = new DailyHolder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, plugin.messages().get("daily.gui.title"));
        holder.bind(inventory);
        render(player, inventory);
        player.openInventory(inventory);
    }

    private void render(Player player, Inventory inventory) {
        List<DailyConfig.DayReward> rewards = plugin.config().rewards();
        DailyStore.Progress progress = plugin.store().progressOf(player.getUniqueId());
        long today = DailyStore.dayOf(System.currentTimeMillis());
        boolean claimedToday = progress.lastClaimDay >= today;
        int cycleSize = Math.max(1, rewards.size());
        // день стрика, который отобразится как «сегодняшний»
        int displayStreak = claimedToday ? progress.streak : progress.streak + 1;
        int todayIndex = Math.floorMod(displayStreak - 1, cycleSize);
        boolean vip = plugin.coreApi() != null && plugin.coreApi().hasActivePass(player.getName());

        // рамка: верхний ряд и хвост за месячной сеткой
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(net.kyori.adventure.text.Component.text(" "));
            filler.setItemMeta(fillerMeta);
        }
        for (int slot = 0; slot < FIRST_DAY_SLOT; slot++) {
            inventory.setItem(slot, filler);
        }
        for (int slot = FIRST_DAY_SLOT + Math.min(rewards.size(), MAX_DAY_CELLS);
                slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        for (int index = 0; index < rewards.size() && index < MAX_DAY_CELLS; index++) {
            int slot = FIRST_DAY_SLOT + index;
            DailyConfig.DayReward day = rewards.get(index);
            ItemStack icon;
            if (index < todayIndex) {
                icon = pane(Material.LIME_STAINED_GLASS_PANE, "daily.gui.claimed-name",
                        day.day(), day, progress, vip, "daily.gui.lore-claimed", 0);
            } else if (index == todayIndex && !claimedToday) {
                icon = pane(Material.CHEST, "daily.gui.today-name",
                        day.day(), day, progress, vip, "daily.gui.lore-today", 0);
            } else if (index == todayIndex) {
                icon = pane(Material.GREEN_STAINED_GLASS_PANE, "daily.gui.claimed-name",
                        day.day(), day, progress, vip, "daily.gui.lore-claimed", 0);
            } else {
                icon = pane(Material.GRAY_STAINED_GLASS_PANE, "daily.gui.locked-name",
                        day.day(), day, progress, vip, "daily.gui.lore-locked", index - todayIndex);
            }
            inventory.setItem(slot, icon);
        }
        ItemStack header = new ItemStack(Material.BOOK);
        ItemMeta headerMeta = header.getItemMeta();
        if (headerMeta != null) {
            headerMeta.displayName(plugin.messages().get("daily.gui.footer-name",
                    "streak", progress.streak));
            header.setItemMeta(headerMeta);
        }
        inventory.setItem(HEADER_SLOT, header);
    }

    private ItemStack pane(Material material, String nameKey, int dayNum,
                           DailyConfig.DayReward day, DailyStore.Progress progress,
                           boolean vip, String loreKey, int opensIn) {
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.messages().get(nameKey, "day", dayNum));
            String rewardText = DailyConfig.describe(day, vip, java.util.Optional.empty());
            meta.lore(plugin.messages().getList(loreKey,
                    "streak", progress.streak,
                    "days", opensIn,
                    "rewards", rewardText));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    /* ------------------------------- обработчики ------------------------------- */

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DailyHolder)
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.getInventory().getHolder() instanceof DailyHolder
                    && (event.getClick().isShiftClick()
                    || event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK
                    || event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY)) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.service().canClaim(player)) {
                // перерисуем — награда уже взята
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof DailyHolder) {
                    open(player);
                }
                return;
            }
            DailyService.ClaimResult result = plugin.service().claim(player);
            if (result != null) {
                plugin.commandHandler().announceClaim(player, result);
            }
            player.closeInventory();
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof DailyHolder) {
            event.setCancelled(true);
        }
    }

    /** Маркер-holder календаря. */
    private static final class DailyHolder implements InventoryHolder {

        private Inventory inventory;

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
