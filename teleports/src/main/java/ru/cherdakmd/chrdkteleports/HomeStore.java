package ru.cherdakmd.chrdkteleports;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Хранилище точек дома: teleports-data.json { "homes": {uuid: {имя_lower: {...}}} }.
 * Паттерн общий с ShopStore/DailyStore: dirty-флаг + snapshotJson() (потокобезопасно)
 * + saveNow().
 */
public final class HomeStore {

    private static final Pattern NAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]{1,16}");

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;
    private final Map<UUID, Map<String, HomeLocation>> homes = new LinkedHashMap<>();
    private volatile boolean dirty = false;

    public HomeStore(Path dataDir, Logger logger) {
        this.file = dataDir.resolve("teleports-data.json");
        this.logger = logger;
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    public static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public synchronized Map<String, HomeLocation> of(UUID uuid) {
        return homes.computeIfAbsent(uuid, key -> new LinkedHashMap<>());
    }

    /** Сохраняет/заменяет точку. true = заменил существующую. */
    public synchronized boolean put(UUID uuid, HomeLocation home) {
        boolean replaced = of(uuid).put(normalize(home.name()), home) != null;
        dirty = true;
        return replaced;
    }

    public synchronized HomeLocation get(UUID uuid, String nameLower) {
        return homes.getOrDefault(uuid, Map.of()).get(nameLower);
    }

    public synchronized boolean remove(UUID uuid, String nameLower) {
        boolean removed = of(uuid).remove(nameLower) != null;
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    /** Список точек игрока (по имени). */
    public synchronized List<HomeLocation> list(UUID uuid) {
        List<HomeLocation> list = new ArrayList<>(homes.getOrDefault(uuid, Map.of()).values());
        list.sort(Comparator.comparing(home -> normalize(home.name())));
        return list;
    }

    public synchronized int count(UUID uuid) {
        return homes.getOrDefault(uuid, Map.of()).size();
    }

    public boolean isDirty() {
        return dirty;
    }

    /* ------------------------------- загрузка/сохранение ------------------------------- */

    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = gson.fromJson(reader, LinkedHashMap.class);
            if (root == null) {
                return;
            }
            Object homesObj = root.get("homes");
            if (homesObj instanceof Map<?, ?> map) {
                map.forEach((uuidKey, sections) -> {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(String.valueOf(uuidKey));
                    } catch (IllegalArgumentException e) {
                        return;
                    }
                    if (!(sections instanceof Map<?, ?> points)) return;
                    Map<String, HomeLocation> player = homes.computeIfAbsent(uuid, key -> new LinkedHashMap<>());
                    points.forEach((nameKey, rawLoc) -> {
                        HomeLocation home = decodeHome(String.valueOf(nameKey), rawLoc);
                        if (home != null) {
                            player.put(normalize(home.name()), home);
                        }
                    });
                });
            }
            dirty = false;
        } catch (IOException | JsonParseException e) {
            logger.warning("Не удалось прочитать teleports-data.json: " + e
                    + ". Начнём с чистого листа (файл перезапишется при изменениях).");
        }
    }

    public synchronized String snapshotJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> homesObj = new LinkedHashMap<>();
        homes.forEach((uuid, points) -> {
            Map<String, Object> player = new LinkedHashMap<>();
            points.forEach((nameLower, home) -> player.put(nameLower, encodeHome(home)));
            homesObj.put(uuid.toString(), player);
        });
        root.put("homes", homesObj);
        return gson.toJson(root);
    }

    public void saveNow() {
        String json;
        synchronized (this) {
            json = snapshotJson();
            dirty = false;
        }
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
        } catch (IOException e) {
            logger.severe("Не удалось сохранить teleports-data.json: " + e);
            dirty = true;
        }
    }

    /* ------------------------------- сериализация ------------------------------- */

    private Map<String, Object> encodeHome(HomeLocation home) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", home.name());
        map.put("world", home.world());
        map.put("x", home.x());
        map.put("y", home.y());
        map.put("z", home.z());
        map.put("yaw", (double) home.yaw());
        map.put("pitch", (double) home.pitch());
        return map;
    }

    private HomeLocation decodeHome(String fallbackName, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object nameRaw = map.get("name");
        Object worldRaw = map.get("world");
        String name = nameRaw == null ? fallbackName : String.valueOf(nameRaw);
        String world = worldRaw == null ? "world" : String.valueOf(worldRaw);
        if (!isValidName(name) || "null".equals(world)) {
            return null;
        }
        return new HomeLocation(name, world,
                num(map.get("x")), num(map.get("y")), num(map.get("z")),
                (float) num(map.get("yaw")), (float) num(map.get("pitch")));
    }

    private static double num(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
