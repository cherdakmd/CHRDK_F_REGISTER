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

    @Test void openedIdsAreValidatedAndRetainedEvenWhenNotClaimed() throws Exception {
        Files.writeString(file(),data("[]").replace("\"unlocked\":[]","\"unlocked\":[\"first\",\"second\"]"));
        assertEquals(Set.of("first","second"),MilestoneMigration.progressIds(file(),MilestoneMigration.Kind.ACHIEVEMENTS).get(owner));
        String duplicate=data("[]").replace("\"unlocked\":[]","\"unlocked\":[\"first\",\"first\"]");
        Files.writeString(file(),duplicate);
        assertThrows(IOException.class,()->MilestoneMigration.progressIds(file(),MilestoneMigration.Kind.ACHIEVEMENTS));
        assertEquals(duplicate,Files.readString(file()));
    }
    @Test void duplicateFinancialKeysAreNeverSilentlyDiscarded() throws Exception {
        String row="{\"claimed\":[25],\"discovered\":[],\"firstFound\":0,\"lastFound\":0}";
        for(String bad:List.of(
                "{\"version\":1,\"players\":{\""+owner+"\":"+row+",\""+owner+"\":"+row+"}}",
                "{\"version\":1,\"players\":{\""+owner+"\":{\"claimed\":[25],\"claimed\":[],\"discovered\":[],\"firstFound\":0,\"lastFound\":0}}}")) {
            Files.writeString(file(),bad);
            assertThrows(IOException.class,()->MilestoneMigration.json(file(),MilestoneMigration.Kind.ARTIFACTS));
            assertEquals(bad,Files.readString(file()));
        }
    }
    @Test void duplicateAwardKeysBlockLoadAndLegacyCopyWithoutChangingFiles() throws Exception {
        Path folder=dir.resolve("source"); Files.createDirectories(folder);
        String body="{\"schema\":1,\"source\":\"test-awards\",\"lineage\":\""+UUID.randomUUID()+"\",\"imported\":true,\"registered\":true,\"awards\":{\""+owner+"\":{\"one\":10,\"one\":0}}}";
        Path journal=folder.resolve("milestone-awards.json");Files.writeString(journal,body);
        assertThrows(IOException.class,()->new MilestoneStore(folder,"test-awards"));
        assertEquals(body,Files.readString(journal));
        Path old=dir.resolve("milestone-awards.json");Files.writeString(old,body);
        assertThrows(IOException.class,()->MilestoneStore.openIn(dir,"test-awards"));
        assertEquals(body,Files.readString(old));
        assertFalse(Files.exists(dir.resolve("test-awards/milestone-awards.json")));
    }
}
