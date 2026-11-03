package ru.cherdakmd.chrdkguilds;

import ru.cherdakmd.chrdkregister.Messages;

/** Чистые кадры по времени: одна проходка Unicode, без задач/состояния на каждый клан. */
final class GuildStyleAnimation {
    private static final int[] PRISM_PALETTE = {0xff4040, 0xffff40, 0x40ff60, 0x00ffff, 0x6688ff, 0xff40ff};

    private GuildStyleAnimation() { }

    static String render(String text, String color, String emblem, String animation, long now) {
        if (!GuildStyleDefs.ANIMATIONS.contains(animation == null ? "" : animation))
            return GuildStyleDefs.name(text, color, emblem);
        // Общая фаза повторяется через 12 секунд. Внутри — разные ритмы эффектов;
        // floorMod сохраняет корректный кадр даже для отрицательного времени.
        double phase = Math.floorMod(now, 12_000L) / 12_000.0;
        String name;
        if (animation.equals("pulse")) {
            // Синхронному дыханию достаточно одного компонента: не раздуваем TAB-пакет
            // одинаковыми RGB-тегами на каждую букву.
            name = colored(text, mix(0xa344ff, 0xffd0ff, breath(phase)));
        } else {
            int[] points = text.codePoints().toArray();
            StringBuilder frame = new StringBuilder();
            for (int i = 0; i < points.length; i++) {
                double offset = i / (double) Math.max(1, points.length);
                int rgb = switch (animation) {
                    // Широкий перелив и вторая ледяная лента: не просто смена общего цвета.
                    case "aurora" -> aurora(phase, offset);
                    // Один направленный узкий блик за проход; тёплая основа не гаснет.
                    case "shine" -> mix(0xffb000, 0xfffacd,
                            Math.pow(intensity(phase * 2 - offset * .85), 6));
                    case "prism" -> prism(phase, offset);
                    // Две связанные волны тепла дают языки пламени без случайного мерцания.
                    case "flame" -> flame(phase, offset);
                    default -> throw new IllegalStateException("Неизвестная серверная анимация");
                };
                // Явный RGB каждого символа переживает сериализацию и выделение метки в Core.
                frame.append(colored(Character.toString(points[i]), rgb));
            }
            name = frame.toString();
        }
        String icon = GuildStyleDefs.EMBLEMS.getOrDefault(emblem == null ? "" : emblem, "");
        return (icon.isEmpty() ? "" : "<gold>" + icon + "</gold> ") + name;
    }

    private static int aurora(double phase, double offset) {
        double wave = phase * 2 - offset * .8;
        int base = mix(0x00ffff, 0xff29ff, smooth(intensity(wave)));
        double ribbon = .35 * Math.pow(intensity(wave + .18), 10);
        return mix(base, 0xbfffff, ribbon);
    }

    /** Замкнутый спектр со сглаженной скоростью на каждом стыке цветов. */
    private static int prism(double phase, double offset) {
        double position = phase * 2 - offset;
        position = (position - Math.floor(position)) * PRISM_PALETTE.length;
        int segment = (int) Math.floor(position);
        return mix(PRISM_PALETTE[segment], PRISM_PALETTE[(segment + 1) % PRISM_PALETTE.length],
                smooth(position - segment));
    }

    private static int flame(double phase, double offset) {
        double heat = smooth(.72 * intensity(phase * 3 - offset * .9)
                + .28 * intensity(phase * 6 + offset * 1.4 + .15));
        return heat < .6 ? mix(0xff4b16, 0xffb31c, smooth(heat / .6))
                : mix(0xffb31c, 0xfff4a3, smooth((heat - .6) / .4));
    }

    /** Вдох 2 с, выдох 4 с: плавные нулевые скорости на обоих концах. */
    private static double breath(double phase) {
        double cycle = phase * 2;
        cycle -= Math.floor(cycle);
        return cycle < 1.0 / 3 ? smooth(cycle * 3) : 1 - smooth((cycle - 1.0 / 3) * 1.5);
    }

    private static double smooth(double value) { return value * value * (3 - 2 * value); }
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
