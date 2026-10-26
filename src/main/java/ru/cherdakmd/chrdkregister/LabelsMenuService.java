package ru.cherdakmd.chrdkregister;

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

    /** Шесть рядов: заголовок, девять плиток, кнопки. */
    static final int SIZE = 54;
    /** Голова игрока со сводкой. */
    static final int HEAD_SLOT = 4;
    /** Как ник выглядит для остальных — ровно та строка, что уходит в чат и таб. */
    static final int PREVIEW_SLOT = 8;
    /** Плитки надписей: три ряда по три. */
    static final int[] ENTRY_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    static final int ENABLE_ALL_SLOT = 45;
    static final int DISABLE_ALL_SLOT = 47;
    static final int PROFILE_SLOT = 48;
    static final int RESET_SLOT = 49;
    static final int CLOSE_SLOT = 53;

    private final RegisterPlugin plugin;
    private final LabelPreferences preferences;
    private LabelsCommandHandler command;

    public LabelsMenuService(RegisterPlugin plugin, LabelPreferences preferences) {
        this.plugin = plugin;
        this.preferences = preferences;
    }

    /** Обратная связь в чат — те же тексты, что у командной формы. */
    public void setCommand(LabelsCommandHandler command) {
        this.command = command;
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
        inventory.setItem(HEAD_SLOT, head(player));
        inventory.setItem(PREVIEW_SLOT, preview(player));
        List<LabelPreferences.Label> labels = LabelPreferences.Label.nameTags();
        // Шапка таба — не метка рядом с ником, но такая же надпись: ставим её
        // последней плиткой, чтобы сетка оставалась 3×3.
        List<LabelPreferences.Label> tiles = new ArrayList<>(labels);
        tiles.add(LabelPreferences.Label.HEADER);
        for (int index = 0; index < Math.min(ENTRY_SLOTS.length, tiles.size()); index++) {
            inventory.setItem(ENTRY_SLOTS[index], tile(player, tiles.get(index)));
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

    /** Голова: сколько надписей включено из скольких. */
    private ItemStack head(Player player) {
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
    private ItemStack tile(Player player, LabelPreferences.Label label) {
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
        lore.add(plugin.messages().get("labels.menu-lore-click"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
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
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return; // клик по своему инвентарю — ничего не делаем
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
