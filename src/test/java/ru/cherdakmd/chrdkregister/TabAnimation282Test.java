package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TabAnimation282Test {
    private TabListService.Settings settings(boolean enabled, int refresh, String format) {
        // tablist.show-rank-name читает RegisterPlugin → ChatService.setRankNameInTab,
        // поэтому в Settings больше нет мёртвого поля showRankName.
        return new TabListService.Settings(enabled, refresh, List.of(), List.of(), format, List.of());
    }
    @Test void animationDoesNotDependOnTheGeneralHeaderRefreshTimer() {
        assertTrue(TabListService.animationEnabled(settings(true, 0, "%player_tagged%")));
        assertTrue(TabListService.animationEnabled(settings(true, 40, "%player_tagged%")));
        assertFalse(TabListService.animationEnabled(settings(false, 40, "%player_tagged%")));
        assertTrue(TabListService.animationEnabled(settings(true, 40, " ")));
        assertTrue(TabListService.animationEnabled(settings(true, 0, null)));
        assertEquals(0, TabListService.refreshTicks(0));
        assertEquals(0, TabListService.refreshTicks(-1));
        assertEquals(10, TabListService.refreshTicks(1));
        assertEquals(40, TabListService.refreshTicks(40));
        assertEquals(5, TabListService.animationTicks(0));
        assertEquals(10, TabListService.animationTicks(10));
        assertEquals(20, TabListService.animationTicks(1000));
    }
}
