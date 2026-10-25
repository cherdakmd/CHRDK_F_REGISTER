package ru.cherdakmd.chrdkreferrals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Правила привязки: себя нельзя, одноразовость, только до первой подписки,
 * регистрация пригласившего по настройке.
 */
class ReferralRulesTest {

    @Test
    void happyPath() {
        assertEquals(ReferralRules.LinkError.OK, ReferralRules.canLink(
                "Steve", "Alex", 0, false, true, true));
    }

    @Test
    void cannotSelfLink() {
        assertEquals(ReferralRules.LinkError.SELF, ReferralRules.canLink(
                "Steve", "steve", 0, false, true, true));
    }

    @Test
    void linkIsOneTimeOnly() {
        assertEquals(ReferralRules.LinkError.ALREADY_LINKED, ReferralRules.canLink(
                "Steve", "Alex", 0, true, true, true));
    }

    @Test
    void tooLateAfterAnyPassEver() {
        // passUntil в прошлом — подписка уже была, привязываться поздно
        assertEquals(ReferralRules.LinkError.TOO_LATE, ReferralRules.canLink(
                "Steve", "Alex", 1_500_000_000_000L, false, true, true));
        assertEquals(ReferralRules.LinkError.TOO_LATE, ReferralRules.canLink(
                "Steve", "Alex", 9_999_999_999_999L, false, true, true));
    }

    @Test
    void inviterRegistrationRequiredOnlyWhenConfigured() {
        assertEquals(ReferralRules.LinkError.INVITER_UNREGISTERED, ReferralRules.canLink(
                "Steve", "Alex", 0, false, false, true));
        assertEquals(ReferralRules.LinkError.OK, ReferralRules.canLink(
                "Steve", "Alex", 0, false, false, false),
                "правило выключается конфигом");
    }

    @Test
    void canonicalLowercasesNames() {
        assertEquals("alex", ReferralRules.canonical("AlEx"));
        assertEquals("", ReferralRules.canonical(null));
    }

    @Test
    void trialDoesNotPayReferralBonus() {
        org.junit.jupiter.api.Assertions.assertFalse(ReferralRules.triggersBonus("trial"));
        org.junit.jupiter.api.Assertions.assertTrue(ReferralRules.triggersBonus("donatepay"));
        org.junit.jupiter.api.Assertions.assertTrue(ReferralRules.triggersBonus("manual"));
    }

    @Test
    void errorKeysMapToMessages() {
        assertEquals("ref.use-ok", ReferralRules.messageKeyOf(ReferralRules.LinkError.OK));
        assertEquals("ref.use-too-late", ReferralRules.messageKeyOf(ReferralRules.LinkError.TOO_LATE));
    }
}
