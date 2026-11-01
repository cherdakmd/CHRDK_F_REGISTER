package ru.cherdakmd.chrdkvk;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class VkOnboarding277Test {
    @Test void defaultReminderNeverRequiresGameplayRestrictionAndSanitizesInvite() {
        var yaml = new YamlConfiguration();
        yaml.set("onboarding.interval-seconds", 1);
        yaml.set("onboarding.chat-invite-url", "javascript:alert(1)");
        var cfg = VkConfig.parse(yaml);
        assertTrue(cfg.onboardingEnabled()); assertEquals(60, cfg.onboardingIntervalSeconds());
        assertEquals("", cfg.onboardingInviteUrl()); assertEquals(0, cfg.onboardingPeerId());
    }
    @Test void conversationMembershipIsReadFromActualMemberIds() throws Exception {
        var client = new VkApiClient("https://api.vk.com/method/", "SECRET", "5.199", new VkApiClient.Transport() {
            @Override public String postForm(URI uri, String body, Duration timeout) {
                assertTrue(uri.toString().endsWith("messages.getConversationMembers"));
                assertTrue(body.contains("peer_id=2000000001"));
                return "{\"response\":{\"count\":3,\"items\":[{\"member_id\":77},{\"member_id\":88},{\"member_id\":-123}]}}";
            }
            @Override public String get(URI uri, Duration timeout) { throw new AssertionError(); }
        });
        assertEquals(Set.of(77L,88L), client.fetchConversationMemberIds(123, 2000000001L));
    }
}
