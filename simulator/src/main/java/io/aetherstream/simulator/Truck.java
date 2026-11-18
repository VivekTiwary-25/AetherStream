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
    private double segmentPhase;
    private double segmentDuration;
    private double cruiseSpeed;
    private boolean rushHour;
    private boolean waitingLight;
    private long transition = Long.MAX_VALUE;
    private boolean stuckStarted;
    private long pauseUntil;
    private String resumeStatus;
    private double resumeSpeed;
    private long pausedAt;
    private final double laneOffset;

    Truck(City city, long seed, int index, City.Point start, long now, boolean stuckDemo) {
        this.city = city;
        this.id = "truck-" + String.format(java.util.Locale.ROOT, "%03d", index);
        this.random = new SplittableRandom(seed ^ index);
        this.laneOffset = 0.2 + 0.8 * (1.0 - 1.0 / (index + 1.0));
        this.corruption = new SplittableRandom((seed ^ index) ^ 0xBADC0FFEL);
        this.stuckDemo = stuckDemo && index == 7;
        x = start.x();
        y = start.y();
        nextPosition = now;
        newRoute(now);
        if (this.stuckDemo && now >= stuckStart()) {
            stuckStarted = true;
            if (now < stuckEnd()) pause(now, stuckEnd());
        }
    }

    private static long stuckStart() { return Simulation.START_EPOCH_MS + 300_000; }
    private static long stuckEnd() { return Simulation.START_EPOCH_MS + 1_200_000; }

    long nextChange() {
        long next = pauseUntil > 0 ? pauseUntil : transition;
        if (stuckDemo && !stuckStarted) next = Math.min(next, stuckStart());
        return next;
    }

    Delivery advance(long now) {
        interpolate(now);
        if (stuckDemo && !stuckStarted && now == stuckStart()) {
            stuckStarted = true;
            pause(now, stuckEnd());
        }
        if (pauseUntil > 0) {
            if (now < pauseUntil) return null;
            resume(now);
        }
        if (now != transition) return null;
        if (status.equals("DRIVING")) {
            x = segmentEnd.x();
            y = segmentEnd.y();
            if (!path.isEmpty()) {
                if (random.nextInt(10) == 0) {
                    waitingLight = true;
                    status = "IDLE";
                    speed = 0;
                    transition = now + random.nextLong(2000, 6001);
                } else {
                    startSegment(now);
                }
            } else if (destination == null) {
                newRoute(now);
            } else {
                status = "UNLOADING";
                speed = 0;
                transition = now + random.nextLong(30_000, 90_000);
                avoidPositionCollision();
            }
            return null;
        }
        if (waitingLight) {
            waitingLight = false;
            startSegment(now);
        } else if (status.equals("UNLOADING")) {
            Delivery delivery = new Delivery(destination, random.nextInt(1, 13));
            routeIndex++;
            goTo(routeIndex < route.size() ? route.get(routeIndex) : null, now);
            return delivery;
        } else {
            newRoute(now);
        }
        return null;
    }

    void pause(long now, long until) {
        if (pauseUntil == 0) {
            interpolate(now);
            pausedAt = now;
            resumeStatus = status;
            resumeSpeed = speed;
        }
        pauseUntil = Math.max(pauseUntil, until);
        status = "STOPPED";
        speed = 0;
    }

    void clearBreakdown(long now) {
        if (pauseUntil == 0) return;
        if (stuckDemo && stuckStarted && now < stuckEnd()) {
            pauseUntil = stuckEnd();
        } else {
            resume(now);
        }
    }

    private void resume(long now) {
        long delay = now - pausedAt;
        if (transition != Long.MAX_VALUE) transition += delay;
        segmentStarted += delay;
        status = resumeStatus;
        speed = resumeSpeed;
        pauseUntil = 0;
    }

    void setRushHour(boolean enabled, long now) {
        if (rushHour == enabled) return;
        // Paused trucks retain the phase at which they stopped, not elapsed outage time.
        long at = pauseUntil > 0 ? pausedAt : now;
        interpolate(at);
        if ((pauseUntil > 0 ? resumeStatus : status).equals("DRIVING")) {
            segmentPhase = phase(at);
            segmentStarted = at;
            rushHour = enabled;
            timeSegment(at);
            double currentSpeed = profileSpeed(segmentPhase);
            if (pauseUntil > 0) resumeSpeed = currentSpeed;
            else speed = currentSpeed;
        } else {
            rushHour = enabled;
        }
    }

    double laneX() {
        if (heading != 0 && heading != 180) return x;
        double offset = heading == 0 ? laneOffset : -laneOffset;
        return x + (x + offset < 0 || x + offset > City.SIZE ? -offset : offset);
    }

    double laneY() {
        if (heading != 90 && heading != 270) return y;
        double offset = heading == 90 ? -laneOffset : laneOffset;
        return y + (y + offset < 0 || y + offset > City.SIZE ? -offset : offset);
    }

    private double phase(long now) {
        return Math.min(1.0, segmentPhase + (now - segmentStarted) / segmentDuration);
    }

    private double trafficFactor() {
        return rushHour && City.inCentre((segmentStart.x() + segmentEnd.x()) / 2,
                (segmentStart.y() + segmentEnd.y()) / 2) ? 0.6 : 1.0;
    }

    private double profileSpeed(double phase) {
        return cruiseSpeed * trafficFactor() * (0.65 + 0.35 * Math.sin(Math.PI * phase));
    }

    private void interpolate(long now) {
        if (!status.equals("DRIVING")) return;
        double phase = phase(now);
        // Integral of the speed profile: position and reported speed describe the same motion.
        double fraction = (0.65 * phase + 0.35 / Math.PI * (1 - Math.cos(Math.PI * phase)))
                / (0.65 + 0.7 / Math.PI);
        x = segmentStart.x() + (segmentEnd.x() - segmentStart.x()) * fraction;
        y = segmentStart.y() + (segmentEnd.y() - segmentStart.y()) * fraction;
        speed = profileSpeed(phase);
    }

    private void avoidPositionCollision() {
        if (Math.floorMod(transition - nextPosition, Simulation.POSITION_INTERVAL_MS) == 0) transition++;
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
        while (atX != target.x()) {
            atX += Math.copySign(Math.min(City.GRID, Math.abs(target.x() - atX)), target.x() - atX);
            path.add(new City.Point(atX, atY));
        }
        while (atY != target.y()) {
            atY += Math.copySign(Math.min(City.GRID, Math.abs(target.y() - atY)), target.y() - atY);
            path.add(new City.Point(atX, atY));
        }
        if (path.isEmpty()) {
            status = stop == null ? "IDLE" : "UNLOADING";
            transition = now + (stop == null ? 1 : random.nextLong(30_000, 90_000));
            if (stop != null) avoidPositionCollision();
        } else {
            startSegment(now);
        }
    }

    private void startSegment(long now) {
        segmentStart = new City.Point(x, y);
        segmentEnd = path.removeFirst();
        segmentStarted = now;
        segmentPhase = 0;
        cruiseSpeed = City.speedLimit(segmentStart, segmentEnd) * random.nextDouble(0.85, 1.0);
        timeSegment(now);
        speed = profileSpeed(0);
        heading = segmentEnd.x() > x ? 90 : segmentEnd.x() < x ? 270 : segmentEnd.y() > y ? 0 : 180;
        status = "DRIVING";
    }

    private void timeSegment(long now) {
        double distance = Math.abs(segmentEnd.x() - segmentStart.x()) + Math.abs(segmentEnd.y() - segmentStart.y());
        segmentDuration = distance / (cruiseSpeed * trafficFactor() / 3.6 * (0.65 + 0.7 / Math.PI)) * 1000;
        transition = now + Math.max(1, (long) Math.ceil((1 - segmentPhase) * segmentDuration));
    }
}
