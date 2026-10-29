package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Обновляет ТОЛЬКО точные стоковые значения презентационных секций: пункты меню,
 * автосообщения и таб-лист. Владельческие правки, расписания и закреплённые ники
 * не трогаются: если значение в конфиге отличается от любого известного стокового
 * снимка, оно остаётся как есть.
 *
 * <p>Снимков несколько — по одному на прошлые релизы ({@link #LEGACY_RESOURCES}).
 * Так сервер, который обновился не сразу, всё равно получает новый стоковый набор:
 * сравнение идёт с каждым известным вариантом, а не только с самым старым.</p>
 *
 * <p>Пункты меню и его новая структура (категории, заголовки, кнопка «Назад»)
 * обновляются одним пакетом ({@link #MENU_COMPANIONS}): новые пункты никогда не
 * окажутся без категорий.</p>
 */
final class PresentationMigration {

    static final List<String> PATHS = List.of("server-menu.items", "server-menu.categories",
            "auto-messages.messages", "auto-messages.prefix", "tablist.header", "tablist.footer",
            "tablist.player-name-format");

    /** Настройки меню, которые должны обновиться вместе с его пунктами. */
    static final List<String> MENU_COMPANIONS = List.of("server-menu.title",
            "server-menu.category-title", "server-menu.back-slot", "server-menu.back-icon",
            "server-menu.back-name", "server-menu.close-slot", "server-menu.close-icon",
            "server-menu.close-name", "server-menu.categories");

    /**
     * Стоковые снимки прошлых версий: от нового к старому. Снимок 1.4.185 —
     * меню из 52 пунктов со старой подсказкой «Оформление ника»; снимок 1.4.182 —
     * меню с «Вики сервера», но без кнопки «Оформление ника» (51 пункт);
     * снимок 1.4.170 — без обеих (50 пунктов); снимок 1.4.169 — последний со
     * старой плоской структурой (6 разделов, 42 пункта).
     */
    static final List<String> LEGACY_RESOURCES = List.of("presentation-legacy-1.4.240.yml", "presentation-legacy-1.4.239.yml", "presentation-legacy-1.4.238.yml", "presentation-legacy-1.4.192.yml", "presentation-legacy-1.4.185.yml",
            "presentation-legacy-1.4.182.yml", "presentation-legacy-1.4.170.yml",
            "presentation-legacy-1.4.169.yml", "presentation-legacy-pre-1.4.138.yml",
            "presentation-legacy.yml");

    private PresentationMigration() {
    }

    /** Совместимость с одним снимком (используется тестами). */
    static List<String> replacements(YamlConfiguration live, YamlConfiguration legacy, YamlConfiguration fresh) {
        return replacements(live, List.of(legacy), fresh);
    }

    static List<String> replacements(YamlConfiguration live, List<YamlConfiguration> legacies,
                                     YamlConfiguration fresh) {
        List<String> out = new ArrayList<>();
        if (live == null || fresh == null) {
            return out;
        }
        for (String key : PATHS) {
            if (!live.contains(key, true)) {
                continue;
            }
            Object value = live.get(key);
            boolean stock = false;
            for (YamlConfiguration legacy : legacies) {
                if (legacy != null && legacy.contains(key, true) && Objects.equals(value, legacy.get(key))) {
                    stock = true;
                    break;
                }
            }
            if (stock && !Objects.equals(value, fresh.get(key))) {
                out.add(key);
            }
        }
        // Меню обновляется целиком: пункты + категории + заголовки и «Назад».
        if (out.contains("server-menu.items")) {
            for (String companion : MENU_COMPANIONS) {
                if (!out.contains(companion) && fresh.contains(companion, true)) {
                    out.add(companion);
                }
            }
        }
        return out;
    }

    static void apply(JavaPlugin plugin) {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        try (InputStream latest = plugin.getResource("config.yml")) {
            if (latest == null || !Files.exists(config)) {
                return;
            }
            List<YamlConfiguration> legacies = new ArrayList<>();
            for (String resource : LEGACY_RESOURCES) {
                try (InputStream old = plugin.getResource(resource)) {
                    if (old != null) {
                        legacies.add(YamlConfiguration.loadConfiguration(
                                new InputStreamReader(old, java.nio.charset.StandardCharsets.UTF_8)));
                    }
                }
            }
            if (legacies.isEmpty()) {
                return;
            }
            YamlConfiguration live = YamlConfiguration.loadConfiguration(config.toFile());
            YamlConfiguration fresh = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(latest, java.nio.charset.StandardCharsets.UTF_8));
            List<String> paths = replacements(live, legacies, fresh);
            if (paths.isEmpty()) {
                return;
            }
            Path backup = config.resolveSibling("config-before-presentation-1.4.138-"
                    + System.currentTimeMillis() + ".yml");
            Files.copy(config, backup); // fail BEFORE changing anything if backup cannot be written
            for (String key : paths) {
                live.set(key, fresh.get(key));
            }
            Path temp = config.resolveSibling("config-presentation.tmp");
            live.save(temp.toFile());
            try {
                Files.move(temp, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, config, StandardCopyOption.REPLACE_EXISTING);
            }
            plugin.reloadConfig();
            plugin.getLogger().info("Updated stock presentation: " + paths + ". Backup: " + backup.getFileName());
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Presentation migration skipped; existing configuration retained", e);
        }
    }
}
