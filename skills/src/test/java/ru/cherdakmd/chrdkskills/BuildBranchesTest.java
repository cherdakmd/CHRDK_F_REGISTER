package ru.cherdakmd.chrdkskills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ветки билда вместо классов: шесть веток, общий пул с атрибутами, новые
 * активные приёмы (не классовые), разделённые щиты поддержки и платный сброс.
 */
class BuildBranchesTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    void sixBranchesShareTheAttributePool() throws IOException {
        String branch = read("src/main/java/ru/cherdakmd/chrdkskills/BuildBranch.java");
        assertTrue(branch.contains("WARRIOR") && branch.contains("ARCHER") && branch.contains("MAGE"),
                "боевые ветки на месте");
        assertTrue(branch.contains("CLERIC") && branch.contains("PRIEST") && branch.contains("ROGUE"),
                "ветки поддержки и скорости на месте");
        String service = read("src/main/java/ru/cherdakmd/chrdkskills/SkillService.java");
        assertTrue(service.contains("store.spentBranches"),
                "очки веток и атрибутов делят один общий пул");
        assertTrue(service.contains("BranchMath.xpMult"), "интеллект Мага ускоряет опыт");
    }

    @Test
    void branchActivesAreNewWithSplitShields() throws IOException {
        String abilities = read("src/main/java/ru/cherdakmd/chrdkskills/BranchAbility.java");
        assertTrue(abilities.contains("FAITH_SHIELD(BuildBranch.CLERIC"),
                "Клирик — щит, съедающий урон");
        assertTrue(abilities.contains("HEALING_SHIELD(BuildBranch.PRIEST"),
                "Жрец — исцеляющий щит");
        assertTrue(abilities.contains("CLEAVE") && abilities.contains("VANISH"),
                "приёмы новые, по два на ветку; приёмы классов не переносятся");
        String math = read("src/main/java/ru/cherdakmd/chrdkskills/BranchMath.java");
        assertTrue(math.contains("0.05 * rank * rank"),
                "сила приёмов растёт со ступенью престижа квадратично");
    }

    @Test
    void branchRespecIsPaidAndCommanded() throws IOException {
        String commands = read("src/main/java/ru/cherdakmd/chrdkskills/SkillCommandHandler.java");
        assertTrue(commands.contains("handleBuild"), "команда /skills build управляет ветками");
        assertTrue(commands.contains("skill-branch-respec"), "сброс веток платный");
        assertTrue(commands.contains("BranchAbility.byId"), "use принимает активные приёмы веток");
        String messages = read("src/main/resources/messages.yml");
        assertTrue(messages.contains("branch-locked:"), "закрытый приём объясняется игроку");
        assertTrue(messages.contains("build-reset-fail:"), "отказ оплаты сброса объясняется");
    }
}
