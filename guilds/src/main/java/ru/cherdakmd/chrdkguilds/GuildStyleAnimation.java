package ru.cherdakmd.chrdkguilds;

import ru.cherdakmd.chrdkregister.Messages;

/** Чистые кадры по реальному времени: без задач на каждый клан, смены текста и случайного мигания. */
final class GuildStyleAnimation {
    private GuildStyleAnimation() { }

    static String render(String text, String color, String emblem, String animation, long now) {
        if (!GuildStyleDefs.ANIMATIONS.contains(animation == null ? "" : animation))
            return GuildStyleDefs.name(text, color, emblem);
        double phase = Math.floorMod(now, 12_000L) / 12_000.0;
        // Передаём готовые RGB каждого символа: после сериализации и вырезания якоря
        // в Core не остаётся виртуального градиента, который можно пересчитать/потерять.
        // Даже односимвольный тег получает полноценную смену кадров.
        String name = switch (animation) {
            case "aurora" -> wave(text, 0x67e8f9, 0xc084fc, phase);
            case "shine" -> wave(text, 0xeab308, 0xfff7d6, phase);
            case "pulse" -> colored(text, mix(0x9178cd, 0xcdb4fa, intensity(phase)));
            default -> throw new IllegalStateException("Неизвестная серверная анимация");
        };
        String icon = GuildStyleDefs.EMBLEMS.getOrDefault(emblem == null ? "" : emblem, "");
        return (icon.isEmpty() ? "" : "<gold>" + icon + "</gold> ") + name;
    }

    private static String wave(String text, int from, int to, double phase) {
        int[] points = text.codePoints().toArray();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < points.length; i++) {
            double offset = i / (2.0 * Math.max(1, points.length));
            result.append(colored(Character.toString(points[i]), mix(from, to, intensity(phase + offset))));
        }
        return result.toString();
    }
    private static double intensity(double phase) { return (1 + Math.sin(phase * Math.PI * 2)) / 2; }
    private static int mix(int from, int to, double amount) {
        int result = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int low = (from >> shift) & 255;
            int high = (to >> shift) & 255;
            result |= (int) Math.round(low + (high - low) * amount) << shift;
        }
        return result;
    }
    private static String colored(String text, int rgb) {
        String hex = "#" + Integer.toHexString(0x1000000 | rgb).substring(1);
        return "<" + hex + ">" + Messages.escape(text) + "</" + hex + ">";
    }
}
