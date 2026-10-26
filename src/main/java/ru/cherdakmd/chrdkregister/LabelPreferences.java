package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Личные переключатели надписей (1.4.183): что игрок показывает в своём нике.
 *
 * <p>В табе и чате над ником висели ранг, звание кладоискателя, метка класса,
 * гильдии и стримера — и половина игроков хотела это выключить: кто-то не
 * любит рекламу своего ранга, кому-то мешает длинная строка в списке. Теперь у
 * каждого три переключателя: название и знак донатного ранга, метки аддонов и
 * шапка-подвал таба. Все они — про <b>свой</b> ник: таб-лист общий, поэтому
 * скрыть чужие метки «только для себя» ванильный клиент не умеет.</p>
 *
 * <p>Файл — {@code labels.json}:
 * <pre>{@code
 * { "version": 1, "players": { "cherdak": { "hidden": ["rank", "badges"] } } }
 * }</pre>
 * Ключ — ник в нижнем регистре: переключатели должны переживать смену UUID при
 * переезде сервера и работать для оффлайн-игрока. Пишется атомарно через
 * {@link SafeJsonWriter}; событие редкое (игрок нажал команду), поэтому запись
 * сразу, без таймера.</p>
 */
public final class LabelPreferences {

    /** Что именно можно спрятать. */
    public enum Category {
        /** Название и знак донатного ранга («· Покровитель», ❋). */
        RANK("rank"),
        /** Метки аддонов: класс, гильдия, стример, звание кладоискателя, титул. */
        BADGES("badges"),
        /** Шапка и подвал таб-листа — их видит только сам игрок. */
        HEADER("header");

        private final String id;

        Category(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Категория по иду; {@code null} — ид неизвестен. */
        public static Category byId(String raw) {
            if (raw == null) {
                return null;
            }
            String key = raw.trim().toLowerCase(Locale.ROOT);
            for (Category category : values()) {
                if (category.id.equals(key)) {
                    return category;
                }
            }
            return null;
        }
    }

    private static final int VERSION = 1;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;
    /** Ник → маска скрытых категорий. */
    private final Map<String, Integer> hidden = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public LabelPreferences(Path dataDir, Logger logger) {
        this.file = dataDir.resolve("labels.json");
        this.logger = logger;
    }

    /** Ключ хранения: ник без регистра и пробелов по краям. */
    public static String key(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
    }

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
            Object players = root.get("players");
            if (!(players instanceof Map<?, ?> map)) {
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = key(entry.getKey() == null ? null : String.valueOf(entry.getKey()));
                if (name.isEmpty() || !(entry.getValue() instanceof Map<?, ?> player)) {
                    continue;
                }
                int mask = 0;
                Object raw = player.get("hidden");
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        Category category = Category.byId(item == null ? null : String.valueOf(item));
                        if (category != null) {
                            mask |= 1 << category.ordinal();
                        }
                    }
                }
                if (mask != 0) {
                    hidden.put(name, mask);
                }
            }
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            logger.warning("Не удалось прочитать labels.json: " + exception
                    + " — настройки надписей взяты по умолчанию (всё видно).");
        }
    }

    /** Видна ли категория у этого игрока. Незнакомый ник — всё видно. */
    public boolean visible(String playerName, Category category) {
        if (category == null) {
            return true;
        }
        Integer mask = hidden.get(key(playerName));
        return mask == null || (mask & (1 << category.ordinal())) == 0;
    }

    /**
     * Переключает категорию.
     *
     * @return новое состояние: {@code true} — видно
     */
    public synchronized boolean toggle(String playerName, Category category) {
        if (category == null) {
            return true;
        }
        String name = key(playerName);
        if (name.isEmpty()) {
            return true;
        }
        int bit = 1 << category.ordinal();
        int mask = hidden.getOrDefault(name, 0);
        boolean nowVisible;
        if ((mask & bit) != 0) {
            mask &= ~bit;
            nowVisible = true;
        } else {
            mask |= bit;
            nowVisible = false;
        }
        store(name, mask);
        return nowVisible;
    }

    /**
     * Показывает или прячет всё сразу.
     *
     * @return {@code true} — теперь всё видно
     */
    public synchronized boolean setAll(String playerName, boolean visible) {
        String name = key(playerName);
        if (name.isEmpty()) {
            return true;
        }
        int mask = 0;
        if (!visible) {
            for (Category category : Category.values()) {
                mask |= 1 << category.ordinal();
            }
        }
        store(name, mask);
        return visible;
    }

    /** Возвращает всё как было (всё видно). */
    public synchronized void reset(String playerName) {
        String name = key(playerName);
        if (name.isEmpty()) {
            return;
        }
        if (hidden.remove(name) != null) {
            saveNow();
        }
    }

    /** Спрятано ли у игрока хоть что-то — для `/labels all`. */
    public boolean anyHidden(String playerName) {
        Integer mask = hidden.get(key(playerName));
        return mask != null && mask != 0;
    }

    /** Сколько записей в файле — для `/pass status` и диагностики. */
    public int size() {
        return hidden.size();
    }

    private void store(String name, int mask) {
        if (mask == 0) {
            hidden.remove(name);
        } else {
            hidden.put(name, mask);
        }
        saveNow();
    }

    public boolean isDirty() {
        return dirty;
    }

    /** JSON-снимок для записи и для тестов. */
    public synchronized String snapshotJson() {
        Map<String, Object> players = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(hidden.keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            int mask = hidden.getOrDefault(name, 0);
            List<String> list = new ArrayList<>();
            for (Category category : Category.values()) {
                if ((mask & (1 << category.ordinal())) != 0) {
                    list.add(category.id());
                }
            }
            if (list.isEmpty()) {
                continue;
            }
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("hidden", list);
            players.put(name, player);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("players", players);
        return gson.toJson(root);
    }

    /** Записывает файл сейчас. */
    public void saveNow() {
        String json;
        synchronized (this) {
            json = snapshotJson();
            dirty = false;
        }
        boolean written = SafeJsonWriter.write(file, json, logger, "[CHRDK_Core]");
        if (!written) {
            dirty = true;
            logger.warning("Не удалось сохранить labels.json — настройки надписей "
                    + "останутся в памяти до следующей попытки.");
        }
    }

    /** Путь файла (для диагностики и тестов). */
    public Path file() {
        return file;
    }
}
