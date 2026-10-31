package ru.cherdakmd.chrdkartifacts;

import ru.cherdakmd.chrdkregister.api.PassivePotions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;
import java.util.Set;

/**
 * Пульс пассивных артефактов: каждые {@code general.passive-refresh-ticks} тиков
 * перевешивает зельевые эффекты носителям (длительность с запасом — эффект не моргает)
 * и крутит случайные триггеры проклятий (поджог/яд).
 *
 * <p>1.4.129: тот же пульс держит уникальные свойства артефактов — атрибутные
 * модификаторы ставит и снимает {@link ArtifactTraitService}. Свойства работают
 * и у активных артефактов: они «фон» предмета, а не его ПКМ-способность.</p>
 */
public final class PassiveEffectsService {

    /** Ид «Глиняной души» — единственный артефакт с атрибутным лимитом прыжка. */
    static final String JUMP_WARD_ID = "glinyanaya-dusha";
    private static final int IGNITE_TICKS = 80;
    private static final int POISON_TICKS = 160;

    private final ArtifactsPlugin plugin;
    private final Random random = new Random();
    private final JumpLimiter jumpLimiter;
    private BukkitTask task;

    public PassiveEffectsService(ArtifactsPlugin plugin) {
        this.plugin = plugin;
        this.jumpLimiter = new JumpLimiter(plugin);
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::pulse,
                plugin.config().refreshTicks(), plugin.config().refreshTicks());
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        PassivePotions.cancel(plugin);
        // Лимит прыжка — модификатор игрока, а не эффект: без снятия он держался бы
        // до конца сессии. Снимаем у всех, кто онлайн. Свойства артефактов тоже.
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearAttributes(player);
        }
    }

    /** Снимает только принадлежащие нам атрибуты, не удаляя чужие зелья. */
    public void clearAttributes(Player player) {
        PassivePotions.replace(plugin, player, java.util.List.of(), plugin.config().refreshTicks());
        PassivePotions.replace(plugin, "traits", player, java.util.List.of(), plugin.config().refreshTicks());
        jumpLimiter.clear(player);
        if (plugin.traits() != null) plugin.traits().clear(player);
    }

    /** Событийная синхронизация не запускает проклятия, лечение и мировые эффекты. */
    public void refreshAttributes(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!canApply(player)) {
            clearAttributes(player);
            return;
        }
        plugin.items().refreshCarriedLore(player);
        if (plugin.config().isEnabled(JUMP_WARD_ID)
                && plugin.items().carriedIds(player).contains(JUMP_WARD_ID)) {
            jumpLimiter.apply(player);
        } else {
            jumpLimiter.clear(player);
        }
        var traits = plugin.items().carriedTraitLevels(player);
        plugin.traits().applyAttributes(player, traits);
        // События инвентаря меняют только непрерывные эффекты, не лечение/проклятия/сытость.
        java.util.List<PotionEffect> continuous = new java.util.ArrayList<>();
        for (String id : plugin.items().carriedIds(player)) {
            ArtifactDef def = ArtifactRegistry.byId(id);
            if (def == null || def.mode() != ArtifactDef.Mode.PASSIVE || !plugin.config().isEnabled(id)) continue;
            for (ArtifactEffects.PotionSpec spec : ArtifactEffects.passivePotions(id)) {
                PotionEffectType type = Potions.resolve(spec.type());
                if (type != null && !type.isInstant()) continuous.add(new PotionEffect(type, 1, spec.amplifier(), true, false, true));
            }
        }
        PassivePotions.replace(plugin, player, continuous, plugin.config().refreshTicks());
        plugin.traits().refreshPotions(player, traits);
    }

    private boolean canApply(Player player) {
        return !player.isDead() && player.hasPermission("chrdkartifacts.use")
                && (!plugin.config().requiresAuth()
                || (plugin.coreApi() != null && plugin.coreApi().isAuthenticated(player)));
    }

    private void pulse() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshAttributes(player);
            if (!canApply(player)) continue;
            Set<String> ids = plugin.items().carriedIds(player);
            for (String id : ids) {
                // Проклятие выше могло вызвать смерть в этом же пульсе.
                if (!canApply(player)) break;
                ArtifactDef def = ArtifactRegistry.byId(id);
                if (def == null || def.mode() != ArtifactDef.Mode.PASSIVE
                        || !plugin.config().isEnabled(id)) {
                    continue;
                }
                applyPotions(player, id);
                rollTrigger(player, id);
                runSpecial(player, id);
            }
        }
    }

    private void applyPotions(Player player, String id) {
        int refresh = plugin.config().refreshTicks();
        for (ArtifactEffects.PotionSpec spec : ArtifactEffects.passivePotions(id)) {
            PotionEffectType type = Potions.resolve(spec.type());
            if (type == null || !type.isInstant()) {
                continue; // непрерывные эффекты уже переданы общему владельцу
            }
            int duration = PotionRefresh.durationTicks(refresh, type.isInstant());
            if (!shouldApplyPotion(player, type, spec.amplifier(), duration)) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, duration,
                    spec.amplifier(), true, false, true));
        }
    }

    /** Не понижаем и не укорачиваем эффект, полученный игроком извне. */
    private static boolean shouldApplyPotion(Player player, PotionEffectType type,
                                             int amplifier, int duration) {
        PotionEffect current = player.getPotionEffect(type);
        if (current == null) {
            return true;
        }
        return PotionRefresh.shouldApply(true, current.getAmplifier(),
                current.getDuration(), amplifier, duration);
    }

    private void rollTrigger(Player player, String id) {
        ArtifactEffects.TriggerSpec trigger = ArtifactEffects.passiveTrigger(id);
        if (trigger == null || random.nextDouble() >= trigger.chancePerCycle()) {
            return;
        }
        switch (trigger.kind()) {
            case IGNITE -> {
                player.setFireTicks(IGNITE_TICKS);
                curseNotice(player, id, "curse-ignite");
            }
            case POISON -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                        POISON_TICKS, 0, true, false, true));
                curseNotice(player, id, "curse-poison");
            }
            case BLEED -> {
                if (player.getHealth() > 1.0) {
                    player.damage(1.0);
                    curseNotice(player, id, "curse-bleed");
                }
            }
            case WATER_HURT -> {
                if (player.isInWater()) {
                    player.damage(1.0);
                    curseNotice(player, id, "curse-water");
                }
            }
            case BLINDNESS_PROC -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                        60, 0, true, false, true));
                curseNotice(player, id, "curse-blind");
            }
            case XP_DRAIN -> {
                if (player.getTotalExperience() > 10) {
                    player.giveExp(-10);
                    curseNotice(player, id, "curse-xp");
                }
            }
            case WITHER_PROC -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                        80, 0, true, false, true));
                curseNotice(player, id, "curse-wither");
            }
        }
    }

    /**
     * Сообщение о сработавшем проклятии (1.4.130, аудит A-21).
     *
     * <p>Раньше каждое срабатывание писало строку в чат: при шансе до 15 %
     * каждые два секунды чат превращался в поток. Теперь первое сообщение
     * уходит в чат, а повторы в пределах {@code feedback.curse-message-seconds}
     * — только короткой строкой над хотбаром.</p>
     */
    private void curseNotice(Player player, String id, String key) {
        if (plugin.feedback().allowCurseChat(player, id)) {
            plugin.messages().send(player, key);
            return;
        }
        plugin.feedback().curse(player, ArtifactRegistry.byId(id));
    }

    /** Пульсовая половина суперспособностей: Магнит Опыта и Ледяная Аура. */
    private void runSpecial(Player player, String id) {
        ArtifactEffects.SpecialKind special = ArtifactEffects.passiveSpecial(id);
        if (special == null) {
            return;
        }
        switch (special) {
            case XP_MAGNET -> {
                int pulled = 0;
                for (org.bukkit.entity.Entity entity : player.getNearbyEntities(8, 5, 8)) {
                    if (entity instanceof org.bukkit.entity.ExperienceOrb orb && !orb.isDead()) {
                        orb.teleport(player.getLocation());
                        if (++pulled >= 12) {
                            break;
                        }
                    }
                }
            }
            case FROST_AURA -> {
                for (org.bukkit.entity.Entity entity : player.getNearbyEntities(8, 5, 8)) {
                    if (entity instanceof org.bukkit.entity.Monster mob && !mob.isDead()) {
                        mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                                100, 1, true, false, true));
                        mob.setFreezeTicks(Math.max(mob.getFreezeTicks(), 40));
                    }
                }
            }
            case JUMP_WARD -> {
                // атрибут ставится общим проходом пульса (jumpLimiter.apply) — здесь только учёт
            }
            default -> {
                // PHOENIX_REVIVE/SOUL_THIEF/THORNS_RETALIATE обслуживает ArtifactEventListener
            }
        }
    }
}
