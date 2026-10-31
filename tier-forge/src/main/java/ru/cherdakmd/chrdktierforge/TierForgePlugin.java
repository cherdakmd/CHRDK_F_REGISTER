package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkEnchantsApi;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.ChrdkTierApi;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * CHRDK_TierForge — крафтовый накат редкости (Terraria-style): броня, оружие, инструменты
 * и утилита выходят с тиром от проклятого до легендарного; два одинаково-редких на
 * наковальне сливаются в тир выше.
 */
public final class TierForgePlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile TierForgeConfig config;
    private volatile ForgeNamePool namePool;
    private TierItems items;
    /** Аура редкости (1.4.119): частицы и блеск по тиру предмета. */
    private TierVisualService visuals;
    private final TierStatsKeeper stats = new TierStatsKeeper();
    /** Коллекция кузни (1.4.150): редкости, свойства и аффиксы, встреченные игроком. */
    private final TierCollectionKeeper collection = new TierCollectionKeeper();
    private TierCollectionService collectionService;
    private TierCollectionMenu collectionMenu;
    /** Титулы коллекции зарегистрированы в оформлении ников ядра (1.4.151). */
    private boolean nameTagRegistered;
    /** Титулы в таб-листе (1.4.152) и мост в PlaceholderAPI. */
    private TierCollectionTabService tabService;
    private TierForgePlaceholders placeholders;
    private TierIdentificationListener identification;
    private TierRefreshListener refreshListener;
    private ForgeUpgradeListener forgeUpgrades;
    private org.bukkit.scheduler.BukkitTask identifyScanTask;
    /** Таймер пассивного пульса кузни (регенерация/зрение/магнит), гасим при перезагрузке. */
    private org.bukkit.scheduler.BukkitTask forgePulseTask;
    /**
     * Мост для чужих аддонов (1.4.141): позволяет выдавать предметы с тиром, не
     * завися от классов этого плагина.
     */
    private TierApiBridge tierApi;
    /** stats.yml — находки переживают перезапуск (1.4.81). */
    private File statsFile;
    private org.bukkit.scheduler.BukkitTask statsSaveTask;

    private ru.cherdakmd.chrdkregister.api.MilestoneAwards awards;
    public ru.cherdakmd.chrdkregister.api.MilestoneAwards awards() { return awards; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadTierConfig();
        try {
            if (coreApi == null || ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 20) throw new IllegalStateException("Нужен Core API 20+");
            this.awards = new ru.cherdakmd.chrdkregister.api.MilestoneAwards(this,this::coreApi,this::messages,"tierforge-awards",ru.cherdakmd.chrdkregister.api.MilestoneMigration.tierForge(getDataFolder().toPath().resolve("stats.yml")));
        } catch (Exception | LinkageError error) {
            getLogger().log(java.util.logging.Level.SEVERE,"Модуль остановлен без сброса прогресса/наград",error);
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        this.items = new TierItems(this);
        this.visuals = new TierVisualService(this);
        this.visuals.restart();
        loadStats();
        startStatsSave();
        this.collectionService = new TierCollectionService(this, collection);
        awards.reconciler(player -> collectionService.reconcileRewards(player));
        this.collectionMenu = new TierCollectionMenu(this, collectionService);
        Bukkit.getPluginManager().registerEvents(collectionMenu, this);

        Bukkit.getPluginManager().registerEvents(new TierCraftListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TierAnvilListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TierMobDropListener(this), this);
        // 1.4.185: редкость мировой добычи — сундуки/структуры и рыбалка.
        Bukkit.getPluginManager().registerEvents(new TierWorldListener(this), this);
        // 1.4.117: старые тир-инструменты носили урон/скорость атаки — правим на месте.
        // 1.4.119: тот же проход лениво ставит блеск тира.
        this.refreshListener = new TierRefreshListener(this);
        Bukkit.getPluginManager().registerEvents(refreshListener, this);
        if (forgeUpgrades == null) {
            this.forgeUpgrades = new ForgeUpgradeListener(this);
            Bukkit.getPluginManager().registerEvents(forgeUpgrades, this);
            startForgePulse();
        }

        // 1.4.141: публичный мост «TierForge → другие аддоны». Регистрируется
        // последним, когда items/config уже готовы.
        this.tierApi = new TierApiBridge(this);
        Bukkit.getServicesManager().register(ChrdkTierApi.class, tierApi, this,
                ServicePriority.Normal);

        this.identification = new TierIdentificationListener(this);
        Bukkit.getPluginManager().registerEvents(identification, this);
        if (config.identifyUnidentified()) {
            this.identifyScanTask = Bukkit.getScheduler().runTaskTimer(this,
                    () -> identification.scanBatch(config.identifyScanPlayersPerTick()),
                    config.identifyScanTicks(), config.identifyScanTicks());
            getLogger().info("CHRDK_TierForge: режим неопознанных предметов включён "
                    + "(редкость открывается в инвентаре; скан каждые "
                    + config.identifyScanTicks() + " тиков).");
        }

        // 1.4.151: титулы за закрытые разделы коллекции — рядом с ником в чате.
        registerCollectionNameTag();
        // 1.4.152: титул в таб-листе и плейсхолдеры для TAB/скорбордов.
        this.tabService = new TierCollectionTabService(this, collectionService);
        Bukkit.getPluginManager().registerEvents(tabService, this);
        this.tabService.restart();
        registerPlaceholders();

        TierCommandHandler handler = new TierCommandHandler(this);
        for (String name : List.of("tiers", "tiersadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(handler);
                command.setTabCompleter(handler);
            }
        }
        getLogger().info("CHRDK_TierForge включён: " + config.tiers().size() + " тиров"
                + (config.improveEnabled() ? ", ковка улучшений (" + ForgeUpgrade.ALL.size() + " видов)" : "")
                + (config.namesEnabled() ? ", имён в пуле: " + namePool.totalCount() : "")
                + (config.affixes().enabled()
                ? ", аффиксов: " + TierAffixes.all().size()
                + " (◆" + TierAffixes.GOOD_TOTAL + "/✖" + TierAffixes.BAD_TOTAL + ")" : "")
                + (config.enchants().enabled() ? ", случайные зачарования" : "")
                + ".");
    }

    /**
     * Подключает титулы коллекции к оформлению ников ядра (1.4.151).
     *
     * <p>Провайдер читает книгу в момент сообщения, поэтому титул появляется
     * сразу после закрытия раздела — перезаход не нужен. Ядро применяет
     * провайдеров по id и само снимает сбой, но мы всё равно не бросаем
     * исключений: пустая книга — просто {@code null}.</p>
     */
    private void registerCollectionNameTag() {
        ChrdkRegisterApi api = coreApi();
        if (api == null) {
            return;
        }
        api.registerNameTag("tierforge", (playerName, decoratedName) ->
                collection().decorateName(playerName, decoratedName));
        nameTagRegistered = true;
        getLogger().info("CHRDK_TierForge: титулы коллекции подключены к оформлению ников.");
    }

    /**
     * PlaceholderAPI — мягкая зависимость (1.4.152): без плагина аддон работает
     * как обычно, а с ним даёт {@code %chrdktierforge_title%} и счётчики.
     */
    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            this.placeholders = new TierForgePlaceholders(this);
            this.placeholders.register();
            getLogger().info("CHRDK_TierForge: PlaceholderAPI — доступны %chrdktierforge_title% "
                    + "и счётчики коллекции.");
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("CHRDK_TierForge: PlaceholderAPI не подключился — "
                    + error.getMessage());
            // Регистрация могла успеть наполовину: снимаем, что успели.
            unregisterPlaceholders();
        }
    }

    private void unregisterPlaceholders() {
        if (placeholders == null) {
            return;
        }
        try {
            placeholders.unregister();
        } catch (RuntimeException | LinkageError ignored) {
            // плагин PlaceholderAPI уже выключен — регистрация уйдёт вместе с ним
        }
        placeholders = null;
    }

    /** Обновляет имя игрока в табе после новой находки или смены титула. */
    public void refreshTab(org.bukkit.entity.Player player) {
        if (tabService != null) {
            tabService.refresh(player);
        }
    }

    private void unregisterCollectionNameTag() {
        if (!nameTagRegistered) {
            return;
        }
        ChrdkRegisterApi api = coreApi();
        if (api != null) {
            api.unregisterNameTag("tierforge");
        }
        nameTagRegistered = false;
    }

    /** Пульс кузни раз в 2 секунды (тихий no-op, если ковка выключена). */
    private void startForgePulse() {
        this.forgePulseTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            TierForgeConfig cfg = this.config;
            if (cfg != null && cfg.improveEnabled() && forgeUpgrades != null) {
                forgeUpgrades.pulse();
            }
        }, 40L, 40L);
    }

    @Override
    public void onDisable() {
        if (awards != null) awards.close();
        if (refreshListener != null) refreshListener.close();
        unregisterCollectionNameTag();
        if (tabService != null) {
            tabService.stop();
            tabService = null;
        }
        unregisterPlaceholders();
        if (tierApi != null) {
            Bukkit.getServicesManager().unregister(ChrdkTierApi.class, tierApi);
            tierApi = null;
        }
        if (visuals != null) {
            visuals.stop();
        }
        if (identifyScanTask != null) {
            identifyScanTask.cancel();
            identifyScanTask = null;
        }
        if (forgePulseTask != null) {
            forgePulseTask.cancel();
            forgePulseTask = null;
        }
        if (statsSaveTask != null) {
            statsSaveTask.cancel();
            statsSaveTask = null;
        }
        saveStats(); // последний сейв — синхронно, чтобы не потерять сессию
    }

    /* ------------------------------ статистика (1.4.81) ------------------------------ */

    /** Читает stats.yml при старте; битый файл не роняет плагин. */
    private void loadStats() {
        this.statsFile = new File(getDataFolder(), "stats.yml");
        if (!statsFile.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(statsFile);
            ConfigurationSection root = yaml.getConfigurationSection("players");
            if (root != null) {
                stats.load(root);
            }
            // 1.4.150: книги коллекции лежат в том же файле, отдельного хранилища нет.
            ConfigurationSection books = yaml.getConfigurationSection("collection");
            if (books != null) {
                collection.load(books);
            }
            getLogger().info("CHRDK_TierForge: статистика находок загружена ("
                    + stats.players() + " игроков, " + stats.grandTotal() + " находок; "
                    + "коллекция — " + collection.players() + " игроков).");
        } catch (RuntimeException exception) {
            getLogger().warning("CHRDK_TierForge: не удалось прочитать stats.yml — "
                    + exception.getMessage() + "; начинаем с пустых счётчиков");
        }
    }

    /** Фоновый сейв раз в 5 минут (6000 тиков). */
    private void startStatsSave() {
        if (statsSaveTask != null) {
            statsSaveTask.cancel();
        }
        statsSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, this::saveStats, 6000L, 6000L);
    }

    /**
     * Атомарная запись stats.yml: временный файл + move. Вызывается из async-таски
     * и из onDisable (там — синхронно, файл крошечный).
     */
    private void saveStats() {
        File file = statsFile;
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            getLogger().warning("CHRDK_TierForge: папка плагина недоступна, stats.yml не сохранён");
            return;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            stats.writeTo(yaml.createSection("players"));
            collection.writeTo(yaml.createSection("collection"));
            File temp = new File(parent, file.getName() + ".tmp");
            yaml.save(temp);
            // 1.4.95: атомарная замена не спасает от битого или подменённого файла —
            // перед ней сохраняем предыдущую версию в stats.yml.bakN.
            try {
                TierForgeConfig current = this.config;
                new StatsBackup(file.toPath(), current == null ? 3 : current.statsBackupCopies())
                        .rotate();
            } catch (Exception backupFailure) {
                getLogger().warning("CHRDK_TierForge: резервная копия stats.yml не сделана — "
                        + backupFailure.getMessage());
            }
            try {
                Files.move(temp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            getLogger().warning("CHRDK_TierForge: не удалось сохранить stats.yml — " + exception);
        }
    }

    /**
     * Печать действующих настроек (1.4.100). Без неё после правки `config.yml`
     * приходилось выяснять клампы и фолбэки опытом.
     */
    public void logEffectiveConfig() {
        TierForgeConfig current = this.config;
        if (current == null) {
            return;
        }
        getLogger().info("CHRDK_TierForge: действующие настройки");
        for (String line : ConfigReport.lines(current)) {
            getLogger().info("  • " + line);
        }
    }

    /** Перечитывает config.yml/messages.yml. */
    public void reloadTierConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = TierForgeConfig.parse(getConfig(), getLogger());
        if (forgeUpgrades != null) forgeUpgrades.invalidateGearCache();
        this.namePool = new ForgeNamePool(config.namesWeapons(), config.namesArmor(),
                config.namesTools(), config.namesUtilities());
        if (identifyScanTask != null) {
            identifyScanTask.cancel();
            identifyScanTask = null;
        }
        if (identification != null && config.identifyUnidentified()) {
            this.identifyScanTask = Bukkit.getScheduler().runTaskTimer(this,
                    () -> identification.scanBatch(config.identifyScanPlayersPerTick()),
                    config.identifyScanTicks(), config.identifyScanTicks());
        }
        // 1.4.119: таймер ауры пересобирается под новый interval-ticks,
        // а надетые вещи получают новый блеск без перезахода.
        if (visuals != null) {
            visuals.restart();
        }
        // 1.4.152: настройки таба могли поменяться (выключатель, шаблон, период).
        if (tabService != null) {
            tabService.restart();
        }
        // Ядро могло подключиться позже включения или перезагрузиться: повторная
        // регистрация с тем же id заменяет прежнюю, дублей в чате не будет.
        registerCollectionNameTag();
        if (refreshListener != null) {
            Bukkit.getScheduler().runTask(this, () -> {
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    refreshListener.refreshAll(player);
                }
            });
        }
        // 1.4.100: и при старте, и после /tiersadmin reload — что реально применилось.
        logEffectiveConfig();
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    /**
     * Кастомные чары CHRDK_Enchants (1.4.128) — мягкая зависимость.
     *
     * <p>Сервис ищется при каждом обращении: аддон чар может загрузиться позже
     * тир-форджа, выгрузиться при {@code /reload} или вообще отсутствовать.
     * {@code null} — кастомных чар на тир-предметах не будет, остальной тир
     * работает как обычно.</p>
     */
    public ChrdkEnchantsApi enchantsApi() {
        try {
            return Bukkit.getServicesManager().load(ChrdkEnchantsApi.class);
        } catch (RuntimeException | LinkageError error) {
            // аддон чар старой версии без API — тир просто остаётся без кастомных чар
            return null;
        }
    }

    public Messages messages() {
        return messages;
    }

    public TierForgeConfig config() {
        return config;
    }

    /** Пул кличек кованных предметов (пересобирается при reload). */
    public ForgeNamePool namePool() {
        return namePool;
    }

    public TierItems items() {
        return items;
    }

    /** Аура редкости и переключатель игрока (1.4.119). */
    public TierVisualService visuals() {
        return visuals;
    }

    public TierStatsKeeper stats() {
        return stats;
    }

    /**
     * Коллекция кузни: отметки находок, награды и витрина (1.4.150).
     * Ленивая инициализация — слушатели не зависят от порядка регистрации.
     */
    public TierCollectionService collection() {
        if (collectionService == null) {
            collectionService = new TierCollectionService(this, collection);
        }
        return collectionService;
    }

    /** Хранилище книг — нужно только сейву и тестам. */
    public TierCollectionKeeper collectionKeeper() {
        return collection;
    }

    public TierCollectionMenu collectionMenu() {
        return collectionMenu;
    }
}
