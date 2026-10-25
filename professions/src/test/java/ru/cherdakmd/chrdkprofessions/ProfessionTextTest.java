package ru.cherdakmd.chrdkprofessions;

import org.junit.jupiter.api.Test;
import ru.cherdakmd.chrdkregister.Messages;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Показ результатов и прибавок (1.4.165).
 *
 * <p>Сервис возвращает иды ({@code smith}), а игрок должен видеть «Кузнец».
 * Перевод живёт в одном месте, поэтому команда, меню и профиль не могут
 * разойтись. Опечатка в идe остаётся как есть — игрок должен увидеть то, что
 * ввёл, а не пустую строку.</p>
 */
class ProfessionTextTest {

    private static final Logger LOGGER = Logger.getLogger("ProfessionTextTest");

    private static Messages messages() {
        String yaml = """
                professions:
                  profession.smith.name: "Кузнец"
                  profession.mason.name: "Камнерез"
                  perk.none: "пока нет"
                  perk.smith: "Скидка %value%"
                  rank.novice: "новичок"
                  rank.master: "мастер"
                """;
        return Messages.from(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                null, LOGGER);
    }

    @Test
    void knownIdsBecomeNames() {
        ProfessionResult chosen = ProfessionResult.ok("profession-chosen",
                "profession", "smith");
        assertEquals(List.of("profession", "Кузнец"),
                ProfessionText.localise(messages(), chosen));

        ProfessionResult changed = ProfessionResult.ok("profession-changed",
                "from", "smith", "to", "mason");
        assertEquals(List.of("from", "Кузнец", "to", "Камнерез"),
                ProfessionText.localise(messages(), changed));

        ProfessionResult upper = ProfessionResult.ok("profession-chosen",
                "profession", "SMITH");
        assertEquals(List.of("profession", "Кузнец"),
                ProfessionText.localise(messages(), upper), "ид не зависит от регистра");
    }

    @Test
    void unknownIdsAndNumbersStay() {
        ProfessionResult unknown = ProfessionResult.fail("profession-unknown",
                "profession", "паладин");
        assertEquals(List.of("profession", "паладин"),
                ProfessionText.localise(messages(), unknown));

        ProfessionResult numbers = ProfessionResult.ok("admin-xp-done",
                "profession", "mason", "amount", "130", "level", "2");
        assertEquals(List.of("profession", "Камнерез", "amount", "130", "level", "2"),
                ProfessionText.localise(messages(), numbers));

        assertEquals(List.of(), ProfessionText.localise(messages(), null));
    }

    @Test
    void perkTextFollowsLevel() {
        Messages messages = messages();
        assertEquals("Скидка 20",
                ProfessionText.perk(messages, Profession.SMITH, 10).text());
        assertEquals("пока нет",
                ProfessionText.perk(messages, Profession.COOK, 5).text(),
                "до 10-го уровня повар ещё не кормит сытнее");
        assertEquals("", ProfessionText.perkRaw(messages, null, 10));
        assertEquals("", ProfessionText.perk(null, Profession.SMITH, 10).text());
    }

    @Test
    void namesAndRanksFallBackGracefully() {
        Messages messages = messages();
        assertEquals("Кузнец", ProfessionText.namePlain(messages, Profession.SMITH));
        assertEquals("Камнерез", ProfessionText.namePlain(messages, Profession.MASON));
        assertEquals("", ProfessionText.namePlain(messages, null));
        assertEquals("", ProfessionText.namePlain(null, Profession.SMITH));

        assertEquals("новичок", ProfessionText.rankPlain(messages, 1));
        assertEquals("мастер", ProfessionText.rankPlain(messages, 10));
        assertEquals("rank.magister", ProfessionText.rankPlain(messages, 25),
                "недостающий ключ виден как ключ, а не как пустота");
        assertEquals("", ProfessionText.rankPlain(null, 1));
    }
}
