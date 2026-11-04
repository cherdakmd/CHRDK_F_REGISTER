package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Отдельное хранилище модерации {@code moderation.json} в папке Core.
 *
 * <p>Варны, роли, зеркало банов и журнал лежат отдельно от {@code data.json}
 * аккаунтов: правка аккаунтов не затирает историю модерации и наоборот.
 * Запись идёт через {@link SafeJsonWriter} — временный файл и атомарный
 * перенос, как у остальных JSON Core. Поверх этого в
 * {@link ModerationService} каждое действие дублируется в append-only
 * {@code audit/audit-YYYY-MM-DD.jsonl}.</p>
 *
 * <p>Файл защищён контрольной суммой SHA-256 по каноническому JSON без поля
 * {@code checksum}. Ручная правка или обрыв записи детектируется при чтении:
 * копия уходит в {@code moderation.json.broken}, а панель стартует с пустым
 * состоянием вместо тихой загрузки подделанных варнов. Файлы старого формата
 * без checksum принимаются и переписываются уже с контрольной суммой.</p>
 */
public final class ModerationStore {

    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public ModerationStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("moderation.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /** Читает состояние; любой повреждённый файл не роняет плагин, а стартует с пустого. */
    public ModerationLedger load() {
        ModerationLedger ledger = new ModerationLedger();
        if (!Files.isRegularFile(file)) return ledger;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text == null || text.isBlank()) return ledger;

            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            String declared = root.has("checksum") && !root.get("checksum").isJsonNull()
                    ? root.get("checksum").getAsString() : "";
            root.remove("checksum");
            if (!declared.isEmpty() && !sha256(gson.toJson(root)).equals(declared)) {
                throw new IllegalStateException("контрольная сумма не совпадает");
            }

            ModerationLedger.State state = gson.fromJson(text, ModerationLedger.State.class);
            if (state == null) return ledger;
            ledger.restore(state);
            if (state.version != ModerationLedger.SCHEMA_VERSION) {
                logger.info("[CHRDK_Core] moderation.json: схема " + state.version
                        + " → " + ModerationLedger.SCHEMA_VERSION + ", миграция по полям");
            }
        } catch (IOException | RuntimeException exception) {
            preserveBroken();
            logger.log(Level.WARNING,
                    "[CHRDK_Core] Не удалось прочитать moderation.json: " + exception.getMessage()
                            + ". Копия сохранена как moderation.json.broken; панель стартует с пустым состоянием.",
                    exception);
        }
        return ledger;
    }

    /** Атомарно сохраняет снимок с контрольной суммой. Возвращает false при ошибке ввода-вывода. */
    public boolean save(ModerationLedger ledger) {
        if (ledger == null) return false;
        try {
            JsonObject root = gson.toJsonTree(ledger.snapshot()).getAsJsonObject();
            root.remove("checksum");
            String body = gson.toJson(root);
            root.addProperty("checksum", sha256(body));
            return SafeJsonWriter.write(file, gson.toJson(root), logger, "[CHRDK_Core]");
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "[CHRDK_Core] Не удалось записать moderation.json", exception);
            return false;
        }
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
