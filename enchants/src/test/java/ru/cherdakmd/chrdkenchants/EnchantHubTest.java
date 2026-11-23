package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnchantHubTest {
    private String source(String name)throws Exception {return Files.readString(Path.of("src/main/java/ru/cherdakmd/chrdkenchants/"+name+".java"));}
    @Test void buttonsHaveDistinctSlotsAndCoverPlayerFunctions() {
        var buttons=EnchantHub.buttons();var slots=new HashSet<Integer>();
        for(var button:buttons){assertTrue(button.slot()>=0 && button.slot()<54);assertTrue(slots.add(button.slot()));assertFalse(button.slot()==34 || button.slot()==49);}
        var keys=new HashSet<String>();buttons.forEach(b->keys.add(b.key()));
        assertTrue(keys.containsAll(Set.of("catalog","library","scribe","inspect","hand","sources","anvil",
                "cleanse","combine","guard","cooldowns","books","workshop","effects","light","curses","search","help")));
        assertTrue(buttons.stream().filter(b->Set.of("scribe","cleanse","combine","inspect","hand").contains(b.key())).allMatch(EnchantHub.Button::handSensitive));
    }
    @Test void navigationRespectsOwnerAndStalenessAndDelegatesOperations() throws Exception {
        String hub=source("EnchantHub"),command=source("EnchantCommandHandler"),plugin=source("EnchantsPlugin");
        assertTrue(command.contains("plugin.hub().open(player)"));assertTrue(command.contains("equalsIgnoreCase(\"catalog\")"));
        assertTrue(hub.contains("event.setCancelled(true)"));assertTrue(hub.contains("holder.owner.equals(player.getUniqueId())"));
        assertTrue(hub.contains("holder.generation==plugin.sourceGeneration()"));assertTrue(hub.contains("getTopInventory().getHolder()==holder"));
        assertTrue(hub.contains("plugin.getCommand(parts[0])"));assertTrue(hub.contains("bound.execute(player,parts[0]"));
        assertFalse(hub.contains("setStorageContents"));assertFalse(hub.contains("addItem("));
        assertTrue(plugin.contains("hub.closeAll()"));
    }
    @Test void instructionsDoNotPretendToPreviewClosedAnvil() throws Exception {
        String hub=source("EnchantHub");assertTrue(hub.contains("hub-anvil-instruction"));
        assertFalse(hub.contains("command(player,holder,\"enchants preview\""));
        String messages=Files.readString(Path.of("src/main/resources/messages.yml"));
        assertTrue(messages.contains("затем введите /enchants preview"));assertTrue(messages.contains("подтверждение и цена — в чате"));
        for(var button:EnchantHub.buttons()) {
            assertTrue(messages.contains("hub-"+button.key()+":"),button.key());
            assertTrue(messages.contains("hub-"+button.key()+"-lore:"),button.key());
        }
    }
}
