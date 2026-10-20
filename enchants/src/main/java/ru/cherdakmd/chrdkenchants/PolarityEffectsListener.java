package ru.cherdakmd.chrdkenchants;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Событийные эффекты светлых чар и проклятий (1.4.128).
 *
 * <p>Атрибутная часть (урон, броня, скорость) живёт в {@link EnchantItems} —
 * здесь только то, что нельзя выразить атрибутом: условный урон, входящий урон,
 * опыт, износ, дроп и огонь. Стрелы обрабатывает {@link BowEffectsListener}:
 * набор чар уже скопирован в PDC снаряда, поэтому повторно читать лук не нужно.</p>
 */
public final class PolarityEffectsListener implements Listener {

    /** Блоки, с которых «Старатель» умеет давать второй дроп. */
    private static boolean isOre(Material material) {
        String name = material.name();
        return name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS");
    }

    private final EnchantsPlugin plugin;
    private final Random random = new Random();

    public PolarityEffectsListener(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------ входящий/исходящий урон ------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        Map<String, Integer> enchants =
                plugin.items().read(attacker.getInventory().getItemInMainHand());
        if (enchants.isEmpty()) {
            return;
        }
        double multiplier = 1.0;
        // Ярость: бьём сильнее, когда сами на последних сердцах
        int frenzy = enchants.getOrDefault("frenzy", 0);
        if (frenzy > 0) {
            double maxHealth = attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                    ? attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20;
            if (attacker.getHealth() <= maxHealth * 0.5) {
                multiplier *= 1 + EnchantParams.percent("frenzy", frenzy) / 100.0;
            }
        }
        // Дуэлянт: только против игроков
        int duelist = enchants.getOrDefault("duelist", 0);
        if (duelist > 0 && event.getEntity() instanceof Player) {
            multiplier *= 1 + EnchantParams.percent("duelist", duelist) / 100.0;
        }
        if (multiplier != 1.0) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIncoming(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Map<String, Integer> enchants = bestArmorEnchants(victim);
        double multiplier = 1.0;

        // Вторая кожа: мягче любой входящий урон
        int secondSkin = enchants.getOrDefault("second_skin", 0);
        if (secondSkin > 0) {
            multiplier *= 1 - EnchantParams.percent("second_skin", secondSkin) / 100.0;
        }
        // Открытость: любой удар проходит глубже
        int exposed = enchants.getOrDefault("exposed", 0);
        if (exposed > 0) {
            multiplier *= 1 + EnchantParams.percent("exposed", exposed) / 100.0;
        }
        // Приземление: мягче падение
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            int grounded = enchants.getOrDefault("grounded", 0);
            if (grounded > 0) {
                multiplier *= 1 - EnchantParams.percent("grounded", grounded) / 100.0;
            }
            int fragile = enchants.getOrDefault("fragile_bones", 0);
            if (fragile > 0) {
                multiplier *= 1 + EnchantParams.percent("fragile_bones", fragile) / 100.0;
            }
        }
        // Боязнь огня: огонь и лава жгут сильнее
        if (isFireDamage(event.getCause())) {
            int prone = enchants.getOrDefault("fire_prone", 0);
            if (prone > 0) {
                multiplier *= 1 + EnchantParams.percent("fire_prone", prone) / 100.0;
                victim.setFireTicks(Math.max(victim.getFireTicks(),
                        20 * (1 + Math.min(3, prone))));
            }
        }
        if (multiplier != 1.0) {
            event.setDamage(event.getDamage() * multiplier);
        }
    }

    private static boolean isFireDamage(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR;
    }

    /* ------------------------------ опыт ------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        double bonus = 0;
        for (ItemStack piece : gearPieces(player)) {
            Map<String, Integer> enchants = plugin.items().read(piece);
            enchants.forEach((id, level) -> {
                if (id.equals("scholar")) {
                    bonus += EnchantParams.percent("scholar", level) / 100.0;
                } else if (id.equals("slow_learner")) {
                    bonus -= EnchantParams.percent("slow_learner", level) / 100.0;
                }
            });
        }
        if (bonus != 0) {
            double multiplier = Math.max(0.1, 1 + bonus);
            event.setAmount(Math.max(0, (int) Math.round(event.getAmount() * multiplier)));
        }
    }

    /* ------------------------------ износ ------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        Map<String, Integer> enchants = plugin.items().read(event.getItem());
        int brittle = enchants.getOrDefault("brittle", 0);
        if (brittle <= 0) {
            return;
        }
        double multiplier = 1 + EnchantParams.percent("brittle", brittle) / 100.0;
        event.setDamage(Math.max(1, (int) Math.ceil(event.getDamage() * multiplier)));
    }

    /* ------------------------------ дроп ------------------------------ */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        Map<String, Integer> enchants =
                plugin.items().read(player.getInventory().getItemInMainHand());
        if (enchants.isEmpty()) {
            return;
        }
        // Порча: добыча может исчезнуть
        int jinxed = enchants.getOrDefault("jinxed", 0);
        if (jinxed > 0 && random.nextInt(100) < EnchantParams.percent("jinxed", jinxed)) {
            if (!event.getItems().isEmpty()) {
                plugin.messages().send(player, "curse-jinxed");
            }
            event.getItems().clear();
            return;
        }
        // Старатель: шанс удвоить дроп руды
        int prospector = enchants.getOrDefault("prospector", 0);
        if (prospector > 0 && isOre(event.getBlock().getType())
                && random.nextInt(100) < EnchantParams.percent("prospector", prospector)) {
            for (org.bukkit.entity.Item drop : List.copyOf(event.getItems())) {
                ItemStack extra = drop.getItemStack().clone();
                drop.getWorld().dropItemNaturally(drop.getLocation(), extra);
            }
        }
    }

    /* ------------------------------ убийства ------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        Map<String, Integer> enchants =
                plugin.items().read(killer.getInventory().getItemInMainHand());
        if (enchants.isEmpty()) {
            return;
        }
        // Мародёр: с убитых падает больше
        int plunder = enchants.getOrDefault("plunder", 0);
        if (plunder > 0) {
            int percent = EnchantParams.percent("plunder", plunder);
            for (ItemStack drop : event.getDrops()) {
                int extra = drop.getAmount() * percent / 100;
                if (random.nextInt(100) < drop.getAmount() * percent % 100) {
                    extra++;
                }
                if (extra > 0) {
                    drop.setAmount(Math.min(drop.getMaxStackSize(), drop.getAmount() + extra));
                }
            }
        }
        // Лёгкая поступь: рывок скорости после убийства
        int lightfoot = enchants.getOrDefault("lightfoot", 0);
        if (lightfoot > 0) {
            killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    EnchantParams.seconds("lightfoot", lightfoot) * 20,
                    EnchantParams.hasteAmplifier(lightfoot), true, false, false));
        }
    }

    /* ------------------------------ утилиты ------------------------------ */

    /** Лучший уровень каждой чары из надетой брони (наборы не складываются). */
    private Map<String, Integer> bestArmorEnchants(Player player) {
        Map<String, Integer> best = new HashMap<>();
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece == null) {
                continue;
            }
            plugin.items().read(piece).forEach((id, level) ->
                    best.merge(id, level, Math::max));
        }
        return best;
    }

    /** Броня + обе руки: так опытные и «проклятые» чары видны целиком. */
    private List<ItemStack> gearPieces(Player player) {
        java.util.List<ItemStack> pieces = new java.util.ArrayList<>();
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece != null) {
                pieces.add(piece);
            }
        }
        pieces.add(player.getInventory().getItemInMainHand());
        pieces.add(player.getInventory().getItemInOffHand());
        return pieces;
    }
}
