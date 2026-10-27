package ru.cherdakmd.chrdkskills;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Выносливость и таймеры отдельно от XP. Восстановление — только явным онлайн-пульсом. */
final class SkillAbilityStore {
    static final int MAX_PLAYERS = 200_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Logger logger;
    private final Object writeLock = new Object();
    private final Map<UUID, State> players = new LinkedHashMap<>();
    private boolean dirty, available = true;
    private static final class State {
        int energy = SkillAbility.MAX_ENERGY;
        final Map<String, Long> cooldowns = new LinkedHashMap<>();
    }
    SkillAbilityStore(Path folder, Logger logger) { file = folder.resolve("abilities.json"); this.logger = logger; }
    synchronized boolean available() { return available; }
    synchronized int energy(UUID id) {
        State state = players.get(id);
        return !available ? 0 : state == null ? SkillAbility.MAX_ENERGY : state.energy;
    }
    synchronized long cooldownLeft(UUID id, SkillAbility ability, long now) {
        State state = players.get(id);
        if (state == null || ability == null) return 0;
        long until = state.cooldowns.getOrDefault(ability.id(), 0L);
        return until <= now ? 0 : (until - Math.max(0L, now) - 1) / 1000 + 1;
    }
    /** Атомарное списание. Неуспешный запрос не создаёт запись и не меняет таймер. */
    synchronized String activate(UUID id, SkillAbility ability, long now) {
        if (!available || id == null || ability == null || now < 0 || now > Long.MAX_VALUE - 600_000)
            return "ability-unavailable";
        if (!players.containsKey(id) && players.size() >= MAX_PLAYERS) return "ability-unavailable";
        if (cooldownLeft(id, ability, now) > 0) return "ability-cooldown";
        if (energy(id) < ability.cost()) return "ability-energy-low";
        State state = players.computeIfAbsent(id, ignored -> new State());
        state.energy -= ability.cost();
        state.cooldowns.put(ability.id(), now + ability.cooldown() * 1000L);
        dirty = true;
        return "";
    }
    synchronized void regenerate(UUID id) {
        State state = players.get(id);
        if (!available || state == null || state.energy >= SkillAbility.MAX_ENERGY) return;
        state.energy = Math.min(SkillAbility.MAX_ENERGY, state.energy + SkillAbility.REGEN_PER_SECOND);
        dirty = true;
    }
    synchronized void load() {
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("version").getAsBigDecimal().intValueExact() != 1 || !root.get("players").isJsonObject()) throw new IllegalArgumentException("unsupported abilities schema");
            Map<UUID, State> loaded = new LinkedHashMap<>();
            for (var entry : root.getAsJsonObject("players").entrySet()) {
                if (loaded.size() >= MAX_PLAYERS) throw new IllegalArgumentException("too many ability players");
                UUID id = UUID.fromString(entry.getKey());
                JsonObject row = entry.getValue().getAsJsonObject();
                State state = new State();
                // Повреждённое состояние не должно становиться бесплатным полным запасом.
                int energy = row.get("energy").getAsBigDecimal().intValueExact();
                if (energy < 0 || energy > SkillAbility.MAX_ENERGY) throw new IllegalArgumentException("bad energy");
                state.energy = energy;
                if (!row.has("cooldowns") || !row.get("cooldowns").isJsonObject()) throw new IllegalArgumentException("missing cooldowns");
                if (row.has("cooldowns")) {
                    for (var cooldown : row.getAsJsonObject("cooldowns").entrySet()) {
                        SkillAbility ability = SkillAbility.of(SkillType.byId(cooldown.getKey()));
                        long until = cooldown.getValue().getAsBigDecimal().longValueExact();
                        if (ability == null || until < 0) throw new IllegalArgumentException("bad ability cooldown");
                        state.cooldowns.put(ability.id(), until);
                    }
                }
                if (loaded.put(id, state) != null) throw new IllegalArgumentException("duplicate ability player");
            }
            players.clear(); players.putAll(loaded);
        } catch (Exception exception) {
            available = false;
            if (logger != null) logger.severe("Приёмы навыков заблокированы: не удалось прочитать abilities.json. "
                    + "Файл не перезаписывается; восстановите резервную копию. " + exception.getMessage());
        }
    }
    private synchronized String snapshot() {
        JsonObject root = new JsonObject(), rows = new JsonObject(); root.addProperty("version", 1);
        for (var entry : players.entrySet()) {
            JsonObject row = new JsonObject(); row.addProperty("energy", entry.getValue().energy);
            row.add("cooldowns", GSON.toJsonTree(entry.getValue().cooldowns)); rows.add(entry.getKey().toString(), row);
        }
        root.add("players", rows); dirty = false; return GSON.toJson(root);
    }
    void saveNow() {
        // Сериализуем СНИМОК вместе с записью: старое async-сохранение не может
        // завершиться после свежего flush и вернуть потраченную выносливость.
        synchronized (writeLock) {
            String json;
            synchronized (this) { if (!available || !dirty) return; json = snapshot(); }
            if (!SafeJsonWriter.write(file, json, logger, "[CHRDK_Skills abilities]")) synchronized (this) { dirty = true; }
        }
    }
    void flush() { saveNow(); }
}
