package ru.cherdakmd.chrdkparty;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Party — группы игроков: пати-чат, общий бонус и защита от своих (1.4.158).
 *
 * <p>Группа не даёт ни урона, ни брони, ни алмазов. Её выгода — опыт за игру
 * рядом (с потолком) и тишина в чате: свои слышат своего. Этого достаточно,
 * чтобы игроки звали друг друга, и мало, чтобы группа заменила прокачку.</p>
 *
 * <p>Группы переживают перезапуск ({@code parties.json}); приглашения — нет,
 * у них есть срок жизни.</p>
 */
public final class PartyPlugin extends JavaPlugin {

    private PartyConfig config;
    private Messages messages;
    private PartyStore store;
    private PartyService service;
    private BukkitTask saveTask;
    private BukkitTask queuedSave;
    private boolean saveQueued;
    private ChrdkRegisterApi coreApi;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            this.config = PartyConfig.parse(getConfig(), getLogger());
            this.messages = Messages.load(this, config.locale(), getLogger());
            this.store = new PartyStore(getDataFolder().toPath(), getLogger());
            store.load();
            this.service = new PartyService(config, store);
            this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            if (coreApi != null) {
                try {
                    coreApi.registerProfile("party", new PartyProfile(this));
                } catch (RuntimeException | LinkageError error) {
                    getLogger().warning("[CHRDK_Party] раздел профиля недоступен "
                            + "(нужно ядро 1.4.156+): " + error);
                }
            }
            getServer().getPluginManager().registerEvents(new PartyListener(this), this);
            registerCommands();
            registerPlaceholders();
            this.saveTask = getServer().getScheduler().runTaskTimerAsynchronously(this,
                    this::saveNow, 100L, 100L);
            getLogger().info("CHRDK_Party включён: групп " + store.size()
                    + ", участников " + store.memberCount()
                    + ", потолок " + config.maxSize()
                    + ", бонус до +" + config.maxBonusPercent() + "%.");
        } catch (RuntimeException exception) {
            getLogger().severe("CHRDK_Party не смог запуститься: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("party");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
            this.coreApi = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (queuedSave != null) {
            queuedSave.cancel();
            queuedSave = null;
        }
        if (store != null) {
            store.flush();
        }
        getLogger().info("CHRDK_Party выключен.");
    }

    /** Перечитывает конфиг и тексты, не трогая сами группы. */
    public void reloadParty() {
        reloadConfig();
        this.config = PartyConfig.parse(getConfig(), getLogger());
        this.messages = Messages.load(this, config.locale(), getLogger());
        // Сервис остаётся тем же: в нём живут отправленные приглашения.
        this.service.applyConfig(config);
    }

    private void registerCommands() {
        PartyCommandHandler handler = new PartyCommandHandler(this);
        for (String name : new String[]{"party", "pc", "partyadmin"}) {
            PluginCommand command = getCommand(name);
            if (command == null) {
                getLogger().warning("Команда " + name + " не объявлена в plugin.yml — модуль без неё.");
                continue;
            }
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    /** PlaceholderAPI — мягкая интеграция: без плагина группы работают как обычно. */
    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new PartyPlaceholders(this).register();
            getLogger().info("PlaceholderAPI: доступны %chrdkparty_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
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
            getLogger().warning("Не удалось сохранить parties.json: " + exception.getMessage());
        }
    }

    public PartyConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public PartyStore store() {
        return store;
    }

    public PartyService service() {
        return service;
    }
}
