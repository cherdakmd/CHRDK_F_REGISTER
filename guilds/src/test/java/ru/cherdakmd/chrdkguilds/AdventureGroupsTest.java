package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AdventureGroupsTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();
    private final Set<UUID> online = new HashSet<>(Set.of(a, b, c, d));
    private final AtomicReference<GuildRelation> state = new AtomicReference<>(GuildRelation.OTHER);
    private final AdventureGroups groups = new AdventureGroups(3, (x, y) -> state.get(), online::contains);

    @Test void consentLimitAndLeaderControl() {
        assertEquals(AdventureGroups.Result.OK, groups.invite(a, b, 0));
        assertTrue(groups.members(a).isEmpty(), "до согласия группа не создаётся");
        assertEquals(AdventureGroups.Result.OK, groups.accept(b, 1));
        assertEquals(Set.of(a, b), Set.copyOf(groups.members(a)));
        assertEquals(AdventureGroups.Result.NOT_LEADER, groups.invite(b, c, 2));
        assertEquals(AdventureGroups.Result.OK, groups.invite(a, c, 2));
        assertEquals(AdventureGroups.Result.OK, groups.accept(c, 3));
        assertEquals(AdventureGroups.Result.GROUP_FULL, groups.invite(a, d, 4));
        groups.leave(b);
        assertEquals(Set.of(a, c), Set.copyOf(groups.members(c)));
        groups.leave(c);
        assertTrue(groups.members(a).isEmpty(), "один участник — роспуск");
    }

    @Test void expiredInvitationsAndRechecks() {
        assertEquals(AdventureGroups.Result.OK, groups.invite(a, b, 100));
        assertEquals(AdventureGroups.Result.INVITE_EXPIRED, groups.accept(b, 60_100));
        assertEquals(AdventureGroups.Result.INVITE_MISSING, groups.accept(b, 60_101));
        assertEquals(AdventureGroups.Result.OK, groups.invite(a, b, 200));
        state.set(GuildRelation.ACTIVE_WAR);
        assertEquals(AdventureGroups.Result.AT_WAR, groups.accept(b, 201));
        assertTrue(groups.members(a).isEmpty());
        state.set(GuildRelation.OTHER);
        assertEquals(AdventureGroups.Result.OK, groups.invite(a, b, 202));
        online.remove(a);
        assertEquals(AdventureGroups.Result.OFFLINE, groups.accept(b, 203));
        assertTrue(groups.members(b).isEmpty());
    }

    @Test void verifiesEveryPairAndDisbandsOnWar() {
        var rivals = new HashSet<Set<UUID>>();
        var group = new AdventureGroups(16, (x, y) -> rivals.contains(Set.of(x, y))
                ? GuildRelation.ACTIVE_WAR : GuildRelation.OTHER, online::contains);
        assertEquals(AdventureGroups.Result.OK, group.invite(a, b, 0));
        assertEquals(AdventureGroups.Result.OK, group.accept(b, 1));
        assertEquals(AdventureGroups.Result.OK, group.invite(a, c, 2));
        assertEquals(AdventureGroups.Result.OK, group.accept(c, 3));
        rivals.add(Set.of(b, c));
        assertEquals(AdventureGroups.Result.AT_WAR, group.verify(a), "война не обязательно с лидером");
        assertTrue(group.members(b).isEmpty());
        assertTrue(group.members(c).isEmpty());
    }

    @Test void acceptanceRechecksCandidateAgainstAllMembers() {
        var rivals = new HashSet<Set<UUID>>();
        var group = new AdventureGroups(16, (x, y) -> rivals.contains(Set.of(x, y))
                ? GuildRelation.ACTIVE_WAR : GuildRelation.OTHER, online::contains);
        assertEquals(AdventureGroups.Result.OK, group.invite(a, b, 0));
        assertEquals(AdventureGroups.Result.OK, group.accept(b, 1));
        assertEquals(AdventureGroups.Result.OK, group.invite(a, c, 2));
        rivals.add(Set.of(b, c));
        assertEquals(AdventureGroups.Result.AT_WAR, group.accept(c, 3));
        assertEquals(Set.of(a, b), Set.copyOf(group.members(a)));
        assertTrue(group.members(c).isEmpty());
    }

    @Test void unavailableResolverFailsClosed() {
        var group = new AdventureGroups(16, (x, y) -> { throw new IllegalStateException(); }, online::contains);
        assertEquals(AdventureGroups.Result.UNAVAILABLE, group.invite(a, b, 0));
        assertTrue(group.members(a).isEmpty());
    }

    @Test void unknownDoesNotGrantAccessOrDissolveGroup() {
        assertEquals(AdventureGroups.Result.OK, groups.invite(a, b, 0));
        assertEquals(AdventureGroups.Result.OK, groups.accept(b, 1));
        state.set(GuildRelation.UNKNOWN);
        assertEquals(AdventureGroups.Result.UNAVAILABLE, groups.verify(a));
        assertTrue(groups.members(a).isEmpty());
        assertEquals(AdventureGroups.Result.UNAVAILABLE, groups.invite(a, c, 2));
        state.set(GuildRelation.OTHER);
        assertEquals(Set.of(a, b), Set.copyOf(groups.members(a)));
    }

    @Test void leaderLeavingAndClearCancelInvites() {
        assertEquals(AdventureGroups.Result.OK, groups.invite(a, b, 0));
        assertEquals(AdventureGroups.Result.OK, groups.accept(b, 1));
        assertEquals(AdventureGroups.Result.OK, groups.invite(a, c, 2));
        groups.leave(a);
        assertTrue(groups.members(b).isEmpty());
        assertEquals(AdventureGroups.Result.INVITE_MISSING, groups.accept(c, 3));
        assertEquals(AdventureGroups.Result.OK, groups.invite(c, d, 4));
        groups.clear();
        assertEquals(AdventureGroups.Result.INVITE_MISSING, groups.accept(d, 5));
    }

    @Test void invalidLimitAndSelfInvitation() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdventureGroups(1, (x, y) -> GuildRelation.OTHER, online::contains));
        assertThrows(IllegalArgumentException.class,
                () -> new AdventureGroups(17, (x, y) -> GuildRelation.OTHER, online::contains));
        assertEquals(AdventureGroups.Result.INVALID, groups.invite(a, a, 0));
        assertEquals(AdventureGroups.Result.INVALID, groups.invite(a, b, Long.MAX_VALUE));
    }
}
