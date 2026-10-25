package ru.cherdakmd.chrdkguilds;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Война двух гильдий (1.4.163).
 *
 * <p>Война — это ставка из казны и время, а не бесконечная вражда. Обе стороны
 * кладут равную ставку в банк войны; победитель по очкам забирает обе. Ставка
 * списывается при объявлении, поэтому «объявил и передумал» не бесплатно:
 * отказ от боя возвращает деньги, но тратит время и кулдаун.</p>
 *
 * <p>Очки дают убийства участников вражеской гильдии. Одна и та же пара не
 * может накручивать счёт: пара «убийца — жертва» даёт очки не чаще окна из
 * конфига, причём пара считается без направления, чтобы перебивание друг друга
 * не обходило защиту.</p>
 */
final class GuildWar {

    /** Состояние войны. */
    enum Status {
        /** Объявлена, ждёт согласия второй стороны. */
        PENDING,
        /** Идёт бой. */
        ACTIVE,
        /** Закончена: есть результат. */
        FINISHED
    }

    /** Чем закончилась война. */
    enum Result {
        /** Победил объявивший. */
        CHALLENGER_WIN,
        /** Победил защищавшийся. */
        TARGET_WIN,
        /** Ничья: ставки возвращаются. */
        DRAW,
        /** Отменена до начала: ставка объявившего возвращается. */
        CANCELLED
    }

    private final String id;
    private final String challengerId;
    private final String targetId;
    private long wager;
    private final long createdAt;
    private long startsAt;
    private long endsAt;
    private Status status = Status.PENDING;
    private Result result;
    private int challengerPoints;
    private int targetPoints;
    private final Map<String, Long> lastKills = new HashMap<>();

    GuildWar(String id, String challengerId, String targetId, long wager, long createdAt) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.challengerId = challengerId == null ? "" : challengerId;
        this.targetId = targetId == null ? "" : targetId;
        this.wager = Math.max(0L, wager);
        this.createdAt = createdAt;
    }

    /* ---------------------------------- доступ -------------------------------- */

    String id() {
        return id;
    }

    String challengerId() {
        return challengerId;
    }

    String targetId() {
        return targetId;
    }

    long wager() {
        return wager;
    }

    long createdAt() {
        return createdAt;
    }

    long startsAt() {
        return startsAt;
    }

    long endsAt() {
        return endsAt;
    }

    Status status() {
        return status;
    }

    Result result() {
        return result;
    }

    int challengerPoints() {
        return challengerPoints;
    }

    int targetPoints() {
        return targetPoints;
    }

    boolean pending() {
        return status == Status.PENDING;
    }

    boolean active() {
        return status == Status.ACTIVE;
    }

    boolean finished() {
        return status == Status.FINISHED;
    }

    boolean involves(String guildId) {
        return challengerId.equals(guildId) || targetId.equals(guildId);
    }

    /** Противник гильдии в этой войне или {@code null}. */
    String opponentOf(String guildId) {
        if (challengerId.equals(guildId)) {
            return targetId;
        }
        return targetId.equals(guildId) ? challengerId : null;
    }

    int pointsOf(String guildId) {
        if (challengerId.equals(guildId)) {
            return challengerPoints;
        }
        return targetId.equals(guildId) ? targetPoints : 0;
    }

    /** Гильдия, которая ведёт по очкам; {@code null} при равенстве. */
    String leader() {
        if (challengerPoints == targetPoints) {
            return null;
        }
        return challengerPoints > targetPoints ? challengerId : targetId;
    }

    /** Сколько секунд осталось до конца войны. */
    long secondsLeft(long now) {
        return endsAt <= now ? 0L : (endsAt - now + 999) / 1_000L;
    }

    /* ------------------------------- переходы -------------------------------- */

    /** Согласие второй стороны: война начинается сейчас. */
    void activate(long now, long durationSeconds) {
        this.status = Status.ACTIVE;
        this.startsAt = now;
        this.endsAt = now + Math.max(60L, durationSeconds) * 1_000L;
    }

    /** Отмена до начала: ставка возвращается. */
    void cancel(long now) {
        if (status == Status.FINISHED) {
            return;
        }
        this.status = Status.FINISHED;
        this.result = Result.CANCELLED;
        this.endsAt = Math.max(0L, now);
    }

    /** Принудительно задаёт счёт — для капитуляции, где победитель известен заранее. */
    void overridePoints(int challenger, int target) {
        this.challengerPoints = Math.max(0, challenger);
        this.targetPoints = Math.max(0, target);
    }

    /** Завершает войну и выбирает результат. */
    Result finish() {
        if (status == Status.FINISHED) {
            return result;
        }
        this.status = Status.FINISHED;
        this.result = challengerPoints == targetPoints
                ? Result.DRAW
                : challengerPoints > targetPoints ? Result.CHALLENGER_WIN : Result.TARGET_WIN;
        return result;
    }

    /* ---------------------------------- очки --------------------------------- */

    /**
     * Начисляет очки за убийство.
     *
     * @return сколько очков начислено; 0 — пара накручивает счёт слишком часто
     */
    int addKill(String guildId, UUID killer, UUID victim, boolean claimed, long now,
                long windowMillis) {
        if (!active() || guildId == null || killer == null || victim == null) {
            return 0;
        }
        String key = pairKey(killer, victim);
        long last = lastKills.getOrDefault(key, 0L);
        if (!GuildMath.countsKill(last, now, windowMillis)) {
            return 0;
        }
        lastKills.put(key, now);
        int points = GuildMath.killPoints(claimed);
        if (challengerId.equals(guildId)) {
            challengerPoints += points;
        } else if (targetId.equals(guildId)) {
            targetPoints += points;
        } else {
            return 0;
        }
        return points;
    }

    private static String pairKey(UUID killer, UUID victim) {
        String left = killer.toString();
        String right = victim.toString();
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }

    /* --------------------------------- данные --------------------------------- */

    /** Восстанавливает состояние из файла, зажимая числа в безопасные границы. */
    void restore(Status status, Result result, long startsAt, long endsAt, int challengerPoints,
                 int targetPoints, Map<String, Long> lastKills) {
        this.status = status == null ? Status.PENDING : status;
        this.result = result;
        this.startsAt = Math.max(0L, startsAt);
        this.endsAt = Math.max(0L, endsAt);
        this.challengerPoints = Math.max(0, challengerPoints);
        this.targetPoints = Math.max(0, targetPoints);
        this.lastKills.clear();
        if (lastKills != null) {
            for (Map.Entry<String, Long> entry : lastKills.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                this.lastKills.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
    }

    Map<String, Long> lastKills() {
        return Map.copyOf(lastKills);
    }

    /** Сводка для меню и логов. */
    String label() {
        return challengerId + " vs " + targetId + " (" + challengerPoints + ":"
                + targetPoints + ")";
    }
}
