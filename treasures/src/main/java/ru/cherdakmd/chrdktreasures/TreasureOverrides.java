package ru.cherdakmd.chrdktreasures;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.logging.Logger;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

/**
 * Переопределения шансов из {@code chances.yml}.
 *
 * <p>Зачем отдельный файл: {@code /treasuresadmin chance} не должна
 * перезаписывать {@code config.yml}, иначе исчезнут русские комментарии и
 * оформление, ради которого файл и писался. Здесь живут только числа, файл
 * маленький и генерируется целиком, а итоговый шанс = значение из этого файла
 * поверх {@code config.yml}.</p>
 *
 * <p>Формат:
 * <pre>
 * chances:
 *   legendary: 0.02
 * </pre>
 * Значения — проценты на блок; допустимы 0..100. Пустой файл или отсутствие
 * редкости означают «взять значение из config.yml».</p>
 */
public final class TreasureOverrides {

    public static final String FILE_NAME = "chances.yml";

    private final Path file;
    private final Logger logger;
    private final Map<TreasureTier, Double> values = new EnumMap<>(TreasureTier.class);

    public TreasureOverrides(Path dataFolder, Logger logger) {
        this.file = dataFolder.resolve(FILE_NAME);
        this.logger = logger;
    }

    public Path file() {
        return file;
    }

    /** Перечитывает файл; битые и неизвестные записи пропускаются с предупреждением. */
    public synchronized void load() {
        values.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("chances");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            TreasureTier tier = TreasureTier.byId(key);
            if (tier == null) {
                logger.warning("[CHRDK_Treasures] " + FILE_NAME + ": неизвестная редкость «"
                        + key + "» — пропускаю.");
                continue;
            }
            double percent = root.getDouble(key, Double.NaN);
            if (Double.isNaN(percent) || percent < 0.0 || percent > 100.0) {
                logger.warning("[CHRDK_Treasures] " + FILE_NAME + ": шанс " + key
                        + " должен быть числом 0..100 — пропускаю.");
                continue;
            }
            values.put(tier, percent);
        }
    }

    public synchronized OptionalDouble get(TreasureTier tier) {
        Double value = tier == null ? null : values.get(tier);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    public synchronized Map<TreasureTier, Double> asMap() {
        EnumMap<TreasureTier, Double> copy = new EnumMap<>(TreasureTier.class);
        copy.putAll(values);
        return Collections.unmodifiableMap(copy);
    }

    public synchronized int size() {
        return values.size();
    }

    /**
     * Записывает шанс и сохраняет файл.
     *
     * @return итоговое (обрезанное) значение
     */
    public synchronized double set(TreasureTier tier, double percent) {
        if (tier == null) {
            return 0.0;
        }
        double clamped = Math.min(100.0, Math.max(0.0, percent));
        values.put(tier, clamped);
        save();
        return clamped;
    }

    /** Убирает переопределение и возвращает {@code true}, если оно было. */
    public synchronized boolean clear(TreasureTier tier) {
        if (tier == null || values.remove(tier) == null) {
            return false;
        }
        save();
        return true;
    }

    /**
     * Пишет файл вручную: здесь только иды редкостей и числа, поэтому
     * генератор YAML не нужен, а комментарии-подсказки остаются на месте.
     */
    private void save() {
        StringBuilder text = new StringBuilder();
        text.append("# CHRDK_Treasures — переопределения шансов.\n")
                .append("# Значения в процентах на блок; заменяют config.yml только для указанных редкостей.\n")
                .append("# Управление: /treasuresadmin chance <редкость> <процент|reset>.\n");
        if (values.isEmpty()) {
            text.append("# Сейчас переопределений нет — все шансы берутся из config.yml.\n");
        } else {
            text.append("chances:\n");
            for (Map.Entry<TreasureTier, Double> entry : values.entrySet()) {
                text.append("  ").append(entry.getKey().id()).append(": ")
                        .append(entry.getValue()).append('\n');
            }
        }
        SafeJsonWriter.write(file, text.toString(), logger, "[CHRDK_Treasures]");
    }
}
