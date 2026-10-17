package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Update ONLY exact stock values; never replace owner customisations, schedules or pinned names. */
final class PresentationMigration {
    static final List<String> PATHS = List.of("server-menu.items", "auto-messages.messages",
            "auto-messages.prefix", "tablist.header", "tablist.footer", "tablist.player-name-format");
    static List<String> replacements(YamlConfiguration live, YamlConfiguration legacy, YamlConfiguration fresh) {
        List<String> out = new ArrayList<>();
        for (String key : PATHS) {
            // Ignore resource defaults: only actual on-disk old stock values qualify.
            if (live.contains(key, true) && Objects.equals(live.get(key), legacy.get(key))
                    && !Objects.equals(live.get(key), fresh.get(key))) out.add(key);
        }
        return out;
    }
    static void apply(JavaPlugin plugin) {
        Path config = plugin.getDataFolder().toPath().resolve("config.yml");
        try (InputStream old = plugin.getResource("presentation-legacy.yml"); InputStream latest = plugin.getResource("config.yml")) {
            if (old == null || latest == null || !Files.exists(config)) return;
            YamlConfiguration live = YamlConfiguration.loadConfiguration(config.toFile());
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(new InputStreamReader(old, java.nio.charset.StandardCharsets.UTF_8));
            YamlConfiguration fresh = YamlConfiguration.loadConfiguration(new InputStreamReader(latest, java.nio.charset.StandardCharsets.UTF_8));
            List<String> paths = replacements(live, legacy, fresh);
            if (paths.isEmpty()) return;
            Path backup = config.resolveSibling("config-before-presentation-1.4.70-" + System.currentTimeMillis() + ".yml");
            Files.copy(config, backup); // fail BEFORE changing anything if backup cannot be written
            for (String key : paths) live.set(key, fresh.get(key));
            Path temp = config.resolveSibling("config-presentation.tmp");
            live.save(temp.toFile());
            try { Files.move(temp, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, config, StandardCopyOption.REPLACE_EXISTING); }
            plugin.reloadConfig();
            plugin.getLogger().info("Updated stock presentation: " + paths + ". Backup: " + backup.getFileName());
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Presentation migration skipped; existing configuration retained", e);
        }
    }
}
