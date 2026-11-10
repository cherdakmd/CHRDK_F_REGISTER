package ru.cherdakmd.chrdkdisasters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Профиль игрока в катастрофах: осколки, опыт стойкости, пережитые виды и
 * временный пропуск. Класс без Bukkit — сериализуется в disasters-progress.json
 * и целиком проверяется JUnit.
 */
public final class DisasterProfile {

    private long shards;
    private long earned;
    private long spent;
    private long xp;
    private long dailyEarned;
    private String day = "";
    private long wardUntil;
    private final Set<String> kinds = new LinkedHashSet<>();

    public long shards() {
        return shards;
    }

    public long earned() {
        return earned;
    }

    public long spent() {
        return spent;
    }

    public long xp() {
        return xp;
    }

    public long dailyEarned() {
        return dailyEarned;
    }

    public String day() {
        return day;
    }

    public long wardUntil() {
        return wardUntil;
    }

    public Set<String> kinds() {
        return Set.copyOf(kinds);
    }

    public int kindCount() {
        return kinds.size();
    }

    /** @return сколько осколков реально начислено с учётом дневного лимита. */
    public long earn(long requested, int dailyCap, String today) {
        if (today != null && !today.equals(day)) {
            day = today == null ? "" : today;
            dailyEarned = 0L;
        }
        long left = DisasterProgressMath.dailyLeft(dailyEarned, dailyCap);
        long granted = Math.min(Math.max(0L, requested), left);
        if (granted <= 0L) {
            return 0L;
        }
        shards += granted;
        earned += granted;
        dailyEarned += granted;
        return granted;
    }

    public boolean spend(long price) {
        if (price <= 0L || shards < price) {
            return false;
        }
        shards -= price;
        spent += price;
        return true;
    }

    /** Возврат осколков, если выдача награды не удалась (например, банк недоступен). */
    public void refund(long price) {
        if (price <= 0L) {
            return;
        }
        shards += price;
        spent = Math.max(0L, spent - price);
    }

    public void addXp(long amount) {
        xp += Math.max(0L, amount);
    }

    /** @return true, если вид пережит впервые (для метрики disaster-kinds). */
    public boolean addKind(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return kinds.add(id.trim().toLowerCase(Locale.ROOT));
    }

    public void grantWard(long fromMillis, long durationMillis) {
        wardUntil = Math.max(wardUntil, fromMillis + Math.max(0L, durationMillis));
    }

    public boolean isWardActive(long nowMillis) {
        return nowMillis < wardUntil;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shards", shards);
        out.put("earned", earned);
        out.put("spent", spent);
        out.put("xp", xp);
        out.put("dailyEarned", dailyEarned);
        out.put("day", day);
        out.put("wardUntil", wardUntil);
        out.put("kinds", new ArrayList<>(kinds));
        return out;
    }

    public static DisasterProfile fromMap(Map<String, Object> raw) {
        DisasterProfile profile = new DisasterProfile();
        if (raw == null) {
            return profile;
        }
        profile.shards = number(raw.get("shards"));
        profile.earned = number(raw.get("earned"));
        profile.spent = number(raw.get("spent"));
        profile.xp = number(raw.get("xp"));
        profile.dailyEarned = number(raw.get("dailyEarned"));
        profile.wardUntil = number(raw.get("wardUntil"));
        Object day = raw.get("day");
        profile.day = day == null ? "" : String.valueOf(day);
        Object kinds = raw.get("kinds");
        if (kinds instanceof Iterable<?> list) {
            for (Object entry : list) {
                profile.addKind(String.valueOf(entry));
            }
        }
        return profile;
    }

    private static long number(Object raw) {
        if (raw instanceof Number value) {
            return value.longValue();
        }
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, DisasterProfile> readPlayers(Object raw) {
        Map<String, DisasterProfile> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            Object value = entry.getValue();
            out.put(key, fromMap(value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of()));
        }
        return out;
    }

    public static Map<String, Object> writePlayers(Map<String, DisasterProfile> players) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, DisasterProfile> entry : players.entrySet()) {
            out.put(entry.getKey(), entry.getValue().toMap());
        }
        return out;
    }

    public static List<String> ids(Set<String> kinds) {
        return List.copyOf(new ArrayList<>(kinds));
    }
}
