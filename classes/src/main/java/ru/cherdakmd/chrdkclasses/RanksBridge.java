package ru.cherdakmd.chrdkclasses;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.api.ChrdkRegisterApi;
import ru.cherdakmd.chrdkregister.api.RankBonus;

import java.util.UUID;

/**
 * Ступень донатного ранга через публичный API ядра (1.4.175).
 *
 * <p>{@code rankLevel} в ядре работает по нику и отвечает и для оффлайн-игрока,
 * поэтому имя берётся у онлайн-игрока, а если его нет — из {@link ClassStore},
 * где ник сохраняется при входе. Так плюшки ранга не «моргают» при переходе
 * между серверами и видны в админских командах.</p>
 *
 * <p>API v10 дополнительно даёт {@link RankBonus}; классам он не нужен (опыт и
 * добыча — не наша валюта), но проверка {@code rankBonus} подтверждает, что
 * ядро умеет ранги, а не только проходку: старое ядро вернёт {@code NONE} и
 * нулевую ступень.</p>
 */
final class RanksBridge implements RankSource {

    private final ClassesPlugin plugin;
    private ChrdkRegisterApi api;
    private boolean lookedUp;

    RanksBridge(ClassesPlugin plugin) {
        this.plugin = plugin;
    }

    /** API ядра или {@code null}; повторный поиск — только после reload. */
    ChrdkRegisterApi api() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                api = Bukkit.getServicesManager().load(ChrdkRegisterApi.class);
            } catch (RuntimeException | LinkageError error) {
                api = null;
                plugin.getLogger().warning("[CHRDK_Classes] API ядра для рангов недоступен: "
                        + error.getMessage());
            }
        }
        return api;
    }

    /** Забывает найденный сервис: после reload ядро могло обновиться. */
    void reset() {
        api = null;
        lookedUp = false;
    }

    @Override
    public boolean available() {
        ChrdkRegisterApi found = api();
        if (found == null) {
            return false;
        }
        try {
            RankBonus bonus = found.rankBonus("");
            return bonus != null;
        } catch (RuntimeException | LinkageError error) {
            return false;
        }
    }

    @Override
    public int rankLevel(UUID player) {
        ChrdkRegisterApi found = api();
        if (found == null || player == null) {
            return 0;
        }
        String name = nameOf(player);
        if (name.isEmpty()) {
            return 0;
        }
        try {
            return Math.clamp(found.rankLevel(name), 0, RankPerks.MAX_RANK);
        } catch (RuntimeException | LinkageError error) {
            // Ядро старше API v9: ранг не читается — играем по базовым правилам.
            return 0;
        }
    }

    /** Ник для запроса: у онлайн-игрока актуальный, иначе последний сохранённый. */
    private String nameOf(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null && !online.getName().isEmpty()) {
            return online.getName();
        }
        if (plugin.store() == null) {
            return "";
        }
        String saved = plugin.store().name(player);
        return saved == null ? "" : saved.trim();
    }
}
