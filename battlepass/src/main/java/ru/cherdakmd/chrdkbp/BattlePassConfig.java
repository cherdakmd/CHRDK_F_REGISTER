package ru.cherdakmd.chrdkbp;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Конфиг сезона батлпасса. Чистый парсинг + арифметика волн — покрыто тестами.
 *
 * Волна открывается в сезон-старт + unlock-day суток (UTC). Прогресс копится
 * только по уже открытым волнам; задания прошлых волн можно добивать весь сезон.
 */
final class BattlePassConfig {

    private BattlePassConfig() {
    }

    enum MissionType {
        MINE, KILL, CRAFT, FISH
    }

    /** Одно задание волны. reward-* — дни проходки (0 = нет награды этой ветки). */
    record MissionDef(String id, String description, MissionType type, String matchKey,
                      int target, int freeDays, int vipDays) {
    }

    /** Одна волна заданий. */
    record WeekDef(int unlockDay, List<MissionDef> missions) {
    }

    record Season(String name, long startEpochMillis, List<WeekDef> weeks) {
        boolean empty() {
            return weeks.isEmpty() || weeks.stream().allMatch(w -> w.missions().isEmpty());
        }
    }

    static Season season(String name, String startIso, List<WeekDef> weeks) {
        long start;
        try {
            start = LocalDate.parse(startIso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Некорректная дата season-start (нужен формат гггг-мм-дд): "
                    + startIso);
        }
        return new Season(name, start, List.copyOf(weeks));
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

    /** Открыта ли волна (индекс 0-based) на момент времени. */
    static boolean isUnlocked(Season season, int weekIndex, long nowMillis) {
        WeekDef week = season.weeks().get(weekIndex);
        return nowMillis >= season.startEpochMillis()
                + week.unlockDay() * 86_400_000L;
    }

    /** Сколько суток осталось до открытия волны (для сообщения), минимум 1. */
    static long daysUntilUnlock(Season season, int weekIndex, long nowMillis) {
        long at = season.startEpochMillis() + season.weeks().get(weekIndex).unlockDay() * 86_400_000L;
        long diff = at - nowMillis;
        if (diff <= 0) {
            return 0;
        }
        return Math.max(1, (diff + 86_399_999L) / 86_400_000L);
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

    /** Парсинг волн из конфиг-карт. Невалидные записи заданий пропускаются. */
    static List<WeekDef> parseWeeks(List<Map<?, ?>> rawWeeks) {
        List<WeekDef> weeks = new ArrayList<>();
        int previousUnlock = -1;
        for (Map<?, ?> rawWeek : rawWeeks) {
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
                    if (rawMission instanceof Map<?, ?> map) {
                        MissionDef mission = parseMission(map);
                        if (mission != null
                                && missions.stream().noneMatch(m -> m.id().equals(mission.id()))) {
                            missions.add(mission);
                        }
                    }
                }
            }
            if (!missions.isEmpty()) {
                weeks.add(new WeekDef(unlockDay, List.copyOf(missions)));
            }
        }
        return List.copyOf(weeks);
    }

    private static MissionDef parseMission(Map<?, ?> raw) {
        String id = norm(raw.get("id"));
        Object descRaw = raw.get("desc");
        String desc = descRaw == null ? id : String.valueOf(descRaw).trim();
        MissionType type;
        try {
            type = MissionType.valueOf(norm(raw.get("type")).toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return null;
        }
        String matchKey = switch (type) {
            case FISH -> norm(raw.get("entity")).isEmpty() ? "any" : norm(raw.get("entity"));
            case KILL -> norm(raw.get("entity"));
            default -> norm(raw.get("material"));
        };
        int target = intOf(raw.get("target"), 0);
        if (id.isEmpty() || matchKey.isEmpty() && type != MissionType.FISH || target <= 0) {
            return null;
        }
        return new MissionDef(id, desc.isEmpty() ? id : desc, type, matchKey, target,
                Math.max(0, intOf(raw.get("free-days"), 0)),
                Math.max(0, intOf(raw.get("vip-days"), 0)));
    }

    /** Полное совпадение ключа: — материал/сущность (регистр сведён), ANY — всегда. */
    static boolean matchesTypeKey(MissionDef mission, String actualKey) {
        String actual = norm(actualKey);
        return "any".equals(mission.matchKey()) || mission.matchKey().equals(actual);
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
