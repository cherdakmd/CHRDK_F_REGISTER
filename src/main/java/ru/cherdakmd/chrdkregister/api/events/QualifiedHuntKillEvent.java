package ru.cherdakmd.chrdkregister.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/** Синхронное уведомление Hunt после допуска награды и проверки природной уровневой метки.
 * Не отменяет смерть/ванильный дроп и не является API выдачи денег. */
public final class QualifiedHuntKillEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final int level;
    private final int elite;
    public QualifiedHuntKillEvent(Player player, int level, int elite) {
        super(player);
        if (level < 1 || level > 30 || elite < 0 || elite > ru.cherdakmd.chrdkregister.api.MobProgressionKeys.MAX_ELITE)
            throw new IllegalArgumentException("Invalid natural Hunt progression");
        this.level = level; this.elite = elite;
    }
    public int level() { return level; }
    public int elite() { return elite; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
