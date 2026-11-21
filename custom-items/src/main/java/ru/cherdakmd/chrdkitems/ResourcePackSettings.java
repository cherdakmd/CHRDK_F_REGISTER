package ru.cherdakmd.chrdkitems;

import org.bukkit.configuration.ConfigurationSection;
import java.net.URI;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates operator-provided distribution metadata; never downloads the URL. */
final class ResourcePackSettings {
    static final UUID PACK_ID = UUID.fromString("75fbba67-5b69-42fc-a672-3aa208dc167c");
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
    record Pack(String url, String sha1) {
        byte[] hashBytes() { return HexFormat.of().parseHex(sha1); }
    }

    static Pack parse(ConfigurationSection config) {
        if (!config.getBoolean("resource-pack.enabled", true)) return null;
        String url = config.getString("resource-pack.url", "");
        String hash = config.getString("resource-pack.sha1", "");
        if (url == null || hash == null || url.isBlank() || hash.isBlank())
            throw new IllegalArgumentException("resource-pack: configure a public HTTPS URL and SHA-1, or disable it");
        if (url.length() > 2048 || !url.equals(url.trim()) || !SHA1.matcher(hash).matches())
            throw new IllegalArgumentException("resource-pack: invalid URL or SHA-1 (40 hex characters required)");
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("resource-pack: URL must be an absolute HTTPS URL without credentials or fragment");
        }
        return new Pack(url, hash.toLowerCase(Locale.ROOT));
    }
}
