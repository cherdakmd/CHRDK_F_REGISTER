package ru.cherdakmd.chrdkgoals;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Данные целей (goals-data.json): активная волна (ид, объёмы, прогресс, вклады, флаг
 * завершения), дедлайн волны, незабранные награды игроков и простая статистика.
 * Паттерн — как у остальных аддонов: мутации в памяти, периодический snapshot + async-запись.
 */
public final class GoalStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final List<GoalState> goals = new ArrayList<>();
    private final Map<String, Integer> pendingRewards = new LinkedHashMap<>();
    private long rotationEndsAt;
    private int rotationsDone;
    private long lifetimeEarned;
    private boolean dirty;

    public GoalStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("goals-data.json");
        this.logger = logger;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null) {
                return;
            }
            rotationEndsAt = longOf(data.get("rotationEndsAt"));
            rotationsDone = (int) longOf(data.get("rotationsDone"));
            lifetimeEarned = longOf(data.get("lifetimeEarned"));
            goals.clear();
            if (data.get("goals") instanceof List<?> list) {
                for (Object row : list) {
                    if (!(row instanceof Map<?, ?> map)) {
                        continue;
                    }
                    GoalState state = new GoalState(
                            strOf(map.get("id")),
                            (int) Math.max(1, longOf(map.get("target"))),
                            (int) Math.max(0, longOf(map.get("rewardPool"))));
                    state.progress = (int) Math.max(0, longOf(map.get("progress")));
                    state.done = Boolean.TRUE.equals(map.get("done"));
                    if (map.get("contributions") instanceof Map<?, ?> contribs) {
                        for (Map.Entry<?, ?> entry : contribs.entrySet()) {
                            state.contributions.put(String.valueOf(entry.getKey()),
                                    (int) Math.max(0, longOf(entry.getValue())));
                        }
                    }
                    goals.add(state);
                }
            }
            pendingRewards.clear();
            if (data.get("pendingRewards") instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    int value = (int) Math.max(0, longOf(entry.getValue()));
                    if (value > 0) {
                        pendingRewards.put(String.valueOf(entry.getKey()), value);
                    }
                }
            }
        } catch (IOException exception) {
            logger.log(Level.WARNING, "[CHRDK_CommonGoals] Не удалось прочитать " + file, exception);
        }
    }

    public synchronized void save() {
        if (!dirty) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rotationEndsAt", rotationEndsAt);
        data.put("rotationsDone", rotationsDone);
        data.put("lifetimeEarned", lifetimeEarned);
        List<Map<String, Object>> goalRows = new ArrayList<>();
        for (GoalState goal : goals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", goal.id);
            row.put("target", goal.target);
            row.put("rewardPool", goal.rewardPool);
            row.put("progress", goal.progress);
            row.put("done", goal.done);
            row.put("contributions", new LinkedHashMap<>(goal.contributions));
            goalRows.add(row);
        }
        data.put("goals", goalRows);
        data.put("pendingRewards", new LinkedHashMap<>(pendingRewards));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.log(Level.WARNING, "[CHRDK_CommonGoals] Не удалось записать " + file, exception);
        }
        dirty = false;
    }

    public void saveAsync(CommonGoalsPlugin plugin) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    public synchronized void markDirty() {
        dirty = true;
    }

    public synchronized List<GoalState> goals() {
        return List.copyOf(goals);
    }

    public synchronized Optional<GoalState> goal(String id) {
        return goals.stream().filter(g -> g.id.equals(id)).findFirst();
    }

    public synchronized void replaceGoals(List<GoalState> newGoals, long newEndsAt) {
        goals.clear();
        goals.addAll(newGoals);
        rotationEndsAt = newEndsAt;
        rotationsDone++;
        dirty = true;
    }

    public synchronized long rotationEndsAt() {
        return rotationEndsAt;
    }

    public synchronized int rotationsDone() {
        return rotationsDone;
    }

    public synchronized boolean needRotation(long nowMillis) {
        return goals.isEmpty() || rotationEndsAt <= nowMillis;
    }

    /* ------------------------------ прогресс ------------------------------ */

    /** Добавляет вклад, true = цель завершилась на этом вызове. */
    public synchronized boolean addProgress(String goalId, String player, int amount) {
        Optional<GoalState> goal = goal(goalId);
        if (goal.isEmpty() || goal.get().done) {
            return false;
        }
        GoalState state = goal.get();
        state.progress += Math.max(0, amount);
        if (player != null) {
            String key = player.toLowerCase(java.util.Locale.ROOT);
            state.contributions.merge(key, Math.max(0, amount), Integer::sum);
        }
        if (state.progress >= state.target) {
            state.done = true;
            state.progress = state.target;
            dirty = true;
            return true;
        }
        dirty = true;
        return false;
    }

    public synchronized void setProgress(String goalId, int value) {
        goal(goalId).ifPresent(state -> {
            state.progress = Math.max(0, Math.min(value, state.target));
            state.done = state.progress >= state.target;
            dirty = true;
        });
    }

    /* ------------------------------ награды ------------------------------ */

    public synchronized void addPendingReward(String player, int amounts) {
        if (amounts <= 0) {
            return;
        }
        pendingRewards.merge(player.toLowerCase(java.util.Locale.ROOT), amounts, Integer::sum);
        lifetimeEarned += amounts;
        dirty = true;
    }

    public synchronized int pendingRewardOf(String player) {
        return pendingRewards.getOrDefault(player.toLowerCase(java.util.Locale.ROOT), 0);
    }

    /** Атомарно снимает накопленную награду игрока, 0 если пусто. */
    public synchronized int claim(String player) {
        String key = player.toLowerCase(java.util.Locale.ROOT);
        Integer removed = pendingRewards.remove(key);
        if (removed == null || removed <= 0) {
            return 0;
        }
        dirty = true;
        return removed;
    }

    public synchronized Map<String, Integer> pendingView() {
        return new LinkedHashMap<>(pendingRewards);
    }

    public synchronized long lifetimeEarned() {
        return lifetimeEarned;
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String strOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
