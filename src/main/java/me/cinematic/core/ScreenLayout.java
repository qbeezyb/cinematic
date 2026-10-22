package me.cinematic.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Splits a wall into filled rectangular panels, never drawing across holes or corners. */
public final class ScreenLayout {
    public static final int MAX_SIZE = 40;
    public record Cell(int right, int up) {}
    public record Rectangle(Cell origin, int width, int height) {
        public boolean contains(Cell cell) {
            return cell.right() >= origin.right() && cell.right() < origin.right() + width
                    && cell.up() >= origin.up() && cell.up() < origin.up() + height;
        }
    }

    private ScreenLayout() {}

    public static List<Rectangle> rectangles(Set<Cell> blocks) {
        var remaining = new HashSet<>(blocks);
        var result = new ArrayList<Rectangle>();
        while (!remaining.isEmpty()) {
            Cell origin = remaining.stream().min(Comparator.comparingInt(Cell::up).thenComparingInt(Cell::right)).orElseThrow();
            int width = 1;
            while (width < MAX_SIZE && remaining.contains(new Cell(origin.right() + width, origin.up()))) { width++; }
            int height = 1;
            while (height < MAX_SIZE && fullRow(remaining, origin, width, height)) { height++; }
            result.add(new Rectangle(origin, width, height));
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) { remaining.remove(new Cell(origin.right() + x, origin.up() + y)); }
            }
        }
        return List.copyOf(result);
    }

    private static boolean fullRow(Set<Cell> cells, Cell origin, int width, int height) {
        for (int x = 0; x < width; x++) {
            if (!cells.contains(new Cell(origin.right() + x, origin.up() + height))) { return false; }
        }
        return true;
    }
}
