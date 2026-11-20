package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkskills.SupportTargetRules.*;

class SupportTargetRulesTest {
    private Evidence evidence(boolean hit, boolean sameWorld, boolean alive, boolean visible,
                              boolean line, double distance, GuildRelation guild,
                              GroupRelation group, Protection protection) {
        return new Evidence(hit, sameWorld, alive, visible, line, distance, guild, group, protection);
    }
    private Evidence valid(GuildRelation guild, GroupRelation group, Protection protection) {
        return evidence(true, true, true, true, true, 64.0, guild, group, protection);
    }
    @Test void guildOrGroupMayQualifyButWarAndUnknownWarTakePrecedence() {
        assertEquals(Result.EXTERNAL_TARGET, decide(valid(GuildRelation.SAME_GUILD, GroupRelation.DIFFERENT, Protection.ALLOW)));
        assertEquals(Result.EXTERNAL_TARGET, decide(valid(GuildRelation.OTHER, GroupRelation.SAME_GROUP, Protection.ALLOW)));
        assertEquals(Result.SELF, decide(valid(GuildRelation.OTHER, GroupRelation.DIFFERENT, Protection.ALLOW)));
        assertEquals(Result.SELF, decide(valid(GuildRelation.OTHER, GroupRelation.UNKNOWN, Protection.ALLOW)));
        assertEquals(Result.SELF, decide(valid(GuildRelation.ACTIVE_WAR, GroupRelation.SAME_GROUP, Protection.ALLOW)));
        assertEquals(Result.SELF, decide(valid(GuildRelation.UNKNOWN, GroupRelation.SAME_GROUP, Protection.ALLOW)));
        assertEquals(Result.SELF, decide(valid(null, GroupRelation.SAME_GROUP, Protection.ALLOW)));
    }
    @Test void explicitProtectionPermissionIsMandatory() {
        for (var guild : new GuildRelation[]{GuildRelation.SAME_GUILD, GuildRelation.OTHER}) {
            for (var protection : new Protection[]{Protection.UNKNOWN, Protection.DENY}) {
                assertEquals(Result.SELF, decide(valid(guild, GroupRelation.SAME_GROUP, protection)));
            }
            assertEquals(Result.SELF, decide(valid(guild, GroupRelation.SAME_GROUP, null)));
        }
    }
    @Test void aimWorldVisibilityAndLineOfSightFailClosed() {
        assertEquals(Result.SELF, decide(null));
        assertEquals(Result.SELF, decide(evidence(false,true,true,true,true,1,GuildRelation.SAME_GUILD,GroupRelation.DIFFERENT,Protection.ALLOW)));
        assertEquals(Result.SELF, decide(evidence(true,false,true,true,true,1,GuildRelation.SAME_GUILD,GroupRelation.DIFFERENT,Protection.ALLOW)));
        assertEquals(Result.SELF, decide(evidence(true,true,false,true,true,1,GuildRelation.SAME_GUILD,GroupRelation.DIFFERENT,Protection.ALLOW)));
        assertEquals(Result.SELF, decide(evidence(true,true,true,false,true,1,GuildRelation.SAME_GUILD,GroupRelation.DIFFERENT,Protection.ALLOW)));
        assertEquals(Result.SELF, decide(evidence(true,true,true,true,false,1,GuildRelation.SAME_GUILD,GroupRelation.DIFFERENT,Protection.ALLOW)));
    }
    @Test void distanceMustBeFiniteNonnegativeAndAtMostEightBlocks() {
        for (double d : new double[]{-1, Math.nextUp(64.0), Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(Result.SELF, decide(evidence(true,true,true,true,true,d,GuildRelation.SAME_GUILD,GroupRelation.DIFFERENT,Protection.ALLOW)));
        }
        for (double d : new double[]{0, 1, 64.0}) {
            assertEquals(Result.EXTERNAL_TARGET, decide(evidence(true,true,true,true,true,d,GuildRelation.SAME_GUILD,GroupRelation.DIFFERENT,Protection.ALLOW)));
        }
    }
}
