package ru.cherdakmd.chrdkregister;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import ru.cherdakmd.chrdkregister.api.events.PlayerAuthCompletedEvent;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Книга-путеводитель для новичков (1.4.107): короткая инструкция по серверу в виде
 * письменной книги. Выдаётся один раз — при первом входе после регистрации/входа,
 * повторно доступна командой {@code /guide}.
 *
 * <p>Тексты живут в {@code messages.yml} ({@code guide.title}, {@code guide.author},
 * {@code guide.pages}), поэтому администратор правит их без пересборки. Факт выдачи
 * хранится в {@code guide-data.json}: игравшим до обновления книгу при входе не
 * навязываем, но они могут взять её командой.</p>
 */
public final class GuideBookService implements Listener {

    private final RegisterPlugin plugin;
    private final GuideStore store;
    private volatile GuideBookSettings settings;

    public GuideBookService(RegisterPlugin plugin, GuideStore store, GuideBookSettings settings) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
    }

    /** Загрузка памяти и сводка в лог (вызывается из {@code onEnable}). */
    public void start() {
        store.load();
        GuideBookSettings current = settings;
        logger().info("[guide] книга-путеводитель: " + (current.enabled() ? "включена" : "выключена")
                + ", новичкам при первом входе: " + (current.giveOnFirstJoin() ? "да" : "нет")
                + ", уже получили: " + store.givenCount());
    }

    /** {@code /pass reload}: новые значения без пересоздания слушателя. */
    public void reload(GuideBookSettings settings) {
        this.settings = settings;
    }

    public GuideBookSettings settings() {
        return settings;
    }

    public void shutdown() {
        store.saveNow();
    }

    /* ------------------------------- выдача ------------------------------- */

    /**
     * Первый заход: новичка запоминаем, чтобы книга дошла до него даже после выхода
     * до регистрации. Игравших раньше помечаем как «книга не нужна».
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String key = GuideStore.key(player.getName());
        if (store.isGiven(key) || store.isPending(key)) {
            return;
        }
        if (player.hasPlayedBefore()) {
            store.markGiven(key);
        } else {
            store.markPending(key);
        }
    }

    /** Игрок завершил вход (пароль и 2FA) — выдаём книгу, если он новичок. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthCompleted(PlayerAuthCompletedEvent event) {
        Player player = event.getPlayer();
        String key = GuideStore.key(player.getName());
        if (!store.isPending(key)) {
            return;
        }
        store.markGiven(key);
        if (settings.giveOnFirstJoin()) {
            give(player, true);
        }
    }

    /**
     * Выдаёт книгу игроку (или выбрасывает рядом, если инвентарь полон).
     *
     * @param announce показывать ли игроку подтверждение
     * @return {@code true}, если книга создана и передана
     */
    public boolean give(Player player, boolean announce) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (!settings.enabled()) {
            plugin.messages().send(player, "guide.disabled");
            return false;
        }
        GuideBookContent content = GuideBookContent.from(plugin.messages());
        if (!content.isUsable()) {
            logger().warning("[guide] книга не создана: в messages.yml нет guide.title или guide.pages");
            return false;
        }
        ItemStack book = createBook(content);
        if (book == null) {
            logger().warning("[guide] не удалось создать письменную книгу (BookMeta недоступен)");
            return false;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(book);
        leftover.values().forEach(rest ->
                player.getWorld().dropItemNaturally(player.getLocation(), rest));
        logger().info("[guide] книга-путеводитель выдана игроку " + player.getName()
                + " (страниц: " + content.pages().size() + ")");
        if (announce) {
            plugin.messages().send(player, "guide.received");
        }
        return true;
    }

    /** Собирает предмет книги из готового содержимого. Публичный — для тестов и аддонов. */
    public static ItemStack createBook(GuideBookContent content) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        if (!(book.getItemMeta() instanceof BookMeta meta)) {
            return null;
        }
        meta.title(Component.text(content.title()));
        meta.author(Component.text(content.author()));
        meta.pages(content.pages());
        book.setItemMeta(meta);
        return book;
    }

    private Logger logger() {
        return plugin.getLogger();
    }
}
