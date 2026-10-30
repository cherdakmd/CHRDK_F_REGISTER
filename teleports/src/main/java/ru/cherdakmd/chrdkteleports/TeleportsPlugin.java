package ru.cherdakmd.chrdkteleports;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.List;

/**
 * CHRDK_Teleports — телепортация за алмазы: RTP в радиусе, TPA-запросы, точки дома.
 * Авторизация и VIP-скидка берутся из API ядра ({@code ChrdkRegisterApi}).
 */
public final class TeleportsPlugin extends JavaPlugin implements org.bukkit.event.Listener {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile TpConfig config;
    private HomeStore store;
    private ru.cherdakmd.chrdkregister.api.PaidActions payments;
    private TeleportService teleports;
    private TpaRequests tpaRequests;
    private TeleportCommandHandler commandHandler;
    private BukkitTask saveTask;
    private BukkitTask sweepTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadTeleportsConfig();
        try {
            if (coreApi == null || ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 30) throw new IllegalStateException("Нужен Core API 30+");
            this.store = new HomeStore(getDataFolder().toPath(), getLogger());
            this.payments = new ru.cherdakmd.chrdkregister.api.PaidActions(this,this::coreApi,this::messages,"teleports");
            if (!payments.store().valuesInitialized()) store.load();
            store.bind(payments.store());
        } catch (Exception | LinkageError error) {
            getLogger().log(java.util.logging.Level.SEVERE,"Телепорты остановлены без сброса данных",error);
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        // Ядро 1.4.106+: случайный спавн не трогает игрока, у которого уже есть дом.
        // Через отдельный мост: со старым ядром модуль продолжает работать без этой связи.
        try {
            TeleportsSpawnBridge.register(this);
        } catch (Throwable throwable) {
            getLogger().warning("[CHRDK_Teleports] мост случайного спавна недоступен "
                    + "(нужно ядро 1.4.106+): " + throwable);
        }
        this.teleports = new TeleportService(this);
        this.tpaRequests = new TpaRequests();
        this.commandHandler = new TeleportCommandHandler(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(teleports, this);
        // Кровать = дом: точка ставится при сне, смерть возвращает к ней.
        Bukkit.getPluginManager().registerEvents(new BedHomeListener(this), this);

        for (String name : List.of("rtp", "tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel",
                "home", "sethome", "delhome", "homes", "tpadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(commandHandler);
                command.setTabCompleter(commandHandler);
            }
        }

        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (store.isDirty()) {
                store.saveNow();
            }
        }, 6000L, 6000L);

        this.sweepTask = Bukkit.getScheduler().runTaskTimer(this, this::notifyExpiredRequests,
                20L, 20L);

        getLogger().info("CHRDK_Teleports включён: радиус RTP " + config.rtpRadius()
                + " блоков, лимит домов " + config.homesMaxNormal() + "+" + ".");
    }

    @Override
    public void onDisable() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (teleports != null) teleports.shutdown();
        if (store != null) store.saveNow();
        getLogger().info("CHRDK_Teleports выключен.");
    }

    /** Перечитывает config.yml/messages.yml. */
    public void reloadTeleportsConfig() {
        reloadConfig();
        ru.cherdakmd.chrdkregister.api.EconomyTariffMigration.apply(this);
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = TpConfig.parse(getConfig(), getLogger());
    }

    /** Рассылает уведомления об истёкших TPA-запросах. */
    private void notifyExpiredRequests() {
        payments.tick();
        for (TpaRequests.Request expired : tpaRequests.sweepExpired(System.currentTimeMillis())) {
            Player requester = Bukkit.getPlayer(expired.requester());
            Player target = Bukkit.getPlayer(expired.target());
            if (requester != null) {
                messages.send(requester, "tpa.expired-requester",
                        "player", displayName(expired.target()));
            }
            if (target != null) {
                messages.send(target, "tpa.expired-target",
                        "player", displayName(expired.requester()));
            }
        }
    }

    public static String displayName(java.util.UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player.getName() : uuid.toString();
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public Messages messages() {
        return messages;
    }

    public TpConfig config() {
        return config;
    }

    public ru.cherdakmd.chrdkregister.api.PaidActions payments() { return payments; }
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.LOWEST)
    public void onAuthenticated(ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent event) { payments.recover(event.getPlayer()); }
    public HomeStore store() {
        return store;
    }

    public TeleportService teleports() {
        return teleports;
    }

    public TpaRequests tpaRequests() {
        return tpaRequests;
    }

    public TeleportCommandHandler commandHandler() {
        return commandHandler;
    }
}
