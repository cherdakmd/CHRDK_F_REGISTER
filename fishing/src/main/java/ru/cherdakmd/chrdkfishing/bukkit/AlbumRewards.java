package ru.cherdakmd.chrdkfishing.bukkit;

import java.util.Objects;
import org.bukkit.entity.Player;
import ru.cherdakmd.chrdkfishing.AlbumRewardJournal;
import ru.cherdakmd.chrdkfishing.FishAlbum;

/** Server-derived album count only; neither inventory lore nor client counts authorize rewards. */
final class AlbumRewards implements AutoCloseable {
    private final AlbumRewardJournal journal;
    private final AlbumRewardJournal.Bank bank;
    private final FishAlbum album;
    private final java.util.function.Predicate<Player> authenticated;
    AlbumRewards(AlbumRewardJournal journal, AlbumRewardJournal.Bank bank, FishAlbum album, java.util.function.Predicate<Player> authenticated) {
        this.journal = Objects.requireNonNull(journal); this.bank = Objects.requireNonNull(bank); this.album = Objects.requireNonNull(album);
        this.authenticated = Objects.requireNonNull(authenticated);
    }
    private boolean eligible(Player player) {
        return player != null && org.bukkit.Bukkit.isPrimaryThread() && player.isOnline() && !player.hasMetadata("NPC")
                && authenticated.test(player) && (player.getGameMode() == org.bukkit.GameMode.SURVIVAL || player.getGameMode() == org.bukkit.GameMode.ADVENTURE);
    }
    int species(Player player) throws Exception { return album.records(player.getUniqueId()).size(); }
    AlbumRewardJournal.Progress progress(Player player) throws Exception { return journal.progress(player.getUniqueId()); }
    AlbumRewardJournal.Result claim(Player player) throws Exception {
        return eligible(player) ? journal.claim(player.getUniqueId(), species(player), bank) : AlbumRewardJournal.Result.WAIT;
    }
    AlbumRewardJournal.Result recover(Player player) throws Exception {
        return eligible(player) ? journal.recover(player.getUniqueId(), bank) : AlbumRewardJournal.Result.WAIT;
    }
    boolean pending(Player player) throws Exception {
        var progress = progress(player); return progress.targetStage() != progress.paidStage();
    }
    @Override public void close() throws Exception { journal.close(); }
}
