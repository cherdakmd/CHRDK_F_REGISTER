package ru.cherdakmd.chrdkitems;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePackSettingsTest {
    @TempDir Path dir;
    private YamlConfiguration config(String url, String hash) {
        var yaml = new YamlConfiguration();
        yaml.set("resource-pack.url", url);
        yaml.set("resource-pack.sha1", hash);
        return yaml;
    }
    private Path pack() throws Exception {
        var file = dir.resolve("pack.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta")); zip.write("{}".getBytes()); zip.closeEntry();
        }
        return file;
    }
    @Test void usesLocalHashAndDefaultsToOptional() throws Exception {
        var file = pack();
        var c = config("https://cdn.example.com/pack.zip", "");
        var pack = ResourcePackSettings.parse(c, file);
        assertEquals(ResourcePackSettings.localHash(file), pack.sha1());
        assertEquals(pack.sha1(), HexFormat.of().formatHex(pack.hashBytes()));
        assertFalse(pack.required());
        c.set("resource-pack.sha1", pack.sha1().toUpperCase()); c.set("resource-pack.required", true);
        assertTrue(ResourcePackSettings.parse(c, file).required());
        c.set("resource-pack.sha1", "a".repeat(40));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(c, file));
        var disabled = config("bad url", "bad hash"); disabled.set("resource-pack.enabled", false);
        assertNull(ResourcePackSettings.parse(disabled, dir.resolve("missing")));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(new YamlConfiguration(), file));
        for (String url : new String[]{"http://cdn.example.com/pack.zip", "https:///file", "https://user:pass@cdn.example.com/a", "https://cdn.example.com/a#section", " https://cdn.example.com/a", "https://cdn.example.com:99999/a"})
            assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(config(url, ""), file), url);
        Files.writeString(file, "not a zip");
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(config("https://cdn.example.com/a", ""), file));
    }
    @Test void embeddedHostAcceptsHttpOnlyWithExactPathAndValidPort() throws Exception {
        var file = pack();
        var c = config("http://example.com:8123/custom-items-pack.zip", "");
        c.set("resource-pack.host.enabled", true);
        var parsed = ResourcePackSettings.parse(c, file);
        assertTrue(parsed.host());
        assertEquals(8123, parsed.port());
        assertEquals("0.0.0.0", parsed.bind());
        assertFalse(parsed.required());
        assertArrayEquals(Files.readAllBytes(file), parsed.bytes());
        c.set("resource-pack.url", "http://example.com:8123/custom-items-pack.zip?x=1");
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(c, file));
        c.set("resource-pack.url", "http://example.com:8123/other");
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(c, file));
        c.set("resource-pack.url", "https://example.com/custom-items-pack.zip");
        assertTrue(ResourcePackSettings.parse(c, file).host());
        c.set("resource-pack.host.port", 0);
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(c, file));
        c.set("resource-pack.host.enabled", false);
        c.set("resource-pack.url", "http://example.com/custom-items-pack.zip");
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(c, file));
    }

}
