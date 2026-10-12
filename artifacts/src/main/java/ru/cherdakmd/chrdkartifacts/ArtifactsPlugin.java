package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Artifacts — 30 артефактов (15 светлых, 15 проклятых), работающих из инвентаря.
 * Пассивные вешают зельевые эффекты пульсом; активные срабатывают по ПКМ за уровни
 * опыта и кулдаун. Жёстко зависит от ядра и CHRDK_Enchants (хартия чар выкатывает
 * случайную кастомную чару). Хранилища нет: метки артефактов живут в PDC предметов,
 * кулдауны — в памяти.
 */
public final class ArtifactsPlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile ArtifactsConfig config;
    private ArtifactItems items;
    private Cooldowns cooldowns;
    private PassiveEffectsService passives;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.items = new ArtifactItems(this);
        this.cooldowns = new Cooldowns();
        this.passives = new PassiveEffectsService(this);
        reloadArtifactsConfig();

        Bukkit.getPluginManager().registerEvents(new ActiveEffectsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ArtifactLootListener(this), this);

        ArtifactCommandHandler commands = new ArtifactCommandHandler(this);
        PluginCommand artifacts = getCommand("artifacts");
        PluginCommand admin = getCommand("artifactsadmin");
        if (artifacts != null) {
            artifacts.setExecutor(commands);
        }
        if (admin != null) {
            admin.setExecutor(commands);
            admin.setTabCompleter(commands);
        }
        getLogger().info("CHRDK_Artifacts включён: артефактов в каталоге " + ArtifactRegistry.size()
                + " (светлых " + ArtifactRegistry.bySide(ArtifactDef.Side.GOOD).size()
                + ", проклятых " + ArtifactRegistry.bySide(ArtifactDef.Side.CURSED).size() + ").");
    }

    @Override
    public void onDisable() {
        if (passives != null) {
            passives.stop();
        }
        if (cooldowns != null) {
            cooldowns.clear();
        }
        getLogger().info("CHRDK_Artifacts выключен.");
    }

    /** Перечитывает config.yml/messages.yml и перезапускает пульс. */
    public void reloadArtifactsConfig() {
        reloadConfig();
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = ArtifactsConfig.parse(getConfig(), getLogger());
        if (passives != null) {
            passives.start();
        }
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public Messages messages() {
        return messages;
    }

    public ArtifactsConfig config() {
        return config;
    }

    public ArtifactItems items() {
        return items;
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }
}
