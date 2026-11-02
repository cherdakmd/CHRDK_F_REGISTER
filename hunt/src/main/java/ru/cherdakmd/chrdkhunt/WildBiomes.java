package ru.cherdakmd.chrdkhunt;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import ru.cherdakmd.chrdkregister.api.MobRewardPolicy;

/** Редкие натуральные разновидности. Обычные HP/броня/урон, одна уклоняемая особенность.
 * Нет постоянных атрибутных модификаторов и наложения существующей элиты. */
final class WildBiomes implements Listener {
    private static final class State {
        final WeakReference<Mob> mob; final WildVariant variant;
        UUID target; Vector aim; long next, windup, primed, chase, ignore, recovery;
        State(Mob mob, WildVariant variant, long tick) { this.mob = new WeakReference<>(mob); this.variant = variant; next = tick + 160; }
    }
    private final HuntPlugin plugin;
    private final WildSafety safety;
    private final NamespacedKey variantKey, pendingKey, projectileKey;
    private final Map<UUID, State> states = new LinkedHashMap<>();
    private final Map<UUID, WeakReference<Mob>> pending = new HashMap<>();
    private final MobTaskQueue replacements;
    private final BukkitTask clock;
    private boolean enabled, stopped;
    private int cap, protection;
    private double chance;
    private long tick;
    WildBiomes(HuntPlugin plugin, WildSafety safety) {
        this.plugin = plugin; this.safety = safety;
        variantKey = new NamespacedKey(plugin, "wild_variant"); pendingKey = new NamespacedKey(plugin, "wild_pending");
        projectileKey = new NamespacedKey(plugin, "wild_arrow");
        replacements = new MobTaskQueue(action -> { var t = Bukkit.getScheduler().runTask(plugin, action); return t::cancel; });
        reload(); clock = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 5, 5);
    }
    void reload() {
        replacements.clear(); clearPending();
        var c = plugin.getConfig(); enabled = c.getBoolean("wild-biomes.enabled", true);
        cap = Math.clamp(c.getInt("wild-biomes.global-cap", 32), 1, 64);
        chance = c.getDouble("wild-biomes.chance-percent", 1.5);
        chance = Double.isFinite(chance) ? Math.clamp(chance, 0, 3) : 1.5;
        protection = Math.clamp(c.getInt("wild-biomes.spawn-protection-radius", 128), 64, 4096);
        var trimmed = states.values().iterator();
        while (states.size() > cap && trimmed.hasNext()) { trimmed.next(); trimmed.remove(); }
        for (var s : states.values()) { s.windup = 0; s.primed = 0; s.next = tick + 160; }
    }
    private void clearPending() {
        for (var ref : pending.values()) { var mob = ref.get(); if (mob != null) mob.getPersistentDataContainer().remove(pendingKey); }
        pending.clear();
    }
    WildVariant kind(Entity entity) {
        if (!(entity instanceof Mob mob) || MobRewardPolicy.temporary(entity)) return null;
        var v = WildVariant.byId(mob.getPersistentDataContainer().get(variantKey, PersistentDataType.STRING));
        return v != null && mob.getType() == v.type ? v : null;
    }
    void loaded(Entity entity) {
        entity.getPersistentDataContainer().remove(pendingKey);
        WildVariant v = kind(entity);
        if (v != null && states.size() < cap && entity instanceof Mob mob) states.putIfAbsent(mob.getUniqueId(), new State(mob, v, tick));
    }
    private void mark(Mob mob, WildVariant v) {
        mob.getPersistentDataContainer().set(variantKey, PersistentDataType.STRING, v.id);
        mob.customName(plugin.messages().get("wild-name-" + v.id)); mob.setCustomNameVisible(false);
        mob.setRemoveWhenFarAway(true);
        states.put(mob.getUniqueId(), new State(mob, v, tick));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent e) {
        if (!enabled || stopped || e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(e.getEntity() instanceof Mob mob)
                || states.size() + pending.size() >= cap || !MobRewardPolicy.onlyNaturalProvenance(mob) || mob.customName() != null) return;
        EntityType type = mob.getType();
        if (type != EntityType.HUSK && type != EntityType.STRAY && type != EntityType.SPIDER && type != EntityType.DROWNED
                && type != EntityType.ZOMBIE && type != EntityType.HOGLIN && type != EntityType.ENDERMAN) return;
        if (ThreadLocalRandom.current().nextDouble(100) >= chance || !WildSafety.healthyTick(40) || !safety.clear(mob.getLocation(), protection)) return;
        var biome = mob.getLocation().getBlock().getBiome().getKey();
        if (!biome.getNamespace().equals("minecraft")) return;
        var v = WildVariant.habitat(type, mob.getWorld().getEnvironment(), biome.getKey());
        if (v == null) return;
        int nearby = 0;
        for (var s : states.values()) { var other = s.mob.get(); if (other != null && other.isValid() && other.getWorld().equals(mob.getWorld()) && other.getLocation().distanceSquared(mob.getLocation()) < 64 * 64) nearby++; }
        for (var ref : pending.values()) { var other = ref.get(); if (other != null && other.isValid() && other.getWorld().equals(mob.getWorld()) && other.getLocation().distanceSquared(mob.getLocation()) < 64 * 64) nearby++; }
        if (nearby >= 2) return;
        // В vanilla болото не порождает утопленника напрямую. Заменяем ОДНОГО уже подтверждённого
        // природного зомби, а не добавляем ещё одного моба и не принимаем DROWNED-конверсионные фермы.
        if (type == EntityType.ZOMBIE && v == WildVariant.BOG) {
            if (pending.size() >= 4) return;
            mob.getPersistentDataContainer().set(pendingKey, PersistentDataType.BYTE, (byte) 1);
            pending.put(mob.getUniqueId(), new WeakReference<>(mob));
            if (!replacements.submit(mob.getUniqueId(), "bog", () -> replaceBog(e, mob))) {
                pending.remove(mob.getUniqueId()); mob.getPersistentDataContainer().remove(pendingKey);
            }
        } else mark(mob, v);
    }
    private void replaceBog(CreatureSpawnEvent source, Mob original) {
        pending.remove(original.getUniqueId()); original.getPersistentDataContainer().remove(pendingKey);
        if (source.isCancelled() || !enabled || stopped || !original.isValid() || original.isDead()
                || states.size() >= cap || !WildSafety.healthyTick(40) || !safety.clear(original.getLocation(), protection)) return;
        Drowned replacement = original.getWorld().spawn(original.getLocation(), Drowned.class, CreatureSpawnEvent.SpawnReason.NATURAL, false, mob -> {
            mob.getPersistentDataContainer().set(variantKey, PersistentDataType.STRING, WildVariant.BOG.id);
            mob.setCanPickupItems(false);
            if (mob.getEquipment() != null) mob.getEquipment().clear();
        });
        if (replacement.isValid() && !replacement.isDead()) { original.remove(); mark(replacement, WildVariant.BOG); }
    }
    private boolean usable(Mob mob, Player p) {
        return enabled && plugin.config().enabled() && mob.isValid() && !mob.isDead() && mob.hasAI()
                && safety.target(p, protection) && safety.clear(mob.getLocation(), protection)
                && mob.getWorld().equals(p.getWorld()) && mob.getLocation().distanceSquared(p.getLocation()) <= 24 * 24;
    }
    private void observe(Player p, WildVariant v, boolean kill) {
        if (!enabled || !safety.player(p)) return;
        if (plugin.store().wild(p.getUniqueId(), p.getName(), v.id, kill)) plugin.requestSave();
    }
    private void sweep() {
        if (stopped) return;
        tick += 5;
        var iterator = states.values().iterator();
        while (iterator.hasNext()) {
            State s = iterator.next(); Mob mob = s.mob.get();
            if (mob == null || !mob.isValid() || mob.isDead() || kind(mob) != s.variant) { iterator.remove(); continue; }
            if (!enabled || !plugin.config().enabled()) { s.windup = 0; s.primed = 0; s.target = null; continue; }
            if (!(mob.getTarget() instanceof Player p)) { s.windup = 0; s.primed = 0; s.target = null; continue; }
            if (!usable(mob, p) || tick < s.ignore || (s.target != null && tick - s.chase >= 400)) {
                mob.setTarget(null); s.windup = 0; s.target = null; s.ignore = tick + 100; continue;
            }
            if (!p.getUniqueId().equals(s.target)) { s.windup = 0; s.primed = 0; s.target = p.getUniqueId(); s.chase = tick; observe(p, s.variant, false); }
            if (!WildSafety.healthyTick(40)) { s.windup = 0; s.primed = 0; continue; }
            if (s.windup > 0) {
                if (!mob.hasLineOfSight(p) || mob.getLocation().distanceSquared(p.getLocation()) > 12 * 12) { s.windup = 0; s.next = tick + 80; continue; }
                if (tick >= s.windup) { s.windup = 0; s.next = tick + 240; execute(mob, p, s); }
                continue;
            }
            double range = mob.getLocation().distanceSquared(p.getLocation());
            if (tick < s.next || range < 3 * 3 || range > 10 * 10 || !mob.hasLineOfSight(p)) continue;
            if (s.variant == WildVariant.BOG && (!mob.isInWater() || !p.isInWater())) continue;
            s.aim = p.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
            s.windup = tick + (s.variant == WildVariant.CRIMSON ? 25 : 20);
            // След виден только участнику, без массовой рассылки частиц всем игрокам мира.
            p.spawnParticle(s.variant == WildVariant.RIFT ? Particle.PORTAL : s.variant == WildVariant.FROST ? Particle.SNOWFLAKE : Particle.CRIT,
                    mob.getLocation().add(0, 1, 0), 10, .3, .3, .3, .02);
            p.sendActionBar(plugin.messages().get("wild-windup", "name", plugin.messages().mini("wild-name-" + s.variant.id)));
        }
    }
    private void execute(Mob mob, Player p, State s) {
        if (!usable(mob, p) || !mob.hasLineOfSight(p)) return;
        if (s.variant == WildVariant.FROST) { s.primed = tick + 40; return; }
        Vector toward = s.aim == null ? new Vector() : s.aim.clone();
        if (toward.lengthSquared() < .01) return;
        toward.normalize();
        if (s.variant == WildVariant.RIFT) {
            Vector side = new Vector(-toward.getZ(), 0, toward.getX()).multiply(ThreadLocalRandom.current().nextBoolean() ? 2.25 : -2.25);
            Location to = mob.getLocation().add(side);
            if (!safety.stand(to, protection) || !to.getWorld().getBlockAt(to.getBlockX(), to.getBlockY() + 2, to.getBlockZ()).getType().isAir()) return;
            if (mob.getWorld().rayTraceBlocks(mob.getLocation().add(0, 1, 0), side, side.length(), FluidCollisionMode.NEVER, true) != null) return;
            if (mob.teleport(to)) p.spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 12, .3, .5, .3, .02); return;
        }
        if (!mob.isOnGround() && s.variant != WildVariant.BOG) return;
        if (s.variant == WildVariant.BOG && (!mob.isInWater() || !p.isInWater())) return;
        // Короткий импульс, не преследующий homing: направление фиксируется в момент окончания предупреждения.
        Location ahead = mob.getLocation().add(toward.clone().multiply(2));
        if (!safety.clear(ahead, protection) || mob.getWorld().rayTraceBlocks(mob.getLocation().add(0, .6, 0), toward, 2, FluidCollisionMode.NEVER, true) != null) return;
        double speed = s.variant == WildVariant.CRIMSON ? .40 : .32;
        mob.setVelocity(toward.multiply(speed).setY(s.variant == WildVariant.THICKET ? .30 : s.variant == WildVariant.BOG ? .08 : .12));
        if (s.variant == WildVariant.CRIMSON) s.recovery = tick + 60;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bow(EntityShootBowEvent e) {
        if (kind(e.getEntity()) == WildVariant.FROST && e.getProjectile() instanceof Arrow shot) shot.getPersistentDataContainer().set(projectileKey, PersistentDataType.BYTE, (byte) 1);
        State s = states.get(e.getEntity().getUniqueId());
        if (s == null || s.variant != WildVariant.FROST || s.primed < tick || s.primed == 0
                || !(e.getEntity() instanceof Mob mob) || !(mob.getTarget() instanceof Player p)
                || !p.getUniqueId().equals(s.target) || !usable(mob, p) || !mob.hasLineOfSight(p) || !(e.getProjectile() instanceof Arrow arrow)) return;
        s.primed = 0;
        arrow.setBasePotionType(PotionType.WATER); arrow.clearCustomEffects();
        arrow.addCustomEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1), true);
        arrow.getPersistentDataContainer().set(projectileKey, PersistentDataType.BYTE, (byte) 1);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        if (!enabled || !plugin.config().enabled()) return;
        Entity source = e.getDamager();
        boolean wildShot = source instanceof Projectile shot && (shot.getPersistentDataContainer().has(projectileKey)
                || shot.getShooter() instanceof Entity shooter && kind(shooter) != null);
        if (source instanceof Projectile shot && shot.getShooter() instanceof Entity shooter) source = shooter;
        WildVariant v = kind(source);
        if ((v != null || wildShot) && e.getEntity() instanceof Player p) {
            if (!safety.target(p, protection) || v != null && !safety.clear(source.getLocation(), protection)) { e.setCancelled(true); return; }
            State state = states.get(source.getUniqueId());
            if (state != null && v == WildVariant.CRIMSON && state.recovery > tick) e.setDamage(e.getDamage() * .6);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void contact(EntityDamageByEntityEvent e) {
        var v = kind(e.getEntity());
        Player p = e.getDamager() instanceof Player player ? player : e.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player player ? player : null;
        if (v != null && safety.target(p, protection)) observe(p, v, false);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void death(EntityDeathEvent e) {
        var v = kind(e.getEntity()); Player p = e.getEntity().getKiller();
        if (v != null && safety.target(p, protection) && safety.clear(e.getEntity().getLocation(), protection)
                && e.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent hit && !hit.isCancelled()) {
            Player finisher = hit.getDamager() instanceof Player player ? player : hit.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Player player ? player : null;
            if (p.equals(finisher)) observe(p, v, true);
        }
        states.remove(e.getEntity().getUniqueId());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent e) {
        if (!enabled || !plugin.config().enabled() || kind(e.getEntity()) == null || !(e.getTarget() instanceof Player p)) return;
        State s = states.get(e.getEntity().getUniqueId());
        if (!safety.target(p, protection) || !safety.clear(e.getEntity().getLocation(), protection) || s != null && tick < s.ignore) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(EntityTeleportEvent e) {
        if (enabled && plugin.config().enabled() && kind(e.getEntity()) != null && !safety.clear(e.getTo(), protection)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void grief(EntityChangeBlockEvent e) { if (kind(e.getEntity()) != null) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void transform(EntityTransformEvent e) {
        WildVariant v = kind(e.getEntity());
        if (v == null) return;
        var name = e.getEntity().customName();
        replacements.submit(e.getEntity().getUniqueId(), "transform", () -> {
            if (e.isCancelled()) return;
            states.remove(e.getEntity().getUniqueId());
            for (Entity replacement : e.getTransformedEntities()) if (replacement.getType() != v.type && replacement.isValid()) {
                replacement.getPersistentDataContainer().remove(variantKey);
                replacement.getPersistentDataContainer().remove(pendingKey);
                if (Objects.equals(name, plugin.messages().get("wild-name-" + v.id)) && Objects.equals(replacement.customName(), name)) replacement.customName(null);
            }
        });
    }
    @EventHandler public void load(EntitiesLoadEvent e) { for (Entity entity : e.getEntities()) loaded(entity); }
    @EventHandler public void unload(EntitiesUnloadEvent e) { for (Entity entity : e.getEntities()) states.remove(entity.getUniqueId()); }
    void inspect(Player p) {
        Entity entity = p.getTargetEntity(16);
        var v = kind(entity);
        if (v != null && safety.target(p, protection) && safety.clear(entity.getLocation(), protection)) observe(p, v, false);
    }
    void stop() { stopped = true; clock.cancel(); replacements.stop(); clearPending(); states.clear(); }
}
