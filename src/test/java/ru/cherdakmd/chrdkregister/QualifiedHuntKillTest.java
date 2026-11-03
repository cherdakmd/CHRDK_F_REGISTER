package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.events.QualifiedHuntKillEvent;
import static org.junit.jupiter.api.Assertions.*;

class QualifiedHuntKillTest {
    private org.bukkit.entity.Player player() {
        return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, args) -> null);
    }
    @Test void invalidNaturalLevelOrVariantCannotBePublished() {
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHuntKillEvent(player(),0,0));
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHuntKillEvent(player(),31,0));
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHuntKillEvent(player(),10,99));
    }
    @Test void notificationHasItsOwnHandlersAndKeepsValidatedNumbers() {
        var event = new QualifiedHuntKillEvent(player(),30,1);
        assertEquals(30,event.level()); assertEquals(1,event.elite());
        assertSame(QualifiedHuntKillEvent.getHandlerList(),event.getHandlers());
    }
}
