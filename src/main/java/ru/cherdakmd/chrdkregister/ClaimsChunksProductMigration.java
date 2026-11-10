package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Аддитивная миграция одного стокового SKU «+8 чанков привата навсегда».
 *
 * <p>Добавляет товар в конец {@code shop.products} только если его там ещё нет.
 * Цены, сроки, описания и произвольные SKU владельца не перезаписываются:
 * редактируется ровно одна отсутствующая запись. Повторный запуск ничего не
 * меняет. Миграция не выдаёт покупки и не трогает {@code claims-data.json}.</p>
 *
 * <p>Как и у прочих стоковых товаров, удаление SKU владельцем не сохраняется —
 * чтобы убрать товар из витрины, выключите {@code shop.enabled} или уберите
 * команду выдачи, не удаляя запись целиком.</p>
 */
final class ClaimsChunksProductMigration {
    static final String PRODUCT_ID = "claims-chunks-plus";

    static boolean update(YamlConfiguration live, YamlConfiguration fresh) {
        if (live == null || fresh == null
                || !live.isList("shop.products") || !fresh.isList("shop.products")) {
            return false;
        }
        List<Map<?, ?>> products = new ArrayList<>(live.getMapList("shop.products"));
        for (Map<?, ?> product : products) {
            if (PRODUCT_ID.equals(String.valueOf(product.get("id")))) {
                return false;
            }
        }
        for (Map<?, ?> product : fresh.getMapList("shop.products")) {
            if (PRODUCT_ID.equals(String.valueOf(product.get("id")))) {
                products.add(new LinkedHashMap<>(product));
                live.set("shop.products", products);
                return true;
            }
        }
        return false;
    }

    static void apply(JavaPlugin plugin) {
        Path file = ru.cherdakmd.chrdkregister.storage.ChrdkStoragePaths.dataFolder(plugin).resolve("config.yml");
        try (var freshStream = plugin.getResource("config.yml")) {
            if (freshStream == null || !Files.isRegularFile(file)) return;
            var live = YamlConfiguration.loadConfiguration(file.toFile());
            var fresh = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(freshStream, StandardCharsets.UTF_8));
            if (!update(live, fresh)) return;
            Files.copy(file, file.resolveSibling(
                    "config-before-claims-chunks-" + System.currentTimeMillis() + ".yml"));
            if (SafeJsonWriter.write(file, live.saveToString(), plugin.getLogger(), "[Чанки]")) {
                plugin.reloadConfig();
            }
        } catch (Exception error) {
            plugin.getLogger().warning("Добавление SKU чанков пропущено, конфиг сохранён: "
                    + error.getClass().getSimpleName());
        }
    }

    private ClaimsChunksProductMigration() { }
}
