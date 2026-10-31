package ru.cherdakmd.chrdkclaims;

import java.util.ArrayList;
import java.util.List;

/** Ограниченный кадр: курсор проходит весь контур, а не застревает на первых рёбрах. */
final class ClaimBorderParticles {
    static final int MAX_PER_PULSE = 512;
    static final int SAMPLES_PER_EDGE = 29;
    private static final double[] HEIGHTS = {.15, 1.4, 2.65};
    record Point(double x, double height, double z, boolean spark) { }
    record Frame(List<Point> points, long nextCursor) {
        Frame { points = List.copyOf(points); }
    }

    static Frame frame(List<ClaimBorders.Edge> edges, long cursor) {
        if (edges.isEmpty()) return new Frame(List.of(), 0);
        long total = (long) edges.size() * SAMPLES_PER_EDGE;
        long start = Math.floorMod(cursor, total);
        int count = (int) Math.min(total, MAX_PER_PULSE);
        List<Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long index = (start + i) % total;
            // Сначала проходим все рёбра на одной высоте, затем следующие слои.
            ClaimBorders.Edge edge = edges.get((int) (index % edges.size()));
            int sample = (int) (index / edges.size());
            boolean spark = sample >= 27;
            int offset = spark ? (sample - 27) * 16 : sample % 9 * 2;
            double height = spark ? 3.9 : HEIGHTS[sample / 9];
            points.add(new Point(edge.x1() + (edge.x2() - edge.x1()) * offset / 16.0,
                    height, edge.z1() + (edge.z2() - edge.z1()) * offset / 16.0, spark));
        }
        return new Frame(points, (start + count) % total);
    }
    private ClaimBorderParticles() { }
}
