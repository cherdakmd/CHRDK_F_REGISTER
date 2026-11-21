package ru.cherdakmd.chrdkitems;

import org.bukkit.configuration.ConfigurationSection;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Uses exactly the local pack's hash; the external URL must serve that same file. */
final class ResourcePackSettings {
    static final UUID PACK_ID = UUID.fromString("75fbba67-5b69-42fc-a672-3aa208dc167c");
    static final String PACK_FILE = "custom-items-pack.zip";
    private static final long MAX_SIZE = 50L * 1024 * 1024;
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
    record Pack(String url, String sha1, boolean required) {
        byte[] hashBytes() { return HexFormat.of().parseHex(sha1); }
    }

    static Pack parse(ConfigurationSection config, Path localFile) {
        if (!config.getBoolean("resource-pack.enabled", true)) return null;
        String url = config.getString("resource-pack.url", "");
        String pinned = config.getString("resource-pack.sha1", "");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("resource-pack: configure a public HTTPS URL or disable delivery");
        if (url.length() > 2048 || !url.equals(url.trim())) throw new IllegalArgumentException("resource-pack: invalid URL");
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("resource-pack: URL must be absolute HTTPS without credentials or fragment");
        }
        String actual = localHash(localFile);
        if (pinned == null || (!pinned.isEmpty() && (!SHA1.matcher(pinned).matches() || !actual.equals(pinned.toLowerCase(Locale.ROOT)))))
            throw new IllegalArgumentException("resource-pack: configured SHA-1 differs from local ZIP");
        return new Pack(url, actual, config.getBoolean("resource-pack.required", false));
    }

    static String localHash(Path file) {
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) < 1 || Files.size(file) > MAX_SIZE)
                throw new IOException("missing or unsafe pack file");
            try (ZipFile zip = new ZipFile(file.toFile())) {
                if (zip.getEntry("pack.mcmeta") == null) throw new IOException("pack.mcmeta missing");
            }
            var digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("resource-pack: local ZIP unavailable or invalid", e);
        }
    }
}
