package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Периодические ауры: стремительные лапки, лягушачьи ноги, провидец (ночное зрение),
 * орлиный глаз (поджигает скрытых), лавоступ (магматическая корка по лаве).
 */
public final class AuraService {

    private static final long LAVA_CRUST_MILLIS = 10_000L;

    private final EnchantsPlugin plugin;
    private BukkitTask task;
    /** Лава → магма (блок → момент возврата обратно). */
    private final Map<Block, Long> lavaCrust = new HashMap<>();
    private int tickCounter;

    public AuraService(EnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // честно возвращаем лаву на место
        lavaCrust.forEach((block, when) -> {
            if (block.getType() == Material.MAGMA_BLOCK) {
                block.setType(Material.LAVA);
            }
        });
        lavaCrust.clear();
    }

    private void tick() {
        tickCounter++;
        revertExpiredCrust();
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyBootsAuras(player);
            applyHelmetAuras(player);
        }
    }

    /* ------------------------------- ботинки ------------------------------- */

    private void applyBootsAuras(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        Map<String, Integer> enchants = plugin.items().read(boots);
        int swift = enchants.getOrDefault("swiftfeet", 0);
        if (swift > 0 && player.isSprinting()) {
            // спринт ускоряет: короткий рефреш без перебивания сильных зелий
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40,
                    swift - 1, true, false, false));
        }
        int frog = enchants.getOrDefault("froggy", 0);
        if (frog > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 50,
                    frog - 1, true, false, false));
        }
        if (enchants.containsKey("lavawalker")) {
            walkLava(player);
        }
    }

    /* ------------------------------- шлем ------------------------------- */

    private void applyHelmetAuras(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        Map<String, Integer> enchants = plugin.items().read(helmet);
        if (enchants.containsKey("seer")) {
            // длинный запас: мерцания нет
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 15 * 20,
                    0, true, false, false));
        }
        if (enchants.containsKey("truesight") && tickCounter % 4 == 0) {
            List<Entity> nearby = player.getNearbyEntities(6, 4, 6);
            for (Entity entity : nearby) {
                if (entity instanceof LivingEntity living
                        && living != player
                        && (living.isInvisible() || living instanceof Monster)) {
                    living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40,
                            0, true, false, false));
                }
            }
        }
    }

    /* ------------------------------- лавоступ ------------------------------- */

    private void walkLava(Player player) {
        Block feet = player.getLocation().getBlock();
        if (feet.getType() != Material.LAVA
                && feet.getRelative(0, -1, 0).getType() != Material.LAVA) {
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60,
                0, true, false, false));
        player.setFireTicks(0);
        long revertAt = System.currentTimeMillis() + LAVA_CRUST_MILLIS;
        Block below = feet.getRelative(0, -1, 0);
        if (below.getType() == Material.LAVA) {
            below.setType(Material.MAGMA_BLOCK);
            lavaCrust.put(below, revertAt);
        }
        // корка ширину в пару паркетин под ногами
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block target = below.getRelative(dx, 0, dz);
                if (target.getType() == Material.LAVA) {
                    target.setType(Material.MAGMA_BLOCK);
                    lavaCrust.put(target, revertAt);
                }
            }
        }
    }

    private void revertExpiredCrust() {
        long now = System.currentTimeMillis();
        lavaCrust.entrySet().removeIf(entry -> {
            if (entry.getValue() > now) {
                return false;
            }
            if (entry.getKey().getType() == Material.MAGMA_BLOCK) {
                entry.getKey().setType(Material.LAVA);
            }
            return true;
        });
    }
}
