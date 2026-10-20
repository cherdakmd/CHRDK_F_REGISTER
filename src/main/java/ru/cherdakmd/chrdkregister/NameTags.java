package ru.cherdakmd.chrdkregister;

import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Оформление ников, которое добавляют аддоны (API v6).
 *
 * <p>Ядро рисует ник в чате, личных сообщениях и (по желанию) в TAB. Аддон
 * регистрирует функцию, которая получает ник и уже оформленное ядром имя
 * (например, с плашкой мецената) и возвращает новое — с меткой стримера,
 * жирным ником и т.п. Несколько провайдеров применяются в порядке регистрации.</p>
 *
 * <p>Сбой одного провайдера не ломает чат: исключение логируется, а имя
 * остаётся таким, каким его собрали предыдущие шаги.</p>
 */
final class NameTags {

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final Logger logger;

    NameTags(Logger logger) {
        this.logger = logger;
    }

    /** Зарегистрировать оформление; повторный id заменяет прежний провайдер. */
    void register(String id, ChrdkRegisterApi.NameTagProvider provider) {
        if (id == null || id.isBlank() || provider == null) {
            return;
        }
        String key = id.trim();
        unregister(key);
        entries.add(new Entry(key, provider));
    }

    /** Убрать оформление по id. {@code true} — запись была. */
    boolean unregister(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String key = id.trim();
        return entries.removeIf(entry -> entry.id().equals(key));
    }

    /** Применить все оформления по очереди. */
    String decorate(String playerName, String decoratedName) {
        String result = decoratedName;
        for (Entry entry : entries) {
            try {
                String next = entry.provider().decorate(playerName, result);
                if (next != null) {
                    result = next;
                }
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Оформление ника '" + entry.id()
                        + "' упало — имя останется без него", exception);
            }
        }
        return result;
    }

    /** Зарегистрированные id — для диагностики и тестов. */
    List<String> ids() {
        List<String> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            result.add(entry.id());
        }
        return List.copyOf(result);
    }

    record Entry(String id, ChrdkRegisterApi.NameTagProvider provider) {
    }
}
