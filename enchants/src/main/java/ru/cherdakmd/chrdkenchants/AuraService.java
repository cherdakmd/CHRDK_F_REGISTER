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
 *
 * <p>1.4.128: сюда же переехали чары и проклятия, которым нужен «пульс» —
 * «Живучесть» (регенерация), «Старатель» (спешка), «Тупой резец» (усталость),
 * «Обжорство» (ускоренный голод) и «Проклятая кровь» (периодический урон).</p>
 */
public final class AuraService {

    private static final long LAVA_CRUST_MILLIS = 10_000L;

    private final EnchantsPlugin plugin;
    private final java.util.Random random = new java.util.Random();
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
            applyGearAuras(player);
        }
    }

    /* --------------------------- светлые чары и проклятия --------------------------- */

    /**
     * Ауры экипировки: жизнь, спешка, усталость, голод и проклятая кровь.
     * Уровни брони берутся по максимуму — четыре «Живучести» не складываются.
     */
    private void applyGearAuras(Player player) {
        Map<String, Integer> armor = new HashMap<>();
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece == null) {
                continue;
            }
            plugin.items().read(piece).forEach((id, level) ->
                    armor.merge(id, level, Math::max));
        }

        // Живучесть: тихий регенерационный пульс, пока здоровье не полное
        int vitality = armor.getOrDefault("vitality", 0);
        if (vitality > 0 && player.getHealth() > 0 && player.getHealth() < maxHealth(player)) {
            double heal = EnchantParams.fraction("vitality", vitality);
            player.setHealth(Math.min(maxHealth(player), player.getHealth() + heal));
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        Map<String, Integer> held = plugin.items().read(hand);

        // Старатель: спешка при работе инструментом в руке
        int prospector = held.getOrDefault("prospector", 0);
        if (prospector > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60,
                    EnchantParams.hasteAmplifier(prospector), true, false, false));
        }
        // Тупой резец: инструмент тянет руки вниз
        int dull = held.getOrDefault("dull_pick", 0);
        if (dull > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 60,
                    EnchantParams.hasteAmplifier(dull), true, false, false));
        }

        // Обжорство: сытость тает быстрее (4.0 истощения = одно очко голода)
        int gluttony = bestOnGear(player, "gluttony");
        if (gluttony > 0) {
            player.setExhaustion(Math.min(4.0f,
                    player.getExhaustion() + EnchantParams.percent("gluttony", gluttony) / 400.0f));
        }

        // Проклятая кровь: раз в 5 секунд шанс потерять полсердца
        int cursedBlood = bestOnGear(player, "cursed_blood");
        if (cursedBlood > 0 && tickCounter % 5 == 0
                && player.getHealth() > 1 && !player.isDead()) {
            if (random.nextDouble() * 100 < EnchantParams.percent("cursed_blood", cursedBlood)) {
                player.damage(1.0);
                player.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                        player.getLocation().add(0, 1.2, 0), 6, 0.3, 0.3, 0.3, 0.02);
            }
        }
    }

    /** Лучший уровень чары по всей экипировке (броня + обе руки). */
    private int bestOnGear(Player player, String id) {
        int best = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece != null) {
                best = Math.max(best, plugin.items().read(piece).getOrDefault(id, 0));
            }
        }
        best = Math.max(best, plugin.items().read(
                player.getInventory().getItemInMainHand()).getOrDefault(id, 0));
        best = Math.max(best, plugin.items().read(
                player.getInventory().getItemInOffHand()).getOrDefault(id, 0));
        return best;
    }

    private static double maxHealth(Player player) {
        return player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                ? player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20.0;
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
