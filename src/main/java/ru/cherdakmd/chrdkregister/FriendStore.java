package ru.cherdakmd.chrdkregister;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Симметричные связи по UUID в одном атомарном файле; при ошибке чтения — отказ без перезаписи. */
final class FriendStore {
    static final int MAX_FRIENDS = 100;
    private static final int MAX_PAIRS = 50_000;
    private static final long MAX_BYTES = 8 * 1024 * 1024;
    enum Result { OK, ALREADY, NOT_FRIENDS, LIMIT, UNAVAILABLE }
    private final Path file;
    private final Logger logger;
    private Map<UUID, Set<UUID>> friends = new HashMap<>();
    private boolean available;

    FriendStore(Path folder, Logger logger) {
        this.file = folder.resolve("players").resolve("friends.json");
        this.logger = logger;
        load();
    }

    private void load() {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) { available = true; return; }
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) throw new IOException("unsafe file");
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("version") || !root.get("version").isJsonPrimitive()
                    || !"1".equals(root.get("version").getAsString())
                    || !root.has("pairs") || !root.get("pairs").isJsonArray()) throw new IOException("unsupported format");
            JsonArray pairs = root.getAsJsonArray("pairs");
            if (pairs.size() > MAX_PAIRS) throw new IOException("too many pairs");
            Map<UUID, Set<UUID>> loaded = new HashMap<>();
            for (JsonElement element : pairs) {
                JsonArray pair = element.getAsJsonArray();
                if (pair.size() != 2 || !pair.get(0).isJsonPrimitive() || !pair.get(0).getAsJsonPrimitive().isString()
                        || !pair.get(1).isJsonPrimitive() || !pair.get(1).getAsJsonPrimitive().isString()) throw new IOException("invalid pair");
                UUID a = UUID.fromString(pair.get(0).getAsString());
                UUID b = UUID.fromString(pair.get(1).getAsString());
                if (a.equals(b) || !loaded.computeIfAbsent(a, key -> new HashSet<>()).add(b)) throw new IOException("duplicate pair");
                loaded.computeIfAbsent(b, key -> new HashSet<>()).add(a);
                if (loaded.get(a).size() > MAX_FRIENDS || loaded.get(b).size() > MAX_FRIENDS) throw new IOException("friend limit exceeded");
            }
            friends = loaded;
            available = true;
        } catch (IOException | RuntimeException error) {
            available = false;
            if (logger != null) logger.severe("[CHRDK_Core] Файл друзей не прочитан, изменения отключены; оригинал сохранён: " + error.getMessage());
        }
    }

    synchronized boolean available() { return available; }
    synchronized boolean areFriends(UUID a, UUID b) {
        return available && a != null && b != null && friends.getOrDefault(a, Set.of()).contains(b);
    }
    synchronized Set<UUID> friendsOf(UUID id) {
        return available && id != null ? Set.copyOf(friends.getOrDefault(id, Set.of())) : Set.of();
    }
    synchronized Result add(UUID a, UUID b) { return change(a, b, true); }
    synchronized Result remove(UUID a, UUID b) { return change(a, b, false); }

    private Result change(UUID a, UUID b, boolean add) {
        if (!available || a == null || b == null || a.equals(b)) return Result.UNAVAILABLE;
        boolean exists = areFriends(a, b);
        if (add && exists) return Result.ALREADY;
        if (!add && !exists) return Result.NOT_FRIENDS;
        if (add && (friendsOf(a).size() >= MAX_FRIENDS || friendsOf(b).size() >= MAX_FRIENDS
                || friends.values().stream().mapToLong(Set::size).sum() / 2 >= MAX_PAIRS)) return Result.LIMIT;
        Map<UUID, Set<UUID>> next = new HashMap<>();
        friends.forEach((id, values) -> next.put(id, new HashSet<>(values)));
        if (add) {
            next.computeIfAbsent(a, key -> new HashSet<>()).add(b);
            next.computeIfAbsent(b, key -> new HashSet<>()).add(a);
        } else {
            next.get(a).remove(b); next.get(b).remove(a);
            if (next.get(a).isEmpty()) next.remove(a);
            if (next.get(b).isEmpty()) next.remove(b);
        }
        JsonArray pairs = new JsonArray();
        next.keySet().stream().sorted().forEach(first -> next.get(first).stream().sorted().forEach(second -> {
            if (first.compareTo(second) < 0) {
                JsonArray pair = new JsonArray(); pair.add(first.toString()); pair.add(second.toString()); pairs.add(pair);
            }
        }));
        JsonObject root = new JsonObject(); root.addProperty("version", 1); root.add("pairs", pairs);
        if (!SafeJsonWriter.write(file, root.toString(), logger, "[CHRDK_Core]")) return Result.UNAVAILABLE;
        friends = next;
        return Result.OK;
    }
}
