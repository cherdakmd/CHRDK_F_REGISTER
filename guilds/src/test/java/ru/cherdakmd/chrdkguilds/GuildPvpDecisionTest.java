package ru.cherdakmd.chrdkguilds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GuildPvpDecisionTest {
    @Test void battleAndClanlessAttackersAreFinedForProtectedVictim(){assertEquals(GuildPvpDecision.BLOCK_AND_FINE,GuildPvpDecision.decide(false,false,false,true,true));}
    @Test void peacefulMemberCannotAttackEvenUnprotectedPlayer(){assertEquals(GuildPvpDecision.BLOCK,GuildPvpDecision.decide(false,true,false,false,true));}
    @Test void peacefulProjectileStaysNonCombatAfterLeavingClan(){assertEquals(GuildPvpDecision.BLOCK,GuildPvpDecision.decide(false,false,true,false,true));}
    @Test void peacefulPlayersCannotDuelEachOther(){assertEquals(GuildPvpDecision.BLOCK,GuildPvpDecision.decide(false,true,false,true,true));}
    @Test void battlePvPIsUnchanged(){assertEquals(GuildPvpDecision.ALLOW,GuildPvpDecision.decide(false,false,false,false,true));}
    @Test void petRetaliationIsStoppedWithoutBaitableOwnerFine(){assertEquals(GuildPvpDecision.BLOCK,GuildPvpDecision.decide(false,false,false,true,false));}
    @Test void selfDamageIsNotPvP(){assertEquals(GuildPvpDecision.ALLOW,GuildPvpDecision.decide(true,true,true,true,true));}
}
