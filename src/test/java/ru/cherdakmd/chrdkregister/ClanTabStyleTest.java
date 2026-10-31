package ru.cherdakmd.chrdkregister;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ClanTabStyleTest {
    @TempDir Path dir;
    final AtomicReference<String> clan=new AtomicReference<>("<red>[TAG]</red> ");
    LabelPreferences prefs() { var p=new LabelPreferences(dir,Logger.getAnonymousLogger());p.load();return p; }
    LabelStyleService service(LabelPreferences p) {
        return new LabelStyleService((id,name,base)->switch(id) {
            case "guilds-tab" -> clan.get()+base;
            case "guilds" -> "<gold>[TAG]</gold> "+base;
            case "classes" -> "[Класс] "+base;
            case "achievements" -> "[Титул] "+base;
            default -> base;
        },p,new LabelStyleService.RankLookup() {
            public LabelStyleService.Rank view(String name){return null;}
            public List<String> registrationOrder(){return List.of("classes","guilds");}
        });
    }
    @Test void clanVisibleBeyondFullFreeCosmeticSlotsWithoutChangingAssignments() {
        var p=prefs();p.setSlots("Alex",List.of("classes","achievements","guilds"));var s=service(p);
        var before=s.assignments("Alex");
        assertEquals("Alex · [Класс] · [Титул] · [TAG]",Messages.stripFormatting(s.decorateForTab("Alex","Alex",null)));
        assertEquals(before,s.assignments("Alex"));assertEquals(2,s.selection("Alex").limit());
    }
    @Test void selectedGuildIsNotDuplicatedAndChatStillUsesSelectedStyle() {
        var p=prefs();p.setSlots("Alex",List.of("guilds","classes"));var s=service(p);
        assertEquals("Alex · [Класс] · [TAG]",Messages.stripFormatting(s.decorateForTab("Alex","Alex",null)));
        assertEquals("Alex · [TAG] · [Класс]",Messages.stripFormatting(s.decorate("Alex","Alex",null)));
    }
    @Test void hiddenCosmeticGuildDoesNotHidePvpStatusOrRestoreChatLabel() {
        var p=prefs();p.setSlots("Alex",List.of("guilds"));p.set("Alex",LabelPreferences.Label.GUILDS,false);var s=service(p);
        assertEquals("Alex",Messages.stripFormatting(s.decorate("Alex","Alex",null)));
        assertEquals("Alex · [TAG]",Messages.stripFormatting(s.decorateForTab("Alex","Alex",null)));
    }
    @Test void modeAndMembershipAreReadAgainWithoutRecreatingService() {
        var s=service(prefs());assertTrue(s.decorateForTab("Alex","Alex",null).contains("<red>"));
        clan.set("<green>[☮ NEW]</green> ");String green=s.decorateForTab("Alex","Alex",null);
        assertTrue(green.contains("<green>"));assertFalse(green.contains("[TAG]"));
        clan.set("");assertFalse(s.decorateForTab("Alex","Alex",null).contains("NEW"));
    }
    @Test void absentProviderDoesNotLeaveEmptySeparatorOrColorName() {
        var p=prefs();p.setSlots("Alex",List.of());var s=service(p);clan.set("");
        assertEquals("Alex",s.decorateForTab("Alex","Alex",null));
        clan.set("<red>[TAG]</red> ");String markup=s.decorateForTab("Alex","Alex",null);
        assertTrue(markup.startsWith("Alex "));assertTrue(markup.endsWith("<reset>"));
    }
    @Test void invalidProviderCannotDuplicateOrReplaceNickname() {
        var p=prefs();p.setSlots("Alex",List.of());
        var s=new LabelStyleService((id,name,base)->"<red>Forged</red>",p,new LabelStyleService.RankLookup(){
            public LabelStyleService.Rank view(String n){return null;}
            public List<String> registrationOrder(){return List.of();}
        });
        assertEquals("Alex",s.decorateForTab("Alex","Alex",null));
    }
}
