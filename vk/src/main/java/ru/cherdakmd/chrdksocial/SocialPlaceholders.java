package ru.cherdakmd.chrdksocial;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.cherdakmd.chrdkvk.VkPlugin;

import java.util.Locale;

/**
 * Плейсхолдеры TAB для единого социального плагина. Живут в {@code CHRDK_VK},
 * поэтому ядру не нужно знать ни о {@code social-links.json}, ни о товаре
 * {@code social-access}.
 *
 * <p>{@code %chrdksocial_access%} — остаток срока товара «Вход без ВК и MAX»
 * ({@code 27 дн.}, {@code нет} или {@code ∞}); {@code %chrdksocial_link%} —
 * персональный статус привязки ({@code ВК}, {@code MAX}, {@code ещё 12 дн.},
 * {@code обязательна}). Значения — обычный текст, без MiniMessage: так требует
 * PlaceholderAPI. Регистрируется только при установленном PAPI.</p>
 */
public final class SocialPlaceholders extends PlaceholderExpansion {

    private final VkPlugin plugin;
    private final SocialService social;
    private final String version;

    public SocialPlaceholders(VkPlugin plugin, SocialService social) {
        this.plugin = plugin;
        this.social = social;
        this.version = plugin.getDescription().getVersion();
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chrdksocial";
    }

    @Override
    public @NotNull String getAuthor() {
        return "cherdakmd";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    /** Переживает {@code /papi reload}: счётчики не пропадают до рестарта сервера. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @Nullable String params) {
        if (player == null || params == null || params.isBlank()) return "";
        String name = player.getName();
        if (name == null || name.isBlank()) return "";
        long now = System.currentTimeMillis();
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "access" -> SocialAccessPolicy.accessCounter(
                    social.donateUntil(name), now, social.permanentDonorAccess(name));
            case "link" -> SocialAccessPolicy.linkCounter(
                    social.linkProvider(name), social.graceDeadline(name, now), now,
                    social.config().mandatoryActive(), social.emergencyActive(now));
            default -> null;
        };
    }
}
