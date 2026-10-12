package ru.cherdakmd.chrdkranks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Лестница званий и маппинг звание → команда scoreboard для сортировки TAB.
 * Чистая логика — покрыта юнит-тестами без сервера.
 *
 * Лестница хранится СТРОГО по убыванию порога часов: индекс 0 — высшее звание.
 * Команда самого высокого звания — {@code !rk-a}, следующего — {@code !rk-b} и т.д.:
 * клиент сортирует список по имени команды, поэтому ветераны оказываются выше
 * (закреп ядра {@code !chrdk-pin} остаётся выше всех — буква 'c' < 'r').
 */
final class RankLadder {

    static final String TEAM_PREFIX = "!rk-";
    static final int MAX_RANKS = 26;

    private RankLadder() {
    }

    record Rank(long hours, String id, String displayName, List<String> commands) {
    }

    /**
     * Индекс звания по стажу в секундах: 0 — высшее достигнутое, -1 — ни одного.
     * Лестница обязана быть отсортирована по убыванию часов (валидируется при парсинге).
     */
    static int tierIndex(List<Rank> ladder, long totalSeconds) {
        long hours = Math.floorDiv(Math.max(0, totalSeconds), 3600);
        for (int i = ladder.size() - 1; i >= 0; i--) {
            if (hours >= ladder.get(i).hours()) {
                return i;
            }
        }
        return -1;
    }

    /** Команда scoreboard для позиции лестницы (0 → "!rk-a"); -1 → null (без команды). */
    static String teamNameFor(int index) {
        if (index < 0 || index >= MAX_RANKS) {
            return null;
        }
        return TEAM_PREFIX + (char) ('a' + index);
    }

    /** Принадлежит ли имя команды нашему модулю (для зачистки при выгрузке). */
    static boolean isManagedTeam(String teamName) {
        return teamName != null && teamName.startsWith(TEAM_PREFIX);
    }

    /**
     * Следующее звание сверху: пара (награда, осталось часов) или null, если игрок уже на вершине.
     */
    static NextRank nextUp(List<Rank> ladder, long totalSeconds) {
        int current = tierIndex(ladder, totalSeconds);
        long hours = Math.floorDiv(Math.max(0, totalSeconds), 3600);
        for (int i = current + 1; i < ladder.size(); i++) {
            Rank candidate = ladder.get(i);
            if (hours < candidate.hours()) {
                return new NextRank(candidate, candidate.hours() - hours);
            }
        }
        return null;
    }

    record NextRank(Rank rank, long hoursLeft) {
    }

    /**
     * Парсинг лестницы из конфиг-карт. Проверяет: строгое убывание часов, непустые id/name,
     * уникальность id, не более {@link #MAX_RANKS} званий. Бросает IllegalStateException
     * с человекочитаемым текстом при нарушении.
     */
    static List<Rank> parse(List<Map<?, ?>> raw) {
        List<Rank> ladder = new ArrayList<>();
        long previousHours = Long.MAX_VALUE;
        for (Map<?, ?> entry : raw) {
            long hours = longOf(entry.get("hours"));
            Object idRaw = entry.get("id");
            Object nameRaw = entry.get("name");
            String id = idRaw == null ? "" : String.valueOf(idRaw).trim();
            String name = nameRaw == null ? "" : String.valueOf(nameRaw).trim();
            if (id.isEmpty() || name.isEmpty()) {
                throw new IllegalStateException("Звание без id или name: " + entry);
            }
            if (hours < 0 || hours > previousHours) {
                throw new IllegalStateException("Часы лестницы должны строго убывать сверху вниз: " + id);
            }
            previousHours = hours;
            List<String> commands = new ArrayList<>();
            if (entry.get("commands") instanceof List<?> list) {
                for (Object command : list) {
                    commands.add(String.valueOf(command));
                }
            }
            if (ladder.stream().anyMatch(r -> r.id().equals(id))) {
                throw new IllegalStateException("Дублируется id звания: " + id);
            }
            ladder.add(new Rank(hours, id, name, commands));
        }
        if (ladder.size() > MAX_RANKS) {
            throw new IllegalStateException("Званий не может быть больше " + MAX_RANKS);
        }
        return List.copyOf(ladder);
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Некорректное число часов: " + value);
        }
    }

    /** Человекочитаемое «X ч. Y мин.» для /ranks. */
    static String formatPlaytime(long totalSeconds) {
        long hours = Math.floorDiv(Math.max(0, totalSeconds), 3600);
        long minutes = Math.floorDiv(Math.max(0, totalSeconds) % 3600, 60);
        if (hours > 0) {
            return hours + " ч. " + minutes + " мин.";
        }
        return minutes + " мин.";
    }

    /** Человекочитаемый остаток «N ч.» (без минут — для прогресса до звания). */
    static String formatHoursLeft(long hoursLeft) {
        if (hoursLeft <= 0) {
            return "0 ч.";
        }
        return hoursLeft + " ч.";
    }
}
