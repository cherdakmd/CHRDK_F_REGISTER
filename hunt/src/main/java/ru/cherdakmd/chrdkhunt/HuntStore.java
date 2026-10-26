package ru.cherdakmd.chrdkhunt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Данные охоты ({@code hunt.json}): кошелёк, осколки, ключи, трофеи и суточные
 * счётчики.
 *
 * <p>Монеты и осколки — не предметы, а числа в профиле: их нельзя потерять при
 * смерти, нельзя продублировать перекладыванием и нельзя продать на аукционе.
 * Поэтому кошелёк живёт в одном месте и меняется только методами этого класса.
 * Сутки считаются по UTC, чтобы «сегодня» не зависело от часового пояса.</p>
 *
 * <p>Паттерн записи общий для репозитория: мутации на главном потоке,
 * {@link #snapshotJson()} и асинхронная запись {@link #saveNow()}. Класс чистый
 * (Gson + NIO + UUID) и проверяется юнит-тестами без Bukkit.</p>
 */
public final class HuntStore {

    /** Сутки в миллисекундах. */
    public static final long DAY_MILLIS = 86_400_000L;
    /** Потолок кошелька: защита от переполнения и опечаток. */
    public static final long MAX_COINS = 10_000_000L;
    /** Потолок осколков одного семейства. */
    public static final long MAX_SHARDS = 1_000_000L;
    /** Потолок ключей одного семейства. */
    public static final long MAX_KEYS = 100_000L;

    /** Профиль охотника. Мутируется только методами хранилища. */
    public static final class PlayerData {
        private String name = "";
        private long coins;
        private long earned;
        private long killsTotal;
        private long day;
        private long coinsToday;
        private long exchangeDay;
        private long exchangeToday;
        private long purchaseDay;
        private long lastKill;
        private final Map<String, Long> shards = new LinkedHashMap<>();
        private final Map<String, Long> keys = new LinkedHashMap<>();
        private final Map<String, Long> kills = new LinkedHashMap<>();
        private final Map<String, Integer> purchases = new LinkedHashMap<>();
        private final Set<String> trophies = new LinkedHashSet<>();
        private final Map<String, Long> bestiary = new LinkedHashMap<>();
        private final Set<String> bestiaryAwards = new LinkedHashSet<>();

        private PlayerData(String name) {
            this.name = name == null ? "" : name;
        }

        public String name() {
            return name;
        }

        public long coins() {
            return coins;
        }

        public long earned() {
            return earned;
        }

        public long killsTotal() {
            return killsTotal;
        }

        public long lastKill() {
            return lastKill;
        }

        public int purchaseCount(String offerId) {
            Integer value = purchases.get(offerId);
            return value == null ? 0 : value;
        }

        /** Осколки семейства (0, если их нет). */
        public long shards(String familyId) {
            Long value = shards.get(HuntFamily.normalize(familyId));
            return value == null ? 0L : value;
        }

        /** Ключи семейства (0, если их нет). */
        public long keys(String familyId) {
            Long value = keys.get(HuntFamily.normalize(familyId));
            return value == null ? 0L : value;
        }

        /** Убийства мобов семейства. */
        public long kills(String familyId) {
            Long value = kills.get(HuntFamily.normalize(familyId));
            return value == null ? 0L : value;
        }

        /** Все осколки по семействам — для статистики. */
        public Map<String, Long> shardMap() {
            return Map.copyOf(shards);
        }

        /** Все ключи по семействам. */
        public Map<String, Long> keyMap() {
            return Map.copyOf(keys);
        }

        /** Полученные трофеи. */
        public Set<String> trophies() {
            return Set.copyOf(trophies);
        }

        /** Журнал бестиария: ключ вида → убийства. */
        public Map<String, Long> bestiary() {
            return Map.copyOf(bestiary);
        }

        /** Разделы, за которые награда уже выдана. */
        public Set<String> bestiaryAwards() {
            return Set.copyOf(bestiaryAwards);
        }
    }

    /** Строка рейтинга: без Bukkit-типов, чтобы её можно было показать где угодно. */
    public record Row(UUID id, String name, long earned, long kills, int trophies) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();
    private static final Comparator<Row> TOP_ORDER =
            Comparator.comparingLong(Row::earned).reversed()
                    .thenComparing(Comparator.comparingLong(Row::kills).reversed())
                    .thenComparing(Row::name);

    private final Path file;
    private final Logger logger;
    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private boolean dirty;

    public HuntStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("hunt.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /* --------------------------------- чтение --------------------------------- */

    /** Читает файл; битые строки пропускаются с предупреждением. */
    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            int skipped = 0;
            if (data.get("players") instanceof Map<?, ?> rows) {
                for (Map.Entry<?, ?> entry : rows.entrySet()) {
                    UUID id = parseUuid(entry.getKey());
                    if (id == null || !(entry.getValue() instanceof Map<?, ?> row)) {
                        skipped++;
                        continue;
                    }
                    PlayerData player = new PlayerData(string(row.get("name")));
                    player.coins = clampLong(row.get("coins"), MAX_COINS);
                    player.earned = clampLong(row.get("earned"), MAX_COINS);
                    player.killsTotal = clampLong(row.get("kills-total"), Long.MAX_VALUE);
                    player.day = clampLong(row.get("day"), Long.MAX_VALUE);
                    player.coinsToday = clampLong(row.get("coins-today"), MAX_COINS);
                    player.exchangeDay = clampLong(row.get("exchange-day"), Long.MAX_VALUE);
                    player.exchangeToday = clampLong(row.get("exchange-today"), 1_000L);
                    player.purchaseDay = clampLong(row.get("purchase-day"), Long.MAX_VALUE);
                    player.lastKill = clampLong(row.get("last-kill"), Long.MAX_VALUE);
                    readCounters(row.get("shards"), player.shards, MAX_SHARDS);
                    readCounters(row.get("keys"), player.keys, MAX_KEYS);
                    readCounters(row.get("kills"), player.kills, Long.MAX_VALUE);
                    readCounters(row.get("bestiary"), player.bestiary, Long.MAX_VALUE);
                    readIds(row.get("bestiary-awards"), player.bestiaryAwards);
                    if (row.get("purchases") instanceof Map<?, ?> purchases) {
                        for (Map.Entry<?, ?> purchase : purchases.entrySet()) {
                            String key = string(purchase.getKey()).trim().toLowerCase(java.util.Locale.ROOT);
                            if (key.isEmpty() || !(purchase.getValue() instanceof Number number)) {
                                continue;
                            }
                            player.purchases.put(key, (int) Math.clamp(number.longValue(), 0L, 1_000L));
                        }
                    }
                    if (row.get("trophies") instanceof List<?> trophies) {
                        for (Object trophy : trophies) {
                            String id2 = string(trophy).trim().toLowerCase(java.util.Locale.ROOT);
                            if (!id2.isEmpty()) {
                                player.trophies.add(id2);
                            }
                        }
                    }
                    players.put(id, player);
                }
            }
            if (skipped > 0 && logger != null) {
                logger.warning("[CHRDK_Hunt] Пропущено записей hunt.json: " + skipped + ".");
            }
            if (logger != null) {
                logger.info("[CHRDK_Hunt] Загружено охотников: " + players.size() + ".");
            }
        } catch (Exception exception) {
            if (logger != null) {
                logger.severe("[CHRDK_Hunt] Не удалось прочитать " + file + ": "
                        + exception.getMessage());
            }
        }
    }

    private static void readCounters(Object raw, Map<String, Long> target, long cap) {
        if (!(raw instanceof Map<?, ?> rows)) {
            return;
        }
        for (Map.Entry<?, ?> entry : rows.entrySet()) {
            String key = HuntFamily.normalize(string(entry.getKey()));
            if (key.isEmpty() || !(entry.getValue() instanceof Number number)) {
                continue;
            }
            long value = Math.clamp(number.longValue(), 0L, cap);
            if (value > 0L) {
                target.put(key, value);
            }
        }
    }

    /** Читает набор идов (разделы бестиария с выданной наградой). */
    private static void readIds(Object raw, Set<String> target) {
        if (!(raw instanceof List<?> rows)) {
            return;
        }
        for (Object value : rows) {
            String id = string(value).trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                target.add(id);
            }
        }
    }

    /* -------------------------------- игроки ---------------------------------- */

    /** Профиль или {@code null}, если игрок ещё не охотился. */
    public synchronized PlayerData player(UUID id) {
        return id == null ? null : players.get(id);
    }

    /** Профиль с созданием; ник обновляется при каждой охоте. */
    public synchronized PlayerData ensure(UUID id, String name) {
        if (id == null) {
            return null;
        }
        PlayerData data = players.get(id);
        if (data == null) {
            data = new PlayerData(name);
            players.put(id, data);
            dirty = true;
            return data;
        }
        if (name != null && !name.isBlank() && !name.equals(data.name)) {
            data.name = name.trim();
            dirty = true;
        }
        return data;
    }

    /** Строка рейтинга по нику без учёта регистра — для офлайн-статистики. */
    public synchronized Row byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            PlayerData data = entry.getValue();
            if (data.name.equalsIgnoreCase(wanted)) {
                return row(entry.getKey(), data);
            }
        }
        return null;
    }

    public synchronized long coins(UUID id) {
        PlayerData data = player(id);
        return data == null ? 0L : data.coins;
    }

    public synchronized long earned(UUID id) {
        PlayerData data = player(id);
        return data == null ? 0L : data.earned;
    }

    public synchronized long shards(UUID id, String familyId) {
        PlayerData data = player(id);
        return data == null ? 0L : data.shards(familyId);
    }

    public synchronized long keys(UUID id, String familyId) {
        PlayerData data = player(id);
        return data == null ? 0L : data.keys(familyId);
    }

    public synchronized boolean hasTrophy(UUID id, String trophyId) {
        PlayerData data = player(id);
        return data != null && data.trophies.contains(HuntFamily.normalize(trophyId));
    }

    /** Сумма осколков всех семейств — для профиля и плейсхолдеров. */
    public synchronized long totalShards(UUID id) {
        PlayerData data = player(id);
        if (data == null) {
            return 0L;
        }
        long total = 0L;
        for (long value : data.shards.values()) {
            total = saturatingAdd(total, value);
        }
        return total;
    }

    /** Сумма ключей всех семейств. */
    public synchronized long totalKeys(UUID id) {
        PlayerData data = player(id);
        if (data == null) {
            return 0L;
        }
        long total = 0L;
        for (long value : data.keys.values()) {
            total = saturatingAdd(total, value);
        }
        return total;
    }

    /* -------------------------------- начисления ------------------------------- */

    /** Начисляет монеты; возвращает, сколько реально легло в кошелёк. */
    public synchronized long addCoins(UUID id, String name, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        PlayerData data = ensure(id, name);
        if (data == null) {
            return 0L;
        }
        long before = data.coins;
        data.coins = clampAdd(data.coins, amount, MAX_COINS);
        data.earned = clampAdd(data.earned, amount, MAX_COINS);
        data.coinsToday = clampAdd(data.coinsToday, amount, MAX_COINS);
        dirty = true;
        return data.coins - before;
    }

    /**
     * Начисляет монеты в обход суточного потолка — только для разовых наград
     * (закрытый раздел бестиария). Стаж (earned) растёт, счётчик «за сегодня»
     * не трогается: иначе награда съедала бы лимит обычной охоты.
     */
    public synchronized long addBonusCoins(UUID id, String name, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        PlayerData data = ensure(id, name);
        if (data == null) {
            return 0L;
        }
        long before = data.coins;
        data.coins = clampAdd(data.coins, amount, MAX_COINS);
        data.earned = clampAdd(data.earned, amount, MAX_COINS);
        dirty = true;
        return data.coins - before;
    }

    /** Списывает монеты; {@code false} — не хватило. */
    public synchronized boolean spendCoins(UUID id, long amount) {
        if (amount <= 0L) {
            return true;
        }
        PlayerData data = player(id);
        if (data == null || data.coins < amount) {
            return false;
        }
        data.coins -= amount;
        dirty = true;
        return true;
    }

    /** Сколько монет игрок заработал за текущие сутки UTC. */
    public synchronized long coinsToday(UUID id, long now) {
        PlayerData data = player(id);
        if (data == null) {
            return 0L;
        }
        return data.day == dayOf(now) ? data.coinsToday : 0L;
    }

    /** Начисляет осколки семейства. */
    public synchronized long addShards(UUID id, String name, String familyId, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        String family = HuntFamily.normalize(familyId);
        if (family.isEmpty()) {
            return 0L;
        }
        PlayerData data = ensure(id, name);
        if (data == null) {
            return 0L;
        }
        long before = data.shards.getOrDefault(family, 0L);
        long after = clampAdd(before, amount, MAX_SHARDS);
        if (after > 0L) {
            data.shards.put(family, after);
        }
        dirty = true;
        return after - before;
    }

    /** Списывает осколки; {@code false} — не хватило. */
    public synchronized boolean spendShards(UUID id, String familyId, long amount) {
        if (amount <= 0L) {
            return true;
        }
        String family = HuntFamily.normalize(familyId);
        PlayerData data = player(id);
        if (data == null) {
            return false;
        }
        long before = data.shards.getOrDefault(family, 0L);
        if (before < amount) {
            return false;
        }
        long after = before - amount;
        if (after <= 0L) {
            data.shards.remove(family);
        } else {
            data.shards.put(family, after);
        }
        dirty = true;
        return true;
    }

    /** Начисляет ключи семейства. */
    public synchronized long addKeys(UUID id, String name, String familyId, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        String family = HuntFamily.normalize(familyId);
        if (family.isEmpty()) {
            return 0L;
        }
        PlayerData data = ensure(id, name);
        if (data == null) {
            return 0L;
        }
        long before = data.keys.getOrDefault(family, 0L);
        long after = clampAdd(before, amount, MAX_KEYS);
        if (after > 0L) {
            data.keys.put(family, after);
        }
        dirty = true;
        return after - before;
    }

    /** Тратит один ключ семейства; {@code false} — ключа нет. */
    public synchronized boolean spendKey(UUID id, String familyId) {
        String family = HuntFamily.normalize(familyId);
        PlayerData data = player(id);
        if (data == null) {
            return false;
        }
        long before = data.keys.getOrDefault(family, 0L);
        if (before <= 0L) {
            return false;
        }
        long after = before - 1L;
        if (after <= 0L) {
            data.keys.remove(family);
        } else {
            data.keys.put(family, after);
        }
        dirty = true;
        return true;
    }

    /** Записывает убийство: счётчики семейства и всего. */
    public synchronized void registerKill(UUID id, String name, String familyId, long now) {
        PlayerData data = ensure(id, name);
        if (data == null) {
            return;
        }
        String family = HuntFamily.normalize(familyId);
        if (!family.isEmpty()) {
            data.kills.merge(family, 1L, (old, added) ->
                    Math.clamp(old + added, 0L, Long.MAX_VALUE));
        }
        data.killsTotal = Math.clamp(data.killsTotal + 1L, 0L, Long.MAX_VALUE);
        data.lastKill = Math.max(0L, now);
        dirty = true;
    }

    /** Убийства мобов семейства. */
    public synchronized long kills(UUID id, String familyId) {
        PlayerData data = player(id);
        return data == null ? 0L : data.kills(familyId);
    }

    /** Всего убийств. */
    public synchronized long killsTotal(UUID id) {
        PlayerData data = player(id);
        return data == null ? 0L : data.killsTotal;
    }

    /** Признаёт текущий день для суточных счётчиков монет. */
    public synchronized void touchDay(UUID id, String name, long now) {
        PlayerData data = ensure(id, name);
        if (data == null) {
            return;
        }
        long day = dayOf(now);
        if (data.day != day) {
            data.day = day;
            data.coinsToday = 0L;
            dirty = true;
        }
    }

    /* --------------------------------- обмен ---------------------------------- */

    /** Сколько алмазов игрок уже обменял за текущие сутки UTC. */
    public synchronized long exchangeToday(UUID id, long now) {
        PlayerData data = player(id);
        if (data == null) {
            return 0L;
        }
        return data.exchangeDay == dayOf(now) ? data.exchangeToday : 0L;
    }

    /** Записывает обмен: сколько алмазов выдано за монеты. */
    public synchronized void recordExchange(UUID id, String name, long now, int diamonds) {
        PlayerData data = ensure(id, name);
        if (data == null || diamonds <= 0) {
            return;
        }
        long day = dayOf(now);
        if (data.exchangeDay != day) {
            data.exchangeDay = day;
            data.exchangeToday = 0L;
        }
        data.exchangeToday = Math.clamp(data.exchangeToday + diamonds, 0L, 1_000L);
        dirty = true;
    }

    /* --------------------------------- лавка ---------------------------------- */

    /** Сколько раз позиция куплена за текущие сутки UTC. */
    public synchronized int purchasedToday(UUID id, String offerId, long now) {
        PlayerData data = player(id);
        if (data == null || offerId == null) {
            return 0;
        }
        if (data.purchaseDay != dayOf(now)) {
            return 0;
        }
        return data.purchaseCount(HuntFamily.normalize(offerId));
    }

    /** Записывает покупку в лавке. */
    public synchronized void recordPurchase(UUID id, String name, String offerId, long now,
                                            int quantity) {
        if (quantity <= 0) {
            return;
        }
        PlayerData data = ensure(id, name);
        if (data == null || offerId == null) {
            return;
        }
        long day = dayOf(now);
        if (data.purchaseDay != day) {
            data.purchaseDay = day;
            data.purchases.clear();
        }
        String key = HuntFamily.normalize(offerId);
        data.purchases.merge(key, quantity, (old, added) ->
                (int) Math.clamp((long) old + added, 0L, 1_000L));
        dirty = true;
    }

    /* --------------------------------- трофеи --------------------------------- */

    /** Добавляет трофей в коллекцию; {@code true} — он новый. */
    public synchronized boolean addTrophy(UUID id, String name, String trophyId) {
        PlayerData data = ensure(id, name);
        if (data == null) {
            return false;
        }
        String key = HuntFamily.normalize(trophyId);
        if (key.isEmpty()) {
            return false;
        }
        boolean added = data.trophies.add(key);
        if (added) {
            dirty = true;
        }
        return added;
    }

    public synchronized int trophyCount(UUID id) {
        PlayerData data = player(id);
        return data == null ? 0 : data.trophies.size();
    }

    /* -------------------------------- бестиарий -------------------------------- */

    /**
     * Записывает убийство вида в бестиарий.
     *
     * @return число убийств этого вида после записи
     */
    public synchronized long recordBestiaryKill(UUID id, String name, String key) {
        String normalized = HuntBestiary.normalize(key);
        if (normalized.isEmpty()) {
            return 0L;
        }
        PlayerData data = ensure(id, name);
        if (data == null) {
            return 0L;
        }
        long value = Math.clamp(data.bestiary.getOrDefault(normalized, 0L) + 1L,
                0L, Long.MAX_VALUE);
        data.bestiary.put(normalized, value);
        dirty = true;
        return value;
    }

    /** Убийства вида ({@code 0}, если вид ещё не открыт). */
    public synchronized long bestiaryKills(UUID id, String key) {
        PlayerData data = player(id);
        return data == null ? 0L : data.bestiary.getOrDefault(
                HuntBestiary.normalize(key), 0L);
    }

    /** Сколько разных видов открыто. */
    public synchronized int bestiarySeen(UUID id) {
        PlayerData data = player(id);
        return data == null ? 0 : data.bestiary.size();
    }

    /** Весь журнал вида: ключ вида → убийства. */
    public synchronized Map<String, Long> bestiaryMap(UUID id) {
        PlayerData data = player(id);
        return data == null ? Map.of() : Map.copyOf(data.bestiary);
    }

    /** Награда за раздел уже выдана. */
    public synchronized boolean hasBestiaryAward(UUID id, String sectionId) {
        PlayerData data = player(id);
        return data != null && data.bestiaryAwards.contains(HuntBestiary.normalize(sectionId));
    }

    /**
     * Запоминает выданную награду раздела.
     *
     * @return {@code true}, если награда выдана впервые
     */
    public synchronized boolean grantBestiaryAward(UUID id, String name, String sectionId) {
        String key = HuntBestiary.normalize(sectionId);
        if (key.isEmpty()) {
            return false;
        }
        PlayerData data = ensure(id, name);
        if (data == null) {
            return false;
        }
        boolean added = data.bestiaryAwards.add(key);
        if (added) {
            dirty = true;
        }
        return added;
    }

    /** Все разделы, за которые награда уже выдана. */
    public synchronized Set<String> bestiaryAwards(UUID id) {
        PlayerData data = player(id);
        return data == null ? Set.of() : Set.copyOf(data.bestiaryAwards);
    }

    /* --------------------------------- рейтинг -------------------------------- */

    /** Топ по заработанным монетам, при равенстве — по убийствам. */
    public synchronized List<Row> top(int limit) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            PlayerData data = entry.getValue();
            if (data.earned <= 0L && data.killsTotal <= 0L) {
                continue;
            }
            rows.add(row(entry.getKey(), data));
        }
        rows.sort(TOP_ORDER);
        int size = Math.max(0, Math.min(limit, rows.size()));
        return List.copyOf(rows.subList(0, size));
    }

    private static Row row(UUID id, PlayerData data) {
        return new Row(id, data.name, data.earned, data.killsTotal, data.trophies.size());
    }

    public synchronized int players() {
        return players.size();
    }

    /** Всего монет заработано всеми игроками — для админской сводки. */
    public synchronized long totalEarned() {
        long total = 0L;
        for (PlayerData data : players.values()) {
            total = saturatingAdd(total, data.earned);
        }
        return total;
    }

    /** Полная очистка профиля админом; {@code true} — запись была. */
    public synchronized boolean reset(UUID id) {
        if (id == null || players.remove(id) == null) {
            return false;
        }
        dirty = true;
        return true;
    }

    /* -------------------------------- запись ---------------------------------- */

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Снимок для асинхронной записи; сбрасывает флаг «грязно». */
    public synchronized String snapshotJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        Map<String, Object> rows = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerData> entry : players.entrySet()) {
            PlayerData player = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", player.name);
            row.put("coins", player.coins);
            row.put("earned", player.earned);
            row.put("kills-total", player.killsTotal);
            row.put("day", player.day);
            row.put("coins-today", player.coinsToday);
            row.put("exchange-day", player.exchangeDay);
            row.put("exchange-today", player.exchangeToday);
            row.put("purchase-day", player.purchaseDay);
            row.put("last-kill", player.lastKill);
            row.put("shards", new LinkedHashMap<>(player.shards));
            row.put("keys", new LinkedHashMap<>(player.keys));
            row.put("kills", new LinkedHashMap<>(player.kills));
            row.put("purchases", new LinkedHashMap<>(player.purchases));
            row.put("trophies", new ArrayList<>(player.trophies));
            row.put("bestiary", new LinkedHashMap<>(player.bestiary));
            row.put("bestiary-awards", new ArrayList<>(player.bestiaryAwards));
            rows.put(entry.getKey().toString(), row);
        }
        data.put("players", rows);
        dirty = false;
        return GSON.toJson(data);
    }

    /** Пишет файл, только если были изменения. */
    public void saveNow() {
        String json;
        synchronized (this) {
            if (!dirty) {
                return;
            }
            json = snapshotJson();
        }
        write(json);
    }

    /** Немедленная запись без проверки флага (выключение сервера, тесты). */
    public void flush() {
        write(snapshotJson());
    }

    private void write(String json) {
        if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Hunt]")) {
            // Диск не принял данные — помечаем состояние изменённым, иначе
            // следующее автосохранение решит, что писать нечего, и прогресс
            // потеряется молча.
            synchronized (this) {
                dirty = true;
            }
        }
    }
    /** Номер суток UTC — так «сегодня» одинаково на любом сервере. */
    public static long dayOf(long now) {
        return Math.max(0L, now) / DAY_MILLIS;
    }

    /** Сколько миллисекунд осталось до конца текущих суток UTC. */
    public static long millisToNextDay(long now) {
        long left = DAY_MILLIS - (Math.max(0L, now) % DAY_MILLIS);
        return left <= 0L ? DAY_MILLIS : left;
    }

    /** Прибавка с потолком, не переполняющая long: {@code amount} может быть огромным. */
    private static long clampAdd(long base, long amount, long cap) {
        if (amount <= 0L || base >= cap) {
            return Math.clamp(base, 0L, cap);
        }
        return amount >= cap - base ? cap : base + amount;
    }

    private static long saturatingAdd(long left, long right) {
        long sum = left + right;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long clampLong(Object value, long cap) {
        return value instanceof Number number ? Math.clamp(number.longValue(), 0L, cap) : 0L;
    }
}
