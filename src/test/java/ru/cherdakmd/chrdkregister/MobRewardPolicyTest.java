package ru.cherdakmd.chrdkregister;

import java.lang.reflect.Proxy;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.MobRewardPolicy;
import static org.junit.jupiter.api.Assertions.*;

class MobRewardPolicyTest {
    private Entity entity(Set<NamespacedKey> keys) {
        var data = (PersistentDataContainer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PersistentDataContainer.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getKeys" -> keys;
            case "has" -> keys.contains(args[0]);
            default -> null;
        });
        return (Entity) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Entity.class}, (proxy, method, args) -> method.getName().equals("getPersistentDataContainer") ? data : null);
    }
    @Test void onlyOwnedTemporaryMarkerBlocksRewardsIndependentlyOfHuntLifecycle() {
        assertFalse(MobRewardPolicy.temporary(null)); assertFalse(MobRewardPolicy.temporary(entity(Set.of())));
        assertTrue(MobRewardPolicy.temporary(entity(Set.of(new NamespacedKey("chrdk_hunt", "full_moon")))));
        assertFalse(MobRewardPolicy.temporary(entity(Set.of(new NamespacedKey("other", "full_moon")))));
    }
    @Test void classesProvenanceIsNotAnEliteOrForeignStatModifier() {
        assertTrue(MobRewardPolicy.onlyNaturalProvenance(entity(Set.of())));
        assertTrue(MobRewardPolicy.onlyNaturalProvenance(entity(Set.of(new NamespacedKey("chrdk_classes", "natural-v1")))));
        assertFalse(MobRewardPolicy.onlyNaturalProvenance(entity(Set.of(new NamespacedKey("chrdk_hunt", "wild_variant")))));
        assertFalse(MobRewardPolicy.onlyNaturalProvenance(entity(Set.of(new NamespacedKey("other", "level")))));
        assertFalse(MobRewardPolicy.onlyNaturalProvenance(entity(Set.of(MobRewardPolicy.TEMPORARY))));
    }
    @Test void namedBiomeVarietyIsNotABossOrAnInvasionMob() {
        Entity wild = entity(Set.of(new NamespacedKey("chrdk_hunt", "wild_variant")));
        assertTrue(MobRewardPolicy.wild(wild)); assertFalse(MobRewardPolicy.temporary(wild));
        assertFalse(MobRewardPolicy.wild(entity(Set.of())));
    }
}
