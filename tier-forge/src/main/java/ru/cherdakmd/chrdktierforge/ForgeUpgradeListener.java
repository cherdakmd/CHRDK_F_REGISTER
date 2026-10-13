package ru.cherdakmd.chrdktierforge;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Random;

/**
 * Событийная половина кузнечных свойств (1.4.55): вампиризм, крит, добивание,
 * кара по нежити, следопыт (опыт), терновник, стойкость, рудачир, закалка.
 * Атрибутная половина живёт в {@link TierItems}. Уровни читаются из PDC предмета
 * в руке/на теле в момент события — ничего не кэшируем.
 */
public final class ForgeUpgradeListener implements Listener {

    /** Потолки суммарных шансов/срезов с четырёх вещей — защита от абсолюта. */
    static final double CAP_CRIT = 0.60;
    static final double CAP_THORNS = 0.40;
    static final double CAP_RESILIENCE = 0.30;
    static final double CAP_ORE_DOUBLE = 0.60;

    private final TierForgePlugin plugin;
    private final Random random = new Random();

    public ForgeUpgradeListener(TierForgePlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------- атака игрока ------------------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(weapon);
        if (ups.isEmpty()) {
            return;
        }
        double damage = event.getDamage();

        // Крит: шанс — уровни ×3%, удар ×1.5
        int crit = levelOf(ups, ForgeUpgrade.Effect.CRIT_CHANCE);
        if (crit > 0 && random.nextDouble()
                < Math.min(CAP_CRIT, crit * fraction(ups, ForgeUpgrade.Effect.CRIT_CHANCE))) {
            damage *= 1.5;
        }
        if (event.getEntity() instanceof LivingEntity target) {
            // Добивание: цель ниже 30% здоровья → +% за уровень
            int execute = levelOf(ups, ForgeUpgrade.Effect.EXECUTE);
            if (execute > 0 && target.getHealth() > 0
                    && target.getHealth() < 0.3 * maxHealth(target)) {
                damage *= 1.0 + execute * fraction(ups, ForgeUpgrade.Effect.EXECUTE);
            }
            // Кара: нежить получает +% за уровень
            int smite = levelOf(ups, ForgeUpgrade.Effect.SMITE);
            if (smite > 0 && isUndead(target)) {
                damage *= 1.0 + smite * fraction(ups, ForgeUpgrade.Effect.SMITE);
            }
        }
        event.setDamage(damage);

        // Кровопийца: % от финального урона назад в здоровье
        int lifesteal = levelOf(ups, ForgeUpgrade.Effect.LIFESTEAL);
        if (lifesteal > 0) {
            double healed = event.getFinalDamage() * lifesteal * fraction(ups, ForgeUpgrade.Effect.LIFESTEAL);
            heal(attacker, healed);
        }
    }

    /* ------------------------------- игрока бьют ------------------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerHurt(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        double thornChance = 0;
        double reduction = 0;
        for (ItemStack piece : victim.getInventory().getArmorContents()) {
            Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(piece);
            thornChance += fraction(ups, ForgeUpgrade.Effect.THORNS);
            reduction += fraction(ups, ForgeUpgrade.Effect.DAMAGE_TAKEN_PERCENT);
        }
        if (reduction > 0) {
            event.setDamage(event.getDamage() * (1.0 - Math.min(CAP_RESILIENCE, reduction)));
        }
        if (thornChance > 0 && event.getDamager() instanceof LivingEntity damager
                && random.nextDouble() < Math.min(CAP_THORNS, thornChance)) {
            // возвращаем четверть полученного урона (килл-кредит — жертве)
            damager.damage(Math.max(1.0, event.getFinalDamage() * 0.25), victim);
        }
    }

    /* ------------------------------- добыча/опыт ------------------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getDroppedExp() <= 0) {
            return;
        }
        Map<ForgeUpgrade.Def, Integer> ups =
                plugin.items().upgradesOf(killer.getInventory().getItemInMainHand());
        int hunter = levelOf(ups, ForgeUpgrade.Effect.HUNTER_XP);
        if (hunter <= 0) {
            return;
        }
        double bonus = hunter * fraction(ups, ForgeUpgrade.Effect.HUNTER_XP);
        event.setDroppedExp((int) Math.round(event.getDroppedExp() * (1.0 + bonus)));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onOreBreak(BlockBreakEvent event) {
        Material type = event.getBlock().getType();
        if (!isOre(type)) {
            return;
        }
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(tool);
        int prospector = levelOf(ups, ForgeUpgrade.Effect.ORE_DOUBLE);
        if (prospector <= 0) {
            return;
        }
        double chance = Math.min(CAP_ORE_DOUBLE, prospector * fraction(ups, ForgeUpgrade.Effect.ORE_DOUBLE));
        if (random.nextDouble() >= chance) {
            return;
        }
        // дубль: всё содержимое дропа этого блока этим инструментом вываливаем второй раз
        for (ItemStack drop : event.getBlock().getDrops(tool, event.getPlayer())) {
            if (drop != null && !drop.getType().isAir()) {
                event.getBlock().getWorld().dropItemNaturally(
                        event.getBlock().getLocation().add(0.5, 0.5, 0.5), drop.clone());
            }
        }
    }

    /* ------------------------------- прочность ------------------------------- */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        Map<ForgeUpgrade.Def, Integer> ups = plugin.items().upgradesOf(event.getItem());
        int tempered = levelOf(ups, ForgeUpgrade.Effect.DURABILITY_SAVE);
        if (tempered > 0
                && random.nextDouble() < tempered * fraction(ups, ForgeUpgrade.Effect.DURABILITY_SAVE)) {
            event.setCancelled(true); // закалка сработала: прочность не тратится
        }
    }

    /* ------------------------------- подсчёты ------------------------------- */

    /** Суммарный уровень всех улучшений с нужным эффектом (наборы могут наслаиваться). */
    private static int levelOf(Map<ForgeUpgrade.Def, Integer> ups, ForgeUpgrade.Effect effect) {
        int total = 0;
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : ups.entrySet()) {
            if (entry.getKey().effect() == effect) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /** Доля от единицы за один уровень по КОНКРЕТНОМУ свойству (perLevel/100). */
    private static double fraction(Map<ForgeUpgrade.Def, Integer> ups, ForgeUpgrade.Effect effect) {
        for (Map.Entry<ForgeUpgrade.Def, Integer> entry : ups.entrySet()) {
            if (entry.getKey().effect() == effect) {
                return entry.getKey().perLevel() / 100.0;
            }
        }
        return 0;
    }

    static boolean isUndead(LivingEntity entity) {
        return entity instanceof Zombie // обычный зомби, кадавр, утопленник, зомби-житель, пиглин
                || entity instanceof AbstractSkeleton // скелет, зимогор, визер-скелет, болотник
                || entity instanceof Phantom
                || entity instanceof Wither;
    }

    static boolean isOre(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return name.endsWith("_ORE") || type == Material.ANCIENT_DEBRIS;
    }

    private static double maxHealth(LivingEntity entity) {
        org.bukkit.attribute.AttributeInstance attribute =
                entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }

    private void heal(Player player, double amount) {
        if (amount <= 0 || player.isDead()) {
            return;
        }
        double max = maxHealth(player);
        double next = Math.min(max, player.getHealth() + amount);
        if (next <= player.getHealth()) {
            return;
        }
        try {
            player.setHealth(next);
        } catch (IllegalArgumentException ignored) {
            // здоровье подвинулось под нами — пропускаем лечение тихо
        }
    }
}
