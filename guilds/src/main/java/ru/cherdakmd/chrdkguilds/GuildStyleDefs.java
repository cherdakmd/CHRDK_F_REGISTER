package ru.cherdakmd.chrdkguilds;

import java.util.*;
import ru.cherdakmd.chrdkregister.Messages;

/** Только серверные пресеты: пользователь не может внедрить MiniMessage/команду. */
final class GuildStyleDefs {
    static final Map<String, String> PRODUCTS = Map.of("name", "clan-name-style", "emblem", "clan-emblem", "frame", "clan-card-frame", "animation", "clan-style-pack");
    static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("gold", "<gold>%name%</gold>"), Map.entry("aqua", "<aqua>%name%</aqua>"),
            Map.entry("green", "<green>%name%</green>"), Map.entry("violet", "<light_purple>%name%</light_purple>"),
            Map.entry("white", "<white>%name%</white>"), Map.entry("blue", "<blue>%name%</blue>"),
            Map.entry("aurora", "<gradient:#67e8f9:#c084fc>%name%</gradient>"),
            Map.entry("sunset", "<gradient:#ffd166:#ef476f>%name%</gradient>"),
            Map.entry("forest", "<gradient:#86efac:#14b8a6>%name%</gradient>"),
            Map.entry("frost", "<gradient:#e0f2fe:#38bdf8>%name%</gradient>"),
            Map.entry("ember", "<gradient:#fbbf24:#f97316>%name%</gradient>"),
            Map.entry("pearl", "<gradient:#f5d0fe:#e0e7ff>%name%</gradient>"));
    static final Map<String, String> EMBLEMS = Map.of("star", "✦", "diamond", "◆", "spark", "✧", "crown", "⚜",
            "sun", "☀", "snow", "❄", "moon", "☾", "comet", "★");
    static final Map<String, String> FRAMES = Map.of("gold", "YELLOW_STAINED_GLASS_PANE", "amethyst", "PURPLE_STAINED_GLASS_PANE",
            "ocean", "CYAN_STAINED_GLASS_PANE", "forest", "GREEN_STAINED_GLASS_PANE",
            "ember", "ORANGE_STAINED_GLASS_PANE", "frost", "LIGHT_BLUE_STAINED_GLASS_PANE");

    static final List<String> ANIMATIONS = List.of("aurora", "shine", "pulse");

    static List<String> options(String kind) {
        return switch (kind) {
            case "name" -> COLORS.keySet().stream().sorted().toList();
            case "emblem" -> EMBLEMS.keySet().stream().sorted().toList();
            case "frame" -> FRAMES.keySet().stream().sorted().toList();
            case "animation" -> ANIMATIONS;
            default -> List.of();
        };
    }
    static boolean valid(String kind, String preset) { return options(kind).contains(preset); }
    static String name(String text, String color, String emblem) {
        String safe = Messages.escape(text);
        String named = COLORS.getOrDefault(color == null ? "" : color, "%name%").replace("%name%", safe);
        String icon = EMBLEMS.getOrDefault(emblem == null ? "" : emblem, "");
        return icon.isEmpty() ? named : "<gold>" + icon + "</gold> " + named;
    }
}
