package ru.cherdakmd.chrdkguilds;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила гильдий (1.4.163): создание, приглашения, ранги, казна, уровни и войны.
 *
 * <p>Проверяется без сервера: кошелёк подменяется заглушкой, поэтому видно, что
 * алмазы не списываются дважды и не уходят в минус.</p>
 */
class GuildServiceTest {

    private static final Logger LOGGER = Logger.getLogger("GuildServiceTest");
    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID RIVAL = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final long NOW = 1_792_000_000_000L;

    /** Кошелёк-заглушка: помнит выданное и списанное. */
    private static final class Purse implements GuildPurse {
        private final Map<UUID, Long> balance = new HashMap<>();
        private long taken;
        private long given;

        Purse put(UUID player, long amount) {
            balance.put(player, amount);
            return 0;
        }

        @Override
        public long take(UUID player, long amount) {
            long have = balance.getOrDefault(player, 0L);
            long take = Math.min(have, amount);
            balance.put(player, have - take);
            taken += take;
            return take;
        }

        @Override
        public long give(UUID player, long amount) {
            balance.merge(player, amount, Long::sum);
            given += amount;
            return 0L;
        }
    }

    private static GuildConfig config(String yaml) {
        YamlConfiguration source = new YamlConfiguration();
        try {
            source.loadFromString(yaml);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return GuildConfig.parse(source, LOGGER);
    }

    private static GuildService service(GuildConfig config, GuildStore store) {
        return new GuildService(config, store);
    }

    private static GuildStore store(Path dir) {
        GuildStore store = new GuildStore(dir, LOGGER);
        store.load();
        return store;
    }

    /* --------------------------------- создание -------------------------------- */

    @Test
    void createTakesDiamondsAndRejectsInvalidNames(@TempDir Path dir) {
        GuildService service = service(GuildConfig.defaults(), store(dir));
        Purse purse = new Purse();
        purse.put(LEADER, 100);

        assertEquals("name-short", service.create(purse, LEADER, "ab", "AB", NOW).key());
        assertEquals("tag-short", service.create(purse, LEADER, "Орден", "A", NOW).key());
        assertEquals("name-long", service.create(purse, LEADER, "я".repeat(40), "AB", NOW).key());
        assertEquals("tag-long", service.create(purse, LEADER, "Орден", "ABCDEFG", NOW).key());

        GuildResult created = service.create(purse, LEADER, "Орден", "ord", NOW);
        assertTrue(created.ok(), created.key());
        assertEquals("Орден", created.args().get(1));
        assertEquals("ORD", created.args().get(3), "тег приводится к заглавным");
        assertEquals(100 - 27, purse.balance.get(LEADER));
        assertEquals(27, purse.taken);

        Guild guild = service.guildOf(LEADER);
        assertNotNull(guild);
        assertEquals("ORD", guild.tag());
        assertEquals("already-in-guild", service.create(purse, LEADER, "Другой", "DR", NOW).key());
    }

    @Test
    void createWithoutDiamondsChangesNothing(@TempDir Path dir) {
        GuildService service = service(GuildConfig.defaults(), store(dir));
        Purse purse = new Purse();
        purse.put(LEADER, 10);
        GuildResult result = service.create(purse, LEADER, "Орден", "ORD", NOW);
        assertFalse(result.ok());
        assertEquals("create-no-diamonds", result.key());
        assertEquals(10, purse.balance.get(LEADER), "недостача не списывается");
        assertNull(service.guildOf(LEADER));
        assertEquals(0, service.count());
    }

    @Test
    void namesAndTagsAreUnique(@TempDir Path dir) {
        GuildService service = service(GuildConfig.defaults(), store(dir));
        Purse purse = new Purse();
        purse.put(LEADER, 100);
        purse.put(RIVAL, 100);
        assertTrue(service.create(purse, LEADER, "Орден", "ORD", NOW).ok());
        assertEquals("name-taken", service.create(purse, RIVAL, "орден", "TWO", NOW).key());
        assertEquals("tag-taken", service.create(purse, RIVAL, "Другой", "ord", NOW).key());
        assertTrue(service.create(purse, RIVAL, "Другой", "TWO", NOW).ok());
        assertEquals(2, service.count());
    }

    @Test
    void freeCreationSkipsPurse(@TempDir Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                """), store(dir));
        assertTrue(service.create(null, LEADER, "Орден", "ORD", NOW).ok());
        assertEquals(1, service.count());
    }

    /* ------------------------------- приглашения ------------------------------- */

    @Test
    void inviteRespectsRankAndCapacity(@TempDir Path dir) {
        GuildService service = service(GuildConfig.defaults(), store(dir));
        Purse purse = new Purse();
        purse.put(LEADER, 100);
        service.create(purse, LEADER, "Орден", "ORD", NOW);

        assertEquals("target-self", service.invite(LEADER, LEADER, NOW).key());
        assertEquals("invite-sent", service.invite(LEADER, SECOND, NOW).key());
        assertEquals("join-ok", service.accept(SECOND, NOW + 1_000).key());
        assertEquals(2, service.guildOf(LEADER).size());
        assertEquals("target-in-guild", service.invite(LEADER, SECOND, NOW).key());
        assertEquals("invite-no-rank", service.invite(SECOND, THIRD, NOW).key(),
                "рядовой приглашать не может");

        assertTrue(service.promote(LEADER, SECOND).ok());
        assertEquals("invite-sent", service.invite(SECOND, THIRD, NOW).key(),
                "офицер приглашать может");
        assertEquals("already-in-guild", service.accept(LEADER, NOW).key());
    }

    @Test
    void inviteExpires(@TempDir Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                  invite-seconds: 30
                """), store(dir));
        service.create(null, LEADER, "Орден", "ORD", NOW);
        assertEquals("invite-sent", service.invite(LEADER, SECOND, NOW).key());
        assertEquals("invite-expired", service.accept(SECOND, NOW + 31_000).key());
        assertEquals("invite-none", service.accept(SECOND, NOW + 32_000).key());
    }

    @Test
    void guildFullBlocksInvite(@TempDir Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                """), store(dir));
        service.create(null, LEADER, "Орден", "ORD", NOW);
        Guild guild = service.guildOf(LEADER);
        for (int index = 0; index < GuildMath.memberCap(1) - 1; index++) {
            guild.addMember(UUID.randomUUID(), GuildRank.MEMBER);
        }
        assertEquals("guild-full", service.invite(LEADER, SECOND, NOW).key());
    }

    /* ---------------------------------- состав -------------------------------- */

    @Test
    void ranksAndKicksFollowWeight(@TempDir Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                """), store(dir));
        service.create(null, LEADER, "Орден", "ORD", NOW);
        service.invite(LEADER, SECOND, NOW);
        service.accept(SECOND, NOW);
        service.invite(LEADER, THIRD, NOW);
        service.accept(THIRD, NOW);

        assertEquals("rank-no-rights", service.promote(SECOND, THIRD).key(),
                "рядовой не повышает");
        assertTrue(service.promote(LEADER, THIRD).ok());
        assertEquals("rank-max", service.promote(LEADER, THIRD).key());
        assertTrue(service.demote(LEADER, THIRD).ok());
        assertEquals("rank-min", service.demote(LEADER, THIRD).key());

        assertTrue(service.promote(LEADER, SECOND).ok());
        assertEquals("kick-no-rank", service.kick(SECOND, LEADER).key());
        assertEquals("kick-no-rank", service.kick(SECOND, SECOND).key(),
                "себя выгнать нельзя: ранг не ниже");
        assertTrue(service.kick(LEADER, THIRD).ok());
        assertEquals(2, service.guildOf(LEADER).size());
    }

    @Test
    void leaderLeavesOnlyThroughTransfer(@TempDir Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                """), store(dir));
        service.create(null, LEADER, "Орден", "ORD", NOW);
        service.invite(LEADER, SECOND, NOW);
        service.accept(SECOND, NOW);

        assertEquals("leave-leader", service.leave(LEADER).key());
        assertEquals("transfer-no-target", service.transfer(LEADER, THIRD).key());
        assertTrue(service.transfer(LEADER, SECOND).ok());
        assertEquals(SECOND, service.guildOf(SECOND).leader());
        assertTrue(service.leave(LEADER).ok());
        assertNull(service.guildOf(LEADER));
        assertEquals(1, service.guildOf(SECOND).size());
    }

    @Test
    void disbandRemovesGuildAndTreasury(@TempDir Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                """), store(dir));
        service.create(null, LEADER, "Орден", "ORD", NOW);
        Guild guild = service.guildOf(LEADER);
        guild.deposit(60);
        GuildResult result = service.disband(LEADER);
        assertTrue(result.ok());
        assertEquals("60", result.args().get(3));
        assertEquals("disband-war-none", result.args().get(7));
        assertEquals(0, service.count());
        assertNull(service.guildOf(LEADER));
    }

    /* ---------------------------------- казна --------------------------------- */

    @Test
    void depositsAndWithdrawalsRespectRanksAndCap(@TempDir Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                """), store(dir));
        service.create(null, LEADER, "Орден", "ORD", NOW);
        service.invite(LEADER, SECOND, NOW);
        service.accept(SECOND, NOW);
        Purse purse = new Purse();
        purse.put(LEADER, 500);
        purse.put(SECOND, 50);

        assertEquals("deposit-empty", service.deposit(purse, LEADER, 0).key());
        GuildResult deposit = service.deposit(purse, SECOND, 30);
        assertTrue(deposit.ok());
        assertEquals(30L, service.guildOf(LEADER).treasury());
        assertEquals(20, purse.balance.get(SECOND));

        assertEquals("withdraw-leader-only", service.withdraw(purse, SECOND, 10).key());
        GuildResult withdraw = service.withdraw(purse, LEADER, 10);
        assertTrue(withdraw.ok());
        assertEquals(20L, service.guildOf(LEADER).treasury());
        assertEquals(10, purse.balance.get(LEADER));

        assertEquals("deposit-no-diamonds", service.deposit(purse, SECOND, 999).key());
        assertEquals(20L, service.guildOf(LEADER).treasury(), "недостача казну не меняет");
    }

    @Test
    void depositStopsAtTreasuryCap(@TempDir Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                """), store(dir));
        service.create(null, LEADER, "Орден", "ORD", NOW);
        Guild guild = service.guildOf(LEADER);
        guild.deposit(Guild.MAX_TREASURY - 5);
        Purse purse = new Purse();
        purse.put(LEADER, 100);

        GuildResult result = service.deposit(purse, LEADER, 50);
        assertTrue(result.ok());
        assertEquals(Guild.MAX_TREASURY, guild.treasury());
        assertEquals(95, purse.balance.get(LEADER), "лишнее вернулось бы игроку");
        assertEquals(0, purse.given, "взяли ровно столько, сколько поместилось");
        assertEquals("treasury-full", service.deposit(purse, LEADER, 1).key());
    }

    @Test
    void upgradeChargesTreasuryAndRaisesCap(@TempDir Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                """), store(dir));
        service.create(null, LEADER, "Орден", "ORD", NOW);
        Guild guild = service.guildOf(LEADER);

        assertEquals("upgrade-no-money", service.upgrade(LEADER).key());
        guild.deposit(64);
        GuildResult result = service.upgrade(LEADER);
        assertTrue(result.ok(), result.key());
        assertEquals(2, guild.level());
        assertEquals(0L, guild.treasury());
        assertEquals("II", result.args().get(1));
        assertEquals("14", result.args().get(3));

        guild.deposit(2_000);
        service.upgrade(LEADER);
        service.upgrade(LEADER);
        service.upgrade(LEADER);
        assertEquals(GuildMath.MAX_LEVEL, guild.level());
        assertEquals(656L, guild.treasury(), "192 + 384 + 768 ушли из казны");
        assertEquals("upgrade-max", service.upgrade(LEADER).key());
    }

    /* ---------------------------------- войны --------------------------------- */

    private static GuildService warService(Path dir) {
        GuildService service = service(config("""
                general:
                  creation-cost: 0
                wars:
                  min-wager: 32
                  max-wager: 256
                  duration-seconds: 600
                  accept-seconds: 60
                  cooldown-seconds: 120
                  kill-window-seconds: 60
                """), store(dir));
        service.create(null, LEADER, "Орден", "ORD", NOW);
        service.create(null, RIVAL, "Риваль", "RIV", NOW);
        service.guildOf(LEADER).deposit(200);
        service.guildOf(RIVAL).deposit(200);
        return service;
    }

    @Test
    void warNeedsStakeAndConsent(@TempDir Path dir) {
        GuildService service = warService(dir);
        assertEquals("war-no-target", service.declareWar(LEADER, "неведомая", 64, NOW).key());
        assertEquals("war-wager-max", service.declareWar(LEADER, "Риваль", 999, NOW).key());

        GuildResult declared = service.declareWar(LEADER, "Риваль", 64, NOW);
        assertTrue(declared.ok(), declared.key());
        assertEquals(136L, service.guildOf(LEADER).treasury(), "ставка ушла из казны");
        assertEquals("war-busy", service.declareWar(LEADER, "Риваль", 64, NOW).key());

        assertEquals("war-accept-target-only", service.acceptWar(LEADER, NOW).key());
        assertEquals("war-busy", service.declareWar(RIVAL, "Орден", 64, NOW).key(),
                "гильдия уже участвует в войне");
        assertEquals("war-accept-none", service.declineWar(LEADER, NOW).key(),
                "объявившая сторона не может отклонить собственную войну");

        GuildResult started = service.acceptWar(RIVAL, NOW + 1_000);
        assertTrue(started.ok(), started.key());
        assertEquals(136L, service.guildOf(RIVAL).treasury());
        assertTrue(service.warOf(service.guildOf(LEADER).id()).active());
    }

    @Test
    void warPointsAndSettlement(@TempDir Path dir) {
        GuildService service = warService(dir);
        service.declareWar(LEADER, "Риваль", 64, NOW);
        service.acceptWar(RIVAL, NOW);

        GuildResult point = service.kill(LEADER, RIVAL, false, NOW + 1_000);
        assertTrue(point.ok(), point.key());
        assertEquals("1", point.args().get(1));
        assertFalse(service.kill(LEADER, RIVAL, false, NOW + 2_000).ok(),
                "та же пара не накручивает счёт");
        assertFalse(service.kill(LEADER, LEADER, false, NOW + 2_000).ok());
        assertFalse(service.kill(LEADER, THIRD, false, NOW + 2_000).ok(),
                "игрок без гильдии в войне не участвует");

        GuildResult finish = service.tick(NOW + 601_000).stream()
                .filter(result -> result.key().startsWith("war-finished"))
                .findFirst().orElseThrow();
        assertEquals("war-finished-win", finish.key());
        assertEquals("Орден", finish.args().get(5));
        assertEquals(136L + 128L, service.guildOf(LEADER).treasury(), "победитель забрал обе ставки");
        assertEquals(136L, service.guildOf(RIVAL).treasury());
        assertEquals(1, service.guildOf(LEADER).warsWon());
        assertEquals(1, service.guildOf(RIVAL).warsLost());
        assertEquals(GuildMath.GLORY_PER_WIN, service.guildOf(LEADER).glory());
        assertTrue(service.guildOf(RIVAL).warCooldownUntil() > NOW);
    }

    @Test
    void warDeclineReturnsStakeAndSetsCooldown(@TempDir Path dir) {
        GuildService service = warService(dir);
        service.declareWar(LEADER, "Риваль", 64, NOW);
        GuildResult declined = service.declineWar(RIVAL, NOW + 1_000);
        assertTrue(declined.ok(), declined.key());
        assertEquals(200L, service.guildOf(LEADER).treasury(), "ставка вернулась");
        assertTrue(service.guildOf(LEADER).warCooldownUntil() > NOW);
        assertEquals("war-cooldown", service.declareWar(LEADER, "Риваль", 64,
                NOW + 2_000).key());
    }

    @Test
    void warExpiresWhenNobodyAnswers(@TempDir Path dir) {
        GuildService service = warService(dir);
        service.declareWar(LEADER, "Риваль", 64, NOW);
        List<GuildResult> notices = service.tick(NOW + 61_000);
        assertTrue(notices.stream().anyMatch(result -> result.key().equals("war-expired")));
        assertEquals(200L, service.guildOf(LEADER).treasury());
        assertEquals(0, service.store().wars().size());
    }

    @Test
    void surrenderGivesVictoryWithoutTimer(@TempDir Path dir) {
        GuildService service = warService(dir);
        service.declareWar(LEADER, "Риваль", 64, NOW);
        service.acceptWar(RIVAL, NOW);
        GuildResult surrender = service.surrenderWar(LEADER, NOW + 5_000);
        assertTrue(surrender.ok(), surrender.key());
        assertEquals("Риваль [RIV]", surrender.args().get(3), "в тексте видно тег гильдии");
        assertEquals(1, service.guildOf(RIVAL).warsWon());
    }

    @Test
    void warCooldownBlocksDeclaration(@TempDir Path dir) {
        GuildService service = warService(dir);
        service.declareWar(LEADER, "Риваль", 64, NOW);
        service.acceptWar(RIVAL, NOW);
        service.kill(RIVAL, LEADER, false, NOW + 1_000);
        service.tick(NOW + 601_000);
        assertEquals("war-cooldown", service.declareWar(RIVAL, "Орден", 32,
                NOW + 602_000).key());
        assertTrue(service.clearCooldown(service.guildOf(RIVAL)));
        assertFalse(service.clearCooldown(service.guildOf(RIVAL)),
                "второй раз снимать нечего");
    }
}
