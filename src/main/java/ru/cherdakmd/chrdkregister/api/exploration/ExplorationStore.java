package ru.cherdakmd.chrdkregister.api.exploration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/** API37. UUID-owned, bounded atlas state and permanent earned licenses. No paid rank fields. */
public final class ExplorationStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static final class Root {
        int schema = 1;
        Map<String, List<String>> catalogs = new LinkedHashMap<>();
        Map<String, Map<UUID, Profile>> scopes = new LinkedHashMap<>();
    }
    public static final class BiomeProgress {
        boolean visited;
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Set<String>> unique = new LinkedHashMap<>();
    }
    public record Waypoint(String world, double x, double y, double z) {
        public Waypoint {
            UUID.fromString(world);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 30_000_000)
                throw new IllegalArgumentException("Invalid waypoint");
        }
    }
    static final class Profile {
        Map<String, BiomeProgress> biomes = new LinkedHashMap<>();
        Set<String> trophies = new LinkedHashSet<>();
        int license;
        String title = "off";
        boolean effect;
        Map<String, Waypoint> marks = new LinkedHashMap<>();
        Map<String, List<String>> routes = new LinkedHashMap<>();
    }
    private final Path file;
    private final Logger logger;
    private Root root = new Root();
    private boolean healthy = true;
    private boolean dirty;
    private long lastFailureLog;
    public ExplorationStore(Path file, Logger logger) { this.file = file.toAbsolutePath(); this.logger = logger; }
    public synchronized boolean healthy() { return healthy; }
    public synchronized void load() {
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Root loaded = JSON.fromJson(reader, Root.class);
            if (loaded == null || loaded.schema != 1 || loaded.scopes == null || loaded.catalogs == null) throw new IOException("Invalid atlas schema");
            for (var c : loaded.catalogs.entrySet()) {
                if (!c.getKey().matches("lifetime|season:-?[0-9]+") || c.getValue() == null || c.getValue().isEmpty()
                        || c.getValue().size() > 256 || c.getValue().stream().anyMatch(k -> !ExplorationCatalog.required(k))
                        || new HashSet<>(c.getValue()).size() != c.getValue().size()) throw new IOException("Invalid catalog");
            }
            for (var scope : loaded.scopes.entrySet()) {
                if (!scope.getKey().matches("lifetime|season:-?[0-9]+|licenses") || scope.getValue() == null) throw new IOException("Invalid scope");
                for (var entry : scope.getValue().entrySet()) {
                    Profile p = entry.getValue();
                    if (entry.getKey() == null || p == null || p.biomes == null || p.trophies == null || p.marks == null || p.routes == null
                            || p.license < 0 || p.license > 2 || p.title == null || !Set.of("off", "free", "premium").contains(p.title)
                            || p.marks.size() > 24 || p.routes.size() > 5) throw new IOException("Invalid profile");
                    for (var mark : p.marks.entrySet()) {
                        if (!validName(mark.getKey()) || mark.getValue() == null) throw new IOException("Invalid mark");
                        Waypoint w = mark.getValue(); new Waypoint(w.world(), w.x(), w.y(), w.z());
                    }
                    for (var route : p.routes.entrySet()) {
                        if (!validName(route.getKey()) || route.getValue() == null || route.getValue().isEmpty()
                                || route.getValue().size() > 24 || route.getValue().stream().anyMatch(n -> !p.marks.containsKey(n))) throw new IOException("Invalid route");
                    }
                    for (var biome : p.biomes.entrySet()) {
                        var b = biome.getValue();
                        if (!ExplorationCatalog.required(biome.getKey()) || b == null || b.counts == null || b.unique == null) throw new IOException("Invalid biome");
                        var tasks = ExplorationCatalog.tasks(biome.getKey());
                        for (var count : b.counts.entrySet()) {
                            var task = tasks.stream().filter(t -> t.id().equals(count.getKey())).findFirst().orElseThrow();
                            if (count.getValue() == null || count.getValue() < 0 || count.getValue() > task.target()) throw new IOException("Invalid counter");
                        }
                        for (var seen : b.unique.entrySet()) {
                            var task = tasks.stream().filter(t -> t.id().equals(seen.getKey())).findFirst().orElseThrow();
                            if (seen.getValue() == null || seen.getValue().size() > task.target()
                                    || seen.getValue().stream().anyMatch(s -> s == null || s.length() > 128)) throw new IOException("Invalid unique set");
                        }
                    }
                }
            }
            root = loaded;
        } catch (Exception error) {
            healthy = false;
            logger.log(Level.SEVERE, "Atlas is blocked; restore the original file, do not reset it: " + file, error);
        }
    }
    private Profile profile(String scope, UUID id) {
        if (!scope.matches("lifetime|season:-?[0-9]+|licenses")) throw new IllegalArgumentException("scope");
        return root.scopes.computeIfAbsent(scope, s -> new LinkedHashMap<>()).computeIfAbsent(id, u -> new Profile());
    }
    private Profile existing(String scope, UUID id) {
        if (scope == null || id == null) return null;
        var profiles = root.scopes.get(scope); return profiles == null ? null : profiles.get(id);
    }
    public synchronized boolean visited(String scope, UUID id, String biome) {
        Profile p = existing(scope, id);
        return p != null && p.biomes.containsKey(biome) && p.biomes.get(biome).visited;
    }
    public synchronized boolean visit(String scope, UUID id, String biome) {
        if (!healthy || id == null || !ExplorationCatalog.required(biome)) return false;
        var b = profile(scope, id).biomes.computeIfAbsent(biome, k -> new BiomeProgress());
        if (b.visited) return false;
        b.visited = true; dirty = true; return true;
    }
    public synchronized long progress(String scope, UUID id, String biome, String task) {
        Profile p = existing(scope, id);
        var b = p == null ? null : p.biomes.get(biome);
        return b == null ? 0 : b.counts.getOrDefault(task, 0L);
    }
    /** Unique chunks/blocks cannot be farmed at the same coordinate, including after restart. */
    public synchronized boolean add(String scope, UUID id, String biome, ExplorationCatalog.Task task, long delta, String unique) {
        if (!healthy || id == null || delta <= 0 || !ExplorationCatalog.required(biome) || !ExplorationCatalog.tasks(biome).contains(task)
                || unique != null && (unique.isBlank() || unique.length() > 128)) return false;
        visit(scope, id, biome);
        var b = profile(scope, id).biomes.get(biome);
        long before = b.counts.getOrDefault(task.id(), 0L);
        if (before >= task.target()) return false;
        if (unique != null && !b.unique.computeIfAbsent(task.id(), k -> new LinkedHashSet<>()).add(unique)) return false;
        long after = before + Math.min(delta, task.target() - before);
        b.counts.put(task.id(), after); dirty = true;
        return after >= task.target();
    }
    public synchronized int done(String scope, UUID id, String biome) {
        return (int) ExplorationCatalog.tasks(biome).stream().filter(t -> progress(scope, id, biome, t.id()) >= t.target()).count();
    }
    public synchronized List<String> catalog(String scope, List<String> candidates) {
        if (!healthy || scope == null || candidates == null || !scope.matches("lifetime|season:-?[0-9]+") || candidates.isEmpty()) return List.of();
        List<String> valid = candidates.stream().filter(ExplorationCatalog::required).distinct().sorted().toList();
        List<String> previous = root.catalogs.get(scope);
        if (previous != null && !scope.equals("lifetime")) return List.copyOf(previous);
        var next = new TreeSet<String>(valid);
        if (previous != null) next.addAll(previous);
        if (next.isEmpty()) return List.of();
        List<String> result = List.copyOf(next);
        if (!result.equals(previous)) { root.catalogs.put(scope, result); dirty = true; }
        return result;
    }
    public synchronized boolean trophy(String scope, UUID id, String name) {
        var p = existing(scope, id); return p != null && p.trophies.contains(name);
    }
    public synchronized boolean awardTrophy(String scope, UUID id, String name) {
        if (!healthy || id == null || name == null || !Set.of("visited", "master").contains(name)) return false;
        boolean fresh = profile(scope, id).trophies.add(name); if (fresh) dirty = true; return fresh;
    }
    public synchronized int license(UUID id) { Profile p = existing("licenses", id); return healthy && p != null ? p.license : 0; }
    /** The license becomes usable only after an fsync+atomic checkpoint, never after a failed write. */
    public synchronized boolean award(UUID id, int level) {
        if (!healthy || id == null || level < 1 || level > 2) return false;
        Profile p = profile("licenses", id);
        int before = p.license; String title = p.title;
        if (before >= level) return true;
        p.license = level; p.title = level == 2 ? "premium" : "free"; dirty = true;
        if (save()) return true;
        p.license = before; p.title = title; dirty = true; return false;
    }
    public synchronized String title(UUID id) {
        Profile p = existing("licenses", id);
        if (p == null || !healthy || p.license == 0 || p.title.equals("off")) return "off";
        return p.title.equals("premium") && p.license < 2 ? "free" : p.title;
    }
    public synchronized boolean setTitle(UUID id, String title) {
        if (license(id) == 0 || title == null || !Set.of("off", "free", "premium").contains(title) || title.equals("premium") && license(id) < 2) return false;
        var p = profile("licenses", id); String old = p.title; p.title = title; dirty = true;
        if (save()) return true; p.title = old; dirty = true; return false;
    }
    public synchronized boolean effect(UUID id) { Profile p = existing("licenses", id); return healthy && p != null && p.license > 0 && p.effect; }
    public synchronized boolean toggleEffect(UUID id) {
        if (license(id) == 0) return false;
        var p = profile("licenses", id); p.effect = !p.effect; dirty = true;
        if (save()) return true; p.effect = !p.effect; dirty = true; return false;
    }
    public static boolean validName(String name) { return name != null && name.matches("[a-zA-Zа-яА-ЯёЁ0-9_-]{1,24}"); }
    public synchronized Map<String, Waypoint> marks(UUID id) {
        var p = existing("licenses", id); return p == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(p.marks));
    }
    public synchronized Map<String, List<String>> routes(UUID id) {
        var p = existing("licenses", id); if (p == null) return Map.of();
        Map<String, List<String>> copy = new LinkedHashMap<>(); p.routes.forEach((k, v) -> copy.put(k, List.copyOf(v))); return Collections.unmodifiableMap(copy);
    }
    public synchronized boolean mark(UUID id, String name, Waypoint point) {
        int level = license(id);
        if (level == 0 || !validName(name) || point == null) return false;
        var p = profile("licenses", id);
        if (!p.marks.containsKey(name) && p.marks.size() >= (level == 2 ? 24 : 8)) return false;
        var old = p.marks.put(name, point); dirty = true;
        if (save()) return true; if (old == null) p.marks.remove(name); else p.marks.put(name, old); dirty = true; return false;
    }
    public synchronized boolean removeMark(UUID id, String name) {
        if (license(id) == 0) return false;
        var p = profile("licenses", id);
        if (!p.marks.containsKey(name) || p.routes.values().stream().anyMatch(r -> r.contains(name))) return false;
        var old = p.marks.remove(name); dirty = true;
        if (save()) return true; p.marks.put(name, old); dirty = true; return false;
    }
    public synchronized boolean route(UUID id, String name, List<String> marks) {
        int level = license(id);
        if (level == 0 || !validName(name) || marks == null || marks.isEmpty() || marks.size() > (level == 2 ? 24 : 8)) return false;
        var p = profile("licenses", id);
        if ((!p.routes.containsKey(name) && p.routes.size() >= (level == 2 ? 5 : 1)) || marks.stream().anyMatch(n -> !p.marks.containsKey(n))) return false;
        var old = p.routes.put(name, List.copyOf(marks)); dirty = true;
        if (save()) return true; if (old == null) p.routes.remove(name); else p.routes.put(name, old); dirty = true; return false;
    }
    public synchronized boolean removeRoute(UUID id, String name) {
        if (license(id) == 0) return false;
        var p = profile("licenses", id); var old = p.routes.remove(name); if (old == null) return false; dirty = true;
        if (save()) return true; p.routes.put(name, old); dirty = true; return false;
    }
    public synchronized boolean save() {
        if (!healthy) return false;
        if (!dirty) return true;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.toJson(root), StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { channel.force(true); }
            dirty = false; lastFailureLog = 0; return true;
        } catch (IOException | RuntimeException error) {
            long now = System.nanoTime();
            if (lastFailureLog == 0 || now - lastFailureLog >= 60_000_000_000L) {
                logger.log(Level.SEVERE, "Atlas checkpoint failed; reward/route changes were NOT confirmed: " + file, error);
                lastFailureLog = now;
            }
            return false;
        }
    }
}
