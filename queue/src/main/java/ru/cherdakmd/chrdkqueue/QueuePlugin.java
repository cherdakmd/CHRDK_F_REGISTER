package ru.cherdakmd.chrdkqueue;

import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;

/**
 * CHRDK_Queue — очередь входа на переполненный сервер.
 *
 * <p>Обычные игроки встают в очередь (FIFO с бронированием освободившегося слота
 * на {@code reservation-seconds} секунд), VIP по LuckPerms-группам заходит мимо.
 * Вся "входная торговля" идёт через pre-login — до регистрации, конфликт с ядром
 * исключён (оно самодостаточно ограничивает по проходке, когда та активна).</p>
 */
public final class QueuePlugin extends JavaPlugin {

    private volatile Messages messages;
    private volatile QueueService queueService;
    private volatile LuckPerms luckPerms;
    private volatile ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi coreApi;

    /** Публичный API ядра: очередь спрашивает ступень донатного ранга (1.4.167). */
    public ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.queueService = new QueueService(QueueConfig.from(getConfig()));
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
        this.coreApi = Bukkit.getServicesManager().load(ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.class);

        Bukkit.getPluginManager().registerEvents(new QueueLoginListener(this), this);

        PluginCommand command = getCommand("queue");
        if (command != null) {
            QueueCommandHandler handler = new QueueCommandHandler(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        QueueConfig cfg = queueService.config();
        getLogger().info("CHRDK_Queue включён: обычных слотов " + cfg.regularLimit()
                + ", VIP-групп " + cfg.vipGroups().size()
                + (luckPerms == null ? " — LuckPerms НЕ найден, все игроки обычные!" : "."));

        int serverMax = Bukkit.getMaxPlayers();
        if (serverMax <= cfg.regularLimit()) {
            getLogger().warning("[Queue] max-players (" + serverMax + ") <= regular-limit ("
                    + cfg.regularLimit() + ")! VIP не сможет зайти на переполненный сервер — "
                    + "поднимите max-players в server.properties.");
        }
    }

    /** Перечитать config.yml + messages без перезапуска. */
    public void reloadQueue() {
        reloadConfig();
        queueService.updateConfig(QueueConfig.from(getConfig()));
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
    }

    public Messages messages() {
        return messages;
    }

    public QueueService queueService() {
        return queueService;
    }

    public LuckPerms luckPerms() {
        return luckPerms;
    }
}
