package ru.cherdakmd.chrdkhunt;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Hunt — охота на мобов: монеты, осколки, трофеи и ключи (1.4.155).
 *
 * <p>Монеты и осколки — не предметы, а кошелёк в {@code hunt.json}: их нельзя
 * потерять при смерти и нельзя продублировать. Спавнеры и яйца не дают добычи,
 * поэтому охота — это поход в мир, а не ферма на участке.</p>
 */
public final class HuntPlugin extends JavaPlugin {

    private HuntConfig config;
    private Messages messages;
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
            this.config = HuntConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
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
            registerCommands();
            registerPlaceholders();
            scheduleTasks();
            getLogger().info("CHRDK_Hunt включён: семейств " + config.families().size()
                    + ", правил мобов " + config.mobRules().size()
                    + ", позиций лавки " + config.shop().size()
                    + ", трофеев " + config.trophies().size()
                    + ", видов в бестиарии " + config.bestiary().totalEntries()
                    + ", охотников в базе " + store.players() + ".");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Hunt не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
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
    public void reloadHunt() {
        reloadConfig();
        this.config = HuntConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        if (progression != null) progression.reload();
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
        return config;
    }

    public Messages messages() {
        return messages;
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
