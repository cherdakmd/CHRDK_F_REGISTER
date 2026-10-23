package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разделы {@code /profile} (1.4.156): порядок, скрытие пустых, изоляция сбоев
 * и перерегистрация аддоном.
 */
class ProfileRegistryTest {

    private static final Logger LOGGER = Logger.getLogger("ProfileRegistryTest");

    private static final ChrdkRegisterApi.ProfileContext CONTEXT =
            new ChrdkRegisterApi.ProfileContext(UUID.randomUUID(), "Аня", true, true);

    private static ChrdkRegisterApi.ProfileSection section(String title, Material icon,
                                                           String... lines) {
        return new ChrdkRegisterApi.ProfileSection(title, icon,
                java.util.Arrays.stream(lines).map(Component::text).toList());
    }

    private static ChrdkRegisterApi.ProfileProvider provider(int order,
                                                             ChrdkRegisterApi.ProfileSection section) {
        return new ChrdkRegisterApi.ProfileProvider() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public ChrdkRegisterApi.ProfileSection section(
                    ChrdkRegisterApi.ProfileContext context) {
                return section;
            }
        };
    }

    @Test
    void blankIdsAndNullProvidersAreIgnored() {
        ProfileRegistry registry = new ProfileRegistry(LOGGER);
        assertFalse(registry.register("", provider(1, section("A", Material.BONE, "x"))));
        assertFalse(registry.register(null, provider(1, section("A", Material.BONE, "x"))));
        assertFalse(registry.register("hunt", null));
        assertEquals(0, registry.size());
    }

    @Test
    void sectionsAreSortedByOrderThenId() {
        ProfileRegistry registry = new ProfileRegistry(LOGGER);
        registry.register("orders", provider(20, section("Заказы", Material.PAPER, "1")));
        registry.register("hunt", provider(10, section("Охота", Material.BONE, "2")));
        registry.register("achievements", provider(20, section("Достижения", Material.BOOK, "3")));

        List<ChrdkRegisterApi.ProfileSection> sections = registry.sections(CONTEXT);
        assertEquals(3, sections.size());
        assertEquals("Охота", sections.get(0).title());
        assertEquals("Достижения", sections.get(1).title(), "при равенстве order — по id");
        assertEquals("Заказы", sections.get(2).title());
        assertEquals(List.of("hunt", "achievements", "orders"), registry.ids(),
                "порядок разделов: order(), затем id");
    }

    @Test
    void emptySectionsAreHidden() {
        ProfileRegistry registry = new ProfileRegistry(LOGGER);
        registry.register("empty", provider(1, new ChrdkRegisterApi.ProfileSection(
                "Пусто", Material.BONE, List.of())));
        registry.register("none", provider(2, null));
        registry.register("full", provider(3, section("Есть", Material.BONE, "строка")));

        List<ChrdkRegisterApi.ProfileSection> sections = registry.sections(CONTEXT);
        assertEquals(1, sections.size());
        assertEquals("Есть", sections.get(0).title());
    }

    @Test
    void brokenProviderDoesNotBreakTheRest() {
        ProfileRegistry registry = new ProfileRegistry(LOGGER);
        registry.register("broken", context -> {
            throw new IllegalStateException("нет данных");
        });
        registry.register("hunt", provider(10, section("Охота", Material.BONE, "строка")));

        List<ChrdkRegisterApi.ProfileSection> sections = registry.sections(CONTEXT);
        assertEquals(1, sections.size());
        assertEquals("Охота", sections.get(0).title());
    }

    @Test
    void sameIdReplacesTheProvider() {
        ProfileRegistry registry = new ProfileRegistry(LOGGER);
        registry.register("hunt", provider(10, section("Старая", Material.BONE, "1")));
        registry.register("hunt", provider(10, section("Новая", Material.BONE, "2")));

        assertEquals(1, registry.size());
        assertEquals("Новая", registry.sections(CONTEXT).get(0).title());
    }

    @Test
    void unregisterRemovesTheSection() {
        ProfileRegistry registry = new ProfileRegistry(LOGGER);
        registry.register("hunt", provider(10, section("Охота", Material.BONE, "1")));
        assertTrue(registry.unregister("hunt"));
        assertFalse(registry.unregister("hunt"));
        assertFalse(registry.unregister(null));
        assertTrue(registry.sections(CONTEXT).isEmpty());
    }

    @Test
    void providerReceivesTheContext() {
        ProfileRegistry registry = new ProfileRegistry(LOGGER);
        UUID id = UUID.randomUUID();
        ChrdkRegisterApi.ProfileContext context =
                new ChrdkRegisterApi.ProfileContext(id, "Борис", false, false);
        registry.register("who", new ChrdkRegisterApi.ProfileProvider() {
            @Override
            public ChrdkRegisterApi.ProfileSection section(
                    ChrdkRegisterApi.ProfileContext received) {
                return section(received.playerId() + " " + received.playerName()
                                + " " + received.online() + " " + received.self(),
                        Material.PAPER, "x");
            }
        });

        ChrdkRegisterApi.ProfileSection result = registry.sections(context).get(0);
        assertEquals(id + " Борис false false", result.title());
        assertTrue(registry.sections(null).isEmpty(), "без контекста разделов нет");
    }

    @Test
    void sectionFillsSafeDefaults() {
        ChrdkRegisterApi.ProfileSection empty =
                new ChrdkRegisterApi.ProfileSection(" ", null, null);
        assertEquals("?", empty.title());
        assertEquals(Material.PAPER, empty.icon());
        assertTrue(empty.lines().isEmpty());

        ChrdkRegisterApi.ProfileSection normal =
                new ChrdkRegisterApi.ProfileSection("Охота", Material.BONE,
                        List.of(Component.text("строка")));
        assertEquals("Охота", normal.title());
        assertEquals(Material.BONE, normal.icon());
        assertEquals(1, normal.lines().size());
    }
}
