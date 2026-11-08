package ru.cherdakmd.chrdkvk;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkregister.ConfigUpgrader;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.configuration.ConfigSnapshot;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;
import ru.cherdakmd.chrdksocial.MaxService;
import ru.cherdakmd.chrdksocial.SocialConfig;
import ru.cherdakmd.chrdksocial.SocialEventAnnouncer;
import ru.cherdakmd.chrdksocial.SocialEventConfig;
import ru.cherdakmd.chrdksocial.SocialInteractionConfig;
import ru.cherdakmd.chrdksocial.SocialInteractionHub;
import ru.cherdakmd.chrdksocial.SocialPlaceholders;
import ru.cherdakmd.chrdksocial.SocialService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Аддон {@code CHRDK_VK} — интеграция сервера с сообществом ВКонтакте:
 * привязка аккаунта по одноразовому коду, Bots Long Poll API, уведомления о входах
 * и переборе пароля, экстренный сброс сессий и заморозка входа (без смены пароля),
 * бонусы подписки и VK Donut, а также анонсы записей стены сообщества.
 */
public final class VkPlugin extends JavaPlugin {

    private static final String LEGACY_MESSAGES_1_4_268 = "vk-messages-legacy-1.4.268.yml";

    private volatile ChrdkRegisterApi coreApi;
    private volatile VkConfig config;
    private volatile Messages messages;
    private VkStore store;
    private VkService service;
    private SocialService socialService;
    private MaxService maxService;
    private SocialPlaceholders socialPlaceholders;
    private SocialEventAnnouncer eventAnnouncer;
    private SocialInteractionHub interactionHub;
    private WallDigestService wallDigest;
    private DailyCardsService dailyCards;
    private boolean placeholdersRetryScheduled;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpgrader.apply(this);
        migrateBundledMessages(getDataFolder().toPath().resolve("messages.yml"));
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.config = VkConfig.parse(getConfig());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if (this.coreApi == null) {
            getLogger().warning("[CHRDK_VK] Сервис ChrdkRegisterApi не найден — проверьте порядок запуска CHRDK_Core.");
        } else {
            try {
                if (ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 34) {
                    getLogger().severe("[CHRDK_VK] Требуется CHRDK_Core API 34+ (версия 1.4.277 или новее).");
                    this.coreApi = null;
                }
            } catch (ReflectiveOperationException badApi) {
                getLogger().severe("[CHRDK_VK] Несовместимая версия CHRDK_Core: обновите комплект до 1.4.277+.");
                this.coreApi = null;
            }
        }

        this.store = new VkStore(getDataFolder().toPath(), getLogger());
        this.store.load();
        this.service = new VkService(this, store);
        this.service.start();
        getServer().getPluginManager().registerEvents(this.service, this);

        VkCommandHandler handler = new VkCommandHandler(this, service);
        VkMenuGui menuGui = new VkMenuGui(this, service, handler);
        handler.attachGui(menuGui);
        getServer().getPluginManager().registerEvents(menuGui, this);
        for (String cmd : List.of("vk", "vkadmin")) {
            PluginCommand pluginCommand = getCommand(cmd);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(handler);
                pluginCommand.setTabCompleter(handler);
            }
        }

        // Единый социальный плагин: обязательная привязка к ВК/MAX, webhook MAX,
        // мост чата, уведомления и автопостинг новостей живут в этом же JAR.
        SocialConfig socialConfig = SocialConfig.parse(getConfig());
        this.socialService = new SocialService(this, socialConfig);
        this.maxService = new MaxService(this, socialService, socialConfig);
        socialService.attachMax(maxService);
        socialService.start();
        maxService.start();
        registerPlaceholders();

        // Единый хаб интерактива: callback-кнопки, диалоги, опросы и тикеты
        // для VK и MAX по одной схеме. Все флаги interactive.* выключены по умолчанию.
        this.interactionHub = new SocialInteractionHub(
                SocialInteractionConfig.parse(getConfig()),
                interactionExecutor(),
                row -> {
                    SocialService social = socialService;
                    if (social != null) {
                        social.audit().write(row.event(), row.player(), row.provider(), row.detail());
                    }
                });
        service.attachInteraction(interactionHub);
        maxService.attachInteractionHub(interactionHub);

        // Смерть, старт/стоп сервера и приветствие новичков — один слушатель,
        // все флаги выключены по умолчанию. Старт анонсируем с задержкой: к
        // этому моменту клиенты VK/MAX уже созданы и беседа доступна.
        this.eventAnnouncer = new SocialEventAnnouncer(this, SocialEventConfig.parse(getConfig()));
        getServer().getPluginManager().registerEvents(eventAnnouncer, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            SocialEventAnnouncer announcer = eventAnnouncer;
            if (announcer != null) announcer.onServerStarted();
        }, 20L * 5);

        // Периодическая запись грязного состояния раз в 30 секунд.
        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, () -> {
                    store.saveIfDirty();
                    if (socialService != null) socialService.store().saveIfDirty();
                }, 20L * 30, 20L * 30);

        // Суточный дайджест на стену сообщества: сбор событий, постер,
        // загрузка фото и wall.post выполняются этим же плагином на сервере.
        this.wallDigest = new WallDigestService(this, WallDigestConfig.parse(getConfig()),
                new WallDigestStore(getDataFolder().toPath(), getLogger()), store);
        this.wallDigest.start();

        // Четыре персональные карточки игроков в 09/12/15/18 + дайджест в 21:00
        // = ровно пять записей на стене сообщества за сутки.
        this.dailyCards = new DailyCardsService(this, this.wallDigest,
                DailyCardsConfig.parse(getConfig()),
                new DailyCardsStore(getDataFolder().toPath(), getLogger()));
        this.dailyCards.start();

        getLogger().info("[CHRDK_VK] Включён (привязок: " + store.linkCount()
                + ", API настроен: " + config.apiConfigured()
                + ", социальные привязки: " + socialService.store().linkCount() + ").");
    }

    /**
     * Счётчики TAB регистрируются только при наличии PlaceholderAPI: без PAPI
     * плагин продолжает работать, а в TAB останутся пустые значения.
     */
    private void registerPlaceholders() {
        if (socialPlaceholders != null) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().severe("[CHRDK_SOCIAL] PlaceholderAPI не найден: счётчики привязки и прохода "
                    + "(%chrdksocial_status%, %chrdksocial_link%, %chrdksocial_access%) в TAB НЕ работают. "
                    + "Установите PlaceholderAPI и перезапустите сервер.");
            // На случай нестандартного порядка загрузки — одна повторная попытка.
            if (!placeholdersRetryScheduled) {
                placeholdersRetryScheduled = true;
                Bukkit.getScheduler().runTaskLater(this, this::registerPlaceholders, 20L * 5);
            }
            return;
        }
        try {
            SocialPlaceholders expansion = new SocialPlaceholders(this, socialService);
            if (expansion.register()) {
                this.socialPlaceholders = expansion;
                getLogger().info("[CHRDK_SOCIAL] Счётчики TAB зарегистрированы (chrdksocial).");
            } else {
                getLogger().warning("[CHRDK_SOCIAL] Идентификатор chrdksocial уже занят — счётчики TAB не зарегистрированы.");
            }
        } catch (Throwable failure) {
            getLogger().log(Level.WARNING, "[CHRDK_SOCIAL] Не удалось зарегистрировать счётчики TAB", failure);
        }
    }

    @Override
    public void onDisable() {
        this.socialPlaceholders = null;
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (dailyCards != null) {
            dailyCards.shutdown();
            dailyCards = null;
        }
        if (wallDigest != null) {
            wallDigest.shutdown();
            wallDigest = null;
        }
        // Анонс остановки — ДО shutdown(): иначе исходящие executors уже закрыты.
        if (eventAnnouncer != null) {
            eventAnnouncer.onServerStopping();
            eventAnnouncer.shutdown();
            eventAnnouncer = null;
        }
        if (maxService != null) {
            maxService.shutdown();
            maxService = null;
        }
        if (socialService != null) {
            socialService.shutdown();
            socialService = null;
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
        if (store != null) {
            store.saveNow();
        }
        this.coreApi = null;
    }

    /** Атомарная перезагрузка {@code config.yml} и {@code messages.yml} без полуобновлённого состояния. */
    public boolean reloadBundle() {
        Path folder = getDataFolder().toPath();
        try {
            migrateBundledMessages(folder.resolve("messages.yml"));
            ConfigSnapshot<VkConfig> snapshot = ConfigSnapshot.load(
                    folder.resolve("config.yml"),
                    getResource("config.yml"),
                    (yaml, log) -> VkConfig.parse(yaml),
                    (yaml, log) -> Messages.fromChecked(
                            getResource("messages.yml"), folder.resolve("messages.yml"), log)
            );
            this.config = snapshot.value();
            this.messages = snapshot.messages();
            if (this.service != null) {
                this.service.applyRuntimeConfig();
            }
            YamlConfiguration fresh = new YamlConfiguration();
            try (BufferedReader configReader = Files.newBufferedReader(
                    folder.resolve("config.yml"), StandardCharsets.UTF_8)) {
                fresh.load(configReader);
            }
            SocialConfig socialConfig = SocialConfig.parse(fresh);
            if (this.socialService != null) this.socialService.applyConfig(socialConfig);
            if (this.maxService != null) this.maxService.applyConfig(socialConfig);
            if (this.eventAnnouncer != null) {
                this.eventAnnouncer.applyConfig(SocialEventConfig.parse(fresh));
            }
            if (this.interactionHub != null) {
                this.interactionHub.applyConfig(SocialInteractionConfig.parse(fresh));
            }
            if (this.wallDigest != null) {
                this.wallDigest.applyConfig(WallDigestConfig.parse(fresh));
            }
            if (this.dailyCards != null) {
                this.dailyCards.applyConfig(DailyCardsConfig.parse(fresh));
            }
            return true;
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "[CHRDK_VK] Ошибка перезагрузки конфигурации", exception);
            return false;
        }
    }

    private void migrateBundledMessages(Path userFile) {
        for (String legacy : List.of("vk-messages-legacy-1.4.275.yml", LEGACY_MESSAGES_1_4_268)) {
            migrateLegacyMessagesFile(userFile, () -> getResource("messages.yml"),
                    () -> getResource(legacy), getLogger());
        }
    }

    /**
     * Обновляет на диске стоковые строки {@code messages.yml} из известного стокового снимка (в1.4.268 подстановки
     * были записаны в угловых скобках {@code <url>}/{@code <code>} вместо {@code %url%}/{@code %code%}),
     * сохраняя любые пользовательские правки администратора.
     */
    static boolean migrateLegacyMessagesFile(Path userFile,
                                             Supplier<InputStream> currentSupplier,
                                             Supplier<InputStream> legacySupplier,
                                             Logger logger) {
        if (userFile == null || !Files.isRegularFile(userFile)) {
            return false;
        }
        try {
            YamlConfiguration live = new YamlConfiguration();
            try (BufferedReader reader = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) {
                live.load(reader);
            }
            YamlConfiguration current = loadYamlResource(currentSupplier.get());
            YamlConfiguration legacy = loadYamlResource(legacySupplier.get());
            if (current == null || legacy == null) {
                return false;
            }

            Set<String> liveLeafKeys = leafKeys(live);
            Set<String> legacyLeafKeys = leafKeys(legacy);
            boolean pureLegacyStock = !liveLeafKeys.isEmpty()
                    && legacyLeafKeys.containsAll(liveLeafKeys)
                    && liveLeafKeys.stream().allMatch(k -> Objects.equals(live.get(k), legacy.get(k)));

            if (pureLegacyStock) {
                try (InputStream rawCurrent = currentSupplier.get()) {
                    if (rawCurrent != null) {
                        String freshText = new String(rawCurrent.readAllBytes(), StandardCharsets.UTF_8);
                        if (SafeJsonWriter.write(userFile, freshText, logger, "[CHRDK_VK]")) {
                            if (logger != null) {
                                logger.info("[CHRDK_VK] Обновлён стоковый messages.yml до текущей версии.");
                            }
                            return true;
                        }
                    }
                }
            }

            int migratedKeys = 0;
            for (String key : legacyLeafKeys) {
                if (live.contains(key)
                        && current.contains(key)
                        && !Objects.equals(legacy.get(key), current.get(key))
                        && Objects.equals(live.get(key), legacy.get(key))) {
                    live.set(key, current.get(key));
                    migratedKeys++;
                }
            }
            if (migratedKeys > 0) {
                for (String key : leafKeys(current)) {
                    if (!live.contains(key)) {
                        live.set(key, current.get(key));
                    }
                }
                if (SafeJsonWriter.write(userFile, live.saveToString(), logger, "[CHRDK_VK]")) {
                    if (logger != null) {
                        logger.info("[CHRDK_VK] Обновлено " + migratedKeys
                                + " устаревших шаблонов в messages.yml до текущей версии.");
                    }
                    return true;
                }
            }
        } catch (Exception exception) {
            if (logger != null) {
                logger.log(Level.WARNING, "[CHRDK_VK] Не удалось проверить миграцию messages.yml", exception);
            }
        }
        return false;
    }

    private static YamlConfiguration loadYamlResource(InputStream stream) throws Exception {
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream;
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        }
    }

    private static Set<String> leafKeys(YamlConfiguration yaml) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Опасные действия выполняет сервис ВК; MAX получает только тикеты и
     * подсказки — у него нет сессий входа, которые можно сбросить.
     */
    private SocialInteractionHub.ActionExecutor interactionExecutor() {
        return new SocialInteractionHub.ActionExecutor() {
            @Override
            public String run(String provider, String kind, String arg, long userId, long now) {
                if ("ticket".equals(kind)) return submitTicket(provider, arg, userId, now);
                if (!"vk".equals(provider) || service == null) {
                    return "⚠ Это действие доступно только в ВКонтакте.";
                }
                return switch (kind) {
                    case "logout" -> service.handleRemoteLogout(userId);
                    case "freeze" -> service.handleRemoteFreeze(userId, true);
                    case "unfreeze" -> service.handleRemoteFreeze(userId, false);
                    case "unlink" -> service.handleUnlinkFromVk(userId);
                    case "link_wizard" -> "Отправьте одноразовый код из игрового чата.";
                    default -> "⚠ Неизвестное действие.";
                };
            }
        };
    }

    private String submitTicket(String provider, String ticketId, long userId, long now) {
        SocialInteractionConfig cfg = interactionHub == null
                ? SocialInteractionConfig.DEFAULT : interactionHub.config();
        // В заявку уходят только служебный ID и номер тикета: без IP, UUID и координат.
        String text = "🆘 Запрос " + ticketId + " из " + provider + " · обращение #" + userId;
        boolean sent = service != null && service.sendAdminAlert(text, cfg.adminPeerId());
        SocialService social = socialService;
        if (social != null) {
            social.audit().write("social.ticket", "", provider, ticketId + ", sent=" + sent);
        }
        if (cfg.inGameDigest()) {
            Bukkit.broadcastMessage("§b[CHERDAK] §f" + text);
        }
        return sent ? "✅ Заявка отправлена администрации." : "⚠ Канал администрации не настроен.";
    }

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public VkConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    /** Регистрация команд социального блока из того же класса: getCommand() может быть protected. */
    public void registerSocialCommands(org.bukkit.command.CommandExecutor executor,
                                       org.bukkit.command.TabCompleter completer) {
        for (String name : List.of("link", "social", "socialadmin", "max")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(completer);
            }
        }
    }

    public VkService vkService() {
        return service;
    }

    public SocialService socialService() {
        return socialService;
    }

    public MaxService maxService() {
        return maxService;
    }
}
