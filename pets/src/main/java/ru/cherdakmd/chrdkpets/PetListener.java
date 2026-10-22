package ru.cherdakmd.chrdkpets;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.TimeSkipEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * События мира, из которых модуль делает прогресс питомцев.
 *
 * <p>Опыт начисляется только за «честные» события: волк сам добил враждебного
 * моба, хозяин добил моба рядом с питомцем, хозяин поймал рыбу рядом, хозяин
 * проспал ночь. Приручение и загрузка чанков — точки приёма питомцев в базу;
 * смерть — точка памяти.</p>
 *
 * <p>Все обработчики стоят на MONITOR: модуль ничего не отменяет и не меняет
 * исход события, он только читает его результат.</p>
 */
public final class PetListener implements Listener {

    private final PetsPlugin plugin;
    /** Кто лёг спать в эту ночь: сон сбрасывается до события пропуска ночи. */
    private final Set<UUID> sleepers = ConcurrentHashMap.newKeySet();

    PetListener(PetsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Новый питомец приручён. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!(event.getEntity() instanceof Tameable tameable) || !tameable.isTamed()) {
            return;
        }
        plugin.service().adopt(event.getEntity());
    }

    /** Загрузка чанка: подбираем питомцев, приручённых до установки модуля. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.config().enabled()) {
            return;
        }
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Tameable tameable && tameable.isTamed()) {
                plugin.service().adopt(entity);
            }
        }
    }

    /** Смерть питомца: память и снятие ауры. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Tameable tameable && tameable.isTamed()
                && plugin.service().data(entity) != null) {
            plugin.service().onDeath(entity);
        }
    }

    /** Волк добил моба или хозяин добил моба рядом с питомцем. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Monster victim)) {
            return;
        }
        Entity damager = shooterOf(event.getDamager());
        if (damager == null) {
            return;
        }
        if (damager instanceof LivingEntity pet && plugin.service().data(pet) != null) {
            if (killed(victim, event)) {
                plugin.service().addXp(pet, plugin.config().xp().kill());
            }
            return;
        }
        if (damager instanceof Player player) {
            LivingEntity nearby = plugin.service().nearestOwned(player, plugin.config().xpRadius());
            if (nearby != null && killed(victim, event)) {
                plugin.service().addPeacefulXp(nearby, plugin.config().xp().assist());
            }
        }
    }

    /** Рыбалка: опыт кошке и волку за компанию. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        LivingEntity pet = plugin.service().nearestOwned(event.getPlayer(),
                plugin.config().xpRadius());
        if (pet != null) {
            plugin.service().addPeacefulXp(pet, plugin.config().xp().fish());
        }
    }

    /** Игрок лёг спать — запоминаем его к утренней награде. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            sleepers.add(event.getPlayer().getUniqueId());
        }
    }

    /**
     * Утренняя награда: хозяин проспал ночь, питомец был рядом.
     *
     * <p>Список спящих берётся из {@link PlayerBedEnterEvent}: к моменту
     * пропуска ночи ванильный сервер уже поднимает игроков, и
     * {@code isSleeping()} для них возвращает {@code false}.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent event) {
        if (event.getSkipReason() != TimeSkipEvent.SkipReason.NIGHT_SKIP) {
            return;
        }
        for (UUID id : List.copyOf(sleepers)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.getWorld().equals(event.getWorld())) {
                continue;
            }
            LivingEntity pet = plugin.service().nearestOwned(player, plugin.config().xpRadius());
            if (pet != null) {
                plugin.service().addPeacefulXp(pet, plugin.config().xp().sleep());
            }
        }
        sleepers.clear();
    }

    /** Вход: ауру считаем после того, как игрок окончательно появился в мире. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.aura().refresh(event.getPlayer()));
    }

    /** Выход: снимаем ауру, чтобы модификаторы не остались «висеть». */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.aura().clear(event.getPlayer());
        sleepers.remove(event.getPlayer().getUniqueId());
    }

    /** Добил ли этот удар моба: событие приходит до применения урона. */
    private static boolean killed(LivingEntity victim, EntityDamageByEntityEvent event) {
        return victim.isDead() || victim.getHealth() - event.getFinalDamage() <= 0.0;
    }

    /** Стрела, трезубец и прочие снаряды разворачиваются в стрелка. */
    private static Entity shooterOf(Entity damager) {
        if (damager instanceof Projectile projectile) {
            return projectile.getShooter() instanceof Entity shooter ? shooter : null;
        }
        return damager;
    }
}
