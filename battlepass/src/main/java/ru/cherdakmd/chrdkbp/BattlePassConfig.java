package ru.cherdakmd.chrdkbp;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Конфиг сезона батлпасса. Чистый парсинг + арифметика волн — покрыто тестами.
 *
 * <p>Волна открывается в «сезон-старт + unlock-day» суток (UTC). Прогресс копится
 * только по уже открытым волнам; задания прошлых волн можно добивать весь сезон.
 * После {@code season-end} прогресс замораживается, но награды остаются доступны.</p>
 */
final class BattlePassConfig {

    /** Очки за задание, если в конфиге не указано иное. */
    static final int DEFAULT_POINTS = 10;

    private BattlePassConfig() {
    }

    enum MissionType {
        MINE, KILL, CRAFT, FISH, PLACE, SMELT, ENCHANT, BREED
    }

    /**
     * Одно задание волны.
     *
     * @param matchKey    материал/сущность или несколько через {@code |}; {@code any} — любой
     * @param points      очки сезона за выполнение
     * @param worlds      миры, где задание считается (пусто — все)
     * @param permission  право, без которого прогресс не идёт (пусто — всем)
     * @param icon        материал иконки в меню (пусто — по типу задания)
     */
    record MissionDef(String id, String description, MissionType type, String matchKey,
                      int target, int freeDays, int vipDays, int points,
                      List<String> worlds, String permission, String icon) {

        /** Совместимый конструктор для тестов и старых вызовов. */
        MissionDef(String id, String description, MissionType type, String matchKey,
                   int target, int freeDays, int vipDays) {
            this(id, description, type, matchKey, target, freeDays, vipDays,
                    DEFAULT_POINTS, List.of(), "", "");
        }

        boolean restrictedWorld() {
            return !worlds.isEmpty();
        }

        boolean restrictedPermission() {
            return !permission.isEmpty();
        }
    }

    /** Одна волна заданий. */
    record WeekDef(int unlockDay, List<MissionDef> missions) {
    }

    /** Сезон: имя, старт, необязательный конец и волны. */
    record Season(String name, long startEpochMillis, long endEpochMillis, List<WeekDef> weeks) {

        /** Совместимый конструктор: сезон без даты окончания. */
        Season(String name, long startEpochMillis, List<WeekDef> weeks) {
            this(name, startEpochMillis, 0L, weeks);
        }

        boolean empty() {
            return weeks.isEmpty() || weeks.stream().allMatch(w -> w.missions().isEmpty());
        }

        /** Сезон закончился: прогресс больше не идёт. */
        boolean ended(long nowMillis) {
            return endEpochMillis > 0 && nowMillis >= endEpochMillis;
        }

        boolean hasEnd() {
            return endEpochMillis > 0;
        }
    }

    /** Результат разбора волн: рабочие волны + понятные человеку проблемы конфига. */
    record ParseResult(List<WeekDef> weeks, List<String> problems) {
    }

    static Season season(String name, String startIso, List<WeekDef> weeks) {
        return season(name, startIso, "", weeks);
    }

    static Season season(String name, String startIso, String endIso, List<WeekDef> weeks) {
        long start = parseDate(startIso, "season-start");
        long end = endIso == null || endIso.isBlank() ? 0L : parseDate(endIso, "season-end");
        if (end > 0 && end < start) {
            throw new IllegalStateException("Дата season-end раньше season-start: " + endIso);
        }
        return new Season(name, start, end, List.copyOf(weeks));
    }

    private static long parseDate(String iso, String key) {
        try {
            return LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Некорректная дата " + key
                    + " (нужен формат гггг-мм-дд): " + iso);
        }
    }

    /** Номер текущей открытой волны (1..N); 0 — сезон ещё не начался. */
    static int currentWeek(Season season, long nowMillis) {
        int open = 0;
        for (int i = 0; i < season.weeks().size(); i++) {
            if (isUnlocked(season, i, nowMillis)) {
                open = i + 1;
            } else {
                break;
            }
        }
        return open;
    }

    /** Момент открытия волны (индекс 0-based), epoch millis. */
    static long unlockAt(Season season, int weekIndex) {
        return season.startEpochMillis() + season.weeks().get(weekIndex).unlockDay() * 86_400_000L;
    }

    /** Открыта ли волна (индекс 0-based) на момент времени. */
    static boolean isUnlocked(Season season, int weekIndex, long nowMillis) {
        return nowMillis >= unlockAt(season, weekIndex);
    }

    /** Сколько суток осталось до открытия волны (для сообщения), минимум 1. */
    static long daysUntilUnlock(Season season, int weekIndex, long nowMillis) {
        long at = unlockAt(season, weekIndex);
        long diff = at - nowMillis;
        if (diff <= 0) {
            return 0;
        }
        return Math.max(1, (diff + 86_399_999L) / 86_400_000L);
    }

    /** Сколько суток осталось до конца сезона; 0 — дата не задана или уже прошла. */
    static long daysUntilEnd(Season season, long nowMillis) {
        if (!season.hasEnd()) {
            return 0;
        }
        long diff = season.endEpochMillis() - nowMillis;
        return diff <= 0 ? 0 : Math.max(1, (diff + 86_399_999L) / 86_400_000L);
    }

    /** Найти задание и его волну по id. */
    record Found(WeekDef week, int weekIndex, MissionDef mission) {
    }

    static Found find(Season season, String missionId) {
        String wanted = missionId == null ? "" : missionId.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < season.weeks().size(); i++) {
            WeekDef week = season.weeks().get(i);
            for (MissionDef mission : week.missions()) {
                if (mission.id().equals(wanted)) {
                    return new Found(week, i, mission);
                }
            }
        }
        return null;
    }

    /**
     * Совместимый разбор без диагностики: невалидные задания молча пропускаются.
     * Для админской проверки есть {@link #parseWeeksDetailed(List)}.
     */
    static List<WeekDef> parseWeeks(List<Map<?, ?>> rawWeeks) {
        return parseWeeksDetailed(rawWeeks).weeks();
    }

    /**
     * Разбор волн с накоплением проблем конфига.
     *
     * <p>Проблемы не мешают плагину работать: плохое задание пропускается, остальные
     * загружаются. Администратор видит список в {@code /bpadmin validate} и после
     * {@code /bpadmin reload} — в логе.</p>
     */
    static ParseResult parseWeeksDetailed(List<Map<?, ?>> rawWeeks) {
        List<WeekDef> weeks = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int previousUnlock = -1;
        int weekNumber = 0;
        for (Map<?, ?> rawWeek : rawWeeks) {
            weekNumber++;
            int unlockDay = Math.max(0, intOf(rawWeek.get("unlock-day"), 0));
            if (weeks.isEmpty()) {
                previousUnlock = unlockDay;
            } else if (unlockDay < previousUnlock) {
                throw new IllegalStateException(
                        "unlock-day волн должен неубывать сверху вниз: " + unlockDay);
            }
            previousUnlock = unlockDay;

            List<MissionDef> missions = new ArrayList<>();
            if (rawWeek.get("missions") instanceof List<?> list) {
                for (Object rawMission : list) {
                    if (!(rawMission instanceof Map<?, ?> map)) {
                        problems.add("волна " + weekNumber + ": задание не является набором ключей");
                        continue;
                    }
                    MissionDef mission = parseMission(map, weekNumber, problems);
                    if (mission == null) {
                        continue;
                    }
                    if (!seenIds.add(mission.id())) {
                        problems.add("волна " + weekNumber + ": id «" + mission.id()
                                + "» уже занят другим заданием — это задание пропущено");
                        continue;
                    }
                    missions.add(mission);
                }
            } else {
                problems.add("волна " + weekNumber + ": нет списка missions");
            }
            if (missions.isEmpty()) {
                problems.add("волна " + weekNumber + ": нет ни одного корректного задания");
                continue;
            }
            weeks.add(new WeekDef(unlockDay, List.copyOf(missions)));
        }
        return new ParseResult(List.copyOf(weeks), List.copyOf(problems));
    }

    private static MissionDef parseMission(Map<?, ?> raw, int weekNumber, List<String> problems) {
        String id = norm(raw.get("id"));
        if (id.isEmpty()) {
            problems.add("волна " + weekNumber + ": задание без id пропущено");
            return null;
        }
        MissionType type;
        String rawType = norm(raw.get("type"));
        try {
            type = MissionType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            problems.add("волна " + weekNumber + ": «" + id + "» — неизвестный тип задания «"
                    + rawType + "» (доступны: " + types() + ")");
            return null;
        }
        String matchKey = switch (type) {
            case FISH -> norm(raw.get("entity")).isEmpty() ? "any" : norm(raw.get("entity"));
            case KILL, BREED -> norm(raw.get("entity"));
            default -> norm(raw.get("material"));
        };
        if (matchKey.isEmpty()) {
            problems.add("волна " + weekNumber + ": «" + id + "» — не указан материал или сущность");
            return null;
        }
        int target = intOf(raw.get("target"), 0);
        if (target <= 0) {
            problems.add("волна " + weekNumber + ": «" + id + "» — цель должна быть больше нуля");
            return null;
        }
        Object descRaw = raw.get("desc");
        String desc = descRaw == null ? id : String.valueOf(descRaw).trim();
        return new MissionDef(
                id,
                desc.isEmpty() ? id : desc,
                type,
                matchKey,
                target,
                Math.max(0, intOf(raw.get("free-days"), 0)),
                Math.max(0, intOf(raw.get("vip-days"), 0)),
                Math.max(0, intOf(raw.get("points"), DEFAULT_POINTS)),
                worlds(raw.get("worlds")),
                String.valueOf(raw.get("permission") == null ? "" : raw.get("permission")).trim(),
                norm(raw.get("icon")).toUpperCase(Locale.ROOT));
    }

    private static List<String> worlds(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof Iterable<?> list) {
            for (Object entry : list) {
                String world = norm(entry);
                if (!world.isEmpty() && !result.contains(world)) {
                    result.add(world);
                }
            }
        } else if (raw != null) {
            for (String part : String.valueOf(raw).split("[|,]")) {
                String world = norm(part);
                if (!world.isEmpty() && !result.contains(world)) {
                    result.add(world);
                }
            }
        }
        return List.copyOf(result);
    }

    /** Список типов заданий для сообщений об ошибке. */
    static String types() {
        StringBuilder builder = new StringBuilder();
        for (MissionType type : MissionType.values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(type.name());
        }
        return builder.toString();
    }

    /** Совпадает ли действие с целью задания (без вариантов блоков). */
    static boolean matchesTypeKey(MissionDef mission, String actualKey) {
        return matchesTypeKey(mission, actualKey, false);
    }

    /**
     * Совпадает ли действие с целью задания.
     *
     * @param variants учитывать варианты блоков (дипслейт-руда, обтёсанное бревно)
     */
    static boolean matchesTypeKey(MissionDef mission, String actualKey, boolean variants) {
        String actual = norm(actualKey);
        for (String part : mission.matchKey().split("[|,]")) {
            String wanted = part.trim();
            if (wanted.isEmpty()) {
                continue;
            }
            if ("any".equals(wanted) || wanted.equals(actual)) {
                return true;
            }
            if (variants && BattlePassVariants.sameMaterial(wanted, actual)) {
                return true;
            }
        }
        return false;
    }

    private static String norm(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
