package me.cinematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import me.cinematic.core.PanelGeometry;
import me.cinematic.core.ControlPolicy;
import me.cinematic.core.ScreenKey;
import me.cinematic.core.ScreenLayout;
import me.cinematic.core.ScreenLayout.Cell;
import me.cinematic.core.ScreenSession;
import org.junit.jupiter.api.Test;

class ScreenLayoutTest {
    @Test void aFilledWallBecomesOnePanelRegardlessOfBuildOrder() {
        var cells = wall(8, 5);
        assertEquals(java.util.List.of(new ScreenLayout.Rectangle(new Cell(0, 0), 8, 5)), ScreenLayout.rectangles(cells));
    }

    @Test void incompleteWallsAndHolesNeverProducePhantomScreenBlocks() {
        var cells = wall(4, 3);
        cells.remove(new Cell(1, 1));
        assertExactCoverage(cells);
        assertTrue(ScreenLayout.rectangles(cells).size() > 1);
        cells.add(new Cell(1, 1));
        assertEquals(1, ScreenLayout.rectangles(cells).size());
    }

    @Test void oversizedWallsAreSplitIntoPanelsWithinTheFortyBlockLimit() {
        var cells = wall(41, 42);
        assertExactCoverage(cells);
        assertTrue(ScreenLayout.rectangles(cells).stream().allMatch(r -> r.width() <= 40 && r.height() <= 40));
    }

    @Test void geometryMapsWallBlocksInAllFourDirectionsAndDistanceUsesWholeWall() {
        ScreenKey origin = new ScreenKey("minecraft:overworld", 10, 20, 30);
        for (int facing = 0; facing < 4; facing++) {
            ScreenKey end = PanelGeometry.block(origin, facing, 39, 39);
            assertEquals(59, end.y());
            assertEquals(0, PanelGeometry.distanceSquared(origin, facing, 40, 40, end.x() + .5, end.y() + .5, end.z() + .5));
        }
        assertEquals(4, PanelGeometry.distanceSquared(origin, 0, 40, 40, 52, 21, 30.5));
    }

    @Test void movingTheAnchorPreservesIdentityMediaAndTimelineAndBumpsRevisionOnce() {
        var screen = new ScreenSession(UUID.randomUUID(), new ScreenKey("minecraft:overworld", 0, 0, 0), 2);
        screen.select("a".repeat(32), 10000, 0);
        var id = screen.id(); var timeline = screen.timeline();
        screen.reshape(new ScreenKey("minecraft:overworld", 2, -1, 0), 3, 2);
        assertEquals(id, screen.id()); assertEquals("a".repeat(32), screen.mediaId()); assertEquals(timeline, screen.timeline());
        assertEquals(2, screen.revision());
        screen.reshape(screen.key(), 3, 2);
        assertEquals(2, screen.revision());
    }

    @Test void remotePermissionsApplyNearTheFarEdgeOfALargeWall() {
        var screen = new ScreenSession(UUID.randomUUID(), new ScreenKey("minecraft:overworld", 0, 0, 0), 0);
        screen.reshape(screen.key(), 40, 40);
        assertDoesNotThrow(() -> ControlPolicy.check(screen, "minecraft:overworld", 39.5, 39.5, 17, true, screen.revision()));
        assertThrows(IllegalArgumentException.class, () -> ControlPolicy.check(screen, "minecraft:overworld", 39.5, 39.5, 17.01, true, screen.revision()));
    }

    private static Set<Cell> wall(int width, int height) {
        var result = new HashSet<Cell>();
        for (int x = 0; x < width; x++) { for (int y = 0; y < height; y++) { result.add(new Cell(x, y)); } }
        return result;
    }

    private static void assertExactCoverage(Set<Cell> cells) {
        var covered = new HashSet<Cell>();
        for (var r : ScreenLayout.rectangles(cells)) {
            for (int x = 0; x < r.width(); x++) {
                for (int y = 0; y < r.height(); y++) {
                    Cell cell = new Cell(r.origin().right() + x, r.origin().up() + y);
                    assertTrue(cells.contains(cell)); assertTrue(covered.add(cell), "Panels must not overlap");
                }
            }
        }
        assertEquals(cells, covered);
        assertFalse(covered.isEmpty());
    }
}
