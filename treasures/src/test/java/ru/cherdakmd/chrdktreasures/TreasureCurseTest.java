package ru.cherdakmd.chrdktreasures;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проклятия-расплаты: цена есть только у сильных находок, шансы разумны, а
 * эффекты временные.
 */
class TreasureCurseTest {

    @Test
    void onlyLegendaryAndMythicalFindsAreCursed() {
        for (TreasureItemDef def : TreasureCatalog.all()) {
            boolean strong = def.tier() == TreasureTier.LEGENDARY
                    || def.tier() == TreasureTier.MYTHICAL;
            assertEquals(strong, def.cursed(), "проклятие не по редкости: " + def.id());
        }
    }

    @Test
    void cursesHaveSaneChancesAndDurations() {
        Set<TreasureCurse.Kind> kinds = EnumSet.noneOf(TreasureCurse.Kind.class);
        for (TreasureItemDef def : TreasureCatalog.all()) {
            TreasureCurse curse = def.curse();
            if (curse == null) {
                continue;
            }
            kinds.add(curse.kind());
            assertTrue(curse.chance() > 0.0 && curse.chance() <= 0.5,
                    "шанс проклятия вне 0..50 %: " + def.id());
            assertTrue(curse.kind().durationTicks() > 0,
                    "нулевая длительность: " + curse.kind());
            assertTrue(curse.kind().trigger() != null);
        }
        assertTrue(kinds.size() >= 4, "слишком мало видов проклятий: " + kinds);
    }

    @Test
    void everyCurseHasATriggerAndAVictimIsNeverKilledByIt() {
        for (TreasureCurse.Kind kind : TreasureCurse.Kind.values()) {
            assertTrue(kind.durationTicks() <= 200,
                    "проклятие длится слишком долго: " + kind);
            assertTrue(kind.id().matches("[a-z0-9]+(-[a-z0-9]+)*"), "ид проклятия: " + kind.id());
            assertEquals("curse." + kind.id(), kind.textKey());
        }
        Set<String> ids = new HashSet<>();
        for (TreasureCurse.Kind kind : TreasureCurse.Kind.values()) {
            assertTrue(ids.add(kind.id()), "дубль ида проклятия: " + kind.id());
        }
    }

    @Test
    void invalidCursesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureCurse(TreasureCurse.Kind.HUNGER, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureCurse(TreasureCurse.Kind.HUNGER, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new TreasureCurse(null, 0.2));
        assertFalse(new TreasureCurse(TreasureCurse.Kind.GLOWING, 0.5).chance() > 1.0);
    }
}
