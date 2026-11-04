package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            ModerationLedger.State state = gson.fromJson(text, ModerationLedger.State.class);
            if (state == null) return ledger;
            ledger.restore(state);
            if (state.version != ModerationLedger.SCHEMA_VERSION) {
                logger.info("[CHRDK_Core] moderation.json: схема " + state.version
                        + " → " + ModerationLedger.SCHEMA_VERSION + ", миграция по полям");
            }
        } catch (IOException | RuntimeException exception) {
            logger.log(Level.WARNING,
                    "[CHRDK_Core] Не удалось прочитать moderation.json: " + exception.getMessage()
                            + ". Сохраните файл как moderation.json.broken и проверьте вручную.",
                    exception);
        }
        return ledger;
    }

    /** Атомарно сохраняет снимок. Возвращает false при ошибке ввода-вывода. */
    public boolean save(ModerationLedger ledger) {
        if (ledger == null) return false;
        try {
            return SafeJsonWriter.write(file, gson.toJson(ledger.snapshot()), logger, "[CHRDK_Core]");
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "[CHRDK_Core] Не удалось записать moderation.json", exception);
            return false;
        }
    }
}
