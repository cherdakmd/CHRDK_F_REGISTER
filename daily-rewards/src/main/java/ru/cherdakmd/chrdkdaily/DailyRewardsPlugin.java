package ru.cherdakmd.chrdkdaily;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.List;

/**
 * CHRDK_DailyRewards — ежедневные награды со стриком. Ветка VIP активируется активной
 * рангом ядра ({@code api.hasActivePass}), выдача VIP-часов — через {@code api.grantPassHours}.
 * Забирать награду могут только авторизованные игроки.
 */
public final class DailyRewardsPlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile DailyConfig config;
    private DailyStore store;
    private DailyService service;
    private DailyCalendarGui gui;
    private DailyCommandHandler commandHandler;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadDailyConfig(); // coreApi, messages, config
        this.store = new DailyStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        this.service = new DailyService(this, store);
        this.gui = new DailyCalendarGui(this);
        this.commandHandler = new DailyCommandHandler(this);

        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(new JoinReminderListener(), this);

        for (String name : List.of("daily", "dailyadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(commandHandler);
                command.setTabCompleter(commandHandler);
            }
        }

        if (coreApi != null) {
            try {
                coreApi.registerProfile("daily", new DailyProfile(this));
            } catch (RuntimeException | LinkageError error) {
                getLogger().warning("[CHRDK_DailyRewards] раздел профиля недоступен "
                        + "(нужно ядро 1.4.156+): " + error);
            }
        }

        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (store.isDirty()) {
                store.saveNow();
            }
        }, 6000L, 6000L);

        getLogger().info("CHRDK_DailyRewards включён: дней в цикле " + config.rewards().size() + ".");
    }

    @Override
    public void onDisable() {
        if (coreApi != null) {
            try {
                coreApi.unregisterProfile("daily");
            } catch (RuntimeException | LinkageError ignored) {
                // Ядро старше API v8 — снимать нечего.
            }
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (store != null) {
            store.saveNow();
        }
        getLogger().info("CHRDK_DailyRewards выключен.");
    }

    /** Перечитывает config.yml/messages.yml и пересобирает каталог наград. */
    public void reloadDailyConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = DailyConfig.parse(getConfig(), getLogger(), true);
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public Messages messages() {
        return messages;
    }

    public DailyConfig config() {
        return config;
    }

    public DailyStore store() {
        return store;
    }

    public DailyService service() {
        return service;
    }

    public DailyCalendarGui gui() {
        return gui;
    }

    public DailyCommandHandler commandHandler() {
        return commandHandler;
    }

    /** Напоминание подсоединившемуся авторизованному, если награда ждёт. */
    private final class JoinReminderListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPlayerJoin(PlayerJoinEvent event) {
            if (!config.joinReminderEnabled() || coreApi == null) return;
            var player = event.getPlayer();
            Bukkit.getScheduler().runTaskLater(DailyRewardsPlugin.this, () -> {
                if (player.isOnline()
                        && coreApi.isAuthenticated(player)
                        && service.canClaim(player)) {
                    messages.send(player, "daily.join-reminder");
                }
            }, 120L);
        }
    }
}
