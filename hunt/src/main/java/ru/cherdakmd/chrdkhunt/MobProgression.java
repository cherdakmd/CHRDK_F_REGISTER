package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.api.MobProgressionKeys;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Природные мобы: фиксированный уровень, без имён/спама и множителей наград. */
final class MobProgression implements Listener {
    record View(int level, int elite) { }
    private static final int WATCH_LIMIT = 8192, WATCH_BUDGET = 128;
    private final HuntPlugin plugin;
    private final NamespacedKey level, elite, origin, active, shotFactor, shotOwner, shotWorld, shotTime, shotEpoch;
    private final Map<UUID, Long> hud = new HashMap<>();
    private final LinkedHashMap<UUID, WeakReference<LivingEntity>> watched = new LinkedHashMap<>();
    private final MobTaskQueue tasks;
    private final BukkitTask watchTask;
    private boolean enabled, showHud, armored, recovering;
    private double eliteChance;
    private NewcomerProtection newcomers;
    private SpawnGroup.Settings groupSettings;
    SpawnGroup.Settings groupSettings() { return groupSettings; }
    private long generation;
    private String epoch;

    MobProgression(HuntPlugin plugin) {
        this.plugin = plugin;
        level = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.LEVEL);
        elite = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.ELITE);
        origin = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.ORIGIN);
        active = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.ACTIVE);
        shotFactor = new NamespacedKey(plugin, "mob_shot_factor"); shotOwner = new NamespacedKey(plugin, "mob_shot_owner");
        shotWorld = new NamespacedKey(plugin, "mob_shot_world"); shotTime = new NamespacedKey(plugin, "mob_shot_time");
        shotEpoch = new NamespacedKey(plugin, "mob_shot_epoch");
        tasks = new MobTaskQueue(action -> { var task = Bukkit.getScheduler().runTask(plugin, action); return task::cancel; });
        reload();
        watchTask = Bukkit.getScheduler().runTaskTimer(plugin, this::watch, 20L, 20L);
    }
    void reload() {
        generation++; epoch = UUID.randomUUID().toString(); tasks.clear();
        enabled = plugin.getConfig().getBoolean("mob-progression.enabled", true);
        showHud = plugin.getConfig().getBoolean("mob-progression.hud", true);
        armored = plugin.getConfig().getBoolean("mob-progression.armored-elite", true);
        recovering = plugin.getConfig().getBoolean("mob-progression.recovery.enabled", true);
        double value = plugin.getConfig().getDouble("mob-progression.elite-chance-percent", 2);
        eliteChance = Double.isFinite(value) ? Math.max(0, Math.min(5, value)) : 2;
        newcomers = NewcomerProtection.settings(
                plugin.getConfig().getBoolean("mob-progression.newcomer-protection.enabled", false),
                plugin.getConfig().getInt("mob-progression.newcomer-protection.max-skill-total", 20),
                plugin.getConfig().getDouble("mob-progression.newcomer-protection.radius", 16));
        groupSettings = SpawnGroup.Settings.bounded(
                plugin.getConfig().getBoolean("mob-progression.group.enabled", false),
                plugin.getConfig().getDouble("mob-progression.group.radius", 24),
                plugin.getConfig().getInt("mob-progression.group.max-members", 8));
        hud.clear(); watched.clear();
        for (var world : Bukkit.getWorlds()) for (var entity : world.getLivingEntities()) if (marked(entity)) refresh(entity);
    }
    boolean available(Player player) {
        return enabled && plugin.config().enabled() && !plugin.config().worldDisabled(player.getWorld().getName());
    }
    private boolean allowed(LivingEntity mob) {
        return enabled && plugin.config().enabled() && !plugin.config().worldDisabled(mob.getWorld().getName());
    }
    private boolean observer(Player player) {
        return player.isOnline() && !player.isDead() && player.getGameMode() == GameMode.SURVIVAL
                && player.hasPermission("chrdkhunt.use") && plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player);
    }
    static int skills(java.util.function.IntSupplier source) {
        try { return Math.clamp(source.getAsInt(), 10, 1000); }
        catch (RuntimeException | LinkageError ignored) { return 10; }
    }
    int skills(Player player) {
        return skills(() -> {
            if (!Bukkit.getPluginManager().isPluginEnabled("CHRDK_Skills")) return 10;
            ChrdkSkillsApi api = Bukkit.getServicesManager().load(ChrdkSkillsApi.class);
            return api == null ? 10 : api.totalLevel(player);
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        if (!MobProgressionPolicy.natural(event.getSpawnReason().name()) || !allowed(event.getEntity())) return;
        long expected = generation;
        tasks.submit(event.getEntity().getUniqueId(), "spawn", () -> {
            LivingEntity mob = event.getEntity();
            if (generation != expected || event.isCancelled() || !mob.isValid() || mob.isDead()
                    || !allowed(mob) || !eligibleMob(mob) || !mob.getPersistentDataContainer().getKeys().isEmpty()) return;
            Player nearest = null; double best = 128 * 128;
            boolean nearbyNewcomer = false;
            var group = new SpawnGroup(groupSettings);
            for (Player player : mob.getWorld().getNearbyPlayers(mob.getLocation(), 128)) {
                if (player.getGameMode() != GameMode.SURVIVAL || player.isDead() || plugin.coreApi() == null
                        || !plugin.coreApi().isAuthenticated(player)) continue;
                double distance = player.getLocation().distanceSquared(mob.getLocation());
                boolean newcomerCandidate = !nearbyNewcomer && newcomers.enabled()
                        && distance <= newcomers.radius() * newcomers.radius();
                if ((newcomerCandidate || groupSettings.inRange(distance)) && observer(player)
                        && !player.hasMetadata("NPC") && !player.isInvulnerable() && mob.hasLineOfSight(player)) {
                    int memberSkills = skills(player);
                    if (newcomerCandidate && newcomers.candidate(memberSkills, distance)) nearbyNewcomer = true;
                    group.offer(player.getUniqueId(), distance, memberSkills);
                }
                if (MobProgressionPolicy.nearer(distance, player.getUniqueId(), best, nearest == null ? null : nearest.getUniqueId())) {
                    best = distance; nearest = player;
                }
            }
            if (nearest == null) return;
            int total = skills(nearest);
            var center = mob.getWorld().getSpawnLocation();
            double distance = Math.hypot(mob.getLocation().getX() - center.getX(), mob.getLocation().getZ() - center.getZ());
            int rank = MobProgressionPolicy.level(mob.getWorld().getEnvironment().name(), distance, group.effectiveSkills(total));
            int variant = distance > 512 && total >= 50 && ThreadLocalRandom.current().nextDouble(100) < eliteChance
                    ? MobProgressionPolicy.variant(ThreadLocalRandom.current().nextInt(MobProgressionPolicy.variantCount(armored, recovering)), armored, recovering) : 0;
            var protectedSpawn = newcomers.apply(MobProgressionPolicy.range(mob.getWorld().getEnvironment().name(), distance),
                    rank, variant, nearbyNewcomer);
            rank = protectedSpawn.level(); variant = protectedSpawn.elite();
            if (variant == 3 && (mob instanceof Ghast || mob instanceof Phantom)) variant = 2;
            if (variant == 2 && mob instanceof Creeper) variant = 1;
            if (variant == 4 && (mob.getAttribute(Attribute.ARMOR) == null
                    || MobProgressionPolicy.armorBonus(mob.getAttribute(Attribute.ARMOR).getValue(), 4) <= 0)) variant = 1;
            var data = mob.getPersistentDataContainer();
            data.set(level, PersistentDataType.INTEGER, rank); data.set(elite, PersistentDataType.INTEGER, variant);
            data.set(origin, PersistentDataType.STRING, MobProgressionKeys.NATURAL);
            refresh(mob);
        });
    }
    private boolean marked(LivingEntity mob) {
        var data = mob.getPersistentDataContainer();
        return data.has(level) || data.has(elite) || data.has(origin) || data.has(active);
    }
    private int integer(PersistentDataContainer data, NamespacedKey key) {
        return data.has(key, PersistentDataType.INTEGER) ? data.get(key, PersistentDataType.INTEGER) : 0;
    }
    private boolean eligibleMob(LivingEntity mob) {
        return hostile(mob) && mob.customName() == null && mob.hasAI() && mob.getEntitySpawnReason() != null
                && MobProgressionPolicy.natural(mob.getEntitySpawnReason().name());
    }
    private boolean validData(LivingEntity mob) {
        var data = mob.getPersistentDataContainer();
        return data.has(level, PersistentDataType.INTEGER) && data.has(elite, PersistentDataType.INTEGER)
                && data.has(origin, PersistentDataType.STRING) && MobProgressionKeys.NATURAL.equals(data.get(origin, PersistentDataType.STRING))
                && MobProgressionPolicy.valid(integer(data, level), integer(data, elite));
    }
    View view(LivingEntity mob) {
        if (!eligibleMob(mob) || !validData(mob)) { if (marked(mob)) clearOwned(mob); return null; }
        if (!allowed(mob)) { strip(mob); return null; }
        var data = mob.getPersistentDataContainer();
        return data.has(active, PersistentDataType.BYTE) && data.get(active, PersistentDataType.BYTE) == 1
                ? new View(integer(data, level), integer(data, elite)) : null;
    }
    @EventHandler public void load(EntitiesLoadEvent event) {
        for (var entity : event.getEntities()) if (entity instanceof LivingEntity mob && marked(mob)) refresh(mob);
    }
    @EventHandler public void unload(EntitiesUnloadEvent event) {
        for (var entity : event.getEntities()) watched.remove(entity.getUniqueId());
    }
    private void refresh(LivingEntity mob) {
        if (!eligibleMob(mob) || !validData(mob)) { clearOwned(mob); return; }
        if (!allowed(mob) || !watched.containsKey(mob.getUniqueId()) && watched.size() >= WATCH_LIMIT) { strip(mob); return; }
        watched.put(mob.getUniqueId(), new WeakReference<>(mob));
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        double before = health == null ? Double.NaN : health.getValue(), current = mob.getHealth();
        var stats = MobProgressionPolicy.stats(integer(mob.getPersistentDataContainer(), level), integer(mob.getPersistentDataContainer(), elite));
        modify(mob, Attribute.MAX_HEALTH, "mob_health", stats.health() - 1);
        modify(mob, Attribute.ATTACK_DAMAGE, "mob_damage", stats.damage() - 1);
        modify(mob, Attribute.MOVEMENT_SPEED, "mob_speed", stats.speed() - 1);
        armor(mob, armored && integer(mob.getPersistentDataContainer(), elite) == 4);
        restoreHealth(mob, before, current);
        mob.getPersistentDataContainer().set(active, PersistentDataType.BYTE, (byte) 1);
    }
    private void modify(LivingEntity mob, Attribute attribute, String id, double value) {
        var instance = mob.getAttribute(attribute);
        if (instance == null) return;
        var key = new NamespacedKey(plugin, id);
        if (instance.getModifier(key) != null) instance.removeModifier(key);
        if (value > 0) instance.addModifier(new AttributeModifier(key, value, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }
    private void armor(LivingEntity mob, boolean apply) {
        var instance = mob.getAttribute(Attribute.ARMOR);
        if (instance == null) return;
        var key = new NamespacedKey(plugin, "mob_armor");
        if (instance.getModifier(key) != null) instance.removeModifier(key);
        double bonus = MobProgressionPolicy.armorBonus(instance.getValue(), apply ? 4 : 0);
        if (bonus > 0) instance.addModifier(new AttributeModifier(key, bonus, AttributeModifier.Operation.ADD_NUMBER));
    }
    private void restoreHealth(LivingEntity mob, double before, double current) {
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        if (health == null || mob.isDead()) return;
        double value = MobProgressionPolicy.scaledHealth(current, before, health.getValue());
        if (Double.isFinite(value)) mob.setHealth(value);
    }
    private void strip(LivingEntity mob) {
        watched.remove(mob.getUniqueId());
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        double before = health == null ? Double.NaN : health.getValue(), current = mob.getHealth();
        modify(mob, Attribute.MAX_HEALTH, "mob_health", 0); modify(mob, Attribute.ATTACK_DAMAGE, "mob_damage", 0);
        modify(mob, Attribute.MOVEMENT_SPEED, "mob_speed", 0); armor(mob, false);
        restoreHealth(mob, before, current);
        mob.getPersistentDataContainer().remove(active);
    }
    private void clearOwned(LivingEntity mob) {
        strip(mob);
        var data = mob.getPersistentDataContainer();
        data.remove(level); data.remove(elite); data.remove(origin);
    }
    /** Без обхода миров: не более 128 слабых ссылок/сек., полный цикл максимум 64 сек. */
    private void watch() {
        for (int left = Math.min(WATCH_BUDGET, watched.size()); left > 0; left--) {
            var entry = watched.entrySet().iterator().next(); watched.remove(entry.getKey());
            LivingEntity mob = entry.getValue().get();
            if (mob == null || !mob.isValid() || mob.isDead()) continue;
            if (!eligibleMob(mob) || !validData(mob)) { clearOwned(mob); continue; }
            if (!allowed(mob)) { strip(mob); continue; }
            watched.put(entry.getKey(), entry.getValue());
        }
    }
    void observe(Player player, View view) {
        if (observer(player) && plugin.store().observeMob(player.getUniqueId(), player.getName(), view.level, view.elite)) plugin.requestSave();
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent event) {
        Player player = event.getDamager() instanceof Player p ? p : event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (player == null || !(event.getEntity() instanceof LivingEntity mob) || !observer(player) || !marked(mob)) return;
        UUID world = player.getWorld().getUID();
        tasks.submit(player.getUniqueId(), "hud", () -> {
            if (event.isCancelled() || !Double.isFinite(event.getFinalDamage()) || event.getFinalDamage() <= 0
                    || !observer(player) || !mob.isValid() || mob.isDead() || !player.getWorld().getUID().equals(world)
                    || !mob.getWorld().getUID().equals(world)) return;
            View view = view(mob); if (view == null) return;
            observe(player, view);
            long now = System.currentTimeMillis();
            if (!showHud || !plugin.store().mobHud(player.getUniqueId()) || hud.getOrDefault(player.getUniqueId(), 0L) > now
                    || !hud.containsKey(player.getUniqueId()) && hud.size() >= 4096) return;
            hud.put(player.getUniqueId(), now + 2000);
            player.sendActionBar(plugin.messages().get("mob-level-hud", "level", view.level,
                    "kind", plugin.messages().mini("mob-variant-" + view.elite)));
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || !marked(mob)) return;
        if (tasks.contains(mob.getUniqueId(), "teleport")) return;
        // Смотрим фактический мир после всех телепортаций тика, а не destination первого события.
        if (!tasks.submit(mob.getUniqueId(), "teleport", () -> { if (mob.isValid()) refresh(mob); })) strip(mob);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void transform(EntityTransformEvent event) {
        if (!(event.getEntity() instanceof LivingEntity source) || !marked(source)) return;
        for (var entity : event.getTransformedEntities()) if (entity instanceof LivingEntity mob) {
            if (tasks.contains(mob.getUniqueId(), "transform")) continue;
            if (!tasks.submit(mob.getUniqueId(), "transform", () -> {
                if (!event.isCancelled() && mob.isValid()) clearOwned(mob);
            })) clearOwned(mob);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) {
        var shot = event.getEntity(); var data = shot.getPersistentDataContainer();
        for (var key : java.util.List.of(shotFactor, shotOwner, shotWorld, shotTime, shotEpoch)) data.remove(key);
        if (!(shot.getShooter() instanceof LivingEntity mob)) return;
        View view = view(mob); if (view == null) return;
        data.set(shotFactor, PersistentDataType.DOUBLE, MobProgressionPolicy.stats(view.level, view.elite).damage());
        data.set(shotOwner, PersistentDataType.STRING, mob.getUniqueId().toString());
        data.set(shotWorld, PersistentDataType.STRING, shot.getWorld().getUID().toString());
        data.set(shotTime, PersistentDataType.LONG, System.currentTimeMillis());
        data.set(shotEpoch, PersistentDataType.STRING, epoch);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void ranged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Projectile shot)
                || !(shot.getShooter() instanceof LivingEntity mob) || !allowed(mob) || !eligibleMob(mob)
                || !Double.isFinite(event.getDamage()) || event.getDamage() <= 0) return;
        var data = shot.getPersistentDataContainer();
        if (!data.has(shotFactor, PersistentDataType.DOUBLE) || !data.has(shotTime, PersistentDataType.LONG)
                || !data.has(shotOwner, PersistentDataType.STRING) || !data.has(shotWorld, PersistentDataType.STRING)
                || !data.has(shotEpoch, PersistentDataType.STRING) || !epoch.equals(data.get(shotEpoch, PersistentDataType.STRING))
                || !mob.getUniqueId().toString().equals(data.get(shotOwner, PersistentDataType.STRING))
                || !shot.getWorld().getUID().toString().equals(data.get(shotWorld, PersistentDataType.STRING))
                || !shot.getWorld().equals(mob.getWorld()) || !shot.getWorld().equals(event.getEntity().getWorld())) return;
        double factor = MobProgressionPolicy.shotFactor(data.get(shotFactor, PersistentDataType.DOUBLE),
                data.get(shotTime, PersistentDataType.LONG), System.currentTimeMillis());
        double damage = event.getDamage() * factor;
        if (Double.isFinite(damage)) event.setDamage(damage);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { hud.remove(event.getPlayer().getUniqueId()); }
    static boolean hostile(LivingEntity entity) {
        if (entity == null || entity instanceof Player || entity instanceof Tameable || entity instanceof Boss
                || entity instanceof Warden || entity instanceof ElderGuardian || entity instanceof ArmorStand || entity.hasMetadata("NPC")) return false;
        return entity instanceof Monster || entity instanceof Slime || entity instanceof Phantom || entity instanceof Ghast;
    }
    void stop() {
        enabled = false; generation++; tasks.stop(); watchTask.cancel(); hud.clear(); watched.clear();
        for (var world : Bukkit.getWorlds()) for (var entity : world.getLivingEntities()) if (marked(entity)) strip(entity);
    }
}
