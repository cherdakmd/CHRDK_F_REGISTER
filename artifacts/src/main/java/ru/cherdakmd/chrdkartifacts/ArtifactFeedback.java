package ru.cherdakmd.chrdkartifacts;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkregister.Messages;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обратная связь артефактов (1.4.130, аудит A-21/A-22): экшнбар и звуки.
 *
 * <p>До 1.4.130 активация подтверждалась только строкой в чате — она тонула
 * в бою, а остаток кулдауна был виден лишь в момент отказа. Теперь активный
 * артефакт отвечает звуком и коротким сообщением над хотбаром, а проклятия
 * не засоряют чат: повторные срабатывания одного и того же артефакта уходят
 * в экшнбар, пока не пройдёт {@code feedback.curse-message-seconds}.</p>
 *
 * <p>Оба эффекта отключаются в конфиге ({@code feedback.actionbar},
 * {@code feedback.sounds}); троттлинг проклятий — 0 означает «писать каждый
 * раз, как раньше».</p>
 */
public final class ArtifactFeedback {

    private static final int CURSE_MAP_LIMIT = 512;

    private final ArtifactsPlugin plugin;
    /** Когда снова можно писать о проклятии в чат: «uuid|ид» → unix-мс. */
    private final Map<String, Long> curseChatUntil = new ConcurrentHashMap<>();

    public ArtifactFeedback(ArtifactsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Активный артефакт сработал. */
    public void activated(Player player, ArtifactDef def) {
        if (player == null || def == null) {
            return;
        }
        if (actionbar()) {
            player.sendActionBar(plugin.messages().get("actionbar-activated",
                    "name", Messages.trusted(plugin.items().nameOf(def))));
        }
        if (sounds()) {
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.5f);
        }
    }

    /** Артефакт ещё на кулдауне. */
    public void cooldown(Player player, int seconds) {
        if (player == null) {
            return;
        }
        if (actionbar()) {
            player.sendActionBar(plugin.messages().get("actionbar-cooldown",
                    "seconds", String.valueOf(seconds)));
        }
        if (sounds()) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
        }
    }

    /** Не хватает уровней опыта. */
    public void notEnoughLevels(Player player, int need) {
        if (player == null) {
            return;
        }
        if (actionbar()) {
            player.sendActionBar(plugin.messages().get("actionbar-no-xp",
                    "need", String.valueOf(need)));
        }
        if (sounds()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
        }
    }

    /** Артефакт открыт в коллекции впервые. */
    public void discovered(Player player) {
        if (player == null || !sounds()) {
            return;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
    }

    /**
     * Можно ли написать о сработавшем проклятии в чат. Первое срабатывание
     * всегда пишется, повторные — только после паузы из конфига.
     */
    public boolean allowCurseChat(Player player, String artifactId) {
        if (player == null || artifactId == null) {
            return false;
        }
        int seconds = plugin.config().feedback().curseMessageSeconds();
        if (seconds <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        String key = player.getUniqueId() + "|" + artifactId;
        Long until = curseChatUntil.get(key);
        if (until != null && until > now) {
            return false;
        }
        if (curseChatUntil.size() > CURSE_MAP_LIMIT) {
            curseChatUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        curseChatUntil.put(key, now + seconds * 1000L);
        return true;
    }

    /** Короткая строка о проклятии над хотбаром (когда чат придержан). */
    public void curse(Player player, ArtifactDef def) {
        if (player == null || def == null || !actionbar()) {
            return;
        }
        player.sendActionBar(plugin.messages().get("actionbar-curse",
                "name", Messages.trusted(plugin.items().nameOf(def))));
    }

    /** Убирает троттлинг игрока при выходе. */
    public void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String prefix = playerId + "|";
        curseChatUntil.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void clear() {
        curseChatUntil.clear();
    }

    private boolean actionbar() {
        return plugin.config().feedback().actionbar();
    }

    private boolean sounds() {
        return plugin.config().feedback().sounds();
    }
}
