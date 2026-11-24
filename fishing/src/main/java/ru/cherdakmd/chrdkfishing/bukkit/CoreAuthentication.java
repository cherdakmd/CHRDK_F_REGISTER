package ru.cherdakmd.chrdkfishing.bukkit;

import java.lang.reflect.Method;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicesManager;

/** The isolated prototype resolves Core's service from Core's own classloader; missing API fails closed. */
final class CoreAuthentication {
    private final ServicesManager services;
    private final Class<?> type;
    private final Method authenticated;
    CoreAuthentication(ServicesManager services, Class<?> type) throws ReflectiveOperationException {
        if (services == null || type == null || !type.isInterface()) throw new IllegalArgumentException("core API");
        this.services = services; this.type = type;
        authenticated = type.getMethod("isAuthenticated", Player.class);
        if (authenticated.getReturnType() != boolean.class) throw new NoSuchMethodException("isAuthenticated return type");
    }
    boolean available() { return services.load(type) != null; }
    boolean authenticated(Player player) {
        if (player == null) return false;
        Object service = services.load(type);
        if (service == null) return false;
        try { return Boolean.TRUE.equals(authenticated.invoke(service, player)); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { return false; }
    }
}
