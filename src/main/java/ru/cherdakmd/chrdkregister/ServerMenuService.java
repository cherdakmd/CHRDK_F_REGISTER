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
 * Серверное меню {@code /menu}: главная страница с категориями и страницы
 * категорий. Пункты конфиг-driven, ядро об аддонах ничего не знает — клик
 * просто выполняет команду от имени игрока.
 *
 * <p>Авто-раскладка: явные слоты уважаются, остальные иконки встают рядами
 * 10..16 / 19..25 / 28..34 / 37..43 / 46..52. Размер инвентаря подбирается
 * минимальный, куда всё помещается (27/36/45/54); страницы категорий всегда 54
 * слота — в нижнем ряду живёт кнопка «Назад».</p>
 *
 * <p>Категория, все пункты которой скрыты (аддон выключен или нет права),
 * пропадает с главной страницы: игрок не попадает в пустой инвентарь.</p>
 */
public final class ServerMenuService implements Listener {

    /** Авто-слоты по рядам (центральные 7 колонок каждого ряда). */
    private static final int[] AUTO_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
            46, 47, 48, 49, 50, 51, 52};

    /** Кнопка меню: категория (переход) или команда. */
    private record Button(int slot, Material icon, String name, String lore, String command,
                          String category) {
    }

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

    /* --------------------------------- раскладка -------------------------------- */

    /**
     * Раскладка пунктов по слотам: явный слот достаётся первому претенденту,
     * остальные берут ближайшие свободные из {@link #AUTO_SLOTS} по порядку.
     */
    public static MenuLayout layout(List<ServerMenuSettings.Entry> entries) {
        if (entries == null) {
            return new MenuLayout(27, List.of());
        }
        return layoutSlots(entries.stream().map(ServerMenuSettings.Entry::slot).toList());
    }

    /** Раскладка по списку запрошенных слотов ({@code -1} — авто). */
    public static MenuLayout layoutSlots(List<Integer> requested) {
        if (requested == null || requested.isEmpty()) {
            return new MenuLayout(27, List.of());
        }
        List<Integer> slots = new ArrayList<>(java.util.Collections.nCopies(requested.size(), (Integer) null));
        Set<Integer> taken = new HashSet<>();
        int maxExplicit = -1;
        for (int i = 0; i < requested.size(); i++) {
            Integer slot = requested.get(i);
            if (slot != null && slot >= 0 && taken.add(slot)) {
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
        return new MenuLayout(size, List.copyOf(slots));
    }

    /** Минимальный размер сундука, куда влезают все явные слоты. */
    static int sizeFor(int maxExplicitSlot) {
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

    /* ------------------------------ главная страница ----------------------------- */

    /** Открывает серверное меню игроку ({@code /menu}). */
    public void openMenu(Player player) {
        if (!player.hasPermission("chrdkregister.menu")) {
            messages.send(player, "server-menu.no-permission");
            return;
        }
        if (accessManager.getStage(player) != AccessManager.PlayerStage.AUTHENTICATED) {
            messages.send(player, "server-menu.need-auth");
            return;
        }
        ServerMenuSettings settings = accessManager.config().serverMenu();
        if (settings == null || !settings.enabled()) {
            messages.send(player, "server-menu.disabled");
            return;
        }
        List<Button> buttons = mainButtons(settings, player);
        if (buttons.isEmpty()) {
            messages.send(player, "server-menu.disabled");
            return;
        }
        MenuLayout layout = layoutSlots(buttons.stream().map(Button::slot).toList());
        ServerMenuHolder holder = new ServerMenuHolder(player.getUniqueId(), null, layout.slots(), buttons);
        Inventory inventory = Bukkit.createInventory(holder, layout.size(),
                messages.renderTemplate(settings.title(), menuPlaceholders(player)));
        holder.bind(inventory);
        paint(inventory, layout, buttons, player);
        player.openInventory(inventory);
    }

    /** Кнопки главной страницы: категории + пункты без категории. */
    private List<Button> mainButtons(ServerMenuSettings settings, Player player) {
        List<Button> buttons = new ArrayList<>();
        if (settings.grouped()) {
            for (ServerMenuSettings.Category category : settings.categories()) {
                List<ServerMenuSettings.Entry> available = availableEntries(settings, category.id(), player);
                if (available.isEmpty()
                        || !category.available(name -> Bukkit.getPluginManager().isPluginEnabled(name),
                        player::hasPermission)) {
                    continue;
                }
                buttons.add(new Button(category.slot(), category.icon(), category.name(), category.lore(),
                        null, category.id()));
            }
        }
        boolean grouped = settings.grouped();
        if (!grouped) {
            // Плоский режим 1.4.49: все пункты на одной странице, в порядке конфига.
            for (ServerMenuSettings.Entry entry : settings.entries()) {
                if (available(entry, player)) {
                    buttons.add(new Button(entry.slot(), entry.icon(), entry.name(), entry.lore(),
                            entry.command(), null));
                }
            }
            return buttons;
        }
        for (ServerMenuSettings.Entry entry : settings.looseEntries()) {
            if (available(entry, player)) {
                buttons.add(new Button(entry.slot(), entry.icon(), entry.name(), entry.lore(),
                        entry.command(), null));
            }
        }
        return buttons;
    }

    /** Открывает страницу категории. */
    public void openCategory(Player player, String categoryId) {
        ServerMenuSettings settings = accessManager.config().serverMenu();
        if (settings == null || !settings.enabled()) {
            messages.send(player, "server-menu.disabled");
            return;
        }
        ServerMenuSettings.Category category = settings.categories().stream()
                .filter(candidate -> candidate.id().equals(categoryId)).findFirst().orElse(null);
        if (category == null) {
            messages.send(player, "server-menu.changed");
            return;
        }
        List<ServerMenuSettings.Entry> entries = availableEntries(settings, category.id(), player);
        if (entries.isEmpty()) {
            messages.send(player, "server-menu.empty");
            return;
        }
        List<Button> buttons = new ArrayList<>();
        for (ServerMenuSettings.Entry entry : entries) {
            buttons.add(new Button(entry.slot(), entry.icon(), entry.name(), entry.lore(),
                    entry.command(), null));
        }
        // Кнопка «Назад» — обычный пункт с командой /menu и явным слотом.
        buttons.add(new Button(settings.backSlot(), settings.backIcon(), settings.backName(), "",
                "menu", null));
        MenuLayout layout = layoutSlots(buttons.stream().map(Button::slot).toList());
        ServerMenuHolder holder = new ServerMenuHolder(player.getUniqueId(), category.id(),
                layout.slots(), buttons);
        String title = settings.categoryTitle().replace("%category%", category.name());
        Inventory inventory = Bukkit.createInventory(holder, layout.size(),
                messages.renderTemplate(title, menuPlaceholders(player)));
        holder.bind(inventory);
        paint(inventory, layout, buttons, player);
        player.openInventory(inventory);
    }

    private List<ServerMenuSettings.Entry> availableEntries(ServerMenuSettings settings,
                                                           String categoryId, Player player) {
        return settings.entriesOf(categoryId).stream().filter(entry -> available(entry, player)).toList();
    }

    private boolean available(ServerMenuSettings.Entry entry, Player player) {
        return entry.available(name -> Bukkit.getPluginManager().isPluginEnabled(name), player::hasPermission);
    }

    private void paint(Inventory inventory, MenuLayout layout, List<Button> buttons, Player player) {
        for (int i = 0; i < buttons.size() && i < layout.slots().size(); i++) {
            int slot = layout.slots().get(i);
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            inventory.setItem(slot, icon(buttons.get(i), player));
        }
    }

    private ItemStack icon(Button button, Player player) {
        Material material = button.icon() == null ? Material.PAPER : button.icon();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.renderTemplate(button.name(), menuPlaceholders(player)));
            if (!button.lore().isBlank()) {
                meta.lore(java.util.Arrays.stream(button.lore().split("\\R"))
                        .map(line -> messages.renderTemplate(line, menuPlaceholders(player))).toList());
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

    /* ---------------------------------- клики ---------------------------------- */

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ServerMenuHolder holder)) return;
        event.setCancelled(true); // also bottom inventory/shift/hotbar/double-click
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        if (event.getClick() != org.bukkit.event.inventory.ClickType.LEFT
                && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
        for (int i = 0; i < holder.slots.size(); i++) {
            if (holder.slots.get(i) != event.getRawSlot()) continue;
            Button button = holder.buttons.get(i);
            if (button.category() != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!accessManager.config().serverMenu().enabled()) {
                        player.closeInventory();
                        messages.send(player, "server-menu.changed");
                        return;
                    }
                    openCategory(player, button.category());
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != holder.inventory) return;
                if (!accessManager.config().serverMenu().enabled()) {
                    player.closeInventory();
                    messages.send(player, "server-menu.changed");
                    return;
                }
                if (!player.hasPermission("chrdkregister.menu")
                        || accessManager.getStage(player) != AccessManager.PlayerStage.AUTHENTICATED) {
                    player.closeInventory();
                    messages.send(player, "server-menu.unavailable");
                    return;
                }
                player.closeInventory();
                player.performCommand(button.command());
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

    /** Маркерный холдер: отличает наше меню от чужих инвентарей, хранит слоты кликов. */
    static final class ServerMenuHolder implements InventoryHolder {
        private final String category;
        private final List<Integer> slots;
        private final List<Button> buttons;
        private final java.util.UUID owner;
        private Inventory inventory;

        ServerMenuHolder(java.util.UUID owner, String category, List<Integer> slots, List<Button> buttons) {
            this.owner = owner;
            this.category = category;
            this.slots = List.copyOf(slots);
            this.buttons = List.copyOf(buttons);
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        List<Integer> slots() {
            return slots;
        }

        String category() {
            return category;
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
