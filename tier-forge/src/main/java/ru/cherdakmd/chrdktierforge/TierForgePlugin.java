package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * CHRDK_TierForge — крафтовый накат редкости (Terraria-style): броня, оружие и инструменты
 * выходят с тиром от проклятого до легендарного; два одинаково-редких на наковальне
 * сливаются в тир выше.
 */
public final class TierForgePlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile TierForgeConfig config;
    private volatile ForgeNamePool namePool;
    private TierItems items;
    private final TierStatsKeeper stats = new TierStatsKeeper();
    private TierIdentificationListener identification;
    private ForgeUpgradeListener forgeUpgrades;
    private org.bukkit.scheduler.BukkitTask identifyScanTask;
    /** Таймер пассивного пульса кузни (регенерация/зрение/магнит), гасим при перезагрузке. */
    private org.bukkit.scheduler.BukkitTask forgePulseTask;
    /** stats.yml — находки переживают перезапуск (1.4.81). */
    private File statsFile;
    private org.bukkit.scheduler.BukkitTask statsSaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadTierConfig();
        this.items = new TierItems(this);
        loadStats();
        startStatsSave();

        Bukkit.getPluginManager().registerEvents(new TierCraftListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TierAnvilListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TierMobDropListener(this), this);
        // 1.4.117: старые тир-инструменты носили урон/скорость атаки — правим на месте.
        Bukkit.getPluginManager().registerEvents(new TierRefreshListener(this), this);
        if (forgeUpgrades == null) {
            this.forgeUpgrades = new ForgeUpgradeListener(this);
            Bukkit.getPluginManager().registerEvents(forgeUpgrades, this);
            startForgePulse();
        }

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
                + ".");
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
            getLogger().info("CHRDK_TierForge: статистика находок загружена ("
                    + stats.players() + " игроков, " + stats.grandTotal() + " находок).");
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
        this.namePool = new ForgeNamePool(config.namesWeapons(), config.namesArmor(), config.namesTools());
        if (identifyScanTask != null) {
            identifyScanTask.cancel();
            identifyScanTask = null;
        }
        if (identification != null && config.identifyUnidentified()) {
            this.identifyScanTask = Bukkit.getScheduler().runTaskTimer(this,
                    () -> identification.scanBatch(config.identifyScanPlayersPerTick()),
                    config.identifyScanTicks(), config.identifyScanTicks());
        }
        // 1.4.100: и при старте, и после /tiersadmin reload — что реально применилось.
        logEffectiveConfig();
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
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

    public TierStatsKeeper stats() {
        return stats;
    }
}
