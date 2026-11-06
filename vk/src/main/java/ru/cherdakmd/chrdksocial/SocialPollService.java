package ru.cherdakmd.chrdksocial;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Голосования в беседе сообщества: один голос на пользователя, TTL, итог.
 * Чистая логика без Bukkit и сети — одинаково работает для VK и MAX.
 */
public final class SocialPollService {

    public record Result(String id, String question, int yes, int no, boolean closedByTimer) { }

    private static final class OpenPoll {
        final String id;
        final String question;
        final long closesAt;
        final Set<String> voters = ConcurrentHashMap.newKeySet();
        int yes;
        int no;
        boolean closed;

        OpenPoll(String id, String question, long closesAt) {
            this.id = id;
            this.question = question;
            this.closesAt = closesAt;
        }
    }

    private final Map<String, OpenPoll> polls = new LinkedHashMap<>();
    private long sequence;

    public synchronized String open(String question, long durationSeconds, long now) {
        String clean = question == null ? "" : question.trim();
        if (clean.isEmpty()) return "";
        String id = Long.toString(++sequence);
        polls.put(id, new OpenPoll(id, clean.length() > 160 ? clean.substring(0, 160) : clean,
                now + Math.max(1L, durationSeconds) * 1000L));
        return id;
    }

    /** false — опрос закрыт, не найден или пользователь уже голосовал. */
    public synchronized boolean vote(String id, String voterKey, boolean yes, long now) {
        OpenPoll poll = polls.get(id);
        if (poll == null || voterKey == null || voterKey.isBlank()) return false;
        if (poll.closed || now >= poll.closesAt) {
            poll.closed = true;
            return false;
        }
        if (!poll.voters.add(voterKey)) return false;
        if (yes) poll.yes++;
        else poll.no++;
        return true;
    }

    public synchronized Optional<Result> close(String id, long now) {
        OpenPoll poll = polls.remove(id);
        if (poll == null) return Optional.empty();
        return Optional.of(new Result(poll.id, poll.question, poll.yes, poll.no, now >= poll.closesAt));
    }

    public synchronized Optional<String> activeText(String id, long now) {
        OpenPoll poll = polls.get(id);
        if (poll == null || poll.closed || now >= poll.closesAt) return Optional.empty();
        return Optional.of("🗳 " + poll.question + "\n✅ " + poll.yes + " · ❌ " + poll.no);
    }

    public synchronized int purgeExpired(long now) {
        int before = polls.size();
        polls.values().removeIf(poll -> poll.closed || now >= poll.closesAt);
        return before - polls.size();
    }

    public synchronized int activeCount() {
        return polls.size();
    }
}
