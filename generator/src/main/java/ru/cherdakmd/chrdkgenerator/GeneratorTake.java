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
            PENDING,
            DONE
        }
    }

    private static final Object SESSION = "gen-take";
    private static final Object QUOTE = "core";

    private final GeneratorPlugin plugin;
    private final EconomyConfirmations confirmations = new EconomyConfirmations();

    GeneratorTake(GeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    Result request(Player player) {
        GeneratorStore.Entry entry = plugin.store().entry(player.getUniqueId());
        if (entry == null || !entry.installed()) {
            return new Result(Result.Status.NO_GENERATOR, 0L);
        }
        long now = System.nanoTime();
        if (!confirmations.consume(player.getUniqueId(), SESSION, QUOTE, now)) {
            confirmations.offer(player.getUniqueId(), SESSION, QUOTE, now);
            return new Result(Result.Status.PENDING, 0L);
        }
        long units = plugin.store().remove(player.getUniqueId(), System.currentTimeMillis());
        plugin.worldListener().removeCore(player, entry, Math.max(0L, units));
        return new Result(Result.Status.DONE, Math.max(0L, units));
    }
}
