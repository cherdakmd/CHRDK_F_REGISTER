package ru.cherdakmd.chrdkteleports;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Снимок конфигурации CHRDK_Teleports. Парсится из config.yml; недопустимые значения
 * приводятся к безопасным границам (лог-заметка). Чисто данные — тестируется без сервера.
 */
public record TpConfig(
        Set<String> allowedWorlds,      // пусто = все миры (имена в lower-регистре)
        int delaySeconds,
        boolean cancelOnMove,
        boolean cancelOnDamage,
        int vipDiscountPercent,          // 0 = без скидки
        int costRtp,
        int costTpa,
        int costHome,
        int costSethome,
        int rtpRadius,
        int rtpMinRadius,
        boolean rtpUseSpawnCenter,
        int rtpCenterX,
        int rtpCenterZ,
        String rtpWorld,                 // пусто = текущий мир игрока
        int rtpMaxAttempts,
        int rtpCooldownSeconds,
        int tpaTimeoutSeconds,
        int tpaCooldownSeconds,
        int homesMaxNormal,
        int homesMaxVip,
        String homesExtraPermission,
        int homesExtraLimit,
        int homesHardCap,
        boolean bedSpawnEnabled,
        List<Integer> rankHomes,        // лимит домов по ступени ранга: [0..5]
        List<Integer> rankDiscounts) {  // скидка телепорта по ступени: [0..5]

    public boolean worldAllowed(String worldName) {
        return allowedWorlds.isEmpty() || allowedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    /** Лимит точек дома для игрока. */
    public int homeLimit(boolean vip) {
        return homeLimit(vip ? 1 : 0, false);
    }

    /**
     * Скидка на телепорты для ступени ранга (1.4.167).
     *
     * <p>Если таблица {@code costs.rank-discount-percent} не задана, работает
     * старое правило: любой ранг = скидка {@code vip-discount-percent}. Так старые
     * конфиги не меняют поведение, а новая таблица даёт разным рангам разные скидки.</p>
     */
    public int discountFor(int rank) {
        if (rank <= 0) {
            return 0;
        }
        if (rankDiscounts.isEmpty()) {
            return vipDiscountPercent;
        }
        int index = Math.min(rank, rankDiscounts.size() - 1);
        return Math.max(0, Math.min(100, rankDiscounts.get(index)));
    }

    /** Лимит домов по ступени ранга: таблица модуля или старое правило проходки. */
    public int homeLimit(int rank, boolean extra) {
        int limit;
        if (rank <= 0) {
            limit = homesMaxNormal;
        } else if (rankHomes.isEmpty()) {
            limit = Math.max(homesMaxNormal, homesMaxVip);
        } else {
            limit = rankHomes.get(Math.min(rank, rankHomes.size() - 1));
        }
        if (extra && !homesExtraPermission.isBlank()) {
            limit = Math.max(limit, homesExtraLimit);
        }
        return Math.min(limit, homesHardCap);
    }

    /**
     * Лимит точек дома с учётом купленного товара «дополнительные дома».
     *
     * <p>Право {@code homes.extra-permission} выдаётся ядром на срок (группа LuckPerms),
     * поэтому лимит живёт ровно столько, сколько оплачено. Независимо от способа получения
     * лимит не превышает {@code homes.hard-cap}: выше потолка не продаётся ничего.</p>
     *
     * @param vip   действует ли проходка
     * @param extra есть ли право на дополнительные дома
     */
    public int homeLimit(boolean vip, boolean extra) {
        int limit = vip ? homesMaxVip : homesMaxNormal;
        if (extra && !homesExtraPermission.isBlank()) {
            limit = Math.max(limit, homesExtraLimit);
        }
        return Math.min(limit, homesHardCap);
    }

    /** Есть ли у игрока дополнительный лимит по праву (пустое право = товар выключен). */
    public boolean grantsExtraHomes(boolean hasPermission) {
        return hasPermission && !homesExtraPermission.isBlank();
    }

    public static TpConfig parse(FileConfiguration config, Logger logger) {
        Set<String> worlds = new LinkedHashSet<>();
        List<String> rawWorlds = config.getStringList("allowed-worlds");
        for (String world : rawWorlds) {
            if (world != null && !world.isBlank()) {
                worlds.add(world.toLowerCase(Locale.ROOT));
            }
        }

        ConfigurationSection tp = config.getConfigurationSection("teleport");
        int delay = clamp(section(tp, "delay-seconds", 3), 0, 60, logger, "teleport.delay-seconds");
        boolean cancelMove = section(tp, "cancel-on-move", true);
        boolean cancelDamage = section(tp, "cancel-on-damage", true);

        ConfigurationSection costs = config.getConfigurationSection("costs");
        int discount = clamp(section(costs, "vip-discount-percent", 50), 0, 100, logger, "costs.vip-discount-percent");
        int costRtp = clamp(section(costs, "rtp-diamonds", 5), 0, 640, logger, "costs.rtp-diamonds");
        int costTpa = clamp(section(costs, "tpa-diamonds", 2), 0, 640, logger, "costs.tpa-diamonds");
        int costHome = clamp(section(costs, "home-diamonds", 1), 0, 640, logger, "costs.home-diamonds");
        int costSethome = clamp(section(costs, "sethome-diamonds", 0), 0, 640, logger, "costs.sethome-diamonds");

        ConfigurationSection rtp = config.getConfigurationSection("rtp");
        int radius = clamp(section(rtp, "radius-blocks", 10000), 1, 30_000_000, logger, "rtp.radius-blocks");
        int minRadius = clamp(section(rtp, "min-radius-blocks", 250), 0, radius, logger, "rtp.min-radius-blocks");
        boolean useSpawn = section(rtp, "use-world-spawn-center", true);
        int centerX = section(rtp, "center-x", 0);
        int centerZ = section(rtp, "center-z", 0);
        String rtpWorld = section(rtp, "world", "world").trim();
        int attempts = clamp(section(rtp, "max-attempts", 24), 1, 200, logger, "rtp.max-attempts");
        int rtpCooldown = clamp(section(rtp, "cooldown-seconds", 300), 0, 86_400, logger, "rtp.cooldown-seconds");

        ConfigurationSection tpa = config.getConfigurationSection("tpa");
        int timeout = clamp(section(tpa, "request-timeout-seconds", 60), 5, 600, logger, "tpa.request-timeout-seconds");
        int tpaCooldown = clamp(section(tpa, "cooldown-seconds", 30), 0, 86_400, logger, "tpa.cooldown-seconds");

        ConfigurationSection homes = config.getConfigurationSection("homes");
        int maxNormal = clamp(section(homes, "max-normal", 3), 0, 100, logger, "homes.max-normal");
        int maxVip = clamp(section(homes, "max-vip", 8), maxNormal, 100, logger, "homes.max-vip");
        String extraPermission = section(homes, "extra-permission", "chrdktele.homes.plus").trim();
        int extraLimit = clamp(section(homes, "extra-limit", 8), maxVip, 100, logger, "homes.extra-limit");
        int hardCap = clamp(section(homes, "hard-cap", 12), maxNormal, 100,
                logger, "homes.hard-cap");
        boolean bedSpawn = section(homes, "bed-spawn", true);
        List<Integer> rankHomes = intList(homes, "rank-limits", logger);
        List<Integer> rankDiscounts = intList(costs, "rank-discount-percent", logger);

        return new TpConfig(
                Set.copyOf(worlds), delay, cancelMove, cancelDamage, discount,
                costRtp, costTpa, costHome, costSethome,
                radius, minRadius, useSpawn, centerX, centerZ, rtpWorld, attempts, rtpCooldown,
                timeout, tpaCooldown, maxNormal, maxVip, extraPermission, extraLimit, hardCap,
                bedSpawn, rankHomes, rankDiscounts);
    }

    /* ------------------------------- утилиты ------------------------------- */

    /** Список чисел из конфига; пустой — таблица не задана (работает старое правило). */
    private static List<Integer> intList(ConfigurationSection section, String key, Logger logger) {
        if (section == null || !section.isList(key)) {
            return List.of();
        }
        List<Integer> result = new java.util.ArrayList<>();
        for (Object raw : section.getList(key, List.of())) {
            if (raw instanceof Number number) {
                result.add(Math.max(0, Math.min(100, number.intValue())));
            } else {
                logger.warning(key + ": значение «" + raw + "» не число — пропущено");
            }
        }
        return List.copyOf(result);
    }

    private static int section(ConfigurationSection section, String key, int fallback) {
        return section == null ? fallback : section.getInt(key, fallback);
    }

    private static boolean section(ConfigurationSection section, String key, boolean fallback) {
        return section == null ? fallback : section.getBoolean(key, fallback);
    }

    private static String section(ConfigurationSection section, String key, String fallback) {
        return section == null ? fallback : section.getString(key, fallback);
    }

    private static int clamp(int value, int min, int max, Logger logger, String key) {
        if (value < min || value > max) {
            logger.warning("config: " + key + " вне диапазона [" + min + ".." + max + "], применено соседнее");
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }
}
