package ru.cherdakmd.chrdkranks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Лестница званий и маппинг звание → команда scoreboard для сортировки TAB.
 * Чистая логика — покрыта юнит-тестами без сервера.
 *
 * Лестница хранится СТРОГО по убыванию порога часов: индекс 0 — высшее звание.
 *
 * <p>С 1.4.183 команда кодирует два признака сразу: сначала донатную ступень,
 * потом звание за стаж. Клиент сортирует список по имени команды, поэтому
 * {@code !rk-a00} (Покровитель, высшее звание) стоит выше {@code !rk-b00}
 * (Меценат), а тот — выше {@code !rk-f99} (без ранга и без звания). Иначе в
 * списке был настоящий бардак: игроки с оплаченным рангом перемешивались с
 * остальными по одной лишь набитой игровой сессии.</p>
 *
 * <p>Закреп ядра {@code !chrdk-pin} остаётся выше всех: буква 'c' < 'r'.</p>
 */
final class RankLadder {

    static final String TEAM_PREFIX = "!rk-";
    static final int MAX_RANKS = 26;
    /** Пять донатных ступеней (см. {@code RankSettings}); 0 — ранга нет. */
    static final int MAX_RANK_LEVEL = 5;
    /** Код «звания нет»: стоит после любого настоящего (0 — высшее звание). */
    static final String NO_TIER_CODE = "99";

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
        // лестница убывает (индекс 0 — высшее звание): берём первый порог сверху,
        // который стаж уже набил — это и есть текущее звание игрока
        for (int i = 0; i < ladder.size(); i++) {
            if (hours >= ladder.get(i).hours()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Команда scoreboard: ранг важнее стажа (1.4.183).
     *
     * <p>Имя команды — {@code !rk-} + буква донатной ступени + двузначный код
     * звания. Буква ступени идёт от 'a' (высшая, Покровитель) к 'f' (ранга
     * нет). Код звания — {@code 00} у высшего звания, {@code 25} у низшего и
     * {@code 99}, если звания ещё нет: так список внутри одного ранга тоже
     * упорядочен, а «новички» не всплывают выше ветеранов.</p>
     *
     * @param rankLevel ступень донатного ранга (0 — нет, 1..5 по лестнице);
     *                  значения вне диапазона зажимаются
     * @param tierIndex позиция звания за стаж ({@link #tierIndex}); -1 — звания нет
     * @return имя команды: не длиннее 7 символов, всегда пригодное для регистрации
     */
    static String teamNameFor(int rankLevel, int tierIndex) {
        int level = Math.clamp(rankLevel, 0, MAX_RANK_LEVEL);
        char rankChar = (char) ('a' + (MAX_RANK_LEVEL - level));
        String tierCode;
        if (tierIndex < 0) {
            tierCode = NO_TIER_CODE;
        } else {
            // индекс 0 — высшее звание, ему самый маленький код
            int capped = Math.clamp(tierIndex, 0, MAX_RANKS - 1);
            tierCode = String.format(java.util.Locale.ROOT, "%02d", capped);
        }
        return TEAM_PREFIX + rankChar + tierCode;
    }

    /** Принадлежит ли имя команды нашему модулю (для зачистки при выгрузке). */
    static boolean isManagedTeam(String teamName) {
        return teamName != null && teamName.startsWith(TEAM_PREFIX);
    }

    /**
     * Следующее звание сверху: пара (награда, осталось часов) или null, если игрок уже на вершине.
     */
    static NextRank nextUp(List<Rank> ladder, long totalSeconds) {
        long hours = Math.floorDiv(Math.max(0, totalSeconds), 3600);
        // ближайшее недостигнутое = минимальный порог выше стажа: идём снизу лестницы вверх
        for (int i = ladder.size() - 1; i >= 0; i--) {
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
            if (hours < 0 || hours >= previousHours) {
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
