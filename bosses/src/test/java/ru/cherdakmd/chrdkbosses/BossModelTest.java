package ru.cherdakmd.chrdkbosses;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модель боссов (1.4.162): клампы, фазы, арены и награды.
 *
 * <p>Здесь проверяется то, чему нельзя дать уехать в конфиг: босс не должен
 * получать отрицательное здоровье, фаза не может начаться выше предыдущей, а
 * зона — отнимать последние сердца.</p>
 */
class BossModelTest {

    /* ------------------------------- способности ------------------------------ */

    @Test
    void abilityClampsItsParameters() {
        BossAbility ability = new BossAbility(BossAbility.Type.STOMP, 1, 1_000, 0,
                null, null, 99, 999);
        assertEquals(3, ability.cooldownSeconds(), "минимальная пауза — 3 секунды");
        assertEquals(BossAbility.MAX_RADIUS, ability.radius(), 1.0e-9);
        assertEquals(0.0, ability.amount(), 1.0e-9);
        assertEquals(EntityType.ZOMBIE, ability.entity(), "по умолчанию свита — зомби");
        assertEquals("", ability.effect());
        assertEquals(BossAbility.MAX_AMPLIFIER, ability.amplifier());
        assertEquals(60, ability.durationSeconds());
        assertFalse(ability.usable(), "удар без урона бесполезен");
    }

    @Test
    void summonCountIsCapped() {
        BossAbility ability = new BossAbility(BossAbility.Type.SUMMON, 30, 5, 40,
                EntityType.SKELETON, "", 0, 0);
        assertEquals(BossAbility.MAX_SUMMONS, ability.summons());
        assertTrue(ability.usable());
        assertTrue(ability.summary().contains("skeleton"));
    }

    @Test
    void healNeverGoesAboveHalf() {
        BossAbility heal = new BossAbility(BossAbility.Type.HEAL, 60, 5, 99, null, "", 0, 0);
        assertEquals(50, heal.healPercent());
        assertTrue(heal.usable());
    }

    @Test
    void curseNeedsEffectAndDuration() {
        BossAbility empty = new BossAbility(BossAbility.Type.CURSE, 20, 8, 0, null, "", 0, 0);
        assertFalse(empty.usable(), "проклятие без эффекта ничего не делает");
        BossAbility curse = new BossAbility(BossAbility.Type.CURSE, 20, 8, 0,
                null, "Slowness", 2, 5);
        assertTrue(curse.usable());
        assertEquals("slowness", curse.effect());
        assertTrue(curse.summary().contains("slowness"));
    }

    /* ---------------------------------- фазы ---------------------------------- */

    @Test
    void emptyPhasesFallBackToTwo() {
        List<BossPhase> phases = BossPhase.normalize(List.of());
        assertEquals(2, phases.size());
        assertEquals(100, phases.get(0).fromPercent());
        assertEquals(50, phases.get(1).fromPercent());
    }

    @Test
    void phasesAreSortedAndStrictlyDescending() {
        List<BossPhase> phases = BossPhase.normalize(List.of(
                new BossPhase(40, "Третья", 1.5, 1.0, List.of()),
                new BossPhase(100, "Первая", 1.0, 1.0, List.of()),
                new BossPhase(70, "Вторая", 1.25, 1.1, List.of())));
        assertEquals(List.of(100, 70, 40),
                phases.stream().map(BossPhase::fromPercent).toList());
        assertEquals("Первая", phases.get(0).name());
        assertEquals(40, phases.get(2).fromPercent());
    }

    @Test
    void equalThresholdsDoNotCollide() {
        List<BossPhase> phases = BossPhase.normalize(List.of(
                new BossPhase(100, "А", 1.0, 1.0, List.of()),
                new BossPhase(100, "Б", 1.0, 1.0, List.of())));
        assertEquals(2, phases.size());
        assertEquals(99, phases.get(1).fromPercent(), "порог второй фазы сдвигается вниз");
    }

    @Test
    void phaseCountAndMultipliersAreClamped() {
        List<BossPhase> many = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            many.add(new BossPhase(100 - index * 5, "Ф" + index, 9.0, 0.1, List.of()));
        }
        List<BossPhase> phases = BossPhase.normalize(many);
        assertEquals(BossPhase.MAX_PHASES, phases.size());
        assertEquals(3.0, phases.get(0).damageMultiplier(), 1.0e-9);
        assertEquals(0.5, phases.get(0).speedMultiplier(), 1.0e-9);
    }

    @Test
    void abilitiesAreFilteredAndCapped() {
        List<BossAbility> abilities = new ArrayList<>();
        abilities.add(new BossAbility(BossAbility.Type.STOMP, 10, 5, 0, null, "", 0, 0));
        for (int index = 0; index < 8; index++) {
            abilities.add(new BossAbility(BossAbility.Type.PULL, 10, 5, 0, null, "", 0, 0));
        }
        BossPhase phase = new BossPhase(100, "Тест", 1.0, 1.0, abilities);
        assertEquals(BossPhase.MAX_ABILITIES, phase.abilities().size());
        assertTrue(phase.hasAbilities());
        assertTrue(phase.abilities().stream()
                .allMatch(ability -> ability.type() == BossAbility.Type.PULL));
    }

    /* --------------------------------- арены ---------------------------------- */

    @Test
    void arenaClampsRadiusAndUnderstandsAutoHeight() {
        Arena arena = new Arena(" world ", 0, -1, 0, 1);
        assertEquals("world", arena.world());
        assertEquals(4.0, arena.radius(), 1.0e-9);
        assertTrue(arena.autoHeight());
        assertTrue(arena.usable());
        Arena wide = new Arena("world", 0, 64, 0, 1_000);
        assertEquals(Arena.MAX_RADIUS, wide.radius(), 1.0e-9);
        assertFalse(wide.autoHeight());
    }

    @Test
    void arenaChecksWorldRadiusAndHeight() {
        Arena arena = new Arena("world", 0, 64, 0, 10);
        assertTrue(arena.contains("world", 5, 64, 0));
        assertFalse(arena.contains("world", 20, 64, 0), "вне радиуса");
        assertFalse(arena.contains("nether", 0, 64, 0), "другой мир");
        assertFalse(arena.contains("world", 0, 200, 0), "слишком высоко");
        assertFalse(arena.contains(null, 0, 64, 0));
        assertFalse(new Arena("", 0, 64, 0, 10).usable(), "без мира арены нет");
    }

    /* ---------------------------------- зоны ---------------------------------- */

    @Test
    void zoneClampsEverything() {
        DangerZone zone = new DangerZone(" ash ", "", "", true, "world", 0, 64, 0, 1,
                9, 100, 100, 100, List.of(), Material.AIR);
        assertEquals("ash", zone.id());
        assertEquals("ash", zone.name(), "без имени берётся ид");
        assertEquals(4.0, zone.radius(), 1.0e-9);
        assertEquals(5, zone.level());
        assertEquals(DangerZone.MAX_DAMAGE_PER_SECOND, zone.damagePerSecond(), 1.0e-9);
        assertEquals(DangerZone.MAX_XP_BONUS_PERCENT, zone.xpBonusPercent());
        assertEquals(60, zone.effectIntervalSeconds());
        assertEquals(Material.MAGMA_BLOCK, zone.icon());
        assertFalse(zone.harmful());
    }

    @Test
    void zoneContainsOnlyItsOwnWorldAndEnabledState() {
        DangerZone zone = new DangerZone("ash", "Пепел", "", true, "world",
                0, 64, 0, 10, 2, 1.0, 10, 5, List.of(), Material.MAGMA_BLOCK);
        assertTrue(zone.contains("world", 0, 64, 0));
        assertTrue(zone.contains("world", 3, 68, 3));
        assertFalse(zone.contains("world", 30, 64, 0));
        assertFalse(new DangerZone("ash", "Пепел", "", false, "world", 0, 64, 0, 10,
                2, 1.0, 10, 5, List.of(), Material.MAGMA_BLOCK)
                .contains("world", 0, 64, 0), "выключенная зона не действует");
        assertTrue(zone.harmful());
        assertEquals("Опасность II", zone.levelLabel());
    }

    @Test
    void romanLevelsStayInRange() {
        assertEquals("I", DangerZone.roman(0));
        assertEquals("III", DangerZone.roman(3));
        assertEquals("V", DangerZone.roman(9));
    }

    @Test
    void zoneEffectClampsAndFilters() {
        ZoneEffect effect = new ZoneEffect(" Slowness ", 9, 0);
        assertEquals("slowness", effect.effect());
        assertEquals(ZoneEffect.MAX_AMPLIFIER, effect.amplifier());
        assertEquals(1, effect.durationSeconds());
        assertTrue(effect.usable());
        assertFalse(new ZoneEffect("  ", 0, 5).usable());
    }

    /* --------------------------------- боссы ---------------------------------- */

    @Test
    void bossClampsHealthAndTimers() {
        BossDefinition weak = new BossDefinition("Weak", "", "", EntityType.ZOMBIE,
                1.0, Material.AIR, true, new Arena("world", 0, 64, 0, 10),
                List.of(), BossReward.none(), 1, 1, 0, true);
        assertEquals("weak", weak.id());
        assertEquals(BossDefinition.MIN_HEALTH, weak.health(), 1.0e-9);
        assertEquals(BossDefinition.MIN_INTERVAL_SECONDS, weak.spawnIntervalSeconds());
        assertEquals(BossDefinition.MIN_DURATION_SECONDS, weak.durationSeconds());
        assertEquals(1, weak.minPlayers());
        assertEquals(Material.NETHERITE_SWORD, weak.icon());
        assertEquals(2, weak.phases().size(), "без фаз появляются две умолчания");
        assertFalse(weak.usable(), "без награды босс не выпускается");

        BossDefinition strong = new BossDefinition("strong", "Гроза", "", EntityType.WITHER,
                9_999.0, Material.NETHERITE_AXE, true, new Arena("world", 0, 64, 0, 10),
                BossPhase.normalize(List.of()),
                new BossReward(100, List.of(), Material.AIR, ""),
                999_999, 999_999, 500, true);
        assertEquals(BossDefinition.MAX_HEALTH, strong.health(), 1.0e-9);
        assertEquals(BossDefinition.MAX_INTERVAL_SECONDS, strong.spawnIntervalSeconds());
        assertEquals(BossDefinition.MAX_DURATION_SECONDS, strong.durationSeconds());
        assertEquals(100, strong.minPlayers());
        assertTrue(strong.usable());
    }

    @Test
    void bossPicksPhaseByHealth() {
        BossDefinition boss = new BossDefinition("ash", "Пепел", "", EntityType.BLAZE, 200,
                Material.MAGMA_BLOCK, true, new Arena("world", 0, 64, 0, 10),
                BossPhase.normalize(List.of(
                        new BossPhase(100, "Первая", 1.0, 1.0, List.of()),
                        new BossPhase(60, "Вторая", 1.3, 1.1, List.of()),
                        new BossPhase(30, "Третья", 1.6, 1.2, List.of()))),
                new BossReward(100, List.of(), Material.AIR, ""),
                1_800, 1_800, 2, true);
        assertEquals("Первая", boss.phase(200).name());
        assertEquals("Вторая", boss.phase(120).name());
        assertEquals("Третья", boss.phase(40).name());
        assertEquals(0, boss.phaseIndex(200));
        assertEquals(2, boss.phaseIndex(1));
        assertEquals("Пепел · blaze · 200 hp", boss.label());
    }

    /* -------------------------------- награды --------------------------------- */

    @Test
    void rewardClampsXpAndItems() {
        BossReward reward = new BossReward(999_999,
                List.of(new BossReward.Item(Material.GOLDEN_APPLE, 0),
                        new BossReward.Item(Material.AIR, 10)),
                Material.DIAMOND, "  Сердце  ");
        assertEquals(BossReward.MAX_XP, reward.xp());
        assertEquals(1, reward.items().size(), "воздух в награду не попадает");
        assertEquals(1, reward.items().get(0).amount());
        assertEquals("Сердце", reward.trophyName());
        assertTrue(reward.hasTrophy());
        assertFalse(reward.empty());
        assertTrue(reward.summary().contains("50000 xp"));

        BossReward none = BossReward.none();
        assertTrue(none.empty());
        assertFalse(none.hasTrophy());
        assertEquals("", none.summary());
    }
}
