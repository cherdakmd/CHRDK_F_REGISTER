package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VkBotRouterTest {

    @Test
    void routesDirectCommandsBlocksPasswordResetAndIgnoresGroupChatSpam() {
        List<String> replies = new ArrayList<>();
        List<String> actions = new ArrayList<>();

        VkBotRouter router = new VkBotRouter(new VkBotRouter.Backend() {
            @Override
            public String handleLinkCode(long vkUserId, String code, long nowMillis) {
                actions.add("link:" + vkUserId + ":" + code);
                return "LINKED:" + code;
            }

            @Override
            public String formatServerOnline() {
                return "ONLINE:12";
            }

            @Override
            public String formatAccountProfile(long vkUserId, long nowMillis) {
                return "PROFILE:" + vkUserId;
            }

            @Override
            public String handleRemoteLogout(long vkUserId) {
                actions.add("logout:" + vkUserId);
                return "LOGOUT_OK";
            }

            @Override
            public String handleRemoteFreeze(long vkUserId, boolean freeze) {
                actions.add((freeze ? "freeze:" : "unfreeze:") + vkUserId);
                return freeze ? "FROZEN" : "UNFROZEN";
            }

            @Override
            public String handleToggleNotifications(long vkUserId) {
                return "NOTIFY_TOGGLED";
            }

            @Override
            public String handleUnlinkFromVk(long vkUserId) {
                return "UNLINKED";
            }

            @Override
            public void sendDirectReply(long peerId, String text) {
                replies.add(peerId + "->" + text);
            }

            @Override
            public void onGroupJoin(long vkUserId, long nowMillis) {
                actions.add("join:" + vkUserId);
            }

            @Override
            public void onGroupLeave(long vkUserId) {
                actions.add("leave:" + vkUserId);
            }

            @Override
            public void onDonutActive(long vkUserId, long nowMillis) {
                actions.add("donut_on:" + vkUserId);
            }

            @Override
            public void onDonutEnded(long vkUserId, long nowMillis) {
                actions.add("donut_off:" + vkUserId);
            }

            @Override
            public void onWallPost(VkApiClient.WallPost post) {
                actions.add("wall:" + post.id());
            }
        });

        // 1. Отказ на любые команды смены/сброса пароля.
        for (String attempt : List.of("/пароль 12345", "сбросить пароль", "/resetpassword", "/changepassword")) {
            String resp = router.dispatchDirectCommand(77L, attempt, 1000L);
            assertTrue(resp.contains("запрещены"), attempt);
        }

        // 2. Прямая отправка 6-значного кода привязки без команды.
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":77,\"from_id\":77,\"text\":\"AB34CD\"}}}")
                .getAsJsonObject(), 1000L);
        assertEquals(List.of("77->LINKED:AB34CD"), replies);

        // 3. Сообщения в общей беседе (peer_id != from_id) игнорируются личным маршрутизатором.
        replies.clear();
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":2000000001,\"from_id\":77,\"text\":\"/выход\"}}}")
                .getAsJsonObject(), 1000L);
        assertTrue(replies.isEmpty());

        // 4. Экстренные команды /выход, /заморозить, /разморозить и события Donut/стены.
        assertEquals("LOGOUT_OK", router.dispatchDirectCommand(77L, "/выход", 1000L));
        assertEquals("FROZEN", router.dispatchDirectCommand(77L, "/заморозить", 1000L));
        assertEquals("UNFROZEN", router.dispatchDirectCommand(77L, "/разморозить", 1000L));

        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"donut_subscription_create\",\"object\":{\"user_id\":77}}").getAsJsonObject(), 1000L);
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"wall_post_new\",\"object\":{\"id\":99,\"text\":\"Новость\"}}").getAsJsonObject(), 1000L);

        assertTrue(actions.containsAll(List.of(
                "link:77:AB34CD", "logout:77", "freeze:77", "unfreeze:77", "donut_on:77", "wall:99")));
    }
}
