package ru.cherdakmd.chrdkregister;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.model.data.NodeMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuckPermsModeratorSyncTest {

    @Test
    @SuppressWarnings("unchecked")
    void addAndRemoveUseOfflineUuidLookupAndOnlyTheModeratorNode() {
        UUID uuid = UUID.randomUUID();
        List<String> operations = new ArrayList<>();
        NodeMap data = proxy(NodeMap.class, (method, args) -> switch (method) {
            case "add", "remove" -> {
                operations.add(method + ":" + ((Node) args[0]).getKey());
                yield true;
            }
            default -> throw new AssertionError("Unexpected NodeMap call: " + method);
        });
        User user = proxy(User.class, (method, args) -> {
            if (method.equals("data")) return data;
            throw new AssertionError("Unexpected User call: " + method);
        });
        UserManager users = proxy(UserManager.class, (method, args) -> switch (method) {
            case "lookupUniqueId" -> {
                assertEquals("OfflineMod", args[0]);
                yield CompletableFuture.completedFuture(uuid);
            }
            case "modifyUser" -> {
                assertEquals(uuid, args[0]);
                ((Consumer<User>) args[1]).accept(user);
                yield CompletableFuture.completedFuture(null);
            }
            default -> throw new AssertionError("Unexpected UserManager call: " + method);
        });
        LuckPerms api = proxy(LuckPerms.class, (method, args) -> {
            if (method.equals("getUserManager")) return users;
            throw new AssertionError("Unexpected LuckPerms call: " + method);
        });
        ServicesManager services = proxy(ServicesManager.class, (method, args) -> {
            if (method.equals("getRegistration") && args[0] == LuckPerms.class) {
                return new RegisteredServiceProvider<>(LuckPerms.class, api, ServicePriority.Normal, null);
            }
            return null;
        });
        ExternalHooks hooks = new ExternalHooks(services, Logger.getLogger("moderator-sync-test"));
        LuckPermsBootstrap bootstrap = new LuckPermsBootstrap(hooks,
                () -> PluginConfig.fromBukkitConfig(new YamlConfiguration()));

        bootstrap.setModerator("OfflineMod", true).join();
        bootstrap.setModerator("OfflineMod", false).join();

        assertEquals(List.of("add:group.moderator", "remove:group.moderator"), operations);
    }

    private interface Invocation {
        Object call(String method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.call(method.getName(), args));
    }
}
