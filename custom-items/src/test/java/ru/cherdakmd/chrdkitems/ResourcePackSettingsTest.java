package ru.cherdakmd.chrdkitems;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePackSettingsTest {
    private YamlConfiguration config(String url, String hash) {
        var yaml = new YamlConfiguration();
        yaml.set("resource-pack.url", url);
        yaml.set("resource-pack.sha1", hash);
        return yaml;
    }
    @Test void onlyConfiguredSecurePackIsSent() {
        var hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01";
        var pack = ResourcePackSettings.parse(config("https://cdn.example.com/pack.zip", hash));
        assertEquals(hash.toLowerCase(), pack.sha1());
        assertEquals(pack.sha1(), HexFormat.of().formatHex(pack.hashBytes()));
        var disabled = config("bad url", "bad hash");
        disabled.set("resource-pack.enabled", false);
        assertNull(ResourcePackSettings.parse(disabled));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(new YamlConfiguration()));
        assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(config("https://cdn.example.com/pack.zip", "abc")));
        for (String url : new String[]{"http://cdn.example.com/pack.zip", "https:///file", "https://user:pass@cdn.example.com/a", "https://cdn.example.com/a#section", " https://cdn.example.com/a", "https://cdn.example.com:99999/a"})
            assertThrows(IllegalArgumentException.class, () -> ResourcePackSettings.parse(config(url, hash)), url);
    }
}
