package ru.cherdakmd.chrdkprofessions;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AnvilBridgeTest {
    @Test void discountRunsBeforeCustomBuildersAndUsesIdempotentFullPriceBridge() throws Exception {
        var listener=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionListener.java"));
        assertTrue(listener.contains("AnvilCosts.discount(event.getView(),event.getResult(),percent)"));
        var prepare=listener.substring(listener.indexOf("public void onPrepareAnvil"),listener.indexOf("/** Сытный стол"));
        assertFalse(prepare.contains("view.getRepairCost()"));assertFalse(prepare.contains("view.setRepairCost("));
        for(String module:new String[]{"enchants","tier-forge"}) {
            var text=Files.readString(Path.of("..",module,"src/main/resources/plugin.yml"));
            assertTrue(text.contains("softdepend: [CHRDK_Professions, "),module);
        }
        var plugin=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkprofessions/ProfessionsPlugin.java"));
        assertTrue(plugin.contains("getInt(null)<40"));
    }
}
