package ru.cherdakmd.chrdkgoals;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalRewardsTest {

    @Test
    void emptyContributionsProduceEmptyShares() {
        assertTrue(GoalRewards.distribute(96, Map.of(), 0).isEmpty());
        assertTrue(GoalRewards.distribute(0, Map.of("alex", 10), 0).isEmpty());
    }

    @Test
    void proportionalSplitKeepsPool() {
        Map<String, Integer> shares = GoalRewards.distribute(96, Map.of(
                "alex", 3, "steve", 1), 0);
        assertEquals(72, shares.get("alex"));
        assertEquals(24, shares.get("steve"));
        assertEquals(96, shares.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void everyoneGetsAtLeastOneAndRemainderGoesToLeader() {
        Map<String, Integer> many = new LinkedHashMap<>();
        many.put("boss", 300);
        many.put("tiny1", 1);
        many.put("tiny2", 1);
        Map<String, Integer> shares = GoalRewards.distribute(102, many, 0);
        assertTrue(shares.get("tiny1") >= 1);
        assertTrue(shares.get("tiny2") >= 1);
        assertEquals(102, shares.values().stream().mapToInt(Integer::intValue).sum(),
                "пул раздан полностью (остаток — лидеру)");
        assertTrue(shares.get("boss") > shares.get("tiny1"), "лидер больше остатка получил");
    }

    @Test
    void bonusPercentScalesPool() {
        Map<String, Integer> shares = GoalRewards.distribute(100, Map.of("alex", 1), 50);
        assertEquals(150, shares.get("alex"));
    }
}
