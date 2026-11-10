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

    /** Лидер номинации: ник и его величина. {@code null}, если данных нет. */
    public record Leader(String name, long value) {
    }

    public record Digest(
            String day,
            int prestigeEvents,
            int donationEvents,
            int peakOnline,
            int newPlayers,
            long blocksBroken,
            long diamondsGained,
            long mobKills,
            List<String> leaders,
            long total) {
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
        state.blocksBroken = 0;
        state.diamondsGained = 0;
        state.mobKills = 0;
        state.blocksByPlayer.clear();
        state.diamondsByPlayer.clear();
        state.mobKillsByPlayer.clear();
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

    /** Один сломанный игроком блок. Считается только факт, не тип и не координаты. */
    public synchronized void recordBlocksBroken(long blocks) {
        recordBlocksBroken(null, blocks);
    }

    /** То же, но с ником: нужно для карточки «Шахтёр дня». */
    public synchronized void recordBlocksBroken(String playerName, long blocks) {
        if (blocks > 0) {
            state.blocksBroken = addCapped(state.blocksBroken, blocks);
            addTo(state.blocksByPlayer, playerName, blocks);
        }
    }

    /** Алмазы, реально подобранные игроком: сумма количеств выпавших предметов. */
    public synchronized void recordDiamonds(long diamonds) {
        recordDiamonds(null, diamonds);
    }

    public synchronized void recordDiamonds(String playerName, long diamonds) {
        if (diamonds > 0) {
            state.diamondsGained = addCapped(state.diamondsGained, diamonds);
            addTo(state.diamondsByPlayer, playerName, diamonds);
        }
    }

    /** Победа над враждебным мобом; игроки и временные мобы событий не дают. */
    public synchronized void recordMobKills(long kills) {
        recordMobKills(null, kills);
    }

    public synchronized void recordMobKills(String playerName, long kills) {
        if (kills > 0) {
            state.mobKills = addCapped(state.mobKills, kills);
            addTo(state.mobKillsByPlayer, playerName, kills);
        }
    }

    /** Лидер по добытым блокам за сутки. */
    public synchronized Leader topBlocks() {
        return top(state.blocksByPlayer);
    }

    /** Лидер по поднятым алмазам за сутки. */
    public synchronized Leader topDiamonds() {
        return top(state.diamondsByPlayer);
    }

    /** Лидер по победам над мобами за сутки. */
    public synchronized Leader topMobKills() {
        return top(state.mobKillsByPlayer);
    }

    /** Лидер по последнему известному престижу (снимок переживает рестарт). */
    public synchronized Leader topPrestige() {
        Leader best = null;
        for (Map.Entry<String, Integer> row : state.prestigeSnapshot.entrySet()) {
            int value = row.getValue() == null ? 0 : row.getValue();
            if (value <= 0 || row.getKey() == null || row.getKey().isBlank()) {
                continue;
            }
            if (best == null || value > best.value()
                    || (value == best.value() && row.getKey().compareToIgnoreCase(best.name()) < 0)) {
                best = new Leader(row.getKey(), value);
            }
        }
        return best;
    }

    /** Сколько алмазов поднял конкретный игрок за эти сутки. */
    public synchronized long diamondsOf(String playerName) {
        if (playerName == null) {
            return 0L;
        }
        return state.diamondsByPlayer.getOrDefault(playerName, 0L);
    }

    private static void addTo(Map<String, Long> target, String playerName, long delta) {
        String name = playerName == null ? "" : playerName.trim();
        if (name.isEmpty()) {
            return;
        }
        long current = target.getOrDefault(name, 0L);
        target.put(name, addCapped(current, delta));
    }

    private static Leader top(Map<String, Long> source) {
        Leader best = null;
        for (Map.Entry<String, Long> row : source.entrySet()) {
            long value = row.getValue() == null ? 0L : row.getValue();
            if (value <= 0 || row.getKey() == null || row.getKey().isBlank()) {
                continue;
            }
            if (best == null || value > best.value()
                    || (value == best.value() && row.getKey().compareToIgnoreCase(best.name()) < 0)) {
                best = new Leader(row.getKey(), value);
            }
        }
        return best;
    }

    /** Счётчик не должен уронить слушатель события даже за очень активные сутки. */
    private static long addCapped(long current, long delta) {
        return Long.MAX_VALUE - current < delta ? Long.MAX_VALUE : current + delta;
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
        long total = activity();
        return new Digest(state.day, state.prestigeEvents, state.donationEvents,
                state.peakOnline, state.newPlayers, state.blocksBroken,
                state.diamondsGained, state.mobKills, List.copyOf(leaders), total);
    }

    /** Сумма всех суточных событий; новые счётчики тоже будят «пустой» день. */
    private synchronized long activity() {
        return (long) state.prestigeEvents + state.donationEvents + state.newPlayers
                + state.blocksBroken + state.diamondsGained + state.mobKills;
    }

    public synchronized boolean shouldPost(String day, int minEvents) {
        rollDay(day);
        if (day == null || day.equals(state.lastPostedDay) || day.equals(state.skippedDay)) {
            return false;
        }
        if (day.equals(state.attemptDay) && state.attempts >= MAX_ATTEMPTS) {
            return false;
        }
        return activity() >= Math.max(0, minEvents);
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
