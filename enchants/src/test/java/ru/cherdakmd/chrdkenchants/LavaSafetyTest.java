package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LavaSafetyTest {
    @Test void fallbackDoesNotModifyOrRestoreWorldBlocks() throws Exception {
        String source=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/AuraService.java"));
        assertFalse(source.contains("setType("));
        assertFalse(source.contains("setBlockData("));
        assertFalse(source.contains("lavaCrust"));
        assertFalse(source.contains("Material.MAGMA_BLOCK"));
        assertFalse(source.contains("walkLava"));
        assertFalse(source.contains("lavawalker"));
        assertFalse(source.contains("player.setFireTicks(0)"));
    }
}
