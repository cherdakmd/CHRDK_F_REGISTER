package ru.cherdakmd.chrdkclasses;

import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;
import ru.cherdakmd.chrdkskills.tree.PerkTreeState;
import ru.cherdakmd.chrdkskills.tree.PerkTreeStore;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Единственная серверная точка покупки узлов, выбора приёма и сброса дерева.
 * Команды и GUI проходят через неё, поэтому их проверки не расходятся.
 *
 * <p>Сброс — двухшаговый: {@link #requestReset} выдаёт одноразовый токен на
 * 30 секунд, {@link #confirmReset} сверяет токен, домен и срок. Повторное
 * подтверждение не создаёт новую операцию. Каждая операция пишется в
 * {@link PerkTreeAudit}; возврат пыли и токенов фиксируется до записи.</p>
 */
final class PerkTreeService {

    static final long RESET_TTL_MILLIS = 30_000L;

    record Result(boolean ok, String reason, PerkTreeState state) {
    }

    private record Pending(TreeDomain domain, String token, long expiresAt) {
    }

    private final PerkTreeStore store;
    private final PerkTreeAudit audit;
    private final Map<UUID, Pending> pending = new HashMap<>();

    PerkTreeService(PerkTreeStore store, PerkTreeAudit audit) {
        this.store = store;
        this.audit = audit;
    }

    PerkTreeState state(UUID player, TreeDomain domain, int classLevel) {
        if (!store.available()) {
            return null;
        }
        PerkTreeState existing = store.state(player, domain);
        if (existing != null) {
            return existing;
        }
        // Внешние домены (MINING, EXPLORATION, HUNT) не получают уровень из
        // класса: пока состояние не заведено, дерево просто недоступно.
        try {
            return ClassPerkTreeBridge.initialState(domain, classLevel);
        } catch (RuntimeException error) {
            return null;
        }
    }

    Result unlock(UUID player, TreeDomain domain, String nodeId, int classLevel) {
        PerkTreeState before = state(player, domain, classLevel);
        if (before == null) {
            return fail(null, "дерево недоступно");
        }
        try {
            PerkTreeState after = before.unlock(
                    PerkTreeRegistry.of(domain).orElseThrow(), nodeId);
            store.putState(player, after);
            audit.record("unlock", player, domain.id(), 0L, 0, nodeId);
            return new Result(true, "ok", after);
        } catch (RuntimeException error) {
            return fail(before, error.getMessage());
        }
    }

    Result select(UUID player, TreeDomain domain, String nodeId, int classLevel) {
        PerkTreeState before = state(player, domain, classLevel);
        if (before == null) {
            return fail(null, "дерево недоступно");
        }
        try {
            PerkTreeState after = before.selectAbility(
                    PerkTreeRegistry.of(domain).orElseThrow(), nodeId);
            store.putState(player, after);
            audit.record("select", player, domain.id(), 0L, 0, nodeId);
            return new Result(true, "ok", after);
        } catch (RuntimeException error) {
            return fail(before, error.getMessage());
        }
    }

    /** Возвращает токен в {@code reason}; состояние при этом не меняется. */
    Result requestReset(UUID player, TreeDomain domain, int classLevel, long now) {
        if (state(player, domain, classLevel) == null) {
            return fail(null, "дерево недоступно");
        }
        String token = UUID.randomUUID().toString().substring(0, 8);
        pending.put(player, new Pending(domain, token, now + RESET_TTL_MILLIS));
        return new Result(true, token, state(player, domain, classLevel));
    }

    Result confirmReset(UUID player, String token, long now) {
        Pending request = pending.remove(player);
        if (request == null || token == null || !token.equals(request.token())
                || now > request.expiresAt()) {
            return fail(null, "подтверждение недействительно");
        }
        PerkTreeState before = store.state(player, request.domain());
        if (before == null) {
            return fail(null, "дерево недоступно");
        }
        long refundPowder = before.refundablePowder();
        int refundTokens = before.tokensSpent();
        PerkTreeState after = before.reset();
        store.putState(player, after);
        audit.record("reset", player, request.domain().id(),
                refundPowder, refundTokens, token);
        return new Result(true, "ok", after);
    }

    private static Result fail(PerkTreeState state, String reason) {
        return new Result(false, reason == null ? "отказ" : reason, state);
    }
}
