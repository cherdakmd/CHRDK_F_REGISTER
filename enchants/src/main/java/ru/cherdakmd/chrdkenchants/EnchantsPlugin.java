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
 * CHRDK_Enchants — 72 кастомных чар: 57 светлых и 15 проклятий. Источники:
 * зачаровательный стол и жители-библиотекари; книги чар накладываются на
 * наковальне. Книги и чары хранятся в PDC предметов, в инвентарях. Отрицательные метки
 * установленной руды сохраняются в PDC чанков.
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
    private TableListener table;
    private EnchantCommandHandler commandHandler;
    private AuraService auras;
    private PveEffectsListener pve;
    private UtilityEffectsListener utility;
    private ToolEffectsListener tools;
    private MiningUtilityListener miningUtility;
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

        this.table = new TableListener(this);
        Bukkit.getPluginManager().registerEvents(table, this);
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
        MiningOrigin origin = new MiningOrigin(this);
        Bukkit.getPluginManager().registerEvents(origin, this);
        this.tools = new ToolEffectsListener(this, origin);
        Bukkit.getPluginManager().registerEvents(tools, this);
        this.miningUtility = new MiningUtilityListener(this);
        Bukkit.getPluginManager().registerEvents(miningUtility, this);
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
        if (table != null) table.clear();
        if (tools != null) tools.clear();
        if (miningUtility != null) miningUtility.clear();
        if (gui != null) gui.stop();
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
        if (table != null) table.clear();
        if (tools != null) tools.clear();
        if (miningUtility != null) miningUtility.clear();
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = EnchantsConfig.parse(getConfig(), getLogger());
        if (gui != null) gui.closeAll();
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
