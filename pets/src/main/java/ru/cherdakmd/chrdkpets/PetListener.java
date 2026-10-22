package ru.cherdakmd.chrdkpets;

import org.bukkit.Bukkit;
import org.bukkit.entity.AnimalTamer;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.TimeSkipEvent;

/**
 * События мира: приручение, загрузка чанков, смерть, опыт и аура.
 *
 * <p>Опыт начисляется только за <b>враждебных</b> мобов ({@link Monster}) и
 * только за честное участие: волк сам добил моба либо хозяин добил его, пока
 * питомец был рядом. Животные, игроки и чужие питомцы опыта не дают — иначе
 * ближайшая ферма кур стала бы бесконечной прокачкой. Сверху стоит минутный
 * лимит из {@code xp.max-per-minute}.</p>
 */
public final class PetListener implements Listener {

    private final PetsPlugin plugin;

    PetListener(PetsPlugin plugin) {
        this.plugin = plugin;
    }

    /* --------------------------------------------------------------- приручение */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!plugin.config().enabled() || !(event.getEntity() instanceof Tameable)) {
            return;
        }
        LivingEntity entity = event.getEntity();
        AnimalTamer tamer = event.getOwner();
        // Владелец выставляется до события, но подстрахуемся следующим тиком:
        // на некоторых сборках getOwnerUniqueId() ещё пуст в момент вызова.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!entity.isValid()) {
                return;
            }
            if (plugin.service().adopt(entity) && tamer instanceof Player player) {
                PetStore.PetData data = plugin.service().data(entity);
                plugin.messages().send(player, "adopted",
                        "name", plugin.service().displayName(data));
            }
        });
    }

    /* ------------------------------------------------------------- загрузка чанка */

    /**
     * Питомцы, приручённые до установки модуля, подбираются при загрузке чанка.
     * Для уже известных сущностей это просто перестановка бонусов и подписи.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.config().enabled()) {
            return;
        }
        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof Tameable tameable) || !tameable.isTamed()) {
                continue;
            }
            try {
                plugin.service().adopt(entity);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Питомец в чанке " + event.getChunk().getX()
                        + "," + event.getChunk().getZ() + " не принят: "
                        + exception.getMessage());
            }
        }
    }

    /* -------------------------------------------------------------------- смерть */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (plugin.service().isPet(victim)) {
            plugin.service().onDeath(victim);
            return;
        }
        if (!(victim instanceof Monster)) {
            return;
        }
        Entity damager = damagerOf(victim);
        if (damager == null) {
            return;
        }
        PetStore.PetData pet = plugin.service().data(damager);
        if (pet != null) {
            if (damager instanceof LivingEntity living) {
                plugin.service().addXp(living, plugin.config().xp().perKill());
                plugin.service().noteKill(pet);
            }
            return;
        }
        if (damager instanceof Player player) {
            LivingEntity nearby = plugin.service().nearestOwned(player, plugin.config().xpRadius());
            if (nearby != null) {
                plugin.service().addXp(nearby, plugin.config().xp().perAssist());
            }
        }
    }

    /* ------------------------------------------------------------------ рыбалка */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        LivingEntity pet = plugin.service().nearestOwned(event.getPlayer(),
                plugin.config().xpRadius());
        if (pet != null) {
            plugin.service().addXp(pet, plugin.config().xp().perFish());
            plugin.service().noteFish(plugin.service().data(pet));
        }
    }

    /* -------------------------------------------------------------------- ночь */

    /**
     * Утренний опыт за сон: ванильная кошка приносит подарок, а здесь — ещё и
     * опыт. Событие срабатывает один раз на пропуск ночи, поэтому ферма из
     * кроватей не получится: пропускать ночь чаще, чем раз в сутки, нельзя.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent event) {
        if (event.getSkipReason() != TimeSkipEvent.SkipReason.NIGHT_SKIP) {
            return;
        }
        for (Player player : event.getWorld().getPlayers()) {
            if (!player.isSleeping()) {
                continue;
            }
            LivingEntity pet = plugin.service().nearestOwned(player, plugin.config().xpRadius());
            if (pet != null) {
                plugin.service().addXp(pet, plugin.config().xp().perSleep());
            }
        }
    }

    /* --------------------------------------------------------------- вход/выход */

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.store().touchOwnerName(player.getUniqueId(), player.getName());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                plugin.aura().refresh(player);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.aura().clear(event.getPlayer());
    }

    /* -------------------------------------------------------------------- бой */

    /**
     * Любой урон игрока блокирует {@code /pet call} на несколько секунд: иначе
     * питомца можно было бы телепортировать прямо из драки как эвакуатор.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.service().noteCombat(player);
        }
    }

    /* --------------------------------------------------------------- внутреннее */

    /** Кто нанёс последний удар; стрела и трезубец разворачиваются во владельца. */
    private static Entity damagerOf(LivingEntity victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent byEntity)) {
            return null;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }
}
