package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BundleVersionAuditTest {
    @Test void acceptsCompleteMatchingBundleAndIgnoresThirdPartyPlugins() {
        var versions = new LinkedHashMap<String, String>();
        versions.put("CHRDK_Core", "2.0");
        versions.put("CHRDK_DiamondShop", "2.0");
        versions.put("LuckPerms", "5.4");
        assertTrue(BundleVersionAudit.find(versions, "2.0").isEmpty());
    }

    @Test void reportsEveryMixedChrdkPluginWithoutTreatingMissingOptionalAddonAsError() {
        var versions = Map.of("CHRDK_Core", "2.0", "CHRDK_DiamondShop", "1.5.8-rc.3",
                "CHRDK_Claims", "2.0", "Vault", "1.7");
        var result = BundleVersionAudit.find(versions, "2.0");
        assertEquals(1, result.size());
        assertEquals("CHRDK_DiamondShop=1.5.8-rc.3 (Core=2.0)", BundleVersionAudit.summary(result));
    }

    @Test void unavailableVersionNeverPassesSilently() {
        assertFalse(BundleVersionAudit.find(null, "2.0").isEmpty());
        assertFalse(BundleVersionAudit.find(Map.of(), null).isEmpty());
        assertFalse(BundleVersionAudit.find(Map.of(), " ").isEmpty());
    }

    @Test void startupAndDoctorExposeMixedBundleAndBankStopGates() throws Exception {
        String plugin = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/RegisterPlugin.java"));
        String doctor = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/RegisterCommandHandler.java"));
        assertTrue(plugin.contains("КОМПЛЕКТ CHRDK СМЕШАННЫХ ВЕРСИЙ"));
        assertTrue(doctor.contains("Единая версия комплекта CHRDK"));
        assertTrue(doctor.contains("bankHealth.available() && bankHealth.pending() == 0"));
        assertTrue(doctor.contains("BundleVersionAudit.find"));
    }
}
