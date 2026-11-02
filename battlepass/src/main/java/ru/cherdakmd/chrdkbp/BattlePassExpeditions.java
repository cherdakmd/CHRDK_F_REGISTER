package ru.cherdakmd.chrdkbp;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.exploration.ExplorationStore;

/** UUID-owned permanent licenses. Never writes rankLevel, entitlements, homes, currency or PvP power. */
final class BattlePassExpeditions implements Listener, AutoCloseable {
    private final BattlePassPlugin plugin;
    private final ExplorationStore store;
    private final Map<UUID, Navigation> navigation = new HashMap<>();
    private final Map<String, UUID> onlineNames = new ConcurrentHashMap<>();
    private final BukkitTask pulse;
    private long ticks;
    private static final class Navigation {
        final List<String> marks;
        final boolean route;
        int index;
        Navigation(List<String> marks, boolean route) { this.marks = List.copyOf(marks); this.route = route; }
    }
    private static final class Holder implements InventoryHolder {
        final UUID owner;
        final BattlePassConfig.Season season;
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        boolean consumed;
        Inventory inventory;
        Holder(Player p, BattlePassConfig.Season season) { owner = p.getUniqueId(); this.season = season; }
        @Override public Inventory getInventory() { return inventory; }
    }
    BattlePassExpeditions(BattlePassPlugin plugin, ExplorationStore store) {
        this.plugin = plugin; this.store = store;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) remember(p);
        plugin.coreApi().registerNameTag("bp-expeditions", (name, decorated) -> {
            if (name == null || decorated == null) return decorated;
            UUID id = onlineNames.get(name.toLowerCase(Locale.ROOT));
            String title = id == null ? "off" : store.title(id);
            return title.equals("off") ? decorated : decorated + " " + plugin.messages().raw("exp-title-" + title);
        });
        plugin.coreApi().registerProfile("bp-expeditions", c -> {
            if (c == null) return null;
            int level = store.license(c.playerId()); if (level == 0) return null;
            var lines = new ArrayList<net.kyori.adventure.text.Component>();
            lines.add(plugin.messages().get("exp-owned-free"));
            if (level == 2) lines.add(plugin.messages().get("exp-owned-premium"));
            lines.add(plugin.messages().get("exp-profile-limits", "marks", level == 2 ? 24 : 8, "routes", level == 2 ? 5 : 1));
            return new ChrdkRegisterApi.ProfileSection(plugin.messages().raw("exp-profile-title"), Material.COMPASS, lines);
        });
        pulse = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }
    private void remember(Player p) { onlineNames.put(p.getName().toLowerCase(Locale.ROOT), p.getUniqueId()); }
    @EventHandler public void join(PlayerJoinEvent e) { remember(e.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        onlineNames.remove(e.getPlayer().getName().toLowerCase(Locale.ROOT), e.getPlayer().getUniqueId());
        navigation.remove(e.getPlayer().getUniqueId());
    }
    private boolean access(Player p) { return plugin.canUse(p) && store.healthy(); }
    boolean eligible(Player p) {
        return access(p) && BattlePassExpeditionRules.complete(plugin.season(), plugin.store().progressMap(p.getUniqueId()), System.currentTimeMillis());
    }
    void claim(Player p, boolean explain) {
        if (!access(p)) { if (explain) plugin.messages().send(p, "exp-unavailable"); return; }
        if (!eligible(p)) { if (explain) plugin.messages().send(p, "exp-not-ready"); return; }
        UUID id = p.getUniqueId(); int before = store.license(id);
        int target = plugin.premiumActive(p) && BattlePassPremium.anyRewards(plugin.season()) ? 2 : 1;
        if (before >= target) { if (explain) plugin.messages().send(p, before == 2 ? "exp-already" : "exp-premium-locked"); return; }
        // The final reward is independent of the 24h boost bank and previous boost-claim ACKs.
        if (!store.award(id, target)) { plugin.messages().send(p, "exp-unavailable"); return; }
        plugin.messages().send(p, target == 2 ? "exp-awarded-premium" : "exp-awarded-free");
        plugin.getLogger().info("Battlepass earned expedition license: uuid=" + id + " level=" + target + " season=" + plugin.season().startEpochMillis());
    }
    void command(Player p, String[] args) {
        if (!access(p)) { plugin.messages().send(p, "exp-unavailable"); return; }
        if (args.length == 1) { open(p); return; }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("claim")) { claim(p, true); return; }
        if (store.license(p.getUniqueId()) == 0) { plugin.messages().send(p, "exp-not-owned"); return; }
        UUID id = p.getUniqueId(); boolean success;
        switch (sub) {
            case "mark" -> {
                if (args.length != 3) break;
                Location l = p.getLocation();
                if (!l.getWorld().getWorldBorder().isInside(l)) { plugin.messages().send(p, "exp-invalid"); return; }
                success = store.mark(id, args[2], new ExplorationStore.Waypoint(l.getWorld().getUID().toString(), l.getX(), l.getY(), l.getZ()));
                result(p, success); return;
            }
            case "unmark" -> { if (args.length == 3) { result(p, store.removeMark(id, args[2])); return; } }
            case "route" -> {
                if (args.length >= 4) { result(p, store.route(id, args[2], Arrays.asList(args).subList(3, args.length))); return; }
            }
            case "unroute" -> { if (args.length == 3) { result(p, store.removeRoute(id, args[2])); return; } }
            case "go", "follow" -> {
                if (args.length != 3) break;
                List<String> marks = sub.equals("go") ? store.marks(id).containsKey(args[2]) ? List.of(args[2]) : null : store.routes(id).get(args[2]);
                if (marks == null || marks.isEmpty()) { plugin.messages().send(p, "exp-invalid"); return; }
                navigation.put(id, new Navigation(marks, sub.equals("follow"))); plugin.messages().send(p, "exp-navigation-started"); return;
            }
            case "next" -> {
                Navigation n = navigation.get(id);
                if (n == null) { plugin.messages().send(p, "exp-invalid"); return; }
                if (++n.index >= n.marks.size()) navigation.remove(id);
                plugin.messages().send(p, "exp-navigation-next"); return;
            }
            case "stop" -> { navigation.remove(id); plugin.messages().send(p, "exp-navigation-stopped"); return; }
            case "title" -> { if (args.length == 3) { result(p, store.setTitle(id, args[2].toLowerCase(Locale.ROOT))); return; } }
            case "effect" -> { result(p, store.toggleEffect(id)); return; }
            case "list" -> { list(p); return; }
            default -> { }
        }
        plugin.messages().sendList(p, "exp-help");
    }
    private void result(Player p, boolean success) { plugin.messages().send(p, success ? "exp-saved" : "exp-invalid"); }
    private void list(Player p) {
        plugin.messages().send(p, "exp-journal");
        new TreeMap<>(store.marks(p.getUniqueId())).forEach((name, w) -> {
            World world = Bukkit.getWorld(UUID.fromString(w.world()));
            plugin.messages().send(p, "exp-mark-line", "name", name, "world", world == null ? plugin.messages().plain("exp-world-offline") : world.getName(),
                    "x", (int) w.x(), "y", (int) w.y(), "z", (int) w.z());
        });
        new TreeMap<>(store.routes(p.getUniqueId())).forEach((name, marks) -> plugin.messages().send(p, "exp-route-line", "name", name, "marks", String.join(" → ", marks)));
        plugin.messages().sendList(p, "exp-help");
    }
    void open(Player p) {
        if (!access(p)) { plugin.messages().send(p, "exp-unavailable"); return; }
        Holder h = new Holder(p, plugin.season()); h.inventory = Bukkit.createInventory(h, 54, plugin.messages().get("exp-menu"));
        int level = store.license(p.getUniqueId());
        item(h, 11, Material.COMPASS, "exp-title-free", "exp-free-lore", x -> { claim(x, true); open(x); }, "owned", plugin.messages().plain(level >= 1 ? "atlas-yes" : "atlas-no"));
        item(h, 15, Material.NETHER_STAR, "exp-title-premium", "exp-premium-lore", x -> { claim(x, true); open(x); }, "owned", plugin.messages().plain(level == 2 ? "atlas-yes" : "atlas-no"));
        item(h, 22, Material.WRITABLE_BOOK, "exp-journal", "exp-journal-lore", x -> { x.closeInventory(); list(x); });
        item(h, 30, Material.NAME_TAG, "exp-title-button", "exp-title-lore", x -> {
            String old = store.title(x.getUniqueId());
            String next = old.equals("off") ? "free" : old.equals("free") && store.license(x.getUniqueId()) == 2 ? "premium" : "off";
            result(x, store.setTitle(x.getUniqueId(), next)); open(x);
        });
        item(h, 32, Material.GLOWSTONE_DUST, "exp-effect", "exp-effect-lore", x -> { result(x, store.toggleEffect(x.getUniqueId())); open(x); },
                "state", plugin.messages().plain(store.effect(p.getUniqueId()) ? "atlas-yes" : "atlas-no"));
        item(h, 40, Material.ENDER_CHEST, "exp-claim", "exp-condition", x -> { claim(x, true); open(x); });
        item(h, 45, Material.ARROW, "guide-back", null, x -> plugin.guide().home(x));
        item(h, 49, Material.OAK_DOOR, "menu-close", null, Player::closeInventory);
        p.openInventory(h.inventory);
    }
    private void item(Holder h, int slot, Material mat, String name, String lore, Consumer<Player> action, Object... params) {
        ItemStack item = new ItemStack(mat); var meta = item.getItemMeta(); meta.displayName(plugin.messages().get(name, params));
        if (lore != null) meta.lore(plugin.messages().getList(lore, params)); item.setItemMeta(meta); h.inventory.setItem(slot, item); h.actions.put(slot, action);
    }
    private void tick() {
        ticks++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!access(p) || store.license(id) == 0) { navigation.remove(id); continue; }
            Navigation n = navigation.get(id);
            if (n != null) navigate(p, n);
            if (ticks % 2 == 0 && store.effect(id) && !p.isInvisible() && p.getGameMode() != GameMode.SPECTATOR) {
                Color color = store.license(id) == 2 ? Color.fromRGB(255, 190, 50) : Color.fromRGB(60, 220, 190);
                // Owner-only: no vanish leaks, no particles around other players, no potion effects.
                for (int i = 0; i < 8; i++) {
                    double angle = ticks * 0.3 + i * Math.PI / 4;
                    p.spawnParticle(Particle.DUST, p.getLocation().add(Math.cos(angle) * 0.5, 0.2 + i * 0.08, Math.sin(angle) * 0.5), 1,
                            new Particle.DustOptions(color, 0.8f));
                }
            }
        }
    }
    private void navigate(Player p, Navigation n) {
        var point = store.marks(p.getUniqueId()).get(n.marks.get(n.index));
        if (point == null) { navigation.remove(p.getUniqueId()); return; }
        if (!p.getWorld().getUID().toString().equals(point.world())) {
            p.sendActionBar(plugin.messages().get("exp-other-world")); return;
        }
        Location l = p.getLocation(); double dx = point.x() - l.getX(), dz = point.z() - l.getZ(), dy = point.y() - l.getY();
        double distance = Math.sqrt(dx * dx + dz * dz + dy * dy);
        if (distance <= 4) {
            if (n.index == n.marks.size() - 1) { navigation.remove(p.getUniqueId()); plugin.messages().send(p, "exp-arrived"); return; }
            if (store.license(p.getUniqueId()) == 2 && n.route) { n.index++; navigate(p, n); return; }
            else { p.sendActionBar(plugin.messages().get("exp-manual-next")); return; }
        }
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int index = Math.floorMod((int) Math.round((Math.toDegrees(Math.atan2(-dx, dz)) - l.getYaw()) / 45), 8);
        p.sendActionBar(plugin.messages().get("exp-direction", "arrow", arrows[index], "name", n.marks.get(n.index), "distance", (long) Math.ceil(distance)));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || h.consumed || !access(p)) return;
        if (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT) return;
        Consumer<Player> action = h.actions.get(e.getRawSlot()); if (action == null) return; h.consumed = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (access(p) && p.getOpenInventory().getTopInventory().getHolder() == h) {
                if (h.season != plugin.season()) { p.closeInventory(); return; } action.accept(p);
            }
        });
    }
    @EventHandler public void drag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true); }
    void closeWindows() { for (Player p : Bukkit.getOnlinePlayers()) if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory(); }
    @Override public void close() {
        pulse.cancel(); closeWindows(); HandlerList.unregisterAll(this); navigation.clear(); onlineNames.clear();
        plugin.coreApi().unregisterNameTag("bp-expeditions"); plugin.coreApi().unregisterProfile("bp-expeditions"); store.save();
    }
}
