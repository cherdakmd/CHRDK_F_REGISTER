package ru.cherdakmd.chrdkartifacts;

import java.util.List;
import java.util.Locale;

/** Конечные темы read-only справки, без исполнения команд или произвольных путей сообщений. */
final class ArtifactGuide {
    static final List<String> TOPICS = List.of("use", "sources", "traits", "collection");
    private ArtifactGuide() { }
    static String topic(String input) {
        if (input == null) return null;
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "use" -> "guide-use";
            case "sources" -> "guide-sources";
            case "traits" -> "guide-traits";
            case "collection" -> "guide-collection";
            default -> null;
        };
    }
}
