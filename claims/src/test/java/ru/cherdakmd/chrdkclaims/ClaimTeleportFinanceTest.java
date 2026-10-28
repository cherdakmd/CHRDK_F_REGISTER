package ru.cherdakmd.chrdkclaims;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class ClaimTeleportFinanceTest {
    @Test void defaultAndConfiguredZeroAreNeverFree() {
        var c=new org.bukkit.configuration.file.YamlConfiguration(); assertEquals(1,ClaimsConfig.parse(c,Logger.getAnonymousLogger()).teleportCost);
        c.set("teleport.cost-diamonds",0); assertEquals(1,ClaimsConfig.parse(c,Logger.getAnonymousLogger()).teleportCost);
    }
    @Test void lateChunkCallbackMustOwnLeaseAndRevalidateClaimBeforePayment() throws Exception {
        String code=Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkclaims/ClaimTeleportService.java"));
        String finish=code.substring(code.indexOf("private void finish"),code.indexOf("private void runMain"));
        assertTrue(finish.indexOf("pending.get(player.getUniqueId())!=state")<finish.indexOf("payments().perform".replace("payments()","teleportPayments()")));
        assertTrue(finish.contains("safeLocation")); assertTrue(code.contains("current==original")); assertTrue(code.contains("current.canUse"));
        assertFalse(code.contains("teleportAsync(target)")); assertTrue(code.contains("PaidActions.teleport(player,target)")); assertTrue(code.contains("state.timeout"));
    }
}
