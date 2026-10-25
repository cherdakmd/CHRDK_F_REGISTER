package ru.cherdakmd.chrdkguilds;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модель гильдий (1.4.163): ранги, состав, казна и войны.
 *
 * <p>Здесь проверяется то, что нельзя чинить руками на живом сервере: не бывает
 * двух лидеров, лидера нельзя выгнать, казна не уходит в минус, а пара игроков
 * не накручивает очки войны чаще окна.</p>
 */
class GuildModelTest {

    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OFFICER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final long NOW = 1_792_000_000_000L;

    private static Guild guild() {
        Guild guild = new Guild("g1", "  Мой   Орден ", "ab", LEADER, NOW);
        guild.addMember(OFFICER, GuildRank.OFFICER);
        guild.addMember(MEMBER, GuildRank.MEMBER);
        return guild;
    }

    /* ---------------------------------- ранги --------------------------------- */

    @Test
    void ranksWeightsAndRights() {
        assertTrue(GuildRank.LEADER.weight() > GuildRank.OFFICER.weight());
        assertTrue(GuildRank.OFFICER.weight() > GuildRank.MEMBER.weight());
        assertTrue(GuildRank.LEADER.canInvite());
        assertTrue(GuildRank.OFFICER.canInvite());
        assertFalse(GuildRank.MEMBER.canInvite());
        assertTrue(GuildRank.LEADER.canKick(GuildRank.OFFICER));
        assertTrue(GuildRank.OFFICER.canKick(GuildRank.MEMBER));
        assertFalse(GuildRank.OFFICER.canKick(GuildRank.LEADER));
        assertFalse(GuildRank.MEMBER.canKick(GuildRank.MEMBER));
        assertFalse(GuildRank.LEADER.canKick(null));
        assertTrue(GuildRank.LEADER.canManageGuild());
        assertFalse(GuildRank.OFFICER.canManageGuild());
        assertEquals("leader", GuildRank.LEADER.id());
        assertEquals(GuildRank.MEMBER, GuildRank.parse(null));
        assertEquals(GuildRank.MEMBER, GuildRank.parse("неведомый"));
        assertEquals(GuildRank.OFFICER, GuildRank.parse(" officer "));
    }

    /* --------------------------------- создание -------------------------------- */

    @Test
    void guildCleansNameAndTag() {
        Guild guild = guild();
        assertEquals("Мой Орден", guild.name());
        assertEquals("AB", guild.tag());
        assertEquals(LEADER, guild.leader());
        assertEquals(3, guild.size());
        assertEquals(GuildRank.LEADER, guild.rankOf(LEADER));
        assertEquals(GuildRank.OFFICER, guild.rankOf(OFFICER));
        assertEquals(1, guild.level());
        assertEquals(0L, guild.treasury());
        assertTrue(guild.isMember(MEMBER));
        assertFalse(guild.isMember(UUID.randomUUID()));
        assertEquals("Мой Орден [AB]", guild.label());
        assertFalse(guild.abandoned());
    }

    @Test
    void nameAndTagAreTrimmedToLimits() {
        Guild guild = new Guild("g", "Очень длинное название гильдии", "TOOLONG", LEADER, NOW);
        assertEquals(Guild.MAX_NAME, guild.name().length());
        assertEquals(Guild.MAX_TAG, guild.tag().length());
        assertEquals("TOOLO", guild.tag());
        assertEquals("", new Guild("g2", "", "", null, NOW).tag());
        assertEquals(Guild.MAX_NAME, new Guild("g3", "я".repeat(50), "a", null, NOW)
                .name().length());
    }

    @Test
    void duplicateMemberIsIgnored() {
        Guild guild = guild();
        assertFalse(guild.addMember(MEMBER, GuildRank.OFFICER));
        assertEquals(GuildRank.MEMBER, guild.rankOf(MEMBER), "ранг не меняется самовольно");
        UUID extra = UUID.randomUUID();
        assertTrue(guild.addMember(extra, GuildRank.LEADER));
        assertEquals(GuildRank.MEMBER, guild.rankOf(extra),
                "вторым лидером стать нельзя — новый участник рядовой");
    }

    @Test
    void leaderCannotBeRemovedOrDemoted() {
        Guild guild = guild();
        assertFalse(guild.removeMember(LEADER));
        assertFalse(guild.setRank(LEADER, GuildRank.MEMBER));
        assertTrue(guild.removeMember(MEMBER));
        assertFalse(guild.isMember(MEMBER));
        assertFalse(guild.removeMember(MEMBER), "повторно убрать нельзя");
    }

    @Test
    void transferMovesLeaderToOfficer() {
        Guild guild = guild();
        assertTrue(guild.transferLeader(OFFICER));
        assertEquals(OFFICER, guild.leader());
        assertEquals(GuildRank.LEADER, guild.rankOf(OFFICER));
        assertEquals(GuildRank.OFFICER, guild.rankOf(LEADER));
        assertFalse(guild.transferLeader(OFFICER), "передать самому себе нельзя");
        assertFalse(guild.transferLeader(UUID.randomUUID()), "только участнику");
    }

    @Test
    void restoreFixesLeaderAndClamps() {
        Guild guild = new Guild("g", "Имя", "T", LEADER, NOW);
        guild.addMember(MEMBER, GuildRank.MEMBER);
        guild.removeMember(LEADER);
        guild.restore(9_999_999L, 99, -5, -2, -1);
        assertEquals(Guild.MAX_TREASURY, guild.treasury());
        assertEquals(GuildMath.MAX_LEVEL, guild.level());
        assertEquals(0, guild.glory());
        assertEquals(0, guild.warsWon());
        assertEquals(0, guild.warsLost());
        assertEquals(GuildRank.LEADER, guild.rankOf(LEADER),
                "лидер, которого нет в составе, добавляется обратно");
    }

    /* ---------------------------------- казна --------------------------------- */

    @Test
    void treasuryNeverGoesNegativeOrOverCap() {
        Guild guild = guild();
        assertEquals(0L, guild.deposit(0));
        assertEquals(0L, guild.deposit(-5));
        assertEquals(100L, guild.deposit(100));
        assertEquals(50L, guild.withdraw(50));
        assertEquals(50L, guild.treasury());
        assertEquals(50L, guild.withdraw(1_000), "снять больше остатка нельзя");
        assertEquals(0L, guild.treasury());
        assertEquals(0L, guild.withdraw(10));
        guild.deposit(Guild.MAX_TREASURY + 500);
        assertEquals(Guild.MAX_TREASURY, guild.treasury());
        assertEquals(0L, guild.deposit(10), "казна полна");
    }

    /* --------------------------------- уровень -------------------------------- */

    @Test
    void levelStopsAtMaximum() {
        Guild guild = guild();
        for (int index = 0; index < GuildMath.MAX_LEVEL + 3; index++) {
            guild.raiseLevel(GuildMath.MAX_LEVEL);
        }
        assertEquals(GuildMath.MAX_LEVEL, guild.level());
    }

    /* ---------------------------------- войны --------------------------------- */

    @Test
    void warPointsCountOncePerWindow() {
        GuildWar war = new GuildWar("w1", "a", "b", 64, NOW);
        assertEquals(0, war.addKill("a", LEADER, MEMBER, false, NOW, 300_000L),
                "до старта очки не идут");
        war.activate(NOW, 3_600);
        assertEquals(1, war.addKill("a", LEADER, MEMBER, false, NOW, 300_000L));
        assertEquals(0, war.addKill("a", LEADER, MEMBER, false, NOW + 1_000, 300_000L),
                "та же пара не накручивает счёт");
        assertEquals(1, war.addKill("a", LEADER, MEMBER, false, NOW + 300_000L, 300_000L));
        assertEquals(2, war.challengerPoints(), "два очка за два разных окна");
        assertEquals(2, war.addKill("a", OFFICER, MEMBER, true, NOW + 400_000L, 300_000L),
                "бой на приватах даёт два очка");
        assertEquals(0, war.addKill("c", OFFICER, MEMBER, true, NOW + 500_000L, 300_000L),
                "чужая гильдия очков не получает");
    }

    @Test
    void warPairIsDirectionless() {
        GuildWar war = new GuildWar("w1", "a", "b", 64, NOW);
        war.activate(NOW, 3_600);
        assertEquals(1, war.addKill("a", LEADER, MEMBER, false, NOW, 300_000L));
        assertEquals(0, war.addKill("a", MEMBER, LEADER, false, NOW + 1_000, 300_000L),
                "обратная пара — та же пара");
    }

    @Test
    void warFinishesWithResult() {
        GuildWar war = new GuildWar("w1", "a", "b", 64, NOW);
        assertTrue(war.pending());
        assertEquals(GuildWar.Result.DRAW, war.finish());
        assertEquals(GuildWar.Status.FINISHED, war.status());
        assertTrue(war.finished());
        assertEquals(GuildWar.Result.DRAW, war.finish(), "результат не меняется");

        GuildWar win = new GuildWar("w2", "a", "b", 64, NOW);
        win.activate(NOW, 3_600);
        win.addKill("b", LEADER, MEMBER, false, NOW, 0L);
        assertEquals(GuildWar.Result.TARGET_WIN, win.finish());
        assertEquals("b", win.leader());
        assertEquals("b", win.opponentOf("a"));
        assertNull(win.opponentOf("c"));
        assertTrue(win.involves("a"));
        assertFalse(win.involves("z"));
    }

    @Test
    void warCancelKeepsStakeInfo() {
        GuildWar war = new GuildWar("w1", "a", "b", 128, NOW);
        war.cancel(NOW + 5_000);
        assertEquals(GuildWar.Result.CANCELLED, war.result());
        assertEquals(128L, war.wager());
        assertEquals(NOW + 5_000, war.endsAt());
    }

    @Test
    void warRestoreClampsPoints() {
        GuildWar war = new GuildWar("w1", "a", "b", 64, NOW);
        war.restore(GuildWar.Status.ACTIVE, null, NOW, NOW + 1_000, -5, -7,
                java.util.Map.of("PAIR", 10L, "", -1L));
        assertEquals(0, war.challengerPoints());
        assertEquals(0, war.targetPoints());
        assertTrue(war.active());
        assertEquals(1, war.lastKills().size(), "битые ключи отбрасываются");
    }

    @Test
    void inviteExpires() {
        GuildInvite invite = new GuildInvite("g1", "Орден", MEMBER, LEADER, NOW, NOW + 60_000);
        assertFalse(invite.expired(NOW));
        assertTrue(invite.expired(NOW + 60_000));
        assertEquals(60, invite.remainingSeconds(NOW));
        assertEquals(0, invite.remainingSeconds(NOW + 90_000));
        assertFalse(new GuildInvite("g1", "Орден", MEMBER, LEADER, NOW, 0).expired(NOW + 1));
    }
}
