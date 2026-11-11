package ru.cherdakmd.chrdkregister;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.datapack.MineTreasureIntegration;
import ru.cherdakmd.chrdkregister.item.ItemAttributeMergeListener;
import ru.cherdakmd.chrdkregister.libs.bstats.bukkit.Metrics;
import ru.cherdakmd.chrdkregister.libs.bstats.charts.SimplePie;
import ru.cherdakmd.chrdkregister.libs.bstats.charts.SingleLineChart;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * Main Purpur 1.21.11 plugin entry point for CHRDK_Core.
 */
public final class RegisterPlugin extends JavaPlugin {
    private AccessManager accessManager;
    private ServerGuard serverGuard;
    private RewardBoostService rewardBoosts;
    RewardBoostService rewardBoosts() { return rewardBoosts; }
    private ChatService chatService;
    ChatModerationService chatModeration;
    private PassMenuService passMenuService;
    private DonateMenuService donateMenuService;
    private ServerMenuService serverMenuService;
    private RankMenuService rankMenuService;
    private HatCommand hatCommand;
    private WikiCommandHandler wikiCommand;
    private DonateCommandHandler donateCommand;
    private BankStore bankStore;
    private BankService bankService;
    private ProfileRegistry profileRegistry;
    private ProfileService profileService;
    private BankCommandHandler bankCommands;
    private AdminDonationsGuiService adminDonationsGui;
    private PasswordKeypadService passwordKeypad;
    private TabListService tabListService;
    /** persist()-expansion переживает /papi reload: не регистрируем его повторно. */
    private boolean tabExpansionRegistered;
    TabListService tabList() { return tabListService; }
    /** 1.4.183: личные переключатели надписей в нике и табе. */
    private LabelPreferences labelPreferences;
    private LabelsCommandHandler labelsCommand;
    /** 1.4.186: меню «Оформление ника» — по плитке на каждую надпись. */
    private LabelsMenuService labelsMenu;
    /** 1.4.187: палитра цветов ника и лимиты пресетов. */
    private volatile LabelPalette palette = LabelPalette.disabled();
    private LabelsStyleMenuService labelsStyleMenu;
    private volatile LabelLimits labelLimits = LabelLimits.DEFAULT;
    private PluginUpdateService pluginUpdater;
    private ExternalHooks externalHooks;
    private LuckPermsPassSync luckPermsPassSync;
    private UpdateChecker updateChecker;
    private SpawnService spawnService;
    private GuideBookService guideBookService;
    private StarterKitService starterKitService;
    private RegistrationStore store;
    private ShopService shopService;
    private ExecutionService executor;
    private RegisterCommandHandler commandHandler;
    private volatile Messages messages;
    /** Админ-панель аудита игроков: отдельное хранилище и сервис модерации. */
    private ModerationStore moderationStore;
    private ModerationLedger moderationLedger;
    private ModerationService moderation;
    private AdminPanelGui adminPanelGui;
    private AdminPanelCommandHandler adminPanelCommand;
    /** Один владелец «ввода значением в чат» на все GUI ядра. */
    private GuiChatInput chatInput;
    /** Особые звания за выслугу лет: редактор, реестр и эффективные привилегии. */
    private HonorStore honorStore;
    private HonorService honors;
    private HonorGui honorGui;
    private ru.cherdakmd.chrdkregister.storage.BackupService backupService;

    /** Имя папки данных до переименования ядра (1.4.44). */
    private static final String LEGACY_DATA_FOLDER = "CHRDK_F_REGISTER";

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        migrateEconomyConfig();
        OptionalTotpMigration.apply(this);
        PresentationMigration.apply(this);
        PremiumPresentationMigration.apply(this);
        ClaimsChunksProductMigration.apply(this);
        ConfigUpgrader.apply(this);
        PluginConfig config = PluginConfig.fromBukkitConfig(getConfig());
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());

        ru.cherdakmd.chrdkregister.api.PassivePotions.start(this);
        this.executor = new ExecutionService(getLogger());
        this.store = new RegistrationStore(getDataFolder().toPath(), getLogger(),
                config.storageWriteAsync() ? executor.taskExecutor() : null);

        DonatePayClient donatePayClient = new DonatePayClient(config);

        // AccessManager captures these hooks in LuckPermsBootstrap; initialize them first.
        this.externalHooks = new ExternalHooks(this);
        this.accessManager = new AccessManager(this, config, store, donatePayClient, messages, getLogger());
        this.chatService = new ChatService(this, accessManager, config.chat(), messages, getLogger());
        this.chatModeration = new ChatModerationService(this, this::messages,
                player -> accessManager.getStage(player) == AccessManager.PlayerStage.AUTHENTICATED);
        this.passMenuService = new PassMenuService(accessManager, messages);
        this.donateMenuService = new DonateMenuService(accessManager, messages);
        this.serverMenuService = new ServerMenuService(this, accessManager, messages);
        this.rankMenuService = new RankMenuService(this, accessManager, messages);
        this.bankStore = new BankStore(getDataFolder().toPath(), getLogger());
        this.bankStore.load();
        this.bankStore.debtReserve(owner -> {
            var providers = getServer().getServicesManager().getRegistrations(ru.cherdakmd.chrdkregister.api.PersonalDebtIndex.class);
            // Если установленный модуль не смог прочитать долговой WAL, расходы закрыты до восстановления.
            if (providers.isEmpty() && getServer().getPluginManager().getPlugin("CHRDK_Guilds") != null) return -1;
            long total = 0;
            for (var registration : providers) {
                long amount = registration.getProvider().reserved(owner);
                if (amount < 0) return -1;
                total = Math.min(BankStore.MAX_BALANCE, Math.addExact(total, Math.min(amount, BankStore.MAX_BALANCE)));
            }
            return total;
        });
        this.bankService = new BankService(this, bankStore, () -> accessManager.config().economy());
        this.bankService.start();
        var chargeGuard = new BankChargeGuard(this, bankStore);
        getServer().getPluginManager().registerEvents(chargeGuard, this);
        getServer().getScheduler().runTask(this, chargeGuard::checkOnline);
        this.profileRegistry = new ProfileRegistry(getLogger());
        this.rewardBoosts = new RewardBoostService(this, accessManager);
        getServer().getPluginManager().registerEvents(rewardBoosts, this);
        profileRegistry.register("reward-boost", rewardBoosts::profile);
        this.profileService = new ProfileService(this, accessManager, bankStore, profileRegistry);
        getServer().getPluginManager().registerEvents(profileService, this);
        // Автосообщения показывают баланс банка: подстановка %balance%.
        this.chatService.setPlaceholderProvider((player, values) ->
                values.put("balance", BankService.formatMinor(bankStore.balanceMinor(player.getUniqueId()))));
        this.adminDonationsGui = new AdminDonationsGuiService(accessManager, messages, this);
        this.donateMenuService.setAdminJournalOpener(player -> adminDonationsGui.openMenu(player));
        this.passwordKeypad = new PasswordKeypadService(this, accessManager, messages);
        this.labelPreferences = new LabelPreferences(getDataFolder().toPath(), getLogger());
        labelPreferences.load();
        // Один координатор чат-ввода: админ-панель и редактор званий не должны
        // одновременно перехватывать одно сообщение или глотать чужой чат.
        this.chatInput = new GuiChatInput(this);
        getServer().getPluginManager().registerEvents(chatInput, this);
        // Админ-панель: moderation.json и players/*.yml живут отдельно от data.json аккаунтов.
        this.moderationStore = new ModerationStore(getDataFolder().toPath(), getLogger());
        this.moderationLedger = moderationStore.load();
        this.moderation = new ModerationService(this, moderationLedger, moderationStore,
                ModerationConfig.fromConfig(getConfig()));
        this.adminPanelGui = new AdminPanelGui(this, moderation);
        this.adminPanelCommand = new AdminPanelCommandHandler(this, moderation, adminPanelGui);
        getServer().getPluginManager().registerEvents(adminPanelGui, this);
        getServer().getPluginManager().registerEvents(new ModerationListener(this, moderation), this);
        PluginCommand adminPanelPlugin = getCommand("adminpanel");
        if (adminPanelPlugin != null) {
            adminPanelPlugin.setExecutor(adminPanelCommand);
            adminPanelPlugin.setTabCompleter(adminPanelCommand);
        }
        // honors.json лежит отдельно от data.json: решение владельца не подделывает покупку.
        this.honorStore = new HonorStore(getDataFolder().toPath(), getLogger());
        HonorConfig honorConfig = HonorConfig.fromConfig(getConfig());
        HonorStore.Load loaded = honorStore.loadWithStatus(honorConfig);
        this.honors = new HonorService(this, loaded.ledger(), honorStore, honorConfig);
        if (loaded.created()) {
            // Стартовые выдачи из конфига фиксируем на диске и в аудите сразу:
            // иначе при жёстком перезапуске дата перегенерируется, а первая
            // благодарность останется единственной выдачей без следа в журнале.
            honors.save();
            honors.auditInitialGrants(loaded.seeded());
        }
        this.honorGui = new HonorGui(this, honors);
        getServer().getPluginManager().registerEvents(honorGui, this);
        if (chatService != null) chatService.setHonors(honors);
        HonorCommandHandler honorHandler = new HonorCommandHandler(this, honors, honorGui);
        PluginCommand honorCommand = getCommand("honor");
        if (honorCommand != null) {
            honorCommand.setExecutor(honorHandler);
            honorCommand.setTabCompleter(honorHandler);
        }
        PluginCommand honorAdminCommand = getCommand("honoradmin");
        if (honorAdminCommand != null) {
            honorAdminCommand.setExecutor(honorHandler);
            honorAdminCommand.setTabCompleter(honorHandler);
        }
        this.tabListService = new TabListService(this, accessManager);
        this.tabListService.setNameDecorator(chatService::decoratedTabName);
        this.tabListService.setAnimationPredicate(chatService::animatedTabName);
        this.tabListService.setLabelPreferences(labelPreferences);
        this.chatService.setLabelPreferences(labelPreferences);
        profileService.setLabelPreferences(labelPreferences);
        this.labelsMenu = new LabelsMenuService(this, labelPreferences);
        getServer().getPluginManager().registerEvents(labelsMenu, this);
        // 1.4.187: цвет, порядок меток и пресеты — отдельные экраны из меню.
        this.labelsStyleMenu = new LabelsStyleMenuService(this, labelPreferences);
        getServer().getPluginManager().registerEvents(labelsStyleMenu, this);
        labelsMenu.setStyleMenu(labelsStyleMenu);
        applyLabelSettings();
        this.luckPermsPassSync = new LuckPermsPassSync(externalHooks, store, accessManager::config, getLogger());
        this.shopService = new ShopService(this, accessManager, externalHooks, getLogger());
        this.chatService.setTagResolver(shopService::tagOf);
        // 1.4.187: «каталожные» цвета открываются покупкой — ядро спрашивает
        // у каталога активные товары игрока (без создания записей).
        this.chatService.setPurchaseLookup(this::purchasedProducts);
        getServer().getPluginManager().registerEvents(new ShopListener(shopService), this);
        shopService.cleanupExpired();
        shopService.logProblems();
        getServer().getServicesManager().register(ChrdkRegisterApi.class,
                new ChrdkRegisterApiImpl(this, accessManager, externalHooks, shopService,
                        chatService.nameTags(), bankStore, profileRegistry),
                this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(new PlayerRestrictionListener(accessManager), this);
        serverGuard = new ServerGuard(GuardSettings.parse(getConfig()), this::messages,
                player -> accessManager != null && accessManager.getStage(player) == AccessManager.PlayerStage.AUTHENTICATED,
                name -> getServer().getCommandMap().getCommand(name), () -> getServer().getCommandAliases(),
                getLogger()::warning, System::nanoTime, () -> getServer().getMaxPlayers(),
                attempt -> {
                    // Append-only аудит попыток обойти console-only. IP маскируется внутри AuditLog.
                    AuditLog log = auditLog();
                    if (log != null) log.log(AuditLog.SECURITY_COMMAND_BLOCKED, attempt.player(),
                            attempt.root(), attempt.address(),
                            AuditLog.details("uuid", attempt.uuid().toString(), "source", "player-chat"));
                });
        getServer().getPluginManager().registerEvents(serverGuard, this);
        getServer().getScheduler().runTaskTimer(this, () -> serverGuard.pulse(getServer().getOnlinePlayers()), 20L, 20L);
        PluginCommand guardCommand = getCommand("guard");
        if (guardCommand != null) guardCommand.setExecutor((sender, command, label, args) -> serverGuard.status(sender));
        getServer().getPluginManager().registerEvents(chatModeration, this);
        getServer().getPluginManager().registerEvents(chatService, this);
        // HIGHEST этого слушателя должен пройти до HIGHEST AnvilCostListener,
        // иначе котировка цены запомнит результат до слияния атрибутов.
        getServer().getPluginManager().registerEvents(new ItemAttributeMergeListener(this), this);
        getServer().getPluginManager().registerEvents(new AnvilCostListener(this), this);
        getServer().getPluginManager().registerEvents(passMenuService, this);
        getServer().getPluginManager().registerEvents(donateMenuService, this);
        getServer().getPluginManager().registerEvents(serverMenuService, this);
        getServer().getPluginManager().registerEvents(rankMenuService, this);
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

        this.starterKitService = new StarterKitService(this,
                new StarterKitStore(getDataFolder().toPath(), getLogger()),
                StarterKitSettings.parse(getConfig()));
        getServer().getPluginManager().registerEvents(starterKitService, this);
        starterKitService.start();

        Path pluginsFolder = getDataFolder().getParentFile() != null
                ? getDataFolder().getParentFile().toPath() : getDataFolder().toPath();
        DuplicatePluginJarDetector.warnIfDuplicates(pluginsFolder, getLogger());
        this.pluginUpdater = new PluginUpdateService(pluginsFolder,
                getServer().getUpdateFolderFile().toPath(), getDataFolder().toPath(),
                () -> accessManager.config().updatesRepo(), getServer().getBukkitVersion());
        this.commandHandler = new RegisterCommandHandler(this, accessManager);
        PluginCommand profileCommand = getCommand("profile");
        if (profileCommand != null) {
            profileCommand.setExecutor((sender, command, label, args) -> {
                boolean chat = args.length > 0 && args[0].equalsIgnoreCase("chat");
                String target = chat
                        ? (args.length > 1 ? args[1] : null)
                        : (args.length > 0 ? args[0] : null);
                if (args.length > (chat ? 2 : 1)) {
                    messages.send(sender, "profile.usage");
                    return true;
                }
                return profileService.show(sender, chat, target);
            });
            profileCommand.setTabCompleter((sender, command, alias, args) ->
                    profileService.complete(sender, args));
        }
        this.hatCommand = new HatCommand(this, accessManager);
        PluginCommand hatCommandPlugin = getCommand("hat");
        if (hatCommandPlugin != null) {
            hatCommandPlugin.setExecutor(this.hatCommand);
        }
        PluginCommand rankCommand = getCommand("rank");
        if (rankCommand != null) {
            RankCommandHandler rankHandler = new RankCommandHandler(this, accessManager, rankMenuService);
            rankCommand.setExecutor(rankHandler);
            rankCommand.setTabCompleter(rankHandler);
        }
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
        this.labelsCommand = new LabelsCommandHandler(this, labelPreferences, labelsMenu);
        labelsMenu.setCommand(labelsCommand);
        PluginCommand labelsCommandPlugin = getCommand("labels");
        if (labelsCommandPlugin != null) {
            labelsCommandPlugin.setExecutor(labelsCommand);
            labelsCommandPlugin.setTabCompleter(labelsCommand);
        }
        this.donateCommand = new DonateCommandHandler(messages);
        PluginCommand donateCommandPlugin = getCommand("donate");
        if (donateCommandPlugin != null) {
            donateCommandPlugin.setExecutor(donateCommand);
            donateCommandPlugin.setTabCompleter(donateCommand);
        }
        this.wikiCommand = new WikiCommandHandler(() -> accessManager.config().wiki(), messages);
        PluginCommand wikiCommandPlugin = getCommand("wiki");
        if (wikiCommandPlugin != null) {
            wikiCommandPlugin.setExecutor(this.wikiCommand);
            wikiCommandPlugin.setTabCompleter(this.wikiCommand);
        }
        for (String commandName : List.of("register", "login", "changepassword", "2fa", "pass",
                "logout", "session", "captcha", "guide")) {
            PluginCommand pluginCommand = getCommand(commandName);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(this.commandHandler);
                pluginCommand.setTabCompleter(commandHandler);
            }
        }

        this.bankCommands = new BankCommandHandler(this, bankService, messages);
        for (String commandName : List.of("bal", "pay", "baltop")) {
            PluginCommand pluginCommand = getCommand(commandName);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(bankCommands);
                pluginCommand.setTabCompleter(bankCommands);
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
        rewardBoosts.start();
        this.backupService = new ru.cherdakmd.chrdkregister.storage.BackupService(this);
        backupService.start();
        chatService.start();
        this.updateChecker = new UpdateChecker(this, accessManager, getLogger());
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.start();
        hookPlaceholderApi();
        enableMetrics(config);
        // Только включает уже установленный в <мир>/datapacks ZIP; сам архив не поставляется.
        MineTreasureIntegration.start(this);
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
        if (backupService != null) backupService.stop();
        // Нельзя оставить готовый результат с клиентским cost=39 после снятия full-price guard.
        getServer().getOnlinePlayers().forEach(player -> {
            if (player.getOpenInventory() instanceof org.bukkit.inventory.view.AnvilView) player.closeInventory();
        });
        ru.cherdakmd.chrdkregister.api.AnvilCosts.clearAll();
        ru.cherdakmd.chrdkregister.api.PassivePotions.stop();
        ru.cherdakmd.chrdkregister.api.CropReplanting.clear();
        if (pluginUpdater != null) pluginUpdater.close();
        if (chatInput != null) chatInput.clearAll();
        if (adminPanelGui != null) adminPanelGui.shutdown();
        if (honorGui != null) honorGui.shutdown();
        if (honors != null) honors.save();
        if (moderation != null) moderation.save();
        if (serverMenuService != null) serverMenuService.shutdown();
        if (rankMenuService != null) rankMenuService.shutdown();
        if (labelsMenu != null) labelsMenu.shutdown();
        if (labelsStyleMenu != null) labelsStyleMenu.shutdown();
        if (bankService != null) {
            bankService.shutdown();
            bankService = null;
        }
        if (updateChecker != null) {
            updateChecker.shutdown();
            updateChecker = null;
        }
        if (tabListService != null) {
            tabListService.shutdown();
            tabListService = null;
        }
        if (labelPreferences != null) {
            labelPreferences.saveNow();
            labelPreferences = null;
        }
        if (chatService != null) {
            chatService.shutdown();
            chatService = null;
        }
        if (guideBookService != null) {
            guideBookService.shutdown();
            guideBookService = null;
        }
        if (starterKitService != null) {
            starterKitService.shutdown();
            starterKitService = null;
        }
        if (spawnService != null) {
            spawnService.shutdown();
            spawnService = null;
        }
        if (rewardBoosts != null) rewardBoosts.close();
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

    /** Ошибка миграции налога не должна выключить регистрацию/защиту всего сервера. */
    private void migrateEconomyConfig() {
        try { ru.cherdakmd.chrdkregister.api.EconomyTariffMigration.apply(this); }
        catch (IllegalStateException error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Тариф /pay не обновлён надёжно: проверьте config.yml/backup. Авторизация остаётся включена.", error);
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        migrateEconomyConfig();
        OptionalTotpMigration.apply(this);
        PresentationMigration.apply(this);
        PremiumPresentationMigration.apply(this);
        ClaimsChunksProductMigration.apply(this);
        PluginConfig updated = PluginConfig.fromBukkitConfig(getConfig());
        if (serverGuard != null) serverGuard.reload(GuardSettings.parse(getConfig()));
        if (accessManager != null) {
            accessManager.reload(updated);
        }
        if (rewardBoosts != null) rewardBoosts.reload();
        if (chatService != null) {
            chatService.reload(updated.chat());
        }
        if (chatModeration != null) chatModeration.reload();
        if (tabListService != null) {
            tabListService.reload();
        }
        if (moderation != null) {
            moderation.reload(getConfig());
        }
        if (honors != null) {
            honors.reload(getConfig());
        }
        if (honorGui != null) {
            honorGui.refreshAll();
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
        if (starterKitService != null) {
            starterKitService.reload(StarterKitSettings.parse(getConfig()));
        }
        if (backupService != null) backupService.reload();
        if (shopService != null) {
            shopService.logProblems();
        }
        applyLabelSettings();
    }

    void writePaidAudit(org.bukkit.command.CommandSender sender) {
        // Отчёт не содержит паролей, TOTP, IP, токенов и иных данных авторизации.
        messages.send(sender, "paid-only.audit-start");
        executor.taskExecutor().execute(() -> {
            try {
                java.nio.file.Path folder = getDataFolder().toPath().resolve("paid-audit");
                java.nio.file.Files.createDirectories(folder);
                java.nio.file.Path file = folder.resolve("audit-" + java.util.UUID.randomUUID() + ".json");
                java.nio.file.Files.writeString(file,
                        PaidAuditReport.render(store.getAllAccounts(), store.paymentReceipts(), store.storeDeliveries(), System.currentTimeMillis()),
                        java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                getServer().getScheduler().runTask(this, () -> messages.send(sender, "paid-only.audit-done", "file", file.getFileName()));
            } catch (Exception error) {
                getLogger().log(java.util.logging.Level.SEVERE, "Payment reconciliation report failed", error);
                getServer().getScheduler().runTask(this, () -> messages.send(sender, "paid-only.audit-error"));
            }
        });
    }

    /** Тексты плагина из {@code messages.yml}. */
    public PluginUpdateService pluginUpdater() { return pluginUpdater; }

    /**
     * Перерисовывает таб-лист всем (1.4.183).
     *
     * <p>Нужно после {@code /labels}: имя в списке общее, поэтому смена «что
     * показывать» должна быть видна всем сразу, а не только автору настройки.</p>
     */
    public void refreshTab() {
        if (tabListService != null) {
            tabListService.refreshAll();
        }
    }

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
        if (rankMenuService != null) {
            rankMenuService.setMessages(messages);
        }
        if (hatCommand != null) {
            hatCommand.refreshMessages();
        }
        if (bankCommands != null) {
            bankCommands.setMessages(messages);
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
        if (wikiCommand != null) {
            wikiCommand.setMessages(messages);
        }
        if (donateCommand != null) {
            donateCommand.setMessages(messages);
        }
        if (labelsCommand != null) {
            labelsCommand.refreshMessages();
        }
        return messages.validate();
    }

    public ru.cherdakmd.chrdkregister.storage.BackupService backups() { return backupService; }

    /** Сбрасывает собственные WAL перед копированием; аддоны сбрасывают свои по своим таймерам. */
    public void flushForBackup() {
        if (store != null) store.flush();
        if (bankStore != null) bankStore.flush();
        if (labelPreferences != null) labelPreferences.saveNow();
        if (honors != null) honors.save();
        if (moderation != null) moderation.save();
    }

    /** Flushes the storage and creates a manual backup (used by {@code /pass backup}). */
    public java.util.Optional<java.nio.file.Path> createBackup(String reason) {
        if (store == null) return java.util.Optional.empty();
        return store.createBackup(reason, PluginConfig.fromBukkitConfig(getConfig()).storageBackupCount());
    }

    public RegistrationStore store() {
        return store;
    }

    public AccessManager accessManager() {
        return accessManager;
    }

    /** Сырые переводы банка для полного профиля в админ-панели. */
    public BankStore bankStore() {
        return bankStore;
    }

    /** Append-only журнал безопасности: дублирует действия админ-панели. */
    public AuditLog auditLog() {
        return accessManager == null ? null : accessManager.audit();
    }

    /** Варны, баны, роли модераторов и журнал действий. */
    public ModerationService moderation() {
        return moderation;
    }

    /** Общий координатор ввода значений в чате для GUI. */
    public GuiChatInput chatInput() {
        return chatInput;
    }

    /** Особые звания за выслугу лет. */
    public HonorService honors() {
        return honors;
    }

    /** GUI-редактор званий, открываемый из админ-панели. */
    public HonorGui honorsGui() {
        return honorGui;
    }

    /** Эффективная ступень привилегий: оплата или звание, что выше. */
    public int effectiveRankLevel(String playerName) {
        return accessManager == null ? 0 : accessManager.effectiveRankLevel(playerName);
    }

    /** Случайный спавн новичков и после смерти ({@code spawn.*}). */
    public SpawnService spawn() {
        return spawnService;
    }

    /** Книга-путеводитель для новичков ({@code guide.*}, команда {@code /guide}). */
    public GuideBookService guide() {
        return guideBookService;
    }

    /** Случайный стартовый комплект новичка (секция {@code starter-kit}). */
    public StarterKitService starterKit() {
        return starterKitService;
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

    /** Витрина донатных рангов {@code /rank} (1.4.167). */
    public RankMenuService rankMenu() {
        return rankMenuService;
    }

    /** Банк алмазов: /bal, /pay, /baltop и депозиты. */
    public BankService bank() {
        return bankService;
    }

    /** Текущая (в т.ч. после /pass reload) конфигурация плагина. */
    public PluginConfig config() {
        return accessManager != null ? accessManager.config() : null;
    }

    /** Разделы аддонов для {@code /profile} (API v8). */
    public ProfileRegistry profileRegistry() {
        return profileRegistry;
    }

    /** Карточка игрока {@code /profile}. */
    public ProfileService profile() {
        return profileService;
    }

    /** Меню «Оформление ника» (1.4.186): открывается из /labels и /profile. */
    public LabelsMenuService labelsMenu() {
        return labelsMenu;
    }

    /** Экраны цвета, порядка и пресетов (1.4.187). */
    public LabelsStyleMenuService labelsStyleMenu() {
        return labelsStyleMenu;
    }

    /** Палитра цветов ника (1.4.187); без конфига — выключена. */
    public LabelPalette palette() {
        return palette;
    }

    /** Лимиты пресетов и надписей по ступеням ранга. */
    public LabelLimits labelLimits() {
        return labelLimits;
    }

    /** Перечитывает палитру и лимиты после /pass reload. */
    public void applyLabelSettings() {
        this.palette = LabelPalette.fromConfig(getConfig());
        this.labelLimits = LabelLimits.fromConfig(getConfig());
        if (chatService != null) {
            chatService.setPalette(palette);
            chatService.setRankNameInTab(getConfig().getBoolean("tablist.show-rank-name", true));
            if (chatService.labelStyles() != null) chatService.labelStyles().applyLimits(labelLimits);
        }
        if (labelsMenu != null) {
            labelsMenu.applySettings(palette, labelLimits);
        }
        if (labelsStyleMenu != null) {
            labelsStyleMenu.applySettings(palette, labelLimits);
        }
    }

    /** Иды активных товаров игрока: нужны и палитре, и меню. */
    public java.util.Set<String> purchasedProducts(String playerName) {
        ShopService shop = shopService;
        if (shop == null) {
            return java.util.Set.of();
        }
        try {
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            for (ShopService.Active active : shop.active(playerName)) {
                ids.add(active.product().id());
            }
            return java.util.Set.copyOf(ids);
        } catch (RuntimeException exception) {
            return java.util.Set.of();
        }
    }

    /** Чат и автосообщения ({@code /pass automessages}). */
    public ChatService chatService() {
        return chatService;
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
     * {@code %chrdk_*%} expansion for tab-lists, scoreboards and holograms, and the
     * separate {@code %tab_*%} expansion used by the stock TAB header/footer.
     */
    private void hookPlaceholderApi() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        String version = getPluginMeta().getVersion();
        try {
            new PlaceholderIntegration(version, accessManager, chatService, store, bankStore, moderation).register();
            getLogger().info("PlaceholderAPI обнаружен: плейсхолдеры %chrdk_*% зарегистрированы.");
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, "Не удалось зарегистрировать плейсхолдеры %chrdk_*%", throwable);
        }
        // Отдельный try: коллизия identifier "tab" во внешнем плагине не должна
        // маскировать успешную регистрацию основного expansion CHRDK. register()
        // сам отказывает при занятом идентификаторе — чужое расширение не перетираем.
        try {
            if (tabExpansionRegistered) {
                getLogger().info("PlaceholderAPI: expansion %tab_*% уже зарегистрирован CHRDK.");
            } else {
                var expansion = new TabPlaceholderIntegration(version, messages(), accessManager, bank(), moderation);
                tabExpansionRegistered = expansion.register();
                if (tabExpansionRegistered) {
                    getLogger().info("PlaceholderAPI обнаружен: плейсхолдеры %tab_*% зарегистрированы.");
                } else {
                    getLogger().warning("PlaceholderAPI: идентификатор 'tab' занят другим expansion; "
                            + "%tab_*% во внешних плагинах резолвит чужое расширение. "
                            + "Внутренний TAB CHRDK продолжает использовать свои кадры.");
                }
            }
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, "Не удалось зарегистрировать плейсхолдеры %tab_*%", throwable);
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
