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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Личное оформление ника: что игрок показывает, в каком порядке и каким цветом
 * (1.4.186, порядок/цвет/пресеты — 1.4.187).
 *
 * <p>С 1.4.183 надписи прятались тремя группами, с 1.4.186 — каждая отдельно.
 * Теперь к этому добавилось три вещи, о которых просили игроки: свой порядок
 * меток после ника, свой цвет ника из палитры (часть цветов открывает ранг,
 * отдельные продаются в каталоге) и пресеты — сохранённые виды, которые
 * переключаются одной кнопкой.</p>
 *
 * <p>Файл — {@code labels.json}:
 * <pre>{@code
 * { "version": 3, "players": { "cherdak": {
 *     "hidden": ["rank"], "order": ["guilds", "rank"], "color": "aqua",
 *     "presets": { "p1": { "hidden": [], "order": [], "color": "gray" } } } } }
 * }</pre>
 * Ключ — ник в нижнем регистре: настройка переживает смену UUID и работает для
 * оффлайн-игрока. Пишется атомарно через {@link SafeJsonWriter}.</p>
 *
 * <p>Файлы прошлых версий читаются как есть: 1.4.183 ({@code badges} — все
 * аддонские метки) и 1.4.186 (только {@code hidden}). Порядок, цвет и пресеты
 * при первом сохранении дописываются, выбор игрока не теряется.</p>
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
     * Что именно можно спрятать и в каком порядке поставить. Ид у
     * {@code NAME}-надписей совпадает с id провайдера оформления, чтобы фильтр
     * не требовал отдельной таблицы.
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
     * Один вид ника: что скрыто, в каком порядке метки и каким цветом.
     *
     * <p>Пресет — это и есть снимок {@link #snapshot(String)}: сохранили
     * текущий вид, потом вернулись к нему одной кнопкой.</p>
     */
    public record Snapshot(int hidden, List<Label> order, String color) {

        public Snapshot {
            order = order == null ? List.of() : List.copyOf(order);
            color = color == null ? "" : color.trim().toLowerCase(Locale.ROOT);
        }

        /** Видна ли надпись в этом виде. */
        public boolean visible(Label label) {
            return label != null && (hidden & (1 << label.ordinal())) == 0;
        }

        /** Сколько надписей скрыто. */
        public int hiddenCount() {
            int count = 0;
            for (Label label : Label.values()) {
                if (!visible(label)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** Неизменяемое состояние игрока: новые записи — новый объект. */
    private record State(int hidden, List<Label> order, String color,
                         Map<String, Snapshot> presets) {

        static final State EMPTY = new State(0, List.of(), "", Map.of());

        State withHidden(int mask) {
            return new State(mask, order, color, presets);
        }

        State withOrder(List<Label> next) {
            return new State(hidden, next, color, presets);
        }

        State withColor(String next) {
            return new State(hidden, order, next, presets);
        }

        State withPreset(String slot, Snapshot snapshot) {
            Map<String, Snapshot> next = new LinkedHashMap<>(presets);
            next.put(slot, snapshot);
            return new State(hidden, order, color, Map.copyOf(next));
        }

        State withoutPreset(String slot) {
            if (!presets.containsKey(slot)) {
                return this;
            }
            Map<String, Snapshot> next = new LinkedHashMap<>(presets);
            next.remove(slot);
            return new State(hidden, order, color, Map.copyOf(next));
        }

        boolean isEmpty() {
            return hidden == 0 && order.isEmpty() && color.isEmpty() && presets.isEmpty();
        }

        /** Видна ли надпись при этом состоянии: та же маска, что и в {@link Snapshot}. */
        boolean visible(Label label) {
            return label != null && (hidden & (1 << label.ordinal())) == 0;
        }
    }

    /**
     * Ид старого формата «все метки одной группой» (1.4.183). При чтении
     * раскрывается во все аддонские источники — ранг остаётся отдельным.
     */
    static final String LEGACY_BADGES = "badges";

    private static final int VERSION = 3;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path file;
    private final Logger logger;
    /** Ник → состояние оформления. Чтения без блокировки, записи под synchronized. */
    private final Map<String, State> players = new ConcurrentHashMap<>();
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
            Object rawPlayers = root.get("players");
            if (!(rawPlayers instanceof Map<?, ?> map)) {
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = key(entry.getKey() == null ? null : String.valueOf(entry.getKey()));
                if (name.isEmpty() || !(entry.getValue() instanceof Map<?, ?> player)) {
                    continue;
                }
                State state = stateOf(player);
                if (!state.isEmpty()) {
                    players.put(name, state);
                }
            }
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            logger.warning("Не удалось прочитать labels.json: " + exception
                    + " — настройки надписей взяты по умолчанию (всё видно).");
        }
    }

    /** Состояние из одной записи файла: формат 1, 2 и 3 читаются одинаково. */
    private static State stateOf(Map<?, ?> player) {
        int mask = 0;
        Object raw = player.get("hidden");
        if (raw instanceof List<?> list) {
            mask = maskOf(list);
        }
        List<Label> order = orderOf(player.get("order"));
        Object rawColor = player.get("color");
        String color = rawColor == null ? "" : key(String.valueOf(rawColor));
        Map<String, Snapshot> presets = presetsOf(player.get("presets"));
        return new State(mask, order, color, presets);
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

    /** Порядок меток из файла: только надписи рядом с ником, без повторов. */
    private static List<Label> orderOf(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        Set<Label> seen = new LinkedHashSet<>();
        for (Object item : list) {
            Label label = Label.byNameTagId(item == null ? null : String.valueOf(item));
            if (label != null) {
                seen.add(label);
            }
        }
        return seen.isEmpty() ? List.of() : List.copyOf(seen);
    }

    private static Map<String, Snapshot> presetsOf(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String slot = key(entry.getKey() == null ? null : String.valueOf(entry.getKey()));
            if (slot.isEmpty() || !(entry.getValue() instanceof Map<?, ?> value)) {
                continue;
            }
            int mask = value.get("hidden") instanceof List<?> list ? maskOf(list) : 0;
            out.put(slot, new Snapshot(mask, orderOf(value.get("order")),
                    value.get("color") == null ? "" : key(String.valueOf(value.get("color")))));
        }
        return Map.copyOf(out);
    }

    /* ------------------------------- состояние -------------------------------- */

    private State state(String playerName) {
        return players.getOrDefault(key(playerName), State.EMPTY);
    }

    /** Видна ли надпись у этого игрока. Незнакомый ник — всё видно. */
    public boolean visible(String playerName, Label label) {
        return label == null || state(playerName).visible(label);
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
        return hiddenLabels(state(playerName));
    }

    private static List<Label> hiddenLabels(State state) {
        if (state.hidden == 0) {
            return List.of();
        }
        List<Label> out = new ArrayList<>();
        for (Label label : Label.values()) {
            if (!state.visible(label)) {
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
        return state(playerName).hidden != 0;
    }

    /** Сколько записей в файле — для диагностики. */
    public int size() {
        return players.size();
    }

    public boolean isDirty() {
        return dirty;
    }

    public Path file() {
        return file;
    }

    /**
     * Порядок меток слева направо. Пустой список — «как задумано»: ранг
     * ближе к нику, метки аддонов — левее, ровно как до 1.4.187.
     */
    public List<Label> order(String playerName) {
        return state(playerName).order;
    }

    /** Свой цвет ника; пусто — цвет даёт ранг или купленный тег каталога. */
    public String color(String playerName) {
        return state(playerName).color;
    }

    /** Пресеты игрока: имя слота → вид. */
    public Map<String, Snapshot> presets(String playerName) {
        return state(playerName).presets;
    }

    /** Текущий вид игрока — то, что сохраняется в пресет. */
    public Snapshot snapshot(String playerName) {
        State state = state(playerName);
        return new Snapshot(state.hidden, state.order, state.color);
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
        int mask = state(name).hidden;
        int next = visible ? (mask & ~bit) : (mask | bit);
        store(name, state(name).withHidden(next));
        return visible;
    }

    /**
     * Показывает или прячет группу надписей.
     *
     * <p>Нужно для старых команд {@code /labels badges}: они продолжают
     * работать, но теперь это массовое действие, а не единственный способ.</p>
     */
    public synchronized void setGroup(String playerName, Set<Label> labels, boolean visible) {
        String name = key(playerName);
        if (name.isEmpty() || labels == null || labels.isEmpty()) {
            return;
        }
        int mask = state(name).hidden;
        for (Label label : labels) {
            int bit = 1 << label.ordinal();
            mask = visible ? (mask & ~bit) : (mask | bit);
        }
        store(name, state(name).withHidden(mask));
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
        store(name, state(name).withHidden(mask));
        return visible;
    }

    /**
     * Новый порядок меток.
     *
     * <p>Принимаются только надписи рядом с ником; повторы и мусор
     * отбрасываются. Пустой список — вернуть вид «по умолчанию».</p>
     */
    public synchronized void setOrder(String playerName, List<Label> order) {
        String name = key(playerName);
        if (name.isEmpty()) {
            return;
        }
        store(name, state(name).withOrder(normalizeOrder(order)));
    }

    /** Свой цвет ника; пустая строка — снять выбор. */
    public synchronized void setColor(String playerName, String colorId) {
        String name = key(playerName);
        if (name.isEmpty()) {
            return;
        }
        String next = colorId == null ? "" : key(colorId);
        store(name, state(name).withColor(next));
    }

    /** Применить сохранённый вид (пресет или системный пресет из меню). */
    public synchronized void apply(String playerName, Snapshot snapshot) {
        String name = key(playerName);
        if (name.isEmpty() || snapshot == null) {
            return;
        }
        State current = state(name);
        store(name, new State(snapshot.hidden(), normalizeOrder(snapshot.order()),
                snapshot.color(), current.presets));
    }

    /** Сохранить текущий вид в слот пресета. */
    public synchronized boolean savePreset(String playerName, String slot) {
        String name = key(playerName);
        String key = key(slot);
        if (name.isEmpty() || key.isEmpty()) {
            return false;
        }
        store(name, state(name).withPreset(key, snapshot(name)));
        return true;
    }

    /** Удалить свой пресет. */
    public synchronized boolean deletePreset(String playerName, String slot) {
        String name = key(playerName);
        String key = key(slot);
        if (name.isEmpty() || key.isEmpty()) {
            return false;
        }
        State current = state(name);
        if (!current.presets.containsKey(key)) {
            return false;
        }
        store(name, current.withoutPreset(key));
        return true;
    }

    /** Возвращает текущий вид по умолчанию (пресеты остаются). */
    public synchronized void reset(String playerName) {
        String name = key(playerName);
        if (name.isEmpty()) {
            return;
        }
        State current = state(name);
        if (current.hidden == 0 && current.order.isEmpty() && current.color.isEmpty()) {
            return;
        }
        store(name, new State(0, List.of(), "", current.presets));
    }

    private void store(String name, State state) {
        State previous = players.getOrDefault(name, State.EMPTY);
        if (state.equals(previous)) {
            return;
        }
        if (state.isEmpty()) {
            players.remove(name);
        } else {
            players.put(name, state);
        }
        saveNow();
    }

    /* --------------------------------- запись --------------------------------- */

    /** JSON-снимок для записи и для тестов. */
    public synchronized String snapshotJson() {
        Map<String, Object> playersJson = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(players.keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            State state = players.get(name);
            if (state == null || state.isEmpty()) {
                continue;
            }
            Map<String, Object> player = new LinkedHashMap<>();
            List<String> hidden = new ArrayList<>();
            for (Label label : Label.values()) {
                if (!state.visible(label)) {
                    hidden.add(label.id());
                }
            }
            if (!hidden.isEmpty()) {
                player.put("hidden", hidden);
            }
            if (!state.order.isEmpty()) {
                player.put("order", state.order.stream().map(Label::id).toList());
            }
            if (!state.color.isEmpty()) {
                player.put("color", state.color);
            }
            if (!state.presets.isEmpty()) {
                Map<String, Object> presets = new LinkedHashMap<>();
                List<String> slots = new ArrayList<>(state.presets.keySet());
                slots.sort(String::compareTo);
                for (String slot : slots) {
                    Snapshot snapshot = state.presets.get(slot);
                    Map<String, Object> view = new LinkedHashMap<>();
                    List<String> presetHidden = new ArrayList<>();
                    for (Label label : Label.values()) {
                        if (!snapshot.visible(label)) {
                            presetHidden.add(label.id());
                        }
                    }
                    if (!presetHidden.isEmpty()) {
                        view.put("hidden", presetHidden);
                    }
                    if (!snapshot.order().isEmpty()) {
                        view.put("order", snapshot.order().stream().map(Label::id).toList());
                    }
                    if (!snapshot.color().isEmpty()) {
                        view.put("color", snapshot.color());
                    }
                    presets.put(slot, view);
                }
                player.put("presets", presets);
            }
            playersJson.put(name, player);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("players", playersJson);
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

    /** Уникальные метки в порядке первого появления — без мусора и повторов. */
    static List<Label> normalizeOrder(List<Label> labels) {
        if (labels == null || labels.isEmpty()) {
            return List.of();
        }
        Set<Label> seen = new LinkedHashSet<>();
        for (Label label : labels) {
            if (label != null && label.group() == Group.NAME) {
                seen.add(label);
            }
        }
        return seen.isEmpty() ? List.of() : List.copyOf(seen);
    }
}
