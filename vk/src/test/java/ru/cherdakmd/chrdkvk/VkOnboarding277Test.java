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
    @Test void premiumReminderIsOncePerConnectionAndNeverAnAuthenticationPolicy() throws Exception {
        for(int rank=1;rank<=5;rank++) {
            assertFalse(VkOnboardingPolicy.suppress(rank,false));
            assertTrue(VkOnboardingPolicy.suppress(rank,true));
        }
        assertFalse(VkOnboardingPolicy.suppress(0,true));
        String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ru/cherdakmd/chrdkvk/VkService.java"));
        assertTrue(source.contains("premiumOnboardingShown.remove(event.getPlayer().getUniqueId())"));
        String reload=source.substring(source.indexOf("public synchronized void applyRuntimeConfig()"),source.indexOf("public synchronized void shutdown()"));
        assertFalse(reload.contains("premiumOnboardingShown.clear()"));
        assertFalse(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ru/cherdakmd/chrdkvk/VkOnboardingPolicy.java")).contains("vkTwoFactor"));
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
