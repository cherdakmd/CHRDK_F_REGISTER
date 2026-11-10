package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Баланс-аудит престижа: согласованность потолков в разных модулях, конечная
 * экономика наград и честная фиксация насыщения дропа.
 *
 * <p>Тест намеренно не «подгоняет» числа под код: он проверяет инварианты,
 * которые обязаны сохраняться при любой крутилке {@code config.yml}. Главный
 * вывод зафиксирован в {@code docs/design/PRESTIGE.md}: на потолке уровня и
 * ранга {@code max-amount} рубит прибавку престижа, поэтому ветеран получает
 * силу через XP и перки, а не через бесконечный дроп.</p>
 */
class PrestigeBalanceTest {

    @Test
    void capsAreConsistentAcrossMathStoreAndDrops() {
        assertEquals(SkillType.values().length, PrestigeMath.SKILLS);
        assertEquals(PrestigeMath.SKILLS * PrestigeMath.MAX_PER_SKILL,
                PrestigeMath.MAX_TOTAL);
        assertEquals(PrestigeMath.MAX_PER_SKILL, DiamondDrops.MAX_PRESTIGE,
                "ступени в хранилище и в формуле дропа не должны расходиться");
        assertTrue(DiamondDrops.Settings.DEFAULT.maxAmount()
                <= DiamondDrops.MAX_AMOUNT_LIMIT);
        assertTrue(DiamondDrops.Settings.DEFAULT.maxChance()
                <= DiamondDrops.MAX_CHANCE_LIMIT);
    }

    @Test
    void everySkillHasItsOwnPrestigePerk() {
        for (SkillType skill : SkillType.values()) {
            SkillPerk.Kind kind = PrestigeMath.perkKind(skill);
            assertNotNull(kind, "у навыка " + skill.id() + " нет профильного перка");
            assertTrue(PrestigeMath.perkValue(skill, 0, 0.01) == 0.0);
            assertTrue(PrestigeMath.perkValue(skill, 10, 0.01) > 0.0,
                    "престиж " + skill.id() + " ничего не усиливает");
        }
    }

    @Test
    void tiersAreStrictlyOrderedAndRewardsGrow() {
        int previousThreshold = -1;
        int previousDiamonds = -1;
        for (PrestigeMath.Tier tier : PrestigeMath.TIERS) {
            assertTrue(tier.threshold() > previousThreshold,
                    "пороги званий должны строго расти");
            previousThreshold = tier.threshold();
            if (tier.threshold() > 0) {
                assertTrue(PrestigeMath.tierDiamonds(tier) > previousDiamonds,
                        "награда за каждый следующий порог выше");
                previousDiamonds = PrestigeMath.tierDiamonds(tier);
            }
        }
        assertEquals(PrestigeMath.MAX_TOTAL, previousThreshold,
                "последнее звание — ровно потолок сервера");
    }

    @Test
    void progressStaysInBoundsForEveryPossibleTotal() {
        PrestigeMath.Tier previous = PrestigeMath.tierFor(0);
        for (int total = 0; total <= PrestigeMath.MAX_TOTAL; total++) {
            int percent = PrestigeMath.progressPercent(total);
            assertTrue(percent >= 0 && percent <= 100,
                    "прогресс вышел за границы на уровне " + total);
            PrestigeMath.Tier current = PrestigeMath.tierFor(total);
            assertTrue(current.threshold() >= previous.threshold(),
                    "звание не должно откатываться");
            previous = current;
        }
        assertEquals("Миф сервера",
                PrestigeMath.tierFor(PrestigeMath.MAX_TOTAL).title());
    }

    @Test
    void lifetimeDiamondRewardsAreFiniteAndBounded() {
        int perStep = PrestigeMath.stepDiamonds(4); // дефолт config.yml
        int steps = PrestigeMath.MAX_TOTAL * perStep;
        int tiers = PrestigeMath.TIERS.stream()
                .mapToInt(PrestigeMath::tierDiamonds).sum();
        assertEquals(400, steps, "100 ступеней по 4 алмаза");
        assertEquals(248, tiers, "8 + 16 + 32 + 64 + 128");
        assertEquals(648, steps + tiers, "≈10 стаков на игрока за всё время");
        assertTrue(steps + tiers <= 1000,
                "разовые награды конечны и не разгоняют экономику");
        assertTrue(PrestigeMath.stepDiamonds(Integer.MAX_VALUE)
                * PrestigeMath.MAX_TOTAL
                + PrestigeMath.TIERS.stream()
                        .mapToInt(t -> PrestigeMath.MAX_TIER_DIAMONDS).sum()
                <= PrestigeMath.MAX_TOTAL * PrestigeMath.MAX_STEP_DIAMONDS
                        + PrestigeMath.TIERS.size() * PrestigeMath.MAX_TIER_DIAMONDS,
                "даже максимальный конфиг упирается в константы");
    }

    @Test
    void configClampsCannotBreakHardCeilings() {
        // Верхние границы самого config.yml: xp-per-step ≤ 0.10,
        // perk-per-step ≤ 0.05, diamonds-per-step ≤ 32.
        assertTrue(PrestigeMath.xpFactor(10, 0.10) <= PrestigeMath.MAX_XP_FACTOR + 1e-9);
        assertEquals(PrestigeMath.MAX_PERCENT_PERK,
                PrestigeMath.perkValue(SkillType.MINING, 10, 0.05), 1e-9);
        assertEquals(PrestigeMath.MAX_ABSOLUTE_PERK,
                PrestigeMath.perkValue(SkillType.DEFENSE, 10, 0.05), 1e-9);
        assertEquals(PrestigeMath.MAX_STEP_DIAMONDS,
                PrestigeMath.stepDiamonds(Integer.MAX_VALUE));
        assertEquals(PrestigeMath.MAX_TIER_DIAMONDS,
                PrestigeMath.tierDiamonds(
                        new PrestigeMath.Tier(1, "x", "x", Integer.MAX_VALUE)));
    }

    @Test
    void dropBonusWorksWithHeadroomAndSaturatesAtTheCap() {
        DiamondDrops.Settings settings = DiamondDrops.Settings.DEFAULT;
        // Низкий уровень, нет ранга: запас до max-chance/max-amount есть,
        // поэтому ступени престижа реально усиливают бросок.
        DiamondDrops.Result fresh = DiamondDrops.roll(
                1, 100, 0, 0, 0, 0, 0, false, 0.0, settings);
        DiamondDrops.Result veteran = DiamondDrops.roll(
                1, 100, 10, 0, 0, 0, 0, false, 0.0, settings);
        assertTrue(veteran.chance() > fresh.chance(),
                "престиж должен поднимать шанс там, где есть запас");
        assertTrue(veteran.amount() >= fresh.amount());

        // Потолок уровня + максимальный ранг: количество упирается в
        // max-amount, шанс — в max-chance. Это известное насыщение, а не баг:
        // бесконечного дропа из престижа быть не должно.
        DiamondDrops.Result maxed = DiamondDrops.roll(
                100, 100, PrestigeMath.MAX_PER_SKILL, 5, 50, 25,
                0, false, 0.0, settings);
        assertEquals(settings.maxAmount(), maxed.amount());
        assertTrue(maxed.chance() <= settings.maxChance() + 1e-9);
        assertTrue(maxed.chance() <= DiamondDrops.MAX_CHANCE_LIMIT);
        assertEquals(settings.maxAmount(), DiamondDrops.roll(
                100, 100, 0, 5, 50, 25, 0, false, 0.0, settings).amount(),
                "на потолке престиж уже не меняет количество — см. документ");
    }
}
