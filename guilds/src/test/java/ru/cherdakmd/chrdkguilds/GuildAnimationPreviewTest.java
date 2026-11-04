package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class GuildAnimationPreviewTest {
    @Test void screenshotFixtureExactlyMatchesEveryProductionFrameAndSourceHash() throws Exception {
        assertEquals(Files.readString(Path.of("src/test/resources/clan-animation-preview.json")),
                GuildAnimationPreview.frames(), "Пересоздайте fixture из Java-рендерера, не меняйте цвета вручную");
    }
}
