package ru.cherdakmd.chrdkregister;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.BoostGrant.*;

class BattlePassReward281Test {
    @TempDir Path dir;final UUID player=UUID.randomUUID();final String key="bp:"+"a".repeat(64);
    RegistrationStore store(){var s=new RegistrationStore(dir,Logger.getAnonymousLogger());s.recordPlayerSeen(player,"Steve");return s;}
    @Test void rewardAndReceiptSurviveRestartAndRepeatedClaimDoesNotExtend() {
        var s=store();assertEquals(ADDED,s.grantRewardBoostOnce(player,"Steve",8,24,key));
        var reboot=new RegistrationStore(dir,Logger.getAnonymousLogger());
        assertEquals(ALREADY,reboot.grantRewardBoostOnce(player,"Steve",8,24,key));
        assertEquals(8*RewardBoostMath.HOUR,reboot.rewardBoostRemaining("Steve"));
        reboot.consumeRewardBoost("Steve",8*RewardBoostMath.HOUR);
        assertEquals(ALREADY,reboot.grantRewardBoostOnce(player,"Steve",24,24,key));assertEquals(0,reboot.rewardBoostRemaining("Steve"));
    }
    @Test void fullBankDoesNotTruncateRewardOrBurnReceipt() {
        var s=store();s.grantRewardBoost("Steve",20,24);
        assertEquals(FULL,s.grantRewardBoostOnce(player,"Steve",8,24,key));assertEquals(20*RewardBoostMath.HOUR,s.rewardBoostRemaining("Steve"));
        s.consumeRewardBoost("Steve",4*RewardBoostMath.HOUR);
        assertEquals(ADDED,s.grantRewardBoostOnce(player,"Steve",8,24,key));assertEquals(24*RewardBoostMath.HOUR,s.rewardBoostRemaining("Steve"));
    }
    @Test void identityBoundsAndReceiptValidationFailClosed() {
        var s=store();assertEquals(FAILED,s.grantRewardBoostOnce(UUID.randomUUID(),"Steve",8,24,key));
        for(int hours:new int[]{-1,0,25,Integer.MAX_VALUE})assertEquals(FAILED,s.grantRewardBoostOnce(player,"Steve",hours,24,key));
        assertEquals(FAILED,s.grantRewardBoostOnce(player,"Steve",8,24,"../bad"));assertEquals(0,s.rewardBoostRemaining("Steve"));
    }
    @Test void failureCanBeRetriedWithoutCreditingTwice() throws Exception {
        var s=store();Files.createDirectories(dir.resolve("data.json.reward.tmp"));
        assertEquals(FAILED,s.grantRewardBoostOnce(player,"Steve",8,24,key));
        Files.delete(dir.resolve("data.json.reward.tmp"));
        assertEquals(ALREADY,s.grantRewardBoostOnce(player,"Steve",8,24,key));
        assertEquals(8*RewardBoostMath.HOUR,new RegistrationStore(dir,Logger.getAnonymousLogger()).rewardBoostRemaining("Steve"));
    }
    @Test void olderQueuedSnapshotCannotOverwriteConfirmedReceipt() {
        var queue=new ArrayList<Runnable>();var s=new RegistrationStore(dir,Logger.getAnonymousLogger(),queue::add);
        s.recordPlayerSeen(player,"Steve");s.grantRewardBoost("Steve",1,24);
        assertEquals(ADDED,s.grantRewardBoostOnce(player,"Steve",8,24,key));queue.forEach(Runnable::run);
        var reboot=new RegistrationStore(dir,Logger.getAnonymousLogger());assertEquals(ALREADY,reboot.grantRewardBoostOnce(player,"Steve",8,24,key));
        assertEquals(9*RewardBoostMath.HOUR,reboot.rewardBoostRemaining("Steve"));
    }
}
