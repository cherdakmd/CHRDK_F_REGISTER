package ru.cherdakmd.chrdkregister;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Место для {@code %tab_*%} вне внутреннего рендера CHRDK: PlaceholderAPI и внешние
 * плагины TAB/скорборда запрашивают плейсхолдеры по имени, а не через
 * {@link TabListService.TabContext#placeholders()}. Без этой регистрации stock-строки
 * шапки/подвала остаются сырым текстом {@code %tab_brand%}, {@code %tab_balance%} и т.д.
 *
 * <p>Значения повторяют {@link TabDashboard.Frame}, но без MiniMessage-разметки:
 * PlaceholderAPI ожидает обычный текст. Анимация бренда и цветного сепаратора
 * остаётся во внутреннем рендере CHRDK; здесь — читаемые статические equivalents.</p>
 */
public final class TabPlaceholderIntegration extends PlaceholderExpansion {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Moscow"));

    private final String version;
    private final Messages messages;
    private final AccessManager access;
    private final BankService bank;

    public TabPlaceholderIntegration(String version, Messages messages,
                                     @Nullable AccessManager access, @Nullable BankService bank) {
        this.version = version;
        this.messages = messages;
        this.access = access;
        this.bank = bank;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "tab";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cherdakmd";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    /** Переживает {@code /papi reload}: TAB не теряет плейсхолдеры до перезапуска сервера. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @Nullable String params) {
        if (params == null || params.isBlank()) return "";
        String key = params.toLowerCase(Locale.ROOT);
        if (!isTabKey(key)) return null;

        long now = System.currentTimeMillis();
        Player live = offlinePlayer == null ? null : offlinePlayer.getPlayer();
        boolean authenticated = live != null && access != null
                && access.getStage(live) == AccessManager.PlayerStage.AUTHENTICATED;
        String hidden = messages.plain("tab.sign-in");

        double tps = Double.NaN, mspt = Double.NaN;
        try {
            double[] tpsArray = Bukkit.getTPS();
            if (tpsArray != null && tpsArray.length > 0) tps = tpsArray[0];
            mspt = Bukkit.getAverageTickTime();
        } catch (Throwable ignored) {
            // В юнит-тестах и до запуска Bukkit телеметрия недоступна.
        }
        int ping = live == null ? -1 : live.getPing();
        String world = live == null ? "" : live.getWorld().getName();
        long balance = authenticated && bank != null && bank.enabled()
                ? bank.balance(live) : -1;
        long boostMillis = authenticated && access != null
                ? access.store().rewardBoostRemaining(live.getName()) : 0;

        return switch (key) {
            case "brand" -> messages.plain("tab.brand");
            case "separator" -> "━".repeat(24);
            case "tip" -> authenticated
                    ? messages.plain("tab.tip-" + Math.floorMod(now / 12000, 6)) : hidden;
            case "clock" -> CLOCK.format(Instant.ofEpochMilli(now));
            case "balance" -> !authenticated ? hidden
                    : balance < 0 ? messages.plain("tab.unavailable") : BankService.format(balance);
            case "boost" -> !authenticated ? hidden
                    : boostMillis <= 0 ? messages.plain("tab.boost-off")
                    : messages.plain("tab.boost-time", "hours", boostMillis / 3600000,
                            "minutes", boostMillis % 3600000 / 60000);
            case "health" -> messages.plain(TabDashboard.healthKey(tps, mspt));
            case "ping" -> messages.plain(ping < 0 ? "tab.ping-unknown"
                    : ping <= 80 ? "tab.ping-good" : ping <= 160 ? "tab.ping-medium" : "tab.ping-high",
                    "ping", Math.max(0, ping));
            case "world" -> authenticated ? world : hidden;
            case "mspt" -> Double.isFinite(mspt) && mspt >= 0
                    ? String.format(Locale.ROOT, "%.1f", mspt) : "—";
            default -> null;
        };
    }

    /** Ровно те ключи, которые использует stock-конфиг {@code tablist.header/footer}. */
    static boolean isTabKey(String key) {
        return switch (key) {
            case "brand", "separator", "tip", "clock", "balance", "boost",
                 "health", "ping", "world", "mspt" -> true;
            default -> false;
        };
    }
}
