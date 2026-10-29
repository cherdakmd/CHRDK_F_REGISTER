package ru.cherdakmd.chrdkregister;

import java.util.Locale;
import java.util.Set;

/** Только совместимость сохранённых настроек после удаления аддонов в 1.4.239. */
final class RetiredAddons {
    private static final Set<String> SOURCES = Set.of("bosses", "treasures");
    private static final Set<String> COMMANDS = Set.of("boss", "bosses", "bossy", "bosss", "bossadmin",
            "bossesadmin", "treasures", "treasuresadmin");
    private static final java.util.regex.Pattern ANNOUNCEMENT = java.util.regex.Pattern.compile(
            "(?i)(?:^|[^a-z0-9_])/+(?:[a-z0-9_]+:)?(?:" + String.join("|", COMMANDS) + ")(?![a-z0-9_])");
    private RetiredAddons() { }

    static boolean source(String value) { return SOURCES.contains(normalize(value)); }
    static boolean plugin(String value) {
        String id = normalize(value);
        return id.startsWith("chrdk_") && source(id.substring(6));
    }
    static boolean command(String value) {
        String id = normalize(value);
        if (id.startsWith("/")) id = id.substring(1);
        id = id.split("\\s+", 2)[0];
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(colon + 1);
        return COMMANDS.contains(id);
    }
    static boolean announcement(String text) {
        // Не ищем «клад»/«босс» по подстроке: рыбалка и ванильные боссы остаются.
        return ANNOUNCEMENT.matcher(text == null ? "" : text).find();
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
