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
        int start=source.indexOf("private void walkLava(");
        assertTrue(start>=0);
        String effect=source.substring(start);
        assertTrue(effect.contains("Material.LAVA"));
        assertTrue(effect.contains("PotionEffectType.FIRE_RESISTANCE"));
        assertTrue(effect.contains("player.setFireTicks(0)"));
    }
}
