package ru.cherdakmd.chrdkgenerator;

import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.EconomyConfirmations;

/** Снятие пустого генератора после остановки и устойчивой сверки выплат. */
final class GeneratorTake {
    record Result(Status status, long units) {
        enum Status { NO_GENERATOR, TOO_FAR, CONTENTS, PAYMENTS, INVENTORY, UNAVAILABLE, PENDING, DONE }
    }
    private static final Object SESSION = "gen-take";
    private final GeneratorPlugin plugin;
    private final EconomyConfirmations confirmations = new EconomyConfirmations();
    GeneratorTake(GeneratorPlugin plugin) { this.plugin = plugin; }

    Result request(Player player, int slot) { return attempt(player, slot, true); }
    Result breakCore(Player player, int slot) { return attempt(player, slot, false); }
    private static Result result(Result.Status status) { return new Result(status, 0); }

    private Result attempt(Player player, int slot, boolean confirm) {
        var owner = player.getUniqueId();
        var entry = plugin.store().entry(owner, slot);
        if (entry == null || !entry.installed()) return result(Result.Status.NO_GENERATOR);
        if (!plugin.nearCore(player, entry)) return result(Result.Status.TOO_FAR);
        try {
            var binding = plugin.store().containerBinding(owner, slot);
            boolean releasing = binding != null && "RELEASE".equals(binding.transition());
            if (!releasing && !plugin.containers().pauseForTake(player, slot)) return result(Result.Status.UNAVAILABLE);
            if (confirm) {
                long now = System.nanoTime();
                Object quote = "core:" + entry.coreItem() + ":" + slot;
                if (!confirmations.consume(owner, SESSION, quote, now)) {
                    confirmations.offer(owner, SESSION, quote, now);
                    return result(Result.Status.PENDING);
                }
            }
            var cursor = player.getItemOnCursor();
            if (cursor != null && !cursor.getType().isAir()) return result(Result.Status.INVENTORY);
            boolean room = false;
            for (var item : player.getInventory().getStorageContents()) {
                if (item == null || item.getType().isAir()) { room = true; break; }
            }
            if (!room) return result(Result.Status.INVENTORY);
            if (!releasing) {
                var view = plugin.containerStore().view(owner, slot);
                var sale = plugin.containerStore().saleState(owner, slot);
                if (view == null || sale == null || view.pending()) return result(Result.Status.UNAVAILABLE);
                if (entry.bunker() != 0 || view.slots().stream().anyMatch(java.util.Objects::nonNull)) return result(Result.Status.CONTENTS);
                if (sale.pending() != null || sale.moneyDebt() != 0) return result(Result.Status.PAYMENTS);
            }
            var release = GeneratorContainerLifecycle.release(plugin.store(), plugin.containerStore(), owner, slot);
            if (release == GeneratorContainerLifecycle.Result.NOT_EMPTY) return result(Result.Status.CONTENTS);
            if (release != GeneratorContainerLifecycle.Result.READY) return result(Result.Status.UNAVAILABLE);
            if (!plugin.coreTake().start(player, slot)) return result(Result.Status.UNAVAILABLE);
            return result(Result.Status.DONE);
        } catch (java.io.IOException | RuntimeException failure) {
            plugin.getLogger().warning("Снятие генератора ожидает сверки: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return result(Result.Status.UNAVAILABLE);
        }
    }

    void message(Player player, Result result) {
        switch (result.status()) {
            case NO_GENERATOR -> plugin.messages().send(player, "take-none");
            case TOO_FAR -> plugin.messages().send(player, "command-too-far", "range", String.valueOf(plugin.config().menuRange()));
            case CONTENTS -> plugin.messages().send(player, "take-contents");
            case PAYMENTS -> plugin.messages().send(player, "take-payments");
            case INVENTORY -> plugin.messages().send(player, "take-inventory");
            case UNAVAILABLE -> plugin.messages().send(player, "take-unavailable");
            case PENDING -> plugin.messages().send(player, "take-paused-confirm");
            case DONE -> plugin.messages().send(player, "take-ok", "bunker", String.valueOf(result.units()));
        }
    }
}
