package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Один владелец чат-ввода на все панели: раньше админ-панель и редактор званий
 * имели по своей карте ожиданий и оба слушали AsyncChatEvent на LOWEST.
 */
class GuiChatInputTest {

    @Test
    void newClaimReplacesOldOneAndForeignEndDoesNotCancelIt() {
        GuiChatInput bus = new GuiChatInput(null, 60_000L);
        UUID id = UUID.randomUUID();
        bus.begin(id, "adminpanel", text -> { });
        assertEquals("adminpanel", bus.owner(id));
        bus.begin(id, "honors", text -> { });
        assertEquals("honors", bus.owner(id), "новое ожидание перекрывает старое");
        assertEquals(1, bus.size(), "ровно одно ожидание на игрока");
        bus.end(id, "adminpanel");
        assertEquals("honors", bus.owner(id), "чужая панель не снимает ожидание");
        bus.end(id, "honors");
        assertNull(bus.owner(id));
    }

    @Test
    void clearOwnerAffectsOnlyItsOwnGui() {
        GuiChatInput bus = new GuiChatInput(null, 60_000L);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        bus.begin(a, "honors", text -> { });
        bus.begin(b, "adminpanel", text -> { });
        bus.clearOwner("honors");
        assertNull(bus.owner(a));
        assertEquals("adminpanel", bus.owner(b));
        bus.clearAll();
        assertEquals(0, bus.size());
    }

    @Test
    void expiredClaimNeverSwallowsTheNextMessage() throws Exception {
        GuiChatInput bus = new GuiChatInput(null, 15L);
        UUID id = UUID.randomUUID();
        bus.begin(id, "honors", text -> fail("просроченный ввод не должен применяться"));
        Thread.sleep(50L);
        assertNull(bus.owner(id), "просроченное ожидание сброшено");
        assertEquals(0, bus.size());
        bus.clear(id);
        assertNull(bus.owner(id));
    }
}
