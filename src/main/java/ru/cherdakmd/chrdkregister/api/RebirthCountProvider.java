package ru.cherdakmd.chrdkregister.api;

import org.bukkit.entity.Player;
import java.util.OptionalInt;

/** Read-only online count for the player list. Empty means the data is unavailable, not zero. */
@FunctionalInterface
public interface RebirthCountProvider {
    OptionalInt rebirths(Player player);
}
