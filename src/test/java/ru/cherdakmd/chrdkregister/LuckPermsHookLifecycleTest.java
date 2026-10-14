package ru.cherdakmd.chrdkregister;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.GroupManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LuckPermsHookLifecycleTest {
    private static final Logger LOGGER = Logger.getLogger("luckperms-lifecycle-test");

    private static PluginConfig config() {
        return PluginConfig.fromBukkitConfig(new YamlConfiguration());
    }

    private static ServicesManager services(AtomicReference<LuckPerms> provider) {
        return (ServicesManager) Proxy.newProxyInstance(ServicesManager.class.getClassLoader(),
                new Class<?>[]{ServicesManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getRegistration")) {
                        if (args[0] == LuckPerms.class && provider.get() != null) {
                            return new RegisteredServiceProvider<>(LuckPerms.class, provider.get(), ServicePriority.Normal, null);
                        }
                        return null; // Vault is absent; it must not prevent detecting LuckPerms.
                    }
                    throw new AssertionError("Unexpected services call: " + method.getName());
                });
    }

    private static LuckPerms provider(List<String> loadedGroups) {
        GroupManager groups = (GroupManager) Proxy.newProxyInstance(GroupManager.class.getClassLoader(),
                new Class<?>[]{GroupManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("loadGroup")) {
                        loadedGroups.add((String) args[0]);
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    throw new AssertionError("Unexpected group call: " + method.getName());
                });
        return (LuckPerms) Proxy.newProxyInstance(LuckPerms.class.getClassLoader(),
                new Class<?>[]{LuckPerms.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getGroupManager")) return groups;
                    throw new AssertionError("Unexpected API call: " + method.getName());
                });
    }

    @Test
    void startupCreatesHooksBeforeTheConsumerThatCapturesThem() throws Exception {
        String source = Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkregister/RegisterPlugin.java"));
        int hooks = source.indexOf("this.externalHooks = new ExternalHooks(this);");
        int consumer = source.indexOf("this.accessManager = new AccessManager(");
        assertTrue(hooks >= 0 && consumer > hooks,
                "LuckPermsBootstrap captures plugin.externalHooks() in the AccessManager constructor");
        assertEquals(hooks, source.lastIndexOf("this.externalHooks = new ExternalHooks(this);"),
                "Do not replace the hooks after consumers captured the shared instance");
    }

    @Test
    void productionConstructorRejectsNullHooksInsteadOfReportingAnInstalledPluginAsMissing() {
        assertThrows(NullPointerException.class,
                () -> new LuckPermsBootstrap(null, LuckPermsHookLifecycleTest::config));
    }

    @Test
    void registeredProviderIsAvailableToBootstrapAndStatusEvenWithoutVault() {
        List<String> loaded = new ArrayList<>();
        LuckPerms api = provider(loaded);
        ExternalHooks hooks = new ExternalHooks(services(new AtomicReference<>(api)), LOGGER);
        LuckPermsBootstrap bootstrap = new LuckPermsBootstrap(hooks, LuckPermsHookLifecycleTest::config);
        assertSame(api, hooks.luckPerms().orElseThrow());
        assertTrue(bootstrap.available());
        List<LuckPermsBootstrap.GroupStatus> statuses = bootstrap.status().join();
        assertEquals(List.of("default", "pass", "admin"), loaded);
        assertEquals(3, statuses.size());
        assertTrue(statuses.stream().noneMatch(LuckPermsBootstrap.GroupStatus::exists));
    }

    @Test
    void sameBootstrapSeesProviderRefreshAndRemoval() {
        AtomicReference<LuckPerms> registration = new AtomicReference<>();
        ExternalHooks hooks = new ExternalHooks(services(registration), LOGGER);
        LuckPermsBootstrap bootstrap = new LuckPermsBootstrap(hooks, LuckPermsHookLifecycleTest::config);
        assertFalse(bootstrap.available());
        LuckPerms api = provider(new ArrayList<>());
        registration.set(api);
        hooks.resolve();
        assertTrue(bootstrap.available());
        assertSame(api, hooks.luckPerms().orElseThrow());
        assertEquals(3, bootstrap.status().join().size());
        registration.set(null);
        hooks.resolve();
        assertFalse(bootstrap.available());
    }
}
