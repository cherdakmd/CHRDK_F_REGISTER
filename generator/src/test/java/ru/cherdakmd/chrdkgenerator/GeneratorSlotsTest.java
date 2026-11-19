package ru.cherdakmd.chrdkgenerator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Слоты генераторов: базовый, за перерождение и за платный ранг. */
class GeneratorSlotsTest {

    @Test
    void baseSlotIsAlwaysOne() {
        assertEquals(1, GeneratorSlots.maxSlots(0, 0, 1, 1));
        assertEquals(1, GeneratorSlots.maxSlots(0, 0, 0, 0), "нулевые пороги — слоты выключены");
    }

    @Test
    void rebirthUnlocksSecondSlotAtThreshold() {
        assertEquals(1, GeneratorSlots.maxSlots(0, 0, 1, 0));
        assertEquals(2, GeneratorSlots.maxSlots(1, 0, 1, 0));
        assertEquals(1, GeneratorSlots.maxSlots(2, 0, 5, 0), "до порога слот закрыт");
        assertEquals(2, GeneratorSlots.maxSlots(5, 0, 5, 0), "на пороге слот открыт");
    }

    @Test
    void paidRankUnlocksThirdSlot() {
        assertEquals(2, GeneratorSlots.maxSlots(0, 1, 0, 1), "только ранг: база + ранг");
        assertEquals(3, GeneratorSlots.maxSlots(3, 2, 1, 2), "все три слота открыты");
        assertEquals(2, GeneratorSlots.maxSlots(3, 1, 1, 2), "ступень ранга ниже порога");
    }
}
