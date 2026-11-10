package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Арифметика потолка наковальни: оплачивается вся цена, а не только превышение. */
class AnvilSettingsTest {
    @Test void defaultsMatchTheAgreedOneHundredFiftyLevelCap() {
        var s=AnvilSettings.DEFAULTS;
        assertEquals(150,s.maxLevelPrice());assertEquals(10,s.levelsPerDiamond());
        assertEquals(64,s.maxDiamondPrice());assertTrue(s.currencyPayment());
        assertFalse(s.aboveLimit(150));assertTrue(s.aboveLimit(151));
        assertEquals(0L,s.diamonds(150));
        assertEquals(0L,s.diamonds(39));
    }

    @Test void diamondPriceRoundsUpAndIsCappedForLegacyRepairCost() {
        var s=AnvilSettings.DEFAULTS;
        assertEquals(16L,s.diamonds(151));   // ceil(151/10) — не 1 алмаз за «лишний» уровень
        assertEquals(16L,s.diamonds(160));   // целое деление без сюрпризов
        assertEquals(17L,s.diamonds(161));
        assertEquals(64L,s.diamonds(16_383)); // предмет с накопленным RepairCost не требует 1639 алмазов
        assertEquals(64L,s.diamonds(Integer.MAX_VALUE));
    }

    @Test void disabledCurrencyNeverProducesAPrice() {
        var s=new AnvilSettings(150,10,64,false);
        assertTrue(s.aboveLimit(151));assertEquals(0L,s.diamonds(151));
        assertEquals("anvil.too-expensive",AnvilCostListener.noticeKey(s,151));
    }

    @Test void configuredValuesAreClampedToSaneRanges() {
        var s=new AnvilSettings(0,0,0,true);
        assertEquals(40,s.maxLevelPrice());assertEquals(1,s.levelsPerDiamond());assertEquals(1,s.maxDiamondPrice());
        var huge=new AnvilSettings(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,true);
        assertEquals(Integer.MAX_VALUE,huge.maxLevelPrice());assertEquals(1_000,huge.levelsPerDiamond());
        assertEquals(10_000,huge.maxDiamondPrice());
        assertEquals(0L,huge.diamonds(10_000));
    }

    @Test void unlimitedTestPolicyKeepsTheOldLevelOnlyContract() {
        var s=AnvilSettings.UNLIMITED;
        assertFalse(s.currencyPayment());assertEquals(0L,s.diamonds(16_383));
        assertEquals("anvil.full-price",AnvilCostListener.noticeKey(s,16_383));
    }
}
