package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import static org.junit.jupiter.api.Assertions.*;

class SupportTargetSafetyTest {
    private org.bukkit.entity.Player player() {
        return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class},
                (p,m,a) -> { throw new AssertionError(m.getName()); });
    }
    @Test void recheckCannotRedirectAnAlreadyChargedCast() {
        var self=player(); var target=player(); var other=player();
        assertSame(target, BranchAbilities.revalidateSupport(self,target,()->target));
        assertSame(self, BranchAbilities.revalidateSupport(self,target,()->other));
        assertSame(self, BranchAbilities.revalidateSupport(self,target,()->null));
        assertSame(self, BranchAbilities.revalidateSupport(self,target,()->{ throw new IllegalStateException(); }));
        assertSame(self, BranchAbilities.revalidateSupport(self,self,()->{ throw new AssertionError(); }));
    }
    @Test void onlyTheFourSupportAbilitiesUseExternalTargetSelection() {
        var support = EnumSet.of(BranchAbility.FAITH_SHIELD, BranchAbility.PURGE,
                BranchAbility.HEALING_SHIELD, BranchAbility.GRACE);
        for (var ability : BranchAbility.values())
            assertEquals(support.contains(ability), BranchAbilities.isSupport(ability), ability.name());
        assertFalse(BranchAbilities.isSupport(null));
    }
}
