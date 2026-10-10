package ru.cherdakmd.chrdkregister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern TWITCH_LOGIN = Pattern.compile("[a-zA-Z0-9_]{1,25}");

    public String publicBaseUrl = "";
    public String bindAddress = "127.0.0.1";
    public int webPort = 8787;

    public String twitchClientId = "";
    public String twitchClientSecret = "";
    public String twitchChannelLogin = "";
    public String twitchChannelId = "";

    public String donatePayApiToken = "";
    public String donatePayPaymentUrl = "";

    public int minimumDonationRubles = 250;
    public int grantDays = 30;
    public int ticketLifetimeHours = 168;
    public int donatePollSeconds = 60;
    public int twitchCheckSeconds = 600;

    public static ModConfig load(Logger logger) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("chrdk_f_register.json");
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                ModConfig defaults = new ModConfig();
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaults, writer);
                }
                logger.info("Created default registration config at {}", path.toAbsolutePath());
                return defaults;
            }

            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                ModConfig config = GSON.fromJson(reader, ModConfig.class);
                if (config == null) {
                    throw new IOException("Config file is empty");
                }
                config.normalize();
                return config;
            }
        } catch (Exception exception) {
            logger.error("Could not read registration config {}. Default settings will be used; fix the file before enabling registrations.", path.toAbsolutePath(), exception);
            return new ModConfig();
        }
    }

    private void normalize() {
        if (bindAddress == null || bindAddress.isBlank()) bindAddress = "127.0.0.1";
        if (webPort < 1 || webPort > 65535) webPort = 8787;
        if (twitchClientId == null) twitchClientId = "";
        if (twitchClientSecret == null) twitchClientSecret = "";
        if (twitchChannelLogin == null) twitchChannelLogin = "";
        if (twitchChannelId == null) twitchChannelId = "";
        if (donatePayApiToken == null) donatePayApiToken = "";
        if (donatePayPaymentUrl == null) donatePayPaymentUrl = "";
        if (minimumDonationRubles < 250) minimumDonationRubles = 250;
        if (grantDays < 1 || grantDays > 3650) grantDays = 30;
        if (ticketLifetimeHours < 1 || ticketLifetimeHours > 720) ticketLifetimeHours = 168;
        if (donatePollSeconds < 20) donatePollSeconds = 60;
        if (twitchCheckSeconds < 60) twitchCheckSeconds = 600;
    }

    public String normalizedPublicBaseUrl() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) return null;
        try {
            URI uri = URI.create(publicBaseUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
                return null;
            }
            String value = uri.toString();
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            return value;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public boolean twitchReady() {
        return !blank(twitchClientId) && !blank(twitchClientSecret)
                && twitchChannelLogin != null && TWITCH_LOGIN.matcher(twitchChannelLogin.trim()).matches();
    }

    public boolean donatePayReady() {
        return !blank(donatePayApiToken);
    }

    public boolean webReady() {
        return normalizedPublicBaseUrl() != null;
    }

    public String twitchChannelUrl() {
        if (twitchChannelLogin == null || !TWITCH_LOGIN.matcher(twitchChannelLogin.trim()).matches()) return "";
        return "https://www.twitch.tv/" + twitchChannelLogin.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
