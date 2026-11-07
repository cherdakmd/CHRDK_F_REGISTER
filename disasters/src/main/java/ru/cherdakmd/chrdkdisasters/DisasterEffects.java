package ru.cherdakmd.chrdkdisasters;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

/**
 * Шесть катастроф второй итерации. Блоки меняются ТОЛЬКО через
 * {@code setType(..., false)} без физики и без Bukkit-событий, поэтому
 * CHRDK_Skills не начислит опыт и не выпадут алмазы за «добычу» катастрофой,
 * а CHRDK_Claims не увидит ложного разрушения. Защита приватов лежит не на
 * событиях, а на явной проверке в {@link DisasterProtection}.
 *
 * <p>Активная фаза больше не меняет всё сразу: {@link DisasterPhasePlan#budget}
 * раскладывает лимит блоков по тикам, а геометрия берётся из
 * {@link DisasterGeometry}. Предупреждение и затухание меняют только частицы,
 * подсказки и эффекты состояния — мир в эти фазы нетронут.</p>
 */
public final class DisasterEffects {

    private static final Particle.DustOptions DUST =
            new Particle.DustOptions(Color.fromRGB(0xC2, 0xA3, 0x6B), 1.3f);
    private static final Particle.DustOptions ASH =
            new Particle.DustOptions(Color.fromRGB(0x6B, 0x62, 0x58), 1.1f);
    private static final Particle.DustOptions SNOW =
            new Particle.DustOptions(Color.fromRGB(0xE8, 0xF3, 0xFF), 1.0f);
    private static final Particle.DustOptions WATER =
            new Particle.DustOptions(Color.fromRGB(0x4C, 0x8F, 0xD8), 1.0f);
    private static final Particle.DustOptions FLAME_DUST =
            new Particle.DustOptions(Color.fromRGB(0xD9, 0x74, 0x3A), 1.2f);

    private final DisastersPlugin plugin;

    public DisasterEffects(DisastersPlugin plugin) {
        this.plugin = plugin;
    }

    /** Фаза предупреждения: кольцо частиц и обратный отсчёт, блоки не тронуты. */
    public int warning(DisasterRun run, int step) {
        Location center = center(run);
        if (center == null) {
            return 0;
        }
        DisastersConfig config = plugin.settings();
        World world = center.getWorld();
        List<int[]> ring = DisasterGeometry.ring(config.radius(), 10 + run.level() * 3, step);
        for (int[] offset : ring) {
            world.spawnParticle(Particle.DUST, center.clone().add(offset[0], 1.0, offset[1]),
                    3, 0.2, 0.3, 0.2, 0.0, dustFor(run.kind()));
        }
        if (step == 0) {
            sound(run, center, 0.7f);
        }
        hint(run, center, "warn", config.radius());
        return 0;
    }

    /** Один тик активной фазы: порция блоков + частицы + мягкий эффект. */
    public int active(DisasterRun run) {
        Location center = center(run);
        if (center == null) {
            return 0;
        }
        DisastersConfig config = plugin.settings();
        int changed = switch (run.kind()) {
            case DUST_STORM -> dustStorm(run, center, config);
            case METEOR_SHOWER -> meteorShower(run, center, config);
            case EARTHQUAKE -> earthquake(run, center, config);
            case TORNADO -> tornado(run, center, config);
            case FLOOD -> flood(run, center, config);
            case BLIZZARD -> blizzard(run, center, config);
        };
        if (config.playerEffectsEnabled()) {
            // Перк стойкости: чем выше уровень игрока, тем слабее эффект.
            int steadiness = plugin.rewards().levelOf(run.player());
            double scale = DisasterProgressMath.effectScale(steadiness);
            int amplifier = steadiness >= 4 ? 0 : run.kind().amplifier();
            int ticks = (int) Math.max(20L,
                    Math.round((config.phases().tickSeconds() * 20L + 20L) * scale));
            DisasterFx.apply(center.getWorld(), center, config.radius(),
                    run.kind().effect(), amplifier, ticks);
        }
        if (run.activeStep() % 3 == 0) {
            sound(run, center, config.soundVolume());
        }
        hint(run, center, run.kind().hintKey(), config.radius());
        return changed;
    }

    /** Затухание: частицы реже, эффекты состояния снимаются, мир не меняется. */
    public int fading(DisasterRun run) {
        Location center = center(run);
        if (center == null) {
            return 0;
        }
        DisastersConfig config = plugin.settings();
        int count = DisasterGeometry.particles(run.level(), true);
        center.getWorld().spawnParticle(Particle.DUST,
                center.clone().add(0, 2, 0), count,
                config.radius() / 2.0, 1.0, config.radius() / 2.0, 0.0,
                dustFor(run.kind()));
        hint(run, center, "end", config.radius());
        return 0;
    }

    /** Полная очистка эффектов при завершении или досрочной остановке. */
    public void clearEffects(DisasterRun run) {
        Location center = center(run);
        if (center != null) {
            DisasterFx.clear(center.getWorld(), center,
                    plugin.settings().radius(), run.kind().effect());
        }
    }

    // ------------------------------------------------------------------
    // Шесть видов
    // ------------------------------------------------------------------

    /** Чисто визуальная: частицы и подсказка, мир не меняется вообще. */
    private int dustStorm(DisasterRun run, Location center, DisastersConfig config) {
        int count = DisasterGeometry.particles(run.level(), false);
        center.getWorld().spawnParticle(Particle.DUST, center.clone().add(0, 2, 0),
                count, config.radius() / 2.0, 2.0, config.radius() / 2.0, 0.0, DUST);
        return 0;
    }

    /** Точечные кратеры по поверхности, порциями по тикам активной фазы. */
    private int meteorShower(DisasterRun run, Location center, DisastersConfig config) {
        int total = Math.min(config.maxBlocks(), run.level() * 3 + 2);
        int shots = config.phases().budget(total, run.activeStep());
        List<int[]> points = DisasterGeometry.scatter(config.radius(), shots, seed(run));
        int changed = 0;
        for (int[] offset : points) {
            int x = center.getBlockX() + offset[0];
            int z = center.getBlockZ() + offset[1];
            if (!center.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block target = center.getWorld().getHighestBlockAt(x, z);
            Location spot = target.getLocation().add(0.5, 1.0, 0.5);
            center.getWorld().spawnParticle(Particle.FLAME, spot, 12, 0.4, 0.4, 0.4, 0.02);
            if (changeBlock(target)) {
                changed++;
            }
        }
        return changed;
    }

    /** Площадное изменение случайных верхних блоков, тоже порциями. */
    private int earthquake(DisasterRun run, Location center, DisastersConfig config) {
        int total = Math.min(config.maxBlocks(), run.level() * 12 + 8);
        int attempts = config.phases().budget(total, run.activeStep());
        center.getWorld().spawnParticle(Particle.DUST, center.clone().add(0, 1, 0),
                30, config.radius() / 2.0, 1.0, config.radius() / 2.0, 0.0, ASH);
        List<int[]> points = DisasterGeometry.scatter(config.radius(), attempts, seed(run));
        int changed = 0;
        for (int[] offset : points) {
            int x = center.getBlockX() + offset[0];
            int z = center.getBlockZ() + offset[1];
            if (!center.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            if (changeBlock(center.getWorld().getHighestBlockAt(x, z))) {
                changed++;
            }
        }
        return changed;
    }

    /** Смерч: вращающаяся спираль, поднятые блоки видны частицами материала. */
    private int tornado(DisasterRun run, Location center, DisastersConfig config) {
        List<int[]> spiral = DisasterGeometry.spiral(config.radius(),
                6 + run.level() * 3, run.activeStep());
        int changed = 0;
        for (int[] offset : spiral) {
            int x = center.getBlockX() + offset[0];
            int z = center.getBlockZ() + offset[1];
            if (!center.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block target = center.getWorld().getHighestBlockAt(x, z);
            Location spot = target.getLocation().add(0.5, 1.5, 0.5);
            center.getWorld().spawnParticle(Particle.CLOUD, spot, 8, 0.3, 0.8, 0.3, 0.02);
            center.getWorld().spawnParticle(Particle.BLOCK, spot, 6,
                    0.3, 0.9, 0.3, 0.02, Material.DIRT.createBlockData());
            if (changeBlock(target)) {
                changed++;
            }
        }
        return changed;
    }

    /** Наводнение: временная вода над поверхностью, снимок вернёт воздух. */
    private int flood(DisasterRun run, Location center, DisastersConfig config) {
        int total = Math.min(config.maxBlocks(), run.level() * 8 + 6);
        int columns = config.phases().budget(total, run.activeStep());
        List<int[]> points = DisasterGeometry.scatter(config.radius(), columns, seed(run));
        int changed = 0;
        for (int[] offset : points) {
            int x = center.getBlockX() + offset[0];
            int z = center.getBlockZ() + offset[1];
            if (!center.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block surface = center.getWorld().getHighestBlockAt(x, z);
            Block water = center.getWorld().getBlockAt(x, surface.getY() + 1, z);
            if (!water.isEmpty() && !water.getType().isAir()) {
                continue;
            }
            center.getWorld().spawnParticle(Particle.BUBBLE,
                    water.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.2, 0.3, 0.01);
            if (changeBlock(water, Material.WATER)) {
                changed++;
            }
        }
        return changed;
    }

    /** Метель: снег и холодный эффект состояния, блоки не меняются. */
    private int blizzard(DisasterRun run, Location center, DisastersConfig config) {
        int count = DisasterGeometry.particles(run.level(), false);
        center.getWorld().spawnParticle(Particle.SNOWFLAKE, center.clone().add(0, 3, 0),
                count, config.radius() / 2.0, 3.0, config.radius() / 2.0, 0.02);
        center.getWorld().spawnParticle(Particle.DUST, center.clone().add(0, 1, 0),
                count / 2, config.radius() / 2.0, 1.0, config.radius() / 2.0, 0.0, SNOW);
        return 0;
    }

    // ------------------------------------------------------------------
    // Общие помощники
    // ------------------------------------------------------------------

    private long seed(DisasterRun run) {
        return run.id() * 131L + run.activeStep() * 7919L + run.level() * 17L;
    }

    private Particle.DustOptions dustFor(DisasterKind kind) {
        return switch (kind) {
            case DUST_STORM, TORNADO -> DUST;
            case METEOR_SHOWER -> FLAME_DUST;
            case EARTHQUAKE -> ASH;
            case FLOOD -> WATER;
            case BLIZZARD -> SNOW;
        };
    }

    private void sound(DisasterRun run, Location center, float volume) {
        DisastersConfig config = plugin.settings();
        DisasterFx.play(center.getWorld(), center, config.soundFor(run.kind()),
                volume, config.soundPitch());
    }

    private void hint(DisasterRun run, Location center, String key, int radius) {
        double limit = (double) radius * radius;
        for (Player player : center.getWorld().getPlayers()) {
            if (player.hasPermission("chrdkdisasters.bypass")
                    || player.getWorld() != center.getWorld()
                    || player.getLocation().distanceSquared(center) > limit) {
                continue;
            }
            player.sendActionBar(plugin.messages().get(key,
                    "kind", plugin.messages().raw(run.kind().titleKey()),
                    "level", run.level(), "seconds", run.secondsLeft()));
        }
    }

    private Location center(DisasterRun run) {
        World world = Bukkit.getWorld(run.world());
        return world == null ? null : new Location(world, run.x(), run.y(), run.z());
    }

    /** Единственная точка изменения мира во всём модуле. */
    private boolean changeBlock(Block block) {
        return changeBlock(block, Material.AIR);
    }

    private boolean changeBlock(Block block, Material material) {
        if (block == null) {
            return false;
        }
        boolean air = block.isEmpty() || block.getType().isAir();
        if (material == Material.AIR ? air : !air) {
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
        if (material == Material.AIR) {
            block.setType(Material.AIR, false);
        } else {
            block.setType(material, false);
        }
        return true;
    }
}
