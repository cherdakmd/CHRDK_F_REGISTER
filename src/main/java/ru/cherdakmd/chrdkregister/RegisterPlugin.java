package ru.cherdakmd.chrdkregister;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.libs.bstats.bukkit.Metrics;
import ru.cherdakmd.chrdkregister.libs.bstats.charts.SimplePie;
import ru.cherdakmd.chrdkregister.libs.bstats.charts.SingleLineChart;

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
    private DonateMenuService donateMenuService;
    private ServerMenuService serverMenuService;
    private AdminDonationsGuiService adminDonationsGui;
    private PasswordKeypadService passwordKeypad;
    private TabListService tabListService;
    private PluginUpdateService pluginUpdater;
    private ExternalHooks externalHooks;
    private LuckPermsPassSync luckPermsPassSync;
    private UpdateChecker updateChecker;
    private SpawnService spawnService;
    private GuideBookService guideBookService;
    private RegistrationStore store;
    private ShopService shopService;
    private ExecutionService executor;
    private RegisterCommandHandler commandHandler;
    private volatile Messages messages;

    /** Имя папки данных до переименования ядра (1.4.44). */
    private static final String LEGACY_DATA_FOLDER = "CHRDK_F_REGISTER";

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        PresentationMigration.apply(this);
        PluginConfig config = PluginConfig.fromBukkitConfig(getConfig());
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());

        this.executor = new ExecutionService(getLogger());
        this.store = new RegistrationStore(getDataFolder().toPath(), getLogger(),
                config.storageWriteAsync() ? executor.taskExecutor() : null);

        DonatePayClient donatePayClient = new DonatePayClient(config);

        // AccessManager captures these hooks in LuckPermsBootstrap; initialize them first.
        this.externalHooks = new ExternalHooks(this);
        this.accessManager = new AccessManager(this, config, store, donatePayClient, messages, getLogger());
        this.chatService = new ChatService(this, accessManager, config.chat(), messages, getLogger());
        this.passMenuService = new PassMenuService(accessManager, messages);
        this.donateMenuService = new DonateMenuService(accessManager, messages);
        this.serverMenuService = new ServerMenuService(this, accessManager, messages);
        this.adminDonationsGui = new AdminDonationsGuiService(accessManager, messages, this);
        this.donateMenuService.setAdminJournalOpener(player -> adminDonationsGui.openMenu(player));
        this.passwordKeypad = new PasswordKeypadService(this, accessManager, messages);
        this.tabListService = new TabListService(this, accessManager);
        this.luckPermsPassSync = new LuckPermsPassSync(externalHooks, store, accessManager::config, getLogger());
        this.shopService = new ShopService(this, accessManager, externalHooks, getLogger());
        this.chatService.setTagResolver(shopService::tagOf);
        getServer().getPluginManager().registerEvents(new ShopListener(shopService), this);
        shopService.cleanupExpired();
        shopService.logProblems();
        getServer().getServicesManager().register(ChrdkRegisterApi.class,
                new ChrdkRegisterApiImpl(this, accessManager, externalHooks), this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(new PlayerRestrictionListener(accessManager), this);
        getServer().getPluginManager().registerEvents(chatService, this);
        getServer().getPluginManager().registerEvents(passMenuService, this);
        getServer().getPluginManager().registerEvents(donateMenuService, this);
        getServer().getPluginManager().registerEvents(serverMenuService, this);
        getServer().getPluginManager().registerEvents(adminDonationsGui, this);
        getServer().getPluginManager().registerEvents(
                new MotdService(this, accessManager::config), this);
        getServer().getPluginManager().registerEvents(passwordKeypad, this);
        getServer().getPluginManager().registerEvents(tabListService, this);
        tabListService.reload();

        this.spawnService = new SpawnService(this, accessManager,
                new SpawnStore(getDataFolder().toPath(), getLogger()),
                SpawnSettings.parse(getConfig(), getLogger()));
        getServer().getPluginManager().registerEvents(spawnService, this);
        spawnService.start();

        this.guideBookService = new GuideBookService(this,
                new GuideStore(getDataFolder().toPath(), getLogger()),
                GuideBookSettings.parse(getConfig()));
        getServer().getPluginManager().registerEvents(guideBookService, this);
        guideBookService.start();

        this.pluginUpdater = new PluginUpdateService(getDataFolder().getParentFile().toPath(),
                getServer().getUpdateFolderFile().toPath(), getDataFolder().toPath(),
                () -> accessManager.config().updatesRepo(), getServer().getBukkitVersion());
        this.commandHandler = new RegisterCommandHandler(this, accessManager);
        PluginCommand menuCommand = getCommand("menu");
        if (menuCommand != null) {
            menuCommand.setExecutor((sender, command, label, args) -> {
                if (!accessManager.config().serverMenu().enabled()) {
                    messages.send(sender, "server-menu.disabled");
                    return true;
                }
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    messages.send(sender, "server-menu.player-only");
                    return true;
                }
                serverMenuService.openMenu(player);
                return true;
            });
        }
        for (String commandName : List.of("register", "login", "changepassword", "2fa", "pass",
                "logout", "session", "captcha", "guide")) {
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
        this.updateChecker = new UpdateChecker(this, accessManager, getLogger());
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.start();
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
        if (pluginUpdater != null) pluginUpdater.close();
        if (serverMenuService != null) serverMenuService.shutdown();
        if (updateChecker != null) {
            updateChecker.shutdown();
            updateChecker = null;
        }
        if (tabListService != null) {
            tabListService.shutdown();
            tabListService = null;
        }
        if (chatService != null) {
            chatService.shutdown();
            chatService = null;
        }
        if (guideBookService != null) {
            guideBookService.shutdown();
            guideBookService = null;
        }
        if (spawnService != null) {
            spawnService.shutdown();
            spawnService = null;
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
        PresentationMigration.apply(this);
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
        if (spawnService != null) {
            spawnService.reload(SpawnSettings.parse(getConfig(), getLogger()));
        }
        if (guideBookService != null) {
            guideBookService.reload(GuideBookSettings.parse(getConfig()));
        }
        if (shopService != null) {
            shopService.logProblems();
        }
    }

    /** Тексты плагина из {@code messages.yml}. */
    public PluginUpdateService pluginUpdater() { return pluginUpdater; }

    public Messages messages() {
        return messages;
    }

    /** Каталог товаров: выдача, отзыв и список покупок. */
    public ShopService shop() {
        return shopService;
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
        if (donateMenuService != null) {
            donateMenuService.setMessages(messages);
        }
        if (serverMenuService != null) {
            serverMenuService.setMessages(messages);
        }
        if (adminDonationsGui != null) {
            adminDonationsGui.setMessages(messages);
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

    /** Случайный спавн новичков и после смерти ({@code spawn.*}). */
    public SpawnService spawn() {
        return spawnService;
    }

    /** Книга-путеводитель для новичков ({@code guide.*}, команда {@code /guide}). */
    public GuideBookService guide() {
        return guideBookService;
    }

    /** Инвентарное меню {@code /pass menu} (иконки вместо текстовых справок). */
    public PassMenuService passMenu() {
        return passMenuService;
    }

    public DonateMenuService donateMenu() {
        return donateMenuService;
    }

    public ServerMenuService serverMenu() {
        return serverMenuService;
    }

    public AdminDonationsGuiService adminDonationsGui() {
        return adminDonationsGui;
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
     * Wires optional bStats metrics: the client is bundled with the plugin (relocated
     * to {@code ru.cherdakmd.chrdkregister.libs.bstats}), so no external dependency is needed.
     * A numeric plugin ID is required and must be obtained from bstats.org;
     * when it is not configured the plugin simply continues without metrics.
     *
     * <p>Besides the standard charts we report anonymous aggregates: how many accounts
     * hold an active pass / have 2FA on, and which donation provider is in use.
     * Only numbers and enum labels — never names, UUIDs or IPs.</p>
     */
    private void enableMetrics(PluginConfig config) {
        if (!config.bStatsEnabled()) return;
        int pluginId = getConfig().getInt("metrics.bstats-id", 0);
        if (pluginId <= 0) {
            getLogger().info("bStats включён, но metrics.bstats-id не задан — метрики пропущены "
                    + "(ID плагина бесплатно выдаётся на bstats.org).");
            return;
        }
        try {
            Metrics metrics = new Metrics(this, pluginId);
            metrics.addCustomChart(new SingleLineChart("players_with_active_pass",
                    () -> store.statsSnapshot(System.currentTimeMillis()).activePass()));
            metrics.addCustomChart(new SingleLineChart("totp_accounts",
                    () -> store.statsSnapshot(System.currentTimeMillis()).totpEnabled()));
            metrics.addCustomChart(new SimplePie("donate_provider", this::donateProviderLabel));
            getLogger().info("bStats-метрики запущены (id " + pluginId + ").");
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, "Не удалось запустить bStats-метрики", throwable);
        }
    }

    /** Метка активного донат-провайдера для графика bStats (читается при каждой отправке). */
    private String donateProviderLabel() {
        PluginConfig current = accessManager != null ? accessManager.config() : null;
        if (current == null) return "none";
        if (current.trademc().enabled()) return "TradeMC";
        if (current.donatePayEnabled()) return "DonatePay";
        if (current.easyDonateEnabled()) return "EasyDonate";
        return "none";
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
