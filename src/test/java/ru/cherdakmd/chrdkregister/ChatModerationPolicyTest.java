package ru.cherdakmd.chrdkregister;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatModerationPolicyTest {
    private static ChatModerationPolicy defaults() throws Exception {
        return new ChatModerationPolicy(load("nicknames.yml"), load("anti-swear.yml"), load("anti-spam.yml"));
    }
    private static YamlConfiguration load(String name) throws Exception {
        return YamlConfiguration.loadConfiguration(new StringReader(
                Files.readString(Path.of("src/main/resources", name))));
    }
    private static ChatModerationPolicy.Decision allow(ChatModerationPolicy policy, UUID id,
            String text, ChatModerationPolicy.Channel channel, long now) {
        return policy.moderate(id, text, channel, false, false, now);
    }

    @Test void defaultNicknameFragmentsAreCaseInsensitiveAndApplyInsideNames() throws Exception {
        var policy = defaults();
        for (String name : new String[]{"x228x", "KLauncher", "Admin123", "my_moderator"})
            assertFalse(policy.isNicknameAllowed(name), name);
        assertTrue(policy.isNicknameAllowed("Steve"));
        assertTrue(policy.isNicknameAllowed("Moderate"));
    }

    @Test void exactNicknameAllowlistAndIndependentDisableWork() throws Exception {
        var names = load("nicknames.yml"); names.set("allowed-names", java.util.List.of("Admin123"));
        var policy = new ChatModerationPolicy(names, load("anti-swear.yml"), load("anti-spam.yml"));
        assertTrue(policy.isNicknameAllowed("admin123")); assertFalse(policy.isNicknameAllowed("OtherAdmin"));
        names.set("enabled", false);
        policy.reload(names, load("anti-swear.yml"), load("anti-spam.yml"));
        assertTrue(policy.isNicknameAllowed("x228x"));
    }

    @Test void defaultSwearFilterMasksWithoutPublishingOriginalAndSupportsBlockMode() throws Exception {
        var policy = defaults(); var id = UUID.randomUUID();
        var decision = allow(policy, id, "привет FUCK мир", ChatModerationPolicy.Channel.CHAT, 1_000);
        assertEquals(ChatModerationPolicy.Status.ALLOW, decision.status());
        assertEquals("привет *** мир", decision.text());
        var swears = load("anti-swear.yml"); swears.set("mode", "block");
        policy.reload(load("nicknames.yml"), swears, load("anti-spam.yml"));
        assertEquals(ChatModerationPolicy.Status.BLOCK,
                allow(policy, id, "fuck", ChatModerationPolicy.Channel.PRIVATE, 2_000).status());
        assertEquals("chat.swear-blocked",
                allow(policy, UUID.randomUUID(), "fuck", ChatModerationPolicy.Channel.PRIVATE, 2_000).messageKey());
    }

    @Test void spamChecksCooldownNormalizedDuplicatesAndSlidingBurst() throws Exception {
        var policy = defaults(); var id = UUID.randomUUID();
        assertEquals(ChatModerationPolicy.Status.ALLOW, allow(policy,id,"Привет, мир!",ChatModerationPolicy.Channel.CHAT,1_000).status());
        assertEquals("chat.anti-spam-cooldown", allow(policy,id,"Другое",ChatModerationPolicy.Channel.CHAT,1_500).messageKey());
        assertEquals("chat.anti-spam-duplicate", allow(policy,id,"привет мир",ChatModerationPolicy.Channel.CHAT,2_001).messageKey());
        assertEquals(ChatModerationPolicy.Status.ALLOW, allow(policy,id,"Другое",ChatModerationPolicy.Channel.CHAT,2_001).status());

        var spam = load("anti-spam.yml"); spam.set("cooldown-ms",0); spam.set("duplicate-window-ms",0);
        spam.set("burst.max-messages",2); spam.set("burst.window-ms",10_000);
        policy.reload(load("nicknames.yml"), load("anti-swear.yml"), spam);
        assertEquals(ChatModerationPolicy.Status.ALLOW, allow(policy,id,"a",ChatModerationPolicy.Channel.CHAT,1_000).status());
        assertEquals(ChatModerationPolicy.Status.ALLOW, allow(policy,id,"b",ChatModerationPolicy.Channel.CHAT,2_000).status());
        assertEquals("chat.anti-spam-flood", allow(policy,id,"c",ChatModerationPolicy.Channel.CHAT,3_000).messageKey());
        assertEquals(ChatModerationPolicy.Status.ALLOW, allow(policy,id,"c",ChatModerationPolicy.Channel.CHAT,11_001).status());
    }

    @Test void channelsBypassAndForgetAreIndependent() throws Exception {
        var spam = load("anti-spam.yml"); spam.set("apply-to-private", false);
        var policy = new ChatModerationPolicy(load("nicknames.yml"), load("anti-swear.yml"), spam);
        var id = UUID.randomUUID();
        assertEquals(ChatModerationPolicy.Status.ALLOW, allow(policy,id,"same",ChatModerationPolicy.Channel.PRIVATE,1_000).status());
        assertEquals(ChatModerationPolicy.Status.ALLOW, allow(policy,id,"same",ChatModerationPolicy.Channel.PRIVATE,1_001).status());
        assertEquals(ChatModerationPolicy.Status.ALLOW,
                policy.moderate(id,"same",ChatModerationPolicy.Channel.CHAT,true,true,1_002).status());
        allow(policy,id,"first",ChatModerationPolicy.Channel.CHAT,1_000);
        policy.forget(id);
        assertEquals(ChatModerationPolicy.Status.ALLOW, allow(policy,id,"first",ChatModerationPolicy.Channel.CHAT,1_001).status());
    }

    @Test void malformedRegexKeepsPreviousCompleteSnapshot() throws Exception {
        var policy = defaults(); var swears = load("anti-swear.yml"); swears.set("regex", java.util.List.of("["));
        assertThrows(IllegalArgumentException.class,
                () -> policy.reload(load("nicknames.yml"), swears, load("anti-spam.yml")));
        assertFalse(policy.isNicknameAllowed("Admin"));
        assertEquals("***", allow(policy,UUID.randomUUID(),"fuck",ChatModerationPolicy.Channel.CHAT,1_000).text());
    }
}
