package ru.cherdakmd.chrdkregister;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * Main Purpur 1.21.11 plugin entry point for CHRDK_F_REGISTER.
 */
public final class RegisterPlugin extends JavaPlugin {
    private AccessManager accessManager;
    private ChatService chatService;
    private RegistrationStore store;
    private ExecutionService executor;
    private RegisterCommandHandler commandHandler;
    private volatile Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginConfig config = PluginConfig.fromBukkitConfig(getConfig());
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());

        this.executor = new ExecutionService(getLogger());
        this.store = new RegistrationStore(getDataFolder().toPath(), getLogger(),
                config.storageWriteAsync() ? executor.taskExecutor() : null);

        DonatePayClient donatePayClient = new DonatePayClient(config);

        this.accessManager = new AccessManager(this, config, store, donatePayClient, messages, getLogger());
        this.chatService = new ChatService(this, accessManager, config.chat(), messages, getLogger());

        getServer().getPluginManager().registerEvents(new PlayerRestrictionListener(accessManager), this);
        getServer().getPluginManager().registerEvents(chatService, this);

        this.commandHandler = new RegisterCommandHandler(this, accessManager);
        for (String commandName : List.of("register", "login", "changepassword", "2fa", "pass", "logout", "session")) {
            PluginCommand pluginCommand = getCommand(commandName);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(this.commandHandler);
                pluginCommand.setTabCompleter(commandHandler);
            }
        }

        ChatCommandHandler chatCommandHandler = new ChatCommandHandler(chatService);
        for (String commandName : List.of("chat", "msg", "reply", "ignore", "unignore", "socialspy", "mute", "unmute")) {
            PluginCommand pluginCommand = getCommand(commandName);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(chatCommandHandler);
                pluginCommand.setTabCompleter(chatCommandHandler);
            }
        }

        accessManager.start();
        chatService.start();
        enableMetrics(config);
        getLogger().info("CHRDK_F_REGISTER enabled for Purpur 1.21.11 (pass, password auth, TOTP 2FA, chat, "
                + "brute-force protection, donation journal).");
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
        if (executor != null) {
            executor.shutdown();
            executor = null;
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

    /** Тексты плагина из {@code messages.yml}. */
    public Messages messages() {
        return messages;
    }

    /**
     * Перезагружает {@code messages.yml} и возвращает список найденных проблем
     * (неизвестные ключи, ошибки разметки MiniMessage, потерянные подстановки).
     */
    public java.util.List<String> reloadMessages() {
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        if (accessManager != null) {
            accessManager.setMessages(messages);
        }
        if (chatService != null) {
            chatService.setMessages(messages);
        }
        if (commandHandler != null) {
            commandHandler.refreshMessages(); // /pass reload сам сообщает результат уже новыми текстами
        }
        return messages.validate();
    }

    /** Flushes the storage and creates a manual backup (used by {@code /pass backup}). */
    public java.util.Optional<java.nio.file.Path> createBackup(String reason) {
        if (store == null) return java.util.Optional.empty();
        return store.createBackup(reason, PluginConfig.fromBukkitConfig(getConfig()).storageBackupCount());
    }

    public RegistrationStore store() {
        return store;
    }

    /**
     * Wires optional bStats metrics. A numeric plugin ID is required and must be obtained from bstats.org;
     * when it is not configured (or bStats is missing) the plugin simply continues without metrics.
     */
    private void enableMetrics(PluginConfig config) {
        if (!config.bStatsEnabled()) return;
        int pluginId = getConfig().getInt("metrics.bstats-id", 0);
        if (pluginId <= 0) {
            getLogger().info("bStats is enabled in the config but metrics.bstats-id is not set — skipping metrics.");
            return;
        }
        try {
            Class<?> metricsClass = Class.forName("org.bstats.bukkit.Metrics");
            metricsClass.getConstructor(org.bukkit.plugin.Plugin.class, int.class)
                    .newInstance(this, pluginId);
        } catch (Throwable throwable) {
            getLogger().log(Level.FINE, "bStats is not available in this build", throwable);
        }
    }

    /** Shared executor that runs store writes off the main thread. */
    public static final class ExecutionService {
        private final java.util.concurrent.ExecutorService service;

        ExecutionService(java.util.logging.Logger logger) {
            this.service = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "CHRDK-storage");
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((thread1, error) ->
                        logger.log(Level.WARNING, "Storage write failed", error));
                return thread;
            });
        }

        public Executor taskExecutor() {
            return service;
        }

        public void shutdown() {
            service.shutdown();
        }
    }
}
