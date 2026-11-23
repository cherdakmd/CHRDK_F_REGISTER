package ru.cherdakmd.chrdkenchants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cherdakmd.chrdkregister.Messages;
import java.nio.file.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class EnchantHubMessagesTest {
    @TempDir Path dir;
    @Test void oldStockHelpMigratesButCustomizedHelpStays() throws Exception {
        Path file=dir.resolve("messages.yml");
        String old=Files.readString(Path.of("src/main/resources/enchants-messages-legacy-hub.yml"));
        Files.writeString(file,old);
        var migrated=load(file);
        assertTrue(migrated.raw("help-overview").contains("/enchants catalog"));
        assertTrue(migrated.raw("usage").contains("catalog"));
        assertTrue(migrated.raw("hub-cleanse-lore").contains("подтвердите в меню"));
        assertEquals(old,Files.readString(file),"owner file is never rewritten");
        Files.writeString(file,"enchants:\n  usage: '<gray>Свой текст</gray>'\n  help-overview:\n  - '<gray>Своя справка</gray>'\n");
        var custom=load(file);
        assertEquals("<gray>Свой текст</gray>",custom.raw("usage"));
        assertEquals("<gray>Своя справка</gray>",custom.raw("help-overview"));
    }
    private Messages load(Path file)throws Exception {
        return Messages.fromChecked(Files.newInputStream(Path.of("src/main/resources/messages.yml")),file,
                Files.newInputStream(Path.of("src/main/resources/enchants-messages-legacy-hub.yml")),Logger.getAnonymousLogger());
    }
}
