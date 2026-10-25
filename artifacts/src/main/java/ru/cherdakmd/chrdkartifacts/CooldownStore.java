package ru.cherdakmd.chrdkartifacts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Постоянные кулдауны активных артефактов (`cooldowns.json`, 1.4.130).
 *
 * <p>До 1.4.130 кулдауны жили только в памяти: перезапуск сервера обнулял
 * «Перо Феникса», «Хартию чар» и все активные артефакты. Теперь карта
 * «игрок|артефакт» → момент окончания сохраняется, а истёкшие записи
 * вычищаются в {@link Cooldowns#prune(long)}.</p>
 *
 * <p>Формат: {@code {"version":1,"cooldowns":{"<uuid>|<id>":1234567890}}}.
 * Класс чистый (Gson + NIO), без Bukkit — проверяется юнит-тестами.</p>
 */
public final class CooldownStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {
    }.getType();

    private final Path file;
    private final Logger logger;

    public CooldownStore(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve("cooldowns.json");
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /** Читает карту кулдаунов; отсутствие файла и мусор — пустая карта. */
    public Map<String, Long> load() {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> data = GSON.fromJson(reader, DATA_TYPE);
            if (data == null || !(data.get("cooldowns") instanceof Map<?, ?> rows)) {
                return Map.of();
            }
            Map<String, Long> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rows.entrySet()) {
                if (entry.getValue() instanceof Number number && number.longValue() > 0L) {
                    out.put(String.valueOf(entry.getKey()), number.longValue());
                }
            }
            return out;
        } catch (Exception exception) {
            logger.severe("[CHRDK_Artifacts] Не удалось прочитать " + file + ": "
                    + exception.getMessage());
            return Map.of();
        }
    }

    /** Записывает карту. Ключи сортируются — файл не «дёргается» при каждом сохранении. */
    public void save(Map<String, Long> snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        data.put("cooldowns", snapshot == null ? Map.of() : new TreeMap<>(snapshot));
        write(GSON.toJson(data));
    }

    private void write(String json) {
        // Флага «грязно» здесь нет: таймер отдаёт свежую карту кулдаунов
        // каждые несколько секунд, поэтому сбой записи исправит следующая.
        SafeJsonWriter.write(file, json, logger, "[CHRDK_Artifacts]");
    }
}
