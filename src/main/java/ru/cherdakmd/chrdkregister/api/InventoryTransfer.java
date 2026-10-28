package ru.cherdakmd.chrdkregister.api;

import java.io.IOException;
import java.util.UUID;

/** API 22: native storage + nonce в одном playerdata до публикации финансового source.
 * Совпадение AFTER без nonce не доказывает переход. Повторный nonce не разрешает
 * повторное списание/выдачу, но требует checkpoint: прошлый saveData мог выбросить исключение.
 */
public final class InventoryTransfer {
    public record Intent(UUID transaction,UUID owner,String before,String after) {
        public Intent {
            if(transaction==null||owner==null||before==null||after==null||before.equals(after)
                    ||before.length()>4*1024*1024||after.length()>4*1024*1024) throw new IllegalArgumentException("inventory transfer intent");
        }
    }
    public enum Decision { WAIT, APPLIED, CONFLICT }
    public interface Inventory {
        UUID owner();
        boolean available();
        String witness();
        boolean matches(String snapshot);
        /** Применить storage и nonce и синхронно сохранить playerdata. Не откатывать при неизвестном IO. */
        void apply(String after,String witness) throws IOException;
        /** Сохранить ТЕКУЩИЙ storage+nonce, ничего не заменять старым снимком. */
        default void checkpoint() throws IOException { throw new IOException("Native checkpoint не реализован; публикация запрещена"); }
    }
    public static Decision recover(Intent intent,Inventory inventory) throws IOException {
        if(intent==null) throw new IllegalArgumentException("missing inventory intent");
        if(inventory==null||!intent.owner().equals(inventory.owner())||!inventory.available()) return Decision.WAIT;
        String token=intent.transaction().toString();
        if(token.equals(inventory.witness())) {
            inventory.checkpoint();
            return token.equals(inventory.witness())?Decision.APPLIED:Decision.CONFLICT;
        }
        if(!inventory.matches(intent.before())||inventory.matches(intent.after())) return Decision.CONFLICT;
        inventory.apply(intent.after(),token);
        return token.equals(inventory.witness())&&inventory.matches(intent.after())?Decision.APPLIED:Decision.CONFLICT;
    }
    private InventoryTransfer() { }
}
