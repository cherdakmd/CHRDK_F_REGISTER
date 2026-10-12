package ru.cherdakmd.chrdktierforge;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

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
    private TierItems items;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadTierConfig();
        this.items = new TierItems(this);

        Bukkit.getPluginManager().registerEvents(new TierCraftListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TierAnvilListener(this), this);

        TierCommandHandler handler = new TierCommandHandler(this);
        for (String name : List.of("tiers", "tiersadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(handler);
                command.setTabCompleter(handler);
            }
        }
        getLogger().info("CHRDK_TierForge включён: " + config.tiers().size() + " тиров.");
    }

    /** Перечитывает config.yml/messages.yml. */
    public void reloadTierConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = TierForgeConfig.parse(getConfig(), getLogger());
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

    public TierItems items() {
        return items;
    }
}
