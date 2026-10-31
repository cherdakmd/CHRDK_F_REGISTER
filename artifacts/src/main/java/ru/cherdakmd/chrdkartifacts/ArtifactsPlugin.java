package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkArtifactsApi;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Artifacts — 65 артефактов (33 светлых, 32 проклятых; 30 базовых + 35
 * «по мотивам CHRDK_REBORN»), работающих из инвентаря. Пассивные вешают зельевые
 * эффекты пульсом или отвечают на игровые события; активные срабатывают по ПКМ за
 * уровни опыта и кулдаун. Жёстко зависит только от ядра; CHRDK_Enchants —
 * мягкая зависимость (без него молчит одна «Хартия чар», остальные работают).
 * Метки артефактов живут в PDC предметов; с 1.4.130 рядом появились два
 * небольших файла состояния: {@code collection.json} (открытия коллекции и
 * выданные награды) и {@code cooldowns.json} (кулдауны переживают перезапуск).
 * С 1.4.129 (волна 19) каждый артефакт при выдаче получает 0–2 уникальных
 * свойства «▲/▼» из каталога {@link ArtifactTraits}: два одинаковых предмета
 * больше не обязаны быть одинаковыми.
 */
public final class ArtifactsPlugin extends JavaPlugin {

    private volatile ChrdkRegisterApi coreApi;
    private volatile Messages messages;
    private volatile ArtifactsConfig config;
    private ArtifactItems items;
    private Cooldowns cooldowns;
    private CooldownStore cooldownStore;
    private ArtifactTraitService traits;
    private PassiveEffectsService passives;
    private ArtifactLifecycleListener lifecycle;
    private CollectionService collection;
    private CollectionGui collectionGui;
    private ArtifactFeedback feedback;
    /** 1.4.166: мост для других аддонов (клады выдают настоящие артефакты). */
    private ArtifactsApiBridge apiBridge;
    private BukkitTask saveTask;

    private ru.cherdakmd.chrdkregister.api.MilestoneAwards awards;
    public ru.cherdakmd.chrdkregister.api.MilestoneAwards awards() { return awards; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.items = new ArtifactItems(this);
        this.cooldowns = new Cooldowns();
        this.cooldownStore = new CooldownStore(getDataFolder().toPath(), getLogger());
        this.cooldowns.restore(cooldownStore.load());
        this.traits = new ArtifactTraitService(this);
        this.passives = new PassiveEffectsService(this);
        this.feedback = new ArtifactFeedback(this);
        reloadArtifactsConfig();
        try {
            if (coreApi == null || ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 20) throw new IllegalStateException("Нужен Core API 20+");
            this.awards = new ru.cherdakmd.chrdkregister.api.MilestoneAwards(this,this::coreApi,this::messages,"artifact-awards",ru.cherdakmd.chrdkregister.api.MilestoneMigration.json(getDataFolder().toPath().resolve("collection.json"),ru.cherdakmd.chrdkregister.api.MilestoneMigration.Kind.ARTIFACTS));
        } catch (Exception | LinkageError error) {
            getLogger().log(java.util.logging.Level.SEVERE,"Модуль остановлен без сброса прогресса/наград",error);
            Bukkit.getPluginManager().disablePlugin(this); return;
        }
        this.collection = new CollectionService(this);
        awards.reconciler(player -> collection.claim(player));
        this.collectionGui = new CollectionGui(this);
        reloadArtifactsConfig();

        this.lifecycle = new ArtifactLifecycleListener(this, passives);
        Bukkit.getPluginManager().registerEvents(lifecycle, this);
        Bukkit.getPluginManager().registerEvents(new ActiveEffectsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ArtifactLootListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ArtifactEventListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CollectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(collectionGui, this);
        // Периодическая запись состояния (5 сек): кулдауны и коллекция.
        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                this::flushState, 100L, 100L);

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
        // 1.4.166: публичный мост — другие аддоны спрашивают каталог и выдают
        // предметы артефактов, не завися от этого JAR.
        this.apiBridge = new ArtifactsApiBridge(this);
        getServer().getServicesManager().register(ChrdkArtifactsApi.class, apiBridge,
                this, org.bukkit.plugin.ServicePriority.Normal);

        if (!EnchantBridge.available()) {
            getLogger().warning("CHRDK_Enchants не найден: «Хартия чар» не сможет накатывать"
                    + " чары, остальные артефакты работают как обычно.");
        }
        getLogger().info("CHRDK_Artifacts включён: артефактов в каталоге " + ArtifactRegistry.size()
                + " (светлых " + ArtifactRegistry.bySide(ArtifactDef.Side.GOOD).size()
                + ", проклятых " + ArtifactRegistry.bySide(ArtifactDef.Side.CURSED).size()
                + "), свойств артефактов " + ArtifactTraits.size()
                + " (польза " + ArtifactTraits.good().size()
                + ", вред " + ArtifactTraits.bad().size() + ").");
    }

    @Override
    public void onDisable() {
        if (awards != null) awards.close();
        if (apiBridge != null) {
            getServer().getServicesManager().unregister(ChrdkArtifactsApi.class, apiBridge);
            apiBridge = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (lifecycle != null) lifecycle.close();
        if (passives != null) {
            passives.stop();
        }
        if (feedback != null) {
            feedback.clear();
        }
        // Кулдауны и коллекция: сначала фиксируем на диск, только потом чистим память.
        if (cooldowns != null) {
            cooldowns.prune(System.currentTimeMillis());
            if (cooldownStore != null && cooldowns.isDirty()) {
                cooldownStore.save(cooldowns.snapshot());
            }
            cooldowns.clear();
        }
        if (collection != null) {
            collection.flush();
        }
        getLogger().info("CHRDK_Artifacts выключен.");
    }

    /** Асинхронная запись состояния; ошибки не должны ронять планировщик. */
    private void flushState() {
        try {
            if (collection != null && collection.isDirty()) {
                collection.saveNow();
            }
            if (cooldowns != null) {
                cooldowns.prune(System.currentTimeMillis());
                if (cooldowns.isDirty() && cooldownStore != null) {
                    cooldownStore.save(cooldowns.snapshot());
                    cooldowns.markClean();
                }
            }
        } catch (RuntimeException exception) {
            getLogger().warning("Не удалось сохранить состояние артефактов: "
                    + exception.getMessage());
        }
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

    /** Уникальные свойства артефактов (1.4.129). */
    public ArtifactTraitService traits() {
        return traits;
    }

    /** Коллекция артефактов и награды (1.4.130). */
    public CollectionService collection() {
        return collection;
    }

    /** GUI коллекции (1.4.130). */
    public CollectionGui collectionGui() {
        return collectionGui;
    }

    /** Экшнбар, звуки и антиспам проклятий (1.4.130). */
    public ArtifactFeedback feedback() {
        return feedback;
    }
}
