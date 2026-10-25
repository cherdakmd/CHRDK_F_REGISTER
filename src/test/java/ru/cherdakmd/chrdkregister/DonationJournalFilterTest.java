package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Контракт фильтров журнала донатов (/pass donations): pending — все очереди разбора,
 * конкретные причины — строго по статусу, отсечение мусорных фильтров.
 */
class DonationJournalFilterTest {

    @Test
    void pendingGroupsAllReviewQueues() {
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_NAME, "pending"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_AMOUNT, "pending"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_REVIEW, "pending"));
        assertFalse(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.GRANTED, "pending"),
                "выданный донат не должен попадать в 'ждут решения'");
        assertFalse(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.REJECTED, "pending"));
    }

    @Test
    void exactReasonsAndFinalStates() {
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_NAME, "name"));
        assertFalse(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_AMOUNT, "name"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_AMOUNT, "amount"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_REVIEW, "review"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.GRANTED, "granted"));
        assertFalse(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.REJECTED, "granted"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.REJECTED, "rejected"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.REJECTED, "all"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.GRANTED, "all"));
    }

    @Test
    void knownFiltersEachPassTheirOwnStatus() {
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.GRANTED, "all"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.GRANTED, "granted"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_NAME, "pending"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.REJECTED, "rejected"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_NAME, "name"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_AMOUNT, "amount"));
        assertTrue(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_REVIEW, "review"));
    }

    @Test
    void unknownFiltersMatchNothingSoUsageShown() {
        assertFalse(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.GRANTED, "мусор"));
        assertFalse(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.PENDING_NAME, ""));
        assertFalse(DonationService.journalFilterMatches(
                RegistrationStore.DonationStatus.REJECTED, "granteddd"),
                "опечатка фильтра не должна ничего возвращать — показываем usage");
    }
}
