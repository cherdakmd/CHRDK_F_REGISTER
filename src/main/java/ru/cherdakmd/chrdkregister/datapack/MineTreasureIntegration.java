package ru.cherdakmd.chrdkregister.datapack;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Серверная интеграция уже установленного в мир датапака Mine Treasure 1.6.5 RU.
 *
 * <p>Плагин <b>не</b> распаковывает, не переводит и не копирует ZIP: архив
 * кладётся владельцем сервера в {@code <мир>/datapacks/}. Патченый готовый
 * архив лежит в репозитории как release-ассет, но ядро его не поставляет.
 * Через секунду после {@code onEnable()} ядро один раз находит пак через Paper
 * {@code io.papermc.paper.datapack.DatapackManager} и, если он выключен,
 * вызывает {@code Datapack#setEnabled(true)}. Уже включённый пак не трогается;
 * отсутствующий пак не создаётся и не копируется.</p>
 *
 * <p>Обращение к Paper API идёт через рефлексию: в Purpur
 * {@code 26.3.build.2647-experimental} наличие конкретного метода не проверено
 * локальной компиляцией, а отсутствие API не должно ронять ядро. В таком случае
 * в журнал пишется команда ручного включения. {@code setEnabled(true)} в Paper
 * само перезагружает ресурсы сервера, поэтому отдельный {@code reloadData()}
 * или {@code /minecraft:reload} из плагина не вызывается.</p>
 *
 * <p>Логика настроек, выбора id и решения «включать / не включать» вынесена в
 * чистые методы и покрывается JUnit без работающего Bukkit. Paper-часть
 * проверяется тестовыми объектами с такими же публичными сигнатурами.</p>
 */
public final class MineTreasureIntegration {

    /** Имя ZIP, которое создаёт tools/datapacks/install_mine_treasure.py. */
    public static final String DEFAULT_FILE_NAME = "mine-treasure-1.6.5-ru.zip";

    private static final String CONFIG_PREFIX = "integrations.mine-treasure.";
    private static final long START_DELAY_TICKS = 20L;
    private static final String MANAGER_METHOD = "getDatapackManager";

    /** Настройки секции integrations.mine-treasure. */
    public record Settings(boolean enabled, boolean refreshOnStart, String fileName,
                           String explicitPackId) {
    }

    /** Итог одной попытки проверки датапака. */
    public enum Outcome {
        DISABLED,
        INVALID_SETTINGS,
        API_UNAVAILABLE,
        PACK_ABSENT,
        ALREADY_ENABLED,
        ENABLED,
        FAILED
    }

    private MineTreasureIntegration() {
    }

    /** Читает настройки; отсутствие секции включает мягкую интеграцию по умолчанию. */
    static Settings settings(FileConfiguration config) {
        if (config == null) {
            return new Settings(true, false, DEFAULT_FILE_NAME, "");
        }
        boolean enabled = config.getBoolean(CONFIG_PREFIX + "auto-enable", true);
        boolean refresh = config.getBoolean(CONFIG_PREFIX + "refresh-on-start", false);
        String fileName = config.getString(CONFIG_PREFIX + "file-name", DEFAULT_FILE_NAME);
        if (fileName == null || fileName.isBlank()) {
            fileName = DEFAULT_FILE_NAME;
        }
        String explicitPackId = config.getString(CONFIG_PREFIX + "pack-id", "");
        if (explicitPackId == null) {
            explicitPackId = "";
        }
        return new Settings(enabled, refresh, fileName.trim(), explicitPackId.trim());
    }

    /**
     * Id датапака в {@code PackRepository}. Для ZIP в {@code <мир>/datapacks}
     * NMS даёт id {@code file/<имя файла>}; явный {@code pack-id} позволяет
     * указать Paper-паки вида {@code PluginName/id}.
     *
     * @return id или {@code null}, если имя/id небезопасно
     */
    static String packId(String fileName, String explicitPackId) {
        String explicit = explicitPackId == null ? "" : explicitPackId.trim();
        if (!explicit.isEmpty()) {
            return safeId(explicit) ? explicit : null;
        }
        if (!safeFileName(fileName)) {
            return null;
        }
        return "file/" + fileName;
    }

    /** Решение по найденному паку до вызова изменения состояния. */
    static Outcome decide(boolean discovered, boolean enabled) {
        if (!discovered) {
            return Outcome.PACK_ABSENT;
        }
        return enabled ? Outcome.ALREADY_ENABLED : Outcome.ENABLED;
    }

    /** Точка входа из {@code RegisterPlugin#onEnable()}. */
    public static void start(JavaPlugin plugin) {
        if (plugin == null) {
            return;
        }
        Logger logger = plugin.getLogger();
        Settings settings = settings(plugin.getConfig());
        if (!settings.enabled()) {
            logger.log(Level.FINE, "Mine Treasure: автоматическое включение отключено в config.yml");
            return;
        }
        String packId = packId(settings.fileName(), settings.explicitPackId());
        if (packId == null) {
            logger.warning("Mine Treasure: небезопасное имя файла или pack-id в config.yml; "
                    + "датапак оставлен без изменений");
            return;
        }
        // setEnabled(true) перезагружает ресурсы: не делаем это в середине onEnable().
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Outcome outcome = connectAndApply(settings, packId, logger);
            switch (outcome) {
                case ALREADY_ENABLED -> logger.info("Mine Treasure: датапак " + packId + " уже включён");
                case ENABLED -> logger.info("Mine Treasure: для " + packId
                        + " вызван setEnabled(true); Purpur перезагружает ресурсы");
                case PACK_ABSENT -> logger.warning("Mine Treasure: " + packId
                        + " не найден. Положите ZIP в <мир>/datapacks/" + settings.fileName()
                        + " и перезапустите сервер; либо включите вручную /datapack enable \""
                        + packId + "\"");
                case API_UNAVAILABLE -> logger.warning("Mine Treasure: Paper DatapackManager недоступен. "
                        + "Включите вручную /datapack enable \"" + packId + "\" и /minecraft:reload");
                case FAILED -> logger.warning("Mine Treasure: не удалось проверить или включить "
                        + packId + ". Включите вручную /datapack enable \"" + packId + "\"");
                case DISABLED, INVALID_SETTINGS -> logger.log(Level.FINE,
                        "Mine Treasure: проверка пропущена");
            }
        }, START_DELAY_TICKS);
    }

    /** Ищет менеджер через рефлексию и передаёт управление в apply(). */
    private static Outcome connectAndApply(Settings settings, String packId, Logger logger) {
        Object manager;
        try {
            Object server = Bukkit.getServer();
            if (server == null) {
                return Outcome.API_UNAVAILABLE;
            }
            manager = invokeNoArgs(server, MANAGER_METHOD);
        } catch (Exception | LinkageError error) {
            logger.log(Level.FINE, "Mine Treasure: " + MANAGER_METHOD
                    + " недоступен (" + error.getClass().getSimpleName() + ")", error);
            return Outcome.API_UNAVAILABLE;
        }
        if (manager == null) {
            return Outcome.API_UNAVAILABLE;
        }
        return apply(manager, packId, settings.fileName(), settings.refreshOnStart(), logger);
    }

    /** Paper-часть без статического импорта io.papermc.paper.datapack.*. */
    static Outcome apply(Object manager, String packId, String fileName,
                         boolean refreshOnStart, Logger logger) {
        if (manager == null) {
            return Outcome.API_UNAVAILABLE;
        }
        try {
            Object pack = findPack(manager, packId, fileName);
            if (pack == null && refreshOnStart) {
                try {
                    invokeNoArgs(manager, "refreshPacks");
                    pack = findPack(manager, packId, fileName);
                } catch (Exception | LinkageError refreshError) {
                    logger.log(Level.FINE, "Mine Treasure: refreshPacks недоступен ("
                            + refreshError.getClass().getSimpleName() + ")", refreshError);
                }
            }
            if (pack == null) {
                return Outcome.PACK_ABSENT;
            }
            boolean enabled = Boolean.TRUE.equals(invokeNoArgs(pack, "isEnabled"));
            Outcome outcome = decide(true, enabled);
            if (outcome == Outcome.ENABLED) {
                invoke(pack, "setEnabled", boolean.class, Boolean.TRUE);
            }
            return outcome;
        } catch (Exception | LinkageError error) {
            logger.log(Level.WARNING, "Mine Treasure: DatapackManager отказал ("
                    + error.getClass().getSimpleName() + ")", error);
            return Outcome.FAILED;
        }
    }

    /** Точный id, затем короткое имя и список найденных паков. */
    private static Object findPack(Object manager, String packId, String fileName)
            throws ReflectiveOperationException {
        Object exact = invoke(manager, "getPack", String.class, packId);
        if (exact != null) {
            return exact;
        }
        if (safeFileName(fileName)) {
            Object byFileName = invoke(manager, "getPack", String.class, fileName);
            if (byFileName != null) {
                return byFileName;
            }
        }
        Object packs;
        try {
            packs = invokeNoArgs(manager, "getPacks");
        } catch (Exception | LinkageError ignored) {
            // getPack уже ответил null; отсутствие перечисления не ошибка включения.
            return null;
        }
        if (packs instanceof Iterable<?> iterable) {
            for (Object candidate : iterable) {
                if (candidate == null) {
                    continue;
                }
                try {
                    String name = String.valueOf(invokeNoArgs(candidate, "getName"));
                    if (name.equals(packId) || name.equals(fileName)
                            || name.equals("file/" + fileName) || name.endsWith("/" + fileName)) {
                        return candidate;
                    }
                } catch (Exception | LinkageError ignored) {
                    // Чужой объект без getName не должен ломать поиск.
                }
            }
        }
        return null;
    }

    private static Object invokeNoArgs(Object target, String methodName)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static Object invoke(Object target, String methodName, Class<?> parameterType,
                                 Object argument) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName, parameterType);
        return method.invoke(target, argument);
    }

    private static boolean safeFileName(String name) {
        if (!safeId(name) || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) {
            return false;
        }
        return true;
    }

    private static boolean safeId(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return false;
            }
        }
        return !value.contains("..");
    }
}
