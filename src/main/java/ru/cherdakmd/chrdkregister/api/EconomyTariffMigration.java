package ru.cherdakmd.chrdkregister.api;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Однократная смена только известных стоковых тарифов, не финансовых данных. */
public final class EconomyTariffMigration {
    private static final String MARKER = "economy-tariff-version";
    private EconomyTariffMigration() { }
    public record Change(Number previous, Number current) { }

    /** Публично для проверки без Bukkit-сервера; отсутствие ключа оставляет новый fallback. */
    public static int upgrade(YamlConfiguration config, Map<String, Change> changes) {
        if (config.getInt(MARKER, 0) >= 253) return 0;
        int changed = 0;
        for (var entry : changes.entrySet()) {
            Object value = config.get(entry.getKey());
            if (value instanceof Number number && same(number, entry.getValue().previous())) {
                config.set(entry.getKey(), entry.getValue().current()); changed++;
            }
        }
        config.set(MARKER, 253);
        return changed;
    }
    private static boolean same(Number a, Number b) {
        try { return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0; }
        catch (NumberFormatException e) { return false; }
    }
    public static Map<String, Change> changes(String plugin) {
        var result = new LinkedHashMap<String, Change>();
        switch (plugin) {
            case "CHRDK_Core" -> result.put("economy.pay-tax-percent", new Change(0, 2));
            case "CHRDK_Teleports" -> {
                result.put("costs.rtp-diamonds", new Change(5, 60));
                result.put("costs.tpa-diamonds", new Change(2, 40));
                result.put("costs.home-diamonds", new Change(1, 30));
                result.put("costs.sethome-diamonds", new Change(0, 20));
            }
            case "CHRDK_Claims" -> {
                result.put("pricing.chunk-price", new Change(16, 160));
                result.put("pricing.upkeep-per-day", new Change(1, 10));
                result.put("teleport.cost-diamonds", new Change(1, 20));
                result.put("taxes.sale-tax-percent", new Change(10, 15));
            }
            case "CHRDK_Auction" -> {
                result.put("market.min-price", new Change(20, 100));
                result.put("market.fee-percent", new Change(5, 10));
            }
            case "CHRDK_Guilds" -> {
                result.put("general.creation-cost", new Change(27, 270));
                result.put("wars.min-wager", new Change(32, 320));
                result.put("wars.max-wager", new Change(1024, 10240));
            }
            default -> { }
        }
        return Map.copyOf(result);
    }
    public static void apply(JavaPlugin plugin) {
        var changes = changes(plugin.getName());
        if (changes.isEmpty()) return;
        if (migrateFile(plugin.getDataFolder().toPath().resolve("config.yml"), changes, plugin.getLogger()))
            plugin.reloadConfig();
    }
    /** Запись с backup перед заменой; испорченный YAML и ошибки диска не превращаются в дешёвый fallback. */
    public static boolean migrateFile(Path file, Map<String, Change> changes, java.util.logging.Logger logger) {
        try {
            if (!Files.isRegularFile(file)) return false;
            var config = new YamlConfiguration(); config.load(file.toFile());
            if (config.getInt(MARKER, 0) >= 253) return false;
            int count = upgrade(config, changes);
            Path backup = file.resolveSibling("config-before-economy-1.4.253-" + System.currentTimeMillis() + ".yml");
            var view = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            var permissions = view == null ? null : view.readAttributes().permissions();
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
            Path temp = file.resolveSibling("config-economy.tmp");
            // В Core config могут быть токены. Не расширяем права даже на временный файл.
            if (permissions != null) {
                if (!Files.exists(temp, LinkOption.NOFOLLOW_LINKS))
                    Files.createFile(temp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(permissions));
                if (!Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS))
                    throw new java.io.IOException("Временный config не является обычным файлом");
                Files.setPosixFilePermissions(temp, permissions);
            }
            config.save(temp.toFile());
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            logger.info("Экономика 1.4.253: обновлено стоковых тарифов " + count
                    + "; отличающиеся значения сохранены. Копия: " + backup.getFileName());
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Тарифы не изменены надёжно; запуск/перезагрузка остановлены. Проверьте config.yml и его копию.", e);
        }
    }
}
