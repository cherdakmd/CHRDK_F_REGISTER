package ru.cherdakmd.chrdkvk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import ru.cherdakmd.chrdksocial.SocialEventText;

/**
 * Чистая логика суточного дайджеста: без Bukkit, сети и графики.
 *
 * <p>События считаются за игровые сутки в часовом поясе владельца. Престиж
 * определяется сравнением с последним снимком ({@link #observePrestige}),
 * поэтому не требует правок модуля навыков и не поднимает API_VERSION.
 * Имена лидеров проходят через opt-out, счётчики — нет: обезличенная цифра
 * не раскрывает игрока.</p>
 */
public final class WallDigestCollector {

    /** Не более трёх попыток публикации за сутки, чтобы сбой VK не спамил стену. */
    public static final int MAX_ATTEMPTS = 3;

    public record Digest(
            String day,
            int prestigeEvents,
            int donationEvents,
            int peakOnline,
            int newPlayers,
            List<String> leaders,
            int total) {
    }

    private final WallDigestStore store;
    private final WallDigestStore.State state;
    private final Predicate<String> visible;
    private volatile WallDigestConfig config;

    public WallDigestCollector(WallDigestStore store, WallDigestStore.State state,
                               Predicate<String> visible, WallDigestConfig config) {
        this.store = store;
        this.state = state == null ? new WallDigestStore.State() : state;
        this.visible = visible == null ? name -> true : visible;
        this.config = config == null ? WallDigestConfig.DEFAULT : config;
    }

    /** Живая перезагрузка конфига плагина без рестарта сервера. */
    public synchronized void applyConfig(WallDigestConfig next) {
        if (next != null) {
            this.config = next;
        }
    }

    public synchronized void rollDay(String day) {
        if (day == null || day.isBlank() || day.equals(state.day)) {
            return;
        }
        state.day = day;
        state.prestigeEvents = 0;
        state.donationEvents = 0;
        state.peakOnline = 0;
        state.newPlayers = 0;
        state.prestigeGains.clear();
        state.postingDay = ""; // прошлая незавершённая попытка больше не актуальна
        // Снимок престижа и lastPostedDay намеренно сохраняются между сутками.
    }

    /** Сравнивает текущее значение престижа с сохранённым и фиксирует прирост. */
    public synchronized void observePrestige(String playerName, int current) {
        String name = SocialEventText.safePlayer(playerName);
        if (name.isEmpty() || current < 0) {
            return;
        }
        Integer previous = state.prestigeSnapshot.get(name);
        state.prestigeSnapshot.put(name, current);
        if (previous == null || current <= previous) {
            // Первое наблюдение или сброс престижа: событием не считаем.
            return;
        }
        int delta = Math.min(current - previous, 100);
        state.prestigeEvents += delta;
        state.prestigeGains.merge(name, delta, Integer::sum);
    }

    public synchronized void recordPrestige(String playerName, int delta) {
        if (delta <= 0) {
            return;
        }
        String name = SocialEventText.safePlayer(playerName);
        state.prestigeEvents += delta;
        if (!name.isEmpty()) {
            state.prestigeGains.merge(name, delta, Integer::sum);
        }
    }

    /** На стену уходит только сам факт поддержки — без суммы, товара и ника. */
    public synchronized void recordDonation(String playerName) {
        state.donationEvents++;
    }

    public synchronized void recordOnline(int online) {
        if (online > state.peakOnline) {
            state.peakOnline = Math.max(0, online);
        }
    }

    public synchronized void recordNewPlayer() {
        state.newPlayers++;
    }

    public synchronized Digest snapshot(String day) {
        rollDay(day);
        List<String> leaders = new ArrayList<>();
        if (config.showPlayerNames()) {
            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(state.prestigeGains.entrySet());
            ranked.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
            for (Map.Entry<String, Integer> entry : ranked) {
                if (leaders.size() >= config.maxNames()) {
                    break;
                }
                if (visible.test(entry.getKey())) {
                    leaders.add(entry.getKey());
                }
            }
        }
        int total = state.prestigeEvents + state.donationEvents + state.newPlayers;
        return new Digest(state.day, state.prestigeEvents, state.donationEvents,
                state.peakOnline, state.newPlayers, List.copyOf(leaders), total);
    }

    public synchronized boolean shouldPost(String day, int minEvents) {
        rollDay(day);
        if (day == null || day.equals(state.lastPostedDay) || day.equals(state.skippedDay)) {
            return false;
        }
        if (day.equals(state.attemptDay) && state.attempts >= MAX_ATTEMPTS) {
            return false;
        }
        int total = state.prestigeEvents + state.donationEvents + state.newPlayers;
        return total >= Math.max(0, minEvents);
    }

    public synchronized void markAttempt(String day) {
        if (!day.equals(state.attemptDay)) {
            state.attemptDay = day;
            state.attempts = 0;
        }
        state.attempts++;
    }

    public synchronized void markPosting(String day) {
        state.postingDay = day;
    }

    public synchronized void markPosted(String day, long postId) {
        state.lastPostedDay = day;
        state.lastPostId = postId;
        state.postingDay = "";
    }

    /** Есть ли незавершённая попытка за эти сутки (окно повторов). */
    public synchronized boolean hasPending(String day) {
        return day != null && day.equals(state.postingDay) && !day.equals(state.lastPostedDay);
    }

    /**
     * Возвращает сутки, на которых процесс прервался между wall.post и записью
     * состояния, и снимает отметку. Повторная публикация таких суток не делается:
     * дубль на публичной стене хуже одного пропущенного дайджеста.
     */
    public synchronized String takeUnresolvedPosting() {
        String pending = state.postingDay == null ? "" : state.postingDay;
        state.postingDay = "";
        if (!pending.isEmpty()) {
            state.skippedDay = pending;
        }
        return pending;
    }

    public synchronized void save() {
        store.save(state);
    }
}
