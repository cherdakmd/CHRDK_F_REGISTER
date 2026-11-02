package ru.cherdakmd.chrdkregister.api.exploration;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.*;
import java.util.function.*;

/** API37. Same rules, independent lifetime/season namespaces; no world edits or chunk loads. */
public final class ExplorationAtlas implements Listener, AutoCloseable {
    private final JavaPlugin owner;
    private final ExplorationStore store;
    private final Supplier<Messages> messages;
    private final Supplier<String> scope;
    private final Predicate<Player> access;
    private final BooleanSupplier tracking;
    private final Consumer<Player> back;
    private final List<String> registry;
    private record Activity(String scope, String biome, long nanos) { }
    private record WalkRemainder(String scope, String biome, double centimeters) { }
    private final Map<UUID, Activity> active = new HashMap<>();
    private final Map<UUID, WalkRemainder> walkRemainders = new HashMap<>();
    private final BukkitTask tickTask;
    private final BukkitTask saveTask;
    private boolean closed;
    private volatile String cachedScope;
    private volatile List<String> cachedCatalog = List.of();
    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final ExplorationAtlas atlas;
        final String scope;
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        boolean consumed;
        Inventory inventory;
        Holder(UUID owner, String scope, ExplorationAtlas atlas) { this.owner = owner; this.scope = scope; this.atlas = atlas; }
        @Override public Inventory getInventory() { return inventory; }
    }
    public ExplorationAtlas(JavaPlugin owner, ExplorationStore store, Supplier<Messages> messages,
                            Supplier<String> scope, Predicate<Player> access, BooleanSupplier tracking,
                            Consumer<Player> back) {
        this.owner = owner; this.store = store; this.messages = messages; this.scope = scope;
        this.access = access; this.tracking = tracking; this.back = back;
        List<String> keys = new ArrayList<>();
        Registry.BIOME.forEach(b -> { String k = b.getKey().toString(); if (ExplorationCatalog.required(k)) keys.add(k); });
        registry = keys.stream().sorted().toList();
        catalog();
        Bukkit.getPluginManager().registerEvents(this, owner);
        tickTask = Bukkit.getScheduler().runTaskTimer(owner, this::tick, 20, 20);
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(owner, store::save, 100, 600);
    }
    public List<String> catalog() {
        String current = scope.get();
        if (!current.equals(cachedScope)) { cachedCatalog = store.catalog(current, registry); cachedScope = current; }
        return cachedCatalog;
    }
    private boolean permitted(Player p) {
        return !closed && store.healthy() && p != null && p.isOnline() && !p.isDead()
                && !p.hasMetadata("NPC") && access.test(p);
    }
    private boolean honest(Player p) {
        return permitted(p) && tracking.getAsBoolean() && p.getGameMode() != GameMode.CREATIVE
                && p.getGameMode() != GameMode.SPECTATOR && !p.isFlying() && !p.isGliding()
                && !p.isInsideVehicle() && !p.isRiptiding();
    }
    private String biome(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        String key = loc.getBlock().getBiome().getKey().toString();
        return catalog().contains(key) ? key : null;
    }
    private boolean visit(Player p, String key) {
        if (key == null) return false;
        boolean fresh = store.visit(scope.get(), p.getUniqueId(), key);
        if (fresh) {
            p.sendActionBar(messages.get().get("atlas-discovered", "biome", Messages.trusted(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(biomeName(key)))));
            trophies(p);
        }
        return true;
    }
    private static String coordinate(Location l, boolean chunk) {
        long position = chunk ? ((long) (l.getBlockX() >> 4) << 32) ^ ((l.getBlockZ() >> 4) & 0xffffffffL)
                : (((long) l.getBlockX() & 0x3ffffffL) << 38) | (((long) l.getBlockZ() & 0x3ffffffL) << 12) | (l.getBlockY() & 0xfffL);
        UUID world = l.getWorld().getUID();
        return Long.toUnsignedString(position ^ Long.rotateLeft(world.getMostSignificantBits(), 13) ^ world.getLeastSignificantBits(), 36);
    }
    private void add(Player p, Location loc, ExplorationCatalog.Kind kind, String material, long amount, String unique) {
        if (!honest(p)) return;
        String key = biome(loc); if (!visit(p, key)) return;
        for (var task : ExplorationCatalog.tasks(key)) {
            if (task.kind() != kind || !task.matches().isEmpty() && !task.accepts(material)) continue;
            if (store.add(scope.get(), p.getUniqueId(), key, task, amount, unique)) {
                p.sendActionBar(messages.get().get("atlas-task-done", "biome", Messages.trusted(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(biomeName(key))),
                        "task", Messages.trusted(messages.get().raw(task.textKey()))));
                if (store.done(scope.get(), p.getUniqueId(), key) == 5)
                    messages.get().send(p, "atlas-biome-done", "biome", Messages.trusted(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(biomeName(key))));
                trophies(p);
            }
        }
    }
    private void trophies(Player p) {
        List<String> all = catalog(); if (all.isEmpty()) return;
        String s = scope.get(); UUID id = p.getUniqueId();
        if (all.stream().allMatch(b -> store.visited(s, id, b)) && store.awardTrophy(s, id, "visited"))
            messages.get().send(p, "atlas-all-visited");
        if (all.stream().allMatch(b -> store.done(s, id, b) == 5) && store.awardTrophy(s, id, "master"))
            messages.get().send(p, "atlas-all-done");
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (e instanceof PlayerTeleportEvent || !honest(p) || e.getTo() == null
                || e.getFrom().getWorld() != e.getTo().getWorld()) return;
        double distance = e.getFrom().distance(e.getTo());
        if (!Double.isFinite(distance) || distance < 0.01 || distance > 8) return;
        String from = biome(e.getFrom()), to = biome(e.getTo());
        if (to == null) return;
        active.put(p.getUniqueId(), new Activity(scope.get(), to, System.nanoTime()));
        add(p, e.getTo(), ExplorationCatalog.Kind.CHUNKS, "", 1, coordinate(e.getTo(), true));
        // Boundary hops cannot charge the full segment to both neighbouring biomes.
        if (to.equals(from) && (p.isOnGround() || p.isSwimming() || p.isInWater())) {
            double dx = e.getTo().getX() - e.getFrom().getX(), dz = e.getTo().getZ() - e.getFrom().getZ();
            WalkRemainder previous = walkRemainders.get(p.getUniqueId());
            double centimeters = Math.sqrt(dx * dx + dz * dz) * 100;
            if (previous != null && previous.scope().equals(scope.get()) && previous.biome().equals(to)) centimeters += previous.centimeters();
            long whole = (long) centimeters;
            walkRemainders.put(p.getUniqueId(), new WalkRemainder(scope.get(), to, centimeters - whole));
            add(p, e.getTo(), ExplorationCatalog.Kind.WALK, "", whole, null);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mine(BlockBreakEvent e) {
        Block b = e.getBlock();
        add(e.getPlayer(), b.getLocation(), ExplorationCatalog.Kind.MINE, b.getType().getKey().getKey(), 1, coordinate(b.getLocation(), false));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player || e.getEntity().hasMetadata("NPC")) return;
        Player p = e.getEntity().getKiller();
        if (p != null) add(p, e.getEntity().getLocation(), ExplorationCatalog.Kind.KILL,
                e.getEntity().getType().getKey().getKey(), 1, null);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breed(EntityBreedEvent e) {
        if (e.getBreeder() instanceof Player p) add(p, e.getEntity().getLocation(), ExplorationCatalog.Kind.BREED,
                e.getEntity().getType().getKey().getKey(), 1, null);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH && e.getCaught() instanceof Item item)
            add(e.getPlayer(), e.getHook().getLocation(), ExplorationCatalog.Kind.FISH, item.getItemStack().getType().getKey().getKey(), 1, null);
    }
    @EventHandler public void quit(PlayerQuitEvent e) { active.remove(e.getPlayer().getUniqueId()); walkRemainders.remove(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) { active.remove(e.getPlayer().getUniqueId()); walkRemainders.remove(e.getPlayer().getUniqueId()); }
    /** Read-only observation: target must actually be visible and within five blocks. */
    public void study(Player p) { study(p, null); }
    private void study(Player p, String expectedBiome) {
        if (!honest(p)) { messages.get().send(p, "atlas-readonly"); return; }
        Block b = p.getTargetBlockExact(5);
        if (b == null || b.getType().isAir()) { messages.get().send(p, "atlas-no-target"); return; }
        String key = biome(b.getLocation());
        if (key == null || expectedBiome != null && !expectedBiome.equals(key) || ExplorationCatalog.tasks(key).stream().noneMatch(t -> t.kind() == ExplorationCatalog.Kind.STUDY && t.accepts(b.getType().getKey().getKey()))) {
            messages.get().send(p, "atlas-no-target"); return;
        }
        add(p, b.getLocation(), ExplorationCatalog.Kind.STUDY, b.getType().getKey().getKey(), 1, coordinate(b.getLocation(), false));
        messages.get().send(p, "atlas-observed");
    }
    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!honest(p)) { active.remove(p.getUniqueId()); continue; }
            String key = biome(p.getLocation()); visit(p, key);
            Activity last = active.get(p.getUniqueId());
            if (last != null && last.scope().equals(scope.get()) && Objects.equals(last.biome(), key) && System.nanoTime() - last.nanos() <= 10_000_000_000L)
                add(p, p.getLocation(), ExplorationCatalog.Kind.ACTIVE, "", 1, null);
        }
    }
    public int visited(UUID id) { String s = scope.get(); return (int) catalog().stream().filter(b -> store.visited(s, id, b)).count(); }
    public int completed(UUID id) { String s = scope.get(); return catalog().stream().mapToInt(b -> store.done(s, id, b)).sum(); }
    private Component biomeName(String key) { return Component.translatable("biome." + key.replace(':', '.')); }
    private Holder window(Player p) {
        if (!permitted(p)) { messages.get().send(p, "atlas-unavailable"); return null; }
        Holder h = new Holder(p.getUniqueId(), scope.get(), this);
        h.inventory = Bukkit.createInventory(h, 54, messages.get().get(scope.get().equals("lifetime") ? "atlas-title-lifetime" : "atlas-title-season")); return h;
    }
    private void item(Holder h, int slot, Material mat, Component title, List<Component> lore, Consumer<Player> action) {
        var item = new ItemStack(mat); var meta = item.getItemMeta(); meta.displayName(title); meta.lore(lore); item.setItemMeta(meta);
        h.inventory.setItem(slot, item); if (action != null) h.actions.put(slot, action);
    }
    private void controls(Holder h) {
        item(h, 48, Material.ARROW, messages.get().get("atlas-back"), List.of(), back);
        item(h, 49, Material.OAK_DOOR, messages.get().get("atlas-close"), List.of(), Player::closeInventory);
    }
    public void open(Player p, int requested) {
        Holder h = window(p); if (h == null) return;
        var all = catalog(); int pages = Math.max(1, (all.size() + 44) / 45), page = Math.clamp(requested, 0, pages - 1);
        for (int i = 0; i < 45 && page * 45 + i < all.size(); i++) {
            String key = all.get(page * 45 + i); int done = store.done(h.scope, h.owner, key);
            item(h, i, done == 5 ? Material.EMERALD : store.visited(h.scope, h.owner, key) ? Material.MAP : Material.GRAY_DYE,
                    biomeName(key), messages.get().getList("atlas-biome-lore", "done", done,
                            "visit", messages.get().plain(store.visited(h.scope, h.owner, key) ? "atlas-yes" : "atlas-no")), x -> detail(x, key, page));
        }
        item(h, 46, Material.COMPASS, messages.get().get("atlas-summary"), messages.get().getList("atlas-summary-lore",
                "visited", visited(h.owner), "total", all.size(), "done", completed(h.owner), "tasks", all.size() * 5), null);
        if (page > 0) item(h, 45, Material.ARROW, messages.get().get("atlas-prev"), List.of(), x -> open(x, page - 1));
        if (page + 1 < pages) item(h, 53, Material.ARROW, messages.get().get("atlas-next"), List.of(), x -> open(x, page + 1));
        controls(h); p.openInventory(h.inventory);
    }
    private void detail(Player p, String biome, int page) {
        Holder h = window(p); if (h == null || !catalog().contains(biome)) return;
        item(h, 4, Material.MAP, biomeName(biome), messages.get().getList("atlas-biome-lore", "done", store.done(h.scope, h.owner, biome),
                "visit", messages.get().plain(store.visited(h.scope, h.owner, biome) ? "atlas-yes" : "atlas-no")), null);
        int[] slots = {19, 21, 23, 30, 32}; int i = 0;
        for (var t : ExplorationCatalog.tasks(biome)) {
            long progress = store.progress(h.scope, h.owner, biome, t.id());
            List<Component> lore = new ArrayList<>(messages.get().getList("atlas-task-lore", "progress", t.kind() == ExplorationCatalog.Kind.WALK ? progress / 100 : progress,
                    "target", t.kind() == ExplorationCatalog.Kind.WALK ? t.target() / 100 : t.target()));
            if (t.kind() == ExplorationCatalog.Kind.STUDY) lore.addAll(messages.get().getList("atlas-study-help"));
            for (String target : new TreeSet<>(t.matches())) {
                if (target.equals("*")) { lore.add(messages.get().get("atlas-any-block")); continue; }
                Material m = Material.matchMaterial(target);
                lore.add(m != null ? Component.translatable(m.getTranslationKey()) : Component.translatable("entity.minecraft." + target));
            }
            item(h, slots[i++], progress >= t.target() ? Material.LIME_DYE : Material.BOOK, messages.get().get(t.textKey()), lore, null);
        }
        item(h, 40, Material.SPYGLASS, messages.get().get("atlas-study-button"), messages.get().getList("atlas-study-help"), x -> { study(x, biome); detail(x, biome, page); });
        controls(h); item(h, 48, Material.ARROW, messages.get().get("atlas-back"), List.of(), x -> open(x, page));
        p.openInventory(h.inventory);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h) || h.atlas != this) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || h.consumed || !permitted(p)) return;
        if (!h.scope.equals(scope.get())) { p.closeInventory(); return; }
        Consumer<Player> action = h.actions.get(e.getRawSlot());
        if (action == null || e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) return;
        h.consumed = true;
        Bukkit.getScheduler().runTask(owner, () -> {
            if (permitted(p) && h.scope.equals(scope.get()) && p.getOpenInventory().getTopInventory().getHolder() == h) action.accept(p);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Holder h && h.atlas == this) e.setCancelled(true); }
    public void closeWindows() {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h && h.atlas == this && h.inventory.equals(p.getOpenInventory().getTopInventory())) p.closeInventory();
    }
    @Override public void close() {
        closed = true; tickTask.cancel(); saveTask.cancel(); HandlerList.unregisterAll(this); closeWindows(); active.clear(); walkRemainders.clear(); store.save();
    }
}
