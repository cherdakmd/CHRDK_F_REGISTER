package ru.cherdakmd.chrdkvk;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.configuration.ConfigSnapshot;
import ru.cherdakmd.chrdkregister.storage.SafeJsonWriter;

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
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateBundledMessages(getDataFolder().toPath().resolve("messages.yml"));
        this.messages = Messages.load(this, getConfig().getString("general.locale", "ru"), getLogger());
        this.config = VkConfig.parse(getConfig());
        this.coreApi = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
        if (this.coreApi == null) {
            getLogger().warning("[CHRDK_VK] Сервис ChrdkRegisterApi не найден — проверьте порядок запуска CHRDK_Core.");
        } else {
            try {
                if (ChrdkRegisterApi.class.getField("API_VERSION").getInt(null) < 33) {
                    getLogger().severe("[CHRDK_VK] Требуется CHRDK_Core API 33+ (версия 1.4.268 или новее).");
                    this.coreApi = null;
                }
            } catch (ReflectiveOperationException badApi) {
                getLogger().severe("[CHRDK_VK] Несовместимая версия CHRDK_Core: обновите комплект до 1.4.268+.");
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

        // Периодическая запись грязного состояния раз в 30 секунд.
        this.saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, () -> store.saveIfDirty(), 20L * 30, 20L * 30);

        getLogger().info("[CHRDK_VK] Включён (привязок: " + store.linkCount()
                + ", API настроен: " + config.apiConfigured() + ").");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
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

    public ChrdkRegisterApi coreApi() {
        return coreApi;
    }

    public VkConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }
}
