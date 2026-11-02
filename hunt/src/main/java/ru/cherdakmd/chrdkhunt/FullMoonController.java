package ru.cherdakmd.chrdkhunt;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.MobRewardPolicy;

/** Ограниченное нашествие. Один общий clock; нет поиска путей, загрузки чанков и задач на каждого моба. */
final class FullMoonController implements Listener {
    private record Owned(Zombie mob, UUID owner, UUID token) { }
    private record ChunkRef(UUID world, int x, int z) { }
    private final HuntPlugin plugin;
    private final WildSafety safety;
    private final FullMoonLedger ledger;
    private final Map<UUID, FullMoonLedger.Night> nights = new HashMap<>();
    private final Map<UUID, Owned> mobs = new LinkedHashMap<>();
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "CHRDK-Hunt-moon-checkpoint"); thread.setDaemon(true); return thread;
    });
    private final Map<UUID, UUID> pendingWrites = new HashMap<>();
    private final Map<UUID, FullMoonLedger.Night> pendingPlans = new HashMap<>();
    private long generation;
    private boolean ioWarning, failed;
    private final Map<UUID, Long> activity = new HashMap<>();
    private final Deque<ChunkRef> bootstrap = new ArrayDeque<>();
    private final NamespacedKey ownerKey;
    private final BukkitTask clock;
    private FullMoonRules.Settings settings;
    private int cursor, pulse, overloaded;
    private long overloadSince;
    private volatile boolean stopped;

    FullMoonController(HuntPlugin plugin, WildSafety safety) {
        this.plugin = plugin; this.safety = safety;
        ownerKey = new NamespacedKey(plugin, "moon_owner");
        ledger = new FullMoonLedger(plugin.getDataFolder().toPath().resolve("full-moon.json"));
        if (!ledger.healthy()) plugin.getLogger().severe("Повреждён full-moon.json: полнолуние отключено, файл сохранён для восстановления.");
        reload();
        // Только одно начальное перечисление уже загруженных чанков. Их сущности проверяются по одному чанку за тик.
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) bootstrap.add(new ChunkRef(w.getUID(), c.getX(), c.getZ()));
        clock = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }
    void reload() {
        generation++;
        if (settings != null) endAll("moon-stopped");
        var c = plugin.getConfig(); String p = "full-moon.";
        settings = FullMoonRules.Settings.bounded(c.getBoolean(p + "enabled", true), c.getDouble(p + "chance-percent", 25),
                c.getInt(p + "global-cap", 64), c.getInt(p + "per-player-cap", 8), c.getInt(p + "nearby-cap", 16),
                c.getInt(p + "position-attempts", 4), c.getInt(p + "spawn-budget", 2), c.getInt(p + "cleanup-budget", 8),
                c.getInt(p + "min-radius", 24), c.getInt(p + "max-radius", 36), c.getInt(p + "wave-seconds", 5),
                c.getInt(p + "max-seconds", 720), c.getInt(p + "spawn-protection-radius", 128), c.getDouble(p + "max-mspt", 40));
    }
    private boolean worldAllowed(World w) {
        var worlds = plugin.getConfig().getStringList("full-moon.worlds");
        return w.getEnvironment() == World.Environment.NORMAL && w.getDifficulty() != Difficulty.PEACEFUL
                && !plugin.config().worldDisabled(w.getName()) && (worlds.isEmpty() || worlds.contains(w.getName()))
                && Boolean.TRUE.equals(w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE))
                && Boolean.TRUE.equals(w.getGameRuleValue(GameRule.DO_MOB_SPAWNING));
    }
    private boolean active(Player p, long now) {
        Long last = p == null ? null : activity.get(p.getUniqueId());
        return last != null && now >= last && now - last < 90_000 && !p.isInvisible() && safety.target(p, settings.spawnProtection());
    }
    private boolean running(World w) {
        var n = nights.get(w.getUID());
        return settings.enabled() && plugin.config().enabled() && !failed && ledger.healthy() && safety.available() && worldAllowed(w) && n != null && n.selected() && !n.finished() && n.day() == FullMoonRules.day(w.getFullTime())
                && FullMoonRules.blocksSleep(FullMoonRules.phase(w.getFullTime()));
    }
    private void tell(World w, String key) {
        for (Player p : w.getPlayers()) if (safety.player(p)) plugin.messages().send(p, key);
    }
    private void finish(World w, String message) {
        var old = nights.remove(w.getUID());
        if (old == null) {
            var unpublished = pendingPlans.get(w.getUID());
            if (unpublished != null && unpublished.selected() && !unpublished.finished()) checkpoint(w.getUID(), unpublished.finish(), () -> { });
            return;
        }
        var planned = pendingPlans.get(w.getUID());
        if (planned == null || !planned.token().equals(old.token())) planned = old;
        checkpoint(w.getUID(), planned.finish(), () -> { });
        tell(w, message);
        // Сразу прекращаем бой. Удаление распределено по следующим тикам.
        for (Owned owned : mobs.values()) if (owned.token().equals(old.token()) && owned.mob().isValid()) {
            owned.mob().setTarget(null); owned.mob().setAI(false); owned.mob().setInvulnerable(true);
        }
    }
    private void endAll(String message) {
        // В том числе решения, чей fsync ещё не вернулся: disable/unload/overload не должны
        // позволить позднему callback опубликовать уже отменённое предупреждение.
        for (var entry : Map.copyOf(pendingPlans).entrySet()) if (!nights.containsKey(entry.getKey()) && entry.getValue().selected() && !entry.getValue().finished()) checkpoint(entry.getKey(), entry.getValue().finish(), () -> { });
        for (UUID id : List.copyOf(nights.keySet())) {
            World w = Bukkit.getWorld(id);
            if (w != null) finish(w, message); else { var n = nights.remove(id); checkpoint(id, n.finish(), () -> { }); }
        }
    }
    /** Единственный IO-worker. Main thread не ждёт fsync; действие разрешено только ПОСЛЕ checkpoint.
     * Ticket+generation не позволяют позднему callback оживить завершённую/перезагруженную ночь. */
    private void checkpoint(UUID world, FullMoonLedger.Night n, Runnable committed) {
        if (failed || !ledger.healthy()) return;
        UUID ticket = UUID.randomUUID(); long expected = generation;
        pendingWrites.put(world, ticket); pendingPlans.put(world, n);
        io.execute(() -> {
            boolean ok = ledger.put(world, n);
            if (stopped || !plugin.isEnabled()) return;
            try { Bukkit.getScheduler().runTask(plugin, () -> {
                if (!ticket.equals(pendingWrites.get(world))) return;
                pendingWrites.remove(world); pendingPlans.remove(world);
                if (!ok) {
                    failed = true;
                    if (!ioWarning) { ioWarning = true; plugin.getLogger().severe("Не удалось записать календарь полнолуния: событие отключено до рестарта. Исходный файл не обнуляется."); }
                    endAll("moon-stopped"); return;
                }
                if (!stopped && expected == generation) committed.run();
            }); } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* disable уже очистил своих мобов */ }
        });
    }
    private void tick() {
        if (stopped) return;
        // Удаляем оставшиеся после аварии/старой версии метки, а не всех зомби/враждебных мобов.
        if (!bootstrap.isEmpty()) {
            var ref = bootstrap.removeFirst(); World w = Bukkit.getWorld(ref.world());
            if (w != null && w.isChunkLoaded(ref.x(), ref.z())) for (Entity e : w.getChunkAt(ref.x(), ref.z()).getEntities()) {
                if (MobRewardPolicy.temporary(e) && !mobs.containsKey(e.getUniqueId())) e.remove();
                else if (!MobRewardPolicy.temporary(e)) plugin.wildBiomes().loaded(e);
            }
        }
        if (++pulse % 10 != 0) return;
        long now = System.currentTimeMillis();
        if (!settings.enabled() || !plugin.config().enabled() || failed || !ledger.healthy() || !safety.available()) endAll("moon-stopped");
        if (!WildSafety.healthyTick(65)) {
            if (overloadSince == 0 || now < overloadSince) overloadSince = now;
            overloaded = now - overloadSince >= 15_000 ? 30 : 1;
        } else { overloaded = 0; overloadSince = 0; }
        if (overloaded >= 30) endAll("moon-overload");
        if (settings.enabled() && plugin.config().enabled() && !failed && ledger.healthy() && safety.available()) {
            for (World w : Bukkit.getWorlds()) updateNight(w, now);
        }
        clean(now);
        if (pulse % (settings.spawnEvery() * 20) == 0 && WildSafety.healthyTick(settings.msptLimit()) && !nights.isEmpty()) spawn(now);
        if (pulse >= 20_000_000) pulse = 0;
    }
    private void updateNight(World w, long now) {
        UUID id = w.getUID(); long day = FullMoonRules.day(w.getFullTime()), phase = FullMoonRules.phase(w.getFullTime());
        var runtime = nights.get(id);
        if (runtime != null && (runtime.day() != day || !FullMoonRules.blocksSleep(phase) || !worldAllowed(w)
                || FullMoonRules.expired(runtime.started(), now, settings.maxSeconds()) || overloaded >= 30)) {
            finish(w, overloaded >= 30 ? "moon-overload" : "moon-ended"); runtime = null;
        }
        if (runtime != null || !worldAllowed(w) || day < 0) {
            if (runtime != null && runtime.started() == 0 && FullMoonRules.night(phase) && !pendingWrites.containsKey(id)) start(w, runtime, now);
            return;
        }
        if (pendingWrites.containsKey(id)) return;
        var prior = ledger.get(id);
        if (prior != null && prior.day() >= day) {
            // После рестарта продолжается только уже выбранная ночь, с прежним wall-clock дедлайном.
            if (prior.day() == day && prior.selected() && !prior.finished() && FullMoonRules.blocksSleep(phase)
                    && !FullMoonRules.expired(prior.started(), now, settings.maxSeconds())) {
                nights.put(id, prior);
                if (prior.started() == 0 && FullMoonRules.warning(phase)) tell(w, "moon-warning");
                if (prior.started() == 0 && FullMoonRules.night(phase)) start(w, prior, now);
            }
            return;
        }
        if (phase < FullMoonRules.WARNING || !FullMoonRules.fullMoon(day)) return;
        boolean selected = FullMoonRules.fullMoon(day) && FullMoonRules.warning(phase)
                && overloaded == 0 && WildSafety.healthyTick(settings.msptLimit())
                && w.getPlayers().stream().anyMatch(p -> active(p, now))
                && ThreadLocalRandom.current().nextDouble(100) < settings.chance();
        var next = new FullMoonLedger.Night(day, selected, UUID.randomUUID(), 0, !selected);
        checkpoint(id, next, () -> {
            if (!selected) return;
            if (Bukkit.getWorld(id) == w && FullMoonRules.day(w.getFullTime()) == day && FullMoonRules.warning(FullMoonRules.phase(w.getFullTime())) && worldAllowed(w)) {
                nights.put(id, next); tell(w, "moon-warning");
            } else checkpoint(id, next.finish(), () -> { });
        });
    }
    private void start(World w, FullMoonLedger.Night old, long now) {
        var started = old.start(now);
        checkpoint(w.getUID(), started, () -> {
            if (Bukkit.getWorld(w.getUID()) == w && nights.get(w.getUID()) == old && old.day() == FullMoonRules.day(w.getFullTime())
                    && FullMoonRules.night(FullMoonRules.phase(w.getFullTime())) && worldAllowed(w)) {
                nights.put(w.getUID(), started); tell(w, "moon-started");
            }
        });
    }
    private void clean(long now) {
        int budget = settings.cleanupBudget();
        var iterator = mobs.values().iterator();
        while (iterator.hasNext()) {
            var o = iterator.next(); Zombie z = o.mob();
            if (!z.isValid() || z.isDead()) { iterator.remove(); continue; }
            var night = nights.get(z.getWorld().getUID()); Player owner = Bukkit.getPlayer(o.owner());
            boolean bad = night == null || night.started() == 0 || !o.token().equals(night.token())
                    || !active(owner, now) || !safety.clear(z.getLocation(), settings.spawnProtection())
                    || !z.getWorld().equals(owner.getWorld()) || z.getLocation().distanceSquared(owner.getLocation()) > 64 * 64;
            if (bad) {
                z.setTarget(null); z.setAI(false); z.setInvulnerable(true);
                if (budget-- > 0) { z.remove(); iterator.remove(); }
            }
        }
    }
    private void spawn(long now) {
        var players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        int budget = settings.spawnBudget(), visited = 0; int[] probes = { settings.attempts() };
        while (visited++ < Math.min(16, players.size()) && budget > 0 && mobs.size() < settings.globalCap() && probes[0] > 0) {
            Player p = players.get(Math.floorMod(cursor++, players.size()));
            var night = nights.get(p.getWorld().getUID());
            if (night == null || night.started() == 0 || !running(p.getWorld()) || !active(p, now)) continue;
            int personal = 0, nearby = 0;
            for (var o : mobs.values()) {
                if (o.owner().equals(p.getUniqueId())) personal++;
                if (o.mob().getWorld().equals(p.getWorld()) && o.mob().getLocation().distanceSquared(p.getLocation()) < 48 * 48) nearby++;
            }
            if (!FullMoonRules.room(mobs.size(), personal, nearby, settings)) continue;
            Location spot = position(p, probes);
            if (spot == null) continue;
            // Consumer ставит метку до CreatureSpawnEvent: чужие награды/прогрессия сразу видят временного моба.
            Zombie z = p.getWorld().spawn(spot, Zombie.class, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, false, mob -> {
                mob.getPersistentDataContainer().set(MobRewardPolicy.TEMPORARY, PersistentDataType.STRING, night.token().toString());
                mob.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
                var reinforcements = mob.getAttribute(org.bukkit.attribute.Attribute.SPAWN_REINFORCEMENTS);
                if (reinforcements != null) {
                    for (var modifier : reinforcements.getModifiers()) reinforcements.removeModifier(modifier);
                    reinforcements.setBaseValue(0);
                }
                mob.setPersistent(false); mob.setRemoveWhenFarAway(true); mob.setCanPickupItems(false); mob.setCanBreakDoors(false); mob.setBaby(false);
                if (mob.getEquipment() != null) mob.getEquipment().clear();
                mob.customName(plugin.messages().get("moon-zombie")); mob.setCustomNameVisible(false);
            });
            budget--; // Отменённый другим плагином спавн тоже расходует бюджет, без повторов в этом pulse.
            if (z.isValid() && !z.isDead()) { mobs.put(z.getUniqueId(), new Owned(z, p.getUniqueId(), night.token())); z.setTarget(p); }
        }
    }
    private Location position(Player p, int[] probes) {
        World w = p.getWorld(); Location at = p.getLocation();
        // Не создаём толпы в шахтах, комнатах и за стеной: только открытая местность и видимая точка.
        if (at.getY() + 4 < w.getHighestBlockYAt(at.getBlockX(), at.getBlockZ())) return null;
        var random = ThreadLocalRandom.current();
        while (probes[0]-- > 0) {
            double angle = random.nextDouble(Math.PI * 2), radius = random.nextDouble(settings.radiusMin(), settings.radiusMax());
            int x = (int) Math.floor(at.getX() + Math.cos(angle) * radius), z = (int) Math.floor(at.getZ() + Math.sin(angle) * radius);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getHighestBlockYAt(x, z) + 1;
            if (Math.abs(y - at.getY()) > 6) continue;
            Location loc = new Location(w, x + .5, y, z + .5);
            if (!safety.stand(loc, settings.spawnProtection())) continue;
            // Не материализуется рядом с другим игроком, даже если он не участник события.
            boolean close = false;
            for (Player other : w.getPlayers()) if (other.getLocation().distanceSquared(loc) < 16 * 16) { close = true; break; }
            if (close) continue;
            var direction = at.clone().add(0, 1, 0).toVector().subtract(loc.clone().add(0, 1, 0).toVector());
            if (w.rayTraceBlocks(loc.clone().add(0, 1, 0), direction, direction.length(), FluidCollisionMode.NEVER, true) == null) return loc;
        }
        return null;
    }
    private boolean mayAttack(Owned owned, Player p) {
        if (owned == null || !owned.owner().equals(p.getUniqueId()) || !running(owned.mob().getWorld())) return false;
        var n = nights.get(owned.mob().getWorld().getUID());
        return n.started() > 0 && owned.token().equals(n.token()) && active(p, System.currentTimeMillis())
                && safety.clear(owned.mob().getLocation(), settings.spawnProtection());
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void sleep(TimeSkipEvent e) {
        if (e.getSkipReason() == TimeSkipEvent.SkipReason.NIGHT_SKIP && running(e.getWorld())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void bed(PlayerBedEnterEvent e) {
        if (running(e.getPlayer().getWorld()) && safety.player(e.getPlayer())) plugin.messages().send(e.getPlayer(), "moon-bed");
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (!e.hasChangedPosition() || e.getTo() == null || !e.getFrom().getWorld().equals(e.getTo().getWorld())) return;
        if (e.getFrom().distanceSquared(e.getTo()) >= .0025 && safety.player(e.getPlayer())) {
            Long prior = activity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
            if (prior == null && running(e.getPlayer().getWorld())) plugin.messages().send(e.getPlayer(), "moon-joined");
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e) { activity.remove(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent e) {
        if (!MobRewardPolicy.temporary(e.getEntity())) return;
        var owned = mobs.get(e.getEntity().getUniqueId());
        if (!(e.getTarget() instanceof Player p) || !mayAttack(owned, p)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        if (MobRewardPolicy.temporary(e.getDamager()) && (!(e.getEntity() instanceof Player p)
                || !mayAttack(mobs.get(e.getDamager().getUniqueId()), p))) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void combat(EntityDamageByEntityEvent e) {
        if (!MobRewardPolicy.temporary(e.getEntity())) return;
        Player p = e.getDamager() instanceof Player player ? player : e.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player player ? player : null;
        if (safety.player(p)) activity.put(p.getUniqueId(), System.currentTimeMillis());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void death(EntityDeathEvent e) {
        if (MobRewardPolicy.temporary(e.getEntity())) { e.getDrops().clear(); e.setDroppedExp(0); mobs.remove(e.getEntity().getUniqueId()); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void transform(EntityTransformEvent e) { if (MobRewardPolicy.temporary(e.getEntity())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) { if (MobRewardPolicy.temporary(e.getEntity())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void grief(EntityChangeBlockEvent e) { if (MobRewardPolicy.temporary(e.getEntity())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void door(EntityBreakDoorEvent e) { if (MobRewardPolicy.temporary(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void unload(EntitiesUnloadEvent e) {
        for (Entity entity : e.getEntities()) if (MobRewardPolicy.temporary(entity)) { mobs.remove(entity.getUniqueId()); entity.remove(); }
    }
    @EventHandler public void load(EntitiesLoadEvent e) {
        // Временные мобы не должны сохраняться вообще. Старые аварийные остатки не возвращаем в бой.
        for (Entity entity : e.getEntities()) if (MobRewardPolicy.temporary(entity)) { mobs.remove(entity.getUniqueId()); entity.remove(); }
    }
    @EventHandler public void changedWorld(PlayerChangedWorldEvent e) {
        if (safety.player(e.getPlayer()) && running(e.getPlayer().getWorld())) plugin.messages().send(e.getPlayer(), "moon-joined");
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void worldUnload(WorldUnloadEvent e) { finish(e.getWorld(), "moon-ended"); }
    String status(World world) {
        return failed || !ledger.healthy() ? "error" : !settings.enabled() || !plugin.config().enabled() ? "disabled"
                : running(world) ? (nights.get(world.getUID()).started() == 0 ? "warning" : "active") : "quiet";
    }
    int count() { return mobs.size(); }
    void stop() {
        stopped = true; generation++; clock.cancel(); endAll("moon-stopped");
        io.shutdown();
        try { if (!io.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) plugin.getLogger().warning("Запись календаря полнолуния ещё завершается; временные мобы уже удаляются."); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        for (var o : mobs.values()) if (o.mob().isValid()) o.mob().remove();
        mobs.clear(); activity.clear(); bootstrap.clear(); pendingWrites.clear(); pendingPlans.clear();
    }
}
