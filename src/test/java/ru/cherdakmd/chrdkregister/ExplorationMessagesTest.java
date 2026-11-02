package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Shared helpers use addon Messages, not Core's unrelated translation bundle. */
class ExplorationMessagesTest {
    boolean hasTranslation(Component c) {
        return c instanceof TranslatableComponent t && t.key().equals("biome.minecraft.plains") || c.children().stream().anyMatch(this::hasTranslation);
    }
    @Test void bothAddonBundlesCoverSharedAtlasAndPreserveClientBiomeTranslation() throws Exception {
        var keys = new HashSet<String>();
        var pattern = Pattern.compile("\"(atlas-[a-z-]+)\"");
        for (String file : List.of("ExplorationAtlas.java", "ExplorationCatalog.java")) {
            var match = pattern.matcher(Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/api/exploration/" + file)));
            while (match.find()) keys.add(match.group(1));
        }
        assertTrue(keys.size() > 20);
        for (String module : List.of("battlepass", "achievements")) {
            try (var stream = Files.newInputStream(Path.of(module + "/src/main/resources/messages.yml"))) {
                var messages = Messages.from(stream, null, Logger.getAnonymousLogger());
                for (String key : keys) { assertTrue(messages.has(key), module + ":" + key); assertDoesNotThrow(() -> messages.getList(key)); }
                var biome = Messages.trusted(MiniMessage.miniMessage().serialize(Component.translatable("biome.minecraft.plains")));
                assertTrue(hasTranslation(messages.get("atlas-discovered", "biome", biome)));
            }
        }
    }
}
