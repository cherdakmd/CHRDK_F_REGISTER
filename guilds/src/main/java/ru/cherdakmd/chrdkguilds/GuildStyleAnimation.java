package ru.cherdakmd.chrdkguilds;

import java.util.Locale;
import ru.cherdakmd.chrdkregister.Messages;

/** Чистые кадры по реальному времени: без задач на каждый клан, смены текста и случайного мигания. */
final class GuildStyleAnimation {
    private GuildStyleAnimation() { }

    static String render(String text, String color, String emblem, String animation, long now) {
        if (!GuildStyleDefs.ANIMATIONS.contains(animation == null ? "" : animation))
            return GuildStyleDefs.name(text, color, emblem);
        double phase = Math.floorMod(now, 12_000L) / 12_000.0;
        String name = switch (animation) {
            case "aurora" -> String.format(Locale.ROOT, "<gradient:#67e8f9:#c084fc:%.4f>%s</gradient>",
                    Math.sin(phase * Math.PI * 2), Messages.escape(text));
            case "shine" -> String.format(Locale.ROOT, "<gradient:#eab308:#fff7d6:#eab308:%.4f>%s</gradient>",
                    Math.sin(phase * Math.PI * 2), Messages.escape(text));
            case "pulse" -> String.format(Locale.ROOT, "<#%02x%02x%02x>%s</#%02x%02x%02x>",
                    channel(phase, 145, 205), channel(phase, 120, 180), channel(phase, 205, 250), Messages.escape(text),
                    channel(phase, 145, 205), channel(phase, 120, 180), channel(phase, 205, 250));
            default -> throw new IllegalStateException("Неизвестная серверная анимация");
        };
        String icon = GuildStyleDefs.EMBLEMS.getOrDefault(emblem == null ? "" : emblem, "");
        return (icon.isEmpty() ? "" : "<gold>" + icon + "</gold> ") + name;
    }
    private static int channel(double phase, int low, int high) {
        return low + (int) Math.round((high - low) * (1 + Math.sin(phase * Math.PI * 2)) / 2);
    }
}
