package ru.cherdakmd.chrdksocial;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Append-only журнал социальных действий: {@code logs/social/social-YYYY-MM-DD.jsonl}. */
public final class SocialAudit {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path folder;
    private final Logger logger;
    private final ZoneId zone;

    public SocialAudit(Path dataFolder, Logger logger, ZoneId zone) {
        this.folder = dataFolder.resolve("logs").resolve("social");
        this.logger = logger;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
    }

    public synchronized void write(String event, String player, String provider, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", System.currentTimeMillis());
        row.put("event", event);
        row.put("player", player == null ? "" : player);
        row.put("provider", provider == null ? "" : provider);
        row.put("detail", detail == null ? "" : detail);
        String line = GSON.toJson(row).replace("\n", " ").replace("\r", " ");
        Path file = folder.resolve("social-" + LocalDate.now(zone) + ".jsonl");
        try {
            Files.createDirectories(folder);
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            logger.log(Level.WARNING, "[CHRDK_SOCIAL] Не удалось записать аудит социального действия", failure);
        }
    }

    public static String line(String event, String player, String provider, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event", event);
        row.put("player", player);
        row.put("provider", provider);
        row.put("detail", detail);
        return GSON.toJson(row);
    }
}
