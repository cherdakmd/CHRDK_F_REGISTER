package ru.cherdakmd.chrdkstreams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Сводки по всем привязкам: топ стримеров и статистика по площадкам.
 *
 * <p>Класс чистый — ни Bukkit, ни файлов: сортировка и подсчёт проверяются
 * юнит-тестами. Топ считает только реально заработанные дни: строка с нулём
 * ничего не говорит игроку, поэтому в таблицу не попадает. При равенстве часов
 * выше тот, чей ник идёт раньше по алфавиту, — так порядок стабилен между
 * вызовами и не «прыгает» от перестановки коллекции.</p>
 */
final class StreamBoard {

    /** Строка топа. */
    record Row(UUID uuid, String playerName, int totalHours, int channels) {
    }

    /** Итоги одной площадки: сколько каналов и сколько из них подтверждено. */
    record PlatformRow(StreamPlatform platform, int channels, int verified) {
    }

    private StreamBoard() {
    }

    /**
     * Топ стримеров по дням за эфир.
     *
     * @param bindings все привязки
     * @param limit    сколько строк показать (зажимается 1..{@value #MAX_ROWS})
     */
    static List<Row> top(Collection<StreamBinding> bindings, int limit) {
        int safeLimit = Math.clamp(limit, 1, MAX_ROWS);
        List<Row> rows = new ArrayList<>();
        if (bindings == null) {
            return rows;
        }
        for (StreamBinding binding : bindings) {
            if (binding == null || binding.state().totalHours() <= 0) {
                continue;
            }
            rows.add(new Row(binding.uuid(), displayName(binding),
                    binding.state().totalHours(), binding.channels().size()));
        }
        rows.sort(Comparator.comparingInt(Row::totalHours).reversed()
                .thenComparing(row -> row.playerName().toLowerCase(Locale.ROOT)));
        return rows.size() <= safeLimit ? List.copyOf(rows) : List.copyOf(rows.subList(0, safeLimit));
    }

    /** Статистика по площадкам: сколько каналов привязано и сколько подтверждено. */
    static List<PlatformRow> platforms(Collection<StreamBinding> bindings) {
        Map<StreamPlatform, int[]> counts = new EnumMap<>(StreamPlatform.class);
        if (bindings != null) {
            for (StreamBinding binding : bindings) {
                if (binding == null) {
                    continue;
                }
                for (StreamBinding.Channel channel : binding.channels()) {
                    int[] pair = counts.computeIfAbsent(channel.platform(), key -> new int[2]);
                    pair[0]++;
                    if (channel.verified()) {
                        pair[1]++;
                    }
                }
            }
        }
        List<PlatformRow> rows = new ArrayList<>();
        for (StreamPlatform platform : StreamPlatform.values()) {
            int[] pair = counts.get(platform);
            if (pair != null && pair[0] > 0) {
                rows.add(new PlatformRow(platform, pair[0], pair[1]));
            }
        }
        return List.copyOf(rows);
    }

    /** Сколько всего каналов привязано (для заголовка статистики). */
    static int channelCount(Collection<StreamBinding> bindings) {
        int count = 0;
        if (bindings != null) {
            for (StreamBinding binding : bindings) {
                if (binding != null) {
                    count += binding.channels().size();
                }
            }
        }
        return count;
    }

    private static String displayName(StreamBinding binding) {
        if (!binding.playerName().isBlank()) {
            return binding.playerName();
        }
        return binding.primary().map(StreamBinding.Channel::channel).orElse("");
    }

    /** Потолок строк топа: больше в чат всё равно не влезет. */
    static final int MAX_ROWS = 10;
}
