package ru.cherdakmd.chrdkhunt;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Маленький отдельный календарь, не кошелёк. Решение пишется ДО предупреждения/спавна.
 * Ошибка чтения/записи закрывает событие до рестарта, а не обнуляет календарь. */
final class FullMoonLedger {
    record Night(long day, boolean selected, UUID token, long started, boolean finished) {
        Night start(long now) { return new Night(day, selected, token, now, false); }
        Night finish() { return new Night(day, selected, token, started, true); }
    }
    private final Path file;
    private final Map<UUID, Night> rows = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean healthy = true;
    FullMoonLedger(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("version").getAsBigDecimal().intValueExact() != 1) throw new IllegalArgumentException("version");
            var worlds = root.getAsJsonObject("worlds");
            if (worlds.size() > 128) throw new IllegalArgumentException("world count");
            for (var entry : worlds.entrySet()) {
                var n = entry.getValue().getAsJsonObject();
                long day = n.get("day").getAsBigDecimal().longValueExact();
                long started = n.get("started").getAsBigDecimal().longValueExact();
                if (day < 0 || started < 0 || !n.get("selected").getAsJsonPrimitive().isBoolean()
                        || !n.get("finished").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("night");
                boolean selected = n.get("selected").getAsBoolean(), finished = n.get("finished").getAsBoolean();
                if (started > 0 && !selected) throw new IllegalArgumentException("start without selection");
                rows.put(UUID.fromString(entry.getKey()), new Night(day, selected,
                        UUID.fromString(n.get("token").getAsString()), started, finished));
            }
        } catch (Exception error) { healthy = false; rows.clear(); }
    }
    boolean healthy() { return healthy; }
    Night get(UUID world) { return rows.get(world); }
    synchronized boolean put(UUID world, Night night) {
        if (!healthy || world == null || night == null) return false;
        if (rows.size() >= 128 && !rows.containsKey(world)) { healthy = false; return false; }
        Night prior = rows.get(world);
        if (night.day() < 0 || night.started() < 0 || night.token() == null
                || prior != null && (night.day() < prior.day() || night.day() == prior.day()
                && (!night.token().equals(prior.token()) || night.selected() != prior.selected()
                || prior.finished() && !night.finished() || prior.started() > 0 && night.started() != prior.started()))) return false;
        rows.put(world, night);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            var root = new LinkedHashMap<String, Object>();
            root.put("version", 1); root.put("worlds", rows);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            byte[] bytes = new Gson().toJson(root).getBytes(StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Не допускаем неатомарную замену: старый валидный календарь лучше неоднозначного нового.
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception error) { healthy = false; return false; }
    }
}
