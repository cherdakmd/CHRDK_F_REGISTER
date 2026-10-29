package ru.cherdakmd.chrdkskills;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Активные окна эфемерны, цена и таймеры — в отдельном устойчивом хранилище. */
final class SkillAbilities implements Listener {
    private static final int MAX_ARROW_MARKS = 4096;
    private final SkillsPlugin plugin;
    private final SkillAbilityStore store;
    private final Map<UUID, Armed> armed = new HashMap<>();
    private final Map<UUID, Wear> wear = new HashMap<>();
    private final Map<UUID, Shot> arrows = new HashMap<>();
    private record Armed(SkillAbility ability, long until, UUID token, UUID world) { }
    private record Wear(BlockBreakEvent event, ItemStack tool, int tick) { }
    private record Shot(UUID owner, UUID token, long until, EntityShootBowEvent source) { }

    SkillAbilities(SkillsPlugin plugin, SkillAbilityStore store) { this.plugin = plugin; this.store = store; }

    boolean eligible(Player player) {
        if (player == null || !player.isOnline() || player.isDead() || player.getGameMode() != GameMode.SURVIVAL
                || !plugin.config().enabled() || !plugin.config().abilitiesEnabled()
                || !player.hasPermission("chrdkskills.use") || plugin.coreApi() == null || !store.available()) return false;
        return plugin.coreApi().isAuthenticated(player);
    }
    private Armed active(Player player) {
        Armed value = player == null ? null : armed.get(player.getUniqueId());
        if (value == null) return null;
        if (value.until <= System.currentTimeMillis() || !eligible(player)
                || !value.world.equals(player.getWorld().getUID()) || !plugin.config().skill(value.ability.skill()).enabled()
                || plugin.skills().level(player, value.ability.skill()) < SkillAbility.UNLOCK_LEVEL) {
            forget(player.getUniqueId()); return null;
        }
        return value;
    }
    int energy(UUID id) { return store.energy(id); }
    long cooldown(UUID id, SkillAbility ability) { return store.cooldownLeft(id, ability, System.currentTimeMillis()); }
    boolean holds(Player player, SkillAbility ability) {
        return ability.acceptsTool(player.getInventory().getItemInMainHand().getType().name())
                || (ability == SkillAbility.DEFENSE && ability.acceptsTool(player.getInventory().getItemInOffHand().getType().name()));
    }
    void activate(Player player, SkillAbility ability) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Ability activation requires server thread");
        if (!eligible(player)) { plugin.messages().send(player, "ability-unavailable"); return; }
        if (ability == null) { plugin.messages().send(player, "ability-no-skill"); return; }
        if (!plugin.config().skill(ability.skill()).enabled()
                || plugin.skills().level(player, ability.skill()) < SkillAbility.UNLOCK_LEVEL) {
            plugin.messages().send(player, "ability-locked", "level", SkillAbility.UNLOCK_LEVEL); return;
        }
        if (active(player) != null) { plugin.messages().send(player, "ability-already-active"); return; }
        if (!holds(player, ability)) { plugin.messages().send(player, "ability-tool"); return; }
        long now = System.currentTimeMillis();
        String error = store.activate(player.getUniqueId(), ability, now);
        if (!error.isEmpty()) {
            plugin.messages().send(player, error, "time", cooldown(player.getUniqueId(), ability), "cost", ability.cost()); return;
        }
        armed.put(player.getUniqueId(), new Armed(ability, now + ability.seconds() * 1000L, UUID.randomUUID(), player.getWorld().getUID()));
        plugin.messages().send(player, "ability-activated", "name", plugin.messages().get(ability.nameKey()),
                "seconds", ability.seconds(), "energy", energy(player.getUniqueId()));
    }
    void status(Player player) {
        plugin.messages().send(player, "ability-energy", "energy", energy(player.getUniqueId()), "max", SkillAbility.MAX_ENERGY);
    }
    void pulse() {
        long now = System.currentTimeMillis();
        arrows.entrySet().removeIf(e -> e.getValue().until <= now);
        wear.entrySet().removeIf(e -> e.getValue().tick != Bukkit.getCurrentTick());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!eligible(player)) { forget(player.getUniqueId()); continue; }
            store.regenerate(player.getUniqueId());
            Armed value = active(player);
            if (value != null && plugin.config().abilitiesHud()) player.sendActionBar(plugin.messages().get("ability-hud",
                    "energy", energy(player.getUniqueId()), "name", plugin.messages().get(value.ability.nameKey()),
                    "seconds", Math.max(1, (value.until - now + 999) / 1000)));
        }
    }
    void clear() { armed.clear(); wear.clear(); arrows.clear(); }
    private void forget(UUID id) {
        armed.remove(id); wear.remove(id); arrows.entrySet().removeIf(e -> e.getValue().owner.equals(id));
    }

    /** Вызывается только после штатной проверки поставленных блоков и материала. */
    void noteBreak(BlockBreakEvent event, SkillType skill) {
        Armed value = active(event.getPlayer());
        if (value == null || !value.ability.gathering() || value.ability.skill() != skill
                || !holds(event.getPlayer(), value.ability)) return;
        wear.put(event.getPlayer().getUniqueId(), new Wear(event,
                event.getPlayer().getInventory().getItemInMainHand().clone(), Bukkit.getCurrentTick()));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWear(PlayerItemDamageEvent event) {
        Wear context = wear.remove(event.getPlayer().getUniqueId());
        Armed value = active(event.getPlayer());
        if (context == null || value == null || !value.ability.gathering() || context.event.isCancelled()
                || context.tick != Bukkit.getCurrentTick() || !context.tool.isSimilar(event.getItem())) return;
        // Не ремонт и не бесплатный опыт: не расходуем одну единицу обычного износа этого блока.
        event.setDamage(Math.max(0, event.getDamage() - 1));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getProjectile() instanceof AbstractArrow arrow)) return;
        Armed value = active(player);
        if (value == null || value.ability != SkillAbility.ARCHERY || arrows.size() >= MAX_ARROW_MARKS) return;
        arrows.put(arrow.getUniqueId(), new Shot(player.getUniqueId(), value.token, value.until, event));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getFinalDamage() <= 0) return;
        if (event.getDamager() instanceof Player player && event.getEntity() instanceof LivingEntity target && hostile(target)) {
            Armed value = active(player);
            if (value != null && value.ability == SkillAbility.COMBAT && holds(player, value.ability))
                applyAfter(event, player, target, value, PotionEffectType.WEAKNESS, 60);
        } else if (event.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player player
                && event.getEntity() instanceof LivingEntity target && hostile(target)) {
            Armed value = active(player); Shot shot = arrows.get(arrow.getUniqueId());
            if (value != null && value.ability == SkillAbility.ARCHERY && shot != null
                    && !shot.source.isCancelled() && shot.owner.equals(player.getUniqueId()) && shot.token.equals(value.token))
                applyAfter(event, player, target, value, PotionEffectType.GLOWING, 160);
        } else if (event.getEntity() instanceof Player player) {
            LivingEntity attacker = event.getDamager() instanceof LivingEntity living ? living
                    : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter ? shooter : null;
            Armed value = active(player);
            if (value != null && value.ability == SkillAbility.DEFENSE && holds(player, value.ability) && hostile(attacker))
                applyAfter(event, player, attacker, value, PotionEffectType.SLOWNESS, 60);
        }
    }
    private void applyAfter(EntityDamageByEntityEvent event, Player owner, LivingEntity target, Armed expected, PotionEffectType type, int ticks) {
        // Последняя проверка отмены после всех обработчиков; не меняем урон и не создаём второй удар.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Armed current = active(owner);
            if (current == null || !current.token.equals(expected.token) || event.isCancelled() || event.getFinalDamage() <= 0
                    || !target.isValid() || target.isDead() || !hostile(target)
                    || !target.getWorld().getUID().equals(expected.world) || target.hasPotionEffect(type)) return;
            if (target.addPotionEffect(new PotionEffect(type, ticks, 0, false, true, true))) forget(owner.getUniqueId());
        });
    }
    static boolean hostile(LivingEntity entity) {
        if (entity == null || entity instanceof Player || entity instanceof Tameable || entity instanceof ArmorStand
                || entity.hasMetadata("NPC")) return false;
        return entity instanceof Monster || entity instanceof Slime || entity instanceof Phantom
                || entity instanceof Ghast || entity instanceof Boss;
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { forget(event.getPlayer().getUniqueId()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { forget(event.getEntity().getUniqueId()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent event) { forget(event.getPlayer().getUniqueId()); }
    @EventHandler(ignoreCancelled = true) public void onMode(PlayerGameModeChangeEvent event) { forget(event.getPlayer().getUniqueId()); }
}
