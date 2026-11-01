package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Аддитивные SKU и точное обновление стоковых приветствий, без изменения старых покупок. */
final class PremiumPresentationMigration {
    static final Set<String> PRODUCTS = Set.of("clan-name-style", "clan-emblem", "clan-card-frame");

    static boolean update(YamlConfiguration live, YamlConfiguration old, YamlConfiguration fresh) {
        boolean changed = false;
        if (Objects.equals(live.get("ranks.levels"), old.get("ranks.levels"))) {
            live.set("ranks.levels", fresh.get("ranks.levels")); changed = true;
        }
        // Собственный каталог не заменяется; только отсутствующие новые SKU добавляются в конец.
        if (live.isList("shop.products")) {
            List<Map<?, ?>> products = new ArrayList<>(live.getMapList("shop.products"));
            Set<Object> present = new HashSet<>();
            products.forEach(product -> present.add(product.get("id")));
            for (Map<?, ?> product : fresh.getMapList("shop.products")) {
                if (PRODUCTS.contains(String.valueOf(product.get("id"))) && !present.contains(product.get("id"))) {
                    products.add(new LinkedHashMap<>(product)); changed = true;
                }
            }
            if (changed) live.set("shop.products", products);
        }
        return changed;
    }

    static void apply(JavaPlugin plugin) {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        try (var oldStream = plugin.getResource("premium-legacy-1.4.277.yml"); var freshStream = plugin.getResource("config.yml")) {
            if (oldStream == null || freshStream == null || !Files.isRegularFile(file)) return;
            var live = YamlConfiguration.loadConfiguration(file.toFile());
            var old = YamlConfiguration.loadConfiguration(new InputStreamReader(oldStream, StandardCharsets.UTF_8));
            var fresh = YamlConfiguration.loadConfiguration(new InputStreamReader(freshStream, StandardCharsets.UTF_8));
            if (!update(live, old, fresh)) return;
            Files.copy(file, file.resolveSibling("config-before-premium-1.4.278-" + System.currentTimeMillis() + ".yml"));
            if (SafeJsonWriter.write(file, live.saveToString(), plugin.getLogger(), "[Оформление]")) plugin.reloadConfig();
        } catch (Exception error) {
            plugin.getLogger().warning("Обновление оформления пропущено, конфиг сохранён: " + error.getClass().getSimpleName());
        }
    }
}
