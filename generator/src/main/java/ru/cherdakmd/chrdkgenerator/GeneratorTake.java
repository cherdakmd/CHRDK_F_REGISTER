package ru.cherdakmd.chrdkgenerator;

import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.EconomyConfirmations;

/**
 * Снятие генератора командой {@code /gen take} или кнопкой меню.
 *
 * <p>Двухшаговое подтверждение через {@link EconomyConfirmations} (TTL 30
 * секунд): первый вызов котирует намерение, повторный в TTL реально снимает
 * ядро. Любая попытка одноразова — смена условий её отклоняет (п.31, §9).</p>
 */
final class GeneratorTake {

    /** Итог запроса: сколько единиц бункера выдано при снятии. */
    record Result(Status status, long units) {
        enum Status {
            NO_GENERATOR,
            TOO_FAR,
            PENDING,
            DONE
        }
    }

    private static final Object SESSION = "gen-take";

    private final GeneratorPlugin plugin;
    private final EconomyConfirmations confirmations = new EconomyConfirmations();

    GeneratorTake(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    private static Object quote(int slot) {
        return "core:" + slot;
    }

    Result request(Player player, int slot) {
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId(), slot);
        if (entry == null || !entry.installed()) {
            return new Result(Result.Status.NO_GENERATOR, 0L);
        }
        if (!plugin.nearCore(player, entry)) {
            return new Result(Result.Status.TOO_FAR, 0L);
        }
        long now = System.nanoTime();
        Object quote = quote(slot);
        if (!confirmations.consume(player.getUniqueId(), SESSION, quote, now)) {
            confirmations.offer(player.getUniqueId(), SESSION, quote, now);
            return new Result(Result.Status.PENDING, 0L);
        }
        long units = plugin.store().remove(player.getUniqueId(), slot, System.currentTimeMillis());
        plugin.worldListener().removeCore(player, entry, Math.max(0L, units));
        return new Result(Result.Status.DONE, Math.max(0L, units));
    }
}
