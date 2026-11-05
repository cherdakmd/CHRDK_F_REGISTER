package ru.cherdakmd.chrdksocial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaxBotRouterTest {

    private static final class Recorder implements MaxBotRouter.Backend {
        final List<String> codes = new ArrayList<>();
        final List<String> chats = new ArrayList<>();
        final List<String> bridge = new ArrayList<>();
        final List<String> callbacks = new ArrayList<>();
        final List<String> replies = new ArrayList<>();

        @Override public String onCode(String senderKey, long userId, String code, long now) {
            codes.add(senderKey + ":" + userId + ":" + code);
            return "ok";
        }
        @Override public void onChatDiscovered(long chatId, String chatType, long now) {
            chats.add(chatId + ":" + chatType);
        }
        @Override public void onBridgeMessage(long chatId, long userId, String senderName,
                                              String text, long now) {
            bridge.add(chatId + ":" + senderName + ":" + text);
        }
        @Override public String onCallback(String senderKey, long userId, String payload, long now) {
            callbacks.add(userId + ":" + payload);
            return "callback";
        }
        @Override public void sendDirectReply(long userId, String text) {
            replies.add(userId + ":" + text);
        }
        @Override public long bridgeChatId() { return 72000001L; }
    }

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    @Test
    void directMessageCodeIsAcceptedAndAnswered() {
        Recorder backend = new Recorder();
        new MaxBotRouter(backend).handle(json("""
                {"update_type":"message_created","timestamp":1,
                 "message":{"sender":{"user_id":999},"recipient":{"chat_id":999,"chat_type":"dialog"},
                            "body":{"text":" ABC234 "}}}
                """), 10L);
        assertEquals(List.of("max:999:999:ABC234"), backend.codes);
        assertEquals(List.of("999:ok"), backend.replies);
        assertTrue(backend.bridge.isEmpty());
    }

    @Test
    void linkPrefixIsAccepted() {
        Recorder backend = new Recorder();
        new MaxBotRouter(backend).handle(json("""
                {"update_type":"message_created","message":{
                  "sender":{"user_id":7},"recipient":{"chat_id":7,"chat_type":"dialog"},
                  "body":{"text":"/link ABC234"}}}
                """), 10L);
        assertEquals(1, backend.codes.size());
        assertTrue(backend.codes.get(0).endsWith(":ABC234"));
    }

    @Test
    void botAddedDiscoversChatIdWithoutGetChats() {
        Recorder backend = new Recorder();
        new MaxBotRouter(backend).handle(json(
                "{\"update_type\":\"bot_added\",\"chat_id\":72000001,\"chat_type\":\"channel\"}"), 10L);
        assertEquals(List.of("72000001:channel"), backend.chats);
    }

    @Test
    void bridgeMessageGoesOnlyFromConfiguredChat() {
        Recorder backend = new Recorder();
        MaxBotRouter router = new MaxBotRouter(backend);
        router.handle(json("""
                {"update_type":"message_created","message":{"sender":{"user_id":5,"name":"Игрок"},
                  "recipient":{"chat_id":72000001,"chat_type":"chat"},"body":{"text":"привет"}}}
                """), 10L);
        assertEquals(List.of("72000001:Игрок:привет"), backend.bridge);
        router.handle(json("""
                {"update_type":"message_created","message":{"sender":{"user_id":5,"name":"Игрок"},
                  "recipient":{"chat_id":123,"chat_type":"chat"},"body":{"text":"чужой чат"}}}
                """), 10L);
        assertEquals(1, backend.bridge.size());
    }

    @Test
    void botMessagesAndUnknownTypesAreIgnored() {
        Recorder backend = new Recorder();
        MaxBotRouter router = new MaxBotRouter(backend);
        router.handle(json("{\"update_type\":\"message_created\",\"message\":{\"body\":{\"text\":\"ABC234\"}}}"), 1L);
        router.handle(json("{\"update_type\":\"something_else\"}"), 1L);
        router.handle(null, 1L);
        assertTrue(backend.codes.isEmpty());
        assertTrue(backend.bridge.isEmpty());
    }

    @Test
    void callbackAndBotStartedAreDispatched() {
        Recorder backend = new Recorder();
        MaxBotRouter router = new MaxBotRouter(backend);
        router.handle(json("""
                {"update_type":"message_callback","user":{"user_id":42},
                 "callback":{"payload":"status"}}
                """), 1L);
        router.handle(json("{\"update_type\":\"bot_started\",\"user_id\":42,\"chat_id\":88}"), 1L);
        assertTrue(backend.callbacks.contains("42:status"));
        assertTrue(backend.callbacks.contains("42:start"));
        assertTrue(backend.chats.contains("88:"));
        assertEquals(2, backend.replies.size());
    }
}
