package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Один голос на пользователя, закрытие по таймеру, итог без повторов. */
class SocialPollServiceTest {

    @Test
    void voteIsCountedOnceAndResultIsFinal() {
        SocialPollService polls = new SocialPollService();
        String id = polls.open("Рестарт?", 300, 1_000L);
        assertFalse(id.isEmpty());
        assertTrue(polls.vote(id, "vk:1", true, 1_001L));
        assertFalse(polls.vote(id, "vk:1", false, 1_002L), "повторный голос не считается");
        assertTrue(polls.vote(id, "max:2", false, 1_003L));
        assertTrue(polls.activeText(id, 1_004L).orElse("").contains("✅ 1"));

        Optional<SocialPollService.Result> result = polls.close(id, 1_005L);
        assertTrue(result.isPresent());
        assertEquals(1, result.get().yes());
        assertEquals(1, result.get().no());
        assertFalse(result.get().closedByTimer());
        assertTrue(polls.close(id, 1_006L).isEmpty(), "итог публикуется один раз");
    }

    @Test
    void expiredPollRejectsVotes() {
        SocialPollService polls = new SocialPollService();
        String id = polls.open("Вопрос", 1, 0L);
        assertFalse(polls.vote(id, "vk:1", true, 5_000L));
        var result = polls.close(id, 5_000L);
        assertTrue(result.isPresent());
        assertTrue(result.get().closedByTimer());
        assertEquals("", polls.open("", 30, 0L), "пустой вопрос не создаёт опрос");
    }
}
