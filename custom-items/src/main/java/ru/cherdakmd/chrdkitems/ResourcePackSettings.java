package ru.cherdakmd.chrdkitems;

import org.bukkit.configuration.ConfigurationSection;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.zip.ZipInputStream;

/** Hash and embedded hosting use the same bounded snapshot taken at startup. */
final class ResourcePackSettings {
    static final UUID PACK_ID = UUID.fromString("75fbba67-5b69-42fc-a672-3aa208dc167c");
    static final String PACK_FILE = "custom-items-pack.zip";
    static final String PACK_PATH = "/custom-items-pack.zip";
    private static final int MAX_SIZE = 50 * 1024 * 1024;
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
    record Pack(String url, String sha1, boolean required, byte[] bytes, boolean host, String bind, int port) {
        byte[] hashBytes() { return HexFormat.of().parseHex(sha1); }
    }

    static Pack parse(ConfigurationSection config, Path localFile) {
        if (!config.getBoolean("resource-pack.enabled", true)) return null;
        String url = config.getString("resource-pack.url", "");
        String pinned = config.getString("resource-pack.sha1", "");
        boolean host = config.isSet("resource-pack.host.enabled") && config.getBoolean("resource-pack.host.enabled", false);
        if (url == null || url.isBlank()) throw new IllegalArgumentException("resource-pack: configure a public URL or disable delivery");
        if (url.length() > 2048 || !url.equals(url.trim())) throw new IllegalArgumentException("resource-pack: invalid URL");
        try {
            URI uri = URI.create(url);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean http = "http".equalsIgnoreCase(uri.getScheme());
            if ((!https && !(host && http)) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || (host && (!PACK_PATH.equals(uri.getRawPath()) || uri.getRawQuery() != null))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("resource-pack: invalid URL; embedded hosting needs HTTP(S) and path " + PACK_PATH + ", external hosting needs HTTPS");
        }
        String bind = config.getString("resource-pack.host.bind", "0.0.0.0");
        int port = config.getInt("resource-pack.host.port", 8123);
        if (host && (bind == null || bind.isBlank() || !bind.equals(bind.trim()) || port < 1 || port > 65535))
            throw new IllegalArgumentException("resource-pack: invalid host bind address or port");
        byte[] bytes = localBytes(localFile);
        String actual = sha1(bytes);
        if (pinned == null || (!pinned.isEmpty() && (!SHA1.matcher(pinned).matches() || !actual.equals(pinned.toLowerCase(Locale.ROOT)))))
            throw new IllegalArgumentException("resource-pack: configured SHA-1 differs from local ZIP");
        return new Pack(url, actual, config.getBoolean("resource-pack.required", false), bytes, host, bind, port);
    }

    static String localHash(Path file) { return sha1(localBytes(file)); }

    private static byte[] localBytes(Path file) {
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsafe pack file");
            byte[] bytes;
            try (var in = Files.newInputStream(file)) { bytes = in.readNBytes(MAX_SIZE + 1); }
            if (bytes.length == 0 || bytes.length > MAX_SIZE) throw new IOException("invalid size");
            boolean metadata = false;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                long scanned = 0;
                int entries = 0;
                byte[] buffer = new byte[8192];
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (++entries > 10000) throw new IOException("too many ZIP entries");
                    if (entry.getName().equals("pack.mcmeta") && !entry.isDirectory()) { metadata = true; break; }
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        scanned += count;
                        if (scanned > 8L * 1024 * 1024) throw new IOException("pack.mcmeta is too far into ZIP");
                    }
                }
            }
            if (!metadata) throw new IOException("pack.mcmeta missing");
            return bytes;
        } catch (IOException e) {
            throw new IllegalArgumentException("resource-pack: local ZIP unavailable or invalid", e);
        }
    }
    private static String sha1(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
