package ru.cherdakmd.chrdkregister.datapack;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Настройки, id и решение о включении проверяются без сервера; Paper-часть —
 * на тестовых объектах с теми же публичными сигнатурами, что и DatapackManager.
 */
final class MineTreasureIntegrationTest {

    private static final Logger LOGGER = Logger.getAnonymousLogger();

    @Test
    void missingSectionUsesSafeDefaults() {
        MineTreasureIntegration.Settings settings =
                MineTreasureIntegration.settings(new YamlConfiguration());
        assertTrue(settings.enabled());
        assertFalse(settings.refreshOnStart());
        assertEquals(MineTreasureIntegration.DEFAULT_FILE_NAME, settings.fileName());
        assertEquals("", settings.explicitPackId());
        assertEquals("file/" + MineTreasureIntegration.DEFAULT_FILE_NAME,
                MineTreasureIntegration.packId(settings.fileName(), settings.explicitPackId()));
    }

    @Test
    void ownerCanDisableIntegrationAndOverridePackId() {
        YamlConfiguration config = yaml("""
                integrations:
                  mine-treasure:
                    auto-enable: false
                    refresh-on-start: true
                    file-name: custom-ru.zip
                    pack-id: CHRDK_Core/mine-treasure
                """);
        MineTreasureIntegration.Settings settings = MineTreasureIntegration.settings(config);
        assertFalse(settings.enabled());
        assertTrue(settings.refreshOnStart());
        assertEquals("custom-ru.zip", settings.fileName());
        assertEquals("CHRDK_Core/mine-treasure",
                MineTreasureIntegration.packId(settings.fileName(), settings.explicitPackId()));
    }

    @Test
    void unsafeFileNamesAndIdsAreRejected() {
        for (String name : List.of("", "   ", "..", "../evil.zip", "nested/evil.zip",
                "evil\\evil.zip", "evil\u0000.zip")) {
            assertNull(MineTreasureIntegration.packId(name, ""), name);
        }
        assertNull(MineTreasureIntegration.packId("ok.zip", "../other"));
        assertNotNull(MineTreasureIntegration.packId("mine-treasure-ru.zip", ""));
    }

    @Test
    void decideNeverEnablesAnAbsentOrAlreadyEnabledPack() {
        assertEquals(MineTreasureIntegration.Outcome.PACK_ABSENT,
                MineTreasureIntegration.decide(false, false));
        assertEquals(MineTreasureIntegration.Outcome.ALREADY_ENABLED,
                MineTreasureIntegration.decide(true, true));
        assertEquals(MineTreasureIntegration.Outcome.ENABLED,
                MineTreasureIntegration.decide(true, false));
    }

    @Test
    void disabledPackIsEnabledOnceWithoutRefresh() {
        FakePack pack = new FakePack("file/mine-treasure-1.6.5-ru.zip", false);
        FakeManager manager = new FakeManager(pack);
        MineTreasureIntegration.Outcome outcome = MineTreasureIntegration.apply(manager,
                "file/mine-treasure-1.6.5-ru.zip", "mine-treasure-1.6.5-ru.zip", false, LOGGER);
        assertEquals(MineTreasureIntegration.Outcome.ENABLED, outcome);
        assertEquals(1, pack.setEnabledCalls);
        assertTrue(pack.enabled);
        assertEquals(0, manager.refreshCalls);
    }

    @Test
    void alreadyEnabledPackIsLeftAlone() {
        FakePack pack = new FakePack("file/mine-treasure-1.6.5-ru.zip", true);
        FakeManager manager = new FakeManager(pack);
        assertEquals(MineTreasureIntegration.Outcome.ALREADY_ENABLED,
                MineTreasureIntegration.apply(manager, "file/mine-treasure-1.6.5-ru.zip",
                        "mine-treasure-1.6.5-ru.zip", true, LOGGER));
        assertEquals(0, pack.setEnabledCalls);
        assertEquals(0, manager.refreshCalls);
    }

    @Test
    void refreshAndNameFallbackFindPackWithDifferentId() {
        FakePack pack = new FakePack("CHRDK_Core/custom-ru.zip", false);
        FakeManager manager = new FakeManager(null);
        manager.afterRefresh = pack;
        assertEquals(MineTreasureIntegration.Outcome.ENABLED,
                MineTreasureIntegration.apply(manager, "file/custom-ru.zip",
                        "custom-ru.zip", true, LOGGER));
        assertEquals(1, manager.refreshCalls);
        assertEquals(1, pack.setEnabledCalls);
    }

    @Test
    void absentPackAndMissingManagerDoNotThrow() {
        assertEquals(MineTreasureIntegration.Outcome.API_UNAVAILABLE,
                MineTreasureIntegration.apply(null, "file/ok.zip", "ok.zip", false, LOGGER));
        FakeManager absent = new FakeManager(null);
        assertEquals(MineTreasureIntegration.Outcome.PACK_ABSENT,
                MineTreasureIntegration.apply(absent, "file/ok.zip", "ok.zip", false, LOGGER));
    }

    @Test
    void brokenManagerApiIsReportedAsFailure() {
        assertEquals(MineTreasureIntegration.Outcome.FAILED,
                MineTreasureIntegration.apply(new BrokenManager(), "file/ok.zip",
                        "ok.zip", false, LOGGER));
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception error) {
            throw new AssertionError("Нечитаемый YAML: " + text, error);
        }
        return config;
    }

    /** Тестовый менеджер: сигнатуры совпадают с Paper DatapackManager. */
    public static final class FakeManager {
        private final FakePack exact;
        int refreshCalls;
        int getPackCalls;
        FakePack afterRefresh;
        Collection<Object> packs = List.of();

        FakeManager(FakePack exact) {
            this.exact = exact;
        }

        public void refreshPacks() {
            refreshCalls++;
            if (afterRefresh != null) {
                packs = List.of(afterRefresh);
            }
        }

        public Object getPack(String id) {
            getPackCalls++;
            return exact != null && exact.getName().equals(id) ? exact : null;
        }

        public Collection<Object> getPacks() {
            return packs;
        }
    }

    /** Тестовый пак: сигнатуры совпадают с Paper Datapack. */
    public static final class FakePack {
        private final String name;
        boolean enabled;
        int setEnabledCalls;

        FakePack(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            setEnabledCalls++;
            enabled = value;
        }
    }

    /** Нет ни getPack, ни getPacks: ядро должно вернуть FAILED, не бросить ошибку. */
    public static final class BrokenManager {
        public void refreshPacks() {
        }
    }
}
