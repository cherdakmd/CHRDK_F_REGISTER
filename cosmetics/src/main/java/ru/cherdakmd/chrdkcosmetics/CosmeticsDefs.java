package ru.cherdakmd.chrdkcosmetics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** Определения косметических эффектов из config.yml — чистый парсинг для тестов. */
final class CosmeticsDefs {

    private CosmeticsDefs() {
    }

    /**
     * Один шлейф частиц. id нормализуется в нижний регистр.
     *
     * <p>{@code product} — id товара каталога ядра, который открывает шлейф
     * ({@code /pass shopbuy}). Если в конфиге поле не задано, берётся
     * {@code trail-<id>}: так товары и шлейфы совпадают без лишней настройки.</p>
     */
    record TrailDef(String id, String displayName, String particle, int count,
                    double spread, double speed, String product, int rank) {
    }

    /** Параметры входа-торжества. */
    record JoinEffect(boolean enabled, String particle, int particleCount,
                      String sound, double volume, double pitch) {
        static JoinEffect disabled() {
            return new JoinEffect(false, "", 0, "", 1.0, 1.0);
        }
    }

    static List<TrailDef> parseTrails(List<Map<?, ?>> raw, boolean enabled) {
        if (!enabled) {
            return List.of();
        }
        List<TrailDef> out = new ArrayList<>();
        for (Map<?, ?> entry : raw) {
            String id = norm(entry.get("id"));
            String particle = norm(entry.get("particle"));
            if (id.isEmpty() || particle.isEmpty()) {
                continue; // безсообразные записи пропускаем — сервер не извещаем смертью
            }
            Object nameRaw = entry.get("name");
            String name = nameRaw == null ? id : String.valueOf(nameRaw);
            String product = norm(entry.get("product"));
            if (product.isEmpty()) {
                product = "trail-" + id;
            }
            int rank = clamp(intOf(entry.get("rank"), 0), 0, 5);
            int count = clamp(intOf(entry.get("count"), 2), 1, 50);
            double spread = doubleOf(entry.get("spread"), 0.2);
            double speed = doubleOf(entry.get("speed"), 0.0);
            if (out.stream().anyMatch(def -> def.id().equals(id))) {
                continue; // дубликаты id молча игнорируем
            }
            out.add(new TrailDef(id, name, particle, count, spread, speed, product, rank));
        }
        return List.copyOf(out);
    }

    static JoinEffect parseJoinEffect(Map<?, ?> raw) {
        if (raw == null || !boolOf(raw.get("enabled"), false)) {
            return JoinEffect.disabled();
        }
        String particle = norm(raw.get("particle"));
        String sound = norm(raw.get("sound"));
        if (particle.isEmpty() && sound.isEmpty()) {
            return JoinEffect.disabled();
        }
        return new JoinEffect(true, particle,
                clamp(intOf(raw.get("particle-count"), 40), 1, 500),
                sound, doubleOf(raw.get("volume"), 1.0), doubleOf(raw.get("pitch"), 1.0));
    }

    /** Валиден ли выбор игрока: есть такой эффект и он доступен. */
    static TrailDef find(List<TrailDef> trails, String id) {
        String wanted = norm(id);
        for (TrailDef def : trails) {
            if (def.id().equals(wanted)) {
                return def;
            }
        }
        return null;
    }

    /**
     * Открыт ли шлейф игроку — чистая логика для теста.
     *
     * <p>Порядок (1.4.167): шлейф с полем {@code rank} открывает только ступень
     * ранга не ниже своей; остальные — по старому правилу {@code passOnly} или
     * покупкой. {@code passOnly: false} — косметика бесплатна для всех.</p>
     *
     * @param rankLevel ступень донатного ранга игрока (0 — без ранга)
     * @param owned проверка «куплен ли товар с таким id и не истёк ли срок»
     * @param packProducts товары-наборы, открывающие сразу все шлейфы
     */
    static boolean unlocked(TrailDef def, boolean passOnly, boolean hasPass, int rankLevel,
                            Predicate<String> owned, List<String> packProducts) {
        if (def != null && def.rank() > 0) {
            // Ранг открывает шлейфы своей ступени и ниже (1.4.167).
            if (rankLevel >= def.rank()) {
                return true;
            }
            return purchased(def, owned, packProducts);
        }
        if (!passOnly || hasPass) {
            return true;
        }
        return purchased(def, owned, packProducts);
    }

    /** Шлейф куплен поштучно или входит в купленный набор. */
    private static boolean purchased(TrailDef def, Predicate<String> owned, List<String> packProducts) {
        if (def == null || owned == null) {
            return false;
        }
        if (!def.product().isEmpty() && owned.test(def.product())) {
            return true;
        }
        if (packProducts != null) {
            for (String pack : packProducts) {
                if (pack != null && !pack.isBlank()
                        && owned.test(pack.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Решение о показе шлейфа за тик — чистая логика для теста. */
    static boolean trailAllowed(boolean trailsEnabled, boolean unlocked, String selectedId) {
        return trailsEnabled && unlocked && selectedId != null && !selectedId.isBlank();
    }

    private static String norm(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
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

    private static double doubleOf(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static boolean boolOf(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
