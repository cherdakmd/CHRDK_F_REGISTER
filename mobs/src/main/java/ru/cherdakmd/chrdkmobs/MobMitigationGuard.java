package ru.cherdakmd.chrdkmobs;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Мобы всегда опасны: суммарное снижение входящего урона плагинами (классы,
 * навыки, Кузня, щиты) ограничено потолком, который падает с тиром моба —
 * высшие тиры пробивают больше защиты.
 *
 * <p>Исходный урон фиксируется на LOWEST, до всех редукторов (они работают на
 * NORMAL..HIGHEST), а на MONITOR суммарное снижение зажимается потолком тира
 * ({@link MobTierMath#mitigationCap}). Ванильная броня и зачарования работают
 * после события и в потолок не входят: полный билд защищён, но толпа мобов
 * высшего тира убивает.</p>
 */
final class MobMitigationGuard implements Listener {
    private static final int MAX_TRACKED = 4096;
    private final MobsPlugin plugin;
    private final NamespacedKey tierKey;
    /** Исходный урон события до редукторов; запись снимается на MONITOR. */
    private final Map<EntityDamageByEntityEvent, Double> originals = new IdentityHashMap<>();

    MobMitigationGuard(MobsPlugin plugin) {
        this.plugin = plugin;
        tierKey = new NamespacedKey(plugin, "tier-v1");
    }

    /** Запоминаем урон до того, как его снизят классы, навыки, Кузня и щиты. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onOriginal(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || mobAttacker(event.getDamager()) == null) {
            return;
        }
        if (originals.size() >= MAX_TRACKED) {
            originals.clear();
        }
        originals.put(event, event.getDamage());
    }

    /** Суммарное снижение сверх потолка возвращается — толпа мобов убивает. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClamp(EntityDamageByEntityEvent event) {
        Double original = originals.remove(event);
        if (original == null || original <= 0.0 || event.isCancelled()) {
            return;
        }
        LivingEntity mob = mobAttacker(event.getDamager());
        if (!(event.getEntity() instanceof Player player) || mob == null) {
            return;
        }
        int tier = tierOf(mob);
        MobsConfig.Mitigation mitigation = plugin.config().mitigation();
        // Стойкость компенсирует пробивание высших тиров, но потолок жёсткий.
        double cap = Math.min(0.9, MobTierMath.mitigationCap(tier,
                mitigation.cap(), mitigation.perTierStep(), mitigation.floor())
                + SkillsHook.mitigationBonus(plugin.getLogger(), player));
        double damage = Math.max(event.getDamage(), original * (1.0 - cap));
        // Давление тира: недобор атрибутов зоны усиливает урон моба.
        damage *= 1.0 + SkillsHook.pressure(plugin.getLogger(), player, tier);
        event.setDamage(damage);
    }

    /** Тир моба из метки тирующего слушателя: без метки моб считается первым тиром. */
    private int tierOf(LivingEntity mob) {
        Integer tier = mob.getPersistentDataContainer().get(tierKey, PersistentDataType.INTEGER);
        return tier == null ? 1 : Math.max(1, tier);
    }

    /** Моб-вредитель: прямой или снаряд, выпущенный мобом. */
    private static LivingEntity mobAttacker(Entity entity) {
        if (entity instanceof LivingEntity living && !(living instanceof Player)) {
            return living;
        }
        if (entity instanceof Projectile shot && shot.getShooter() instanceof LivingEntity living
                && !(living instanceof Player)) {
            return living;
        }
        return null;
    }
}
