package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;

/** Holds validated configuration settings for the Purpur registration, DonatePay pass & TOTP 2FA plugin. */
public final class PluginConfig {
    private final String donatePayApiToken;
    private final String donatePayPaymentUrl;
    private final int minimumDonationRubles;
    private final int donatePollSeconds;
    private final boolean strictCaseMatch;
    private final int grantDays;
    private final int sessionMinutes;
    private final int minPasswordLength;
    private final int maxPasswordLength;
    private final int maxLoginAttempts;
    private final int reminderSeconds;
    private final boolean totpEnabled;
    private final String totpIssuer;
    private final int totpWindowSteps;
    private final int totpMaxAttempts;

    public PluginConfig(
            String donatePayApiToken,
            String donatePayPaymentUrl,
            int minimumDonationRubles,
            int donatePollSeconds,
            boolean strictCaseMatch,
            int grantDays,
            int sessionMinutes,
            int minPasswordLength,
            int maxPasswordLength,
            int maxLoginAttempts,
            int reminderSeconds
    ) {
        this(
                donatePayApiToken,
                donatePayPaymentUrl,
                minimumDonationRubles,
                donatePollSeconds,
                strictCaseMatch,
                grantDays,
                sessionMinutes,
                minPasswordLength,
                maxPasswordLength,
                maxLoginAttempts,
                reminderSeconds,
                true,
                "CHRDK",
                1,
                maxLoginAttempts
        );
    }

    public PluginConfig(
            String donatePayApiToken,
            String donatePayPaymentUrl,
            int minimumDonationRubles,
            int donatePollSeconds,
            boolean strictCaseMatch,
            int grantDays,
            int sessionMinutes,
            int minPasswordLength,
            int maxPasswordLength,
            int maxLoginAttempts,
            int reminderSeconds,
            boolean totpEnabled,
            String totpIssuer,
            int totpWindowSteps,
            int totpMaxAttempts
    ) {
        this.donatePayApiToken = donatePayApiToken == null ? "" : donatePayApiToken.trim();
        this.donatePayPaymentUrl = sanitizeUrl(donatePayPaymentUrl);
        this.minimumDonationRubles = Math.max(1, minimumDonationRubles);
        this.donatePollSeconds = Math.max(15, donatePollSeconds);
        this.strictCaseMatch = strictCaseMatch;
        this.grantDays = Math.clamp(grantDays, 1, 3650);
        this.sessionMinutes = Math.clamp(sessionMinutes, 0, 10080);
        this.minPasswordLength = Math.clamp(minPasswordLength, 4, 32);
        this.maxPasswordLength = Math.max(this.minPasswordLength, Math.min(128, maxPasswordLength));
        this.maxLoginAttempts = Math.clamp(maxLoginAttempts, 1, 20);
        this.reminderSeconds = Math.clamp(reminderSeconds, 2, 120);
        this.totpEnabled = totpEnabled;
        this.totpIssuer = (totpIssuer == null || totpIssuer.isBlank()) ? "CHRDK" : totpIssuer.trim();
        this.totpWindowSteps = Math.clamp(totpWindowSteps, 0, 10);
        this.totpMaxAttempts = Math.clamp(totpMaxAttempts, 1, 20);
    }

    public static PluginConfig fromBukkitConfig(FileConfiguration config) {
        int maxLoginAttempts = config.getInt("auth.max-login-attempts", 5);
        return new PluginConfig(
                config.getString("donatepay.api-token", ""),
                config.getString("donatepay.payment-url", ""),
                config.getInt("donatepay.minimum-rubles", 150),
                config.getInt("donatepay.poll-seconds", 30),
                config.getBoolean("donatepay.strict-case-match", false),
                config.getInt("pass.grant-days", 30),
                config.getInt("auth.session-minutes", 30),
                config.getInt("auth.min-password-length", 6),
                config.getInt("auth.max-password-length", 64),
                maxLoginAttempts,
                config.getInt("auth.reminder-seconds", 5),
                config.getBoolean("totp.enabled", true),
                config.getString("totp.issuer", "CHRDK"),
                config.getInt("totp.window-steps", 1),
                config.getInt("totp.max-attempts", maxLoginAttempts)
        );
    }

    public String donatePayApiToken() {
        return donatePayApiToken;
    }

    public String donatePayPaymentUrl() {
        return donatePayPaymentUrl;
    }

    public int minimumDonationRubles() {
        return minimumDonationRubles;
    }

    public int donatePollSeconds() {
        return donatePollSeconds;
    }

    public boolean strictCaseMatch() {
        return strictCaseMatch;
    }

    public int grantDays() {
        return grantDays;
    }

    public int sessionMinutes() {
        return sessionMinutes;
    }

    public int minPasswordLength() {
        return minPasswordLength;
    }

    public int maxPasswordLength() {
        return maxPasswordLength;
    }

    public int maxLoginAttempts() {
        return maxLoginAttempts;
    }

    public int reminderSeconds() {
        return reminderSeconds;
    }

    public boolean totpEnabled() {
        return totpEnabled;
    }

    public String totpIssuer() {
        return totpIssuer;
    }

    public int totpWindowSteps() {
        return totpWindowSteps;
    }

    public int totpMaxAttempts() {
        return totpMaxAttempts;
    }

    public boolean donatePayReady() {
        return !donatePayApiToken.isBlank();
    }

    public boolean hasPaymentUrl() {
        return !donatePayPaymentUrl.isBlank();
    }

    private static String sanitizeUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                return "";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
            return trimmed;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
