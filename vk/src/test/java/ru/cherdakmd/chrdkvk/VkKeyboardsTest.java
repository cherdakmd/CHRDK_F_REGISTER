package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VkKeyboardsTest {

    @Test
    void directMenuAndSecurityInlineKeyboardsFollowVkSchema() {
        JsonObject menu = JsonParser.parseString(VkKeyboards.directMenuKeyboard()).getAsJsonObject();
        assertFalse(menu.get("inline").getAsBoolean());
        assertFalse(menu.get("one_time").getAsBoolean());
        assertEquals(3, menu.getAsJsonArray("buttons").size());

        JsonObject alertInline = JsonParser.parseString(VkKeyboards.securityAlertInlineKeyboard()).getAsJsonObject();
        assertTrue(alertInline.get("inline").getAsBoolean());
        assertEquals(1, alertInline.getAsJsonArray("buttons").size());
        assertEquals(2, alertInline.getAsJsonArray("buttons").get(0).getAsJsonArray().size());
    }

    @Test
    void extractsCommandsFromPayloadAndIgnoresMalformedInput() {
        assertEquals("/выход", VkKeyboards.extractCommandFromPayload("{\"cmd\":\"logout\"}"));
        assertEquals("/заморозить", VkKeyboards.extractCommandFromPayload("{\"cmd\":\"freeze\"}"));
        assertEquals("/разморозить", VkKeyboards.extractCommandFromPayload("{\"cmd\":\"unfreeze\"}"));
        assertEquals("/профиль", VkKeyboards.extractCommandFromPayload("{\"cmd\":\"profile\"}"));
        assertEquals("/онлайн", VkKeyboards.extractCommandFromPayload("{\"cmd\":\"online\"}"));
        assertEquals("/уведомления", VkKeyboards.extractCommandFromPayload("{\"cmd\":\"notify\"}"));
        assertEquals("/вики", VkKeyboards.extractCommandFromPayload("{\"command\":\"wiki\"}"));

        assertEquals("", VkKeyboards.extractCommandFromPayload(null));
        assertEquals("", VkKeyboards.extractCommandFromPayload(""));
        assertEquals("", VkKeyboards.extractCommandFromPayload("not-json"));
        assertEquals("", VkKeyboards.extractCommandFromPayload("{\"cmd\":\"password\"}"));
    }
}
