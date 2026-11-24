package ru.cherdakmd.chrdkfishing;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatchPolicyTest {
    @Test void onlyPlainAuthenticatedSurvivalFishAreCandidates() {
        var random = new Random(4);
        assertTrue(CatchPolicy.replace(new CatchPolicy.Attempt(true,true,true,false,true,false,"COD",1), 10_000, random));
        assertFalse(CatchPolicy.replace(new CatchPolicy.Attempt(true,true,true,false,true,false,"BOW",1), 10_000, random));
        assertFalse(CatchPolicy.replace(new CatchPolicy.Attempt(true,true,true,false,true,true,"COD",1), 10_000, random));
        assertFalse(CatchPolicy.replace(new CatchPolicy.Attempt(true,false,true,false,true,false,"COD",1), 10_000, random));
        assertFalse(CatchPolicy.replace(new CatchPolicy.Attempt(true,true,false,false,true,false,"COD",1), 10_000, random));
        assertFalse(CatchPolicy.replace(new CatchPolicy.Attempt(true,true,true,true,true,false,"COD",1), 10_000, random));
        assertFalse(CatchPolicy.replace(new CatchPolicy.Attempt(true,true,true,false,false,false,"COD",1), 10_000, random));
        assertFalse(CatchPolicy.replace(new CatchPolicy.Attempt(true,true,true,false,true,false,"COD",2), 10_000, random));
        assertFalse(CatchPolicy.replace(new CatchPolicy.Attempt(true,true,true,false,true,false,"COD",1), 0, random));
        assertThrows(IllegalArgumentException.class, () -> CatchPolicy.replace(null, 100, random));
        assertThrows(IllegalArgumentException.class, () -> CatchPolicy.replace(new CatchPolicy.Attempt(true,true,true,false,true,false,"COD",1), 10_001, random));
    }
}
