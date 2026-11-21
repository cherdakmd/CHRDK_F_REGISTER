package ru.cherdakmd.chrdkitems;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CatalogCommandTest {
    @Test void catalogCommandsOnlyReadAndRespectPermission() {
        List<String> messages = new ArrayList<>();
        boolean[] permitted = {true};
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) return permitted[0];
                    if (method.getName().equals("sendMessage") && args.length == 1 && args[0] instanceof Component c) {
                        messages.add(PlainTextComponentSerializer.plainText().serialize(c)); return null;
                    }
                    throw new AssertionError("Unexpected call: " + method.getName());
                });
        var definition = new ItemCatalog.Definition("scroll", ItemCatalog.Category.CONSUMABLE,
                Material.PAPER, "chrdk_items:item/scroll", "Свиток", List.of("Тест"));
        var command = new CatalogCommand(Map.of("scroll", definition));
        assertTrue(command.execute(sender, new String[]{"consumable"}));
        assertTrue(messages.stream().anyMatch(s -> s.contains("Свиток [scroll]")));
        messages.clear();
        assertTrue(command.execute(sender, new String[]{"info", "scroll"}));
        assertTrue(messages.stream().anyMatch(s -> s.contains("ещё не реализованы")));
        messages.clear(); permitted[0] = false;
        assertTrue(command.execute(sender, new String[]{"info", "scroll"}));
        assertEquals(1, messages.size()); assertTrue(messages.getFirst().contains("Нет права"));
    }
}
