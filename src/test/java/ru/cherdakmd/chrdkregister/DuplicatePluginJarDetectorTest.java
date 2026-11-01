package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DuplicatePluginJarDetectorTest {

    @Test
    void detectsAmbiguousDuplicatePluginJarsAndIgnoresSingletons(@TempDir Path pluginsDir) throws Exception {
        writeStubJar(pluginsDir.resolve("chrdk-core-1.4.268.jar"), "CHRDK_Core", "1.4.268");
        writeStubJar(pluginsDir.resolve("chrdk-battlepass-1.4.267.jar"), "CHRDK_BattlePass", "1.4.267");
        writeStubJar(pluginsDir.resolve("chrdk-battlepass-1.4.268.jar"), "CHRDK_BattlePass", "1.4.268");
        writeStubJar(pluginsDir.resolve("chrdk-vk-1.4.268.jar"), "CHRDK_VK", "1.4.268");

        Map<String, List<String>> duplicates = DuplicatePluginJarDetector.scan(pluginsDir);
        assertEquals(1, duplicates.size());
        assertEquals(
                List.of("chrdk-battlepass-1.4.267.jar", "chrdk-battlepass-1.4.268.jar"),
                duplicates.get("CHRDK_BattlePass")
        );
        String summary = DuplicatePluginJarDetector.formatSummary(duplicates);
        assertTrue(summary.contains("CHRDK_BattlePass (chrdk-battlepass-1.4.267.jar, chrdk-battlepass-1.4.268.jar)"));
    }

    private static void writeStubJar(Path jarFile, String name, String version) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarFile))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            String yaml = "name: " + name + "\nversion: " + version + "\nmain: example.Main\n";
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
