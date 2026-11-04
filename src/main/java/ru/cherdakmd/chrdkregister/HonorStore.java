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
import java.util.List;
import java.security.MessageDigest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Отдельное хранилище {@code honors.json} в папке Core.
 *
 * <p>Особые звания намеренно лежат вне {@code data.json}: ручная правка
 * аккаунтов не должна затирать решения владельца, а решение владельца не
 * должно выглядеть как подделанная покупка. Запись через {@link SafeJsonWriter}
 * — временный файл и атомарный перенос, как у остальных JSON Core.</p>
 *
 * <p>SHA-256 по каноническому JSON без поля {@code checksum}: ручная правка
 * или обрыв записи уходит в {@code honors.json.broken}, а плагин стартует с
 * шаблоном по умолчанию. Legacy без checksum принимается и переписывается.</p>
 */
public final class HonorStore {

    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public HonorStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("honors.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /** Результат загрузки: создан ли файл с нуля и какие выдачи посеяны из конфига. */
    public record Load(HonorLedger ledger, List<HonorGrant> seeded, boolean created) {
    }

    /** Совместимость с тестами и вызовами без настроек. */
    public HonorLedger load() {
        return load(HonorConfig.DEFAULT);
    }

    public HonorLedger load(HonorConfig config) {
        return loadWithStatus(config).ledger();
    }

    /**
     * Нет файла (или он пустой/битый) — шаблон и благодарности из
     * {@code honors.initial-grants}. {@code created=true} означает, что состояние
     * ещё не записано на диск: вызывающий должен сохранить его и продублировать
     * стартовые выдачи в аудит, иначе дата перегенерируется при следующем старте.
     */
    public Load loadWithStatus(HonorConfig config) {
        HonorConfig effective = config == null ? HonorConfig.DEFAULT : config;
        if (!Files.isRegularFile(file)) return seeded(effective);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text == null || text.isBlank()) return seeded(effective);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            String declared = root.has("checksum") && !root.get("checksum").isJsonNull()
                    ? root.get("checksum").getAsString() : "";
            root.remove("checksum");
            if (!declared.isEmpty() && !sha256(gson.toJson(root)).equals(declared)) {
                throw new IllegalStateException("контрольная сумма не совпадает");
            }
            HonorLedger.State state = gson.fromJson(text, HonorLedger.State.class);
            HonorLedger ledger = new HonorLedger();
            if (state != null) ledger.restore(state);
            return new Load(ledger, List.of(), false);
        } catch (IOException | RuntimeException exception) {
            preserveBroken();
            logger.log(Level.WARNING,
                    "[CHRDK_Core] Не удалось прочитать honors.json: " + exception.getMessage()
                            + ". Копия сохранена как honors.json.broken; звания стартуют с шаблона по умолчанию.",
                    exception);
            return seeded(effective);
        }
    }

    private Load seeded(HonorConfig config) {
        HonorLedger ledger = HonorLedger.withDefaults(config);
        return new Load(ledger, ledger.allGrants(), true);
    }

    /** Атомарно сохраняет снимок с контрольной суммой. */
    public boolean save(HonorLedger ledger) {
        if (ledger == null) return false;
        try {
            JsonObject root = gson.toJsonTree(ledger.snapshot()).getAsJsonObject();
            root.remove("checksum");
            String body = gson.toJson(root);
            root.addProperty("checksum", sha256(body));
            return SafeJsonWriter.write(file, gson.toJson(root), logger, "[CHRDK_Core]");
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "[CHRDK_Core] Не удалось записать honors.json", exception);
            return false;
        }
    }

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
