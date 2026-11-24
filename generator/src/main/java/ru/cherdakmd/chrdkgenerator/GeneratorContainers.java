package ru.cherdakmd.chrdkgenerator;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.cherdakmd.chrdkregister.api.InventoryTransfer;

/** Игровой адаптер устойчивого контейнера. Все действия вызываются на главном потоке. */
final class GeneratorContainers {
    private final GeneratorPlugin plugin;
    private final GeneratorContainerStore containers;
    private final GeneratorContainerTransfer transfer;
    private final Random random = new Random();
    private final Map<String, Long> nextMoney = new HashMap<>();

    GeneratorContainers(GeneratorPlugin plugin, GeneratorContainerStore containers, GeneratorContainerTransfer transfer) {
        this.plugin = plugin; this.containers = containers; this.transfer = transfer;
    }

    boolean recoverPlayer(Player player) throws IOException {
        if (!plugin.coreApi().isAuthenticated(player)) return false;
        if (!plugin.store().payoutCheckpoint()) return false;
        for (var entry : plugin.store().entries(player.getUniqueId())) {
            if (plugin.store().coreTake(player.getUniqueId(), entry.slot()) != null
                    && plugin.coreTake().recover(player, entry.slot()) != InventoryTransfer.Decision.APPLIED) return false;
            var view = containers.view(player.getUniqueId(), entry.slot());
            if (view != null && view.pending()
                    && transfer.recover(player, entry.slot()) != InventoryTransfer.Decision.APPLIED) return false;
        }
        return true;
    }

    boolean syncRebirth(UUID owner, Player player, long now) {
        var provider = Bukkit.getServicesManager().load(ru.cherdakmd.chrdkregister.api.SkillResetBridge.Skills.class);
        if (provider == null) return false;
        var state = provider.state(owner);
        if (state.isEmpty() || !GeneratorCycleGate.allows(state.get(), plugin.store().skillResetEpoch(owner))) return false;
        if (plugin.store().entry(owner) == null) return true;
        if (!plugin.store().observeSkillRebirth(owner, state.get().count())) return false;
        int requested = plugin.store().requestedSkillRebirth(owner);
        if (requested == 0) return true;
        for (var entry : plugin.store().entries(owner)) {
            if (!entry.installed()) continue;
            var result = settle(entry, now, false);
            if (result == GeneratorContainerCoordinator.Result.WAIT || result == GeneratorContainerCoordinator.Result.CONFLICT) return false;
        }
        return plugin.store().rebirthKeepingBunker(owner, requested);
    }

    boolean resetSkillCycle(Player player, String previous, String next, int count) throws IOException {
        if (player == null || !player.isOnline() || !plugin.coreApi().isAuthenticated(player)
                || plugin.payments() == null || plugin.payments().occupied(player.getUniqueId())) return false;
        UUID oldEpoch = "legacy".equals(previous) ? null : UUID.fromString(previous);
        UUID newEpoch = UUID.fromString(next);
        UUID owner = player.getUniqueId();
        var provider = Bukkit.getServicesManager().load(ru.cherdakmd.chrdkregister.api.SkillResetBridge.Skills.class);
        if (provider == null) return false;
        var state = provider.state(owner);
        if (state.isEmpty() || !next.equals(state.get().pendingEpoch()) || !state.get().epoch().equals(previous)
                || state.get().count() != count) return false;
        if (!recoverPlayer(player)) return false;
        var existing = plugin.store().entry(owner);
        if (existing != null && !newEpoch.equals(plugin.store().skillResetEpoch(owner))) {
            if (!java.util.Objects.equals(oldEpoch, plugin.store().skillResetEpoch(owner))
                    || !plugin.store().observeSkillRebirth(owner, count)) return false;
            int requested = plugin.store().requestedSkillRebirth(owner);
            long now = System.currentTimeMillis();
            for (var entry : plugin.store().entries(owner)) {
                if (!entry.installed()) continue;
                var result = settle(entry, now, false);
                if (result == GeneratorContainerCoordinator.Result.WAIT || result == GeneratorContainerCoordinator.Result.CONFLICT) return false;
            }
            if (requested > 0 && !plugin.store().rebirthKeepingBunker(owner, requested)) return false;
        }
        return plugin.store().resetSkillCycle(owner, oldEpoch, newEpoch, count);
    }

    boolean preparePlayer(Player player, int slot) throws IOException {
        if (!recoverPlayer(player) || !syncRebirth(player.getUniqueId(), player, System.currentTimeMillis())) return false;
        var entry = plugin.store().entry(player.getUniqueId(), slot);
        if (entry == null || !entry.installed()) return false;
        var result = tick(entry, System.currentTimeMillis());
        return result == GeneratorContainerCoordinator.Result.READY || result == GeneratorContainerCoordinator.Result.BACKLOG;
    }

    boolean pauseForTake(Player player, int slot) throws IOException {
        if (!preparePlayer(player, slot)) return false;
        var entry = plugin.store().entry(player.getUniqueId(), slot);
        var view = containers.view(player.getUniqueId(), slot);
        if (entry == null || view == null || view.pending()) return false;
        long now = System.currentTimeMillis();
        if (!containers.setPaused(player.getUniqueId(), slot, view.core(), view.revision(), true, now)) return false;
        return payAndSell(entry, now, false, true);
    }

    boolean resume(Player player, int slot) throws IOException {
        if (!preparePlayer(player, slot)) return false;
        var view = containers.view(player.getUniqueId(), slot);
        return view != null && !view.pending()
                && containers.setPaused(player.getUniqueId(), slot, view.core(), view.revision(), false, System.currentTimeMillis());
    }

    GeneratorContainerCoordinator.Result tick(GeneratorStore.Entry entry, long now) {
        var world = Bukkit.getWorld(entry.world());
        if (world == null || !world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)) {
            plugin.publishState(entry.owner(), entry.slot(), "state-unloaded");
            return GeneratorContainerCoordinator.Result.WAIT;
        }
        var core = world.getBlockAt(entry.x(), entry.y(), entry.z());
        if (entry.modules().getOrDefault("chunk", 0) > 0) core.getChunk().addPluginChunkTicket(plugin);
        var chamber = GeneratorChamber.scan(GeneratorCells.scan(world, entry.x(), entry.y(), entry.z()));
        plugin.publishState(entry.owner(), entry.slot(), GeneratorCells.stateKey(chamber.state()));
        var result = settle(entry, now, chamber.working());
        if (result == GeneratorContainerCoordinator.Result.READY || result == GeneratorContainerCoordinator.Result.BACKLOG) {
            payAndSell(entry, now, result == GeneratorContainerCoordinator.Result.BACKLOG, false);
            plugin.holograms().update(entry.owner(), entry.slot());
        }
        return result;
    }

    private GeneratorContainerCoordinator.Result settle(GeneratorStore.Entry entry, long now, boolean working) {
        var binding = plugin.store().containerBinding(entry.owner(), entry.slot());
        if (binding != null && "RELEASE".equals(binding.transition())) {
            GeneratorContainerLifecycle.release(plugin.store(), containers, entry.owner(), entry.slot());
            return GeneratorContainerCoordinator.Result.WAIT;
        }
        var config = plugin.config();
        var tier = config.tier(entry.tier());
        int luck = entry.modules().getOrDefault("luck", 0);
        double bonus = GeneratorMath.rebirthBonusPercent(config.rebirthPercentPerRebirth(), config.rebirthDecay(),
                config.rebirthBonusCapPercent(), entry.rebirths());
        return GeneratorContainerCoordinator.run(plugin.store(), containers, entry.owner(), entry.slot(), now,
                tier.drops(), luck, frozen -> GeneratorDrops.roll(frozen.drops(), frozen.luck(), random),
                () -> GeneratorDrops.roll(tier.drops(), luck, random), GeneratorContainers::stackSize,
                GeneratorContainers::fuel, units -> GeneratorDrops.money(tier.money(), Math.toIntExact(units), luck, random),
                working, GeneratorStats.cycleSeconds(entry, config) * 1000.0, tier.unitsPerCycle(), bonus,
                entry.modules().getOrDefault("chunk", 0) > 0, config.offlineEfficiency(),
                config.offlineHours() * 3_600_000L, GeneratorStats.capacityUnits(entry, config));
    }

    private boolean payAndSell(GeneratorStore.Entry entry, long now, boolean backlog, boolean forceDebt) {
        Player player = Bukkit.getPlayer(entry.owner());
        if (player == null || !player.isOnline() || !plugin.coreApi().isAuthenticated(player)) return false;
        var view = containers.view(entry.owner(), entry.slot());
        var state = containers.saleState(entry.owner(), entry.slot());
        if (view == null || state == null) return false;
        long count = view.slots().subList(0, GeneratorContainerStore.RESOURCE_SLOTS).stream()
                .filter(java.util.Objects::nonNull).mapToLong(GeneratorContainerStore.Stack::amount).sum();
        boolean full = view.slots().subList(0, GeneratorContainerStore.RESOURCE_SLOTS).stream()
                .allMatch(item -> item != null && stackSize(item.material()) != null && item.amount() >= stackSize(item.material()));
        boolean sell = !forceDebt && !view.paused() && plugin.config().autoSellEnabled() && (backlog || full || count >= GeneratorStats.capacityUnits(entry, plugin.config()));
        String id = entry.owner() + ":" + entry.slot();
        if (!forceDebt && !sell && state.pending() == null && state.moneyDebt() < GeneratorPayoutCoordinator.MAX_OBLIGATION
                && now < nextMoney.getOrDefault(id, 0L)) return true;
        nextMoney.put(id, now + 60_000L);
        var result = GeneratorContainerSale.run(containers, entry.owner(), entry.slot(), sell, (material, amount) -> {
            long value = GeneratorShopHook.sellValue(plugin.getLogger(), material, amount);
            return value >= 0 ? value : (long) amount * plugin.config().autoSellFallbackPrice();
        }, new GeneratorContainerSale.Ledger() {
            public Map<String, Long> reservations(UUID owner) { return plugin.moneyLoot().reservations(owner); }
            public boolean accept(String key, long amount) { return plugin.moneyLoot().accept(player, key, amount); }
        });
        if (result == GeneratorContainerSale.Result.CONFLICT) {
            plugin.getLogger().warning("Автопродажа генератора требует сверки журнала и файлов: " + id);
            return false;
        }
        return true;
    }

    private static Integer stackSize(String name) {
        Material material = Material.matchMaterial(name);
        return material == null || !material.isItem() || material.isAir() ? null : material.getMaxStackSize();
    }
    private static GeneratorFurnaceFuel.Fuel fuel(String name) {
        Material material = Material.matchMaterial(name);
        return material == null || !material.isItem() || material.isAir() ? null
                : GeneratorFurnaceFuel.inspect(new ItemStack(material)).orElse(null);
    }

    long count(UUID owner, int slot) {
        var view = containers.view(owner, slot);
        return view == null ? 0 : view.slots().subList(0, GeneratorContainerStore.RESOURCE_SLOTS).stream()
                .filter(java.util.Objects::nonNull).mapToLong(GeneratorContainerStore.Stack::amount).sum();
    }
}
