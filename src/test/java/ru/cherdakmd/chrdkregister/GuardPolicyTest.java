package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GuardPolicyTest {
    @Test void attackRootsAndNamespacesAreConsoleOnly() {
        for(String root:List.of("fill","title","particle","gamerule","execute","tp","op","function","tick","reload"))
            for(String prefix:List.of("/","/minecraft:","/bukkit:"," /MINECRAFT:"))
                assertTrue(GuardPolicy.consoleOnly(prefix+root.toUpperCase()+" payload",GuardSettings.DEFAULT_CONSOLE_ONLY,k->null),prefix+root);
    }
    @Test void passwordsMessagesAndOrdinaryCommandsAreNotScannedAsCode() {
        for(String text:List.of("/login gamerule", "/register /fill /fill", "/2fa 123456", "/msg friend /stop", "/shop sellall", "/claim menu", "/tpa player"))
            assertFalse(GuardPolicy.consoleOnly(text,GuardSettings.DEFAULT_CONSOLE_ONLY,k->null),text);
    }
    @Test void registeredPluginAliasesCannotRenameBlockedRoots() {
        assertTrue(GuardPolicy.consoleOnly("/shortcut target",GuardSettings.DEFAULT_CONSOLE_ONLY,
                k->new GuardPolicy.Target("fill",List.of())));
    }
    @Test void nestedConfiguredAliasesAndMixedExpansionsAreChecked() {
        var aliases=Map.of("shortcut",List.of("say hello","next $1-"),"next",List.of("minecraft:execute as @a run fill 0 0 0 1 1 1 air"));
        assertTrue(GuardPolicy.consoleOnly("/shortcut",GuardSettings.DEFAULT_CONSOLE_ONLY,
                k->new GuardPolicy.Target(k,aliases.getOrDefault(k,List.of()))));
        assertFalse(GuardPolicy.consoleOnly("/hello",GuardSettings.DEFAULT_CONSOLE_ONLY,
                k->new GuardPolicy.Target(k,k.equals("hello")?List.of("msg friend hello"):List.of())));
    }
    @Test void dynamicRecursiveAndExcessivelyWideAliasesFailClosed() {
        for(List<String> expansions:List.of(List.of("loop"),List.of("$1 $2-"),java.util.Collections.nCopies(40,"say hello")))
            assertTrue(GuardPolicy.consoleOnly("loop",GuardSettings.DEFAULT_CONSOLE_ONLY,k->new GuardPolicy.Target(k,k.equals("loop")?expansions:List.of())));
    }
    @Test void emptyOverrideAndDisabledModuleAreExplicitSettings() {
        var c=new YamlConfiguration();
        assertEquals(GuardSettings.DEFAULT_CONSOLE_ONLY,GuardSettings.parse(c).consoleOnly());
        c.set("security.console-only-commands",List.of());c.set("security.enabled",false);
        assertTrue(GuardSettings.parse(c).consoleOnly().isEmpty());assertFalse(GuardSettings.parse(c).enabled());
    }
    @Test void malformedConsoleOnlySettingCannotSilentlyDisableProtection() {
        for(Object bad:List.of(false,"fill",List.of(""),List.of(12),Arrays.asList((Object)null))) {
            var c=new YamlConfiguration();c.set("security.console-only-commands",bad);
            assertEquals(GuardSettings.DEFAULT_CONSOLE_ONLY,GuardSettings.parse(c).consoleOnly(),String.valueOf(bad));
        }
    }
    @Test void shippedDefaultsMatchFallbacksAndUnsafeNumericValuesAreClamped() {
        var c=YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/config.yml"),StandardCharsets.UTF_8));
        assertEquals(GuardSettings.DEFAULT_CONSOLE_ONLY,GuardSettings.parse(c).consoleOnly());
        c.set("security.max-addresses",Integer.MAX_VALUE);c.set("security.login-seconds",-1);
        c.set("security.global-per-second",0);
        var settings=GuardSettings.parse(c);
        assertEquals(100000,settings.maxAddresses());assertEquals(30,settings.loginSeconds());assertEquals(1,settings.globalPerSecond());
    }
}
