package ru.cherdakmd.chrdkreferrals;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.List;

/**
 * CHRDK_Referrals — реферальная система-аддон: новичок привязывается к пригласившему
 * ({@code /ref use <ник>}) до первой подписки, при его первой оплате оба получают бонусные
 * часы через API ядра ({@code PassGrantedEvent} + {@code ChrdkRegisterApi.grantPassHours}).
 */
public final class ReferralsPlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile ReferralConfig config;
    private ReferralStore store;
    private ReferralService service;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = new ReferralConfig(getConfig());
        this.store = new ReferralStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        this.service = new ReferralService(this, store);

        Bukkit.getPluginManager().registerEvents(new ReferralListener(this, service), this);
        ReferralCommandHandler handler = new ReferralCommandHandler(this, service);
        for (String name : List.of("ref", "refadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(handler);
                command.setTabCompleter(handler);
            }
        }

        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (store.isDirty()) {
                store.saveNow();
            }
        }, 6000L, 6000L);

        getLogger().info("CHRDK_Referrals включён: бонус друга +" + config.bonusHoursInviter()
                + " / приглашённого +" + config.bonusHoursInvitee() + " ч.");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (store != null) {
            store.saveNow();
        }
        getLogger().info("CHRDK_Referrals выключен.");
    }

    /** Перечитывает config.yml и messages.yml (/refadmin reload). */
    public void reloadReferralConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.config = new ReferralConfig(getConfig());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public Messages messages() {
        return messages;
    }

    public ReferralConfig config() {
        return config;
    }
}
