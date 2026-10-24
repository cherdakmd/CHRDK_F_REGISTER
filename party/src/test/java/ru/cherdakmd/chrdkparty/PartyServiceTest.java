package ru.cherdakmd.chrdkparty;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила групп (1.4.158): создание, приглашения со сроком, лидерство, потолок
 * состава и бонус опыта. Всё — без сервера: время передаётся параметром.
 */
class PartyServiceTest {

    private static final Logger LOGGER = Logger.getLogger("PartyServiceTest");
    private static final long NOW = 1_000_000L;

    private static PartyService service(Path dir, PartyConfig config) {
        PartyStore store = new PartyStore(dir, LOGGER);
        store.load();
        return new PartyService(config, store);
    }

    private static PartyService defaults(Path dir) {
        return service(dir, PartyConfig.defaults());
    }

    /** Собирает конфиг из умолчаний с правкой нужных полей. */
    private static PartyConfig config(int maxSize, int expireSeconds, int perMember, int maxBonus) {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("limits.max-size", maxSize);
        cfg.set("limits.invite-expire-seconds", expireSeconds);
        cfg.set("bonus.per-member-percent", perMember);
        cfg.set("bonus.max-percent", maxBonus);
        return PartyConfig.parse(cfg, LOGGER);
    }

    @Test
    void createAndLeave(@TempDir Path dir) {
        PartyService service = defaults(dir);
        UUID anya = UUID.randomUUID();
        assertNull(service.of(anya), "новичок ни в какой группе");

        Party party = service.create(anya, "Аня");
        assertNotNull(party);
        assertEquals(1, party.size());
        assertTrue(party.isLeader(anya));
        assertEquals("Аня", party.leaderName());

        assertNull(service.create(anya, "Аня"), "вторую группу создать нельзя");

        assertEquals(PartyService.Result.OK, service.leave(anya));
        assertNull(service.of(anya), "последний ушёл — группы нет");
    }

    @Test
    void inviteAcceptAndDeny(@TempDir Path dir) {
        PartyService service = defaults(dir);
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        UUID vera = UUID.randomUUID();
        service.create(anya, "Аня");

        assertEquals(PartyService.Result.OK,
                service.invite(anya, "Аня", boris, "Борис", NOW));
        assertNotNull(service.inviteOf(boris, NOW));
        assertEquals(1, service.invitesFor(boris, NOW));
        assertEquals(PartyService.Result.ALREADY_INVITED,
                service.invite(anya, "Аня", boris, "Борис", NOW));

        assertEquals(PartyService.Result.OK, service.accept(boris, "Борис", NOW));
        Party party = service.of(anya);
        assertEquals(2, party.size());
        assertTrue(party.isMember(boris));
        assertEquals(0, service.invitesFor(boris, NOW), "приглашение сгорело при входе");

        assertEquals(PartyService.Result.OK,
                service.invite(anya, "Аня", vera, "Вера", NOW));
        assertTrue(service.deny(vera));
        assertEquals(PartyService.Result.NO_INVITE, service.accept(vera, "Вера", NOW));
        assertFalse(service.deny(vera), "второй раз отклонять нечего");
    }

    @Test
    void inviteExpires(@TempDir Path dir) {
        PartyService service = service(dir, config(8, 30, 5, 15));
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        service.create(anya, "Аня");
        service.invite(anya, "Аня", boris, "Борис", NOW);

        long later = NOW + 31_000L;
        assertEquals(PartyService.Result.EXPIRED, service.accept(boris, "Борис", later));
        assertNull(service.inviteOf(boris, later), "истёкшее приглашение не висит");
        assertNull(service.of(boris));
    }

    @Test
    void onlyLeaderInvitesAndKicks(@TempDir Path dir) {
        PartyService service = defaults(dir);
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        UUID vera = UUID.randomUUID();
        service.create(anya, "Аня");
        service.invite(anya, "Аня", boris, "Борис", NOW);
        service.accept(boris, "Борис", NOW);

        assertEquals(PartyService.Result.NOT_LEADER,
                service.invite(boris, "Борис", vera, "Вера", NOW));
        assertEquals(PartyService.Result.NOT_LEADER, service.kick(boris, anya));
        assertEquals(PartyService.Result.NOT_LEADER, service.disband(boris));

        assertEquals(PartyService.Result.OK, service.kick(anya, boris));
        assertNull(service.of(boris));
        assertEquals(1, service.of(anya).size());
    }

    @Test
    void partySizeIsCapped(@TempDir Path dir) {
        PartyService service = service(dir, config(2, 60, 5, 15));
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        UUID vera = UUID.randomUUID();
        service.create(anya, "Аня");
        service.invite(anya, "Аня", boris, "Борис", NOW);
        service.accept(boris, "Борис", NOW);

        assertEquals(PartyService.Result.PARTY_FULL,
                service.invite(anya, "Аня", vera, "Вера", NOW));
    }

    @Test
    void inviteLimitStopsSpam(@TempDir Path dir) {
        PartyService service = defaults(dir);
        UUID anya = UUID.randomUUID();
        service.create(anya, "Аня");
        int limit = service.config().inviteMaxPending();
        for (int index = 0; index < limit; index++) {
            assertEquals(PartyService.Result.OK, service.invite(anya, "Аня",
                    UUID.randomUUID(), "Игрок" + index, NOW));
        }
        assertEquals(PartyService.Result.INVITE_LIMIT, service.invite(anya, "Аня",
                UUID.randomUUID(), "Лишний", NOW));
    }

    @Test
    void selfAndTakenTargetsAreRejected(@TempDir Path dir) {
        PartyService service = defaults(dir);
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        service.create(anya, "Аня");
        service.create(boris, "Борис");

        assertEquals(PartyService.Result.SELF, service.invite(anya, "Аня", anya, "Аня", NOW));
        assertEquals(PartyService.Result.TARGET_IN_PARTY,
                service.invite(anya, "Аня", boris, "Борис", NOW));
        assertEquals(PartyService.Result.NOT_IN_PARTY,
                service.invite(UUID.randomUUID(), "Кто-то", boris, "Борис", NOW));
    }

    @Test
    void leadershipPassesToTheNextMember(@TempDir Path dir) {
        PartyService service = defaults(dir);
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        UUID vera = UUID.randomUUID();
        service.create(anya, "Аня");
        service.invite(anya, "Аня", boris, "Борис", NOW);
        service.accept(boris, "Борис", NOW);
        service.invite(anya, "Аня", vera, "Вера", NOW);
        service.accept(vera, "Вера", NOW);

        assertEquals(PartyService.Result.OK, service.leave(anya));
        Party party = service.of(boris);
        assertNotNull(party, "группа не должна исчезнуть вместе с лидером");
        assertTrue(party.isLeader(boris), "лидерство переходит следующему");
        assertEquals(boris, party.memberIds().get(0), "лидер идёт первым в списке");
        assertFalse(party.isMember(anya));
    }

    @Test
    void disbandRemovesEveryone(@TempDir Path dir) {
        PartyService service = defaults(dir);
        UUID anya = UUID.randomUUID();
        UUID boris = UUID.randomUUID();
        service.create(anya, "Аня");
        service.invite(anya, "Аня", boris, "Борис", NOW);
        service.accept(boris, "Борис", NOW);

        assertEquals(PartyService.Result.OK, service.disband(anya));
        assertNull(service.of(anya));
        assertNull(service.of(boris));
    }

    @Test
    void bonusCountsOnlyNearbyMembersAndStopsAtTheCap(@TempDir Path dir) {
        PartyService service = service(dir, config(8, 60, 5, 15));
        UUID anya = UUID.randomUUID();
        service.create(anya, "Аня");

        assertEquals(0, service.bonusPercent(anya, 3), "один в группе — бонуса нет");

        UUID member = UUID.randomUUID();
        service.invite(anya, "Аня", member, "Борис", NOW);
        service.accept(member, "Борис", NOW);

        assertEquals(0, service.bonusPercent(anya, 0), "никого рядом — нет бонуса");
        assertEquals(5, service.bonusPercent(anya, 1));
        assertEquals(10, service.bonusPercent(anya, 2));
        assertEquals(15, service.bonusPercent(anya, 5), "бонус упирается в потолок");
        assertEquals(0, service.bonusPercent(UUID.randomUUID(), 3),
                "прохожий без группы бонуса не получает");
        assertTrue(service.sameParty(anya, member));
        assertFalse(service.sameParty(anya, UUID.randomUUID()));
        assertFalse(service.sameParty(anya, anya), "сам с собой — не пара");
    }

    @Test
    void potentialBonusUsesPartySize(@TempDir Path dir) {
        PartyService service = service(dir, config(8, 60, 5, 15));
        UUID anya = UUID.randomUUID();
        Party party = service.create(anya, "Аня");
        assertEquals(0, service.potentialBonus(party), "один в группе — бонуса нет");
        UUID boris = UUID.randomUUID();
        service.invite(anya, "Аня", boris, "Борис", NOW);
        service.accept(boris, "Борис", NOW);
        assertEquals(5, service.potentialBonus(service.of(anya)));
        assertEquals(0, service.potentialBonus(null));
    }

    @Test
    void forceDisbandWorksForAdmins(@TempDir Path dir) {
        PartyService service = defaults(dir);
        UUID anya = UUID.randomUUID();
        service.create(anya, "Аня");
        assertTrue(service.forceDisband(anya));
        assertNull(service.of(anya));
        assertFalse(service.forceDisband(anya));
    }

    @Test
    void partyOfAnotherPlayerIsNotVisibleThroughOf(@TempDir Path dir) {
        PartyService service = defaults(dir);
        UUID anya = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        service.create(anya, "Аня");
        assertNull(service.of(stranger));
        assertFalse(service.sameParty(anya, stranger));
    }
}
