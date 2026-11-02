package ru.cherdakmd.chrdkregister;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Чистые синхронные кадры: без IO, случайности, виртуальных градиентов и задач на игрока. */
final class TabDashboard {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Moscow"));
    private TabDashboard() { }

    record Frame(String brand, String separator, String tip, String clock, String balance,
                 String boost, String health, String ping, String world, String mspt) {
        static Frame empty() { return new Frame("", "", "", "", "", "", "", "", "", ""); }
        Object[] placeholders() {
            return new Object[]{"tab_brand", Messages.trusted(brand), "tab_separator", Messages.trusted(separator),
                    "tab_tip", tip, "tab_clock", clock, "tab_balance", balance, "tab_boost", boost,
                    "tab_health", Messages.trusted(health), "tab_ping", Messages.trusted(ping), "tab_world", world, "tab_mspt", mspt};
        }
    }
    static Frame frame(Messages messages, long now, boolean authenticated, long balance, long boostMillis,
                       double tps, double mspt, int ping, String world) {
        String hidden = messages.plain("tab.sign-in");
        return new Frame(wave(messages.plain("tab.brand"), now), separator(now),
                authenticated ? messages.plain("tab.tip-" + Math.floorMod(now / 12000, 6)) : hidden,
                CLOCK.format(Instant.ofEpochMilli(now)),
                !authenticated ? hidden : balance < 0 ? messages.plain("tab.unavailable") : BankService.format(balance),
                !authenticated ? hidden : boostMillis <= 0 ? messages.plain("tab.boost-off")
                        : messages.plain("tab.boost-time", "hours", boostMillis / 3600000, "minutes", boostMillis % 3600000 / 60000),
                messages.raw(healthKey(tps, mspt)),
                messages.raw(ping < 0 ? "tab.ping-unknown" : ping <= 80 ? "tab.ping-good" : ping <= 160 ? "tab.ping-medium" : "tab.ping-high", "ping", Math.max(0, ping)),
                authenticated ? world : hidden,
                Double.isFinite(mspt) && mspt >= 0 ? String.format(Locale.ROOT, "%.1f", mspt) : "—");
    }
    static String healthKey(double tps, double mspt) {
        if (!Double.isFinite(tps) || !Double.isFinite(mspt) || tps < 0 || mspt < 0) return "tab.health-unknown";
        return tps >= 19 && mspt <= 40 ? "tab.health-good" : tps >= 17 && mspt <= 55 ? "tab.health-medium" : "tab.health-high";
    }
    static String wave(String text, long now) {
        StringBuilder out = new StringBuilder();
        int[] points = text.codePoints().toArray();
        double phase = Math.floorMod(now, 8000) / 8000.0;
        for (int i = 0; i < points.length; i++) {
            double mix = (1 + Math.sin((phase + i * .075) * Math.PI * 2)) / 2;
            int r = (int)Math.round(103 + 152 * mix), g = (int)Math.round(232 - 21 * mix), b = (int)Math.round(249 - 143 * mix);
            out.append(color(Character.toString(points[i]), r << 16 | g << 8 | b));
        }
        return out.toString();
    }
    static String separator(long now) {
        StringBuilder out = new StringBuilder();
        int head = Math.floorMod(now / 250, 24);
        for (int i=0;i<24;i++) out.append(color("━", Math.floorMod(i-head,24)<3 ? 0x67e8f9 : 0x455468));
        return out.toString();
    }
    private static String color(String text, int rgb) {
        String hex="#"+Integer.toHexString(0x1000000|rgb).substring(1);
        return "<"+hex+">"+Messages.escape(text)+"</"+hex+">";
    }
}
