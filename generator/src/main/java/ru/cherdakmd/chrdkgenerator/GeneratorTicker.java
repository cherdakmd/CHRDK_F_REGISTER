package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Пульс производства: раз в секунду на каждый установленный генератор.
 *
 * <p>Работает только в загруженных чанках с целой камерой: простой (сломанная
 * ячейка) сгорает без начисления, выгрузка чанка и рестарты добираются
 * офлайн-расчётом при следующем старте (п.31, §6). Топливо расходуется
 * рабочим временем: ведро лавы держит {@code cycles-per-bucket} циклов, потом
 * ячейка прогорает и требует доливки. Модуль «Чанк» держит чанк загруженным
 * и снимает офлайн-ограничения.</p>
 */
final class GeneratorTicker {

    private final GeneratorPlugin plugin;
    private final Map<UUID, Long> lastFx = new HashMap<>();
    private BukkitTask tickTask;
    private BukkitTask saveTask;

    GeneratorTicker(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> plugin.store().saveNow(), 6000L, 6000L);
    }

    void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID owner : plugin.store().owners()) {
            GeneratorStore.Entry entry = plugin.store().entry(owner);
            if (entry == null || !entry.installed()) {
                continue;
            }
            try {
                tickOne(owner, entry, now);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("[CHRDK_Generator] сбой цикла для " + owner
                        + ": " + exception.getMessage());
            }
        }
    }

    private void tickOne(UUID owner, GeneratorStore.Entry entry, long now) {
        World world = Bukkit.getWorld(entry.world());
        if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
            // Чанк выгружен: время доберёт офлайн-расчёт при следующем входе.
            return;
        }
        Block core = world.getBlockAt(entry.x(), entry.y(), entry.z());
        if (entry.modules().getOrDefault("chunk", 0) > 0) {
            core.getChunk().addPluginChunkTicket(plugin);
        }
        List<GeneratorChamber.Cell> cells = GeneratorCells.scan(world, entry.x(), entry.y(), entry.z());
        GeneratorChamber.Result chamber = GeneratorChamber.scan(cells);
        if (!chamber.working()) {
            // Простой сгорает: метка расчёта — сейчас, без начисления.
            plugin.store().addBunker(owner, 0, now);
            return;
        }
        GeneratorConfig config = plugin.config();
        double cycleMs = GeneratorMath.cycleSeconds(config.cycleSeconds(),
                entry.modules().getOrDefault("speed", 0)) * 1000.0;
        long cap = (long) GeneratorMath.bunkerCapacity(config.bunkerBase(),
                entry.modules().getOrDefault("capacity", 0)) * config.offlineHours();
        long room = Math.max(0L, cap - entry.bunker());
        boolean chunkModule = entry.modules().getOrDefault("chunk", 0) > 0;
        double bonus = GeneratorMath.rebirthBonusPercent(
                config.rebirthPercentPerRebirth(), config.rebirthDecay(),
                config.rebirthBonusCapPercent(), entry.rebirths());
        GeneratorProduction.Settle settle = GeneratorProduction.settle(
                now - entry.lastCalcMillis(), cycleMs,
                config.tier(entry.tier()).unitsPerCycle(), bonus,
                chunkModule, config.offlineEfficiency(),
                config.offlineHours() * 3_600_000L, room);
        if (settle.units() > 0 || settle.advanceMillis() > 0) {
            plugin.store().addBunker(owner, settle.units(), entry.lastCalcMillis() + settle.advanceMillis());
        }
        if (config.fuelEnabled() && settle.advanceMillis() > 0) {
            long used = entry.fuelUsedMs() + settle.advanceMillis();
            long budget = (long) config.cyclesPerBucket() * (long) cycleMs;
            if (used >= budget) {
                burnLava(world, entry, cells, chamber);
                used = 0;
            }
            plugin.store().setFuelUsedMs(owner, used);
        }
        Long last = lastFx.get(owner);
        if (last == null || now - last >= (long) cycleMs) {
            lastFx.put(owner, now);
            fx(world, entry, cells, chamber);
        }
    }

    /** Ведро лавы выгорело: ячейка прогорает, пока её не доливят. */
    private void burnLava(World world, GeneratorStore.Entry entry,
                          List<GeneratorChamber.Cell> cells, GeneratorChamber.Result chamber) {
        if (chamber.lavaIndex() < 0) {
            return;
        }
        Block lava = GeneratorCells.candidates(world, entry.x(), entry.y(), entry.z())
                .get(chamber.lavaIndex());
        lava.setType(Material.AIR, false);
    }

    /** Работа видна: пар и искры на стыке лавы и воды, басовый щелчок цикла. */
    private void fx(World world, GeneratorStore.Entry entry,
                    List<GeneratorChamber.Cell> cells, GeneratorChamber.Result chamber) {
        if (chamber.lavaIndex() < 0 || chamber.waterIndex() < 0) {
            return;
        }
        List<Block> candidates = GeneratorCells.candidates(world, entry.x(), entry.y(), entry.z());
        Block lava = candidates.get(chamber.lavaIndex());
        Block water = candidates.get(chamber.waterIndex());
        Location seam = new Location(world,
                (lava.getX() + water.getX()) / 2.0 + 0.5,
                (lava.getY() + water.getY()) / 2.0 + 0.5,
                (lava.getZ() + water.getZ()) / 2.0 + 0.5);
        world.spawnParticle(Particle.CLOUD, seam, 3, 0.2, 0.1, 0.2, 0.01);
        world.spawnParticle(Particle.FLAME, seam, 2, 0.15, 0.05, 0.15, 0.01);
        world.playSound(seam, Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 1.4f);
    }
}
