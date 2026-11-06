package ru.cherdakmd.chrdksocial;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Одно активное состояние диалога на «провайдер:пользователь» с TTL.
 * Общий для VK и MAX: подтверждение опасного действия, мастер привязки,
 * ввод причины тикета. Просроченные состояния не выполняются.
 */
public final class SocialDialogStore {

    public record State(String kind, String arg, long expiresAt) {
        public boolean matches(String expected) {
            return kind.equals(expected);
        }
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public void begin(String key, String kind, String arg, long ttlMillis, long now) {
        if (key == null || key.isBlank() || kind == null || kind.isBlank()) return;
        states.put(key, new State(kind, arg == null ? "" : arg, now + Math.max(1L, ttlMillis)));
    }

    /** Возвращает состояние или null, если диалога нет либо он просрочен. */
    public State peek(String key, long now) {
        if (key == null) return null;
        State state = states.get(key);
        if (state == null) return null;
        if (state.expiresAt() <= now) {
            states.remove(key, state);
            return null;
        }
        return state;
    }

    public void end(String key) {
        if (key != null) states.remove(key);
    }

    public int purge(long now) {
        int before = states.size();
        states.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        return before - states.size();
    }

    public int size() {
        return states.size();
    }
}
