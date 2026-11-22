package ru.cherdakmd.chrdkregister;

import java.util.OptionalInt;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TabRebirthTest {
    @Test void onlyTheTwoPaidRankIdsCanHideTheCount() {
        assertTrue(TabRebirth.eligible("patron"));
        assertTrue(TabRebirth.eligible("founder"));
        for (String id : new String[]{"", "keeper", "other", "PATRON"}) assertFalse(TabRebirth.eligible(id));
        assertFalse(TabRebirth.eligible(null));
    }
    @Test void hiddenPreferenceIsIgnoredWithoutEligibleRankAndUnknownIsNotFakeZero() {
        var base=Component.text("Alex");
        assertNotEquals(base, TabRebirth.append(base,OptionalInt.of(0),false,false));
        assertNotEquals(base, TabRebirth.append(base,OptionalInt.of(3),false,true));
        assertNotEquals(base, TabRebirth.append(base,OptionalInt.of(3),true,false));
        assertEquals(base, TabRebirth.append(base,OptionalInt.of(3),true,true));
        assertEquals(base, TabRebirth.append(base,OptionalInt.empty(),false,false));
        assertEquals(base, TabRebirth.append(base,OptionalInt.of(-1),false,false));
    }
}
