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

    /** Имя точки «кровать»: её ставит сервер сам, когда игрок ложится спать. */
    public static final String BED = "bed";

    private static final Pattern NAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]{1,16}");

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;
    private final Map<UUID, Map<String, HomeLocation>> homes = new LinkedHashMap<>();
    private volatile boolean dirty = false;
    private boolean healthy = true;
    private ru.cherdakmd.chrdkregister.api.PaidActionStore finance;
    public static String financeKey(UUID owner,String name) { return owner+":"+normalize(name); }
    public static String financeValue(HomeLocation home) { return new Gson().toJson(home); }
    public synchronized void bind(ru.cherdakmd.chrdkregister.api.PaidActionStore source) throws IOException {
        if (!healthy) throw new IOException("Повреждён источник домов");
        if (!source.valuesInitialized()) {
            var values=new LinkedHashMap<String,String>(); homes.forEach((id,points)->points.forEach((name,home)->values.put(financeKey(id,name),financeValue(home))));
            source.initializeValues(values);
        }
        for (var row:source.values().entrySet()) {
            int colon=row.getKey().indexOf(':'); if (colon<1) throw new IOException("Неверный ключ дома");
            UUID.fromString(row.getKey().substring(0,colon));
            HomeLocation home=new Gson().fromJson(row.getValue(),HomeLocation.class);
            if (!valid(home) || !normalize(home.name()).equals(row.getKey().substring(colon+1))) throw new IOException("Неверный дом");
        }
        finance=source; dirty=false;
    }
    private static boolean valid(HomeLocation h) { return h!=null && isValidName(h.name()) && h.world()!=null && !h.world().isBlank() && Double.isFinite(h.x()) && Double.isFinite(h.y()) && Double.isFinite(h.z()) && Math.abs(h.x())<=30_000_000 && Math.abs(h.z())<=30_000_000 && Float.isFinite(h.yaw()) && Float.isFinite(h.pitch()); }
    private void writeValue(String key,String value) {
        try { finance.putValue(key,value); } catch (IOException error) { throw new IllegalStateException("Дом не сохранён; источник остановлен",error); }
    }

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

    /** Кровать ли это (точка {@value #BED}). */
    public static boolean isBedName(String name) {
        return name != null && BED.equalsIgnoreCase(name.trim());
    }

    /**
     * Можно ли игроку занять это имя через {@code /sethome}.
     *
     * <p>{@code bed} зарезервировано: точку ставит сервер, когда игрок ложится
     * спать, и игрок не должен затирать её вручную, не понимая, что делает.</p>
     */
    public static boolean isPlayerNameAllowed(String name) {
        return isValidName(name) && !isBedName(name);
    }

    public synchronized Map<String, HomeLocation> of(UUID uuid) {
        if (finance!=null) {
            var result=new LinkedHashMap<String,HomeLocation>(); String prefix=uuid+":";
            finance.values().forEach((key,value)->{ if (key.startsWith(prefix)) result.put(key.substring(prefix.length()),new Gson().fromJson(value,HomeLocation.class)); });
            return result;
        }
        return homes.computeIfAbsent(uuid, key -> new LinkedHashMap<>());
    }

    /** Сохраняет/заменяет точку. true = заменил существующую. */
    public synchronized boolean put(UUID uuid, HomeLocation home) {
        if (!valid(home)) throw new IllegalArgumentException("home");
        if (finance!=null) { boolean replaced=get(uuid,normalize(home.name()))!=null; writeValue(financeKey(uuid,home.name()),financeValue(home)); return replaced; }
        boolean replaced = of(uuid).put(normalize(home.name()), home) != null;
        dirty = true;
        return replaced;
    }

    public synchronized HomeLocation get(UUID uuid, String nameLower) {
        return of(uuid).get(nameLower);
    }

    public synchronized boolean remove(UUID uuid, String nameLower) {
        if (finance!=null) { boolean found=get(uuid,nameLower)!=null; if (found) writeValue(financeKey(uuid,nameLower),null); return found; }
        boolean removed = of(uuid).remove(nameLower) != null;
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    /** Список точек игрока (по имени). */
    public synchronized List<HomeLocation> list(UUID uuid) {
        List<HomeLocation> list = new ArrayList<>(of(uuid).values());
        list.sort(Comparator.comparing(home -> normalize(home.name())));
        return list;
    }

    public synchronized int count(UUID uuid) {
        return of(uuid).size();
    }

    /**
     * Сколько у игрока «своих» точек дома. Кровать не считается: её ставит
     * сервер, и она не должна съедать слот из лимита.
     */
    public synchronized int countPlayerHomes(UUID uuid) {
        int total = 0;
        for (String key : of(uuid).keySet()) {
            if (!isBedName(key)) {
                total++;
            }
        }
        return total;
    }

    public boolean isDirty() {
        return finance==null && dirty;
    }

    /* ------------------------------- загрузка/сохранение ------------------------------- */

    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = gson.fromJson(reader, LinkedHashMap.class);
            if (root == null || !(root.get("homes") instanceof Map)) throw new JsonParseException("Нет таблицы homes");
            Object homesObj = root.get("homes");
            if (homesObj instanceof Map<?, ?> map) {
                map.forEach((uuidKey, sections) -> {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(String.valueOf(uuidKey));
                    } catch (IllegalArgumentException e) { throw new JsonParseException("Неверный UUID дома",e); }
                    if (!(sections instanceof Map<?, ?> points)) throw new JsonParseException("Неверные дома");
                    Map<String, HomeLocation> player = homes.computeIfAbsent(uuid, key -> new LinkedHashMap<>());
                    points.forEach((nameKey, rawLoc) -> {
                        HomeLocation home = decodeHome(String.valueOf(nameKey), rawLoc);
                        if (!valid(home) || !normalize(home.name()).equals(String.valueOf(nameKey))) throw new JsonParseException("Неверная точка дома");
                        player.put(normalize(home.name()), home);
                    });
                });
            }
            dirty = false;
        } catch (IOException | JsonParseException e) {
            healthy=false; throw new IllegalStateException("Повреждён teleports-data.json; файл не сброшен",e);
        }
    }

    public synchronized String snapshotJson() {
        if (finance!=null) {
            var current=new LinkedHashMap<String,Map<String,Object>>();
            finance.values().forEach((key,value)->{ int colon=key.indexOf(':'); current.computeIfAbsent(key.substring(0,colon),ignored->new LinkedHashMap<>()).put(key.substring(colon+1),encodeHome(new Gson().fromJson(value,HomeLocation.class))); });
            return gson.toJson(Map.of("homes",current));
        }
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
        if (finance!=null || !healthy) return;
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
        for (String key:List.of("world","x","y","z","yaw","pitch")) if (!map.containsKey(key)) return null;
        for (String key:List.of("x","y","z","yaw","pitch")) if (!(map.get(key) instanceof Number)) return null;
        if (!(map.get("world") instanceof String)) return null;
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
