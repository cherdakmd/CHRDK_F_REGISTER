package ru.cherdakmd.chrdkregister;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/** Stock examples are used only to recognize virtual menu icons, never to rewrite config. */
final class ServerMenuEntryVisuals {
    private record Signature(Material icon, String name, String lore, String command,
                             String plugin, String permission, String category) {
        static Signature of(ServerMenuSettings.Entry entry) {
            return new Signature(entry.icon(), entry.name(), entry.lore(), entry.command(),
                    entry.plugin(), entry.permission(), entry.category());
        }
    }

    private final Map<Signature, NamespacedKey> models;
    private ServerMenuEntryVisuals(Map<Signature, NamespacedKey> models) {
        this.models = Map.copyOf(models);
    }

    static ServerMenuEntryVisuals load(JavaPlugin plugin) {
        try (InputStream stream = plugin.getResource("server-menu-visuals.yml")) {
            if (stream == null) throw new IllegalStateException("Missing server-menu-visuals.yml");
            return from(YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Menu entry visuals unavailable; using configured icons", e);
            return new ServerMenuEntryVisuals(Map.of());
        }
    }

    static ServerMenuEntryVisuals from(FileConfiguration config) {
        var raw = config.getMapList("server-menu.items");
        var entries = ServerMenuSettings.from(config).entries();
        if (raw.size() != entries.size()) throw new IllegalArgumentException("Invalid menu visual examples");
        Map<Signature, NamespacedKey> models = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Object value = raw.get(i).get("visual");
            if (!(value instanceof String id) || !id.matches("[a-z0-9_]+/[a-z0-9_]+") || !ids.add(id))
                throw new IllegalArgumentException("Invalid or duplicate menu visual id");
            var model = new NamespacedKey("chrdk_menu", "entry/" + id);
            if (models.putIfAbsent(Signature.of(entries.get(i)), model) != null)
                throw new IllegalArgumentException("Ambiguous menu visual example");
        }
        return new ServerMenuEntryVisuals(models);
    }

    NamespacedKey model(ServerMenuSettings.Entry entry) {
        return entry == null ? null : models.get(Signature.of(entry));
    }
}
