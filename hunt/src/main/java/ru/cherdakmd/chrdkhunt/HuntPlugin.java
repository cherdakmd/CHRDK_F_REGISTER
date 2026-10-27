package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.configuration.ConfigSnapshot;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Hunt — охота на мобов: монеты, осколки, трофеи и ключи (1.4.155).
 *
 * <p>Монеты и осколки — не предметы, а кошелёк в {@code hunt.json}: их нельзя
 * потерять при смерти и нельзя продублировать. Спавнеры и яйца не дают добычи,
 * поэтому охота — это поход в мир, а не ферма на участке.</p>
 */
public final class HuntPlugin extends JavaPlugin {

    private volatile ConfigSnapshot<HuntConfig> settings;
    private HuntStore store;
    private HuntItems items;
    private HuntService service;
    private HuntReport report;
    private HuntGui gui;
    private HuntShopGui shopGui;
    private HuntTrophyGui trophyGui;
    private HuntBestiaryGui bestiaryGui;
    private BukkitTask saveTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;
    private ChrdkRegisterApi coreApi;
    private MobProgression progression;
    private EliteRegeneration regeneration;
    private EliteDashing dashing;
    private EliteAdaptation adaptation;
    private EliteFleeing fleeing;
    private HuntHistoryTracker historyTracker;
    HuntHistoryTracker historyTracker() { return historyTracker; }

    /**
     * Публичный API ядра: охота спрашивает бонус добычи ранга (1.4.167).
     * {@code null} — ядро старше API v10 или не установлено.
     */
    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            if (!tryReloadHunt()) {
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            this.store = new HuntStore(getDataFolder().toPath(), getLogger());
            store.load();
            this.items = new HuntItems(this);
            this.service = new HuntService(this);
            this.report = new HuntReport(this);
            this.gui = new HuntGui(this);
            this.shopGui = new HuntShopGui(this);
            this.trophyGui = new HuntTrophyGui(this);
            this.bestiaryGui = new HuntBestiaryGui(this);
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            if (coreApi != null) {
                try {
                    coreApi.registerProfile("hunt", new HuntProfile(this));
                } catch (RuntimeException | LinkageError error) {
                    getLogger().warning("[CHRDK_Hunt] раздел профиля недоступен "
                            + "(нужно ядро 1.4.156+): " + error);
                }
            }
            getServer().getPluginManager().registerEvents(new HuntListener(this), this);
            getServer().getPluginManager().registerEvents(gui, this);
            getServer().getPluginManager().registerEvents(shopGui, this);
            getServer().getPluginManager().registerEvents(trophyGui, this);
            getServer().getPluginManager().registerEvents(bestiaryGui, this);
            this.progression = new MobProgression(this);
            getServer().getPluginManager().registerEvents(progression, this);
            regeneration = new EliteRegeneration(this);
            getServer().getPluginManager().registerEvents(regeneration, this);
            dashing = new EliteDashing(this);
            getServer().getPluginManager().registerEvents(dashing, this);
            adaptation = new EliteAdaptation(this);
            getServer().getPluginManager().registerEvents(adaptation, this);
            fleeing = new EliteFleeing(this);
            getServer().getPluginManager().registerEvents(fleeing, this);
            historyTracker = new HuntHistoryTracker(this);
            getServer().getPluginManager().registerEvents(historyTracker, this);
            registerCommands();
            registerPlaceholders();
            scheduleTasks();
            getLogger().info("CHRDK_Hunt включён: семейств " + config().families().size()
                    + ", правил мобов " + config().mobRules().size()
                    + ", позиций лавки " + config().shop().size()
                    + ", трофеев " + config().trophies().size()
                    + ", видов в бестиарии " + config().bestiary().totalEntries()
                    + ", охотников в базе " + store.players() + ".");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Hunt не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (fleeing != null) fleeing.stop();
        if (adaptation != null) adaptation.stop();
        if (dashing != null) dashing.stop();
        if (regeneration != null) regeneration.stop();
        if (historyTracker != null) historyTracker.stop();
        if (progression != null) progression.stop();
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("hunt");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
            this.coreApi = null;
        }
        cancelTasks();
        if (gui != null) {
            gui.closeAll();
        }
        if (shopGui != null) {
            shopGui.closeAll();
        }
        if (trophyGui != null) {
            trophyGui.closeAll();
        }
        if (bestiaryGui != null) {
            bestiaryGui.closeAll();
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Hunt выключен.");
    }

    /**
     * Перечитывает {@code config.yml} и {@code messages.yml}, не трогая
     * кошельки и коллекции. Открытые меню закрываются: старые иконки могут
     * ссылаться на удалённые позиции.
     */
    public void reloadHunt() { tryReloadHunt(); }
    @Override public void reloadConfig() {
        if (settings == null) super.reloadConfig();
        else tryReloadHunt();
    }
    @Override public org.bukkit.configuration.file.FileConfiguration getConfig() {
        var current = settings;
        return current == null ? super.getConfig() : current.yaml();
    }
    public boolean tryReloadHunt() {
        ConfigSnapshot<HuntConfig> candidate;
        try {
            candidate = ConfigSnapshot.load(
                    getDataFolder().toPath().resolve("config.yml"), getResource("config.yml"), HuntConfig::parse,
                    (yaml, staged) -> Messages.loadChecked(this, yaml.getString("general.locale", "ru"), staged, settings == null));
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException | RuntimeException | LinkageError failure) {
            if (settings == null) getLogger().severe("Начальные настройки охоты не загружены (" + failure.getClass().getSimpleName() + "). Проверьте config.yml/messages.yml.");
            else ConfigSnapshot.rejected(getLogger(), failure);
            return false;
        }
        settings = candidate; // До этого места никакой cleanup/закрытие GUI не выполняется.
        candidate.committed(getLogger(),
                () -> { if (historyTracker != null) historyTracker.clear(); },
                () -> { if (fleeing != null) fleeing.reload(); },
                () -> { if (adaptation != null) adaptation.reload(); },
                () -> { if (dashing != null) dashing.reload(); },
                () -> { if (regeneration != null) regeneration.reload(); },
                () -> { if (progression != null) progression.reload(); },
                () -> { if (gui != null) gui.closeAll(); },
                () -> { if (shopGui != null) shopGui.closeAll(); },
                () -> { if (trophyGui != null) trophyGui.closeAll(); },
                () -> { if (bestiaryGui != null) bestiaryGui.closeAll(); });
        return true;
    }

    private void registerCommands() {
        HuntCommandHandler handler = new HuntCommandHandler(this);
        for (String name : new String[]{"hunt", "huntadmin"}) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().warning("Команда " + name + " не объявлена в plugin.yml — модуль без неё.");
                continue;
            }
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    /** PlaceholderAPI — мягкая интеграция: без плагина охота работает как обычно. */
    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new HuntPlaceholders(this).register();
            getLogger().info("PlaceholderAPI: доступны %chrdkhunt_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
    }

    private void scheduleTasks() {
        this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                this::saveNow, 100L, 100L);
    }

    private void cancelTasks() {
        if (saveTask != null) {
            saveTask.cancel();
            this.saveTask = null;
        }
        if (queuedSave != null) {
            queuedSave.cancel();
            this.queuedSave = null;
        }
    }

    /** Просит сохранить данные в ближайшие секунды, не чаще одного раза за 2 с. */
    void requestSave() {
        if (saveQueued || !isEnabled()) {
            return;
        }
        saveQueued = true;
        this.queuedSave = getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
            saveQueued = false;
            queuedSave = null;
            saveNow();
        }, 40L);
    }

    private void saveNow() {
        try {
            if (store != null && store.isDirty()) {
                store.saveNow();
            }
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось сохранить hunt.json: " + exception.getMessage());
        }
    }

    public HuntConfig config() {
        return settings.value();
    }

    public Messages messages() {
        return settings.messages();
    }

    MobProgression progression() { return progression; }

    public HuntStore store() {
        return store;
    }

    public HuntItems items() {
        return items;
    }

    public HuntService service() {
        return service;
    }

    public HuntReport report() {
        return report;
    }

    public HuntGui gui() {
        return gui;
    }

    public HuntShopGui shopGui() {
        return shopGui;
    }

    public HuntTrophyGui trophyGui() {
        return trophyGui;
    }

    public HuntBestiaryGui bestiaryGui() {
        return bestiaryGui;
    }
}
