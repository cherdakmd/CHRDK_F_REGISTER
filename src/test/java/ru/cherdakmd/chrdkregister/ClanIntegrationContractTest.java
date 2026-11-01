package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.api.ClanMembershipIndex;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Межмодульные соединения: закрытый чат, динамический сервис и проверки после ожидания TP. */
class ClanIntegrationContractTest {
    String source(String file) throws Exception { return Files.readString(Path.of(file)); }
    @Test void stableIdIsRequiredAndLabelsCannotReplaceIt() {
        assertThrows(IllegalArgumentException.class,()->new ClanMembershipIndex.Membership(" ","name","tag","leader"));
        assertThrows(IllegalArgumentException.class,()->new ClanMembershipIndex.Membership("a".repeat(129),"name","tag","leader"));
        var member = new ClanMembershipIndex.Membership("id",null,null,null);
        assertEquals("id",member.id()); assertEquals("",member.name());
        assertEquals(34,ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi.API_VERSION);
    }
    @Test void commandAndRecipientsUseThePrivateBoundary() throws Exception {
        String handler=source("guilds/src/main/java/ru/cherdakmd/chrdkguilds/GuildsCommandHandler.java");
        int chat=handler.indexOf("equalsIgnoreCase(\"gchat\")");
        assertTrue(chat>handler.indexOf("!plugin.authenticated(actor)"));
        assertTrue(chat>handler.indexOf("!sender.hasPermission(\"chrdkguilds.use\")"));
        assertTrue(chat>handler.indexOf("!plugin.config().enabled()"));
        assertTrue(handler.indexOf("equalsIgnoreCase(\"guildadmin\")")<handler.indexOf("!plugin.config().enabled()"));
        assertTrue(handler.contains("if (plugin.visible(online))"));
        String listener=source("guilds/src/main/java/ru/cherdakmd/chrdkguilds/GuildsListener.java");
        assertFalse(listener.contains("void onJoin(")); assertTrue(listener.contains("remind(event.getPlayer())"));
    }
    @Test void serviceIsDynamicAndTeleportStillRechecksEffectiveAccess() throws Exception {
        String claims=source("claims/src/main/java/ru/cherdakmd/chrdkclaims/ClaimsPlugin.java");
        assertTrue(claims.contains("store.membershipIndex(this::clanMembership)"));
        assertTrue(claims.contains("getServicesManager().load(ru.cherdakmd.chrdkregister.api.ClanMembershipIndex.class)"));
        String teleports=source("claims/src/main/java/ru/cherdakmd/chrdkclaims/ClaimTeleportService.java");
        assertTrue(teleports.split("\\.canUse\\(",-1).length>=3,"Права нужны перед ожиданием и перед отправлением");
        String guilds=source("guilds/src/main/java/ru/cherdakmd/chrdkguilds/GuildsPlugin.java");
        assertTrue(guilds.contains("getServicesManager().unregisterAll(this)"));
    }
}
