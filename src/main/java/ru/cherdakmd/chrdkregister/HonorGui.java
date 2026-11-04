package ru.cherdakmd.chrdkregister;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Полноценный GUI-редактор особых званий, открываемый из админ-панели и
 * {@code /honoradmin gui}.
 *
 * <p>Три страницы: справочник званий, карточка одного звания и общий реестр
 * выдач. Звание можно создать, переименовать, задать градиент ника и знак,
 * поднять/опустить привязанную ступень, задать описание и строку входа,
 * удалить, выдать конкретному игроку и снять. Режим {@code forPlayer}
 * открывается кнопкой из профиля админ-панели: администратор сам выбирает,
 * какое звание дать именно этому человеку.</p>
 *
 * <p>Текстовые поля вводятся следующим сообщением в чат и перехватываются на
 * LOWEST, как в админ-панели модерации. Все клики и drag отменяются.</p>
 */
public final class HonorGui implements Listener {

    public static final int SIZE = 54;
    public static final int[] ENTRY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    public enum Page { DEFINITIONS, DETAIL, GRANTS }

    private enum Input {
        CREATE_ID, CREATE_NAME, HONOR_NAME, FORMAT, BADGE, DESCRIPTION, JOIN,
        DELETE_CONFIRM, GRANT_PLAYER, GRANT_REASON, FILTER
    }

    static final int SLOT_DEFINITIONS = 0;
    static final int SLOT_GRANTS = 1;
    static final int SLOT_INFO = 4;
    static final int SLOT_FILTER = 6;
    static final int SLOT_REFRESH = 8;
    static final int SLOT_PREV = 45;
    static final int SLOT_PAGES = 46;
    static final int SLOT_NEXT = 47;
    static final int SLOT_DELETE = 45;
    static final int SLOT_JOIN = 46;
    static final int SLOT_DESCRIPTION = 47;
    static final int SLOT_NAME = 48;
    static final int SLOT_FORMAT = 49;
    static final int SLOT_BADGE = 50;
    static final int SLOT_LEVEL = 51;
    static final int SLOT_BACK = 52;
    static final int SLOT_CLOSE = 53;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault());

    /** Состояние открытого редактора. */
    public static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Page page;
        /** На странице DETAIL — id звания; в режиме выбора — пусто. */
        private String target = "";
        /** Непусто — режим «выбрать звание для этого игрока». */
        private String player = "";
        private int index;
        private String filter = "";
        private Inventory inventory;

        Holder(UUID owner, Page page, String target, String player) {
            this.owner = owner;
            this.page = page;
            this.target = target == null ? "" : target;
            this.player = player == null ? "" : player;
        }

        public UUID owner() { return owner; }
        public Page page() { return page; }
        public String target() { return target; }
        public String player() { return player; }
        public int index() { return index; }
        public String filter() { return filter; }

        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    private record Pending(Input type, Page page, String target, String player,
                           int index, String filter, String extra) {
        Pending withFilter(String value) {
            return new Pending(type, page, target, player, index, value, extra);
        }
    }

    private final RegisterPlugin plugin;
    private final HonorService honors;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public HonorGui(RegisterPlugin plugin, HonorService honors) {
        this.plugin = plugin;
        this.honors = honors;
    }

    /* ------------------------------- открытие ------------------------------ */

    public void open(Player viewer, Page page, String target) {
        open(viewer, page, target, "", 0, "");
    }

    /** Открыть выбор звания для конкретного игрока (кнопка профиля админ-панели). */
    public void openForPlayer(Player viewer, String playerName) {
        open(viewer, Page.DEFINITIONS, "", playerName == null ? "" : playerName, 0, "");
    }

    public void open(Player viewer, Page page, String target, String player, int index, String filter) {
        if (viewer == null) return;
        if (!honors.canViewGui(viewer)) {
            plugin.messages().send(viewer, "honor.gui.no-permission");
            return;
        }
        Holder holder = new Holder(viewer.getUniqueId(),
                page == null ? Page.DEFINITIONS : page, target, player);
        holder.index = Math.max(0, index);
        holder.filter = filter == null ? "" : filter.trim();
        Messages messages = plugin.messages();
        Component title = switch (holder.page) {
            case DEFINITIONS -> holder.player.isEmpty()
                    ? messages.get("honor.gui.title-definitions")
                    : messages.get("honor.gui.title-choose", "player", holder.player);
            case DETAIL -> messages.get("honor.gui.title-detail", "name", honors.ledger().display(holder.target));
            case GRANTS -> messages.get("honor.gui.title-grants");
        };
        holder.inventory = Bukkit.createInventory(holder, SIZE, title);
        paint(viewer, holder);
        viewer.openInventory(holder.inventory);
    }

    /** Перерисовать все открытые редакторы после reload или чужой правки. */
    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder
                    && holder.owner.equals(player.getUniqueId())) {
                paint(player, holder);
            }
        }
    }

    public void shutdown() {
        pending.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                player.closeInventory();
            }
        }
    }

    /* -------------------------------- отрисовка ----------------------------- */

    private void paint(Player viewer, Holder holder) {
        Messages messages = plugin.messages();
        Inventory inv = holder.inventory;
        for (int slot = 0; slot < SIZE; slot++) inv.setItem(slot, filler());

        inv.setItem(SLOT_DEFINITIONS, icon(Material.BOOK,
                messages.get("honor.gui.button-definitions"), List.of()));
        inv.setItem(SLOT_GRANTS, icon(Material.NAME_TAG,
                messages.get("honor.gui.button-grants"), List.of()));
        inv.setItem(SLOT_FILTER, icon(Material.HOPPER,
                messages.get("honor.gui.button-filter", "filter",
                        holder.filter.isEmpty() ? "—" : holder.filter), List.of()));
        inv.setItem(SLOT_REFRESH, icon(Material.CLOCK,
                messages.get("honor.gui.button-refresh"), List.of()));
        // На карточке звания слоты 45–47 отданы кнопкам редактора, а не листанию.
        if (holder.page != Page.DETAIL) {
            inv.setItem(SLOT_PREV, icon(Material.ARROW,
                    messages.get("honor.gui.button-prev", "page", holder.index + 1), List.of()));
            inv.setItem(SLOT_NEXT, icon(Material.ARROW,
                    messages.get("honor.gui.button-next", "page", holder.index + 1), List.of()));
        }
        inv.setItem(SLOT_BACK, icon(Material.CHEST,
                messages.get("honor.gui.button-back"), List.of()));
        inv.setItem(SLOT_CLOSE, icon(Material.BARRIER,
                messages.get("honor.gui.button-close"), List.of()));

        switch (holder.page) {
            case DEFINITIONS -> paintDefinitions(viewer, holder);
            case DETAIL -> paintDetail(viewer, holder);
            case GRANTS -> paintGrants(viewer, holder);
        }
    }

    private void paintDefinitions(Player viewer, Holder holder) {
        Messages messages = plugin.messages();
        List<HonorDefinition> defs = filterDefinitions(holder.filter);
        holder.inventory.setItem(SLOT_INFO, icon(Material.NETHER_STAR,
                messages.get("honor.gui.info-title"),
                messages.getList("honor.gui.info",
                        "definitions", defs.size(),
                        "grants", honors.ledger().allGrants().size(),
                        "actor", viewer.getName())));
        if (holder.player.isEmpty()) {
            holder.inventory.setItem(SLOT_NAME, icon(Material.EMERALD,
                    messages.get("honor.gui.button-create"), List.of()));
            holder.inventory.setItem(SLOT_FORMAT, icon(Material.PLAYER_HEAD,
                    messages.get("honor.gui.button-grant"), List.of()));
        } else {
            holder.inventory.setItem(SLOT_INFO, icon(Material.PLAYER_HEAD,
                    messages.get("honor.gui.title-choose", "player", holder.player),
                    List.of(Component.text(messages.plain("honor.gui.choose-hint")))));
        }
        paintSlots(holder, defs.size(), (slot, offset) -> {
            HonorDefinition def = defs.get(offset);
            int granted = honors.ledger().grantsOfHonor(def.id()).size();
            return icon(Material.PAPER,
                    messages.get("honor.gui.definition-entry",
                            "name", def.name(), "id", def.id(),
                            "level", def.grantLevel(), "players", granted),
                    List.of(Component.text(def.description()),
                            Component.text(def.format()),
                            Component.text(messages.plain("honor.gui.definition-hint"))));
        }, true);
        if (defs.isEmpty()) {
            empty(holder, Material.PAPER, messages.get("honor.gui.empty-definitions"));
        }
    }

    private void paintDetail(Player viewer, Holder holder) {
        Messages messages = plugin.messages();
        HonorDefinition def = honors.ledger().definition(holder.target);
        if (def == null) {
            // Звание удалили в другой панели: не переоткрываем инвентарь изнутри отрисовки.
            empty(holder, Material.PAPER, messages.get("honor.gui.empty-definitions"));
            return;
        }
        List<HonorGrant> grants = honors.ledger().grantsOfHonor(def.id());
        holder.inventory.setItem(SLOT_INFO, icon(Material.PLAYER_HEAD,
                messages.get("honor.gui.button-grant-to"),
                messages.getList("honor.gui.detail-info",
                        "name", def.name(), "id", def.id(), "level", def.grantLevel(),
                        "badge", def.badge(), "listed", def.listed() ? "да" : "нет",
                        "description", def.description())));
        holder.inventory.setItem(SLOT_NAME, icon(Material.NAME_TAG,
                messages.get("honor.gui.button-name", "value", def.name()), List.of()));
        holder.inventory.setItem(SLOT_FORMAT, icon(Material.LIME_DYE,
                messages.get("honor.gui.button-format", "value", def.format()), List.of()));
        holder.inventory.setItem(SLOT_BADGE, icon(Material.GOLD_NUGGET,
                messages.get("honor.gui.button-badge", "value", def.badge()), List.of()));
        holder.inventory.setItem(SLOT_LEVEL, icon(Material.DIAMOND,
                messages.get("honor.gui.button-level", "level", def.grantLevel()), List.of()));
        holder.inventory.setItem(SLOT_DESCRIPTION, icon(Material.WRITABLE_BOOK,
                messages.get("honor.gui.button-description"), List.of()));
        holder.inventory.setItem(SLOT_JOIN, icon(Material.OAK_DOOR,
                messages.get("honor.gui.button-join"), List.of()));
        holder.inventory.setItem(SLOT_DELETE, icon(Material.BARRIER,
                messages.get("honor.gui.button-delete"), List.of()));
        paintSlots(holder, grants.size(), (slot, offset) -> {
            HonorGrant grant = grants.get(offset);
            return icon(Material.PLAYER_HEAD,
                    messages.get("honor.gui.detail-entry",
                            "player", grant.player(), "date", date(grant.grantedAt()),
                            "reason", grant.reason(), "actor", grant.grantedBy()),
                    List.of(Component.text(messages.plain("honor.gui.revoke-hint"))));
        }, false);
        if (grants.isEmpty()) {
            empty(holder, Material.PLAYER_HEAD, messages.get("honor.gui.empty-detail"));
        }
    }

    private void paintGrants(Player viewer, Holder holder) {
        Messages messages = plugin.messages();
        List<HonorGrant> grants = honors.ledger().allGrants().stream()
                .filter(grant -> holder.filter.isEmpty()
                        || grant.player.toLowerCase(Locale.ROOT).contains(holder.filter.toLowerCase(Locale.ROOT))
                        || grant.reason.toLowerCase(Locale.ROOT).contains(holder.filter.toLowerCase(Locale.ROOT))
                        || honors.ledger().display(grant.honorId()).toLowerCase(Locale.ROOT)
                                .contains(holder.filter.toLowerCase(Locale.ROOT)))
                .toList();
        holder.inventory.setItem(SLOT_INFO, icon(Material.BOOK,
                messages.get("honor.gui.info-title"),
                messages.getList("honor.gui.info",
                        "definitions", honors.ledger().definitions().size(),
                        "grants", grants.size(), "actor", viewer.getName())));
        paintSlots(holder, grants.size(), (slot, offset) -> {
            HonorGrant grant = grants.get(offset);
            return icon(Material.NAME_TAG,
                    messages.get("honor.gui.grant-entry",
                            "player", grant.player(),
                            "honor", honors.ledger().display(grant.honorId()),
                            "date", date(grant.grantedAt()),
                            "reason", grant.reason(), "actor", grant.grantedBy()),
                    List.of(Component.text(messages.plain("honor.gui.revoke-hint"))));
        }, true);
        if (grants.isEmpty()) {
            empty(holder, Material.NAME_TAG, messages.get("honor.gui.empty-grants"));
        }
    }

    private List<HonorDefinition> filterDefinitions(String filter) {
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        return honors.ledger().definitions().stream()
                .filter(def -> needle.isEmpty()
                        || def.id().contains(needle)
                        || def.name().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private void paintSlots(Holder holder, int total,
                            java.util.function.BiFunction<Integer, Integer, ItemStack> factory,
                            boolean showPages) {
        int start = holder.index * ENTRY_SLOTS.length;
        if (start >= total && start > 0) {
            holder.index = Math.max(0, (total - 1) / ENTRY_SLOTS.length);
            start = holder.index * ENTRY_SLOTS.length;
        }
        for (int i = 0; i < ENTRY_SLOTS.length; i++) {
            int offset = start + i;
            ItemStack item = offset < total ? factory.apply(ENTRY_SLOTS[i], offset) : null;
            holder.inventory.setItem(ENTRY_SLOTS[i], item == null ? filler() : item);
        }
        if (showPages) {
            Messages messages = plugin.messages();
            int pages = Math.max(1, (total + ENTRY_SLOTS.length - 1) / ENTRY_SLOTS.length);
            holder.inventory.setItem(SLOT_PAGES, icon(Material.BOOK,
                    messages.get("honor.gui.button-pages",
                            "page", holder.index + 1, "pages", pages, "total", total), List.of()));
        }
    }

    /* -------------------------------- клики -------------------------------- */

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)
                || !holder.owner.equals(viewer.getUniqueId())) return;
        if (!honors.canViewGui(viewer)) {
            viewer.closeInventory();
            plugin.messages().send(viewer, "honor.gui.no-permission");
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        boolean change = honors.canEdit(viewer);

        if (slot == SLOT_CLOSE) { viewer.closeInventory(); return; }
        if (slot == SLOT_DEFINITIONS) {
            open(viewer, Page.DEFINITIONS, "", "", 0, holder.filter); return;
        }
        if (slot == SLOT_GRANTS) {
            open(viewer, Page.GRANTS, "", "", 0, holder.filter); return;
        }
        if (slot == SLOT_REFRESH) { paint(viewer, holder); return; }
        if (slot == SLOT_FILTER) {
            if (!change) { readOnly(viewer); return; }
            startInput(viewer, Input.FILTER, holder, ""); return;
        }
        if (slot == SLOT_BACK) {
            if (holder.page == Page.DETAIL) {
                open(viewer, Page.DEFINITIONS, "", holder.player, 0, holder.filter);
            } else {
                open(viewer, Page.DEFINITIONS, "", "", 0, holder.filter);
            }
            return;
        }
        if (holder.page != Page.DETAIL && slot == SLOT_PREV) {
            if (holder.index > 0) { holder.index--; paint(viewer, holder); }
            return;
        }
        if (holder.page != Page.DETAIL && slot == SLOT_NEXT) {
            holder.index++; paint(viewer, holder); return;
        }

        if (holder.page == Page.DETAIL) {
            if (slot == SLOT_INFO) {
                if (!change) { readOnly(viewer); return; }
                startInput(viewer, Input.GRANT_PLAYER, holder, ""); return;
            }
            if (slot == SLOT_NAME) { requireChange(viewer, change); if (change)
                startInput(viewer, Input.HONOR_NAME, holder, ""); return; }
            if (slot == SLOT_FORMAT) { requireChange(viewer, change); if (change)
                startInput(viewer, Input.FORMAT, holder, ""); return; }
            if (slot == SLOT_BADGE) { requireChange(viewer, change); if (change)
                startInput(viewer, Input.BADGE, holder, ""); return; }
            if (slot == SLOT_DESCRIPTION) { requireChange(viewer, change); if (change)
                startInput(viewer, Input.DESCRIPTION, holder, ""); return; }
            if (slot == SLOT_JOIN) { requireChange(viewer, change); if (change)
                startInput(viewer, Input.JOIN, holder, ""); return; }
            if (slot == SLOT_DELETE) { requireChange(viewer, change); if (change)
                startInput(viewer, Input.DELETE_CONFIRM, holder, ""); return; }
            if (slot == SLOT_LEVEL) {
                if (!change) { readOnly(viewer); return; }
                HonorDefinition def = honors.ledger().definition(holder.target);
                if (def != null) {
                    int next = (def.grantLevel() + 1) % (HonorDefinition.MAX_GRANT_LEVEL + 1);
                    honors.edit(viewer, def.id(), "level", Integer.toString(next));
                    paint(viewer, holder);
                }
                return;
            }
        } else if (holder.page == Page.DEFINITIONS && holder.player.isEmpty()) {
            if (slot == SLOT_NAME) { requireChange(viewer, change); if (change)
                startInput(viewer, Input.CREATE_ID, holder, ""); return; }
            if (slot == SLOT_FORMAT) { requireChange(viewer, change); if (change)
                startInput(viewer, Input.GRANT_PLAYER, holder, ""); return; }
        }

        int index = entryIndex(slot);
        if (index < 0) return;
        int offset = holder.index * ENTRY_SLOTS.length + index;
        if (!change) { readOnly(viewer); return; }
        switch (holder.page) {
            case DEFINITIONS -> {
                List<HonorDefinition> defs = filterDefinitions(holder.filter);
                if (offset >= defs.size()) return;
                HonorDefinition def = defs.get(offset);
                if (!holder.player.isEmpty()) {
                    // Ручной выбор звания для конкретного игрока: сначала причина.
                    startInput(viewer, Input.GRANT_REASON, holder, def.id());
                } else {
                    open(viewer, Page.DETAIL, def.id(), "", 0, holder.filter);
                }
            }
            case DETAIL -> {
                List<HonorGrant> grants = honors.ledger().grantsOfHonor(holder.target);
                if (offset < grants.size()) {
                    honors.revoke(viewer, grants.get(offset).player(), holder.target);
                    paint(viewer, holder);
                }
            }
            case GRANTS -> {
                List<HonorGrant> grants = honors.ledger().allGrants().stream()
                        .filter(grant -> holder.filter.isEmpty()
                                || grant.player.toLowerCase(Locale.ROOT).contains(holder.filter.toLowerCase(Locale.ROOT))
                                || grant.reason.toLowerCase(Locale.ROOT).contains(holder.filter.toLowerCase(Locale.ROOT))
                                || honors.ledger().display(grant.honorId()).toLowerCase(Locale.ROOT)
                                        .contains(holder.filter.toLowerCase(Locale.ROOT)))
                        .toList();
                if (offset < grants.size()) {
                    honors.revoke(viewer, grants.get(offset).player(), grants.get(offset).honorId());
                    paint(viewer, holder);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void requireChange(Player viewer, boolean allowed) {
        if (!allowed) readOnly(viewer);
    }

    private void readOnly(Player viewer) {
        plugin.messages().send(viewer, "honor.gui.read-only");
    }

    private static int entryIndex(int slot) {
        for (int i = 0; i < ENTRY_SLOTS.length; i++) {
            if (ENTRY_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    /* -------------------------- ввод значений в чате ----------------------- */

    private void startInput(Player viewer, Input type, Holder holder, String extra) {
        pending.put(viewer.getUniqueId(), new Pending(type, holder.page, holder.target,
                holder.player, holder.index, holder.filter, extra));
        viewer.closeInventory();
        Messages messages = plugin.messages();
        switch (type) {
            case CREATE_ID -> messages.send(viewer, "honor.gui.input-create-id");
            case CREATE_NAME -> messages.send(viewer, "honor.gui.input-create-name");
            case HONOR_NAME -> messages.send(viewer, "honor.gui.input-name");
            case FORMAT -> messages.send(viewer, "honor.gui.input-format");
            case BADGE -> messages.send(viewer, "honor.gui.input-badge");
            case DESCRIPTION -> messages.send(viewer, "honor.gui.input-description");
            case JOIN -> messages.send(viewer, "honor.gui.input-join");
            case DELETE_CONFIRM -> messages.send(viewer, "honor.gui.input-delete",
                    "id", holder.target);
            case GRANT_PLAYER -> messages.send(viewer, "honor.gui.input-grant-player");
            case GRANT_REASON -> messages.send(viewer, "honor.gui.input-grant-reason",
                    "player", holder.player.isEmpty() ? "—" : holder.player,
                    "honor", honors.ledger().display(extra));
            case FILTER -> messages.send(viewer, "honor.gui.input-filter");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (pending.get(player.getUniqueId()) == null) return;
        try {
            event.setCancelled(true);
        } catch (Throwable ignored) {
            // Не все сборки Paper позволяют отменять AsyncChatEvent.
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> applyInput(player, text));
    }

    private void applyInput(Player player, String raw) {
        Pending request = pending.remove(player.getUniqueId());
        if (request == null || !player.isOnline() || !honors.canEdit(player)) return;
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
            plugin.messages().send(player, "honor.gui.input-cancelled");
            reopen(player, request);
            return;
        }
        switch (request.type) {
            case CREATE_ID -> {
                if (!HonorDefinition.validId(text)) {
                    plugin.messages().send(player, "honoradmin.bad-id");
                    reopen(player, request);
                    return;
                }
                pending.put(player.getUniqueId(), new Pending(Input.CREATE_NAME, request.page,
                        text, request.player, request.index, request.filter, ""));
                plugin.messages().send(player, "honor.gui.input-create-name");
                return;
            }
            case CREATE_NAME -> honors.create(player, request.target, text);
            case HONOR_NAME -> honors.edit(player, request.target, "name", text);
            case FORMAT -> honors.edit(player, request.target, "format", text);
            case BADGE -> honors.edit(player, request.target, "badge", text);
            case DESCRIPTION -> honors.edit(player, request.target, "description", text);
            case JOIN -> honors.edit(player, request.target, "join", text);
            case DELETE_CONFIRM -> {
                if (text.equalsIgnoreCase("да") || text.equalsIgnoreCase("yes")) {
                    honors.remove(player, request.target);
                    open(player, Page.DEFINITIONS, "", "", 0, "");
                    return;
                }
                plugin.messages().send(player, "honor.gui.input-cancelled");
            }
            case GRANT_PLAYER -> {
                if (!AccessManager.isValidMinecraftName(text)) {
                    plugin.messages().send(player, "honoradmin.bad-player", "player", text);
                    reopen(player, request);
                    return;
                }
                open(player, Page.DEFINITIONS, "", text, 0, "");
                return;
            }
            case GRANT_REASON -> {
                String target = request.player;
                if (target.isEmpty()) target = request.target;
                honors.grant(player, target, request.extra, text);
            }
            case FILTER -> { /* фильтр применяется при повторном открытии */ }
        }
        reopen(player, request.type == Input.FILTER ? request.withFilter(text) : request);
    }

    private void reopen(Player player, Pending request) {
        open(player, request.page(), request.target(), request.player(), request.index(), request.filter());
    }

    /* ------------------------------- утилиты ------------------------------- */

    private static String date(long millis) {
        return millis <= 0 ? "—" : DATE.format(Instant.ofEpochMilli(millis));
    }

    private static void empty(Holder holder, Material material, Component name) {
        holder.inventory.setItem(ENTRY_SLOTS[ENTRY_SLOTS.length / 2],
                icon(material, name, List.of()));
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name == null ? Component.empty() : name);
            if (lore != null && !lore.isEmpty()) meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack filler() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
    }
}
