package io.aetherstream.simulator;

/** Mutable simulation clock; callers hold the associated Simulation monitor. */
public final class SimClock implements HttpApi.Control {
    private long now;
    private double fractionalMillis;
    private double fractionalCompensation;
    private boolean running = true;
    private double timeScale;

    public SimClock() {
        this(Simulation.START_EPOCH_MS, 10.0);
    }

    public SimClock(double timeScale) {
        this(Simulation.START_EPOCH_MS, timeScale);
    }

    public SimClock(long initialEpochMs, double timeScale) {
        now = initialEpochMs;
        setTimeScale(timeScale);
    }

    public long now() {
        return now;
    }

    public long advance(long realElapsedMs) {
        if (realElapsedMs < 0) {
            throw new IllegalArgumentException("Elapsed time must be nonnegative");
        }
        if (!running) {
            return now;
        }
        // Compensated addition keeps repeated sub-millisecond ticks from losing a millisecond.
        double increment = realElapsedMs * timeScale - fractionalCompensation;
        double elapsed = fractionalMillis + increment;
        double compensation = (elapsed - fractionalMillis) - increment;
        if (!Double.isFinite(elapsed) || elapsed >= 0x1.0p63) {
            throw new IllegalArgumentException("Scaled elapsed time exceeds the clock range");
        }
        long wholeMillis = (long) elapsed;
        long next;
        try {
            next = Math.addExact(now, wholeMillis);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Simulation time exceeds the clock range", e);
        }
        fractionalMillis = elapsed - wholeMillis;
        fractionalCompensation = compensation;
        now = next;
        return now;
    }

    @Override
    public boolean running() {
        return running;
    }

    @Override
    public void setRunning(boolean running) {
        this.running = running;
    }

    @Override
    public double timeScale() {
        return timeScale;
    }

    @Override
    public void setTimeScale(double timeScale) {
        if (!Double.isFinite(timeScale) || timeScale <= 0) {
            throw new IllegalArgumentException("timeScale must be finite and positive");
        }
        this.timeScale = timeScale;
    }
}
