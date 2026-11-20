package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkguilds.AdventureGroupRelation;
import ru.cherdakmd.chrdkguilds.GuildRelation;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SupportRelationsTest {
    enum Fake { SAME_GUILD, SAME_GROUP }

    @Test void mapsAllActualGuildAndGroupStates() {
        for (var guild : GuildRelation.values()) for (var group : AdventureGroupRelation.values()) {
            var result = SupportRelations.translate(guild, group);
            assertEquals(guild.name(), result.guild().name());
            assertEquals(group.name(), result.group().name());
        }
    }
    @Test void missingOrUntrustedValuesDoNotGrantAccess() {
        var r = SupportRelations.translate(Fake.SAME_GUILD, Fake.SAME_GROUP);
        assertEquals(SupportTargetRules.GuildRelation.UNKNOWN, r.guild());
        assertEquals(SupportTargetRules.GroupRelation.UNKNOWN, r.group());
        r = SupportRelations.translate(null, "SAME_GROUP");
        assertEquals(SupportTargetRules.GuildRelation.UNKNOWN, r.guild());
        assertEquals(SupportTargetRules.GroupRelation.UNKNOWN, r.group());
        r = SupportRelations.read(new Object(), UUID.randomUUID(), UUID.randomUUID());
        assertEquals(SupportTargetRules.GuildRelation.UNKNOWN, r.guild());
        assertEquals(SupportTargetRules.GroupRelation.UNKNOWN, r.group());
        r = SupportRelations.read(null, UUID.randomUUID(), UUID.randomUUID());
        assertEquals(SupportTargetRules.GuildRelation.UNKNOWN, r.guild());
    }
}
