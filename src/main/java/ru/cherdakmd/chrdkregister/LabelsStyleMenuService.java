package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Три личных экрана оформления ника (1.4.187): цвет, порядок меток и пресеты.
 *
 * <p>Главное меню «Оформление ника» отвечает на вопрос «что показывать».
 * Эти три — на вопрос «как»: каким цветом, в каком порядке и одним нажатием
 * вернуться к сохранённому виду. Все экраны открываются из главного меню, из
 * {@code /labels} (аргументами {@code color}, {@code order}, {@code preset}) и
 * помнят, кто их владелец: чужой клик ничего не меняет.</p>
 *
 * <p>Цвет проверяется при выборе: ступень ранга или активный товар каталога.
 * Пресетов столько, сколько даёт ступень ({@link LabelLimits}); слот занят —
 * его можно перезаписать или удалить.</p>
 */
public final class LabelsStyleMenuService implements Listener {

    static final int SIZE = 54;
    /** Сетка содержимого: четыре ряда по семь слотов. */
    static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    static final int BACK_SLOT = 45;
    static final int INFO_SLOT = 49;
    static final int RESET_SLOT = 48;
    static final int CLOSE_SLOT = 53;

    /** Какой экран открыт — по нему обработчик понимает смысл клика. */
    enum Kind { COLORS, ORDER, PRESETS, PICKER }

    private final RegisterPlugin plugin;
    private final LabelPreferences preferences;
    private volatile LabelPalette palette = LabelPalette.disabled();
    private volatile LabelLimits limits = LabelLimits.DEFAULT;

    public LabelsStyleMenuService(RegisterPlugin plugin, LabelPreferences preferences) {
        this.plugin = plugin;
        this.preferences = preferences;
    }

    /** Палитра и лимиты приходят из конфига и обновляются по {@code /pass reload}. */
    public void applySettings(LabelPalette colors, LabelLimits presetLimits) {
        this.palette = colors == null ? LabelPalette.disabled() : colors;
        this.limits = presetLimits == null ? LabelLimits.DEFAULT : presetLimits;
    }

    /* -------------------------------- открытие -------------------------------- */

    public void openColors(Player player) {
        open(player, Kind.COLORS, plugin.messages().get("labels.color-title"));
    }

    public void openOrder(Player player) {
        open(player, Kind.ORDER, plugin.messages().get("labels.order-title"));
    }

    public void openPresets(Player player) {
        open(player, Kind.PRESETS, plugin.messages().get("labels.preset-title"));
    }

    public void openSlot(Player player, int place) {
        LabelStyleService styles = styles();
        if (player == null || styles == null) return;
        if (place < 0 || place >= styles.selection(player.getName()).limit()) {
            plugin.messages().send(player, "labels.slot-locked");
            return;
        }
        open(player, Kind.PICKER, plugin.messages().get("labels.slot-title", "number", place + 1), place);
    }

    private LabelStyleService styles() {
        return plugin.chatService() == null ? null : plugin.chatService().labelStyles();
    }

    private void open(Player player, Kind kind, Component title) {
        open(player, kind, title, -1);
    }

    private void open(Player player, Kind kind, Component title, int place) {
        if (player == null) {
            return;
        }
        if (!player.hasPermission("chrdkregister.labels")) {
            plugin.messages().send(player, "error.no-permission");
            return;
        }
        Holder holder = new Holder(kind, player.getUniqueId(), place);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title);
        holder.bind(inventory);
        paint(player, holder);
        player.openInventory(inventory);
    }

    private void paint(Player player, Holder holder) {
        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }
        ItemStack filler = filler();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        switch (holder.kind) {
            case COLORS -> paintColors(player, inventory);
            case ORDER -> paintOrder(player, inventory);
            case PRESETS -> paintPresets(player, inventory);
            case PICKER -> paintPicker(player, inventory, holder.place);
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW,
                plugin.messages().get("labels.style-back")));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER,
                plugin.messages().get("labels.menu-close")));
    }

    /* ---------------------------------- цвета ---------------------------------- */

    private void paintColors(Player player, Inventory inventory) {
        int level = rankLevel(player.getName());
        Set<String> owned = plugin.purchasedProducts(player.getName());
        String chosen = preferences.color(player.getName());
        List<LabelPalette.Color> colors = palette.colors();
        for (int index = 0; index < Math.min(CONTENT_SLOTS.length, colors.size()); index++) {
            LabelPalette.Color color = colors.get(index);
            boolean allowed = palette.allowed(color, level, owned);
            boolean active = allowed && color.id().equals(chosen);
            inventory.setItem(CONTENT_SLOTS[index],
                    colorTile(player, color, allowed, active, level, owned));
        }
        inventory.setItem(INFO_SLOT, button(Material.PAPER,
                plugin.messages().get("labels.color-info",
                        "level", String.valueOf(level))));
        inventory.setItem(RESET_SLOT, button(Material.MILK_BUCKET,
                plugin.messages().get("labels.color-reset")));
    }

    private ItemStack colorTile(Player player, LabelPalette.Color color, boolean allowed,
                                boolean active, int level, Set<String> owned) {
        ItemStack item = new ItemStack(allowed ? iconOf(color) : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(colorName(plugin.messages(), color, allowed, active));
        List<Component> lore = new ArrayList<>();
        lore.add(plugin.messages().get("labels.color-sample",
                "sample", Messages.trusted(preview(player.getName(), color))));
        LabelPalette.Lock reason = LabelPalette.lockReason(color, owned);
        if (!allowed) {
            lore.add(reason == LabelPalette.Lock.CATALOG
                    ? plugin.messages().get("labels.color-need-catalog",
                    "product", Messages.trusted(color.product()))
                    : plugin.messages().get("labels.color-need-rank",
                    "rank", String.valueOf(color.rank())));
        }
        lore.add(plugin.messages().get(allowed && !active
                ? "labels.color-click" : "labels.style-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Статус цвета — доверенная разметка, не Component.toString() в подстановке. */
    static Component colorName(Messages messages, LabelPalette.Color color, boolean allowed, boolean active) {
        Messages.Trusted mark = allowed && active ? messages.mini("labels.color-active-mark")
                : allowed ? Messages.trusted("") : messages.mini("labels.color-locked-mark");
        return messages.get("labels.color-name", "name", Messages.trusted(color.name()), "mark", mark);
    }

    /** Подпись-образец: ник игрока в этом цвете, без разметки меню. */
    private String preview(String playerName, LabelPalette.Color color) {
        return LabelPalette.apply(color, Messages.escape(playerName));
    }

    /* --------------------------------- порядок --------------------------------- */

    private void paintOrder(Player player, Inventory inventory) {
        for (int i = 0; i < LabelLimits.MAX_LABELS; i++) {
            inventory.setItem(CONTENT_SLOTS[i], plugin.labelsMenu().placeTile(player, i));
        }
        LabelStyleService.Selection selection = LabelsMenuService.selectionOf(plugin, player.getName());
        ItemStack info = button(Material.PAPER, plugin.messages().get("labels.order-info"));
        ItemMeta meta = info.getItemMeta();
        if (meta != null && selection != null) {
            meta.lore(List.of(LabelsMenuService.summary(plugin.messages(), selection),
                    plugin.messages().get("labels.order-preview", "nick", Messages.trusted(samplePreview(player)))));
            info.setItemMeta(meta);
        }
        inventory.setItem(INFO_SLOT, info);
        inventory.setItem(RESET_SLOT, button(Material.MILK_BUCKET, plugin.messages().get("labels.order-reset")));
    }

    private void paintPicker(Player player, Inventory inventory, int place) {
        LabelStyleService styles = styles();
        if (styles == null) return;
        LabelStyleService.Selection selection = styles.selection(player.getName());
        List<LabelPreferences.Label> labels = LabelPreferences.Label.nameTags();
        String chosen = styles.assignments(player.getName()).get(place);
        for (int i = 0; i < labels.size(); i++) {
            LabelPreferences.Label label = labels.get(i);
            boolean obtained = selection.obtained(label);
            ItemStack tile = button(obtained ? Material.NAME_TAG : Material.GRAY_DYE,
                    plugin.messages().get(label.id().equals(chosen) ? "labels.slot-selected" : "labels.menu-entry-on",
                            "name", LabelsMenuService.nameOf(plugin.messages(), label)));
            ItemMeta meta = tile.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                selection.available().stream().filter(entry -> entry.source() == label).findFirst()
                        .ifPresent(entry -> lore.add(plugin.messages().get("labels.slot-sample",
                                "sample", Messages.trusted(entry.markup()))));
                lore.add(plugin.messages().get(obtained ? "labels.slot-pick-hint" : "labels.slot-unavailable"));
                lore.add(plugin.messages().get("labels.slot-single-source"));
                meta.lore(lore);
                tile.setItemMeta(meta);
            }
            inventory.setItem(CONTENT_SLOTS[i], tile);
        }
        ItemStack info = button(Material.PAPER, plugin.messages().get("labels.slot-title", "number", place + 1));
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.lore(List.of(plugin.messages().get("labels.order-preview", "nick", Messages.trusted(samplePreview(player))),
                    plugin.messages().get("labels.slot-no-fallback")));
            info.setItemMeta(meta);
        }
        inventory.setItem(INFO_SLOT, info);
        inventory.setItem(RESET_SLOT, button(Material.BARRIER, plugin.messages().get("labels.slot-clear")));
    }

    /** Текущий ник целиком: по нему видно, как перестановка меняет строку. */
    private String samplePreview(Player player) {
        ChatService chat = plugin.chatService();
        String nick = chat == null ? Messages.escape(player.getName())
                : chat.decoratedName(player.getName());
        return nick == null || nick.isBlank() ? Messages.escape(player.getName()) : nick;
    }

    /* --------------------------------- пресеты --------------------------------- */

    private void paintPresets(Player player, Inventory inventory) {
        int level = rankLevel(player.getName());
        int slots = limits.slotsFor(level);
        Map<String, LabelPreferences.Snapshot> presets = preferences.presets(player.getName());
        for (int index = 0; index < Math.min(CONTENT_SLOTS.length, slots); index++) {
            String slot = slotId(index);
            LabelPreferences.Snapshot snapshot = presets.get(slot);
            inventory.setItem(CONTENT_SLOTS[index], presetTile(slot, index + 1, snapshot));
        }
        inventory.setItem(INFO_SLOT, button(Material.PAPER,
                plugin.messages().get("labels.preset-info",
                        "slots", String.valueOf(slots))));
        inventory.setItem(RESET_SLOT, button(Material.MILK_BUCKET,
                plugin.messages().get("labels.menu-reset")));
    }

    private ItemStack presetTile(String slot, int number, LabelPreferences.Snapshot snapshot) {
        ItemStack item = new ItemStack(snapshot == null ? Material.BOOK : Material.WRITTEN_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(plugin.messages().get(snapshot == null
                        ? "labels.preset-empty" : "labels.preset-filled",
                "number", String.valueOf(number)));
        List<Component> lore = new ArrayList<>();
        if (snapshot == null) {
            lore.add(plugin.messages().get("labels.preset-save-hint"));
        } else {
            lore.add(plugin.messages().get("labels.preset-summary",
                    "hidden", String.valueOf(snapshot.hiddenCount()),
                    "color", snapshot.color().isEmpty()
                            ? Messages.trusted(
                            plugin.messages().raw("labels.preset-no-color"))
                            : Messages.trusted(snapshot.color())));
            lore.add(plugin.messages().get("labels.preset-apply-hint"));
            lore.add(plugin.messages().get("labels.preset-overwrite-hint"));
            lore.add(plugin.messages().get("labels.preset-delete-hint"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /* ---------------------------------- клики ---------------------------------- */

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner().equals(player.getUniqueId())) {
            return;
        }
        if (!player.hasPermission("chrdkregister.labels")) {
            player.closeInventory();
            plugin.messages().send(player, "error.no-permission");
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT
                && !(holder.kind == Kind.PRESETS && event.getClick() == ClickType.SHIFT_RIGHT)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    plugin.labelsMenu().open(player);
                }
            });
            return;
        }
        switch (holder.kind) {
            case COLORS -> onColorClick(player, slot);
            case ORDER -> onOrderClick(player, slot, event.getClick());
            case PRESETS -> onPresetClick(player, slot, event.getClick());
            case PICKER -> onPickerClick(player, slot, holder.place);
        }
        repaint(player, holder);
    }

    private void onColorClick(Player player, int slot) {
        if (slot == RESET_SLOT) {
            preferences.setColor(player.getName(), "");
            plugin.messages().send(player, "labels.color-reset-done");
            plugin.refreshTab();
            return;
        }
        int index = indexOf(slot);
        List<LabelPalette.Color> colors = palette.colors();
        if (index < 0 || index >= colors.size()) {
            return;
        }
        LabelPalette.Color color = colors.get(index);
        int level = rankLevel(player.getName());
        Set<String> owned = plugin.purchasedProducts(player.getName());
        if (!palette.allowed(color, level, owned)) {
            plugin.messages().send(player,
                    LabelPalette.lockReason(color, owned) == LabelPalette.Lock.CATALOG
                            ? "labels.color-locked-catalog" : "labels.color-locked-rank",
                    "rank", String.valueOf(color.rank()),
                    "product", color.product());
            return;
        }
        if (color.id().equals(preferences.color(player.getName()))) {
            preferences.setColor(player.getName(), "");
            plugin.messages().send(player, "labels.color-cleared");
        } else {
            preferences.setColor(player.getName(), color.id());
            plugin.messages().send(player, "labels.color-set",
                    "name", Messages.trusted(color.name()));
        }
        plugin.refreshTab();
    }

    private void onOrderClick(Player player, int slot, ClickType click) {
        if (slot == RESET_SLOT) {
            preferences.setOrder(player.getName(), List.of());
            plugin.messages().send(player, "labels.order-reset-done");
            plugin.refreshTab();
            return;
        }
        int index = indexOf(slot);
        if (index < 0 || index >= LabelLimits.MAX_LABELS) return;
        if (click.isRightClick()) applyAssignment(player, index, null);
        else {
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) openSlot(player, index);
            });
        }
    }

    private void onPickerClick(Player player, int slot, int place) {
        if (slot == RESET_SLOT) {
            applyAssignment(player, place, null);
            return;
        }
        int index = indexOf(slot);
        List<LabelPreferences.Label> labels = LabelPreferences.Label.nameTags();
        if (index >= 0 && index < labels.size()) applyAssignment(player, place, labels.get(index));
    }

    private void applyAssignment(Player player, int place, LabelPreferences.Label label) {
        LabelStyleService styles = styles();
        if (styles == null) return;
        switch (styles.assign(player.getName(), place, label)) {
            case LOCKED -> plugin.messages().send(player, "labels.slot-locked");
            case UNAVAILABLE -> plugin.messages().send(player, "labels.slot-unavailable");
            case APPLIED -> {
                plugin.messages().send(player, label == null ? "labels.slot-cleared" : "labels.slot-assigned",
                        "number", place + 1, "name", label == null ? Messages.trusted("")
                                : LabelsMenuService.nameOf(plugin.messages(), label));
                plugin.refreshTab();
            }
        }
    }

    private void onPresetClick(Player player, int slot, ClickType click) {
        if (slot == RESET_SLOT) {
            preferences.reset(player.getName());
            plugin.messages().send(player, "labels.reset");
            plugin.refreshTab();
            return;
        }
        int index = indexOf(slot);
        int slots = limits.slotsFor(rankLevel(player.getName()));
        if (index < 0 || index >= slots) {
            return;
        }
        String slotId = slotId(index);
        LabelPreferences.Snapshot snapshot = preferences.presets(player.getName()).get(slotId);
        if (snapshot == null) {
            preferences.savePreset(player.getName(), slotId);
            plugin.messages().send(player, "labels.preset-saved",
                    "number", String.valueOf(index + 1));
            return;
        }
        if (click.isShiftClick() && click.isRightClick()) {
            preferences.deletePreset(player.getName(), slotId);
            plugin.messages().send(player, "labels.preset-deleted",
                    "number", String.valueOf(index + 1));
            return;
        }
        if (click.isRightClick()) {
            preferences.savePreset(player.getName(), slotId);
            plugin.messages().send(player, "labels.preset-saved",
                    "number", String.valueOf(index + 1));
            return;
        }
        preferences.apply(player.getName(), snapshot);
        plugin.messages().send(player, "labels.preset-applied",
                "number", String.valueOf(index + 1));
        plugin.refreshTab();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Закрывает экраны при выключении плагина. */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                player.closeInventory();
            }
        }
    }

    /* -------------------------------- помощники -------------------------------- */

    private void repaint(Player player, Holder holder) {
        paint(player, holder);
    }

    private int rankLevel(String playerName) {
        RegistrationStore store = plugin.store();
        return store == null ? 0 : store.rankLevel(playerName, System.currentTimeMillis());
    }

    static String slotId(int index) {
        return "p" + (index + 1);
    }

    /** Номер слота сетки по индексу; {@code -1} — вне сетки. */
    static int indexOf(int slot) {
        for (int index = 0; index < CONTENT_SLOTS.length; index++) {
            if (CONTENT_SLOTS[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    private static Material iconOf(LabelPalette.Color color) {
        return switch (color.id()) {
            case "gray" -> Material.GRAY_DYE;
            case "white" -> Material.WHITE_DYE;
            case "yellow" -> Material.YELLOW_DYE;
            case "green" -> Material.LIME_DYE;
            case "aqua" -> Material.CYAN_DYE;
            case "blue" -> Material.BLUE_DYE;
            case "gold" -> Material.ORANGE_DYE;
            case "light-purple" -> Material.PURPLE_DYE;
            case "red" -> Material.RED_DYE;
            case "sunset" -> Material.MAGMA_CREAM;
            case "aurora" -> Material.AMETHYST_SHARD;
            case "flame" -> Material.BLAZE_POWDER;
            case "pearl" -> Material.PRISMARINE_CRYSTALS;
            default -> Material.NAME_TAG;
        };
    }

    private ItemStack button(Material material, Component name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Заголовок открытого экрана и владелец: чужой клик не сработает. */
    static final class Holder implements InventoryHolder {
        private final Kind kind;
        private final UUID owner;
        private final int place;
        private Inventory inventory;

        Holder(Kind kind, UUID owner) {
            this(kind, owner, -1);
        }

        Holder(Kind kind, UUID owner, int place) {
            this.kind = kind;
            this.owner = owner;
            this.place = place;
        }

        Kind kind() {
            return kind;
        }

        UUID owner() {
            return owner;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
