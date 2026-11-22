package ru.cherdakmd.chrdkgenerator;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
    private final Map<String, Long> nextProduction = new HashMap<>();
    private final Map<UUID, Long> payoutWarnings = new HashMap<>();
    private final Map<UUID, Long> nextPayout = new HashMap<>();
    private final Random random = new Random();
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
            List<GeneratorStore.Entry> entries = plugin.store().entries(owner);
            if (entries.isEmpty()) {
                continue;
            }
            try {
                // Сначала восстановление старой/ожидающей выплаты, затем производство.
                // Выплаты идут порциями с устойчивым ключом, без дневного лимита.
                tryPayout(owner);
                Player player = Bukkit.getPlayer(owner);
                if (player != null && player.isOnline() && plugin.coreApi().isAuthenticated(player)
                        && !plugin.containers().recoverPlayer(player)) continue;
                if (!plugin.containers().syncRebirth(owner, player, now)) continue;
                entries = plugin.store().entries(owner);
                for (GeneratorStore.Entry entry : entries) {
                    if (entry.installed()) {
                        String key = owner + ":" + entry.slot();
                        if (now >= nextProduction.getOrDefault(key, 0L)) {
                            nextProduction.put(key, now + 5_000L);
                            var result = plugin.containers().tick(entry, now);
                            if (result == GeneratorContainerCoordinator.Result.CONFLICT)
                                plugin.getLogger().warning("[CHRDK_Generator] Требуется сверка контейнера: " + key);
                        }
                    } else {
                        plugin.publishState(owner, entry.slot(), "state-carried");
                    }
                }
            } catch (java.io.IOException | RuntimeException exception) {
                plugin.getLogger().warning("[CHRDK_Generator] сбой цикла для " + owner
                        + ": " + exception.getMessage());
            }
        }
    }

    /** Восстанавливает ожидание и отправляет одну техническую порцию за тик. */
    private void tryPayout(UUID owner) {
        Player player = Bukkit.getPlayer(owner);
        if (player == null || !player.isOnline() || plugin.coreApi() == null
                || !plugin.coreApi().isAuthenticated(player)) return;
        long now = System.currentTimeMillis();
        var state = plugin.store().payoutState(owner);
        if (state == null) return;
        if (state.legacyReconciled() && state.pending() == 0
                && state.money() < GeneratorPayoutCoordinator.MAX_OBLIGATION
                && now < nextPayout.getOrDefault(owner, 0L)) return;
        // Coalesce small production amounts to avoid an unbounded event per second.
        nextPayout.put(owner, now + 60_000L);
        boolean settled = GeneratorPayoutCoordinator.run(plugin.store(), owner, new GeneratorPayoutCoordinator.Ledger() {
            public Map<String, Long> reservations(UUID id) { return plugin.moneyLoot().reservations(id); }
            public boolean accept(String key, long amount) { return plugin.moneyLoot().accept(player, key, amount); }
        });
        if (settled) payoutWarnings.remove(owner);
        else {
            long last = payoutWarnings.getOrDefault(owner, 0L);
            if (now - last >= 60_000L) {
                payoutWarnings.put(owner, now);
                plugin.getLogger().warning("Выплата генератора " + owner
                        + " ожидает восстановления; не удаляйте generator.json или журнал generator-payouts.");
            }
        }
    }

}
