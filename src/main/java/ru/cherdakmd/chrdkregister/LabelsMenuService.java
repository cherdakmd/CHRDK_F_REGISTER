package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Меню «Оформление ника» (1.4.186): каждое звание и надпись — своим
 * переключателем.
 *
 * <p>С 1.4.183 настройка жила в чате командой {@code /labels}, и прятать можно
 * было только группами: «всё звания» одной кнопкой. Здесь то же самое, но
 * наглядно: девять плиток — ранг, титул кузни, звание кладоискателя, класс,
 * гильдия, стример, квесты, достижения и шапка таба. Клик переключает одну
 * надпись и сразу перерисовывает меню; снизу — «Включить всё», «Выключить
 * всё», «Сброс», «Профиль» и «Закрыть». Справа вверху — «Вас видят так»: та же
 * строка, которую получают остальные игроки, поэтому видно, что настройка не
 * личная, а общая.</p>
 *
 * <p>Меню открывается командой {@code /labels}, кнопкой из {@code /profile} и
 * пунктом «Оформление ника» в {@code /menu}. Это только оформление: ранг, опыт,
 * права и срок подписки не меняются, а настройка личная — файл
 * {@code labels.json} ядра.</p>
 */
public final class LabelsMenuService implements Listener {

    /** Шесть рядов: превью, места, переключатели источников, кнопки. */
    static final int SIZE = 54;
    /** Голова игрока со сводкой. */
    static final int HEAD_SLOT = 4;
    /** Как ник выглядит для остальных — ровно та строка, что уходит в чат и таб. */
    static final int PREVIEW_SLOT = 8;
    /** Переключатели источников под выбранными местами; шапка TAB отдельно. */
    static final int[] ENTRY_SLOTS = {28, 29, 30, 31, 32, 33, 34, 35, 37};
    static final int[] PLACE_SLOTS = {19, 20, 21, 22, 23, 24, 25, 26};
    static final int ENABLE_ALL_SLOT = 45;
    static final int DISABLE_ALL_SLOT = 47;
    /** 1.4.187: три личных экрана — цвет, порядок меток и пресеты. */
    static final int COLORS_SLOT = 46;
    static final int ORDER_SLOT = 50;
    static final int PRESETS_SLOT = 51;
    static final int PROFILE_SLOT = 48;
    static final int RESET_SLOT = 49;
    static final int CLOSE_SLOT = 53;

    private final RegisterPlugin plugin;
    private final LabelPreferences preferences;
    private LabelsCommandHandler command;
    /** Три личных экрана 1.4.187; {@code null} — кнопки не показываем. */
    private LabelsStyleMenuService styleMenu;
    private volatile LabelPalette palette = LabelPalette.disabled();
    private volatile LabelLimits limits = LabelLimits.DEFAULT;

    public LabelsMenuService(RegisterPlugin plugin, LabelPreferences preferences) {
        this.plugin = plugin;
        this.preferences = preferences;
    }

    /** Обратная связь в чат — те же тексты, что у командной формы. */
    public void setCommand(LabelsCommandHandler command) {
        this.command = command;
    }

    /** Подключает экраны цвета, порядка и пресетов (1.4.187). */
    public void setStyleMenu(LabelsStyleMenuService styleMenu) {
        this.styleMenu = styleMenu;
    }

    /** Палитра нужна для подписи кнопки «Цвет»: какой цвет выбран сейчас. */
    public void applySettings(LabelPalette colors, LabelLimits limits) {
        this.palette = colors == null ? LabelPalette.disabled() : colors;
        this.limits = limits == null ? LabelLimits.DEFAULT : limits;
    }

    /* ------------------------------- открытие ------------------------------- */

    /** Открывает меню; без права — отказ тем же текстом, что у команды. */
    public void open(Player player) {
        if (player == null) {
            return;
        }
        if (!player.hasPermission("chrdkregister.labels")) {
            plugin.messages().send(player, "error.no-permission");
            return;
        }
        Holder holder = new Holder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.messages().get("labels.menu-title"));
        holder.bind(inventory);
        paint(player, inventory);
        player.openInventory(inventory);
    }

    /** Перерисовка без закрытия: клик по плитке оставляет игрока в меню. */
    private void paint(Player player, Inventory inventory) {
        ItemStack filler = filler();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        LabelStyleService.Selection selection = selectionOf(plugin, player.getName());
        inventory.setItem(HEAD_SLOT, head(player, selection));
        inventory.setItem(PREVIEW_SLOT, preview(player));
        for (int i = 0; i < PLACE_SLOTS.length; i++) inventory.setItem(PLACE_SLOTS[i], placeTile(player, i));
        List<LabelPreferences.Label> labels = LabelPreferences.Label.nameTags();
        // Шапка TAB — отдельный переключатель, не место возле ника.
        List<LabelPreferences.Label> tiles = new ArrayList<>(labels);
        tiles.add(LabelPreferences.Label.HEADER);
        for (int index = 0; index < Math.min(ENTRY_SLOTS.length, tiles.size()); index++) {
            inventory.setItem(ENTRY_SLOTS[index], tile(player, tiles.get(index), selection));
        }
        if (styleMenu != null) {
            inventory.setItem(COLORS_SLOT, colorButton(player));
            inventory.setItem(ORDER_SLOT, button(Material.ARROW,
                    plugin.messages().get("labels.menu-order")));
            inventory.setItem(PRESETS_SLOT, button(Material.BOOK,
                    plugin.messages().get("labels.menu-presets")));
        }
        inventory.setItem(ENABLE_ALL_SLOT, button(Material.LIME_DYE,
                plugin.messages().get("labels.menu-enable-all")));
        inventory.setItem(DISABLE_ALL_SLOT, button(Material.GRAY_DYE,
                plugin.messages().get("labels.menu-disable-all")));
        inventory.setItem(PROFILE_SLOT, button(Material.PLAYER_HEAD,
                plugin.messages().get("labels.menu-profile")));
        inventory.setItem(RESET_SLOT, button(Material.MILK_BUCKET,
                plugin.messages().get("labels.menu-reset")));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER,
                plugin.messages().get("labels.menu-close")));
    }

    /** Кнопка «Цвет»: подпись показывает выбранный цвет или «как у ранга». */
    private ItemStack colorButton(Player player) {
        LabelPalette colors = palette;
        String chosen = preferences.color(player.getName());
        LabelPalette.Color color = colors.enabled() ? colors.byId(chosen) : null;
        ItemStack item = new ItemStack(Material.CYAN_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(color == null
                    ? plugin.messages().get("labels.menu-color-auto")
                    : plugin.messages().get("labels.menu-color",
                    "name", Messages.trusted(color.name())));
            meta.lore(plugin.messages().getList("labels.menu-color-lore"));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Голова: сколько надписей включено из скольких. */
    private ItemStack head(Player player, LabelStyleService.Selection selection) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (!(item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta meta)) {
            return item;
        }
        meta.setOwningPlayer(player);
        int total = LabelPreferences.Label.values().length;
        int visible = total - preferences.hiddenCount(player.getName());
        meta.displayName(plugin.messages().get("labels.menu-head",
                "player", player.getName(),
                "visible", String.valueOf(visible),
                "total", String.valueOf(total)));
        List<Component> lore = new ArrayList<>();
        lore.addAll(plugin.messages().getList("labels.menu-head-lore"));
        if (selection != null) lore.add(summary(plugin.messages(), selection));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * «Вас видят так» — ник, собранный ровно так, как его получают другие.
     *
     * <p>Настройка надписей не личная: спрятанную метку не видит никто, поэтому
     * в меню и показывается общая строка, а не «как вижу я». Собирается тем же
     * {@code ChatService#decoratedName}, что и сообщения в чате, — расхождению
     * взяться неоткуда.</p>
     */
    private ItemStack preview(Player player) {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        ChatService chat = plugin.chatService();
        String nick = chat == null ? Messages.escape(player.getName())
                : chat.decoratedName(player.getName());
        meta.displayName(plugin.messages().get("labels.menu-preview",
                "nick", Messages.trusted(nick == null || nick.isBlank()
                        ? Messages.escape(player.getName()) : nick)));
        meta.lore(plugin.messages().getList("labels.menu-preview-lore"));
        item.setItemMeta(meta);
        return item;
    }

    /** Плитка одной надписи: иконка, состояние и подсказка. */
    private ItemStack tile(Player player, LabelPreferences.Label label, LabelStyleService.Selection selection) {
        boolean visible = preferences.visible(player.getName(), label);
        ItemStack item = new ItemStack(icon(label));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(visible
                ? plugin.messages().get("labels.menu-entry-on",
                        "name", nameOf(plugin.messages(), label))
                : plugin.messages().get("labels.menu-entry-off",
                        "name", nameOf(plugin.messages(), label)));
        List<Component> lore = new ArrayList<>();
        lore.add(descOf(label));
        lore.add(plugin.messages().get(visible
                ? "labels.menu-lore-state-on" : "labels.menu-lore-state-off"));
        lore.add(plugin.messages().get("labels.source-state", "value",
                stateOf(plugin.messages(), preferences, player.getName(), label, selection)));
        lore.add(plugin.messages().get("labels.menu-lore-click"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Один снимок используется для всех плиток, команды и карточки игрока. */
    static LabelStyleService.Selection selectionOf(RegisterPlugin plugin, String playerName) {
        ChatService chat = plugin.chatService();
        return chat == null || chat.labelStyles() == null ? null : chat.labelStyles().selection(playerName);
    }

    static Component summary(Messages messages, LabelStyleService.Selection selection) {
        return messages.get("labels.display-summary", "shown", selection.shown().size(),
                "limit", selection.limit(), "waiting", selection.overflow().size());
    }

    static Messages.Trusted stateOf(Messages messages, LabelPreferences preferences, String playerName,
                                   LabelPreferences.Label label, LabelStyleService.Selection selection) {
        if (!preferences.visible(playerName, label)) return messages.mini("labels.value-off");
        if (label == LabelPreferences.Label.HEADER || selection == null || selection.displayed(label)) {
            return messages.mini("labels.value-on");
        }
        if (!selection.obtained(label)) return messages.mini("labels.value-unavailable");
        return selection.deferred(label) ? messages.mini("labels.value-waiting")
                : messages.mini("labels.value-unassigned");
    }

    /** Места всегда показывают сохранённое назначение, даже если источник исчез. */
    ItemStack placeTile(Player player, int index) {
        ChatService chat = plugin.chatService();
        LabelStyleService styles = chat == null ? null : chat.labelStyles();
        LabelStyleService.Selection selection = styles == null ? null : styles.selection(player.getName());
        LabelPreferences.Label chosen = styles == null ? null
                : LabelPreferences.Label.byNameTagId(styles.assignments(player.getName()).get(index));
        boolean open = selection != null && index < selection.limit();
        ItemStack item = new ItemStack(open ? Material.NAME_TAG : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(chosen == null ? plugin.messages().get("labels.slot-empty", "number", index + 1)
                : plugin.messages().get("labels.order-name", "position", index + 1,
                        "name", nameOf(plugin.messages(), chosen)));
        List<Component> lore = new ArrayList<>();
        if (chosen != null) lore.add(plugin.messages().get("labels.source-state", "value",
                stateOf(plugin.messages(), preferences, player.getName(), chosen, selection)));
        if (open) {
            lore.add(plugin.messages().get("labels.slot-choose"));
            lore.add(plugin.messages().get("labels.slot-clear-hint"));
        } else {
            int level = limits.levelForLabelSlot(index);
            lore.add(level < 0 ? plugin.messages().get("labels.slot-config-locked")
                    : plugin.messages().get("labels.slot-need-rank", "level", level));
        }
        lore.add(plugin.messages().get("labels.slot-no-fallback"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static int placeIndex(int slot) {
        for (int i = 0; i < PLACE_SLOTS.length; i++) if (PLACE_SLOTS[i] == slot) return i;
        return -1;
    }

    /* ------------------------------ тексты и иконки ------------------------------ */

    /**
     * Название надписи. Ключи перечислены явно: контракт сообщений видит
     * литералы, а опечатка в имени не превращается в пустую строку. Метод
     * общий с командой {@code /labels status}, поэтому ключи читаются из
     * одного места.
     */
    static Messages.Trusted nameOf(Messages messages, LabelPreferences.Label label) {
        return switch (label) {
            case RANK -> messages.mini("labels.name-rank");
            case TIERFORGE -> messages.mini("labels.name-tierforge");
            case TREASURES -> messages.mini("labels.name-treasures");
            case CLASSES -> messages.mini("labels.name-classes");
            case GUILDS -> messages.mini("labels.name-guilds");
            case STREAMS -> messages.mini("labels.name-streams");
            case QUESTS -> messages.mini("labels.name-quests");
            case ACHIEVEMENTS -> messages.mini("labels.name-achievements");
            case HEADER -> messages.mini("labels.name-header");
        };
    }

    /** Пояснение: откуда надпись и что она значит. */
    private Component descOf(LabelPreferences.Label label) {
        return switch (label) {
            case RANK -> plugin.messages().get("labels.desc-rank");
            case TIERFORGE -> plugin.messages().get("labels.desc-tierforge");
            case TREASURES -> plugin.messages().get("labels.desc-treasures");
            case CLASSES -> plugin.messages().get("labels.desc-classes");
            case GUILDS -> plugin.messages().get("labels.desc-guilds");
            case STREAMS -> plugin.messages().get("labels.desc-streams");
            case QUESTS -> plugin.messages().get("labels.desc-quests");
            case ACHIEVEMENTS -> plugin.messages().get("labels.desc-achievements");
            case HEADER -> plugin.messages().get("labels.desc-header");
        };
    }

    /** Иконка надписи: у каждой свой предмет, чтобы плитки различались издалека. */
    private static Material icon(LabelPreferences.Label label) {
        return switch (label) {
            case RANK -> Material.NETHER_STAR;
            case TIERFORGE -> Material.ANVIL;
            case TREASURES -> Material.COMPASS;
            case CLASSES -> Material.IRON_SWORD;
            case GUILDS -> Material.WHITE_BANNER;
            case STREAMS -> Material.JUKEBOX;
            case QUESTS -> Material.WRITABLE_BOOK;
            case ACHIEVEMENTS -> Material.EXPERIENCE_BOTTLE;
            case HEADER -> Material.PAPER;
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

    /* --------------------------------- слоты ---------------------------------- */

    /** Надпись в этом слоте; {@code null} — слот занят чем-то другим. */
    static LabelPreferences.Label labelAt(int slot) {
        List<LabelPreferences.Label> tiles =
                new ArrayList<>(LabelPreferences.Label.nameTags());
        tiles.add(LabelPreferences.Label.HEADER);
        for (int index = 0; index < ENTRY_SLOTS.length; index++) {
            if (ENTRY_SLOTS[index] == slot) {
                return index < tiles.size() ? tiles.get(index) : null;
            }
        }
        return null;
    }

    /** Слот плитки надписи; {@code -1} — надписи нет в меню. */
    static int slotOf(LabelPreferences.Label label) {
        List<LabelPreferences.Label> tiles =
                new ArrayList<>(LabelPreferences.Label.nameTags());
        tiles.add(LabelPreferences.Label.HEADER);
        int index = tiles.indexOf(label);
        return index < 0 || index >= ENTRY_SLOTS.length ? -1 : ENTRY_SLOTS[index];
    }

    /* --------------------------------- клики ---------------------------------- */

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
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return; // клик по своему инвентарю — ничего не делаем
        }
        int place = placeIndex(slot);
        if (place >= 0 && styleMenu != null) {
            if (event.isRightClick()) {
                LabelStyleService styles = plugin.chatService() == null ? null : plugin.chatService().labelStyles();
                if (styles != null && styles.assign(player.getName(), place, null)
                        == LabelStyleService.AssignmentResult.APPLIED) {
                    plugin.messages().send(player, "labels.slot-cleared", "number", place + 1);
                    plugin.refreshTab();
                    repaint(player);
                } else plugin.messages().send(player, "labels.slot-locked");
            } else {
                player.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) styleMenu.openSlot(player, place);
                });
            }
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == PROFILE_SLOT) {
            // Открываем карточку следующим тиком: закрытие и открытие меню
            // подряд некоторые клиенты проглатывают.
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    plugin.profile().show(player, false, null);
                }
            });
            return;
        }
        if (styleMenu != null && slot == COLORS_SLOT) {
            Player viewer = player;
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) {
                    styleMenu.openColors(viewer);
                }
            });
            return;
        }
        if (styleMenu != null && slot == ORDER_SLOT) {
            Player viewer = player;
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) {
                    styleMenu.openOrder(viewer);
                }
            });
            return;
        }
        if (styleMenu != null && slot == PRESETS_SLOT) {
            Player viewer = player;
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) {
                    styleMenu.openPresets(viewer);
                }
            });
            return;
        }
        if (slot == ENABLE_ALL_SLOT) {
            preferences.setAll(player.getName(), true);
            feedback(player, true);
            return;
        }
        if (slot == DISABLE_ALL_SLOT) {
            preferences.setAll(player.getName(), false);
            feedback(player, false);
            return;
        }
        if (slot == RESET_SLOT) {
            preferences.reset(player.getName());
            plugin.messages().send(player, "labels.reset");
            plugin.refreshTab();
            repaint(player);
            return;
        }
        LabelPreferences.Label label = labelAt(slot);
        if (label != null) {
            boolean nowVisible = preferences.toggle(player.getName(), label);
            if (command != null) {
                command.announceToggle(player, label, nowVisible);
            }
            plugin.refreshTab();
            repaint(player);
        }
    }

    /** Общий ответ на «всё вкл/выкл»: текст в чат, перерисовка таба и меню. */
    private void feedback(Player player, boolean visible) {
        plugin.messages().send(player, visible ? "labels.all-on" : "labels.all-off");
        plugin.refreshTab();
        repaint(player);
    }

    /** Перерисовывает меню, если оно открыто у игрока. */
    private void repaint(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder
                && holder.owner().equals(player.getUniqueId())) {
            repaint(player, holder);
        }
    }

    private void repaint(Player player, Holder holder) {
        Inventory inventory = holder.getInventory();
        if (inventory != null) {
            paint(player, inventory);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Закрывает меню при выключении плагина: холдер не переживает перезагрузку. */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                player.closeInventory();
            }
        }
    }

    /** Маркерный холдер: отличает наше меню от чужих инвентарей. */
    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private Holder(UUID owner) {
            this.owner = owner;
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
