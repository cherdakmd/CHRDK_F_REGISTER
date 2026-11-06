package ru.cherdakmd.chrdksocial;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Тикет «Позвать администратора» из беседы. Только ник и короткий текст:
 * IP, UUID, координаты и приватные данные не передаются. Кулдаун на игрока
 * не даёт завалить администрацию повторами.
 */
public final class SocialTicketService {

    public record Ticket(String id, String player, String text, long at) { }

    private final Map<String, Long> lastByPlayer = new ConcurrentHashMap<>();
    private long sequence;

    public synchronized Optional<Ticket> create(String player, String text,
                                                long now, long cooldownMillis) {
        String safePlayer = player == null ? "" : player.trim();
        if (safePlayer.isEmpty()) return Optional.empty();
        Long previous = lastByPlayer.get(safePlayer.toLowerCase(java.util.Locale.ROOT));
        if (cooldownMillis > 0L && previous != null && now - previous < cooldownMillis) {
            return Optional.empty();
        }
        String safeText = text == null ? "" : text.trim();
        if (safeText.length() > 200) safeText = safeText.substring(0, 200);
        lastByPlayer.put(safePlayer.toLowerCase(java.util.Locale.ROOT), now);
        return Optional.of(new Ticket("T" + (++sequence), safePlayer, safeText, now));
    }

    public int pendingPlayers() {
        return lastByPlayer.size();
    }
}
