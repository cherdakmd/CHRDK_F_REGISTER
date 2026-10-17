package ru.cherdakmd.chrdkteleports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Реестр активных TPA-запросов (чистая логика — без Bukkit, время передаётся снаружи).
 * У цели — не более одного входящего запроса, у заявителя — не более одного исходящего.
 */
public final class TpaRequests {

    public enum CreateResult {OK, SELF, OUTGOING_EXISTS, INCOMING_EXISTS}

    /** @param here true = заявитель зовёт цель К СЕБЕ (tpahere); false = заявитель идёт к цели. */
    public record Request(UUID requester, UUID target, boolean here, long expiresAtMillis) {
    }

    private final Map<UUID, Request> byTarget = new LinkedHashMap<>();

    /**
     * Пытается зарегистрировать запрос. Устаревшие записи молча заменяются.
     */
    public synchronized CreateResult create(UUID requester, UUID target, boolean here,
                                            long nowMillis, long timeoutSeconds) {
        if (requester.equals(target)) {
            return CreateResult.SELF;
        }
        if (findOutgoing(requester, nowMillis) != null) {
            return CreateResult.OUTGOING_EXISTS;
        }
        Request existing = activeByTarget(target, nowMillis);
        if (existing != null) {
            return CreateResult.INCOMING_EXISTS;
        }
        byTarget.put(target, new Request(requester, target, here,
                nowMillis + timeoutSeconds * 1000L));
        return CreateResult.OK;
    }

    /** Принятие: забирает запрос (null — нет или истёк). */
    public synchronized Request accept(UUID target, long nowMillis) {
        Request request = activeByTarget(target, nowMillis);
        if (request != null) {
            byTarget.remove(target);
        }
        return request;
    }

    /** Отказ: убирает запрос (null — нет или истёк). */
    public synchronized Request deny(UUID target, long nowMillis) {
        return accept(target, nowMillis);
    }

    /** Отзыв заявителем своего исходящего. */
    public synchronized Request cancelOutgoing(UUID requester, long nowMillis) {
        Request request = findOutgoing(requester, nowMillis);
        if (request != null) {
            byTarget.remove(request.target());
        }
        return request;
    }

    /** Текущий входящий запрос цели (для подсказок), null если нет/истёк. */
    public synchronized Request incomingFor(UUID target, long nowMillis) {
        return activeByTarget(target, nowMillis);
    }

    /** Вычищает просроченные запросы и возвращает их для уведомлений. */
    public synchronized List<Request> sweepExpired(long nowMillis) {
        List<Request> expired = new ArrayList<>();
        byTarget.values().removeIf(request -> {
            boolean stale = request.expiresAtMillis() <= nowMillis;
            if (stale) {
                expired.add(request);
            }
            return stale;
        });
        return expired;
    }

    public synchronized int size() {
        return byTarget.size();
    }

    private Request activeByTarget(UUID target, long nowMillis) {
        Request request = byTarget.get(target);
        if (request == null) {
            return null;
        }
        if (request.expiresAtMillis() <= nowMillis) {
            byTarget.remove(target);
            return null;
        }
        return request;
    }

    private Request findOutgoing(UUID requester, long nowMillis) {
        for (Map.Entry<UUID, Request> entry : byTarget.entrySet()) {
            Request request = entry.getValue();
            if (request.requester().equals(requester)) {
                if (request.expiresAtMillis() <= nowMillis) {
                    byTarget.remove(entry.getKey());
                    return null;
                }
                return request;
            }
        }
        return null;
    }
}
