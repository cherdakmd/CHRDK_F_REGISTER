package ru.cherdakmd.chrdksocial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Одна схема payload для VK и MAX. */
class SocialCallbackTest {

    @Test
    void parsesNewAndLegacyPayloads() {
        assertEquals(new SocialCallback.Action("confirm", "freeze"),
                SocialCallback.parse("{\"act\":\"confirm\",\"arg\":\"freeze\"}"));
        assertEquals(new SocialCallback.Action("poll", "1:yes"),
                SocialCallback.parse("{\"act\":\"poll\",\"arg\":\"1:yes\"}"));
        assertEquals(new SocialCallback.Action("help", ""),
                SocialCallback.parse("{\"cmd\":\"start\"}"));
        assertEquals(new SocialCallback.Action("link_wizard", ""),
                SocialCallback.parse("{\"cmd\":\"link\"}"));
        assertFalse(SocialCallback.parse("not-json").known());
        assertFalse(SocialCallback.parse(null).known());
        assertFalse(SocialCallback.parse("{\"act\":\"password\"}").known());
    }

    @Test
    void buttonPayloadRoundTrips() {
        var button = new SocialCallback.Button("✅ Да", "confirm", "logout", "negative");
        var action = SocialCallback.parse(button.payload());
        assertEquals("confirm", action.kind());
        assertEquals("logout", action.arg());
    }
}
