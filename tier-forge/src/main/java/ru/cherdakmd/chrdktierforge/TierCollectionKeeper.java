package ru.cherdakmd.chrdktierforge;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
        TierCollectionBook existing = books.get(player);
        if (existing != null) {
            return existing.discover(tierId, propertyIds, affixIds);
        }
        // Новую книгу заводим только под реальную находку: пустые книги не
        // должны попадать в stats.yml и в рейтинг.
        TierCollectionBook fresh = new TierCollectionBook();
        TierCollectionBook.Discovery discovery = fresh.discover(tierId, propertyIds, affixIds);
        if (!discovery.isEmpty()) {
            books.putIfAbsent(player, fresh);
        }
        return discovery;
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

    /** Голые счётчики книги; неизвестный игрок — нули, а не {@code null}. */
    public TierCollectionBook.Progress progress(UUID player) {
        TierCollectionBook book = player == null ? null : books.get(player);
        return book == null ? TierCollectionBook.Progress.EMPTY : book.progress();
    }

    /** Строка рейтинга: игрок и его счётчики. */
    public record Row(UUID player, TierCollectionBook.Progress progress) {

        public int total() {
            return progress.total();
        }
    }

    /**
     * Рейтинг самых полных книг (1.4.151).
     *
     * <p>Сортировка: всего собрано, затем редкости, затем свойства, затем UUID —
     * так порядок стабилен и не зависит от обхода {@link ConcurrentHashMap}.
     * Пустые книги в рейтинг не попадают.</p>
     *
     * @param limit сколько строк вернуть; {@code <= 0} — пустой список
     */
    public List<Row> top(int limit) {
        int max = Math.max(0, limit);
        if (max == 0) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        books.forEach((player, book) -> {
            TierCollectionBook.Progress progress = book.progress();
            if (progress.total() > 0) {
                rows.add(new Row(player, progress));
            }
        });
        rows.sort(Comparator.comparingInt(Row::total).reversed()
                .thenComparing(Comparator.comparingInt(
                        (Row row) -> row.progress().tiers()).reversed())
                .thenComparing(Comparator.comparingInt(
                        (Row row) -> row.progress().properties()).reversed())
                .thenComparing(row -> row.player().toString()));
        return List.copyOf(rows.subList(0, Math.min(max, rows.size())));
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
