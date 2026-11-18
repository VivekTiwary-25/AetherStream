package io.aetherstream.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Immutable city geometry; coordinates are metres. */
public final class City {
    public static final int SIZE = 2000;
    public static final int GRID = 200;
    public static final Point DEPOT = new Point(1000, 1000);
    public record Point(double x, double y) {}
    public record Stop(String stopId, double x, double y) {
        Point point() { return new Point(x, y); }
    }

    private final List<Stop> stops;

    public City(long seed) {
        var random = new SplittableRandom(seed);
        var candidates = new ArrayList<Point>();
        for (int x = 0; x <= SIZE; x += GRID) {
            for (int y = 0; y <= SIZE; y += GRID) {
                if (x != 1000 || y != 1000) candidates.add(new Point(x, y));
            }
        }
        // A nearby first delivery ensures even the demo truck delivers before its outage.
        Point first = switch (random.nextInt(4)) {
            case 0 -> new Point(800, 1000);
            case 1 -> new Point(1200, 1000);
            case 2 -> new Point(1000, 800);
            default -> new Point(1000, 1200);
        };
        candidates.remove(first);
        var selected = new ArrayList<Stop>();
        selected.add(new Stop("stop-01", first.x(), first.y()));
        for (int i = 1; i < 24; i++) {
            Point point = candidates.remove(random.nextInt(candidates.size()));
            selected.add(new Stop("stop-" + String.format(java.util.Locale.ROOT, "%02d", i + 1), point.x(), point.y()));
        }
        stops = List.copyOf(selected);
    }

    public List<Stop> stops() { return stops; }

    public Point nearestStreet(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > SIZE || y < 0 || y > SIZE) {
            throw new IllegalArgumentException("x and y must be finite numbers between 0 and 2000");
        }
        double gridX = grid(x);
        double gridY = grid(y);
        return Math.abs(x - gridX) <= Math.abs(y - gridY) ? new Point(gridX, y) : new Point(x, gridY);
    }

    static double grid(double coordinate) { return Math.round(coordinate / GRID) * GRID; }
}
