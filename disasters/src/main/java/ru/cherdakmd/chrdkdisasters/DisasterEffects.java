package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

import java.util.Random;

/**
 * Три катастрофы первой версии. Важное правило: блоки меняются ТОЛЬКО через
 * {@link Block#setType(Material, boolean)} без физики, без {@code callEvent},
 * без {@code breakNaturally} и без Bukkit-взрывов. Поэтому CHRDK_Skills не
 * начислит опыт и не выпадут алмазы за «добычу» катастрофой, а CHRDK_Claims
 * не увидит ложный {@code BlockBreakEvent}. Защита приватов при этом лежит не
 * на событиях, а на явной проверке в {@link DisasterProtection}.
 */
public final class DisasterEffects {

    private static final Particle.DustOptions DUST =
            new Particle.DustOptions(Color.fromRGB(0xC2, 0xA3, 0x6B), 1.3f);

    private final DisastersPlugin plugin;
    private final Random random = new Random();

    public DisasterEffects(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return сколько блоков реально изменено (для destructives; 0 для бури). */
    public int run(DisasterKind kind, Location center, int level) {
        if (center == null || center.getWorld() == null) {
            return 0;
        }
        return switch (kind) {
            case DUST_STORM -> dustStorm(center, level);
            case METEOR_SHOWER -> meteorShower(center, level);
            case EARTHQUAKE -> earthquake(center, level);
        };
    }

    /** Чисто визуальная: частицы и одна строка над хотбаром, мир не меняется. */
    private int dustStorm(Location center, int level) {
        World world = center.getWorld();
        int count = 40 + level * 20;
        world.spawnParticle(Particle.DUST, center.clone().add(0, 2, 0),
                count, 6.0, 2.0, 6.0, 0.0, DUST);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= 24.0 * 24.0) {
                player.sendActionBar(plugin.messages().get("storm-hint"));
            }
        }
        return 0;
    }

    /** Точечные кратеры: по одному верхнему блоку на метеор, со снимком для отката. */
    private int meteorShower(Location center, int level) {
        World world = center.getWorld();
        int radius = plugin.settings().radius();
        int shots = Math.min(plugin.settings().maxBlocks(), level * 3 + 2);
        int changed = 0;
        for (int i = 0; i < shots; i++) {
            int x = center.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int z = center.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block target = world.getHighestBlockAt(x, z);
            Location spot = target.getLocation().add(0.5, 1.0, 0.5);
            world.spawnParticle(Particle.FLAME, spot, 12, 0.4, 0.4, 0.4, 0.02);
            if (changeBlock(target)) {
                changed++;
            }
        }
        return changed;
    }

    /** Площадное: случайные блоки в квадрате, но только вне приватов и спавна. */
    private int earthquake(Location center, int level) {
        World world = center.getWorld();
        int radius = plugin.settings().radius();
        int attempts = Math.min(plugin.settings().maxBlocks(), level * 12 + 8);
        world.spawnParticle(Particle.DUST, center.clone().add(0, 1, 0),
                60, radius / 2.0, 1.0, radius / 2.0, 0.0, DUST);
        int changed = 0;
        for (int i = 0; i < attempts; i++) {
            int x = center.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int z = center.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block target = world.getHighestBlockAt(x, z);
            if (changeBlock(target)) {
                changed++;
            }
        }
        return changed;
    }

    /** Единственная точка изменения мира во всём модуле. */
    private boolean changeBlock(Block block) {
        if (block == null || block.isEmpty() || block.getType().isAir()) {
            return false;
        }
        if (plugin.protection().blocked(block.getLocation())) {
            return false;
        }
        if (!plugin.restore().canRemember()) {
            return false;
        }
        // Хранилища игроков не трогаем даже вне привата: потеря сундука —
        // худший вид «веселья» для выживания.
        if (block.getState() instanceof InventoryHolder) {
            return false;
        }
        plugin.restore().remember(block);
        block.setType(Material.AIR, false);
        return true;
    }
}
