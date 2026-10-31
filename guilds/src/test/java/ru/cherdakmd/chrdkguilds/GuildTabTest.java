package ru.cherdakmd.chrdkguilds;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class GuildTabTest {
    @Test void guiAndMenuShareRouteIncludingCaseInsensitiveAliases() {
        assertEquals("menu",GuildsCommandHandler.canonicalSubcommand("gui"));
        assertEquals("menu",GuildsCommandHandler.canonicalSubcommand("GUI"));
        assertEquals("menu",GuildsCommandHandler.canonicalSubcommand("menu"));
        assertEquals("mode",GuildsCommandHandler.canonicalSubcommand("mode"));
    }
    @Test void modeChoosesRedBattleOrGreenPeaceWithExplicitWarmupMarker() {
        var y=YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/messages.yml"),StandardCharsets.UTF_8));
        assertEquals("tab-battle-prefix",GuildNameTag.tabKey(false,false));
        for(boolean protectedNow:new boolean[]{false,true}) {
            String value=y.getString("guilds."+GuildNameTag.tabKey(true,protectedNow));
            assertNotNull(value);assertTrue(value.contains("<green>"));assertTrue(value.contains(protectedNow?"☮":"⌛"));assertTrue(value.contains("%tag%"));
        }
        assertTrue(y.getString("guilds."+GuildNameTag.tabKey(false,true)).contains("<red>"));
    }
    @Test void guiModeLabelsHaveSameColorsAndReadableNames() {
        var y=YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/messages.yml"),StandardCharsets.UTF_8));
        assertEquals("<red>Боевой клан</red>",y.getString("guilds.menu-mode-battle"));
        assertEquals("<green>Мирный клан</green>",y.getString("guilds.menu-mode-peaceful"));
    }
}
