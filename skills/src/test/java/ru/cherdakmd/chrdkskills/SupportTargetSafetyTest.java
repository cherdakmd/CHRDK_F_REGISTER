package ru.cherdakmd.chrdkskills;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class SupportTargetSafetyTest {
    @Test void withoutVerifiedPositiveEffectProtectionOnlyCasterIsSelected() {
        Player caster = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> { throw new AssertionError("Target selection must not inspect other entities"); });
        assertSame(caster, BranchAbilities.supportTarget(caster));
        assertNull(BranchAbilities.supportTarget(null));
    }
}
