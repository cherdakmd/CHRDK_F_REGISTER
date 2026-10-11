package ru.cherdakmd.chrdkregister;

import net.luckperms.api.LuckPerms;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Мягкие интеграции со сторонними плагинами: Vault (экономика и права) и LuckPerms.
 *
 * <p>Все три зависимости опциональны: классы подключены как compileOnly, сервисы ищутся через
 * {@link ServicesManager} и возвращаются как {@link Optional}. Разрешение повторяется при каждом
 * {@code /pass reload} — можно поставить/снять Vault без перезапуска.</p>
 */
public final class ExternalHooks {

    private final ServicesManager services;
    private final Logger logger;

    private volatile Economy economy;
    private volatile Permission permission;
    private volatile LuckPerms luckPerms;

    public ExternalHooks(JavaPlugin plugin) {
        this.services = plugin.getServer().getServicesManager();
        this.logger = plugin.getLogger();
        resolve();
    }

    /** Перечитывает привязки к Vault/LuckPerms (вызывается при включении и /pass reload). */
    public final void resolve() {
        this.economy = economyService();
        this.permission = permissionService();
        this.luckPerms = luckPermsService();
        logger.info("Внешние интеграции: Vault экономика=" + (economy != null)
                + ", Vault права=" + (permission != null)
                + ", LuckPerms=" + (luckPerms != null));
    }

    private Economy economyService() {
        try {
            RegisteredServiceProvider<Economy> registration = services.getRegistration(Economy.class);
            return registration == null ? null : registration.getProvider();
        } catch (LinkageError e) {
            return null; // Vault не установлен
        }
    }

    private Permission permissionService() {
        try {
            RegisteredServiceProvider<Permission> registration = services.getRegistration(Permission.class);
            return registration == null ? null : registration.getProvider();
        } catch (LinkageError e) {
            return null;
        }
    }

    private LuckPerms luckPermsService() {
        try {
            RegisteredServiceProvider<LuckPerms> registration = services.getRegistration(LuckPerms.class);
            return registration == null ? null : registration.getProvider();
        } catch (LinkageError e) {
            return null;
        }
    }

    /** Экономика Vault (пусто, если Vault не установлен). */
    public Optional<Economy> economy() {
        return Optional.ofNullable(economy);
    }

    /** Сервис прав Vault. */
    public Optional<Permission> permissions() {
        return Optional.ofNullable(permission);
    }

    /** LuckPerms API. */
    public Optional<LuckPerms> luckPerms() {
        return Optional.ofNullable(luckPerms);
    }
}
