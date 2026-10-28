package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneMigrationTest {
    @TempDir Path dir;
    final UUID owner=UUID.randomUUID();
    Path file() { return dir.resolve("legacy.json"); }
    String data(String claims) { return "{\"version\":1,\"players\":{\""+owner+"\":{\"claimed\":"+claims+",\"unlocked\":[],\"counters\":{},\"xp\":{},\"discovered\":[],\"firstFound\":0,\"lastFound\":0}}}"; }
    @Test void missingFileIsNewInstallButInvalidJsonAndMissingClaimsNeverMeanEmptyLedger() throws Exception {
        assertTrue(MilestoneMigration.json(file(),MilestoneMigration.Kind.ARTIFACTS).isEmpty());
        for(String bad:List.of("{[","null","{}","{\"version\":1,\"players\":null}","{\"version\":1,\"players\":{\""+owner+"\":{}}}")) {
            Files.writeString(file(),bad); assertThrows(IOException.class,()->MilestoneMigration.json(file(),MilestoneMigration.Kind.ARTIFACTS)); assertEquals(bad,Files.readString(file()));
        }
    }
    @Test void oldArtifactPercentAndAchievementIdHaveDistinctStableKeys() throws Exception {
        Files.writeString(file(),data("[25,50]")); assertEquals(Set.of("percent:25","percent:50"),MilestoneMigration.json(file(),MilestoneMigration.Kind.ARTIFACTS).get(owner));
        Files.writeString(file(),data("[\"first_diamond\",\"old_deleted_id\"]")); assertEquals(Set.of("first_diamond","old_deleted_id"),MilestoneMigration.json(file(),MilestoneMigration.Kind.ACHIEVEMENTS).get(owner));
    }
    @Test void skillsRejectFractionalOrClampedMilestoneAndNoncanonicalPlayer() throws Exception {
        for(String list:List.of("[0]","[-1]","[10.5]","[2147483648]","[10,10]","[\"10\"]")) {
            Files.writeString(file(),data(list).replace("claimed","milestones")); assertThrows(IOException.class,()->MilestoneMigration.json(file(),MilestoneMigration.Kind.SKILLS));
        }
        Files.writeString(file(),data("[25]").replace(owner.toString(),"0-0-0-0-1")); assertThrows(IOException.class,()->MilestoneMigration.json(file(),MilestoneMigration.Kind.ARTIFACTS));
    }
    @Test void artifactBoundsAndWrongClaimTypeDoNotSilentlyClampOrSkip() throws Exception {
        for(String list:List.of("[0]","[101]","[25.5]","[25,null]","null","{}")) {
            Files.writeString(file(),data(list)); assertThrows(IOException.class,()->MilestoneMigration.json(file(),MilestoneMigration.Kind.ARTIFACTS));
        }
    }
    @Test void tierForgeClaimsReadFromActualCollectionSectionAndInvalidYamlIsFatal() throws Exception {
        var path=dir.resolve("stats.yml"); Files.writeString(path,"players: {}\ncollection:\n  "+owner+":\n    claimed: ['properties:10', 'section:tiers']\n    tiers: []\n    properties: []\n    affixes: []\n");
        assertEquals(Set.of("properties:10","section:tiers"),MilestoneMigration.tierForge(path).get(owner));
        for(String bad:List.of("collection: [oops]","collection:\n  "+owner+": {claimed: [1]}","collection:\n  "+owner+": {claimed: ['section:tiers', 'section:tiers']}","collection: [")) {
            Files.writeString(path,bad); assertThrows(IOException.class,()->MilestoneMigration.tierForge(path)); assertEquals(bad,Files.readString(path));
        }
    }
    @Test void malformedProgressCannotPassFinancialPreflightThenBeSavedEmpty() throws Exception {
        for(var kind:MilestoneMigration.Kind.values()) {
            String good=data("[]");
            String bad=switch(kind) {
                case SKILLS -> good.replace("\"xp\":{}","\"xp\":[]").replace("\"claimed\"","\"milestones\"");
                case ACHIEVEMENTS -> good.replace("\"unlocked\":[]","\"unlocked\":false");
                case ARTIFACTS -> good.replace("\"discovered\":[]","\"discovered\":false");
            };
            Files.writeString(file(),bad); assertThrows(IOException.class,()->MilestoneMigration.json(file(),kind)); assertEquals(bad,Files.readString(file()));
        }
    }

}
