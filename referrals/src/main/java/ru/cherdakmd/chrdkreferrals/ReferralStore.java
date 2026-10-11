package ru.cherdakmd.chrdkreferrals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Данные рефералки (referrals-data.json): связи приглашённый→пригласитель со статусом выплаты,
 * статистика приглашателей и одноразовая пометка «подсказка показана». Паттерн записи — как
 * у ядра/магазина: мутации на главном потоке, периодический snapshot + асинхронная запись.
 */
public final class ReferralStore {

    /** Привязка одного приглашённого (одноразовая). */
    public static final class RefLink {
        public String inviter = "";
        public long linkedAt;
        public boolean consumed; // бонус выплачен (после первой оплаты)
        public long paidAt;
    }

    /** Накопленная статистика пригласителя. */
    public static class RefStats {
        public int linked;  // сколько друзей привязалось
        public int paid;    // сколько из них оплатило
        public long daysEarned;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<String, RefLink> links = new LinkedHashMap<>();
    private final Map<String, RefStats> stats = new LinkedHashMap<>();
    private boolean dirty;

    public ReferralStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("referrals-data.json");
        this.logger = logger;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) return;
            if (data.get("links") instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> row)) continue;
                    RefLink link = new RefLink();
                    link.inviter = strOf(row.get("inviter"));
                    link.linkedAt = longOf(row.get("linkedAt"));
                    link.consumed = Boolean.TRUE.equals(row.get("consumed"));
                    link.paidAt = longOf(row.get("paidAt"));
                    links.put(String.valueOf(entry.getKey()), link);
                }
            }
            if (data.get("stats") instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?, ?> row)) continue;
                    RefStats stat = new RefStats();
                    stat.linked = (int) longOf(row.get("linked"));
                    stat.paid = (int) longOf(row.get("paid"));
                    stat.daysEarned = longOf(row.get("daysEarned"));
                    stats.put(String.valueOf(entry.getKey()), stat);
                }
            }
            logger.info("[Referrals] Загружено: " + links.size() + " привязок, "
                    + stats.size() + " рекордов статистики.");
        } catch (Exception e) {
            logger.severe("[Referrals] Не удалось прочитать " + file + ": " + e.getMessage());
        }
    }

    /* ------------------------------- связи ------------------------------- */

    public synchronized Optional<RefLink> linkOf(String invitee) {
        return Optional.ofNullable(links.get(ReferralRules.canonical(invitee)));
    }

    public synchronized boolean hasAnyLink(String invitee) {
        return linkOf(invitee).isPresent();
    }

    /** Создаёт привязку приглашённого к пригласившему, инкрементируя счётчик inviter.linked. */
    public synchronized void createLink(String invitee, String inviter, long nowMillis) {
        RefLink link = new RefLink();
        link.inviter = inviter;
        link.linkedAt = nowMillis;
        links.put(ReferralRules.canonical(invitee), link);
        statsOf(inviter).linked++;
        dirty = true;
    }

    /** Помечает привязку выплаченной и возвращает её (пусто, если платить не за что). */
    public synchronized Optional<RefLink> consumeLink(String invitee, long nowMillis) {
        RefLink link = links.get(ReferralRules.canonical(invitee));
        if (link == null || link.consumed) return Optional.empty();
        link.consumed = true;
        link.paidAt = nowMillis;
        dirty = true;
        return Optional.of(link);
    }

    /** Удаляет привязку приглашённого (admin reset); статистика пригласителя не трогается. */
    public synchronized boolean removeLink(String invitee) {
        dirty = dirty || links.containsKey(ReferralRules.canonical(invitee));
        return links.remove(ReferralRules.canonical(invitee)) != null;
    }

    /* ------------------------------- статистика ------------------------------- */

    public synchronized RefStats statsOf(String player) {
        return stats.computeIfAbsent(ReferralRules.canonical(player), key -> new RefStats());
    }

    /** Статистика, только для чтения (null-safe копия). */
    public synchronized RefStats statsView(String player) {
        RefStats stat = stats.get(ReferralRules.canonical(player));
        if (stat == null) return new RefStats();
        RefStats copy = new RefStats();
        copy.linked = stat.linked;
        copy.paid = stat.paid;
        copy.daysEarned = stat.daysEarned;
        return copy;
    }

    /** Топ приглашателей по числу оплативших (затем по дням). */
    public synchronized List<Map.Entry<String, RefStats>> topByPaid(int limit) {
        List<Map.Entry<String, RefStats>> ranked = new ArrayList<>(stats.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, RefStats>>comparingInt(e -> e.getValue().paid).reversed()
                .thenComparing((Map.Entry<String, RefStats> e) -> e.getValue().daysEarned).reversed());
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    /** Отмечает выплату дней пригласителю (после начисления через API). */
    public synchronized void recordBonusPaid(String inviter, long days) {
        RefStats stat = statsOf(inviter);
        stat.paid++;
        stat.daysEarned += days;
        dirty = true;
    }

    /* ------------------------------- сохранение ------------------------------- */

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> linksCopy = new LinkedHashMap<>();
        links.forEach((invitee, link) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("inviter", link.inviter);
            fields.put("linkedAt", link.linkedAt);
            fields.put("consumed", link.consumed);
            fields.put("paidAt", link.paidAt);
            linksCopy.put(invitee, fields);
        });
        data.put("links", linksCopy);
        Map<String, Object> statsCopy = new LinkedHashMap<>();
        stats.forEach((player, stat) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("linked", stat.linked);
            fields.put("paid", stat.paid);
            fields.put("daysEarned", stat.daysEarned);
            statsCopy.put(player, fields);
        });
        data.put("stats", statsCopy);
        dirty = false;
        return GSON.toJson(data);
    }

    public void saveNow() {
        if (!dirty && Files.isRegularFile(file)) return;
        String json = snapshotJson();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
        } catch (IOException e) {
            logger.severe("[Referrals] Не удалось записать " + file + ": " + e.getMessage());
            dirty = true;
        }
    }

    private static String strOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
