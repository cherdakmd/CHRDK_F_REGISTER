package ru.cherdakmd.diamondshop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.storage.ChrdkStorageFileSystem;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import java.util.logging.Level;

/** Заменяет только неизменённый стоковый каталог; цены владельца и удалённые товары не воскрешает. */
final class ShopCatalogMigration {
    private ShopCatalogMigration() { }
    static boolean shouldUpgrade(YamlConfiguration live, YamlConfiguration legacy, YamlConfiguration fresh) {
        return live.contains("items",true) && Objects.equals(live.get("items"),legacy.get("items"))
                && !Objects.equals(live.get("items"),fresh.get("items"));
    }
    static void apply(JavaPlugin plugin) {
        Path file = ru.cherdakmd.chrdkregister.storage.ChrdkStoragePaths.dataFolder(plugin).resolve("config.yml");
        try (var old = plugin.getResource("catalog-legacy-1.4.244.yml"); var previous = plugin.getResource("catalog-legacy-1.4.249.yml"); var latest = plugin.getResource("config.yml")) {
            if (old == null || previous == null || latest == null || !Files.isRegularFile(file)) return;
            var legacy = new YamlConfiguration(); var fresh = new YamlConfiguration(); var live = new YamlConfiguration();
            legacy.load(new InputStreamReader(old,StandardCharsets.UTF_8));
            fresh.load(new InputStreamReader(latest,StandardCharsets.UTF_8));
            var prior = new YamlConfiguration();
            prior.load(new InputStreamReader(previous,StandardCharsets.UTF_8));
            // DB-aware: StoragePath materializes в temp, поэтому читаем content и парсим из Reader
            if (file instanceof ChrdkStorageFileSystem.StoragePath) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                YamlConfiguration loaded = YamlConfiguration.loadConfiguration(new StringReader(content));
                live.set("items", loaded.get("items"));
            } else {
                live.load(file.toFile()); // Строгий parse: повреждённый YAML не перезаписываем.
            }
            if (!shouldUpgrade(live,legacy,fresh) && !shouldUpgrade(live,prior,fresh)) {
                if (!Objects.equals(live.get("items"),fresh.get("items")))
                    plugin.getLogger().info("Базовые записи пользовательского каталога сохранены. Тариф покупки pricing.purchase-multipliers применяется отдельно; новые товары при необходимости перенесите из config.yml внутри JAR.");
                return;
            }
            Path backup = file.resolveSibling("config-before-catalog-1.4.250-"+System.currentTimeMillis()+".yml");
            Files.copy(file,backup); // Сначала успешная резервная копия, затем изменение.
            live.set("items",fresh.get("items"));
            Path temp = file.resolveSibling("config-catalog.tmp");
            // DB-aware: StoragePath.save(temp.toFile()) не пишет обратно в БД, поэтому используем saveToString
            if (file instanceof ChrdkStorageFileSystem.StoragePath) {
                String yamlContent = live.saveToString();
                Files.writeString(temp, yamlContent, StandardCharsets.UTF_8);
            } else {
                live.save(temp.toFile());
            }
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            plugin.reloadConfig();
            plugin.getLogger().info("Стоковый каталог обновлён до 248 товаров. Резервная копия: "+backup.getFileName());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,"Каталог не обновлён автоматически; проверьте config.yml и резервную копию.",e);
        }
    }
}
