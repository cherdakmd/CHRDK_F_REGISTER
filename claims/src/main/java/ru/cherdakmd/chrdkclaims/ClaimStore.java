package ru.cherdakmd.chrdkclaims;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Данные приватов (claims-data.json): чанки по мирам (запись плоским списком),
 * казна игроков, отложенные вести (долги/утраты) и статистика. Мутации в памяти,
 * периодический snapshot + async-запись, как в остальных аддонах.
 */
public final class ClaimStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    /** world → (chunkKey → Claim). */
    private final Map<String, Map<Long, Claim>> claims = new HashMap<>();
    private final Map<String, Integer> treasury = new LinkedHashMap<>();
    private final Map<String, List<String>> notices = new LinkedHashMap<>();

    private long chunksClaimedTotal;
    private long diamondsSpent;
    private long taxBurned;
    private boolean dirty;

    public ClaimStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("claims-data.json");
        this.logger = logger;
    }

    // ---------------------------------------------------------------- io --

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            claims.clear();
            if (data.get("claims") instanceof List<?> list) {
                for (Object entry : list) {
                    if (!(entry instanceof Map<?, ?> map)) {
                        continue;
                    }
                    String world = str(map.get("w"));
                    int x = (int) longOf(map.get("x"));
                    int z = (int) longOf(map.get("z"));
                    String owner = str(map.get("o"));
                    if (world == null || owner == null) {
                        continue;
                    }
                    Claim claim = new Claim(world, x, z, owner, longOf(map.get("p")));
                    claim.debtDays = (int) longOf(map.get("d"));
                    claim.salePrice = (int) longOf(map.get("s"));
                    if (map.get("m") instanceof List<?> members) {
                        for (Object member : members) {
                            if (member != null) {
                                claim.members.add(String.valueOf(member));
                            }
                        }
                    }
                    claims.computeIfAbsent(world, k -> new HashMap<>()).put(claim.key(), claim);
                }
            }
            treasury.clear();
            if (data.get("treasury") instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    treasury.put(String.valueOf(entry.getKey()), (int) longOf(entry.getValue()));
                }
            }
            notices.clear();
            if (data.get("notices") instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getValue() instanceof List<?> list) {
                        List<String> lines = new ArrayList<>();
                        for (Object line : list) {
                            lines.add(String.valueOf(line));
                        }
                        notices.put(String.valueOf(entry.getKey()), lines);
                    }
                }
            }
            if (data.get("stats") instanceof Map<?, ?> stats) {
                chunksClaimedTotal = longOf(stats.get("chunksClaimedTotal"));
                diamondsSpent = longOf(stats.get("diamondsSpent"));
                taxBurned = longOf(stats.get("taxBurned"));
            }
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось прочитать claims-data.json: "
                    + exception.getMessage(), exception);
        }
    }

    public synchronized void save() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> claimsList = new ArrayList<>();
        for (Map<Long, Claim> world : claims.values()) {
            for (Claim claim : world.values()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("w", claim.world);
                entry.put("x", claim.x);
                entry.put("z", claim.z);
                entry.put("o", claim.owner);
                entry.put("p", claim.paidUntil);
                entry.put("d", claim.debtDays);
                entry.put("s", claim.salePrice);
                if (!claim.members.isEmpty()) {
                    entry.put("m", new ArrayList<>(claim.members));
                }
                claimsList.add(entry);
            }
        }
        data.put("claims", claimsList);
        data.put("treasury", treasury);
        if (!notices.isEmpty()) {
            data.put("notices", notices);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("chunksClaimedTotal", chunksClaimedTotal);
        stats.put("diamondsSpent", diamondsSpent);
        stats.put("taxBurned", taxBurned);
        data.put("stats", stats);

        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException exception) {
            logger.log(Level.WARNING, "[CHRDK_Claims] Не удалось записать claims-data.json: "
                    + exception.getMessage(), exception);
        }
    }

    public void saveAsync(org.bukkit.plugin.Plugin plugin) {
        if (!dirty) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    public void markDirty() {
        dirty = true;
    }

    // ----------------------------------------------------------- claims --

    public synchronized Claim claimAt(String world, int chunkX, int chunkZ) {
        Map<Long, Claim> inWorld = claims.get(world);
        return inWorld == null ? null : inWorld.get(ClaimMath.chunkKey(chunkX, chunkZ));
    }

    public synchronized void add(Claim claim) {
        claims.computeIfAbsent(claim.world, k -> new HashMap<>()).put(claim.key(), claim);
        chunksClaimedTotal++;
        dirty = true;
    }

    public synchronized void remove(Claim claim) {
        Map<Long, Claim> world = claims.get(claim.world);
        if (world != null) {
            world.remove(claim.key());
            if (world.isEmpty()) {
                claims.remove(claim.world);
            }
        }
        dirty = true;
    }

    public synchronized List<Claim> ofOwner(String owner) {
        List<Claim> result = new ArrayList<>();
        for (Map<Long, Claim> world : claims.values()) {
            for (Claim claim : world.values()) {
                if (claim.isOwner(owner)) {
                    result.add(claim);
                }
            }
        }
        return result;
    }

    public synchronized List<Claim> all() {
        List<Claim> result = new ArrayList<>();
        for (Map<Long, Claim> world : claims.values()) {
            result.addAll(world.values());
        }
        return result;
    }

    public synchronized int count(String owner) {
        int total = 0;
        for (Map<Long, Claim> world : claims.values()) {
            for (Claim claim : world.values()) {
                if (claim.isOwner(owner)) {
                    total++;
                }
            }
        }
        return total;
    }

    // ---------------------------------------------------------- treasury --

    public synchronized int treasuryOf(String name) {
        return treasury.getOrDefault(name, 0);
    }

    /** @return новый баланс. */
    public synchronized int addTreasury(String name, int amount) {
        int balance = treasuryOf(name) + amount;
        treasury.put(name, balance);
        dirty = true;
        return balance;
    }

    public synchronized boolean takeTreasury(String name, int amount) {
        int balance = treasuryOf(name);
        if (balance < amount) {
            return false;
        }
        treasury.put(name, balance - amount);
        dirty = true;
        return true;
    }

    // ----------------------------------------------------------- notices --

    public synchronized void addNotice(String name, String text) {
        notices.computeIfAbsent(name, k -> new ArrayList<>()).add(text);
        dirty = true;
    }

    /** Забирает (и очищает) вести игрока. */
    public synchronized List<String> pollNotices(String name) {
        List<String> lines = notices.remove(name);
        if (lines == null) {
            return List.of();
        }
        dirty = true;
        return lines;
    }

    // ------------------------------------------------------------- stats --

    public synchronized void addDiamondsSpent(long amount) {
        diamondsSpent += amount;
        dirty = true;
    }

    public synchronized void addTaxBurned(long amount) {
        taxBurned += amount;
        dirty = true;
    }

    public synchronized long chunksClaimedTotal() {
        return chunksClaimedTotal;
    }

    public synchronized long taxBurned() {
        return taxBurned;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
