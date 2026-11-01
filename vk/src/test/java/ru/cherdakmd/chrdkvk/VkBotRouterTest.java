package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VkBotRouterTest {

    @Test
    void routesDirectCommandsAndGroupChatBridgeSafely() {
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

            @Override
            public long activeGroupChatPeerId() {
                return 2000000001L;
            }

            @Override
            public void onGroupChatMessage(long peerId, long fromUserId, JsonObject message, long nowMillis) {
                actions.add("chat:" + peerId + ":" + fromUserId + ":" + message.get("text").getAsString());
            }
        });

        // 1. Отказ на любые команды смены/сброса пароля.
        for (String attempt : List.of("/пароль 12345", "сбросить пароль", "/resetpassword", "/changepassword")) {
            String resp = router.dispatchDirectCommand(77L, attempt, 1000L);
            assertTrue(resp.contains("запрещены"), attempt);
        }

        // 2. Прямая отправка 6-значного кода привязки без команды в ЛС.
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":77,\"from_id\":77,\"text\":\"AB34CD\"}}}")
                .getAsJsonObject(), 1000L);
        assertEquals(List.of("77->LINKED:AB34CD"), replies);

        // 3. В общей беседе личные защитные команды (/выход) НЕ выполняются и НЕ утекают в игровой чат.
        replies.clear();
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":2000000001,\"from_id\":77,\"text\":\"/выход\"}}}")
                .getAsJsonObject(), 1000L);
        assertTrue(replies.isEmpty());
        assertFalse(actions.contains("logout:77"));

        // 4. В беседе работают /chatid и /онлайн (в том числе с упоминанием бота [club1|@bot]),
        // а обычные сообщения участников маршрутизируются в мост глобального чата.
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":2000000005,\"from_id\":77,\"text\":\"[club1|@chrdk_reborn] /chatid\"}}}")
                .getAsJsonObject(), 1000L);
        assertEquals(1, replies.size());
        assertTrue(replies.getFirst().contains("2000000005"));

        replies.clear();
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":2000000001,\"from_id\":77,\"text\":\"/онлайн\"}}}")
                .getAsJsonObject(), 1000L);
        assertEquals(List.of("2000000001->ONLINE:12"), replies);

        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":2000000001,\"from_id\":77,\"text\":\"Всем привет из беседы!\"}}}")
                .getAsJsonObject(), 1000L);
        // Сообщение из чужой непривязанной беседы и сообщение от самого бота (from_id < 0) игнорируются.
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":2000000099,\"from_id\":77,\"text\":\"Чужая беседа\"}}}")
                .getAsJsonObject(), 1000L);
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":2000000001,\"from_id\":-12345,\"text\":\"[MC] Steve: эхо\"}}}")
                .getAsJsonObject(), 1000L);

        // 5. Экстренные команды /выход, /заморозить, /разморозить в ЛС, нажатие inline-кнопки с payload и /вики в беседе.
        replies.clear();
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":77,\"from_id\":77,\"text\":\"🧊 Заморозить вход\",\"payload\":\"{\\\"cmd\\\":\\\"freeze\\\"}\"}}}")
                .getAsJsonObject(), 1000L);
        assertEquals(List.of("77->FROZEN"), replies);

        replies.clear();
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"message_new\",\"object\":{\"message\":{\"peer_id\":2000000001,\"from_id\":77,\"text\":\"/вики\"}}}")
                .getAsJsonObject(), 1000L);
        assertEquals(1, replies.size());
        assertTrue(replies.getFirst().contains("https://cherdakmd.github.io/CHRDK_F_REGISTER/"));

        assertEquals("LOGOUT_OK", router.dispatchDirectCommand(77L, "/выход", 1000L));
        assertEquals("FROZEN", router.dispatchDirectCommand(77L, "/заморозить", 1000L));
        assertEquals("UNFROZEN", router.dispatchDirectCommand(77L, "/разморозить", 1000L));

        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"donut_subscription_create\",\"object\":{\"user_id\":77}}").getAsJsonObject(), 1000L);
        router.handleUpdate(JsonParser.parseString(
                "{\"type\":\"wall_post_new\",\"object\":{\"id\":99,\"text\":\"Новость\"}}").getAsJsonObject(), 1000L);

        assertTrue(actions.containsAll(List.of(
                "link:77:AB34CD",
                "chat:2000000001:77:Всем привет из беседы!",
                "logout:77", "freeze:77", "unfreeze:77", "donut_on:77", "wall:99")));
        assertFalse(actions.stream().anyMatch(a -> a.contains("Чужая беседа") || a.contains("эхо")));
    }
}
