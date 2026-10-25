package ru.cherdakmd.chrdklottery;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LotteryRoundTest {
    @Test void emptyAndInvalidCounts() {
        assertEquals(0, LotteryRound.totalTickets(null));
        assertEquals(0, LotteryRound.participants(Map.of()));
        Map<String, Integer> entries = new LinkedHashMap<>();
        entries.put("none", null); entries.put("bad", -5); entries.put("Alice", 3); entries.put("Bob", 2);
        assertEquals(5, LotteryRound.totalTickets(entries));
        assertEquals(2, LotteryRound.participants(entries));
        assertNull(LotteryRound.pickWinner(Map.of(), new Random(0)));
    }
    @Test void everyTicketMapsToExactlyOneWinner() {
        Map<String, Integer> entries = new LinkedHashMap<>();
        entries.put("Alice", 3); entries.put("Bob", 2);
        for (int roll = 0; roll < 5; roll++) {
            final int fixed = roll;
            Random rng = new Random() { @Override public int nextInt(int bound) { assertEquals(5, bound); return fixed; } };
            assertEquals(roll < 3 ? "Alice" : "Bob", LotteryRound.pickWinner(entries, rng));
        }
    }
    @Test void roundingAndBurnConservePot() {
        for (int pot = 0; pot <= 500; pot++) {
            for (int percent = 1; percent <= 100; percent++) {
                assertEquals(pot, LotteryRound.payoutOf(pot, percent) + LotteryRound.burnOf(pot, percent));
            }
        }
        assertEquals(80, LotteryRound.payoutOf(100, 80));
        assertEquals(20, LotteryRound.burnOf(100, 80));
        assertEquals(1, LotteryRound.payoutOf(2, 80));
        assertEquals(0, LotteryRound.payoutOf(1, 80));
        assertEquals(Integer.MAX_VALUE, LotteryRound.payoutOf(Integer.MAX_VALUE, 100));
        assertThrows(IllegalArgumentException.class, () -> LotteryRound.payoutOf(-1, 80));
    }
    @Test void chanceAndOverflow() {
        assertEquals(333, LotteryRound.chancePermille(1, 3));
        assertEquals(1000, LotteryRound.chancePermille(10, 10));
        assertEquals(0, LotteryRound.chancePermille(1, 0));
        assertThrows(ArithmeticException.class, () -> LotteryRound.totalTickets(Map.of("a", Integer.MAX_VALUE, "b", 1)));
    }
}
