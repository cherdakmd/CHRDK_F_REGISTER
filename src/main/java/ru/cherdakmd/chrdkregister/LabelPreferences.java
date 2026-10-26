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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Личные переключатели надписей: что игрок показывает в своём нике (1.4.186).
 *
 * <p>С 1.4.183 надписи прятались тремя группами: ранг, «метки» и шапка таба.
 * Внутри «меток» лежали сразу титул кузни, звание кладоискателя, класс, гильдия,
 * стример, квесты и достижения — выключить одно из них было нельзя. Теперь у
 * каждого звания свой источник: игрок сам решает, что оставить, и это ровно те
 * id, под которыми аддоны регистрируют оформление через
 * {@link ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi#registerNameTag}.
 * Так фильтр живёт в одном месте — ядре, — а аддоны о настройках не знают.</p>
 *
 * <p>Файл — {@code labels.json}:
 * <pre>{@code
 * { "version": 2, "players": { "cherdak": { "hidden": ["rank", "streams"] } } }
 * }</pre>
 * Ключ — ник в нижнем регистре: настройка должна переживать смену UUID при
 * переезде сервера и работать для оффлайн-игрока. Пишется атомарно через
 * {@link SafeJsonWriter}; событие редкое (игрок нажал кнопку), поэтому запись
 * сразу, без таймера.</p>
 *
 * <p>Файл 1.4.183 читается как есть: {@code badges} раскрывается во все
 * аддонские источники, {@code rank} и {@code header} совпадают по смыслу.
 * Первое изменение перезапишет файл в новом формате — потерь нет.</p>
 */
public final class LabelPreferences {

    /** Куда попадает надпись: в ник или в шапку списка игроков. */
    public enum Group {
        /** Надпись рядом с ником в чате и табе. */
        NAME,
        /** Шапка и подвал таб-листа — их видит только сам игрок. */
        TAB
    }

    /**
     * Что именно можно спрятать. Ид у {@code NAME}-надписей совпадает с id
     * провайдера оформления, чтобы фильтр не требовал отдельной таблицы.
     */
    public enum Label {
        /** Название и знак донатного ранга («· Покровитель», ❋). */
        RANK("rank", Group.NAME),
        /** Титул за закрытые разделы коллекции кузни. */
        TIERFORGE("tierforge", Group.NAME),
        /** Звание кладоискателя за найденные клады. */
        TREASURES("treasures", Group.NAME),
        /** Метка выбранного класса. */
        CLASSES("classes", Group.NAME),
        /** Метка гильдии. */
        GUILDS("guilds", Group.NAME),
        /** Метка стримера (Twitch / VK Play Live). */
        STREAMS("streams", Group.NAME),
        /** Метка квестов и общего прогресса. */
        QUESTS("quests", Group.NAME),
        /** Значок за собранные достижения. */
        ACHIEVEMENTS("achievements", Group.NAME),
        /** Шапка и подвал таб-листа. */
        HEADER("header", Group.TAB);

        private final String id;
        private final Group group;

        Label(String id, Group group) {
            this.id = id;
            this.group = group;
        }

        public String id() {
            return id;
        }

        public Group group() {
            return group;
        }

        /** Значение по иду; {@code null} — ид неизвестен (новая версия файла). */
        public static Label byId(String raw) {
            if (raw == null) {
                return null;
            }
            String key = raw.trim().toLowerCase(Locale.ROOT);
            for (Label label : values()) {
                if (label.id.equals(key)) {
                    return label;
                }
            }
            return null;
        }

        /**
         * Источник по id провайдера оформления. Возвращает {@code null} для
         * id, которых ядро не знает: чужой аддон не должен потерять метку
         * только потому, что о нём не слышали.
         */
        public static Label byNameTagId(String raw) {
            Label label = byId(raw);
            // Шапка таба не рисуется рядом с ником, значит и провайдером
            // оформления быть не может: чужой вызов с таким id — «видно».
            return label != null && label.group == Group.NAME ? label : null;
        }

        /** Все надписи, которые рисуются рядом с ником (без шапки таба). */
        public static List<Label> nameTags() {
            List<Label> out = new ArrayList<>();
            for (Label label : values()) {
                if (label.group == Group.NAME) {
                    out.add(label);
                }
            }
            return List.copyOf(out);
        }
    }

    /**
     * Ид старого формата «все метки одной группой» (1.4.183). При чтении
     * раскрывается во все аддонские источники — ранг остаётся отдельным.
     */
    static final String LEGACY_BADGES = "badges";

    private static final int VERSION = 2;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;
    /** Ник → маска скрытых надписей. */
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

    /* --------------------------------- чтение --------------------------------- */

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
                    mask = maskOf(list);
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

    /** Маска из списка идов файла — с разворотом старой группы {@code badges}. */
    private static int maskOf(List<?> ids) {
        int mask = 0;
        for (Object item : ids) {
            String id = item == null ? null : String.valueOf(item);
            if (legacyBadges(id)) {
                for (Label label : Label.values()) {
                    if (label.group() == Group.NAME && label != Label.RANK) {
                        mask |= 1 << label.ordinal();
                    }
                }
                continue;
            }
            Label label = Label.byId(id);
            if (label != null) {
                mask |= 1 << label.ordinal();
            }
        }
        return mask;
    }

    private static boolean legacyBadges(String raw) {
        return raw != null && LEGACY_BADGES.equals(raw.trim().toLowerCase(Locale.ROOT));
    }

    /* ------------------------------- состояние -------------------------------- */

    /** Видна ли надпись у этого игрока. Незнакомый ник — всё видно. */
    public boolean visible(String playerName, Label label) {
        if (label == null) {
            return true;
        }
        Integer mask = hidden.get(key(playerName));
        return mask == null || (mask & (1 << label.ordinal())) == 0;
    }

    /**
     * Видна ли метка аддона по id его провайдера.
     *
     * <p>Неизвестный id (аддон новее ядра или чужой плагин) считается видимым:
     * молча терять чужую метку из-за незнакомого имени нельзя.</p>
     */
    public boolean visibleNameTag(String playerName, String nameTagId) {
        Label label = Label.byNameTagId(nameTagId);
        return label == null || visible(playerName, label);
    }

    /** Скрытые надписи игрока в порядке объявления. */
    public List<Label> hiddenLabels(String playerName) {
        Integer mask = hidden.get(key(playerName));
        if (mask == null || mask == 0) {
            return List.of();
        }
        List<Label> out = new ArrayList<>();
        for (Label label : Label.values()) {
            if ((mask & (1 << label.ordinal())) != 0) {
                out.add(label);
            }
        }
        return List.copyOf(out);
    }

    /** Сколько надписей скрыто — для строки статуса в профиле. */
    public int hiddenCount(String playerName) {
        return hiddenLabels(playerName).size();
    }

    /** Спрятано ли у игрока хоть что-то — для {@code /labels all}. */
    public boolean anyHidden(String playerName) {
        Integer mask = hidden.get(key(playerName));
        return mask != null && mask != 0;
    }

    /** Сколько записей в файле — для диагностики. */
    public int size() {
        return hidden.size();
    }

    public boolean isDirty() {
        return dirty;
    }

    public Path file() {
        return file;
    }

    /* ------------------------------- изменение -------------------------------- */

    /**
     * Переключает надпись.
     *
     * @return новое состояние: {@code true} — видно
     */
    public synchronized boolean toggle(String playerName, Label label) {
        if (label == null) {
            return true;
        }
        return set(playerName, label, !visible(playerName, label));
    }

    /**
     * Показывает или прячет одну надпись.
     *
     * @return итоговое состояние: {@code true} — видно
     */
    public synchronized boolean set(String playerName, Label label, boolean visible) {
        if (label == null) {
            return true;
        }
        String name = key(playerName);
        if (name.isEmpty()) {
            return true;
        }
        int bit = 1 << label.ordinal();
        int mask = hidden.getOrDefault(name, 0);
        int next = visible ? (mask & ~bit) : (mask | bit);
        store(name, next);
        return visible;
    }

    /**
     * Показывает или прячет группу надписей.
     *
     * <p>Нужно для старых команд {@code /labels badges} и {@code /labels rank}:
     * они продолжают работать, но теперь это массовое действие, а не
     * единственный способ.</p>
     */
    public synchronized void setGroup(String playerName, Set<Label> labels, boolean visible) {
        String name = key(playerName);
        if (name.isEmpty() || labels == null || labels.isEmpty()) {
            return;
        }
        int mask = hidden.getOrDefault(name, 0);
        for (Label label : labels) {
            int bit = 1 << label.ordinal();
            mask = visible ? (mask & ~bit) : (mask | bit);
        }
        store(name, mask);
    }

    /** Все аддонские метки — то, что раньше называлось {@code badges}. */
    public static Set<Label> badgeLabels() {
        Set<Label> out = EnumSet.noneOf(Label.class);
        for (Label label : Label.values()) {
            if (label.group() == Group.NAME && label != Label.RANK) {
                out.add(label);
            }
        }
        return Set.copyOf(out);
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
            for (Label label : Label.values()) {
                mask |= 1 << label.ordinal();
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

    private void store(String name, int mask) {
        int previous = hidden.getOrDefault(name, 0);
        if (mask == 0) {
            hidden.remove(name);
        } else {
            hidden.put(name, mask);
        }
        if (mask != previous) {
            saveNow();
        }
    }

    /* --------------------------------- запись --------------------------------- */

    /** JSON-снимок для записи и для тестов. */
    public synchronized String snapshotJson() {
        Map<String, Object> players = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(hidden.keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            int mask = hidden.getOrDefault(name, 0);
            List<String> list = new ArrayList<>();
            for (Label label : Label.values()) {
                if ((mask & (1 << label.ordinal())) != 0) {
                    list.add(label.id());
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
}
