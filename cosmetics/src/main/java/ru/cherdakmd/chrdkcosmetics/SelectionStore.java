package ru.cherdakmd.chrdkcosmetics;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Выбор шлейфа по игрокам: cosmetics-data.json. null/отсутствие записи = шлейф выключен. */
final class SelectionStore {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private final Path file;
    private final Logger logger;
    private final Map<UUID, String> selection = new HashMap<>();
    private volatile boolean dirty;

    SelectionStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("cosmetics-data.json");
        this.logger = logger;
    }

    synchronized void load() {
        selection.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, String> parsed = GSON.fromJson(reader, MAP_TYPE);
            if (parsed == null) {
                return;
            }
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                try {
                    selection.put(UUID.fromString(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // мусорный ключ — пропускаем
                }
            }
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING, "[CHRDK_Cosmetics] Не удалось прочитать cosmetics-data.json: "
                    + exception.getMessage());
        }
    }

    synchronized void save() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<UUID, String> entry : selection.entrySet()) {
            out.put(entry.getKey().toString(), entry.getValue());
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(out, writer);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException exception) {
            logger.log(Level.WARNING, "[CHRDK_Cosmetics] Не удалось записать cosmetics-data.json: "
                    + exception.getMessage());
        }
    }

    synchronized String selectionOf(UUID uuid) {
        return selection.get(uuid);
    }

    synchronized void select(UUID uuid, String trailId) {
        if (trailId == null) {
            if (selection.remove(uuid) != null) {
                dirty = true;
            }
            return;
        }
        selection.put(uuid, trailId);
        dirty = true;
    }

    boolean isDirty() {
        return dirty;
    }
}
