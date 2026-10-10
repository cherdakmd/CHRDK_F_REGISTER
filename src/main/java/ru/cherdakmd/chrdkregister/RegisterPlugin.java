package ru.cherdakmd.chrdkregister;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Main Purpur 1.21.11 plugin entry point for CHRDK_F_REGISTER.
 */
public final class RegisterPlugin extends JavaPlugin {
    private AccessManager accessManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginConfig config = PluginConfig.fromBukkitConfig(getConfig());
        RegistrationStore store = new RegistrationStore(getDataFolder().toPath(), getLogger());
        DonatePayClient donatePayClient = new DonatePayClient(config);

        this.accessManager = new AccessManager(this, config, store, donatePayClient, getLogger());

        getServer().getPluginManager().registerEvents(new PlayerRestrictionListener(accessManager), this);

        RegisterCommandHandler commandHandler = new RegisterCommandHandler(this, accessManager);
        for (String commandName : List.of("register", "login", "changepassword", "pass")) {
            PluginCommand pluginCommand = getCommand(commandName);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(commandHandler);
                pluginCommand.setTabCompleter(commandHandler);
            }
        }

        accessManager.start();
        getLogger().info("CHRDK_F_REGISTER enabled for Purpur 1.21.11.");
    }

    @Override
    public void onDisable() {
        if (accessManager != null) {
            accessManager.shutdown();
            accessManager = null;
        }
        getLogger().info("CHRDK_F_REGISTER disabled.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        PluginConfig updated = PluginConfig.fromBukkitConfig(getConfig());
        if (accessManager != null) {
            accessManager.reload(updated);
        }
    }
}
