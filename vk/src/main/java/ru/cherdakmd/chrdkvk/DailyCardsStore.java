package ru.cherdakmd.chrdkvk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ledger четырёх персональных карточек: какие номинации уже ушли на стену
 * сегодня и сколько было попыток. Лежит рядом с {@code wall-digest.json},
 * пишется атомарно через {@code .tmp} + {@code move}.
 *
 * <p>Отдельный файл и отдельная схема: состояние суточного дайджеста не
 * перезаписывается и его {@code SCHEMA = 1} остаётся нетронутым.</p>
 */
public final class DailyCardsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int SCHEMA = 1;

    public static final class State {
        int schema = SCHEMA;
        String day = "";
        /** kind.id → post_id; 0 означает «сутки намеренно пропущены после сбоя». */
        Map<String, Long> posted = new LinkedHashMap<>();
        /** kind.id → число попыток за эти сутки. */
        Map<String, Integer> attempts = new LinkedHashMap<>();
        /** kind.id → незавершённая сетевая публикация (дубль хуже пропуска). */
        Map<String, Boolean> posting = new LinkedHashMap<>();
    }

    private final Path file;
    private final Logger logger;

    public DailyCardsStore(Path folder, Logger logger) {
        this.file = folder.resolve("daily-cards.json");
        this.logger = logger;
    }

    public State load() {
        if (!Files.isRegularFile(file)) {
            return new State();
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            State loaded = GSON.fromJson(raw, State.class);
            if (loaded == null || loaded.schema != SCHEMA) {
                return new State();
            }
            if (loaded.posted == null) loaded.posted = new LinkedHashMap<>();
            if (loaded.attempts == null) loaded.attempts = new LinkedHashMap<>();
            if (loaded.posting == null) loaded.posting = new LinkedHashMap<>();
            if (loaded.day == null) loaded.day = "";
            return loaded;
        } catch (IOException | RuntimeException bad) {
            logger.log(Level.WARNING, "[daily-cards] состояние не прочитано, начинаем чистые сутки", bad);
            return new State();
        }
    }

    public void save(State state) {
        if (state == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(temp, GSON.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException bad) {
            logger.log(Level.WARNING, "[daily-cards] не удалось сохранить состояние карточек", bad);
        }
    }
}
