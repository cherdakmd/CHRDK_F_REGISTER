package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.storage.PlayerDataFiles;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Разделённое хранилище модерации в папке Core.
 *
 * <p>{@code moderation.json} хранит глобальную часть — роли модераторов,
 * журнал, счётчик журнала и маркер схемы — и защищён SHA-256 по каноническому
 * JSON. Варны и зеркало банов лежат в отдельных редактируемых
 * {@code players/warns.yml} и {@code players/bans.yml}: у них нет контрольной
 * суммы, чтобы администратор мог исправить запись вручную при остановленном
 * сервере. При первом запуске этой версии старые поля переносятся в YAML,
 * исходный JSON уходит в backup, а затем перезаписывается без них.</p>
 *
 * <p>Битый JSON или YAML сохраняется в {@code .broken}; панель стартует с пустой
 * затронутой частью вместо тихой загрузки подделанных варнов. Оба YAML пишутся
 * атомарно через {@link SafeJsonWriter}.</p>
 */
public final class ModerationStore {

    private static final String TAG = "[CHRDK_Core]";
    private final Path dataFolder;
    private final Path file;
    private final ModerationPlayerStore playerStore;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private boolean playerLoadFailed;
    private boolean globalFrozen;

    public ModerationStore(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.file = dataFolder.resolve("moderation.json");
        this.playerStore = new ModerationPlayerStore(dataFolder, logger);
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    public Path warnsFile() {
        return playerStore.warnsFile();
    }

    public Path bansFile() {
        return playerStore.bansFile();
    }

    public boolean playerLoadFailed() {
        return playerLoadFailed;
    }

    /** Читает состояние; любой повреждённый файл не роняет плагин, а стартует с пустой части. */
    public ModerationLedger load() {
        ModerationLedger ledger = new ModerationLedger();
        playerLoadFailed = false;
        globalFrozen = false;
        JsonObject root = null;
        ModerationLedger.State state = new ModerationLedger.State();
        state.playerDataSchema = ModerationLedger.PLAYER_DATA_SCHEMA;
        boolean globalCorrupted = false;

        if (Files.isRegularFile(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                root = JsonParser.parseString(text).getAsJsonObject();
                String declared = root.has("checksum") && !root.get("checksum").isJsonNull()
                        ? root.get("checksum").getAsString() : "";
                root.remove("checksum");
                if (!declared.isEmpty() && !sha256(gson.toJson(root)).equals(declared)) {
                    throw new IllegalStateException("контрольная сумма не совпадает");
                }
                ModerationLedger.State parsed = gson.fromJson(text, ModerationLedger.State.class);
                if (parsed != null) state = parsed;
                normalizeState(state);
                if (state.version != ModerationLedger.SCHEMA_VERSION) {
                    logger.info(TAG + " moderation.json: схема " + state.version
                            + " → " + ModerationLedger.SCHEMA_VERSION + ", миграция по полям");
                }
            } catch (IOException | RuntimeException exception) {
                preserveBroken();
                globalCorrupted = true;
                state = new ModerationLedger.State();
                state.playerDataSchema = ModerationLedger.PLAYER_DATA_SCHEMA;
                logger.log(Level.WARNING,
                        TAG + " Не удалось прочитать moderation.json: " + exception.getMessage()
                                + ". Копия сохранена как moderation.json.broken; панель стартует с пустым состоянием.",
                        exception);
            }
        }

        ModerationPlayerStore.Result players = playerStore.load();
        ModerationPlayerStore.Document document = new ModerationPlayerStore.Document();
        boolean needsMigration = !globalCorrupted && root != null
                && (hasLegacyPlayerFields(root)
                    || state.playerDataSchema < ModerationLedger.PLAYER_DATA_SCHEMA);
        if (players.failed()) {
            playerLoadFailed = true;
        } else {
            document = players.document();
            if (needsMigration) {
                if (players.missing()) {
                    document = ModerationPlayerStore.fromState(state);
                    if (!playerStore.save(document)) {
                        playerLoadFailed = true;
                        logger.warning(TAG + " Не удалось создать players/warns.yml и players/bans.yml. "
                                + "Исходный moderation.json оставлен без изменений.");
                    }
                }
                if (!playerLoadFailed) pruneLegacyGlobal(root);
            } else if (players.missing()) {
                if (!playerStore.save(document)) {
                    playerLoadFailed = true;
                    logger.warning(TAG + " Не удалось создать пустые players/warns.yml и players/bans.yml.");
                }
            }
        }

        PlayerDataFiles.writeReadme(dataFolder, ModerationPlayerStore.README, logger, TAG);
        ModerationPlayerStore.applyTo(state, document);
        ledger.restore(state);
        return ledger;
    }

    private static void normalizeState(ModerationLedger.State state) {
        if (state.moderators == null) state.moderators = new java.util.ArrayList<>();
        if (state.journal == null) state.journal = new java.util.ArrayList<>();
        if (state.warns == null) state.warns = new java.util.ArrayList<>();
        if (state.bans == null) state.bans = new java.util.ArrayList<>();
        if (state.nextWarnId < 1L) state.nextWarnId = 1L;
        if (state.nextJournalId < 1L) state.nextJournalId = 1L;
    }

    private static boolean hasLegacyPlayerFields(JsonObject root) {
        return root.has("warns") || root.has("bans") || root.has("nextWarnId");
    }

    /** Backup → удаление варнов/банов из moderation.json → новая контрольная сумма. */
    private void pruneLegacyGlobal(JsonObject root) {
        try {
            PlayerDataFiles.backup(file, "players-split", logger, TAG);
        } catch (IOException | RuntimeException failure) {
            globalFrozen = true;
            logger.severe(TAG + " Backup moderation.json не создан (" + failure.getClass().getSimpleName()
                    + "). Оригинал не перезаписывается; устраните права на папку и перезапустите сервер.");
            return;
        }
        for (String legacy : List.of("warns", "bans", "nextWarnId")) {
            root.remove(legacy);
        }
        root.addProperty("playerDataSchema", ModerationLedger.PLAYER_DATA_SCHEMA);
        root.addProperty("checksum", checksumOf(root));
        if (!SafeJsonWriter.write(file, gson.toJson(root), logger, TAG)) {
            globalFrozen = true;
            logger.severe(TAG + " Не удалось перезаписать moderation.json после выноса данных игроков. "
                    + "Оригинал сохранён в backup; глобальная часть не пишется до следующего старта.");
        }
    }

    /**
     * Атомарно сохраняет полный снимок: глобальный JSON с контрольной суммой
     * и два YAML с варнами/банами. При сбое одного файла возвращает false,
     * состояние в памяти остаётся доступным для следующей попытки.
     */
    public boolean save(ModerationLedger ledger) {
        if (ledger == null) return false;
        ModerationLedger.State full = ledger.snapshot();
        boolean playersOk = !playerLoadFailed;
        if (playerLoadFailed) {
            logger.warning(TAG + " players/warns.yml или players/bans.yml повреждены: "
                    + "сохранение YAML пропущено, исправьте файл и перезапустите сервер.");
        } else {
            playersOk = playerStore.save(ModerationPlayerStore.fromState(full));
        }
        if (globalFrozen) return false;
        JsonObject root = gson.toJsonTree(full).getAsJsonObject();
        root.remove("checksum");
        root.remove("warns");
        root.remove("bans");
        root.remove("nextWarnId");
        root.addProperty("checksum", checksumOf(root));
        boolean globalOk = SafeJsonWriter.write(file, gson.toJson(root), logger, TAG);
        return globalOk && playersOk;
    }

    /** Не даём следующему save() безвозвратно затереть подозрительный файл. */
    private void preserveBroken() {
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // Нет прав или нет места — оригинал всё равно остаётся на диске.
        }
    }

    private String checksumOf(JsonObject root) {
        JsonObject copy = root.deepCopy();
        copy.remove("checksum");
        return sha256(gson.toJson(copy));
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format("%02x", value & 0xff));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 недоступен", impossible);
        }
    }
}
