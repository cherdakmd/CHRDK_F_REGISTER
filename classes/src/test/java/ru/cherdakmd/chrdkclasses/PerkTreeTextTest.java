package ru.cherdakmd.chrdkclasses;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;
import ru.cherdakmd.chrdkskills.tree.PerkNode;
import ru.cherdakmd.chrdkskills.tree.PerkTreeDefinition;
import ru.cherdakmd.chrdkskills.tree.PerkTreeRegistry;
import ru.cherdakmd.chrdkskills.tree.TreeDomain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * В /perktree иконка раньше называлась голым id (defense-telegraph) и не имела
 * описания. Тест следит, что КАЖДЫЙ зарегистрированный узел и домен получили
 * русские название и описание в messages.yml.
 */
class PerkTreeTextTest {

    private static final Pattern KEY = Pattern.compile("^  ([a-z][a-z0-9._-]*):(?:\\s.*)?$");
    private static final Pattern DESC_LINE = Pattern.compile("^    -\\s+'(.*)'$");
    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");

    private static Map<String, String> readKeys() throws Exception {
        Map<String, String> keys = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("src/main/resources/messages.yml"),
                StandardCharsets.UTF_8)) {
            Matcher matcher = KEY.matcher(line);
            if (matcher.matches()) {
                keys.put(matcher.group(1), line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return keys;
    }

    @Test
    void everyDomainHasRussianName() throws Exception {
        Map<String, String> keys = readKeys();
        for (TreeDomain domain : TreeDomain.values()) {
            String key = "perktree.domain." + domain.id();
            assertTrue(keys.containsKey(key), "нет перевода домена " + domain.id());
            assertTrue(CYRILLIC.matcher(keys.get(key)).find(),
                    "домен " + domain.id() + " переведён не по-русски");
        }
    }

    @Test
    void everyRegisteredNodeHasNameAndDescription() throws Exception {
        Map<String, String> keys = readKeys();
        Set<String> seen = new HashSet<>();
        for (TreeDomain domain : PerkTreeRegistry.defined()) {
            PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElseThrow();
            for (PerkNode node : definition.nodes()) {
                seen.add(node.id());
                String nameKey = "perktree.node." + node.id() + ".name";
                String descKey = "perktree.node." + node.id() + ".desc";
                assertTrue(keys.containsKey(nameKey), "нет русского названия узла " + node.id());
                assertTrue(keys.containsKey(descKey), "нет описания узла " + node.id());
                assertTrue(CYRILLIC.matcher(keys.get(nameKey)).find(),
                        "название узла " + node.id() + " не по-русски");
                assertFalse(keys.get(nameKey).contains(node.id()),
                        "в названии узла " + node.id() + " остался голый id");
                int descriptionLines = countDescriptionLines(node.id());
                assertTrue(descriptionLines >= 2,
                        "у узла " + node.id() + " описание должно объяснять действие и границу QoL-only");
            }
        }
        assertTrue(seen.size() >= 40, "реестр деревьев должен оставаться полным");
        assertTrue(seen.contains("defense-telegraph"),
                "узел со скриншота обязан быть переведён");
    }

    @Test
    void iconLoreKeysArePresent() throws Exception {
        Map<String, String> keys = readKeys();
        for (String key : new String[]{
                "perktree-node-tier", "perktree-node-cost", "perktree-node-open",
                "perktree-node-available", "perktree-node-locked-tier",
                "perktree-node-requires", "perktree-node-core", "perktree-node-ability",
                "perktree-node-hint-unlock", "perktree-node-hint-select",
                "perktree-node-no-desc"}) {
            assertTrue(keys.containsKey(key), "нет служебной строки подсказки " + key);
        }
    }


    @Test
    void realMessagesLoaderResolvesEveryTranslation() throws Exception {
        Messages messages = Messages.from(
                Files.newInputStream(Path.of("src/main/resources/messages.yml")), null,
                Logger.getLogger("PerkTreeTextTest"));
        for (TreeDomain domain : TreeDomain.values()) {
            assertTrue(messages.has("perktree.domain." + domain.id()),
                    "загрузчик не видит имя домена " + domain.id());
        }
        for (TreeDomain domain : PerkTreeRegistry.defined()) {
            PerkTreeDefinition definition = PerkTreeRegistry.of(domain).orElseThrow();
            for (PerkNode node : definition.nodes()) {
                assertTrue(messages.has("perktree.node." + node.id() + ".name"));
                assertTrue(messages.has("perktree.node." + node.id() + ".desc"));
            }
        }
        assertEquals("Защита", messages.raw("perktree.domain.defense"));
        assertEquals("Предвестник удара",
                Messages.stripTags(messages.raw("perktree.node.defense-telegraph.name")));
        assertEquals(2,
                messages.getList("perktree.node.defense-telegraph.desc").size(),
                "узел со скриншота должен объяснять действие и границу QoL-only");
        assertTrue(messages.has("perktree-node-hint-unlock"));
    }

    private static int countDescriptionLines(String nodeId) throws Exception {
        boolean inside = false;
        int count = 0;
        for (String line : Files.readAllLines(Path.of("src/main/resources/messages.yml"),
                StandardCharsets.UTF_8)) {
            if (line.startsWith("  perktree.node." + nodeId + ".desc:")) {
                inside = true;
                continue;
            }
            if (inside) {
                Matcher matcher = DESC_LINE.matcher(line);
                if (matcher.matches()) {
                    assertTrue(CYRILLIC.matcher(matcher.group(1)).find(),
                            "строка описания " + nodeId + " не по-русски");
                    count++;
                } else {
                    break;
                }
            }
        }
        return count;
    }
}
