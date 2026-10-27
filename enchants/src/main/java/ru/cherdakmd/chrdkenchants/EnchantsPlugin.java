package ru.cherdakmd.chrdkenchants;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkEnchantsApi;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.List;

/**
 * CHRDK_Enchants — 70 кастомных чар: 55 светлых и 15 проклятий. Источники:
 * зачаровательный стол и жители-библиотекари; книги чар накладываются на
 * наковальне. Хранилище — только PDC предметов, поэтому у плагина нет файла
 * данных: всё живёт в инвентарях.
 *
 * <p>Эффекты сгруппированы слушателями: ближний бой, стрелы, броня/триггеры
 * здоровья, кирки, светлые чары и проклятия (1.4.128) и периодический сервис аур
 * (скорость/зрение/лава/регенерация/проклятая кровь).</p>
 *
 * <p>С 1.4.128 модуль публикует {@link ChrdkEnchantsApi} в {@code ServicesManager}:
 * CHRDK_TierForge накладывает кастомные чары на тир-предметы, не завися от
 * внутренних классов.</p>
 */
public final class EnchantsPlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile EnchantsConfig config;
    private long sourceGeneration;
    long sourceGeneration() { return sourceGeneration; }
    private EnchantItems items;
    private EnchantService service;
    private EnchantGui gui;
    private EnchantCommandHandler commandHandler;
    private AuraService auras;
    private PveEffectsListener pve;
    private UtilityEffectsListener utility;
    private BowEffectsListener bowEffects;
    private EnchantsApiBridge apiBridge;

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
        Bukkit.getPluginManager().registerEvents(new MobBookListener(this), this);
        this.pve = new PveEffectsListener(this);
        Bukkit.getPluginManager().registerEvents(pve, this);
        this.utility = new UtilityEffectsListener(this);
        Bukkit.getPluginManager().registerEvents(utility, this);
        Bukkit.getPluginManager().registerEvents(new VillagerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AnvilApplyListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MeleeEffectsListener(this), this);
        this.bowEffects = new BowEffectsListener(this);
        Bukkit.getPluginManager().registerEvents(bowEffects, this);
        Bukkit.getPluginManager().registerEvents(new ArmorEffectsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ToolEffectsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PolarityEffectsListener(this), this);
        // 1.4.140: старые предметы с чарами потеряли ванильный урон — чиним на входе.
        Bukkit.getPluginManager().registerEvents(new AttributeRepairListener(this), this);
        Bukkit.getPluginManager().registerEvents(gui, this);
        auras.start();
        this.apiBridge = new EnchantsApiBridge(this);
        Bukkit.getServicesManager().register(ChrdkEnchantsApi.class, apiBridge, this,
                ServicePriority.Normal);

        for (String name : List.of("enchants", "enchantsadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(commandHandler);
                command.setTabCompleter(commandHandler);
            }
        }
        getLogger().info("CHRDK_Enchants включён: " + EnchantRegistry.size() + " чар ("
                + EnchantRegistry.lightCount() + " светлых, "
                + EnchantRegistry.curseCount() + " проклятий).");
    }

    @Override
    public void onDisable() {
        sourceGeneration++;
        if (pve != null) pve.stop();
        if (utility != null) utility.stop();
        if (auras != null) {
            auras.stop();
        }
        if (bowEffects != null) {
            bowEffects.stopSweep();
        }
        if (apiBridge != null) {
            Bukkit.getServicesManager().unregister(ChrdkEnchantsApi.class, apiBridge);
            apiBridge = null;
        }
        getLogger().info("CHRDK_Enchants выключен.");
    }

    /** Перечитывает config.yml/messages.yml. */
    public void reloadEnchantsConfig() {
        sourceGeneration++;
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = EnchantsConfig.parse(getConfig(), getLogger());
        if (pve != null) pve.reload();
        if (utility != null) utility.reload();
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
