package ru.cherdakmd.chrdkvk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Чистая JVM-логика расписания четырёх карточек: без Bukkit, сети и графики.
 *
 * <p>Каждая номинация публикуется один раз в сутки. Перед сетевой частью
 * сохраняется отметка {@code posting}; если процесс прервался между
 * {@code wall.post} и записью ledger, такие сутки пропускаются — дубль на
 * публичной стене хуже одного пропущенного поста.</p>
 */
public final class DailyCardsLedger {

    private final DailyCardsStore store;
    private final DailyCardsStore.State state;

    public DailyCardsLedger(DailyCardsStore store, DailyCardsStore.State state) {
        this.store = store;
        this.state = state == null ? new DailyCardsStore.State() : state;
    }

    /** Какие карточки пора публиковать прямо сейчас. */
    public synchronized List<DailyCardKind> due(DailyCardsConfig config, Instant now) {
        DailyCardsConfig cfg = config == null ? DailyCardsConfig.DEFAULT : config;
        rollDay(cfg.dayKey(now));
        if (!cfg.enabled()) {
            return List.of();
        }
        List<DailyCardKind> out = new ArrayList<>();
        for (DailyCardKind kind : DailyCardKind.values()) {
            String key = kind.id();
            if (state.posted.containsKey(key) || state.posting.containsKey(key)) {
                continue;
            }
            int attempts = state.attempts.getOrDefault(key, 0);
            if (attempts >= DailyCardsConfig.MAX_ATTEMPTS) {
                continue;
            }
            if (cfg.isSlotTime(kind, now) || (attempts > 0 && cfg.isRetryTime(kind, now))) {
                out.add(kind);
            }
        }
        return List.copyOf(out);
    }

    /** Незавершённые публикации после рестарта: пропускаем, не повторяем. */
    public synchronized List<DailyCardKind> takeUnresolvedPosting() {
        List<DailyCardKind> skipped = new ArrayList<>();
        for (DailyCardKind kind : DailyCardKind.values()) {
            if (Boolean.TRUE.equals(state.posting.remove(kind.id()))) {
                state.posted.putIfAbsent(kind.id(), 0L);
                skipped.add(kind);
            }
        }
        return List.copyOf(skipped);
    }

    public synchronized void markAttempt(DailyCardKind kind) {
        state.attempts.merge(kind.id(), 1, Integer::sum);
    }

    public synchronized void markPosting(DailyCardKind kind) {
        state.posting.put(kind.id(), true);
    }

    public synchronized void markPosted(DailyCardKind kind, long postId) {
        state.posted.put(kind.id(), postId);
        state.posting.remove(kind.id());
    }

    public synchronized void abandonPosting(DailyCardKind kind) {
        state.posting.remove(kind.id());
    }

    public synchronized boolean isPosted(DailyCardKind kind) {
        return state.posted.containsKey(kind.id());
    }

    public synchronized int attempts(DailyCardKind kind) {
        return state.attempts.getOrDefault(kind.id(), 0);
    }

    public synchronized long postedId(DailyCardKind kind) {
        return state.posted.getOrDefault(kind.id(), 0L);
    }

    public synchronized String day() {
        return state.day == null ? "" : state.day;
    }

    /** Только для тестов: текущее состояние без копирования. */
    synchronized DailyCardsStore.State state() {
        return state;
    }

    public synchronized void save() {
        store.save(state);
    }

    private void rollDay(String day) {
        if (day == null || day.isBlank() || day.equals(state.day)) {
            return;
        }
        state.day = day;
        state.posted.clear();
        state.attempts.clear();
        state.posting.clear();
    }
}
