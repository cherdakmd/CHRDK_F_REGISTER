package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Новые чары изолированы от старых: финальная отмена, снимок выстрела, один эффект/общий persistent cooldown. */
final class PveEffectsListener implements Listener {
    private static final int MAX_SHOTS = 4096;
    private static final int MAX_PENDING = 4096;
    private record Choice(PveEnchant enchant, int level) { }
    private record Shot(UUID owner, UUID world, long expires, Choice choice, EntityShootBowEvent source) { }
    private final EnchantsPlugin plugin;
    private final NamespacedKey ready;
    private final Map<UUID, Shot> shots = new HashMap<>();
    private final Map<UUID, UUID> sessions = new HashMap<>();
    private final BukkitTask sweep;
    private int pending;

    PveEffectsListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
        ready = new NamespacedKey(plugin, "pve_ready_at");
        sweep = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            shots.entrySet().removeIf(entry -> entry.getValue().expires < now
                    || Bukkit.getEntity(entry.getKey()) == null);
        }, 200L, 200L);
    }
    void reload() { shots.clear(); sessions.clear(); } // Перезарядка в PDC игрока не сбрасывается.
    void stop() { sweep.cancel(); shots.clear(); sessions.clear(); }
    private boolean enabled() { return plugin.getConfig().getBoolean("pve-effects.enabled", true); }
    private boolean eligible(Player player) {
        return enabled() && player.isOnline() && !player.isDead() && player.getGameMode() == GameMode.SURVIVAL
                && player.hasPermission("chrdkenchants.use") && plugin.coreApi() != null
                && plugin.coreApi().isAuthenticated(player);
    }
    static boolean hostile(LivingEntity target) {
        if (target == null || target instanceof Player || target instanceof Tameable || target instanceof ArmorStand
                || target instanceof Boss || target instanceof Warden || target instanceof ElderGuardian
                || target.hasMetadata("NPC") || target.customName() != null) return false;
        return target instanceof Monster || target instanceof Slime || target instanceof Ghast || target instanceof Phantom;
    }
    private Choice choose(PveEnchant.Channel channel, ItemStack... items) {
        Choice best = null;
        for (PveEnchant enchant : PveEnchant.values()) {
            if (enchant.channel != channel || !plugin.config().isEnabled(enchant.id)) continue;
            int level = 0;
            for (ItemStack item : items) {
                if (item == null || !EnchantRegistry.byId(enchant.id).supports(item.getType().name())) continue;
                level = Math.max(level, plugin.items().read(item).getOrDefault(enchant.id, 0));
            }
            if (level > 0) level = enchant.level(level);
            if (level > 0 && (best == null || level > best.level)) best = new Choice(enchant, level);
        }
        return best;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !eligible(player) || event.getBow() == null
                || !(event.getProjectile() instanceof AbstractArrow arrow) || arrow instanceof Trident || shots.size() >= MAX_SHOTS) return;
        Choice choice = choose(PveEnchant.Channel.RANGED, event.getBow());
        if (choice != null) shots.put(arrow.getUniqueId(), new Shot(player.getUniqueId(), player.getWorld().getUID(),
                System.currentTimeMillis() + 30000, choice, event));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (!enabled() || event.getFinalDamage() <= 0 || pending >= MAX_PENDING) return;
        if (event.getDamager() instanceof Player player && eligible(player)
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getEntity() instanceof LivingEntity target && hostile(target)) {
            ItemStack weapon = player.getInventory().getItemInMainHand().clone();
            Choice choice = choose(PveEnchant.Channel.MELEE, weapon);
            if (choice != null) after(event, player, target, choice, weapon, null, null, null);
        } else if (event.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player player
                && eligible(player) && event.getEntity() instanceof LivingEntity target && hostile(target)) {
            Shot shot = shots.remove(arrow.getUniqueId()); // Не более одного броска на стрелу, в том числе с Пронзанием.
            if (shot != null && shot.owner.equals(player.getUniqueId()) && shot.expires >= System.currentTimeMillis()
                    && shot.world.equals(player.getWorld().getUID()))
                after(event, player, target, shot.choice, null, null, shot, arrow.getUniqueId());
        } else if (event.getEntity() instanceof Player player && eligible(player)) {
            LivingEntity target = event.getDamager() instanceof LivingEntity living ? living
                    : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter ? shooter : null;
            if (!hostile(target)) return;
            ItemStack[] armor = Arrays.stream(player.getInventory().getArmorContents())
                    .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
            Choice choice = choose(PveEnchant.Channel.GUARD, armor);
            if (choice != null) after(event, player, target, choice, null, armor, null, null);
        }
    }
    private void after(EntityDamageByEntityEvent event, Player owner, LivingEntity target, Choice choice,
                       ItemStack weapon, ItemStack[] armor, Shot shot, UUID projectile) {
        if (owner.getPersistentDataContainer().getOrDefault(ready, PersistentDataType.LONG, 0L) > System.currentTimeMillis()) return;
        long generation = plugin.sourceGeneration();
        UUID world = owner.getWorld().getUID();
        UUID session = sessions.computeIfAbsent(owner.getUniqueId(), ignored -> UUID.randomUUID());
        pending++;
        Bukkit.getScheduler().runTask(plugin, () -> {
            pending--;
            if (!session.equals(sessions.get(owner.getUniqueId())) || generation != plugin.sourceGeneration() || event.isCancelled() || event.getFinalDamage() <= 0
                    || !eligible(owner) || !target.isValid() || target.isDead() || !hostile(target)
                    || !world.equals(owner.getWorld().getUID()) || !world.equals(target.getWorld().getUID())
                    || !plugin.config().isEnabled(choice.enchant.id)) return;
            if (weapon != null && !weapon.equals(owner.getInventory().getItemInMainHand())) return;
            if (armor != null && !Arrays.equals(armor, owner.getInventory().getArmorContents())) return;
            if (shot != null && (shot.source.isCancelled() || shot.expires < System.currentTimeMillis()
                    || !shot.source.getProjectile().getUniqueId().equals(projectile))) return;
            long now = System.currentTimeMillis();
            if (owner.getPersistentDataContainer().getOrDefault(ready, PersistentDataType.LONG, 0L) > now) return;
            PotionEffectType type = choice.enchant.effect.equals("SLOWNESS") ? PotionEffectType.SLOWNESS : PotionEffectType.WEAKNESS;
            if (target.hasPotionEffect(type) || plugin.service().random().nextInt(100) >= choice.enchant.chance(choice.level)) return;
            if (target.addPotionEffect(new PotionEffect(type, choice.enchant.ticks(choice.level), 0, false, true, true)))
                owner.getPersistentDataContainer().set(ready, PersistentDataType.LONG, now + choice.enchant.cooldownSeconds * 1000L);
        });
    }
    private void forget(Player player) { sessions.remove(player.getUniqueId()); shots.entrySet().removeIf(entry -> entry.getValue().owner.equals(player.getUniqueId())); }
    @EventHandler public void quit(PlayerQuitEvent event) { forget(event.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent event) { forget(event.getEntity()); }
    @EventHandler public void world(PlayerChangedWorldEvent event) { forget(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void mode(PlayerGameModeChangeEvent event) { forget(event.getPlayer()); }
}
