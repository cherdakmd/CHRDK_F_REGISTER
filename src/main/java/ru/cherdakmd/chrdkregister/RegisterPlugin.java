package ru.cherdakmd.chrdkregister;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * Main Purpur 1.21.11 plugin entry point for CHRDK_Core.
 */
public final class RegisterPlugin extends JavaPlugin {
    private AccessManager accessManager;
    private ChatService chatService;
    private PassMenuService passMenuService;
    private PasswordKeypadService passwordKeypad;
    private TabListService tabListService;
    private ExternalHooks externalHooks;
    private LuckPermsPassSync luckPermsPassSync;
    private RegistrationStore store;
    private ExecutionService executor;
    private RegisterCommandHandler commandHandler;
    private volatile Messages messages;

    /** Имя папки данных до переименования ядра (1.4.44). */
    private static final String LEGACY_DATA_FOLDER = "CHRDK_F_REGISTER";

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        PluginConfig config = PluginConfig.fromBukkitConfig(getConfig());
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());

        this.executor = new ExecutionService(getLogger());
        this.store = new RegistrationStore(getDataFolder().toPath(), getLogger(),
                config.storageWriteAsync() ? executor.taskExecutor() : null);

        DonatePayClient donatePayClient = new DonatePayClient(config);

        this.accessManager = new AccessManager(this, config, store, donatePayClient, messages, getLogger());
        this.chatService = new ChatService(this, accessManager, config.chat(), messages, getLogger());
        this.passMenuService = new PassMenuService(accessManager, messages);
        this.passwordKeypad = new PasswordKeypadService(this, accessManager, messages);
        this.tabListService = new TabListService(this, accessManager);
        this.externalHooks = new ExternalHooks(this);
        this.luckPermsPassSync = new LuckPermsPassSync(externalHooks, store, accessManager::config, getLogger());
        getServer().getServicesManager().register(ChrdkRegisterApi.class,
                new ChrdkRegisterApiImpl(this, accessManager, externalHooks), this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(new PlayerRestrictionListener(accessManager), this);
        getServer().getPluginManager().registerEvents(chatService, this);
        getServer().getPluginManager().registerEvents(passMenuService, this);
        getServer().getPluginManager().registerEvents(
                new MotdService(this, accessManager::config), this);
        getServer().getPluginManager().registerEvents(passwordKeypad, this);
        getServer().getPluginManager().registerEvents(tabListService, this);
        tabListService.reload();

        this.commandHandler = new RegisterCommandHandler(this, accessManager);
        for (String commandName : List.of("register", "login", "changepassword", "2fa", "pass", "logout", "session", "captcha")) {
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
        hookPlaceholderApi();
        enableMetrics(config);
        getLogger().info("CHRDK_Core enabled for Purpur 1.21.11 (pass, password auth, TOTP 2FA, chat, "
                + "brute-force protection, donation journal).");
    }

    /**
     * Переезд папки данных со старого имени плагина (CHRDK_F_REGISTER) на новое
     * (CHRDK_Core): запускается до всего остального; если новая папка уже есть —
     * ничего не трогаем (миграция одноразовая). Конфликт: залогирован.
     */
    private void migrateLegacyDataFolder() {
        java.io.File fresh = getDataFolder();
        java.io.File legacy = new java.io.File(getDataFolder().getParentFile(), LEGACY_DATA_FOLDER);
        if (fresh.exists() || !legacy.exists()) {
            return;
        }
        if (legacy.renameTo(fresh)) {
            getLogger().info("[migration] Папка данных " + LEGACY_DATA_FOLDER
                    + " переименована в " + fresh.getName() + ".");
        } else {
            getLogger().severe("[migration] НЕ УДАЛОСЬ переименовать папку " + LEGACY_DATA_FOLDER
                    + " в " + fresh.getName() + " — перенесите файлы вручную, иначе начнём с чистого хранилища!");
        }
    }

    @Override
    public void onDisable() {
        if (tabListService != null) {
            tabListService.shutdown();
            tabListService = null;
        }
        if (chatService != null) {
            chatService.shutdown();
            chatService = null;
        }
        if (accessManager != null) {
            accessManager.shutdown();
            accessManager = null;
        }
        getServer().getServicesManager().unregisterAll(this);
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        getLogger().info("CHRDK_Core disabled.");
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
        if (tabListService != null) {
            tabListService.reload();
        }
        if (externalHooks != null) {
            externalHooks.resolve();
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
        if (passMenuService != null) {
            passMenuService.setMessages(messages);
        }
        if (passwordKeypad != null) {
            passwordKeypad.setMessages(messages);
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

    /** Инвентарное меню {@code /pass menu} (иконки вместо текстовых справок). */
    public PassMenuService passMenu() {
        return passMenuService;
    }

    /** Кнопочная клавиатура пароля для {@code /login} без аргументов (безопасно на стриме). */
    public PasswordKeypadService keypadLogin() {
        return passwordKeypad;
    }

    /** Мягкие привязки к Vault/LuckPerms (для /pass doctor и аддонов). */
    public ExternalHooks externalHooks() {
        return externalHooks;
    }

    /** Синхронизация группы LuckPerms с проходкой. */
    public LuckPermsPassSync luckPermsPassSync() {
        return luckPermsPassSync;
    }

    /**
     * Wires PlaceholderAPI when the plugin is installed (soft dependency): registers the
     * {@code %chrdk_*%} expansion for tab-lists, scoreboards and holograms.
     */
    private void hookPlaceholderApi() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        try {
            String version = getPluginMeta().getVersion();
            new PlaceholderIntegration(version, accessManager, chatService, store).register();
            getLogger().info("PlaceholderAPI обнаружен: плейсхолдеры %chrdk_*% зарегистрированы.");
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, "Не удалось зарегистрировать плейсхолдеры PlaceholderAPI", throwable);
        }
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
