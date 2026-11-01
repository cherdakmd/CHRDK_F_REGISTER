package ru.cherdakmd.chrdkguilds;

import com.google.gson.*;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

/** Личный выбор, а не платные права: сроки всегда перечитываются из Core. */
final class GuildStyleStore {
    static final long TRANSFER_COOLDOWN = 86_400_000L;
    record Choice(String preset, long changed) {
        Choice { if (preset == null || changed < 0) throw new IllegalArgumentException("choice"); }
    }
    record Row(UUID owner, String name, String guild, long movedAt, Map<String, Choice> choices) {
        Row {
            if (owner == null || name == null || !name.matches("[A-Za-z0-9_]{1,16}") || guild == null
                    || !guild.matches("[A-Za-z0-9_-]{1,64}") || movedAt < 0 || movedAt > Long.MAX_VALUE - TRANSFER_COOLDOWN || choices == null || choices.size() > 3)
                throw new IllegalArgumentException("binding");
            choices.forEach((kind, choice) -> { if (choice == null || !GuildStyleDefs.valid(kind, choice.preset())) throw new IllegalArgumentException("preset"); });
            choices = Map.copyOf(choices);
        }
    }
    enum Result { OK, COOLDOWN, FAILED }
    private record Snapshot(Map<UUID, Row> rows, Map<String, List<Row>> guilds) { }
    private static final Gson GSON = new Gson();
    private final Path file;
    private final Logger logger;
    private final BiPredicate<Path, String> writer;
    private final Object writeLock = new Object();
    private volatile Snapshot state = snapshot(Map.of());
    private volatile boolean healthy = true;

    GuildStyleStore(Path folder, Logger logger) {
        this(folder, logger, (path, json) -> SafeJsonWriter.write(path, json, logger, "[Оформление кланов]"));
    }
    GuildStyleStore(Path folder, Logger logger, BiPredicate<Path, String> writer) {
        this.file = folder.resolve("clan-styles.json"); this.logger = logger; this.writer = writer;
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            if (Files.size(file) > 8_000_000) throw new IllegalArgumentException("size");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.get("schema").toString().equals("1")) throw new IllegalArgumentException("schema");
            Map<UUID, Row> rows = new LinkedHashMap<>();
            for (JsonElement entry : root.getAsJsonArray("bindings")) {
                Row row = GSON.fromJson(entry, Row.class);
                if (row == null || rows.putIfAbsent(row.owner(), row) != null || rows.size() > 10000) throw new IllegalArgumentException("owner");
            }
            state = snapshot(rows);
        } catch (Exception failure) {
            healthy = false;
            if (logger != null) logger.severe("Оформление кланов: файл clan-styles.json не прочитан; эффекты и изменения выключены до восстановления. Файл не перезаписывается.");
        }
    }
    boolean healthy() { return healthy; }
    Row of(UUID owner) { return state.rows().get(owner); }
    List<Row> forGuild(String guild) { return state.guilds().getOrDefault(guild, List.of()); }

    /** Единственный писатель; ни чтение GUI, ни проверка лицензии не ждут диск. */
    Result select(UUID owner, String name, String guild, String kind, String preset, long now) {
        if (!GuildStyleDefs.valid(kind, preset) || now < 0) return Result.FAILED;
        synchronized (writeLock) {
            Row old = of(owner);
            boolean moved = old != null && !old.guild().equals(guild);
            if (moved && (now < old.movedAt() || now - old.movedAt() < TRANSFER_COOLDOWN)) return Result.COOLDOWN;
            Map<String, Choice> choices = new LinkedHashMap<>(old == null ? Map.of() : old.choices());
            if (moved) choices.replaceAll((key, value) -> new Choice(value.preset(), now));
            choices.put(kind, new Choice(preset, now));
            Map<UUID, Row> next = new LinkedHashMap<>(state.rows());
            next.put(owner, new Row(owner, name, guild, old == null || moved ? now : old.movedAt(), choices));
            return publish(next) ? Result.OK : Result.FAILED;
        }
    }
    boolean remove(UUID owner, String kind) {
        synchronized (writeLock) {
            Row old = of(owner); if (old == null) return healthy;
            Map<String, Choice> choices = new LinkedHashMap<>(old.choices()); choices.remove(kind);
            Map<UUID, Row> next = new LinkedHashMap<>(state.rows());
            // Пустой выбор не стирает привязку и cooldown: off нельзя использовать для обхода переноса.
            next.put(owner, new Row(owner, old.name(), old.guild(), old.movedAt(), choices));
            return publish(next);
        }
    }
    boolean clear(String guild, String kind) {
        synchronized (writeLock) {
            Map<UUID, Row> next = new LinkedHashMap<>(state.rows());
            for (Row old : forGuild(guild)) {
                Map<String, Choice> choices = new LinkedHashMap<>(old.choices()); choices.remove(kind);
                next.put(old.owner(), new Row(old.owner(), old.name(), old.guild(), old.movedAt(), choices));
            }
            return publish(next);
        }
    }
    private boolean publish(Map<UUID, Row> rows) {
        if (!healthy || rows.size() > 10000) return false;
        String json = GSON.toJson(Map.of("schema", 1, "bindings", rows.values()));
        if (json.length() > 8_000_000 || !writer.test(file, json)) return false;
        state = snapshot(rows); return true;
    }
    private static Snapshot snapshot(Map<UUID, Row> rows) {
        Map<String, List<Row>> guilds = new HashMap<>();
        for (Row row : rows.values()) guilds.computeIfAbsent(row.guild(), key -> new ArrayList<>()).add(row);
        guilds.replaceAll((key, value) -> List.copyOf(value));
        return new Snapshot(Map.copyOf(rows), Map.copyOf(guilds));
    }
}
