package ru.cherdakmd.chrdkhunt;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модель бестиария (1.4.159): разделы, порядок видов, награды, прогресс и
 * границы, за которыми раздел становится недостижимым.
 */
class HuntBestiaryTest {

    private static Map<String, HuntFamily> families() {
        Map<String, HuntFamily> families = new LinkedHashMap<>();
        families.put("undead", new HuntFamily("undead", "Нежить", "Осколок кости",
                "Ключ склепа", 1.0, Material.BONE, Material.TRIAL_KEY, null));
        families.put("beasts", new HuntFamily("beasts", "Твари", "Осколок клыка",
                "Ключ логова", 1.0, Material.PHANTOM_MEMBRANE, Material.TRIAL_KEY, null));
        families.put("empty", new HuntFamily("empty", "Пустошь", "Осколок пустоши",
                "Ключ пустоши", 1.0, Material.BONE, Material.TRIAL_KEY, null));
        return families;
    }

    private static MobRule rule(EntityType type, String family, boolean boss) {
        return new MobRule(type, family, 3, 6, 20, 1, 1, 1.5, boss, List.of());
    }

    private static HuntBestiary bestiary() {
        List<MobRule> rules = List.of(
                rule(EntityType.ZOMBIE, "undead", false),
                rule(EntityType.SKELETON, "undead", false),
                rule(EntityType.CREEPER, "beasts", false),
                rule(EntityType.WITHER, "beasts", true));
        Map<String, HuntBestiary.Reward> rewards = new LinkedHashMap<>();
        rewards.put("undead", new HuntBestiary.Reward(150, 10, 1));
        rewards.put("beasts", new HuntBestiary.Reward(0, 0, 0));
        rewards.put("empty", new HuntBestiary.Reward(999, 99, 9));
        return HuntBestiary.of(rules, families(), rewards, Map.of(), 1500);
    }

    @Test
    void emptyBestiaryHasNoSections() {
        HuntBestiary empty = HuntBestiary.none();
        assertTrue(empty.empty());
        assertEquals(0, empty.sectionCount());
        assertEquals(0, empty.totalEntries());
        assertEquals(0, empty.fullCoins());
        assertNull(empty.section("undead"));
        assertNull(empty.entry("zombie"));
        assertNull(empty.entryFor(EntityType.ZOMBIE));
        assertFalse(empty.fullyComplete(Map.of()));
        assertTrue(HuntBestiary.of(List.of(), families(), Map.of(), Map.of(), 10).empty());
        assertTrue(HuntBestiary.of(null, null, null, null, 10).empty());
    }

    @Test
    void sectionsFollowFamiliesAndSkipEmptyOnes() {
        HuntBestiary bestiary = bestiary();
        assertEquals(2, bestiary.sectionCount(), "семейство без видов в журнал не попадает");
        assertEquals(List.of("undead", "beasts"),
                bestiary.sections().stream().map(HuntBestiary.Section::id).toList());
        assertEquals(4, bestiary.totalEntries());

        HuntBestiary.Section undead = bestiary.section("UNDEAD");
        assertNotNull(undead);
        assertEquals("Нежить", undead.displayName());
        assertEquals(List.of("zombie", "skeleton"),
                undead.entries().stream().map(HuntBestiary.Entry::key).toList());
        assertTrue(undead.rewarded());
        assertEquals(150, undead.reward().coins());

        HuntBestiary.Section beasts = bestiary.section("beasts");
        assertNotNull(beasts);
        assertFalse(beasts.rewarded(), "нулевая награда = без подарка");
        assertTrue(beasts.entries().get(1).boss());
        assertNull(bestiary.section("empty"));
        assertNull(bestiary.section(null));
        assertNull(bestiary.section(""));
    }

    @Test
    void entriesAreFoundByKeyAndType() {
        HuntBestiary bestiary = bestiary();
        HuntBestiary.Entry zombie = bestiary.entry("ZOMBIE");
        assertNotNull(zombie);
        assertEquals(EntityType.ZOMBIE, zombie.type());
        assertEquals("undead", zombie.familyId());
        assertFalse(zombie.boss());
        assertEquals(Material.ZOMBIE_HEAD, zombie.icon(), "голова моба — иконка по умолчанию");
        assertEquals("<lang:entity.minecraft.zombie>", zombie.translationTag());

        assertEquals(zombie, bestiary.entryFor(EntityType.ZOMBIE));
        assertNull(bestiary.entryFor(EntityType.COW), "моб вне охоты в журнал не входит");
        assertNull(bestiary.entry("cow"));
        assertNull(bestiary.entry(""));
        assertNull(bestiary.entryFor(null));
        assertEquals(4, bestiary.entries().size());
    }

    @Test
    void iconOverrideAndFallbackWork() {
        Map<EntityType, Material> icons = Map.of(EntityType.ZOMBIE, Material.ROTTEN_FLESH);
        HuntBestiary bestiary = HuntBestiary.of(
                List.of(rule(EntityType.ZOMBIE, "undead", false),
                        rule(EntityType.ENDER_DRAGON, "undead", true)),
                families(), Map.of(), icons, 0);
        assertEquals(Material.ROTTEN_FLESH, bestiary.entry("zombie").icon());
        assertEquals(Material.PAPER, bestiary.entry("ender_dragon").icon(),
                "ни головы, ни яйца призыва у дракона нет — бумага");
        assertEquals(Material.PAPER, HuntBestiary.defaultIcon(null));
        assertEquals(Material.PLAYER_HEAD, HuntBestiary.defaultIcon(EntityType.PLAYER));
    }

    @Test
    void progressCountsSeenEntries() {
        HuntBestiary bestiary = bestiary();
        HuntBestiary.Section undead = bestiary.section("undead");
        Map<String, Long> journal = Map.of("zombie", 12L, "cow", 4L);

        HuntBestiary.Progress first = bestiary.progress(undead, journal);
        assertEquals(1, first.seen());
        assertEquals(2, first.total());
        assertEquals(50, first.percent());
        assertFalse(first.complete());

        HuntBestiary.Progress done = bestiary.progress(undead,
                Map.of("zombie", 1L, "skeleton", 1L));
        assertTrue(done.complete());
        assertEquals(100, done.percent());

        assertEquals(3, bestiary.seenTotal(Map.of("zombie", 1L, "skeleton", 1L,
                "creeper", 2L)));
        assertFalse(bestiary.fullyComplete(Map.of("zombie", 1L, "skeleton", 1L)));
        assertTrue(bestiary.fullyComplete(Map.of("zombie", 1L, "skeleton", 1L,
                "creeper", 1L, "wither", 1L)));
        assertFalse(bestiary.fullyComplete(null));

        HuntBestiary.Progress unknown = bestiary.progress(null, Map.of());
        assertEquals("", unknown.sectionId());
        assertEquals(0, unknown.total());
        assertEquals(100, unknown.percent(), "пустой раздел считается закрытым");
    }

    @Test
    void entriesBeyondTheMenuAreDropped() {
        List<MobRule> rules = new ArrayList<>();
        for (EntityType type : List.of(EntityType.ZOMBIE, EntityType.SKELETON,
                EntityType.CREEPER, EntityType.SPIDER, EntityType.WITCH,
                EntityType.SLIME, EntityType.PHANTOM, EntityType.BLAZE,
                EntityType.GHAST, EntityType.ZOMBIFIED_PIGLIN, EntityType.ENDERMAN,
                EntityType.SHULKER, EntityType.GUARDIAN, EntityType.RAVAGER,
                EntityType.STRAY, EntityType.HUSK, EntityType.DROWNED,
                EntityType.BOGGED, EntityType.PIGLIN, EntityType.HOGLIN,
                EntityType.ZOGLIN, EntityType.MAGMA_CUBE, EntityType.WITHER_SKELETON,
                EntityType.PIGLIN_BRUTE, EntityType.SILVERFISH, EntityType.CAVE_SPIDER,
                EntityType.ENDERMITE, EntityType.EVOKER, EntityType.VINDICATOR,
                EntityType.PILLAGER, EntityType.VEX, EntityType.ILLUSIONER,
                EntityType.WARDEN, EntityType.ELDER_GUARDIAN, EntityType.WITHER,
                EntityType.ENDER_DRAGON, EntityType.ZOMBIE_VILLAGER)) {
            rules.add(rule(type, "undead", false));
        }
        HuntBestiary bestiary = HuntBestiary.of(rules, families(), Map.of(), Map.of(), 0);
        assertEquals(1, bestiary.sectionCount());
        assertEquals(HuntBestiary.MAX_ENTRIES_PER_SECTION,
                bestiary.section("undead").size(), "лишние виды не помещаются в меню");
        assertEquals(HuntBestiary.MAX_ENTRIES_PER_SECTION, bestiary.totalEntries());
    }

    @Test
    void keysAndRewardsAreClamped() {
        assertEquals("zombie", HuntBestiary.keyOf(EntityType.ZOMBIE));
        assertEquals("wither_skeleton", HuntBestiary.keyOf(EntityType.WITHER_SKELETON));
        assertEquals("", HuntBestiary.keyOf(null));
        assertEquals("entity.minecraft.zombie", HuntBestiary.translationKey(EntityType.ZOMBIE));

        HuntBestiary.Reward huge = new HuntBestiary.Reward(-5, 50_000, 500);
        assertEquals(0, huge.coins());
        assertEquals(HuntBestiary.Reward.NONE, new HuntBestiary.Reward(0, 0, 0));
        assertFalse(new HuntBestiary.Reward(1, 0, 0).empty());
        assertTrue(HuntBestiary.Reward.NONE.empty());

        HuntBestiary.Section section = new HuntBestiary.Section("UNDEAD", "", null, null);
        assertEquals("undead", section.id());
        assertEquals("undead", section.displayName(), "пустое имя заменяется идом");
        assertEquals(0, section.size());
        assertFalse(section.usable());
        assertFalse(section.rewarded());
    }
}
