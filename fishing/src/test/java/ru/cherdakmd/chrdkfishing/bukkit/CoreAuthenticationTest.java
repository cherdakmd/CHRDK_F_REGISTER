package ru.cherdakmd.chrdkfishing.bukkit;

import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoreAuthenticationTest {
    public interface Api { boolean isAuthenticated(Player player); }
    @Test void resolvesAndFailsClosedWhenServiceDisappearsOrThrows() throws Exception {
        var services = mock(ServicesManager.class); var service = mock(Api.class); var player = mock(Player.class);
        when(services.load(Api.class)).thenReturn(service);
        var auth = new CoreAuthentication(services, Api.class);
        assertTrue(auth.available()); assertFalse(auth.authenticated(player));
        when(service.isAuthenticated(player)).thenReturn(true); assertTrue(auth.authenticated(player));
        when(service.isAuthenticated(player)).thenThrow(new IllegalStateException()); assertFalse(auth.authenticated(player));
        when(services.load(Api.class)).thenReturn(null); assertFalse(auth.available()); assertFalse(auth.authenticated(player));
    }
}
