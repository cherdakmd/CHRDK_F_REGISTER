package ru.cherdakmd.chrdkfishing.bukkit;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ru.cherdakmd.chrdkfishing.FishSigningKey;
import ru.cherdakmd.chrdkskills.ChrdkSkillsApi;

/** Prototype bootstrap. It is not yet the complete fishing product. */
public final class FishingPlugin extends JavaPlugin {
    @Override public void onEnable() {
        try {
            var core = getServer().getPluginManager().getPlugin("CHRDK_Core");
            if (core == null || !core.isEnabled()) throw new IllegalStateException("CHRDK_Core unavailable");
            Class<?> api = Class.forName("ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi", true, core.getClass().getClassLoader());
            var services = getServer().getServicesManager();
            var auth = new CoreAuthentication(services, api);
            var skills = services.load(ChrdkSkillsApi.class);
            if (!auth.available() || skills == null) throw new IllegalStateException("Core/Skills API unavailable");
            byte[] key = signingKey(getDataFolder().toPath());
            try {
                FishingActivation.install(this, getServer().getPluginManager(), services,
                        auth::authenticated, auth::available, Bukkit::isPrimaryThread, skills, key, 5000, new Random());
            } finally { java.util.Arrays.fill(key, (byte) 0); }
            getLogger().warning("CHRDK_Fishing DEV: экспериментальный кастомный улов включён (50% обычной ванильной рыбы). Продажа, альбом, удочки и готовка НЕ готовы. Не устанавливайте на production.");
        } catch (Exception | LinkageError failure) {
            getLogger().log(java.util.logging.Level.SEVERE, "CHRDK_Fishing остановлен без создания улова; сохраните данные и проверьте Core/Skills и ключ", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    /** Only initialise an empty, new folder; never replace a missing key in an established folder. */
    static byte[] signingKey(Path directory) throws java.io.IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new java.io.IOException("fishing directory invalid");
        Files.createDirectories(directory);
        Path key = directory.resolve("fish-signing.key");
        if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) return FishSigningKey.load(key);
        try (var files = Files.list(directory)) {
            if (files.findAny().isPresent()) throw new java.io.IOException("existing fishing data without key: manual recovery required");
        }
        return FishSigningKey.create(key);
    }
}
