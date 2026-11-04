package ru.cherdakmd.chrdkenchants;

import java.util.List;
import java.util.Locale;

/** Только выбор темы справки; никаких предметов, PDC, платежей или произвольных message paths. */
final class EnchantHelp {
    static final List<String> TOPICS = List.of("books", "anvil", "workshop", "effects");
    private EnchantHelp() { }
    static String topic(String input) {
        if (input == null) return null;
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "books" -> "help-books";
            case "anvil" -> "help-anvil";
            case "workshop" -> "help-workshop";
            case "effects" -> "help-effects";
            default -> null;
        };
    }
}
