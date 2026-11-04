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
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Админ-панель аудита игроков ({@code /adminpanel}).
 *
 * <p>Четыре страницы в одном инвентаре: список всех известных Core аккаунтов,
 * полный профиль выбранного игрока с историей варнов, состав модераторов и
 * фильтруемый журнал действий. Варны выдаются кнопкой с вводом причины в чат,
 * снимаются кликом по записи; бан/разбан и роль модератора — кнопки того же
 * профиля. Верхний ряд всегда виден, поэтому из журнала можно вернуться к
 * профилю, а из профиля — к списку игроков.</p>
 *
 * <p>Все клики и drag отменяются: предметы меню нельзя забрать. Причину
 * модератор пишет следующим сообщением в чат; оно перехватывается на LOWEST,
 * не уходит в общий чат и обрабатывается уже в главном потоке сервера.</p>
 */
public final class AdminPanelGui implements Listener {

    public static final int SIZE = 54;
    /** Семь слотов в четырёх средних рядах: список без наложения на рамку. */
    public static final int[] ENTRY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    static final int SLOT_PLAYERS = 0;
    static final int SLOT_MODERATORS = 1;
    static final int SLOT_JOURNAL = 2;
    static final int SLOT_INFO = 4;
    static final int SLOT_FILTER = 6;
    static final int SLOT_REFRESH = 8;
    static final int SLOT_PREV = 45;
    static final int SLOT_NEXT = 47;
    static final int SLOT_WARN = 48;
    static final int SLOT_BAN = 50;
    static final int SLOT_ROLE = 51;
    static final int SLOT_BACK = 52;
    static final int SLOT_CLOSE = 53;

    public enum Page { PLAYERS, PROFILE, MODERATORS, JOURNAL }

    private enum Input { WARN, BAN, MODERATOR_ADD, FILTER }

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    /** Состояние открытого меню. */
    public static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Page page;
        private String target = "";
        private int index;
        private String filter = "";
        private Inventory inventory;

        Holder(UUID owner, Page page, String target) {
            this.owner = owner;
            this.page = page;
            this.target = target;
        }

        public UUID owner() { return owner; }
        public Page page() { return page; }
        public String target() { return target; }
        public int index() { return index; }
        public String filter() { return filter; }

        @Override
        public @NotNull Inventory getInventory() { return inventory; }
    }

    private record Pending(Input type, Page page, String target, int index, String filter) {
        Pending withFilter(String value) {
            return new Pending(type, page, target, index, value);
        }
    }

    private final RegisterPlugin plugin;
    private final ModerationService service;
    /** Ожидание ввода причины: один запрос на администратора. */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public AdminPanelGui(RegisterPlugin plugin, ModerationService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /* ------------------------------- открытие ------------------------------ */

    public void open(Player viewer, Page page, String target) {
        open(viewer, page, target, 0, "");
    }

    public void open(Player viewer, Page page, String target, int index, String filter) {
        if (viewer == null) return;
        if (!service.isStaff(viewer)) {
            plugin.messages().send(viewer, "admin.no-permission");
            return;
        }
        Page safePage = page == null ? Page.PLAYERS : page;
        Holder holder = new Holder(viewer.getUniqueId(), safePage,
                ModerationLedger.display(target));
        holder.index = Math.max(0, index);
        holder.filter = filter == null ? "" : filter.trim();
        Messages messages = plugin.messages();
        Component title = switch (safePage) {
            case PLAYERS -> messages.get("admin.panel-title", "page", messages.plain("admin.page-players"));
            case PROFILE -> messages.get("admin.panel-title", "page", messages.plain("admin.page-profile"));
            case MODERATORS -> messages.get("admin.panel-title", "page", messages.plain("admin.page-moderators"));
            case JOURNAL -> messages.get("admin.panel-title", "page", messages.plain("admin.page-journal"));
        };
        holder.inventory = Bukkit.createInventory(holder, SIZE, title);
        paint(viewer, holder);
        viewer.openInventory(holder.inventory);
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
        Inventory inventory = holder.inventory;
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, filler());

        inventory.setItem(SLOT_PLAYERS, icon(Material.PLAYER_HEAD,
                messages.get("admin.button-players"), List.of()));
        inventory.setItem(SLOT_MODERATORS, icon(Material.NAME_TAG,
                messages.get("admin.button-moderators"), List.of()));
        inventory.setItem(SLOT_JOURNAL, icon(Material.BOOK,
                messages.get("admin.button-journal"), List.of()));
        inventory.setItem(SLOT_FILTER, icon(Material.HOPPER,
                messages.get("admin.button-filter", "filter",
                        holder.filter.isEmpty() ? messages.plain("admin.value-none") : holder.filter),
                List.of()));
        inventory.setItem(SLOT_REFRESH, icon(Material.CLOCK,
                messages.get("admin.button-refresh"), List.of()));
        inventory.setItem(SLOT_PREV, icon(Material.ARROW,
                messages.get("admin.button-prev", "page", holder.index + 1), List.of()));
        inventory.setItem(SLOT_NEXT, icon(Material.ARROW,
                messages.get("admin.button-next", "page", holder.index + 1), List.of()));
        inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER,
                messages.get("admin.button-close"), List.of()));
        inventory.setItem(SLOT_BACK, icon(Material.CHEST,
                messages.get("admin.button-back"), List.of()));

        switch (holder.page) {
            case PLAYERS -> paintPlayers(viewer, holder);
            case PROFILE -> paintProfile(viewer, holder);
            case MODERATORS -> paintModerators(viewer, holder);
            case JOURNAL -> paintJournal(viewer, holder);
        }
    }

    private void paintInfo(Player viewer, Holder holder, List<String> players) {
        Messages messages = plugin.messages();
        holder.inventory.setItem(SLOT_INFO, icon(Material.NETHER_STAR,
                messages.get("admin.panel-info", "page", switch (holder.page) {
                    case PLAYERS -> messages.plain("admin.page-players");
                    case PROFILE -> messages.plain("admin.page-profile");
                    case MODERATORS -> messages.plain("admin.page-moderators");
                    case JOURNAL -> messages.plain("admin.page-journal");
                }),
                messages.getList("admin.panel-info-lore",
                        "players", players.size(),
                        "warns", service.ledger().warnTotal(),
                        "banned", service.ledger().bannedCount(),
                        "staff", service.ledger().moderators().size(),
                        "limit", service.warnLimit(),
                        "actor", viewer.getName())));
    }

    private void paintPlayers(Player viewer, Holder holder) {
        Messages messages = plugin.messages();
        List<String> players = players();
        paintInfo(viewer, holder, players);
        List<String> visible = filter(players, holder.filter);
        paintSlots(viewer, holder, visible.size(), (slot, offset) -> {
            String name = visible.get(offset);
            boolean online = Bukkit.getPlayerExact(name) != null;
            int warns = service.warnCount(name);
            return icon(Material.PLAYER_HEAD,
                    messages.get("admin.players-entry", "player", name),
                    messages.getList("admin.players-entry-lore",
                            "online", online ? messages.plain("admin.value-yes") : messages.plain("admin.value-no"),
                            "warns", warns, "limit", service.warnLimit(),
                            "banned", service.isBanned(name)
                                    ? messages.plain("admin.value-yes") : messages.plain("admin.value-no"),
                            "staff", service.ledger().isModerator(name)
                                    ? messages.plain("admin.value-yes") : messages.plain("admin.value-no")));
        });
        if (visible.isEmpty()) {
            empty(holder, Material.PAPER, messages.get("admin.players-empty"));
        }
    }

    private void paintProfile(Player viewer, Holder holder) {
        Messages messages = plugin.messages();
        String name = holder.target;
        paintInfo(viewer, holder, players());
        holder.inventory.setItem(SLOT_INFO, icon(Material.PLAYER_HEAD,
                messages.get("admin.profile-head", "player", name),
                profileRows(name)));

        boolean banned = service.isBanned(name);
        boolean staff = service.ledger().isModerator(name);
        holder.inventory.setItem(SLOT_WARN, icon(Material.WRITABLE_BOOK,
                messages.get("admin.button-warn", "player", name, "count", service.warnCount(name),
                        "limit", service.warnLimit()), List.of()));
        Component banName = banned
                ? messages.get("admin.button-unban", "player", name)
                : messages.get("admin.button-ban", "player", name);
        holder.inventory.setItem(SLOT_BAN,
                icon(banned ? Material.EMERALD : Material.REDSTONE_BLOCK, banName, List.of()));
        Component roleName = staff
                ? messages.get("admin.button-role-remove", "player", name)
                : messages.get("admin.button-role-add", "player", name);
        holder.inventory.setItem(SLOT_ROLE, icon(Material.GOLDEN_AXE, roleName, List.of()));

        List<ModerationLedger.Warn> history = service.ledger().warnHistory(name);
        paintSlots(viewer, holder, history.size(), (slot, offset) -> {
            ModerationLedger.Warn warn = history.get(offset);
            boolean removed = warn.removedAt > 0;
            return icon(removed ? Material.PAPER : Material.ENCHANTED_BOOK,
                    messages.get("admin.warn-entry", "id", warn.id, "reason", warn.reason),
                    messages.getList("admin.warn-entry-lore",
                            "actor", warn.issuer, "at", date(warn.at),
                            "status", removed ? messages.plain("admin.value-removed") : messages.plain("admin.value-active"),
                            "removed", removed ? warn.removedBy + " · " + date(warn.removedAt)
                                    : messages.plain("admin.value-none"),
                            "hint", messages.plain("admin.button-remove-warn")));
        });
        if (history.isEmpty()) {
            empty(holder, Material.PAPER, messages.get("admin.warns-empty", "player", name));
        }
    }

    private void paintModerators(Player viewer, Holder holder) {
        Messages messages = plugin.messages();
        paintInfo(viewer, holder, players());
        List<ModerationLedger.Moderator> staff = filterModerators(service.ledger().moderators(), holder.filter);
        paintSlots(viewer, holder, staff.size(), (slot, offset) -> {
            ModerationLedger.Moderator moderator = staff.get(offset);
            return icon(Material.NAME_TAG,
                    messages.get("admin.moderator-entry", "player", moderator.name),
                    messages.getList("admin.moderator-entry-lore",
                            "actor", moderator.addedBy, "at", date(moderator.addedAt),
                            "hint", messages.plain("admin.button-role-remove")));
        });
        inventorySet(holder, 49, icon(Material.EMERALD,
                messages.get("admin.button-role-add", "player", messages.plain("admin.value-none")), List.of()));
        if (staff.isEmpty()) {
            empty(holder, Material.PAPER, messages.get("admin.moderators-empty"));
        }
    }

    private void paintJournal(Player viewer, Holder holder) {
        Messages messages = plugin.messages();
        paintInfo(viewer, holder, players());
        List<ModerationLedger.JournalEntry> entries = holder.filter.isEmpty()
                ? service.ledger().journal("", service.config().journalLimit())
                : service.ledger().journal(holder.filter, service.config().journalLimit());
        paintSlots(viewer, holder, entries.size(), (slot, offset) -> {
            ModerationLedger.JournalEntry entry = entries.get(offset);
            return icon(Material.PAPER,
                    messages.get("admin.journal-entry", "id", entry.id, "action", entry.action),
                    messages.getList("admin.journal-entry-lore",
                            "at", date(entry.at), "actor", entry.actor,
                            "target", entry.target.isEmpty() ? messages.plain("admin.value-none") : entry.target,
                            "detail", entry.detail.isEmpty() ? messages.plain("admin.value-none") : entry.detail));
        });
        if (entries.isEmpty()) {
            empty(holder, Material.PAPER, messages.get("admin.journal-empty"));
        }
    }

    /** Заполняет слоты записями текущей страницы и рисует подсказку о страницах. */
    private void paintSlots(Player viewer, Holder holder, int total,
                            java.util.function.BiFunction<Integer, Integer, ItemStack> factory) {
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
        Messages messages = plugin.messages();
        int pages = Math.max(1, (total + ENTRY_SLOTS.length - 1) / ENTRY_SLOTS.length);
        inventorySet(holder, 46, icon(Material.BOOK,
                messages.get("admin.button-pages", "page", holder.index + 1, "pages", pages, "total", total),
                List.of()));
    }

    private void empty(Holder holder, Material material, Component name) {
        // Центр списка: подсказка «пусто» вместо стеклянной рамки.
        holder.inventory.setItem(ENTRY_SLOTS[ENTRY_SLOTS.length / 2],
                icon(material, name, List.of()));
    }

    private void inventorySet(Holder holder, int slot, ItemStack item) {
        holder.inventory.setItem(slot, item);
    }

    /* ------------------------------- профиль ------------------------------- */

    private List<Component> profileRows(String name) {
        Messages messages = plugin.messages();
        List<Component> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        Optional<RegistrationStore.PlayerAccount> found = plugin.store().getAccount(name);
        if (found.isEmpty()) {
            rows.add(messages.get("admin.field-missing", "player", name));
        }
        RegistrationStore.PlayerAccount account = found.orElse(null);
        Player online = Bukkit.getPlayerExact(name);
        String uuid = account != null && account.uuid != null && !account.uuid.isBlank() ? account.uuid
                : online != null ? online.getUniqueId().toString() : messages.plain("admin.value-none");
        rows.add(row(messages.plain("admin.field-uuid"), uuid));
        rows.add(row(messages.plain("admin.field-online"),
                online != null ? messages.plain("admin.value-yes") : messages.plain("admin.value-no")));
        if (account != null) {
            rows.add(row(messages.plain("admin.field-rank"),
                    "L" + account.rankLevel + (account.rankPermanent ? " ∞" : "")
                            + (account.tempRankLevel > 0 ? " +" + account.tempRankLevel : "")));
            rows.add(row(messages.plain("admin.field-pass"),
                    account.passUntil > now ? date(account.passUntil) : messages.plain("admin.value-none")));
            rows.add(row(messages.plain("admin.field-registered"),
                    account.registeredAt > 0 ? date(account.registeredAt) : messages.plain("admin.value-no")));
            rows.add(row(messages.plain("admin.field-2fa"),
                    account.totpEnabledAt > 0 ? date(account.totpEnabledAt) : messages.plain("admin.value-no")));
            rows.add(row(messages.plain("admin.field-frozen"),
                    account.loginFrozen ? messages.plain("admin.value-yes") : messages.plain("admin.value-no")));
            rows.add(row(messages.plain("admin.field-ip"),
                    account.lastAuthIp == null || account.lastAuthIp.isBlank()
                            ? messages.plain("admin.value-none") : account.lastAuthIp));
            rows.add(row(messages.plain("admin.field-last-auth"),
                    account.lastAuthAt > 0 ? date(account.lastAuthAt) : messages.plain("admin.value-none")));
            List<String> ips = new ArrayList<>();
            if (account.sessions != null) {
                account.sessions.stream()
                        .sorted(Comparator.comparingLong((RegistrationStore.SessionEntry s) -> s.lastSeenAt).reversed())
                        .limit(3)
                        .forEach(session -> ips.add(session.ip + " (" + date(session.lastSeenAt) + ")"));
            }
            rows.add(row(messages.plain("admin.field-sessions"),
                    (account.sessions == null ? 0 : account.sessions.size())
                            + (ips.isEmpty() ? "" : " · " + String.join(", ", ips))));
            RegistrationStore.MuteEntry mute = account.mute;
            rows.add(row(messages.plain("admin.field-mute"),
                    mute != null && mute.until > now
                            ? mute.reason + " · " + date(mute.until)
                            : messages.plain("admin.value-none")));
            rows.add(row(messages.plain("admin.field-entitlements"),
                    account.entitlements == null ? 0 : account.entitlements.size()));
        }
        rows.add(row(messages.plain("admin.field-bank"),
                BankService.format(plugin.bankStore().balanceOf(name))));
        UUID bankId = plugin.bankStore().idByName(name);
        if (bankId != null) {
            rows.add(row(messages.plain("admin.field-transfers"),
                    messages.plain("admin.value-sent") + " "
                            + BankService.format(plugin.bankStore().sentOf(bankId))
                            + " · " + messages.plain("admin.value-received") + " "
                            + BankService.format(plugin.bankStore().receivedOf(bankId))));
        }
        rows.add(row(messages.plain("admin.field-warns"),
                service.warnText(name) + " · " + messages.plain("admin.value-history") + " "
                        + service.ledger().warnHistory(name).size()));
        rows.add(row(messages.plain("admin.field-ban"),
                service.isBanned(name) ? service.banReason(name) : messages.plain("admin.value-no")));
        rows.add(row(messages.plain("admin.field-moderator"),
                service.ledger().isModerator(name)
                        ? messages.plain("admin.value-yes") : messages.plain("admin.value-no")));

        // Разделы аддонов (клан, дома, приваты, профессии) приходят через ProfileProvider API.
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (RuntimeException ignored) {
            id = UUID.nameUUIDFromBytes(name.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var context = new ChrdkRegisterApi.ProfileContext(id, name, online != null, false);
        for (ChrdkRegisterApi.ProfileSection section : plugin.profileRegistry().sections(context)) {
            rows.add(row(messages.plain("admin.field-addon"), Messages.trusted(section.title())));
            rows.addAll(section.lines());
        }
        return rows;
    }

    private Component row(String label, Object value) {
        return plugin.messages().get("admin.field-row", "label", label, "value", value);
    }

    /* -------------------------------- клики -------------------------------- */

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)
                || !holder.owner.equals(viewer.getUniqueId())) return;
        if (!service.isStaff(viewer)) {
            viewer.closeInventory();
            plugin.messages().send(viewer, "admin.no-permission");
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == SLOT_CLOSE) { viewer.closeInventory(); return; }
        if (slot == SLOT_PLAYERS) { open(viewer, Page.PLAYERS, "", holder.index, holder.filter); return; }
        if (slot == SLOT_MODERATORS) { open(viewer, Page.MODERATORS, "", 0, holder.filter); return; }
        if (slot == SLOT_JOURNAL) { open(viewer, Page.JOURNAL, "", 0, holder.filter); return; }
        if (slot == SLOT_REFRESH) { paint(viewer, holder); return; }
        if (slot == SLOT_BACK) { open(viewer, Page.PLAYERS, "", 0, holder.filter); return; }
        if (slot == SLOT_FILTER) { startInput(viewer, Input.FILTER, holder); return; }
        if (slot == SLOT_PREV) {
            if (holder.index > 0) { holder.index--; paint(viewer, holder); }
            return;
        }
        if (slot == SLOT_NEXT) {
            holder.index++;
            paint(viewer, holder);
            return;
        }
        if (slot == SLOT_WARN && holder.page == Page.PROFILE) {
            startInput(viewer, Input.WARN, holder);
            return;
        }
        if (slot == SLOT_BAN && holder.page == Page.PROFILE) {
            if (service.isBanned(holder.target)) {
                service.unban(viewer, holder.target);
                paint(viewer, holder);
            } else {
                startInput(viewer, Input.BAN, holder);
            }
            return;
        }
        if (slot == SLOT_ROLE && holder.page == Page.PROFILE) {
            if (service.ledger().isModerator(holder.target)) {
                service.removeModerator(viewer, holder.target);
            } else {
                service.addModerator(viewer, holder.target);
            }
            paint(viewer, holder);
            return;
        }
        if (slot == 49 && holder.page == Page.MODERATORS) {
            startInput(viewer, Input.MODERATOR_ADD, holder);
            return;
        }

        int entry = entryIndex(slot);
        if (entry < 0) return;
        int offset = holder.index * ENTRY_SLOTS.length + entry;
        switch (holder.page) {
            case PLAYERS -> {
                List<String> visible = filter(players(), holder.filter);
                if (offset < visible.size()) open(viewer, Page.PROFILE, visible.get(offset));
            }
            case PROFILE -> {
                List<ModerationLedger.Warn> history = service.ledger().warnHistory(holder.target);
                if (offset < history.size()) {
                    ModerationLedger.Warn warn = history.get(offset);
                    if (warn.removedAt <= 0) service.removeWarn(viewer, warn.id);
                    paint(viewer, holder);
                }
            }
            case MODERATORS -> {
                List<ModerationLedger.Moderator> staff =
                        filterModerators(service.ledger().moderators(), holder.filter);
                if (offset < staff.size()) {
                    service.removeModerator(viewer, staff.get(offset).name);
                    paint(viewer, holder);
                }
            }
            case JOURNAL -> { /* запись журнала только для чтения */ }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private static int entryIndex(int slot) {
        for (int i = 0; i < ENTRY_SLOTS.length; i++) {
            if (ENTRY_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    /* -------------------------- ввод причины в чате ------------------------ */

    private void startInput(Player viewer, Input type, Holder holder) {
        pending.put(viewer.getUniqueId(),
                new Pending(type, holder.page, holder.target, holder.index, holder.filter));
        viewer.closeInventory();
        Messages messages = plugin.messages();
        switch (type) {
            case WARN -> messages.send(viewer, "admin.input-warn", "player", holder.target);
            case BAN -> messages.send(viewer, "admin.input-ban", "player", holder.target);
            case MODERATOR_ADD -> messages.send(viewer, "admin.input-moderator");
            case FILTER -> messages.send(viewer, "admin.input-filter");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (pending.get(player.getUniqueId()) == null) return;
        try {
            event.setCancelled(true);
        } catch (Throwable ignored) {
            // Не все сборки Paper позволяют отменять AsyncChatEvent: ввод всё равно обработаем.
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> applyInput(player, text));
    }

    private void applyInput(Player player, String raw) {
        Pending request = pending.remove(player.getUniqueId());
        if (request == null || !player.isOnline() || !service.isStaff(player)) return;
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
            plugin.messages().send(player, "admin.input-cancelled");
            reopen(player, request);
            return;
        }
        switch (request.type) {
            case WARN -> service.warn(player, request.target, text);
            case BAN -> service.ban(player, request.target, text);
            case MODERATOR_ADD -> service.addModerator(player, text);
            case FILTER -> { /* фильтр применяется при повторном открытии */ }
        }
        reopen(player, request.type == Input.FILTER ? request.withFilter(text) : request);
    }

    private void reopen(Player player, Pending request) {
        open(player, request.page(), request.target(), request.index(), request.filter());
    }

    /* ------------------------------- утилиты ------------------------------- */

    private List<String> players() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : plugin.store().accountNames()) {
            if (name != null && !name.isBlank()) names.add(name);
        }
        for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
        List<String> out = new ArrayList<>(names);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static List<String> filter(List<String> source, String filter) {
        if (filter == null || filter.isBlank()) return List.copyOf(source);
        String needle = filter.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : source) {
            if (value.toLowerCase(Locale.ROOT).contains(needle)) out.add(value);
        }
        return List.copyOf(out);
    }

    private static List<ModerationLedger.Moderator> filterModerators(
            List<ModerationLedger.Moderator> source, String filter) {
        if (filter == null || filter.isBlank()) return List.copyOf(source);
        String needle = filter.toLowerCase(Locale.ROOT);
        List<ModerationLedger.Moderator> out = new ArrayList<>();
        for (ModerationLedger.Moderator value : source) {
            if (value.name.contains(needle) || value.addedBy.contains(needle)) out.add(value);
        }
        return List.copyOf(out);
    }

    private static String date(long millis) {
        return millis <= 0 ? "—" : DATE.format(Instant.ofEpochMilli(millis));
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
