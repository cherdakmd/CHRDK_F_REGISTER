package ru.cherdakmd.chrdkstreams;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

/**
 * CHRDK_Streams — стримерская программа сервера.
 *
 * <p>Игрок привязывает канал на Twitch или VK Play Live, подтверждает владение
 * кодом {@code CHRDK-XXXX} в названии эфира, и каждый час подтверждённого эфира
 * получает сутки проходки. Награда ограничена дневным и общим потолком, поэтому
 * программа не превращается в способ играть бесплатно круглосуточно.</p>
 *
 * <p>Аддон зависит от {@code CHRDK_Core}: дни проходки выдаёт его публичный API,
 * тексты берутся из {@link Messages}. Без ядра плагин просто не запустится —
 * это лучше, чем молча не выдавать награды.</p>
 */
public final class StreamPlugin extends JavaPlugin {

    /** Ключ автосохранения: прогресс не должен теряться при падении сервера. */
    static final String AUTO_SAVE_KEY = "general.auto-save-minutes";
    static final int DEFAULT_AUTO_SAVE_MINUTES = 5;
    static final int MAX_AUTO_SAVE_MINUTES = 60;

    private volatile Messages messages;
    private volatile StreamConfig config;
    private volatile ChrdkRegisterApi coreApi;

    private StreamStore store;
    private StreamService service;
    private ViewerStore viewerStore;
    private ViewerService viewerService;
    private StreamerTagService tagService;
    private BukkitTask autoSaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = Messages.load(this,
                getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = StreamConfig.from(getConfig());

        this.store = new StreamStore(getDataFolder().toPath(), getLogger());
        store.load();
        this.viewerStore = new ViewerStore(getDataFolder().toPath(), getLogger());
        viewerStore.load();

        this.service = new StreamService(this, store, config);
        this.viewerService = new ViewerService(this, store, viewerStore, service, config);
        this.tagService = new StreamerTagService(this, store, config);

        PluginCommand command = getCommand("stream");
        if (command != null) {
            StreamCommandHandler handler = new StreamCommandHandler(this, service);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        service.start();
        viewerService.start();
        restartAutoSave();
        tagService.update(config, manageTabNames());
        registerNameTag();
        logStartup();
        registerPlaceholders();
    }

    @Override
    public void onDisable() {
        stopAutoSave();
        unregisterNameTag();
        if (tagService != null) {
            tagService.stop();
        }
        if (viewerService != null) {
            viewerService.stop();
        }
        if (service != null) {
            service.stop();
        }
        if (store != null) {
            store.save();
        }
        if (viewerStore != null) {
            viewerStore.save();
        }
        getLogger().info("CHRDK_Streams выключен.");
    }

    /** Перечитать config.yml и messages.yml, перезапустив опрос без рестарта сервера. */
    public void reloadStreams() {
        reloadConfig();
        this.messages = Messages.load(this,
                getConfig().getString("general.locale", "ru"), getLogger());
        this.config = StreamConfig.from(getConfig());
        this.service.update(config);
        this.viewerService.update(config);
        this.tagService.update(config, manageTabNames());
        restartAutoSave();
        registerNameTag();
        logStartup();
    }

    /**
     * Периодическая запись данных: события сохраняются сразу, но прогресс,
     * накопленный в памяти между ними, до диска доходит только здесь. При
     * падении сервера теряется максимум {@code general.auto-save-minutes}.
     * {@code 0} — выключить (тогда сохранение только по событиям и при stop).
     */
    private void restartAutoSave() {
        stopAutoSave();
        int minutes = Math.clamp(getConfig().getInt(AUTO_SAVE_KEY, DEFAULT_AUTO_SAVE_MINUTES),
                0, MAX_AUTO_SAVE_MINUTES);
        if (minutes <= 0) {
            getLogger().info("[Streams] Автосохранение выключено (general.auto-save-minutes: 0).");
            return;
        }
        long ticks = minutes * 60L * 20L;
        autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, this::saveAllSafely, ticks, ticks);
        getLogger().info("[Streams] Автосохранение каждые " + minutes + " мин.");
    }

    private void stopAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }

    private void saveAllSafely() {
        try {
            if (store != null) {
                store.save();
            }
            if (viewerStore != null) {
                viewerStore.save();
            }
        } catch (RuntimeException exception) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "[Streams] Ошибка автосохранения", exception);
        }
    }

    private void logStartup() {
        StreamConfig cfg = config;
        if (!cfg.enabled()) {
            getLogger().info("[Streams] Программа выключена (streams.enabled: false).");
            return;
        }
        if (coreApi() == null) {
            getLogger().warning("[Streams] CHRDK_Core не найден: награды выдавать некуда. "
                    + "Проверьте, что ядро загружается раньше аддона.");
        }
        if (cfg.platforms().isEmpty()) {
            getLogger().warning("[Streams] Ни одна площадка не настроена: "
                    + "заполните streams.twitch.client-id/client-secret "
                    + "или включите streams.vkplay.");
            return;
        }
        if (cfg.twitch().enabled() && !cfg.twitch().configured()) {
            getLogger().warning("[Streams] Twitch выключен: нет client-id/client-secret "
                    + "(https://dev.twitch.tv/console/apps). VK Play Live работает и так.");
        }
        getLogger().info("[Streams] Готово: " + cfg.platforms() + ", "
                + cfg.minutesPerDay() + " мин. эфира = 1 сутки, максимум "
                + cfg.maxDaysPerDay() + " суток в день, привязок " + store.size() + ".");
        if (cfg.tags().enabled()) {
            // manageTabNames() опрашивает Bukkit — только из основного потока.
            boolean tab = manageTabNames();
            getLogger().info("[Streams] Метка стримера: [" + cfg.tags().label() + "]"
                    + (tab ? " в чате и TAB."
                    : " в чате, TAB ведёт ядро (добавьте %player_tagged% в tablist)."));
        }
        if (cfg.viewers().enabled()) {
            getLogger().info("[Streams] Просмотр зрителей: " + cfg.viewers().minutesPerDay()
                    + " мин. = 1 сутки, до " + cfg.viewers().maxDaysPerDay() + "/день, "
                    + cfg.viewers().maxTotalDays() + " всего, код раз в "
                    + cfg.viewers().codeWindowMinutes() + " мин.");
        }
    }

    /** id оформления ника в API ядра: повторная регистрация заменяет прежнюю. */
    static final String NAME_TAG_ID = "streams";

    /** Подключает метку стримера к чату/личным сообщениям ядра. */
    private void registerNameTag() {
        ChrdkRegisterApi api = coreApi();
        if (api == null || tagService == null) {
            return;
        }
        try {
            api.registerNameTag(NAME_TAG_ID, tagService::decorate);
        } catch (LinkageError error) {
            // Ядро старше API v6: метода нет. Аддон обязан работать дальше —
            // без метки в чате, но с наградами.
            getLogger().warning("[Streams] Ядро не поддерживает оформление ников (API v6): "
                    + "метка стримера в чате отключена. Обновите CHRDK_Core.");
        }
    }

    /** Снимает метку при выключении/перезагрузке; старые ядра просто пропускаем. */
    private void unregisterNameTag() {
        ChrdkRegisterApi api = coreApi();
        if (api == null) {
            return;
        }
        try {
            api.unregisterNameTag(NAME_TAG_ID);
        } catch (LinkageError error) {
            // API v5 и старше — снимать нечего.
        }
    }

    /**
     * Можно ли управлять именем в TAB: ядро не должно красить имя своим
     * {@code tablist.player-name-format}, иначе оно перезапишет метку.
     */
    private boolean manageTabNames() {
        StreamConfig cfg = config;
        if (!cfg.tags().enabled() || !cfg.tags().tab()) {
            return false;
        }
        Plugin core = Bukkit.getPluginManager().getPlugin("CHRDK_Core");
        if (core instanceof JavaPlugin corePlugin) {
            String format = corePlugin.getConfig().getString("tablist.player-name-format", "");
            return format == null || format.isBlank();
        }
        return true;
    }

    /** PlaceholderAPI — мягкая интеграция: без плагина аддон работает как обычно. */
    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new StreamPlaceholders(this).register();
            getLogger().info("[Streams] PlaceholderAPI: доступны %chrdkstreams_...%.");
        } catch (RuntimeException exception) {
            getLogger().warning("[Streams] Не удалось зарегистрировать PlaceholderAPI: "
                    + exception.getMessage());
        }
    }

    public Messages messages() {
        return messages;
    }

    public StreamConfig config() {
        return config;
    }

    public StreamService service() {
        return service;
    }

    public ViewerService viewerService() {
        return viewerService;
    }

    public ViewerStore viewerStore() {
        return viewerStore;
    }

    public StreamerTagService tagService() {
        return tagService;
    }

    public StreamStore store() {
        return store;
    }

    /** API ядра; кэшируется, но при поздней загрузке ядра подхватится. */
    public ChrdkRegisterApi coreApi() {
        ChrdkRegisterApi api = coreApi;
        if (api == null) {
            api = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            coreApi = api;
        }
        return api;
    }
}
