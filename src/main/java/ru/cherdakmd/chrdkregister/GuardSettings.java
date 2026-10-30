package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;
import java.util.Set;
import java.util.stream.Collectors;

/** Настройки встроенного защитного модуля. OP и донат не обходят ограничения. */
record GuardSettings(boolean enabled, int ipBurst, int ipRefillSeconds, int globalBurst,
                     int globalPerSecond, int maxAddresses, int unauthGlobal, int unauthPerIp,
                     int loginSeconds, int commandBurst, int commandsPerSecond,
                     int unauthCommandBurst, int unauthCommandsPerSecond, int maxCommandLength,
                     Set<String> consoleOnly) {
    static final Set<String> DEFAULT_CONSOLE_ONLY = Set.of(
            "op", "deop", "stop", "restart", "reload", "rl", "debug", "perf", "jfr", "paper", "purpur", "spigot", "fill", "fillbiome", "clone", "setblock",
            "execute", "function", "schedule", "return", "gamerule", "title", "particle", "summon",
            "forceload", "tick", "place", "data", "damage", "kill", "tp", "teleport", "spreadplayers",
            "give", "item", "clear", "gamemode", "defaultgamemode", "difficulty", "worldborder",
            "attribute", "effect", "enchant", "experience", "xp", "time", "weather",
            "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist", "save-all", "save-off", "save-on",
            "sudo", "runas", "dispatch", "console");

    GuardSettings {
        consoleOnly = Set.copyOf(consoleOnly);
        if (ipBurst < 1 || ipRefillSeconds < 1 || globalBurst < 1 || globalPerSecond < 1
                || maxAddresses < 1 || unauthGlobal < 1 || unauthPerIp < 1 || loginSeconds < 1
                || commandBurst < 1 || commandsPerSecond < 1 || unauthCommandBurst < 1
                || unauthCommandsPerSecond < 1 || maxCommandLength < 1)
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
                limit(c,"max-command-length",2048,256,32767), blocked);
    }

    private static int limit(FileConfiguration c, String key, int value, int min, int max) {
        return Math.clamp(c.getInt("security."+key,value),min,max);
    }
}
