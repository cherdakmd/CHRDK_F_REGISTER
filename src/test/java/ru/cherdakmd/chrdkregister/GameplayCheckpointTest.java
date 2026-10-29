package ru.cherdakmd.chrdkregister;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class GameplayCheckpointTest {
 @TempDir Path dir;
 @Test void gameplayBoostCheckpointSurvivesRestartAndOlderQueuedSnapshots() {var queue=new ArrayList<Runnable>();var s=new RegistrationStore(dir,Logger.getAnonymousLogger(),queue::add);s.recordPlayerSeen(UUID.randomUUID(),"Steve");s.grantRewardBoost("Steve",2,24);assertTrue(s.checkpointGameplayRewards());queue.forEach(Runnable::run);var restarted=new RegistrationStore(dir,Logger.getAnonymousLogger());assertEquals(7200000,restarted.rewardBoostRemaining("Steve"));}
 @Test void failedCheckpointDoesNotAuthorizeRewardAck() throws Exception {var s=new RegistrationStore(dir,Logger.getAnonymousLogger(),r->{});s.recordPlayerSeen(UUID.randomUUID(),"Steve");s.grantRewardBoost("Steve",2,24);Files.createDirectories(dir.resolve("data.json.reward.tmp"));assertFalse(s.checkpointGameplayRewards());assertTrue(s.hasPendingChanges());}
}
