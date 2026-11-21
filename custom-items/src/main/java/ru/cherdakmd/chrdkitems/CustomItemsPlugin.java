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
    private MudShovel mudShovel;

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("catalog.yml", false);
        saveResource(ResourcePackSettings.PACK_FILE, false);
        var yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "catalog.yml"));
            catalog = BuiltinItems.merge(ItemCatalog.load(yaml));
            getLogger().info("Experimental catalog: " + catalog.size()
                    + " definitions validated; test properties are opt-in, recipes are not registered.");
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException e) {
            getLogger().severe("Invalid item catalog; addon disabled: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        mudShovel = new MudShovel(this);
        mudShovel.start();
        try {
            var pack = ResourcePackSettings.parse(getConfig(), getDataFolder().toPath().resolve(ResourcePackSettings.PACK_FILE));
            if (pack != null) {
                var delivery = new ResourcePackDelivery(this, pack);
                getServer().getPluginManager().registerEvents(delivery, this);
                getServer().getOnlinePlayers().forEach(delivery::schedule);
                getLogger().info("Resource pack delivery enabled for joining players.");
            }
        } catch (IllegalArgumentException e) {
            getLogger().warning(e.getMessage() + "; automatic pack delivery disabled.");
        }
    }

    @Override public void onDisable() {
        if (mudShovel != null) mudShovel.stop();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("customitems")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("use")) {
                if (!sender.hasPermission("chrdkcustomitems.use")) sender.sendMessage(Component.text("Нет права на использование прототипов."));
                else if (!(sender instanceof Player player)) sender.sendMessage(Component.text("Использование доступно только игроку."));
                else PrototypeUse.use(player, catalog, getConfig().getBoolean("prototype-properties", false));
                return true;
            }
            return new CatalogCommand(catalog).execute(sender, args);
        }
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
        int slot = firstFreeStorage(i -> {
            var current = inventory.getItem(i);
            return current != null && !current.getType().isAir();
        });
        if (slot < 0) {
            player.sendMessage(Component.text("Нет свободной ячейки инвентаря; предмет не выдан."));
            return true;
        }
        org.bukkit.inventory.ItemStack item;
        try {
            item = PrototypeItems.create(definition, getConfig().getBoolean("prototype-textures", false));
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

    static int firstFreeStorage(java.util.function.IntPredicate occupied) {
        for (int i = 0; i < 36; i++) {
            if (!occupied.test(i)) return i;
        }
        return -1;
    }
}
