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
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import ru.cherdakmd.chrdkregister.api.MobProgressionKeys;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Природные мобы, без переименования (и спама Named entity died), новых монет и сканирования мира по таймеру. */
final class MobProgression implements Listener {
    private final HuntPlugin plugin;
    private final NamespacedKey level, elite, origin, active;
    private final Map<UUID, Long> hud = new HashMap<>();
    private boolean enabled, showHud;
    private double eliteChance;
    private int pending;
    private long generation;

    MobProgression(HuntPlugin plugin) {
        this.plugin = plugin;
        level = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.LEVEL);
        elite = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.ELITE);
        origin = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.ORIGIN);
        active = new NamespacedKey(MobProgressionKeys.NAMESPACE, MobProgressionKeys.ACTIVE);
        reload();
    }
    void reload() {
        generation++;
        enabled = plugin.getConfig().getBoolean("mob-progression.enabled", true);
        showHud = plugin.getConfig().getBoolean("mob-progression.hud", true);
        double value = plugin.getConfig().getDouble("mob-progression.elite-chance-percent", 2);
        eliteChance = Double.isFinite(value) ? Math.max(0, Math.min(5, value)) : 2;
        hud.clear();
        for (var world : Bukkit.getWorlds()) for (var entity : world.getLivingEntities()) {
            if (entity.getPersistentDataContainer().has(level, PersistentDataType.INTEGER)) refresh(entity);
        }
    }
    private boolean allowed(LivingEntity mob) {
        return enabled && plugin.config().enabled() && !plugin.config().worldDisabled(mob.getWorld().getName());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        if (!MobProgressionPolicy.natural(event.getSpawnReason().name()) || !allowed(event.getEntity()) || pending >= 4096) return;
        long expected = generation;
        pending++;
        Bukkit.getScheduler().runTask(plugin, () -> {
            pending--;
            LivingEntity mob = event.getEntity();
            if (generation != expected || event.isCancelled() || !mob.isValid() || mob.isDead()
                    || !allowed(mob) || !hostile(mob) || mob.customName() != null || mob.hasMetadata("NPC")
                    || !mob.hasAI() || !mob.getPersistentDataContainer().getKeys().isEmpty()) return;
            Player nearest = null; double best = 128 * 128;
            for (Player player : mob.getWorld().getNearbyPlayers(mob.getLocation(), 128)) {
                if (player.getGameMode() != GameMode.SURVIVAL || player.isDead() || plugin.coreApi() == null
                        || !plugin.coreApi().isAuthenticated(player)) continue;
                double distance = player.getLocation().distanceSquared(mob.getLocation());
                if (distance < best) { best = distance; nearest = player; }
            }
            if (nearest == null) return;
            int total = 10;
            if (Bukkit.getPluginManager().isPluginEnabled("CHRDK_Skills")) {
                try {
                    ChrdkSkillsApi skills = Bukkit.getServicesManager().load(ChrdkSkillsApi.class);
                    if (skills != null) total = Math.max(10, Math.min(1000, skills.totalLevel(nearest)));
                } catch (LinkageError ignored) {
                    // Мягкая интеграция: при отсутствии/старой версии Skills остаётся географический минимум.
                }
            }
            var center = mob.getWorld().getSpawnLocation();
            double distance = Math.hypot(mob.getLocation().getX() - center.getX(), mob.getLocation().getZ() - center.getZ());
            int rank = MobProgressionPolicy.level(mob.getWorld().getEnvironment().name(), distance, total);
            int variant = distance > 512 && total >= 50 && ThreadLocalRandom.current().nextDouble(100) < eliteChance
                    ? ThreadLocalRandom.current().nextInt(1, 4) : 0;
            var data = mob.getPersistentDataContainer();
            data.set(level, PersistentDataType.INTEGER, rank);
            data.set(elite, PersistentDataType.INTEGER, variant);
            data.set(origin, PersistentDataType.STRING, MobProgressionKeys.NATURAL);
            refresh(mob);
        });
    }
    @EventHandler public void load(EntitiesLoadEvent event) {
        for (var entity : event.getEntities()) if (entity instanceof LivingEntity living
                && living.getPersistentDataContainer().has(level, PersistentDataType.INTEGER)) refresh(living);
    }
    private void refresh(LivingEntity mob) {
        var data = mob.getPersistentDataContainer();
        int rank = data.getOrDefault(level, PersistentDataType.INTEGER, 0);
        int variant = data.getOrDefault(elite, PersistentDataType.INTEGER, 0);
        if (!allowed(mob) || !hostile(mob) || rank < 1 || rank > MobProgressionPolicy.MAX_LEVEL || variant < 0 || variant > 3
                || !MobProgressionKeys.NATURAL.equals(data.get(origin, PersistentDataType.STRING))) {
            strip(mob); return;
        }
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        double ratio = health == null || health.getValue() <= 0 ? 1 : Math.clamp(mob.getHealth() / health.getValue(), 0, 1);
        data.set(active, PersistentDataType.BYTE, (byte) 1);
        var stats = MobProgressionPolicy.stats(rank, variant);
        modify(mob, Attribute.MAX_HEALTH, "mob_health", stats.health() - 1);
        modify(mob, Attribute.ATTACK_DAMAGE, "mob_damage", stats.damage() - 1);
        modify(mob, Attribute.MOVEMENT_SPEED, "mob_speed", stats.speed() - 1);
        if (health != null && !mob.isDead()) mob.setHealth(Math.min(health.getValue(), Math.max(0.01, health.getValue() * ratio)));
    }
    private void modify(LivingEntity mob, Attribute attribute, String id, double value) {
        var instance = mob.getAttribute(attribute);
        if (instance == null) return;
        var key = new NamespacedKey(plugin, id);
        if (instance.getModifier(key) != null) instance.removeModifier(key);
        if (value > 0) instance.addModifier(new AttributeModifier(key, value, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }
    private void strip(LivingEntity mob) {
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        double ratio = health == null || health.getValue() <= 0 ? 1 : Math.clamp(mob.getHealth() / health.getValue(), 0, 1);
        modify(mob, Attribute.MAX_HEALTH, "mob_health", 0);
        modify(mob, Attribute.ATTACK_DAMAGE, "mob_damage", 0);
        modify(mob, Attribute.MOVEMENT_SPEED, "mob_speed", 0);
        if (health != null && !mob.isDead()) mob.setHealth(Math.min(health.getValue(), Math.max(0.01, health.getValue() * ratio)));
        mob.getPersistentDataContainer().remove(active);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent event) {
        Player player = event.getDamager() instanceof Player p ? p : event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (!showHud || player == null || !(event.getEntity() instanceof LivingEntity mob) || !allowed(mob)
                || plugin.coreApi() == null || !plugin.coreApi().isAuthenticated(player)) return;
        int rank = mob.getPersistentDataContainer().getOrDefault(level, PersistentDataType.INTEGER, 0);
        long now = System.currentTimeMillis();
        if (rank < 1 || mob.getPersistentDataContainer().getOrDefault(active, PersistentDataType.BYTE, (byte) 0) != 1 || hud.getOrDefault(player.getUniqueId(), 0L) > now) return;
        hud.put(player.getUniqueId(), now + 2000);
        int variant = mob.getPersistentDataContainer().getOrDefault(elite, PersistentDataType.INTEGER, 0);
        player.sendActionBar(plugin.messages().get("mob-level-hud", "level", rank,
                "kind", plugin.messages().mini("mob-variant-" + Math.clamp(variant, 0, 3))));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(org.bukkit.event.entity.EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof LivingEntity mob) || !mob.getPersistentDataContainer().has(level, PersistentDataType.INTEGER)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.isCancelled() && mob.isValid()) refresh(mob);
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void transform(org.bukkit.event.entity.EntityTransformEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled()) return;
            for (LivingEntity mob : event.getTransformedEntities()) {
                strip(mob);
                var data = mob.getPersistentDataContainer();
                data.remove(level); data.remove(elite); data.remove(origin);
            }
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void ranged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getDamager() instanceof Projectile shot)
                || !(shot.getShooter() instanceof LivingEntity mob) || !allowed(mob)) return;
        var data = mob.getPersistentDataContainer();
        if (data.getOrDefault(active, PersistentDataType.BYTE, (byte) 0) != 1) return;
        int rank = data.getOrDefault(level, PersistentDataType.INTEGER, 0);
        int variant = data.getOrDefault(elite, PersistentDataType.INTEGER, 0);
        if (rank >= 1 && rank <= 30 && variant >= 0 && variant <= 3)
            event.setDamage(event.getDamage() * MobProgressionPolicy.stats(rank, variant).damage());
    }
    @EventHandler public void quit(PlayerQuitEvent event) { hud.remove(event.getPlayer().getUniqueId()); }
    static boolean hostile(LivingEntity entity) {
        if (entity == null || entity instanceof Player || entity instanceof Tameable || entity instanceof Boss
                || entity instanceof Warden || entity instanceof ElderGuardian || entity instanceof ArmorStand || entity.hasMetadata("NPC")) return false;
        return entity instanceof Monster || entity instanceof Slime || entity instanceof Phantom || entity instanceof Ghast;
    }
    // При выключении модуля загруженные мобы возвращаются к своим базовым атрибутам.
    void stop() {
        enabled = false; generation++; hud.clear();
        for (var world : Bukkit.getWorlds()) for (var entity : world.getLivingEntities())
            if (entity.getPersistentDataContainer().has(level, PersistentDataType.INTEGER)) strip(entity);
    }
}
