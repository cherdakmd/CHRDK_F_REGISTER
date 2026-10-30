package ru.cherdakmd.chrdkclasses;

/**
 * Узел дерева талантов (1.4.164).
 *
 * <p>Талант — одна пассивная прибавка. Он открывается за очки, которые дают
 * уровни навыков, и требует двух вещей: предыдущий талант своей ветки и
 * уровень конкретного навыка. Поэтому дерево не проходится «в один заход»:
 * чтобы взять верхний узел, надо действительно качать навык, а не просто
 * доиграть до нужного уровня класса.</p>
 *
 * @param id            ид таланта ({@code warrior-natisk-2})
 * @param classId       ид класса
 * @param branch        ид ветки внутри класса
 * @param tier          ярус: 1..5, строго растёт снизу вверх
 * @param cost          цена в очках (пятый ярус — 1 очко, но требует 75 навыка)
 * @param requires      ид предыдущего таланта ветки или {@code ""}
 * @param skill         ид навыка, который нужно поднять, или {@code ""}
 * @param skillLevel    требуемый уровень навыка (0 — не требуется)
 * @param kind          вид эффекта
 * @param value         величина эффекта до потолка
 */
public record Talent(String id, String classId, String branch, int tier, int cost,
                     String requires, String skill, int skillLevel,
                     EffectKind kind, double value, int maxRank, int minLevel, String exclusiveGroup) {

    public Talent(String id, String classId, String branch, int tier, int cost, String requires,
                  String skill, int skillLevel, EffectKind kind, double value) {
        this(id, classId, branch, tier, cost, requires, skill, skillLevel, kind, value, 1, 1, "");
    }
    public int rankCost(int rank) { return maxRank == 1 ? cost : rank; }
    public int totalCost(int rank) {
        if (rank < 0 || rank > maxRank) throw new IllegalArgumentException("Некорректный ранг таланта");
        return maxRank == 1 ? rank * cost : rank * (rank + 1) / 2;
    }
    public Talent {
        id = id == null ? "" : id.trim();
        classId = classId == null ? "" : classId.trim();
        branch = branch == null ? "" : branch.trim();
        requires = requires == null ? "" : requires.trim();
        skill = skill == null ? "" : skill.trim();
        if (maxRank < 1 || maxRank > 3 || minLevel < 1 || minLevel > 50 || kind == null
                || !Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Некорректный талант");
        exclusiveGroup = exclusiveGroup == null ? "" : exclusiveGroup;
        tier = Math.clamp(tier, 1, ClassMath.MAX_TIER);
        cost = Math.clamp(cost, 1, 10);
        skillLevel = Math.clamp(skillLevel, 0, 100);
    }

    /** Есть ли у таланта родитель в ветке. */
    public boolean hasParent() {
        return !requires.isEmpty();
    }

    /** Нужен ли уровень навыка. */
    public boolean needsSkill() {
        return !skill.isEmpty() && skillLevel > 0;
    }
}
