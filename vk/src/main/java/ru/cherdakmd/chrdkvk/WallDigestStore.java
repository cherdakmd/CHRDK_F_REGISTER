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
 * Персистентное состояние дайджеста: счётчики суток, снимок престижа,
 * отметка об уже опубликованной записи и счётчик попыток.
 *
 * <p>Файл {@code wall-digest.json} лежит в папке плагина и пишется атомарно
 * через временный файл, как остальные хранилища проекта. Битый или чужой JSON
 * не роняет плагин: начинается чистый день, а причина пишется в лог.</p>
 */
public final class WallDigestStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Версия формата на случай будущей миграции полей. */
    public static final int SCHEMA = 1;

    public static final class State {
        int schema = SCHEMA;
        String day = "";
        int prestigeEvents;
        int donationEvents;
        int peakOnline;
        int newPlayers;
        /** Агрегаты игровой активности за сутки: блоки, алмазы, победы над мобами. */
        long blocksBroken;
        long diamondsGained;
        long mobKills;
        /** Те же агрегаты по игрокам — для персональных карточек «Шахтёр/Охотник дня». */
        Map<String, Long> blocksByPlayer = new LinkedHashMap<>();
        Map<String, Long> diamondsByPlayer = new LinkedHashMap<>();
        Map<String, Long> mobKillsByPlayer = new LinkedHashMap<>();
        /** Прирост престижа за текущие сутки по игрокам (для топа). */
        Map<String, Integer> prestigeGains = new LinkedHashMap<>();
        /** Последние известные значения престижа (переживают рестарт). */
        Map<String, Integer> prestigeSnapshot = new LinkedHashMap<>();
        String lastPostedDay = "";
        long lastPostId;
        String postingDay = "";
        /** Сутки, намеренно пропущенные после прерванной публикации (без дубля). */
        String skippedDay = "";
        String attemptDay = "";
        int attempts;
    }

    private final Path file;
    private final Logger logger;

    public WallDigestStore(Path folder, Logger logger) {
        this.file = folder.resolve("wall-digest.json");
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
            if (loaded.prestigeGains == null) loaded.prestigeGains = new LinkedHashMap<>();
            if (loaded.prestigeSnapshot == null) loaded.prestigeSnapshot = new LinkedHashMap<>();
            if (loaded.blocksByPlayer == null) loaded.blocksByPlayer = new LinkedHashMap<>();
            if (loaded.diamondsByPlayer == null) loaded.diamondsByPlayer = new LinkedHashMap<>();
            if (loaded.mobKillsByPlayer == null) loaded.mobKillsByPlayer = new LinkedHashMap<>();
            if (loaded.day == null) loaded.day = "";
            if (loaded.lastPostedDay == null) loaded.lastPostedDay = "";
            if (loaded.postingDay == null) loaded.postingDay = "";
            if (loaded.skippedDay == null) loaded.skippedDay = "";
            if (loaded.attemptDay == null) loaded.attemptDay = "";
            return loaded;
        } catch (IOException | RuntimeException bad) {
            logger.log(Level.WARNING, "[wall-digest] состояние не прочитано, начинаем чистые сутки", bad);
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
            logger.log(Level.WARNING, "[wall-digest] не удалось сохранить состояние дайджеста", bad);
        }
    }
}
