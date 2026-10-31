package ru.cherdakmd.chrdkprofessions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Профессии игроков в {@code professions.json} (1.4.165).
 *
 * <p>Хранится только то, что нельзя вывести: выбранная профессия, опыт по
 * каждой из четырёх и суточный счётчик. Уровень считается из опыта, поэтому
 * «в файле 12-й, а опыта на 9-й» невозможно.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}. Класс чистый
 * (Gson + NIO + UUID) и проверяется юнит-тестами без Bukkit.</p>
 */
public final class ProfessionStore {

    public static final int VERSION = 3;
    /** Потолок числа игроков в файле: защита от мусора. */
    public static final int MAX_PLAYERS = 200_000;
    /** Потолок опыта одной профессии: защита от правки файла руками. */
    public static final int MAX_XP = 1_000_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    /** Суточный счётчик опыта одной профессии. */
    public static final class Daily {
        private String date = "";
        private int amount;

        public String date() {
            return date;
        }

        public int amount() {
            return amount;
        }
    }

    /** Данные одного игрока. Мутировать можно только через методы хранилища. */
    public static final class Player {
        private final UUID id;
        private String name;
        private String active = "";
        private final Map<String, Integer> xp = new LinkedHashMap<>();
        private final Map<String, Daily> daily = new LinkedHashMap<>();
        private long changedAt;
        private final Map<String,Map<String,Integer>> practice=new LinkedHashMap<>();
        private final Map<String,Integer> trials=new LinkedHashMap<>();
        private String workReceipt="";
        private final Map<String, String> mastery = new LinkedHashMap<>();
        private final Map<String, Long> masteryChanged = new LinkedHashMap<>();
        private final Map<String, Long> workUntil = new LinkedHashMap<>();

        Player(UUID id, String name) {
            this.id = id;
            this.name = name == null ? "" : name;
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private boolean dirty;
    private boolean healthy=true;
    private final Object ioLock=new Object();
    public synchronized boolean healthy(){return healthy;}

    public ProfessionStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("professions.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Читает файл; битые записи пропускаются с предупреждением. */
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null)throw new IllegalArgumentException("null professions file");
            long version=number(data.get("version"));
            if(version<1||version>VERSION||!(data.get("players") instanceof Map<?,?>))throw new IllegalArgumentException("profession schema");
            int skipped = 0;
            if (data.get("players") instanceof Map<?, ?> rows) {
                for (Map.Entry<?, ?> entry : rows.entrySet()) {
                    UUID id = parseUuid(entry.getKey());
                    if (id == null || !(entry.getValue() instanceof Map<?, ?> row)
                            || players.size() >= MAX_PLAYERS) {
                        throw new IllegalArgumentException("invalid profession player");
                    }
                    players.put(id, readPlayer(id, row));
                }
            }
            if (skipped > 0 && logger != null) {
                logger.warning("[CHRDK_Professions] Пропущено записей professions.json: "
                        + skipped + ".");
            }
        } catch (IOException | RuntimeException error) {
            healthy=false;
            if (logger != null) {
                logger.severe("[CHRDK_Professions] Не удалось прочитать " + file + ": "
                        + error.getMessage() + " — исходник не перезаписывается.");
            }
            throw new IllegalStateException("Повреждён professions.json",error);
        }
    }

    private static Player readPlayer(UUID id, Map<?, ?> row) {
        String name = row.get("name") == null ? "" : row.get("name").toString();
        Player player = new Player(id, name);
        String active = id(row.get("active"));
        player.active = Profession.exists(active) ? active : "";
        if (row.get("xp") instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                String key = id(entry.getKey());
                Profession profession = Profession.parse(key);
                if (profession == null) {
                    continue;
                }
                long value = number(entry.getValue());
                if (value > 0) {
                    player.xp.put(profession.id(), (int) Math.min(MAX_XP, value));
                }
            }
        }
        if (row.get("daily") instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                Profession profession = Profession.parse(id(entry.getKey()));
                if (profession == null || !(entry.getValue() instanceof Map<?, ?> dailyRow)) {
                    continue;
                }
                Daily daily = new Daily();
                daily.date = dailyRow.get("date") == null ? "" : dailyRow.get("date").toString();
                daily.amount = (int) Math.clamp(number(dailyRow.get("amount")), 0L, MAX_XP);
                if (!daily.date.isBlank() && daily.amount > 0) {
                    player.daily.put(profession.id(), daily);
                }
            }
        }
        if (row.get("mastery") instanceof Map<?, ?> values) {
            for (Profession profession : Profession.values()) {
                ProfessionMastery choice = ProfessionMastery.parse(id(values.get(profession.id())));
                if (choice != null && choice.profession() == profession) player.mastery.put(profession.id(), choice.id());
            }
        }
        readTimes(row.get("mastery-changed"), player.masteryChanged);
        readTimes(row.get("work-until"), player.workUntil);
        if(row.get("practice") instanceof Map<?,?> all)for(Profession profession:Profession.values()){
            if(all.get(profession.id()) instanceof Map<?,?> counts){
                var values=new LinkedHashMap<String,Integer>();
                if(counts.size()>512)throw new IllegalArgumentException("practice capacity");
                for(var entry:counts.entrySet()){
                    String key=entry.getKey().toString();long n=number(entry.getValue());
                    if(!key.matches("[a-z0-9_:.-]{1,100}")||n<0||n>MAX_XP)throw new IllegalArgumentException("practice row");
                    values.put(key,(int)n);
                }
                player.practice.put(profession.id(),values);
            }
        }
        if(row.get("trials") instanceof Map<?,?> all)for(Profession profession:Profession.values()){
            long n=number(all.get(profession.id()));if(n<0||n>15)throw new IllegalArgumentException("trials");
            if(n>0)player.trials.put(profession.id(),(int)n);
        }
        player.workReceipt=row.get("work-receipt") instanceof String text?text:"";
        player.changedAt = Math.max(0L, number(row.get("changed-at")));
        return player;
    }

    private static void readTimes(Object raw, Map<String, Long> target) {
        if (!(raw instanceof Map<?, ?> values)) return;
        for (Profession profession : Profession.values()) {
            long value = number(values.get(profession.id()));
            if (value > 0) target.put(profession.id(), value);
        }
    }

    public synchronized boolean canTrack(UUID id) {
        return id != null && (players.containsKey(id) || players.size() < MAX_PLAYERS);
    }

    public synchronized ProfessionMastery mastery(UUID id, Profession profession) {
        Player p = players.get(id);
        return p == null || profession == null ? null : ProfessionMastery.parse(p.mastery.get(profession.id()));
    }

    public synchronized long masteryChanged(UUID id, Profession profession) {
        Player p = players.get(id);
        return p == null || profession == null ? 0 : p.masteryChanged.getOrDefault(profession.id(), 0L);
    }

    public synchronized long workUntil(UUID id, Profession profession) {
        Player p = players.get(id);
        return p == null || profession == null ? 0 : p.workUntil.getOrDefault(profession.id(), 0L);
    }

    public synchronized boolean setMastery(UUID id, ProfessionMastery choice, long now) {
        if (!canTrack(id) || choice == null) return false;
        Player p = of(id);
        p.mastery.put(choice.profession().id(), choice.id());
        p.masteryChanged.put(choice.profession().id(), Math.max(0, now));
        dirty = true;
        return true;
    }

    public synchronized boolean setWorkUntil(UUID id, Profession profession, long until) {
        if (!canTrack(id) || profession == null) return false;
        of(id).workUntil.put(profession.id(), Math.max(0, until));
        dirty = true;
        return true;
    }

    public synchronized Map<String,Integer> practice(UUID id,Profession profession){
        return Map.copyOf(of(id).practice.getOrDefault(profession.id(),Map.of()));
    }
    public synchronized void practice(UUID id,Profession profession,String source,int units){
        if(!canTrack(id)||units<=0||!source.matches("[a-z0-9_:.-]{1,100}"))return;
        var map=of(id).practice.computeIfAbsent(profession.id(),k->new LinkedHashMap<>());
        if(map.size()>=512&&!map.containsKey(source))return;
        map.put(source,(int)Math.min(MAX_XP,(long)map.getOrDefault(source,0)+units));dirty=true;
    }
    public synchronized int trials(UUID id,Profession profession){return of(id).trials.getOrDefault(profession.id(),0);}
    public synchronized boolean claimTrial(UUID id,Profession profession,int index){
        if(index<0||index>3||!canTrack(id))return false;
        int mask=trials(id,profession),bit=1<<index;if((mask&bit)!=0)return false;
        of(id).trials.put(profession.id(),mask|bit);dirty=true;return true;
    }
    /** Идемпотентное завершение мастерской после сохранения playerdata. */
    public synchronized void completeWork(UUID id,Profession profession,long until,String receipt){
        Player p=of(id);if(p.workReceipt.equals(receipt))return;
        p.workUntil.put(profession.id(),Math.max(until,p.workUntil.getOrDefault(profession.id(),0L)));
        practice(id,profession,"work",1);p.workReceipt=receipt;dirty=true;
    }
    public synchronized String receipt(UUID id){return of(id).workReceipt;}

    /* --------------------------------- доступ --------------------------------- */

    /** Данные игрока; создаются при первом обращении. */
    public synchronized Player of(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id игрока не может быть null");
        }
        Player player = players.get(id);
        if (player == null) {
            if (players.size() >= MAX_PLAYERS) {
                return new Player(id, "");
            }
            player = new Player(id, "");
            players.put(id, player);
        }
        return player;
    }

    /** Запоминает актуальный ник: по нему удобно искать в админских командах. */
    public synchronized void rename(UUID id, String name) {
        if (id == null || name == null || name.isBlank()) {
            return;
        }
        Player player = of(id);
        if (!name.equals(player.name)) {
            player.name = name;
            dirty = true;
        }
    }

    public synchronized String name(UUID id) {
        return of(id).name;
    }

    /** Ид выбранной профессии или {@code ""}. */
    public synchronized String activeId(UUID id) {
        return of(id).active;
    }

    /** Выбранная профессия или {@code null}. */
    public synchronized Profession active(UUID id) {
        return Profession.parse(of(id).active);
    }

    public synchronized long changedAt(UUID id) {
        return of(id).changedAt;
    }

    /**
     * Записывает выбор профессии.
     *
     * <p>Опыт прежней профессии остаётся: игрок может вернуться и продолжить.
     * Сбрасывать его значило бы наказывать за интерес к другому ремеслу.</p>
     *
     * @return {@code false}, если выбор не изменился
     */
    public synchronized boolean setActive(UUID id, String professionId, long now) {
        if (!Profession.exists(professionId)) {
            return false;
        }
        Player player = of(id);
        String value = professionId.trim().toLowerCase(Locale.ROOT);
        if (player.active.equals(value)) {
            return false;
        }
        player.active = value;
        player.changedAt = Math.max(0L, now);
        dirty = true;
        return true;
    }

    /** Забывает выбор, не трогая опыт (админская правка). */
    public synchronized boolean clearActive(UUID id) {
        Player player = of(id);
        if (player.active.isEmpty() && player.changedAt == 0) {
            return false;
        }
        player.active = "";
        player.changedAt = 0L;
        dirty = true;
        return true;
    }

    /** Накопленный опыт профессии. */
    public synchronized int xp(UUID id, Profession profession) {
        if (profession == null) {
            return 0;
        }
        return of(id).xp.getOrDefault(profession.id(), 0);
    }

    /** Сколько всего опыта по всем профессиям — для справки. */
    public synchronized int totalXp(UUID id) {
        int total = 0;
        for (int value : of(id).xp.values()) {
            total += value;
        }
        return total;
    }

    /** Добавляет опыт с потолком {@link #MAX_XP}; возвращает новое значение. */
    public synchronized int addXp(UUID id, Profession profession, int amount) {
        if (profession == null || amount <= 0) {
            return xp(id, profession);
        }
        Player player = of(id);
        int before = player.xp.getOrDefault(profession.id(), 0);
        int after = (int) Math.clamp((long) before + amount, 0L, MAX_XP);
        if (after != before) {
            player.xp.put(profession.id(), after);
            dirty = true;
        }
        return after;
    }

    /** Дата суточного счётчика или {@code ""}. */
    public synchronized String dailyDate(UUID id, Profession profession) {
        if (profession == null) {
            return "";
        }
        Daily daily = of(id).daily.get(profession.id());
        return daily == null ? "" : daily.date;
    }

    /** Израсходованный за сутки опыт профессии. */
    public synchronized int dailyAmount(UUID id, Profession profession) {
        if (profession == null) {
            return 0;
        }
        Daily daily = of(id).daily.get(profession.id());
        return daily == null ? 0 : daily.amount;
    }

    /** Записывает суточный счётчик (дата + израсходовано). */
    public synchronized void setDaily(UUID id, Profession profession, String date, int amount) {
        if (profession == null) {
            return;
        }
        Daily daily = of(id).daily.computeIfAbsent(profession.id(), key -> new Daily());
        String value = date == null ? "" : date;
        int safe = (int) Math.clamp(amount, 0, MAX_XP);
        if (!value.equals(daily.date) || safe != daily.amount) {
            daily.date = value;
            daily.amount = safe;
            dirty = true;
        }
    }

    /**
     * Полный сброс игрока: профессия, опыт и суточные счётчики.
     *
     * @return {@code false}, если сбрасывать нечего — админ увидит это в ответе,
     *         а файл не будет помечен изменённым зря
     */
    public synchronized boolean reset(UUID id) {
        Player player = players.get(id);
        if (player == null) {
            return false;
        }
        if (player.active.isEmpty() && player.xp.isEmpty() && player.daily.isEmpty()
                && player.changedAt == 0L && player.mastery.isEmpty() && player.workUntil.isEmpty() && player.masteryChanged.isEmpty()) {
            return false;
        }
        player.active = "";
        player.xp.clear();
        player.daily.clear();
        player.mastery.clear();
        player.masteryChanged.clear();
        player.workUntil.clear();
        player.changedAt = 0L;
        dirty = true;
        return true;
    }

    public synchronized int size() {
        return players.size();
    }

    /** Ищет игрока по сохранённому нику (без учёта регистра). */
    public synchronized UUID byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Player player : players.values()) {
            if (name.equalsIgnoreCase(player.name)) {
                return player.id;
            }
        }
        return null;
    }

    /** Сколько игроков выбрали эту профессию — для сводки админа и топа. */
    public synchronized int countOf(Profession profession) {
        if (profession == null) {
            return 0;
        }
        int count = 0;
        for (Player player : players.values()) {
            if (profession.id().equals(player.active)) {
                count++;
            }
        }
        return count;
    }

    /** Суммарный уровень всех игроков по профессии — для «среднего» в топе. */
    public synchronized long levelSum(Profession profession) {
        if (profession == null) {
            return 0L;
        }
        long sum = 0L;
        for (Player player : players.values()) {
            int xp = player.xp.getOrDefault(profession.id(), 0);
            if (xp > 0) {
                sum += ProfessionMath.levelFor(xp,ProfessionMath.PROGRESSION_CAP);
            }
        }
        return sum;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /* -------------------------------- запись ---------------------------------- */

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        Map<String, Object> rows = new LinkedHashMap<>();
        for (Player player : players.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", player.name);
            if(!player.practice.isEmpty())row.put("practice",player.practice);
            if(!player.trials.isEmpty())row.put("trials",player.trials);
            if(!player.workReceipt.isEmpty())row.put("work-receipt",player.workReceipt);
            if (!player.mastery.isEmpty()) row.put("mastery", new LinkedHashMap<>(player.mastery));
            if (!player.masteryChanged.isEmpty()) row.put("mastery-changed", new LinkedHashMap<>(player.masteryChanged));
            if (!player.workUntil.isEmpty()) row.put("work-until", new LinkedHashMap<>(player.workUntil));
            if (!player.active.isEmpty()) {
                row.put("active", player.active);
            }
            if (!player.xp.isEmpty()) {
                Map<String, Object> xp = new LinkedHashMap<>(player.xp);
                row.put("xp", xp);
            }
            if (!player.daily.isEmpty()) {
                Map<String, Object> daily = new LinkedHashMap<>();
                for (Map.Entry<String, Daily> entry : player.daily.entrySet()) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("date", entry.getValue().date);
                    value.put("amount", entry.getValue().amount);
                    daily.put(entry.getKey(), value);
                }
                row.put("daily", daily);
            }
            if (player.changedAt > 0) {
                row.put("changed-at", player.changedAt);
            }
            rows.put(player.id.toString(), row);
        }
        data.put("players", rows);
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл, только если были изменения. */
    public void saveNow(){synchronized(ioLock){String json;synchronized(this){if(!dirty||!healthy)return;json=snapshotJson();}write(json);}}
    public void flush(){flushDurably();}
    public boolean flushDurably(){synchronized(ioLock){if(!healthy())return false;return write(snapshotJson());}}
    private boolean write(String json){
        boolean saved=SafeJsonWriter.write(file,json,logger,"[CHRDK_Professions]");
        if(!saved)synchronized(this){dirty=true;}
        return saved;
    }

    /* -------------------------------- помощники ------------------------------- */

    private static String id(Object raw) {
        return raw == null ? "" : raw.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    private static long number(Object raw) {
        return raw instanceof Number ? new java.math.BigDecimal(raw.toString()).longValueExact() : 0L;
    }
}
