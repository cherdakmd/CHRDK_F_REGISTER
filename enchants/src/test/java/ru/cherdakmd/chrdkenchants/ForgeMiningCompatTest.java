package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.2: «Карьер» CHRDK_TierForge и чары массовой добычи несовместимы.
 * Bukkit-PDC здесь не нужен — проверяем чистые правила, чтение чужого ключа
 * остаётся тонким адаптером в {@link ForgeMiningCompat}.
 */
final class ForgeMiningCompatTest {

    @Test
    void onlyMegaMiningGroupTriggersTheBan() {
        assertTrue(ForgeMiningCompat.isMegaMining("veinminer"));
        assertTrue(ForgeMiningCompat.isMegaMining("excavator"));
        assertFalse(ForgeMiningCompat.isMegaMining("midas"));
        assertFalse(ForgeMiningCompat.isMegaMining("autosmelt"));
        assertFalse(ForgeMiningCompat.isMegaMining("scholar"));
        assertFalse(ForgeMiningCompat.isMegaMining("legacy_unknown"));
        assertFalse(ForgeMiningCompat.isMegaMining(null));
        assertEquals("megamine", EnchantConflicts.group("veinminer"));
        assertEquals("megamine", EnchantConflicts.group("excavator"));
    }

    @Test
    void bothColumnSourcesAreDeclared() {
        assertEquals("quarry", ForgeMiningCompat.QUARRY);
        assertEquals("hewer", ForgeMiningCompat.HEWER);
        assertEquals("megamine", ForgeMiningCompat.MEGA_MINING_GROUP);
        // up_hewer лежит не в строке свойств тира, а в отдельном PDC-ключе.
        assertFalse(ForgeMiningCompat.quarryPresent(
                ForgeMiningCompat.parseProperties("up_hewer")));
    }

    @Test
    void quarryIsFoundInSerializedTierProperties() {
        assertTrue(ForgeMiningCompat.quarryPresent(
                ForgeMiningCompat.parseProperties("world_breaker,quarry")));
        assertTrue(ForgeMiningCompat.quarryPresent(
                ForgeMiningCompat.parseProperties(" motherlode ; QUARRY , eternal ")));
        assertFalse(ForgeMiningCompat.quarryPresent(
                ForgeMiningCompat.parseProperties("world_breaker,motherlode")));
        assertTrue(ForgeMiningCompat.parseProperties(null).isEmpty());
        assertTrue(ForgeMiningCompat.parseProperties("  ").isEmpty());
        assertFalse(ForgeMiningCompat.quarryPresent(null));
        assertFalse(ForgeMiningCompat.quarryPresent(List.of()));
    }

    @Test
    void parserIsCaseInsensitiveDeduplicatesAndKeepsOrder() {
        assertEquals(List.of("quarry", "world_breaker"),
                ForgeMiningCompat.parseProperties("QUARRY;quarry world_breaker"));
    }

    @Test
    void blockedRejectsMegaMiningOnlyWhenQuarryIsPresent() {
        assertTrue(ForgeMiningCompat.blocked(true, "excavator"));
        assertTrue(ForgeMiningCompat.blocked(true, "veinminer"));
        assertFalse(ForgeMiningCompat.blocked(false, "excavator"));
        assertFalse(ForgeMiningCompat.blocked(true, "midas"));
        assertFalse(ForgeMiningCompat.blocked(true, (String) null));

        Map<String, Integer> book = Map.of("excavator", 2, "autosmelt", 1);
        assertTrue(ForgeMiningCompat.blocked(true, book));
        assertFalse(ForgeMiningCompat.blocked(false, book));
        // Другие чары на таком предмете накладывать можно: проверяется
        // именно переносимый набор, а не весь уже стоящий на инструменте.
        assertFalse(ForgeMiningCompat.blocked(true, Map.of("autosmelt", 1)));
        assertFalse(ForgeMiningCompat.blocked(true, Map.of()));
        assertFalse(ForgeMiningCompat.blocked(true, (Map<String, Integer>) null));
    }

    @Test
    void stripRemovesBothMegaMiningIdsAndKeepsOtherEnchantsInOrder() {
        Map<String, Integer> source = new LinkedHashMap<>();
        source.put("veinminer", 3);
        source.put("autosmelt", 1);
        source.put("excavator", 2);
        source.put("midas", 1);

        Map<String, Integer> cleaned = ForgeMiningCompat.stripMegaMining(source, true);
        assertEquals(List.of("autosmelt", "midas"), new ArrayList<>(cleaned.keySet()));
        assertEquals(Map.of("autosmelt", 1, "midas", 1), cleaned);
        assertTrue(EnchantConflicts.validSet(cleaned));

        assertEquals(source, ForgeMiningCompat.stripMegaMining(source, false));
        assertTrue(ForgeMiningCompat.stripMegaMining(null, true).isEmpty());
    }
}
