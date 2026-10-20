package ru.cherdakmd.chrdktierforge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Id модификаторов предмета. До 1.4.124 все детали брони носили один
 * {@code tier_movespeed}, и Minecraft применял только последний модификатор —
 * полный сет не давал ни скорости, ни защиты. Теперь у каждого предмета свой
 * суффикс, и ключи обязаны быть уникальными и стабильными.
 */
class TierModifierIdsTest {

    @Test
    void keysGetStableUniqueSuffix() {
        Random random = new Random(42L);
        Set<String> uids = new HashSet<>();
        for (int index = 0; index < 200; index++) {
            String uid = TierModifierIds.newUid(random);
            assertTrue(TierModifierIds.validUid(uid), "сгенерированный uid валиден: " + uid);
            uids.add(uid);
        }
        assertEquals(200, uids.size(), "uid не повторяются на разумной выборке");
        String uid = TierModifierIds.newUid(new Random(7L));
        assertEquals("tier_movespeed_" + uid, TierModifierIds.key("tier_movespeed", uid));
    }

    @Test
    void legacyKeysStayRecognizable() {
        assertEquals("tier_attack", TierModifierIds.key("tier_attack", null));
        assertEquals("tier_attack", TierModifierIds.key("tier_attack", ""));
        assertTrue(TierModifierIds.ours("tier_armor_3f9a1c0b7d2e"));
        assertTrue(TierModifierIds.ours("tier_armor"), "старый ключ тоже наш");
        assertTrue(TierModifierIds.ours("fu_edge_3f9a1c0b7d2e"));
        assertTrue(TierModifierIds.ours("pr_soul_echo"));
        assertFalse(TierModifierIds.ours("minecraft:generic.attack_damage"));
        assertFalse(TierModifierIds.ours("other_plugin_modifier"));
    }

    @Test
    void stampReflectsAffixAndEnchantSwitches() {
        String defaults = TierModifierIds.stamp(true, 2, 2, true);
        assertEquals("2-a22-e", defaults);
        assertEquals("2-a22-E", TierModifierIds.stamp(true, 2, 2, false),
                "выключенные зачарования — другой отпечаток, иначе старые предметы"
                        + " не пересоберутся");
        assertEquals("2-A-e", TierModifierIds.stamp(false, 2, 2, true));
        assertFalse(defaults.equals(TierModifierIds.stamp(true, 3, 2, true)),
                "новый потолок аффиксов запускает пересборку");
        assertFalse(defaults.equals(TierModifierIds.stamp(true, 2, 1, true)));
        assertTrue(defaults.startsWith(TierModifierIds.STAMP), "версия раскладки впереди");
    }

    @Test
    void invalidUidIsRegenerated() {
        assertFalse(TierModifierIds.validUid(null));
        assertFalse(TierModifierIds.validUid(""));
        assertFalse(TierModifierIds.validUid("3F9A1C0B7D2E"), "только нижний hex");
        assertFalse(TierModifierIds.validUid("3f9a1c0b7d2"), "короткий");
        assertFalse(TierModifierIds.validUid("3f9a1c0b7d2e0"), "длинный");
        assertTrue(TierModifierIds.validUid("0123456789ab"));
    }
}
