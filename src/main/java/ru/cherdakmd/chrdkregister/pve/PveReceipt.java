package ru.cherdakmd.chrdkregister.pve;

import java.util.UUID;

/**
 * Квитанция: один получатель уже получил награду за одну смерть моба в один день.
 *
 * <p>Ключ защищает от повторного события в рамках реестра. Сам этот объект и
 * общий реестр в памяти не являются долговечными: защита после рестарта зависит
 * от хранилища конкретного модуля. Ключ состоит из трёх частей, поэтому смена получателя,
 * смерть другого моба или новый день не блокируются ошибочно.</p>
 */
public record PveReceipt(UUID deathId, PveRecipient recipient, String dayKey, int units) {

    public PveReceipt {
        if (deathId == null || recipient == null || dayKey == null || dayKey.isBlank() || units < 1) {
            throw new IllegalArgumentException("некорректная квитанция");
        }
    }

    public String key() {
        return deathId + "|" + recipient + "|" + dayKey;
    }
}
