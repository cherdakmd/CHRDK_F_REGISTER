package ru.cherdakmd.chrdkenchants;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class AnchorPolicyTest {
    @Test void horizontalReductionIsBoundedAt15And30Percent() {
        assertEquals(.85, AnchorPolicy.factor(1), 1e-12); assertEquals(.70, AnchorPolicy.factor(2), 1e-12);
        assertEquals(.70, AnchorPolicy.factor(100), 1e-12); assertEquals(.85, AnchorPolicy.factor(-1), 1e-12);
        assertTrue(AnchorPolicy.finite(-2, .4, 3)); assertFalse(AnchorPolicy.finite(0,.4,0));
        assertFalse(AnchorPolicy.finite(Double.NaN,0,1)); assertFalse(AnchorPolicy.finite(1,Double.POSITIVE_INFINITY,0));
    }
    @Test void nativePveOnlyHandlerPreservesVerticalAndSharedGuardCooldown() throws Exception {
        var s = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/AnchorListener.java"));
        for(String guard : List.of("EntityKnockbackByEntityEvent", "ignoreCancelled = true", "ENTITY_ATTACK", "PveEffectsListener.hostile(attacker)", "isAuthenticated", "EnchantTimers.PVE", "PersistentDataType.LONG", "anchorPreferred")) assertTrue(s.contains(guard), guard);
        assertFalse(s.contains("setY(")); assertFalse(s.contains("setVelocity")); assertFalse(s.contains("getAttribute"));
        var effect = PveEnchant.byId("pve_anchor"); assertNotNull(effect); assertEquals(PveEnchant.Channel.GUARD, effect.channel);
        assertEquals(15000, effect.cooldownSeconds * 1000);
    }
}
