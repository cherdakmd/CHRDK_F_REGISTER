package ru.cherdakmd.chrdkregister;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Main Purpur 1.21.11 plugin entry point for CHRDK_F_REGISTER.
 */
public final class RegisterPlugin extends JavaPlugin {
    private AccessManager accessManager;
    private ChatService chatService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginConfig config = PluginConfig.fromBukkitConfig(getConfig());
        RegistrationStore store = new RegistrationStore(getDataFolder().toPath(), getLogger());
        DonatePayClient donatePayClient = new DonatePayClient(config);

        this.accessManager = new AccessManager(this, config, store, donatePayClient, getLogger());
        this.chatService = new ChatService(this, accessManager, config.chat(), getLogger());

        getServer().getPluginManager().registerEvents(new PlayerRestrictionListener(accessManager), this);
        getServer().getPluginManager().registerEvents(chatService, this);

        RegisterCommandHandler commandHandler = new RegisterCommandHandler(this, accessManager);
        for (String commandName : List.of("register", "login", "changepassword", "2fa", "pass")) {
            PluginCommand pluginCommand = getCommand(commandName);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(commandHandler);
                pluginCommand.setTabCompleter(commandHandler);
            }
        }

        ChatCommandHandler chatCommandHandler = new ChatCommandHandler(chatService);
        for (String commandName : List.of("chat", "msg", "reply", "ignore", "unignore", "socialspy")) {
            PluginCommand pluginCommand = getCommand(commandName);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(chatCommandHandler);
                pluginCommand.setTabCompleter(chatCommandHandler);
            }
        }

        accessManager.start();
        chatService.start();
        getLogger().info("CHRDK_F_REGISTER enabled for Purpur 1.21.11 (pass, password auth, TOTP 2FA, chat).");
    }

    @Override
    public void onDisable() {
        if (chatService != null) {
            chatService.shutdown();
            chatService = null;
        }
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
        if (chatService != null) {
            chatService.reload(updated.chat());
        }
    }
}
