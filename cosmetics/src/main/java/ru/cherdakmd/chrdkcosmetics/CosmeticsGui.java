package ru.cherdakmd.chrdkcosmetics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
import ru.cherdakmd.chrdkregister.Messages;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Единая точка внешнего вида; никаких покупок/выдач и команд из клиентского lore. */
final class CosmeticsGui implements Listener {
    static final int PAGE_SIZE = 45;
    static final Set<String> LINKS = Set.of("menu", "labels", "labels color", "labels order", "labels preset", "hat", "pet");
    private enum Kind { TRAILS, HUB, PREV, NEXT, SELECT, OFF, JOIN, LINK, CLOSE }
    private record Action(Kind kind, String value) { Action(Kind kind) { this(kind, ""); } }
    private final CosmeticsPlugin plugin;
    private boolean stopped;
    private static final class Ticket { org.bukkit.scheduler.BukkitTask task; }
    private final Map<UUID, Ticket> pending = new HashMap<>();
    CosmeticsGui(CosmeticsPlugin plugin) { this.plugin = plugin; }
    static int pages(int size) { return Math.max(1, (Math.max(0, size) - 1) / PAGE_SIZE + 1); }
    static int page(int requested, int size) { return Math.max(0, Math.min(pages(size) - 1, requested)); }
    void open(Player player) { open(player, false, 0); }
    void trails(Player player) { open(player, true, 0); }
    private void open(Player player, boolean trails, int requested) {
        if (stopped || !plugin.eligible(player)) return;
        int page = page(requested, plugin.config().trails().size());
        Holder holder = new Holder(player.getUniqueId(), plugin.config(), trails, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, plugin.messages().get(trails ? "gui-trails-title" : "gui-title",
                "page", page + 1, "pages", pages(plugin.config().trails().size())));
        holder.inventory = inventory;
        for (int i = 0; i < 54; i++) inventory.setItem(i, tile(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of(), false));
        if (trails) paintTrails(player, holder); else paintHub(player, holder);
        player.openInventory(inventory);
    }
    private void paintHub(Player player, Holder holder) {
        var selected = CosmeticsDefs.find(plugin.config().trails(), plugin.store().selectionOf(player.getUniqueId()));
        put(holder, 4, Material.PLAYER_HEAD, "gui-summary", null, "name", selected == null
                ? plugin.messages().mini("gui-none") : Messages.trusted(selected.displayName()));
        put(holder, 10, Material.FIREWORK_ROCKET, "gui-trails", new Action(Kind.TRAILS));
        boolean pref = plugin.store().joinEnabled(player.getUniqueId());
        put(holder, 12, Material.TOTEM_OF_UNDYING, "gui-join", new Action(Kind.JOIN),
                "state", plugin.messages().mini(pref ? "gui-on" : "gui-off"),
                "access", plugin.messages().mini(plugin.joinUnlocked(player) ? "gui-access-yes" : "gui-access-no"),
                "rank", plugin.config().joinMinRank(), "product", plugin.config().joinEffect().product().isBlank() ? "—" : plugin.config().joinEffect().product());
        link(player, holder, 14, Material.CYAN_DYE, "gui-colors", "labels color", "chrdkregister.labels");
        link(player, holder, 16, Material.NAME_TAG, "gui-labels", "labels", "chrdkregister.labels");
        link(player, holder, 28, Material.COMPARATOR, "gui-slots", "labels order", "chrdkregister.labels");
        link(player, holder, 30, Material.WRITABLE_BOOK, "gui-presets", "labels preset", "chrdkregister.labels");
        put(holder, 32, Material.CARVED_PUMPKIN, "gui-hat", new Action(Kind.LINK, "hat"));
        if (Bukkit.getPluginManager().isPluginEnabled("CHRDK_Pets"))
            link(player, holder, 34, Material.BONE, "gui-pets", "pet", "chrdkpets.use");
        put(holder, 45, Material.ARROW, "gui-menu", new Action(Kind.LINK, "menu"));
        put(holder, 49, Material.BARRIER, "gui-close", new Action(Kind.CLOSE));
    }
    private void link(Player player, Holder holder, int slot, Material icon, String key, String command, String permission) {
        put(holder, slot, icon, key, new Action(Kind.LINK, command));
        if (!player.hasPermission(permission)) {
            var meta = holder.inventory.getItem(slot).getItemMeta();
            var lore = new java.util.ArrayList<>(meta.lore() == null ? List.<Component>of() : meta.lore());
            lore.add(plugin.messages().get("gui-link-locked")); meta.lore(lore); holder.inventory.getItem(slot).setItemMeta(meta);
        }
    }
    private void paintTrails(Player player, Holder holder) {
        List<CosmeticsDefs.TrailDef> defs = plugin.config().trails();
        String selected = plugin.store().selectionOf(player.getUniqueId());
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = holder.page * PAGE_SIZE + slot;
            if (index >= defs.size()) { holder.inventory.setItem(slot, null); continue; }
            var def = defs.get(index); boolean open = plugin.unlocked(player.getName(), def);
            boolean chosen = def.id().equals(selected); boolean valid = plugin.resolveParticle(def.particle()) != null;
            var lore = plugin.messages().getList("gui-trail-lore", "state", plugin.messages().mini(!valid ? "gui-unavailable"
                    : !open ? (chosen ? "gui-suspended" : "gui-access-no") : chosen ? "gui-selected" : "gui-access-yes"),
                    "rank", def.rank(), "product", def.product().isBlank() ? "—" : def.product(), "id", def.id());
            holder.inventory.setItem(slot, tile(open && valid ? icon(def.id()) : Material.GRAY_DYE,
                    plugin.messages().markup(def.displayName()), lore, chosen && open && valid));
            holder.actions.put(slot, new Action(Kind.SELECT, def.id()));
        }
        if (defs.isEmpty()) put(holder, 22, Material.PAPER, "gui-empty", null);
        if (holder.page > 0) put(holder, 45, Material.ARROW, "gui-prev", new Action(Kind.PREV));
        put(holder, 46, Material.COMPASS, "gui-back", new Action(Kind.HUB));
        put(holder, 48, Material.MILK_BUCKET, "gui-clear", new Action(Kind.OFF));
        put(holder, 49, Material.BOOK, "gui-trails-info", null, "count", defs.size());
        if (holder.page + 1 < pages(defs.size())) put(holder, 53, Material.ARROW, "gui-next", new Action(Kind.NEXT));
    }
    static Material icon(String id) {
        return switch (id) { case "flame" -> Material.BLAZE_POWDER; case "hearts" -> Material.POPPY;
            case "notes" -> Material.NOTE_BLOCK; case "sparkles" -> Material.END_ROD; case "magic" -> Material.AMETHYST_SHARD;
            case "gold" -> Material.GOLD_NUGGET; case "aurora" -> Material.NETHER_STAR; default -> Material.FIREWORK_STAR; };
    }
    private void put(Holder holder, int slot, Material icon, String key, Action action, Object... values) {
        holder.inventory.setItem(slot, tile(icon, plugin.messages().get(key, values), plugin.messages().has(key + "-lore") ? plugin.messages().getList(key + "-lore", values) : List.of(), false));
        if (action != null) holder.actions.put(slot, action);
    }
    private ItemStack tile(Material material, Component name, List<Component> lore, boolean glow) {
        ItemStack item = new ItemStack(material); var meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        meta.setEnchantmentGlintOverride(glow); item.setItemMeta(meta); return item;
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        boolean blocked = event.isCancelled(); event.setCancelled(true);
        if (blocked || !(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())
                || !plugin.eligible(player) || event.getRawSlot() < 0 || event.getRawSlot() >= 54) return;
        Action action = holder.actions.get(event.getRawSlot()); if (action == null) return;
        UUID id = player.getUniqueId();
        if (pending.containsKey(id) || pending.size() >= 4096) return;
        Ticket ticket = new Ticket(); pending.put(id, ticket);
        try {
        ticket.task = Bukkit.getScheduler().runTask(plugin, () -> {
            if (!pending.remove(id, ticket)) return;
            if (stopped || !plugin.eligible(player) || holder.snapshot != plugin.config()
                    || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            switch (action.kind) {
                case TRAILS -> trails(player);
                case HUB -> open(player);
                case PREV -> open(player, true, holder.page - 1);
                case NEXT -> open(player, true, holder.page + 1);
                case SELECT -> { plugin.select(player, action.value); open(player, true, holder.page); }
                case OFF -> { plugin.select(player, null); open(player, true, holder.page); }
                case JOIN -> {
                    boolean enable = !plugin.store().joinEnabled(player.getUniqueId());
                    if (enable && !plugin.joinUnlocked(player)) plugin.messages().send(player, "join-locked");
                    else plugin.store().joinEnabled(player.getUniqueId(), enable);
                    open(player);
                }
                case LINK -> {
                    if (LINKS.contains(action.value)) { player.closeInventory(); player.performCommand(action.value); }
                }
                case CLOSE -> player.closeInventory();
            }
        });
        } catch (RuntimeException | Error failure) { pending.remove(id, ticket); throw failure; }
    }
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event) {
        Ticket ticket = pending.remove(event.getPlayer().getUniqueId()); if (ticket != null && ticket.task != null) ticket.task.cancel();
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
    void closeAll() {
        var tickets = List.copyOf(pending.values()); pending.clear();
        for (Ticket ticket : tickets) if (ticket.task != null) ticket.task.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) player.closeInventory();
    }
    void stop() { stopped = true; closeAll(); }
    private static final class Holder implements InventoryHolder {
        final UUID owner; final CosmeticsPlugin.CosmeticsConfig snapshot; final boolean trails; final int page;
        final Map<Integer, Action> actions = new HashMap<>(); Inventory inventory;
        Holder(UUID owner, CosmeticsPlugin.CosmeticsConfig snapshot, boolean trails, int page) {
            this.owner = owner; this.snapshot = snapshot; this.trails = trails; this.page = page;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
