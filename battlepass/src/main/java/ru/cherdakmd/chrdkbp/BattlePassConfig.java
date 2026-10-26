package ru.cherdakmd.chrdkbp;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
    /** Сколько товаров каталога может выдавать одно задание премиум-трека. */
    static final int MAX_PREMIUM_PRODUCTS = 5;
    /** Допустимый id товара каталога: те же правила, что у shop.products в ядре. */
    private static final Pattern PRODUCT_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

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
     * @param premiumHours часы ранга в премиум-треке (0 — премиум-награды нет)
     * @param premiumProducts товары каталога ядра, которые выдаёт премиум-трек
     */
    record MissionDef(String id, String description, MissionType type, String matchKey,
                      int target, int freeHours, int vipHours, int points,
                      List<String> worlds, String permission, String icon,
                      int premiumHours, List<String> premiumProducts) {

        MissionDef {
            premiumProducts = premiumProducts == null ? List.of() : List.copyOf(premiumProducts);
            // 1.4.184: награда в часах, одна награда — не больше суток.
            premiumHours = Math.max(0, Math.min(24, premiumHours));
        }

        /** Совместимый конструктор для тестов и старых вызовов. */
        MissionDef(String id, String description, MissionType type, String matchKey,
                   int target, int freeHours, int vipHours) {
            this(id, description, type, matchKey, target, freeHours, vipHours,
                    DEFAULT_POINTS, List.of(), "", "", 0, List.of());
        }

        MissionDef(String id, String description, MissionType type, String matchKey,
                   int target, int freeHours, int vipHours, int points,
                   List<String> worlds, String permission, String icon) {
            this(id, description, type, matchKey, target, freeHours, vipHours,
                    points, worlds, permission, icon, 0, List.of());
        }

        /** В задании есть премиум-награда. */
        boolean hasPremium() {
            return premiumHours > 0 || !premiumProducts.isEmpty();
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
        Object rawTypeValue = raw.get("type");
        String rawType = norm(rawTypeValue).toUpperCase(Locale.ROOT);
        try {
            type = MissionType.valueOf(rawType);
        } catch (RuntimeException exception) {
            // Показываем значение так, как его написал администратор: «WALK» понятнее «walk».
            problems.add("волна " + weekNumber + ": «" + id + "» — неизвестный тип задания «"
                    + (rawTypeValue == null ? "" : rawTypeValue) + "» (доступны: "
                    + types() + ")");
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
                clampHours(hoursOf(raw, "free")),
                clampHours(hoursOf(raw, "vip")),
                Math.max(0, intOf(raw.get("points"), DEFAULT_POINTS)),
                worlds(raw.get("worlds")),
                String.valueOf(raw.get("permission") == null ? "" : raw.get("permission")).trim(),
                norm(raw.get("icon")).toUpperCase(Locale.ROOT),
                premiumHours(raw, id, weekNumber, problems),
                premiumProducts(raw, id, weekNumber, problems));
    }

    /**
     * Часы награды задания. Ключи старого формата ({@code free-days} и т.п.)
     * читаются как часы: конфиг, не перезаписанный после обновления, не должен
     * потерять награду, а сутками прежние значения больше не выдаются.
     */
    private static int hoursOf(Map<?, ?> raw, String branch) {
        Object value = raw.get(branch + "-hours");
        if (value == null) {
            value = raw.get(branch + "-days");
        }
        return intOf(value, 0);
    }

    /** Часы ранга вне 0..24 (потолок награды) — ошибка конфига. */
    private static int clampHours(int hours) {
        return Math.max(0, Math.min(24, hours));
    }

    /** Часы премиум-трека: отрицательные и больше суток отбрасываются. */
    private static int premiumHours(Map<?, ?> raw, String id, int weekNumber, List<String> problems) {
        int hours = hoursOf(raw, "premium");
        if (hours < 0 || hours > 24) {
            problems.add("волна " + weekNumber + ": «" + id + "» — premium-hours " + hours
                    + " вне диапазона 0..24, награда не выдаётся");
            return 0;
        }
        return hours;
    }

    /** Товары каталога в премиум-треке: чистим id и ограничиваем список. */
    private static List<String> premiumProducts(Map<?, ?> raw, String id, int weekNumber,
                                                List<String> problems) {
        Object value = raw.get("premium-products");
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Iterable<?> list = value instanceof Iterable<?> iterable
                ? iterable
                : List.of(String.valueOf(value));
        for (Object entry : list) {
            String product = norm(entry);
            if (product.isEmpty()) {
                continue;
            }
            if (!PRODUCT_ID.matcher(product).matches()) {
                problems.add("волна " + weekNumber + ": «" + id + "» — premium-products: id «"
                        + product + "» недопустим (a-z, 0-9, '-' и '_'), пропущен");
                continue;
            }
            if (!result.contains(product)) {
                result.add(product);
            }
        }
        if (result.size() > MAX_PREMIUM_PRODUCTS) {
            problems.add("волна " + weekNumber + ": «" + id + "» — в premium-products больше "
                    + MAX_PREMIUM_PRODUCTS + " товаров, лишние отброшены");
            return List.copyOf(result.subList(0, MAX_PREMIUM_PRODUCTS));
        }
        return List.copyOf(result);
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
