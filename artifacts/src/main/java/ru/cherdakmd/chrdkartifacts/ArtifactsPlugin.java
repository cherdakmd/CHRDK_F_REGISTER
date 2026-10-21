package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
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
    private CollectionService collection;
    private CollectionGui collectionGui;
    private ArtifactFeedback feedback;
    private BukkitTask saveTask;

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
        this.collection = new CollectionService(this);
        this.collectionGui = new CollectionGui(this);
        reloadArtifactsConfig();

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
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
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
