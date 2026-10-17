package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.List;

/**
 * CHRDK_Enchants — 30 кастомных эпичных чар. Источники: зачаровательный стол и
 * жители-библиотекари; книги чар накладываются на наковальне. Хранилище — только PDC
 * предметов, поэтому у плагина нет файла данных: всё живёт в инвентарях.
 *
 * <p>Эффекты сгруппированы слушателями: ближний бой, стрелы, броня/триггеры здоровья,
 * кирки, и периодический сервис аур (скорость/зрение/лава).</p>
 */
public final class EnchantsPlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile EnchantsConfig config;
    private EnchantItems items;
    private EnchantService service;
    private EnchantGui gui;
    private EnchantCommandHandler commandHandler;
    private AuraService auras;
    private BowEffectsListener bowEffects;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadEnchantsConfig();
        this.items = new EnchantItems(this);
        this.service = new EnchantService(this, items);
        this.gui = new EnchantGui(this);
        this.commandHandler = new EnchantCommandHandler(this);
        this.auras = new AuraService(this);

        Bukkit.getPluginManager().registerEvents(new TableListener(this), this);
        Bukkit.getPluginManager().registerEvents(new VillagerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AnvilApplyListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MeleeEffectsListener(this), this);
        this.bowEffects = new BowEffectsListener(this);
        Bukkit.getPluginManager().registerEvents(bowEffects, this);
        Bukkit.getPluginManager().registerEvents(new ArmorEffectsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ToolEffectsListener(this), this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        auras.start();

        for (String name : List.of("enchants", "enchantsadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(commandHandler);
                command.setTabCompleter(commandHandler);
            }
        }
        getLogger().info("CHRDK_Enchants включён: " + EnchantRegistry.size() + " чар в реестре.");
    }

    @Override
    public void onDisable() {
        if (auras != null) {
            auras.stop();
        }
        if (bowEffects != null) {
            bowEffects.stopSweep();
        }
        getLogger().info("CHRDK_Enchants выключен.");
    }

    /** Перечитывает config.yml/messages.yml. */
    public void reloadEnchantsConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = EnchantsConfig.parse(getConfig(), getLogger());
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public Messages messages() {
        return messages;
    }

    public EnchantsConfig config() {
        return config;
    }

    public EnchantItems items() {
        return items;
    }

    public EnchantService service() {
        return service;
    }

    public EnchantGui gui() {
        return gui;
    }

    public AuraService auras() {
        return auras;
    }
}
