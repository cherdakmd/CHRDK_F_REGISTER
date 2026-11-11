package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.Set;
import java.util.stream.Collectors;

/** Настройки встроенного защитного модуля. OP и донат не обходят ограничения. */
record GuardSettings(boolean enabled, int ipBurst, int ipRefillSeconds, int globalBurst,
                     int globalPerSecond, int maxAddresses, int unauthGlobal, int unauthPerIp,
                     int loginSeconds, int commandBurst, int commandsPerSecond,
                     int unauthCommandBurst, int unauthCommandsPerSecond, int maxCommandLength,
                     int blockedAttemptBurst, int blockedAttemptsPerSecond, int blockedAlertSeconds,
                     Set<String> consoleOnly, GuardTuning defense) {
    /** Ванильные/серверные корни: эскалация прав, мир, выдача, отключение, раскрытие сборки. */
    static final Set<String> DEFAULT_CONSOLE_ONLY = Set.of(
            "op", "deop", "stop", "restart", "reload", "rl", "debug", "perf", "jfr", "paper", "purpur", "spigot", "fill", "fillbiome", "clone", "setblock",
            "execute", "function", "schedule", "return", "gamerule", "title", "particle", "summon",
            "forceload", "tick", "place", "data", "damage", "kill", "tp", "teleport", "spreadplayers",
            "give", "item", "clear", "gamemode", "defaultgamemode", "difficulty", "worldborder",
            "attribute", "effect", "enchant", "experience", "xp", "time", "weather",
            "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist", "save-all", "save-off", "save-on",
            "sudo", "runas", "dispatch", "console",
            // Разведка сборки: версии ядра и список плагинов не нужны игроку.
            "pl", "plugins", "version", "ver", "about", "icanhasbukkit", "help", "?", "timings", "tps", "mspt",
            // Сторонние плагины из рабочего набора: права, скины, голос, камера, плейсхолдеры.
            "lp", "luckperms", "perm", "perms", "permissions", "papi", "placeholderapi",
            "skinsrestorer", "sr", "skins", "plasmovoice", "pv", "plvoice", "cameraman", "camera", "cam",
            // Админ-корни и алиасы CHRDK_Core.
            "guard", "rankadmin", "socialspy", "spy", "adminpanel", "apanel", "staffpanel", "honoradmin", "honorsadmin",
            // Админ-корни и алиасы аддонов CHRDK_* (все 22 модуля из ZIP).
            "achadmin", "artifactsadmin", "auctionadmin", "ahadmin", "bpadmin", "claimadmin",
            "classadmin", "classesadmin", "clsadmin", "goalsadmin", "dailyadmin", "dlyadmin",
            "shopadmin", "dshopadmin", "marketadmin", "disastersadmin", "enchantsadmin",
            "guildadmin", "guildsadmin", "clanadmin", "clansadmin", "ga", "ordersadmin",
            "petsadmin", "questsadmin", "ranksadmin",
            "skillsadmin", "tpadmin", "tiersadmin", "vkadmin");

    GuardSettings {
        consoleOnly = Set.copyOf(consoleOnly);
        if (ipBurst < 1 || ipRefillSeconds < 1 || globalBurst < 1 || globalPerSecond < 1
                || maxAddresses < 1 || unauthGlobal < 1 || unauthPerIp < 1 || loginSeconds < 1
                || commandBurst < 1 || commandsPerSecond < 1 || unauthCommandBurst < 1
                || unauthCommandsPerSecond < 1 || maxCommandLength < 1
                || blockedAttemptBurst < 1 || blockedAttemptsPerSecond < 1 || blockedAlertSeconds < 1)
            throw new IllegalArgumentException("Лимиты защиты должны быть положительными");
    }

    static GuardSettings parse(FileConfiguration c) {
        var configured = c.getList("security.console-only-commands");
        // Только явный [] снимает запрет. Ошибка типа/null/пустая строка не открывает OP-команды.
        boolean valid = configured != null && configured.stream().allMatch(value ->
                value instanceof String text && !GuardPolicy.label(text).isEmpty());
        Set<String> blocked = valid
                ? configured.stream().map(value -> GuardPolicy.label((String)value)).collect(Collectors.toSet())
                : DEFAULT_CONSOLE_ONLY;
        return new GuardSettings(c.getBoolean("security.enabled", true),
                limit(c,"ip-burst",6,1,1000), limit(c,"ip-refill-seconds",3,1,300),
                limit(c,"global-burst",60,1,10000), limit(c,"global-per-second",10,1,1000),
                limit(c,"max-addresses",20000,16,100000), limit(c,"unauthenticated-global",32,1,10000),
                limit(c,"unauthenticated-per-ip",4,1,1000), limit(c,"login-seconds",120,30,600),
                limit(c,"command-burst",40,1,1000), limit(c,"commands-per-second",10,1,100),
                limit(c,"unauthenticated-command-burst",10,1,100),
                limit(c,"unauthenticated-commands-per-second",2,1,20),
                limit(c,"max-command-length",2048,256,32767),
                // Аудит попыток: не более 3 сразу, затем 1/сек на игрока; сводный алерт ≤1 раз в 30с.
                limit(c,"blocked-attempt-burst",3,1,100),
                limit(c,"blocked-attempts-per-second",1,1,20),
                limit(c,"blocked-alert-seconds",30,5,600),
                blocked, GuardTuning.parse(c));
    }

    private static int limit(FileConfiguration c, String key, int value, int min, int max) {
        return Math.clamp(c.getInt("security."+key,value),min,max);
    }
}
