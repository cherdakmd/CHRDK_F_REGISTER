package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Предпросмотр привязан к игроку, правилам, источникам и конкретному выбору. */
class ClassRespecQuoteTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final long NOW = 1_792_000_000_000L;
    private static final long COOLDOWN = 86_400;
    private static LegacyClassBasis basis(long at, Set<String> talents, int skills, int rank, int achievements,
                                           long changed, long respec, LegacyClassRules rules) {
        return new LegacyClassBasis(PLAYER, "warrior", talents, changed, respec, rules, skills, rank, achievements, at);
    }
    private static LegacyClassBasis initial() {
        return basis(NOW, Set.of("warrior-stoyka-1"), 300, 0, 30, NOW - 1000, 0,
                LegacyClassRules.capture(ClassConfig.defaults()));
    }

    @Test
    void equivalentFreshReadCanConfirmOnlyInsideLifetime() {
        ClassRespecQuote quote = ClassRespecQuote.create(initial(), COOLDOWN);
        LegacyClassBasis current = basis(NOW + 10, initial().talents(), 300, 0, 30, NOW - 1000, 0, initial().rules());
        assertTrue(quote.matches(PLAYER, current, NOW + 10, COOLDOWN));
        assertFalse(quote.matches(PLAYER, current, NOW - 1, COOLDOWN));
        assertTrue(quote.matches(PLAYER, current, NOW + 29_999, COOLDOWN));
        assertFalse(quote.matches(PLAYER, current, NOW + 30_000, COOLDOWN));
        assertFalse(quote.matches(OTHER, current, NOW + 1, COOLDOWN));
        assertFalse(quote.matches(PLAYER, null, NOW + 1, COOLDOWN));
    }

    @Test
    void changedTalentsOrSourcesRequireNewExplicitPreview() {
        ClassRespecQuote quote = ClassRespecQuote.create(initial(), COOLDOWN);
        for (LegacyClassBasis changed : new LegacyClassBasis[]{
                basis(NOW + 1, Set.of("warrior-stoyka-1", "warrior-stoyka-2"), 300, 0, 30, NOW - 1000, 0, initial().rules()),
                basis(NOW + 1, initial().talents(), 301, 0, 30, NOW - 1000, 0, initial().rules()),
                basis(NOW + 1, initial().talents(), 300, 1, 30, NOW - 1000, 0, initial().rules()),
                basis(NOW + 1, initial().talents(), 300, 0, 31, NOW - 1000, 0, initial().rules()),
                basis(NOW + 1, initial().talents(), 300, 0, 30, NOW - 999, 0, initial().rules()),
                basis(NOW + 1, initial().talents(), 300, 0, 30, NOW - 1000, NOW - 10, initial().rules())}) {
            assertFalse(quote.matches(PLAYER, changed, NOW + 1, COOLDOWN));
        }
    }

    @Test
    void configReloadCannotSilentlyChangePriceOrCooldownPromise() {
        ClassRespecQuote quote = ClassRespecQuote.create(initial(), COOLDOWN);
        var newRules = new LegacyClassRules(1, 30, 1, 2, 15, 2, true, true);
        var changed = basis(NOW + 1, initial().talents(), 300, 0, 30, NOW - 1000, 0, newRules);
        assertFalse(quote.matches(PLAYER, changed, NOW + 1, COOLDOWN));
        assertFalse(quote.matches(PLAYER, initial(), NOW + 1, COOLDOWN / 2));
    }

    @Test
    void otherClassCannotReuseQuote() {
        ClassRespecQuote quote = ClassRespecQuote.create(initial(), COOLDOWN);
        var changed = new LegacyClassBasis(PLAYER, "mage", Set.of(), NOW - 1000, 0,
                initial().rules(), 300, 0, 30, NOW + 1);
        assertFalse(quote.matches(PLAYER, changed, NOW + 1, COOLDOWN));
    }

    @Test
    void cannotConstructAnUnboundOrExtendedQuote() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClassRespecQuote(OTHER, initial(), NOW + 30_000, COOLDOWN));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassRespecQuote(PLAYER, initial(), NOW + 30_001, COOLDOWN));
        assertThrows(IllegalArgumentException.class,
                () -> ClassRespecQuote.create(initial(), -1));
    }
    @Test
    void unconfirmedInvestmentCannotBeOfferedAsFullRefund() {
        var deficit = basis(NOW, Set.of("warrior-stoyka-1", "warrior-stoyka-2"),
                0, 0, 0, NOW - 1000, 0, initial().rules());
        assertEquals(2, deficit.deficit());
        assertThrows(IllegalArgumentException.class, () -> ClassRespecQuote.create(deficit, COOLDOWN));
    }
}
