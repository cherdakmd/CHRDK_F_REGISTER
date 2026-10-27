package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Короткий native-speed burst наземной элиты: ни velocity, ни телепортов, ни смены AI/цели. */
final class EliteDashing implements Listener {
    private static final String KIND = "dash-confirm";
    private record Context(Mob mob, Player player, UUID world, double boost) { }
    private final HuntPlugin plugin;
    private final NamespacedKey dash, normal, cooldown;
    private final MobTaskQueue confirmations;
    private final RecoveryWindows requests = new RecoveryWindows();
    private final BurstWindows<Context> bursts = new BurstWindows<>();
    private final BukkitTask clockTask;
    private EliteDash.Settings settings;
    private long generation, tick;
    private boolean stopped;

    EliteDashing(HuntPlugin plugin) {
        this.plugin = plugin;
        dash = new NamespacedKey(plugin, "mob_dash"); normal = new NamespacedKey(plugin, "mob_speed");
        cooldown = new NamespacedKey(plugin, "elite_dash_next_v1");
        confirmations = new MobTaskQueue(action -> {
            var task = Bukkit.getScheduler().runTask(plugin, action); return task::cancel;
        });
        reload();
        clockTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 1L, 1L);
    }
    void reload() {
        clear();
        String path = "mob-progression.dash.";
        var config = plugin.getConfig();
        settings = EliteDash.Settings.bounded(config.getBoolean(path + "enabled", true),
                config.getInt(path + "duration-ticks", 20), config.getInt(path + "cooldown-seconds", 12),
                config.getDouble(path + "speed-multiplier", 1.10));
    }
    /** Используется также refresh/strip прогрессии: удаляет только наш временный ключ. */
    static void clearModifier(LivingEntity mob, HuntPlugin plugin) {
        var attribute = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        var key = new NamespacedKey(plugin, "mob_dash");
        if (attribute != null && attribute.getModifier(key) != null) attribute.removeModifier(key);
    }
    private Long next(Mob mob) {
        var data = mob.getPersistentDataContainer();
        if (!data.has(cooldown)) return 0L;
        return data.has(cooldown, PersistentDataType.LONG) ? data.get(cooldown, PersistentDataType.LONG) : null;
    }
    private MobProgression.View eligible(Player player, Mob mob, UUID world, boolean requireTarget) {
        if (stopped || !settings.enabled() || !plugin.isEnabled() || !player.isOnline() || player.isDead()
                || player.getGameMode() != GameMode.SURVIVAL || player.isInvulnerable() || player.hasMetadata("NPC")
                || !player.hasPermission("chrdkhunt.use") || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)
                || !mob.isValid() || mob.isDead() || mob.isInvulnerable() || !EliteDash.supported(mob.getType().name())
                || !mob.isOnGround() || mob.isInWater() || mob.isInLava() || mob.isInsideVehicle() || !mob.getPassengers().isEmpty()
                || !world.equals(player.getWorld().getUID()) || !world.equals(mob.getWorld().getUID())
                || !EliteDash.range(player.getLocation().distanceSquared(mob.getLocation()))
                || !mob.hasLineOfSight(player) || requireTarget && !player.equals(mob.getTarget())) return null;
        var progression = plugin.progression();
        var view = progression == null ? null : progression.view(mob);
        return view != null && view.elite() == EliteDash.VARIANT ? view : null;
    }
    private boolean levelMatches(AttributeInstance attribute, double levelFactor) {
        var modifier = attribute.getModifier(normal);
        return modifier == null ? levelFactor == 1 : EliteDash.ownedLevel(modifier.getAmount(), levelFactor,
                modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }
    private void request(Player player, Mob mob, BooleanSupplier confirmed) {
        UUID id = mob.getUniqueId(), world = mob.getWorld().getUID();
        if (bursts.contains(id) || eligible(player, mob, world, false) == null) return;
        Long next = next(mob);
        if (next == null || settings.reserve(next, System.currentTimeMillis()) == null) return;
        var ticket = requests.replace(id, player.getUniqueId());
        if (ticket == null) return;
        confirmations.cancel(id, KIND);
        long expected = generation;
        try {
            if (!confirmations.submit(id, KIND, () -> {
                if (!requests.remove(id, ticket) || expected != generation || !confirmed.getAsBoolean()) return;
                begin(player, mob, world);
            })) requests.remove(id, ticket);
        } catch (RuntimeException | Error failure) { requests.remove(id, ticket); throw failure; }
    }
    private void begin(Player player, Mob mob, UUID world) {
        var view = eligible(player, mob, world, true);
        if (view == null) return;
        var attribute = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        double levelFactor = MobProgressionPolicy.stats(view.level(), view.elite()).speed();
        double boost = settings.boost(levelFactor);
        Long next = next(mob), reserved = next == null ? null : settings.reserve(next, System.currentTimeMillis());
        if (attribute == null || boost <= 0 || reserved == null || !levelMatches(attribute, levelFactor)
                || !Double.isFinite(attribute.getValue()) || attribute.getValue() <= 0) return;
        var context = new Context(mob, player, world, boost);
        if (!bursts.start(mob.getUniqueId(), player.getUniqueId(), context, tick, settings.ticks())) return;
        try {
            clearModifier(mob, plugin);
            // Запись cooldown до эффекта: ошибка применения не позволяет обходить ограничение повторными попытками.
            mob.getPersistentDataContainer().set(cooldown, PersistentDataType.LONG, reserved);
            attribute.addTransientModifier(new AttributeModifier(dash, boost, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        } catch (RuntimeException | Error failure) { forget(mob.getUniqueId()); throw failure; }
    }
    private void sweep() {
        tick++;
        // Не обход мира: максимум 128 активных коротких эффектов, каждый проверяется каждый тик.
        for (var entry : bursts.snapshot()) {
            boolean keep = false;
            try {
                var context = entry.value(); var mob = context.mob();
                var view = tick < entry.until() ? eligible(context.player(), mob, context.world(), true) : null;
                var attribute = view == null ? null : mob.getAttribute(Attribute.MOVEMENT_SPEED);
                var modifier = attribute == null ? null : attribute.getModifier(dash);
                keep = modifier != null && levelMatches(attribute, MobProgressionPolicy.stats(view.level(), view.elite()).speed())
                        && modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1
                        && modifier.getAmount() == context.boost();
            } catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().warning("Проверка ускорения элиты прервана (" + failure.getClass().getSimpleName() + "). Эффект снят.");
            } finally {
                if (!keep) forget(entry.id());
            }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!(event.getTarget() instanceof Player player)) { forget(mob.getUniqueId()); return; }
        // Даже отменённая позднее смена цели может только досрочно снять бонус, не перенести его на другого игрока.
        var entry = bursts.get(mob.getUniqueId());
        if (entry != null && !entry.owner().equals(player.getUniqueId())) forget(entry.id());
        request(player, mob, () -> !event.isCancelled() && player.equals(event.getTarget()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent event) {
        Player player = event.getDamager() instanceof Player direct ? direct
                : event.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player shooter ? shooter : null;
        if (player == null || !(event.getEntity() instanceof Mob mob)
                || !Double.isFinite(event.getFinalDamage()) || event.getFinalDamage() <= 0) return;
        double before = mob.getHealth();
        request(player, mob, () -> mob.getLastDamageCause() == event
                && EliteRecovery.lostHealth(before, mob.getHealth(), event.getFinalDamage(), event.isCancelled()));
    }
    private void forget(UUID id) {
        requests.remove(id); confirmations.cancel(id, KIND);
        var removed = bursts.remove(id);
        if (removed != null) clearModifier(removed.value().mob(), plugin);
    }
    private void forgetPlayer(UUID owner) {
        for (UUID id : requests.forget(owner)) confirmations.cancel(id, KIND);
        for (var entry : bursts.drain(e -> e.owner().equals(owner))) clearModifier(entry.value().mob(), plugin);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { forgetPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void playerTeleport(PlayerTeleportEvent event) { forgetPlayer(event.getPlayer().getUniqueId()); }
    @EventHandler public void unload(EntitiesUnloadEvent event) {
        for (var entity : event.getEntities()) {
            forget(entity.getUniqueId());
            if (entity instanceof LivingEntity mob) clearModifier(mob, plugin);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(EntityTeleportEvent event) { forget(event.getEntity().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent event) {
        forget(event.getEntity().getUniqueId());
        if (event.getEntity() instanceof Player player) forgetPlayer(player.getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void transform(EntityTransformEvent event) {
        forget(event.getEntity().getUniqueId());
        if (event.getEntity() instanceof LivingEntity source) clearModifier(source, plugin);
        for (var entity : event.getTransformedEntities()) if (entity instanceof LivingEntity mob) {
            forget(mob.getUniqueId()); clearModifier(mob, plugin);
        }
    }
    void clear() {
        generation++; confirmations.clear(); requests.clear();
        for (var entry : bursts.clear()) clearModifier(entry.value().mob(), plugin);
    }
    void stop() { stopped = true; clockTask.cancel(); clear(); confirmations.stop(); }
}
