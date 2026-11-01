package ru.cherdakmd.chrdkvk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class VkChatBatcherTest {

    @Test
    void coalescesBurstMessagesIntoSingleVkSendCall() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        try (VkChatBatcher batcher = new VkChatBatcher(120, 5, 1_500, (peer, body) -> {
            sent.add(peer + "|" + body);
            latch.countDown();
        })) {
            batcher.enqueue(2000000001L, "[MC] Steve: Первое");
            batcher.enqueue(2000000001L, "[MC] Alex: Второе");
            batcher.enqueue(2000000001L, "[MC] Notch: Третье");
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        }

        assertEquals(1, sent.size());
        assertEquals("2000000001|[MC] Steve: Первое\n[MC] Alex: Второе\n[MC] Notch: Третье", sent.getFirst());
    }

    @Test
    void flushesImmediatelyOnMaxLinesAndOnClose() {
        List<String> sent = new CopyOnWriteArrayList<>();
        try (VkChatBatcher batcher = new VkChatBatcher(2_000, 2, 1_500, (peer, body) -> sent.add(body))) {
            batcher.enqueue(2000000001L, "A");
            assertTrue(sent.isEmpty());
            batcher.enqueue(2000000001L, "B");
            assertEquals(List.of("A\nB"), sent, "при достижении maxLines сброс происходит мгновенно");
            batcher.enqueue(2000000001L, "C");
        }
        assertEquals(List.of("A\nB", "C"), sent, "close() сбрасывает остаток буфера без потерь");
    }
}
