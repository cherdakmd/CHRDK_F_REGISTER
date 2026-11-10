package ru.cherdakmd.chrdkregister.api.storage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Документное хранилище, выданное ядром конкретному плагину. Namespace уже
 * закреплён за {@link org.bukkit.plugin.Plugin}; аддон не может прочитать или
 * перезаписать чужой документ. SQL и JDBC в контракте отсутствуют.
 */
public interface DocumentStore {

    /** Читает документ; пустой {@link Optional}, если ключ ещё не создан. */
    CompletableFuture<Optional<DocumentSnapshot>> read(String key);

    /** Безусловная запись; возвращает новую revision. */
    CompletableFuture<Long> write(String key, String payload);

    /** Оптимистичная запись: успешна только при совпадении ожидаемой revision. */
    CompletableFuture<Boolean> compareAndSet(String key, long expectedRevision, String payload);

    /** Удаляет документ; возвращает {@code true}, если запись существовала. */
    CompletableFuture<Boolean> delete(String key);
}
