package io.aetherstream.simulator;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Wall-clock adapter. All calls are serialized by the Simulation monitor. */
final class SimulatorRuntime implements HttpApi.Control {
    // Fixed across deployments: 2026-11-21T00:00:00Z (not a process start timestamp).
    static final long DEFAULT_ANCHOR_EPOCH_MS = 1795219200000L;

    final Simulation simulation;
    private final SimClock clock;
    private final LongSupplier wallTime;
    private long lastRealMs;

    SimulatorRuntime(SimulatorConfig config, long anchorRealMs, LongSupplier wallTime,
                     OptionalLong latestEventTime) {
        this.wallTime = wallTime;
        lastRealMs = wallTime.getAsLong();
        double scaledElapsed = Math.subtractExact(lastRealMs, anchorRealMs) * config.timeScale();
        if (!Double.isFinite(scaledElapsed) || scaledElapsed >= 0x1.0p63 || scaledElapsed < -0x1.0p63) {
            throw new IllegalArgumentException("Wall-anchored simulation time exceeds the clock range");
        }
        long initial = Math.addExact(Simulation.START_EPOCH_MS, (long) Math.floor(scaledElapsed));
        if (latestEventTime.isPresent()) {
            initial = Math.max(initial, Math.addExact(latestEventTime.getAsLong(), Simulation.POSITION_INTERVAL_MS));
        }
        clock = new SimClock(initial, config.timeScale());
        // Fixed-width decimal UUID namespace keeps the existing numeric sequence wire shape.
        // Unlike a time-derived counter alone, it also survives same-millisecond/offline restarts.
        String runPrefix = String.format(Locale.ROOT, "%039d",
                new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16));
        simulation = new Simulation(config.seed(), config.initialTrucks(), config.malformedRate(),
                config.stuckTruckDemo(), initial, runPrefix);
    }

    List<Simulation.Emission> tick() {
        updateClock();
        return clock.running() ? simulation.step(clock.now()) : List.of();
    }

    private void updateClock() {
        long realNow = wallTime.getAsLong();
        // Hold the high-water mark through wall-clock corrections; never count a rebound twice.
        if (realNow > lastRealMs) {
            clock.advance(Math.subtractExact(realNow, lastRealMs));
            lastRealMs = realNow;
        }
    }

    @Override
    public boolean running() { return clock.running(); }

    @Override
    public double timeScale() { return clock.timeScale(); }

    @Override
    public void setRunning(boolean running) {
        updateClock();
        clock.setRunning(running);
    }

    @Override
    public void setTimeScale(double timeScale) {
        if (!Double.isFinite(timeScale) || timeScale <= 0) {
            throw new IllegalArgumentException("timeScale must be finite and positive");
        }
        updateClock();
        clock.setTimeScale(timeScale);
    }
}
