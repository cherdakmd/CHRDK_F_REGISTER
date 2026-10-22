package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище книг коллекции по игрокам (1.4.150).
 *
 * <p>Живёт в памяти и сбрасывается в {@code stats.yml} вместе со счётчиками
 * находок — отдельный файл не нужен, резервные копии и атомарная запись уже
 * есть. Ключ — UUID, поэтому смена ника прогресс не теряет.</p>
 */
public final class TierCollectionKeeper {

    private final ConcurrentHashMap<UUID, TierCollectionBook> books = new ConcurrentHashMap<>();

    /**
     * Отмечает добычу и возвращает новинки.
     *
     * @return {@link TierCollectionBook.Discovery#EMPTY}, если всё уже было
     */
    public TierCollectionBook.Discovery record(UUID player, String tierId,
                                               Collection<String> propertyIds,
                                               Collection<String> affixIds) {
        if (player == null) {
            return TierCollectionBook.Discovery.EMPTY;
        }
        return books.computeIfAbsent(player, ignored -> new TierCollectionBook())
                .discover(tierId, propertyIds, affixIds);
    }

    /** Пытается закрыть веху; {@code true} — награда ещё не выдавалась. */
    public boolean claim(UUID player, String milestoneKey) {
        TierCollectionBook book = player == null ? null : books.get(player);
        return book != null && book.claim(milestoneKey);
    }

    public boolean isClaimed(UUID player, String milestoneKey) {
        TierCollectionBook book = player == null ? null : books.get(player);
        return book != null && book.isClaimed(milestoneKey);
    }

    /** Снимок книги; для неизвестного игрока — пустой каталог, а не {@code null}. */
    public TierCollectionBook.Snapshot snapshot(UUID player) {
        TierCollectionBook book = player == null ? null : books.get(player);
        return book == null ? TierCollectionBook.Snapshot.EMPTY : book.snapshot();
    }

    /** Сколько игроков вообще что-то собрали (для стартового лога). */
    public int players() {
        return books.size();
    }

    /** Запись всех книг: UUID сортируются, чтобы файл не «прыгал» между сейвами. */
    public void writeTo(ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (var entry : new TreeMap<>(books).entrySet()) {
            entry.getValue().writeTo(root.createSection(entry.getKey().toString()));
        }
    }

    /** Чтение секции {@code collection} из {@code stats.yml}; битые ключи пропускаются. */
    public void load(ConfigurationSection root) {
        if (root == null) {
            return;
        }
        for (String raw : root.getKeys(false)) {
            UUID player;
            try {
                player = UUID.fromString(raw);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            TierCollectionBook book = new TierCollectionBook();
            book.read(root.getConfigurationSection(raw));
            if (book.total() > 0 || !book.claimed().isEmpty()) {
                books.put(player, book);
            }
        }
    }

    /** Полная очистка — только для тестов и админского сброса. */
    public void clear() {
        books.clear();
    }
}
