package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VkApiClientTest {

    @Test
    void tokenStaysInPostBodyNeverInUriAndMethodsParseResponses() throws Exception {
        List<URI> calledUris = new ArrayList<>();
        List<String> formBodies = new ArrayList<>();

        VkApiClient.Transport fakeTransport = new VkApiClient.Transport() {
            @Override
            public String postForm(URI uri, String formBody, Duration timeout) {
                calledUris.add(uri);
                formBodies.add(formBody);
                String path = uri.getPath();
                if (path.endsWith("/groups.getLongPollServer")) {
                    return "{\"response\":{\"server\":\"https://lp.vk.com/wh1\",\"key\":\"k1\",\"ts\":\"10\"}}";
                }
                if (path.endsWith("/groups.isMember")) {
                    return "{\"response\":1}";
                }
                if (path.endsWith("/groups.getMembers")) {
                    return "{\"response\":{\"count\":2,\"items\":[101,202]}}";
                }
                if (path.endsWith("/wall.get")) {
                    return "{\"response\":{\"count\":1,\"items\":[{\"id\":55,\"date\":1700000000,\"text\":\"Обновление [club1|CHRDK] <click:run_command:'/op'>!\"}]}}";
                }
                if (path.endsWith("/messages.send")) {
                    return "{\"response\":9001}";
                }
                throw new AssertionError("Unexpected path: " + path);
            }

            @Override
            public String get(URI uri, Duration timeout) {
                return "{\"ts\":\"11\",\"updates\":[{\"type\":\"group_join\",\"object\":{\"user_id\":101}}]}";
            }
        };

        VkApiClient client = new VkApiClient("https://api.vk.com/method/", "SECRET_VK_TOKEN", "5.199", fakeTransport);
        VkApiClient.LongPollEndpoint lp = client.getLongPollServer(777L);
        assertEquals("10", lp.ts());
        VkApiClient.LongPollBatch batch = client.checkLongPoll(lp, 25);
        assertEquals("11", batch.ts());
        assertEquals(1, batch.updates().size());

        assertTrue(client.isGroupMember(777L, 101L));
        assertEquals(Set.of(101L, 202L), client.fetchDonutMemberIds(777L));
        assertTrue(client.sendMessage(101L, "Привет!"));

        List<VkApiClient.WallPost> posts = client.fetchWallPosts(777L, 5);
        assertEquals(1, posts.size());
        String preview = VkApiClient.formatWallPreview(posts.getFirst().text(), 140);
        assertEquals("Обновление CHRDK ‹click:run_command:'/op'›!", preview,
                "вики-ссылка раскрыта, а угловые скобки обезврежены");

        for (URI uri : calledUris) {
            assertFalse(uri.toString().contains("SECRET_VK_TOKEN"),
                    "токен сообщества никогда не передаётся в URI");
        }
        assertTrue(formBodies.stream().allMatch(b -> b.contains("access_token=SECRET_VK_TOKEN")));
    }

    @Test
    void longPollWorkerRecoversFromKeyExpirationAndDeliversUpdates() throws Exception {
        AtomicInteger serverCalls = new AtomicInteger();
        AtomicInteger checkCalls = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);

        VkApiClient.Transport transport = new VkApiClient.Transport() {
            @Override
            public String postForm(URI uri, String formBody, Duration timeout) {
                int n = serverCalls.incrementAndGet();
                return "{\"response\":{\"server\":\"https://lp.vk.com/wh\",\"key\":\"key" + n + "\",\"ts\":\"1\"}}";
            }

            @Override
            public String get(URI uri, Duration timeout) throws IOException, InterruptedException {
                int c = checkCalls.incrementAndGet();
                if (c == 1) {
                    return "{\"failed\":2}"; // ключ устарел — воркер обязан перезапросить сервер
                }
                if (c == 2) {
                    return "{\"ts\":\"2\",\"updates\":[{\"type\":\"group_join\",\"object\":{\"user_id\":555}}]}";
                }
                Thread.sleep(500L);
                return "{\"ts\":\"2\",\"updates\":[]}";
            }
        };

        VkApiClient client = new VkApiClient("https://api.vk.com/method/", "tok", "5.199", transport);
        try (VkLongPollWorker worker = new VkLongPollWorker(client, 1L, 5, 2, (upd, ts) -> {
            if ("group_join".equals(upd.get("type").getAsString())) {
                delivered.countDown();
            }
        }, Logger.getLogger("VkLongPollWorkerTest"))) {
            worker.start();
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertTrue(serverCalls.get() >= 2, "после failed=2 воркер перезапросил getLongPollServer");
        }
    }

    @Test
    void parseWallPostDetectsPinnedAdsAndReposts() {
        var post = VkApiClient.parseWallPost(JsonParser.parseString(
                "{\"id\":12,\"is_pinned\":1,\"marked_as_ads\":0,\"copy_history\":[{\"id\":1}],\"text\":\"x\"}")
                .getAsJsonObject());
        assertEquals(12L, post.id());
        assertTrue(post.pinned());
        assertFalse(post.markedAsAds());
        assertTrue(post.repost());
    }
}
