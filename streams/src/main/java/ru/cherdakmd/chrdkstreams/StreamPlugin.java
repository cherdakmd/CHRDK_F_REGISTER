package ru.cherdakmd.chrdkstreams;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
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

    private volatile Messages messages;
    private volatile StreamConfig config;
    private volatile ChrdkRegisterApi coreApi;

    private StreamStore store;
    private StreamService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.messages = Messages.load(this,
                getConfig().getString("general.locale", "ru"), getLogger());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        this.config = StreamConfig.from(getConfig());

        this.store = new StreamStore(getDataFolder().toPath(), getLogger());
        store.load();

        this.service = new StreamService(this, store, config);

        PluginCommand command = getCommand("stream");
        if (command != null) {
            StreamCommandHandler handler = new StreamCommandHandler(this, service);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        service.start();
        logStartup();
        registerPlaceholders();
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.stop();
        }
        if (store != null) {
            store.save();
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
        logStartup();
    }

    private void logStartup() {
        StreamConfig cfg = config;
        if (!cfg.enabled()) {
            getLogger().info("[Streams] Программа выключена (streams.enabled: false).");
            return;
        }
        if (coreApi == null) {
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
