package ru.cherdakmd.chrdkbp;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.function.Function;

/** Только weeks точного прежнего стока. Никакого getConfig/default fallback или записи прогресса/receipts. */
final class BattlePassStockMigration {
    static final List<String> LEGACIES = List.of("battlepass-default-legacy-1.4.280.yml",
            "battlepass-default-legacy-1.4.282.yml", "battlepass-default-legacy-1.4.282-money-rc3.yml");
    record Result(boolean updated, Path backup) { }
    enum Phase { BEFORE_BACKUP, AFTER_BACKUP, BEFORE_REPLACE, AFTER_REPLACE }
    @FunctionalInterface interface Fault { void at(Phase phase) throws IOException; }
    private BattlePassStockMigration() { }

    static Result apply(Path file, Function<String,InputStream> resources) throws IOException, InvalidConfigurationException {
        return apply(file, resources, phase -> { });
    }
    static Result apply(Path file, Function<String,InputStream> resources, Fault fault) throws IOException, InvalidConfigurationException {
        // loadConfiguration() проглатывает синтаксическую ошибку и возвращает пустую конфигурацию.
        // Здесь это недопустимо: нельзя незаметно заменить испорченный файл значениями JAR.
        String original = Files.readString(file, StandardCharsets.UTF_8);
        var live = new YamlConfiguration(); live.loadFromString(original);
        var fresh = resource(resources, "config.yml");
        if (!live.contains("battlepass.weeks", true)) throw new InvalidConfigurationException("Missing explicit battlepass.weeks; defaults must not replace owner data");
        if (!live.isString("battlepass.season-start")) throw new InvalidConfigurationException("Missing explicit season-start; receipt namespace must not fall back to JAR defaults");
        try { BattlePassConfig.season("validation", live.getString("battlepass.season-start"), live.getString("battlepass.season-end", ""), List.of()); }
        catch (IllegalStateException error) { throw new InvalidConfigurationException(error.getMessage()); }
        Object current = live.get("battlepass.weeks");
        if (!(current instanceof List<?> existing) || existing.stream().anyMatch(row -> !(row instanceof java.util.Map<?,?>)))
            throw new InvalidConfigurationException("battlepass.weeks must be a list of maps (an empty list disables the season)");
        boolean stock = false;
        for (String name : LEGACIES) {
            var legacy = resource(resources, name);
            if (BattlePassConfig.stockUpgrade(current, legacy.get("battlepass.weeks"))) stock = true;
        }
        if (!stock || java.util.Objects.equals(current, fresh.get("battlepass.weeks"))) return new Result(false, null);
        if (!(fresh.get("battlepass.weeks") instanceof List<?> rows) || rows.isEmpty()) throw new IOException("Empty packaged BP weeks");
        if (Files.isSymbolicLink(file)) throw new IOException("Stock BP config is a symbolic link; migrate manually without replacing the link");
        live.set("battlepass.weeks", fresh.get("battlepass.weeks"));
        // Имя уникально, а backup — точные исходные байты, включая комментарии владельца.
        Path parent = file.toAbsolutePath().getParent();
        Path backup = parent.resolve("config-before-battlepass-1.4.282-" + java.util.UUID.randomUUID() + ".yml");
        fault.at(Phase.BEFORE_BACKUP);
        Files.copy(file, backup); force(backup); force(parent);
        fault.at(Phase.AFTER_BACKUP);
        Path temp = Files.createTempFile(parent, "config-battlepass-1.4.282-", ".tmp");
        try {
            Files.writeString(temp, live.saveToString(), StandardCharsets.UTF_8); force(temp);
            // Не затираем правку, внесённую владельцем за время подготовки backup.
            fault.at(Phase.BEFORE_REPLACE);
            if (!Files.readString(file, StandardCharsets.UTF_8).equals(original)) throw new IOException("BP config changed during migration; retry reload");
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fault.at(Phase.AFTER_REPLACE);
            force(parent);
            return new Result(true, backup);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
    private static YamlConfiguration resource(Function<String,InputStream> resources, String name) throws IOException, InvalidConfigurationException {
        try (InputStream input = resources.apply(name)) {
            if (input == null) throw new IOException("Missing BP migration resource: " + name);
            var yaml = new YamlConfiguration();
            yaml.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return yaml;
        }
    }
    private static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, Files.isDirectory(file) ? StandardOpenOption.READ : StandardOpenOption.WRITE)) { channel.force(true); }
    }
}
