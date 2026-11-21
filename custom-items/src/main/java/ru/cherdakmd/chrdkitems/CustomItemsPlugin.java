package ru.cherdakmd.chrdkitems;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Experimental addon; catalog is loaded once and admin issuance is test-only. */
public final class CustomItemsPlugin extends JavaPlugin {
    private Map<String, ItemCatalog.Definition> catalog = Map.of();

    @Override public void onEnable() {
        saveResource("catalog.yml", false);
        var yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "catalog.yml"));
            catalog = ItemCatalog.load(yaml);
            getLogger().info("Experimental catalog: " + catalog.size()
                    + " definitions validated; no recipes, effects or resource pack are registered.");
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            getLogger().severe("Invalid item catalog; addon disabled: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chrdkcustomitems.admin")) {
            sender.sendMessage(Component.text("Нет права на выдачу прототипов."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Выдача доступна только игроку-администратору для себя."));
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("give")) return false;
        var definition = catalog.get(args[1]);
        if (definition == null) {
            player.sendMessage(Component.text("Неизвестный идентификатор в каталоге."));
            return true;
        }
        // Only an empty main-storage slot, no drop and no partial writes or merging.
        var inventory = player.getInventory();
        int slot = firstFreeStorage(inventory);
        if (slot < 0) {
            player.sendMessage(Component.text("Нет свободной ячейки инвентаря; предмет не выдан."));
            return true;
        }
        org.bukkit.inventory.ItemStack item;
        try {
            item = PrototypeItems.create(definition);
        } catch (RuntimeException e) {
            getLogger().warning("Prototype creation failed: " + e.getClass().getSimpleName());
            player.sendMessage(Component.text("Не удалось создать прототип; проверьте журнал."));
            return true;
        }
        inventory.setItem(slot, item);
        player.sendMessage(Component.text("Выдан тестовый прототип: " + definition.id()));
        getLogger().info("Prototype " + definition.id() + " issued to " + player.getUniqueId());
        return true;
    }

    static int firstFreeStorage(org.bukkit.inventory.PlayerInventory inventory) {
        for (int i = 0; i < 36; i++) {
            var item = inventory.getItem(i);
            if (item == null || item.getType().isAir()) return i;
        }
        return -1;
    }
}
