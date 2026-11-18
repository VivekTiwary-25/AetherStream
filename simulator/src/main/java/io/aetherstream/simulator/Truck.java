package io.aetherstream.simulator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** One event-driven truck. No wall clock, I/O, or threads. */
final class Truck {
    record Delivery(City.Stop stop, int parcels) {}
    private final City city;
    private final SplittableRandom random;
    final SplittableRandom corruption;
    private final ArrayDeque<City.Point> path = new ArrayDeque<>();
    private final boolean stuckDemo;
    final String id;
    long sequence;
    long nextPosition;
    double x;
    double y;
    double heading;
    double speed;
    String status = "IDLE";
    String routeId;
    private List<City.Stop> route;
    private int routeIndex;
    private int routeNumber;
    private City.Stop destination;
    private City.Point segmentStart;
    private City.Point segmentEnd;
    private long segmentStarted;
    private long transition = Long.MAX_VALUE;
    private boolean stuckStarted;
    private boolean stuckFinished;
    private String resumeStatus;
    private double resumeSpeed;
    private long pausedAt;

    Truck(City city, long seed, int index, City.Point start, long now, boolean stuckDemo) {
        this.city = city;
        this.id = "truck-" + String.format(java.util.Locale.ROOT, "%03d", index);
        this.random = new SplittableRandom(seed ^ index);
        this.corruption = new SplittableRandom((seed ^ index) ^ 0xBADC0FFEL);
        this.stuckDemo = stuckDemo && index == 7;
        x = start.x();
        y = start.y();
        nextPosition = now;
        newRoute(now);
        if (this.stuckDemo && now >= stuckEnd()) {
            stuckStarted = true;
            stuckFinished = true;
        } else if (this.stuckDemo && now >= stuckStart()) {
            stop(now);
        }
    }

    private static long stuckStart() { return Simulation.START_EPOCH_MS + 300_000; }
    private static long stuckEnd() { return Simulation.START_EPOCH_MS + 1_200_000; }

    long nextChange() {
        long next = transition;
        if (stuckDemo && !stuckStarted) next = Math.min(next, stuckStart());
        if (stuckDemo && stuckStarted && !stuckFinished) next = stuckEnd();
        return next;
    }

    Delivery advance(long now) {
        interpolate(now);
        if (stuckDemo && !stuckStarted && now == stuckStart()) {
            stop(now);
            return null;
        }
        if (stuckDemo && stuckStarted && !stuckFinished) {
            if (now == stuckEnd()) {
                long delay = now - pausedAt;
                if (transition != Long.MAX_VALUE) transition += delay;
                segmentStarted += delay;
                status = resumeStatus;
                speed = resumeSpeed;
                stuckFinished = true;
            }
            return null;
        }
        if (now != transition) return null;
        if (status.equals("DRIVING")) {
            x = segmentEnd.x();
            y = segmentEnd.y();
            if (!path.isEmpty()) {
                startSegment(now);
            } else if (destination == null) {
                newRoute(now);
            } else {
                status = "UNLOADING";
                speed = 0;
                transition = now + random.nextLong(30_000, 90_000);
                // Never collide with this truck's fixed position schedule: all event times stay strict.
                if (Math.floorMod(transition - nextPosition, Simulation.POSITION_INTERVAL_MS) == 0) transition++;
            }
            return null;
        }
        if (status.equals("UNLOADING")) {
            Delivery delivery = new Delivery(destination, random.nextInt(1, 13));
            routeIndex++;
            goTo(routeIndex < route.size() ? route.get(routeIndex) : null, now);
            return delivery;
        }
        return null;
    }

    private void stop(long now) {
        stuckStarted = true;
        pausedAt = now;
        resumeStatus = status;
        resumeSpeed = speed;
        status = "STOPPED";
        speed = 0;
    }

    private void interpolate(long now) {
        if (!status.equals("DRIVING")) return;
        double fraction = Math.min(1.0, (double) (now - segmentStarted) / (transition - segmentStarted));
        x = segmentStart.x() + (segmentEnd.x() - segmentStart.x()) * fraction;
        y = segmentStart.y() + (segmentEnd.y() - segmentStart.y()) * fraction;
    }

    private void newRoute(long now) {
        routeNumber++;
        routeId = "r-" + id.substring(6) + "-" + routeNumber;
        var choices = new ArrayList<>(city.stops());
        var selected = new ArrayList<City.Stop>();
        // stop-01 is one block from the depot; subsequent stops vary per truck seed.
        selected.add(choices.remove(0));
        int count = random.nextInt(3, 6);
        while (selected.size() < count) selected.add(choices.remove(random.nextInt(choices.size())));
        route = selected;
        routeIndex = 0;
        goTo(route.get(0), now);
    }

    private void goTo(City.Stop stop, long now) {
        destination = stop;
        City.Point target = stop == null ? City.DEPOT : stop.point();
        path.clear();
        double atX = x;
        double atY = y;
        // API-added trucks may start between intersections, but always on a street.
        if (atX != City.grid(atX)) {
            atX = City.grid(atX);
            path.add(new City.Point(atX, atY));
        }
        if (atY != City.grid(atY)) {
            atY = City.grid(atY);
            path.add(new City.Point(atX, atY));
        }
        if (atX != target.x()) path.add(new City.Point(target.x(), atY));
        if (atY != target.y()) path.add(target);
        if (path.isEmpty()) {
            status = stop == null ? "IDLE" : "UNLOADING";
            speed = 0;
            transition = now + (stop == null ? 1 : random.nextLong(30_000, 90_000));
            if (stop != null && Math.floorMod(transition - nextPosition, Simulation.POSITION_INTERVAL_MS) == 0) transition++;
        } else {
            startSegment(now);
        }
    }

    private void startSegment(long now) {
        segmentStart = new City.Point(x, y);
        segmentEnd = path.removeFirst();
        segmentStarted = now;
        speed = random.nextDouble(20, 50);
        double distance = Math.abs(segmentEnd.x() - x) + Math.abs(segmentEnd.y() - y);
        transition = now + Math.max(1, (long) Math.ceil(distance / (speed / 3.6) * 1000));
        heading = segmentEnd.x() > x ? 90 : segmentEnd.x() < x ? 270 : segmentEnd.y() > y ? 0 : 180;
        status = "DRIVING";
    }
}
