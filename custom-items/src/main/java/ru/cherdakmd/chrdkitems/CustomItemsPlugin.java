package ru.cherdakmd.chrdkitems;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/** Experimental addon, currently metadata validation only. */
public final class CustomItemsPlugin extends JavaPlugin {
    @Override public void onEnable() {
        saveResource("catalog.yml", false);
        var yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "catalog.yml"));
            var catalog = ItemCatalog.load(yaml);
            getLogger().info("Experimental catalog: " + catalog.size()
                    + " definitions validated; no items, recipes or effects are registered.");
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            getLogger().severe("Invalid item catalog; addon disabled: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }
}
