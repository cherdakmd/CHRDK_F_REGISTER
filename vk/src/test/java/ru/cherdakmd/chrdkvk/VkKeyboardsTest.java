package ru.cherdakmd.chrdkvk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdksocial.SocialCallback;

import java.util.List;

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
    void callbackKeyboardUsesVkCallbackActionsNotTextCommands() {
        String json = VkKeyboards.callbackKeyboard(List.of(
                new SocialCallback.Button("✅ Да", "confirm", "freeze", "positive"),
                new SocialCallback.Button("❌ Нет", "cancel", "", "negative"),
                new SocialCallback.Button("🗳 Опрос", "poll_new", "", "secondary")));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(root.get("inline").getAsBoolean());
        var rows = root.getAsJsonArray("buttons");
        assertEquals(2, rows.size(), "не больше двух кнопок в ряду");
        JsonObject action = rows.get(0).getAsJsonArray().get(0).getAsJsonObject()
                .getAsJsonObject("action");
        assertEquals("callback", action.get("type").getAsString());
        assertEquals("✅ Да", action.get("label").getAsString());
        assertEquals(new SocialCallback.Action("confirm", "freeze"),
                SocialCallback.parse(action.get("payload").getAsString()));

        JsonObject snackbar = JsonParser.parseString(VkKeyboards.snackbar("Готово")).getAsJsonObject();
        assertEquals("show_snackbar", snackbar.get("type").getAsString());
        assertEquals("Готово", snackbar.getAsJsonObject("show_snackbar")
                .get("text").getAsString());
        assertEquals("Готово", JsonParser.parseString(VkKeyboards.snackbar("  "))
                .getAsJsonObject().getAsJsonObject("show_snackbar").get("text").getAsString());
        assertTrue(VkKeyboards.callbackKeyboard(null).contains("\"buttons\":[]"));
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
