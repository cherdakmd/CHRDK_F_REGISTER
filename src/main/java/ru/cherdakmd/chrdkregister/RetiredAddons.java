package ru.cherdakmd.chrdkregister;

import java.util.Locale;
import java.util.Set;

/** Только совместимость сохранённых настроек после удаления аддонов в 1.4.239–1.4.271. */
final class RetiredAddons {
    private static final Set<String> SOURCES = Set.of("bosses", "treasures", "streams", "events", "queue", "lottery", "party", "referrals");
    private static final Set<String> COMMANDS = Set.of("boss", "bosses", "bossy", "bosss", "bossadmin",
            "bossesadmin", "treasures", "treasuresadmin", "stream", "streams", "strimer", "events", "event", "sobytiya", "eventsadmin", "queue", "qjoin", "ochered",
            "lottery", "lot", "loto", "lotteryadmin", "lotadmin",
            "party", "pt", "group", "gruppa", "pc", "pchat", "partychat", "partyadmin",
            "ref", "referral", "invite", "refadmin", "referraladmin");
    private static final java.util.regex.Pattern ANNOUNCEMENT = java.util.regex.Pattern.compile(
            "(?i)(?:^|[^a-z0-9_])/+(?:[a-z0-9_.-]+:)?(?:" + String.join("|", COMMANDS) + ")(?![a-z0-9_/-])");
    // Только реклама снятой программы, а не общий совет о безопасном входе при записи экрана.
    private static final java.util.regex.Pattern REMOVED_PROGRAM = java.util.regex.Pattern.compile(
            "(?iu)twitch|vk\\s*play\\s*live|стримерск[\\p{L}]*\\s+программ[\\p{L}]*|мировые\\s+события");
    // Только объявления конкретных снятых систем, не случайный дроп или приглашение в клан.
    private static final java.util.regex.Pattern REMOVED_SOCIAL = java.util.regex.Pattern.compile(
            "(?iu)реферальн[\\p{L}]*|еженедельн[\\p{L}]*\\s+лотере[\\p{L}]*|лотере[\\p{L}]*\\s+недел[\\p{L}]*|пати[- ]чат|чат\\s+отряда");
    private RetiredAddons() { }

    static boolean source(String value) { return SOURCES.contains(normalize(value)); }
    static boolean plugin(String value) {
        String id = normalize(value);
        return id.startsWith("chrdk_") && source(id.substring(6));
    }
    static boolean command(String value) {
        String id = normalize(value);
        while (id.startsWith("/")) id = id.substring(1);
        id = id.split("\\s+", 2)[0];
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(colon + 1);
        return COMMANDS.contains(id);
    }
    /** Старые права не выдаются повторно при обнаружении оставленного аддона. */
    static boolean permission(String value) {
        String id = normalize(value);
        return id.startsWith("chrdkref.") || SOURCES.stream().anyMatch(source -> id.startsWith("chrdk" + source + "."));
    }
    static boolean announcement(String text) {
        // Не ищем «клад»/«босс» по подстроке: рыбалка и ванильные боссы остаются.
        String value = text == null ? "" : text;
        return ANNOUNCEMENT.matcher(value).find() || REMOVED_PROGRAM.matcher(value).find()
                || REMOVED_SOCIAL.matcher(value).find();
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
