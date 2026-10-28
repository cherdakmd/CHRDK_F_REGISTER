package ru.cherdakmd.chrdklottery;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import java.util.Objects;
import java.util.logging.Level;

/** CHRDK_Core-dependent weekly lottery addon. No Vault currency or paid tickets. */
public final class LotteryPlugin extends JavaPlugin {
    private ChrdkRegisterApi api;
    private Messages messages;
    private LotteryConfig config;
    private LotteryService service;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            api = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            if (api == null || ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 22) throw new IllegalStateException("Нужен CHRDK_Core API 22+");
            reloadLotteryConfig();
            LotteryStore store = new LotteryStore(getDataFolder().toPath(), getLogger());
            store.load();
            service = new LotteryService(this, store);
            Bukkit.getPluginManager().registerEvents(service, this);
            LotteryCommandHandler commands = new LotteryCommandHandler(this, service);
            for (String name : new String[]{"lottery", "lotteryadmin"}) {
                var command = Objects.requireNonNull(getCommand(name));
                command.setExecutor(commands); command.setTabCompleter(commands);
            }
            service.start();
            getLogger().info("CHRDK_Lottery enabled: " + config.drawDay() + " " + config.drawTime() + " " + config.zone());
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.SEVERE, "Lottery disabled. Existing ledger must not be overwritten.", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() { if (service != null) service.shutdown(); }
    public void reloadLotteryConfig() throws java.io.IOException {
        reloadConfig();
        config = LotteryConfig.parse(getConfig(), getLogger());
        messages = Messages.load(this, "ru", getLogger());
        if (service != null) { service.shutdown(); service.start(); }
    }
    public ChrdkRegisterApi coreApi() { return api; }
    public Messages messages() { return messages; }
    public LotteryConfig config() { return config; }
}
