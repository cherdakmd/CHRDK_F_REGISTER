package ru.cherdakmd.chrdkbp;

import com.google.gson.JsonParser;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;

/** Durable witness: исчезновение банковского домена не считается новым пустым банком наград. */
final class BattlePassMoneySource {
    static final String SOURCE = "battlepass-rewards";
    static final UUID LINEAGE = UUID.nameUUIDFromBytes("CHRDK:BattlePass:money:v1".getBytes(StandardCharsets.UTF_8));
    private BattlePassMoneySource() { }

    static boolean initialize(Path folder, ChrdkRegisterApi core, Logger logger) {
        Path file = folder.resolve("battlepass-money.json");
        try {
            if (!core.personalBankAvailable()) return false;
            if (Files.exists(file)) {
                if (Files.size(file) > 1024) return false;
                var state = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (state.get("schema").getAsBigDecimal().intValueExact() != 1
                        || !state.get("registered").isJsonPrimitive()
                        || !state.get("registered").getAsJsonPrimitive().isBoolean()) return false;
                // Даже PREPARED не разрешает повторную регистрацию после неизвестного исхода.
                if (!core.incomeSourceAvailable(SOURCE, LINEAGE)) return false;
                if (!state.get("registered").getAsBoolean()) write(file, true);
                return true;
            }
            write(file, false); // Сначала witness, затем банковская регистрация, затем ACK.
            if (!core.registerIncomeSource(SOURCE, LINEAGE)) return false;
            write(file, true);
            return true;
        } catch (Exception failure) {
            logger.warning("BattlePass: денежный домен не подтверждён; сохраните bank.json и battlepass-money.json для сверки: " + failure.getMessage());
            return false;
        }
    }
    private static void write(Path file, boolean registered) throws Exception {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, "{\"schema\":1,\"registered\":" + registered + "}");
        try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var channel = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { channel.force(true); }
    }
}
