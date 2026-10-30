package ru.cherdakmd.chrdkguilds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GuildVisibilityTest {
    @Test void privateChatNeedsAllThreeGatesForEveryRecipient() {
        for (boolean enabled : new boolean[]{false,true})
            for (boolean auth : new boolean[]{false,true})
                for (boolean permission : new boolean[]{false,true})
                    assertEquals(enabled && auth && permission, GuildVisibility.allowed(enabled,auth,permission));
    }
}
