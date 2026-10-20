package ru.cherdakmd.chrdkbp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Очки сезона и рейтинг. Чистая арифметика: очки начисляются за выполненные
 * задания (цель достигнута), а не за забранные награды — иначе игрок без проходки
 * никогда бы не поднялся в рейтинге.
 */
final class BattlePassScore {

    private BattlePassScore() {
    }

    /** Строка рейтинга. */
    record Entry(UUID uuid, String name, int points, int completed, int claimed) {
    }

    /** Сумма очков за выполненные задания. */
    static int points(BattlePassConfig.Season season, Map<String, Integer> progress) {
        int sum = 0;
        for (BattlePassConfig.WeekDef week : season.weeks()) {
            for (BattlePassConfig.MissionDef mission : week.missions()) {
                if (progress.getOrDefault(mission.id(), 0) >= mission.target()) {
                    sum += mission.points();
                }
            }
        }
        return sum;
    }

    /** Сколько заданий выполнено (цель достигнута). */
    static int completed(BattlePassConfig.Season season, Map<String, Integer> progress) {
        int count = 0;
        for (BattlePassConfig.WeekDef week : season.weeks()) {
            for (BattlePassConfig.MissionDef mission : week.missions()) {
                if (progress.getOrDefault(mission.id(), 0) >= mission.target()) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Сколько наград забрано (каждая ветка — бесплатная, VIP, премиум — отдельно). */
    static int claimed(BattlePassConfig.Season season, Set<String> marks) {
        int count = 0;
        for (BattlePassConfig.WeekDef week : season.weeks()) {
            for (BattlePassConfig.MissionDef mission : week.missions()) {
                if (marks.contains(mission.id() + ":FREE")) {
                    count++;
                }
                if (marks.contains(mission.id() + ":VIP")) {
                    count++;
                }
                if (marks.contains(mission.id() + ":PREMIUM")) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Рейтинг сезона: очки, затем число выполненных заданий, затем ник.
     *
     * @param limit максимум строк (0 или отрицательное — все)
     */
    static List<Entry> leaderboard(BattlePassConfig.Season season,
                                   Map<UUID, Map<String, Integer>> progress,
                                   Map<UUID, String> names,
                                   Map<UUID, Set<String>> claims,
                                   int limit) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Integer>> row : progress.entrySet()) {
            UUID uuid = row.getKey();
            int points = points(season, row.getValue());
            int completed = completed(season, row.getValue());
            int claimed = claimed(season, claims.getOrDefault(uuid, Set.of()));
            if (points <= 0 && completed <= 0 && claimed <= 0) {
                continue;
            }
            entries.add(new Entry(uuid, displayName(uuid, names.get(uuid)), points, completed, claimed));
        }
        entries.sort(Comparator
                .comparingInt(Entry::points).reversed()
                .thenComparing(Comparator.comparingInt(Entry::completed).reversed())
                .thenComparing((Entry entry) -> entry.name().toLowerCase(Locale.ROOT))
                .thenComparing((Entry entry) -> entry.uuid().toString()));
        if (limit > 0 && entries.size() > limit) {
            return List.copyOf(entries.subList(0, limit));
        }
        return List.copyOf(entries);
    }

    /** Место игрока в рейтинге (1-based) или 0, если его там нет. */
    static int position(List<Entry> entries, UUID uuid) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).uuid().equals(uuid)) {
                return index + 1;
            }
        }
        return 0;
    }

    private static String displayName(UUID uuid, String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return uuid == null ? "?" : uuid.toString().substring(0, 8);
    }
}
