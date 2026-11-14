package ru.cherdakmd.chrdkmobs;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.ConfigUpgrader;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.MoneyLoot;

/**
 * CHRDK_Mobs — тиры мобов: чем дальше от спавна мира, тем сильнее
 * естественные враги и щедрее награда за их убийство. Награды идут деньгами
 * в {@code /bal} через общий {@link MoneyLoot} ядра (с обязательством при
 * недоступном банке), без физических алмазов и без повторных выплат.
 */
public final class MobsPlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile MobsConfig config;
    private volatile MoneyLoot loot;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpgrader.apply(this);
        reloadMobs();
        if (coreApi != null) {
            try {
                this.loot = new MoneyLoot(this, this::coreApi, this::messages, "mob-tiers");
            } catch (RuntimeException error) {
                getLogger().warning("Награды за тиры мобов отключены: " + error.getMessage());
            }
        }
        Bukkit.getPluginManager().registerEvents(new MobTierListener(this), this);
        var mobtiers = getCommand("mobtiers");
        if (mobtiers != null) {
            var handler = new MobTierCommand(this);
            mobtiers.setExecutor(handler);
            mobtiers.setTabCompleter(handler);
        }
        getLogger().info("Тиры мобов: " + (config == null ? "конфиг не прочитан" : config.describe()));
    }

    @Override
    public void onDisable() {
        if (loot != null) {
            loot.close();
            loot = null;
        }
    }

    /** Перечитывает конфиг и сообщения; ядро подгружается тем же порядком, что и в аддонах. */
    public void reloadMobs() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = MobsConfig.parse(getConfig(), getLogger());
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public Messages messages() {
        return messages;
    }

    public MobsConfig config() {
        return config;
    }

    public MoneyLoot loot() {
        return loot;
    }
}
