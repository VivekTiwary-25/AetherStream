package io.aetherstream.simulator;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimClockTest {
    @Test
    void fractionalTicksAccumulateAndPauseDoesNotCatchUpWhenResumed() {
        SimClock clock = new SimClock(0.001);
        for (int i = 0; i < 1000; i++) clock.advance(100);
        assertEquals(Simulation.START_EPOCH_MS + 100, clock.now());
        clock.setRunning(false);
        assertEquals(clock.now(), clock.advance(60_000));
        clock.setTimeScale(20);
        clock.setRunning(true);
        assertEquals(Simulation.START_EPOCH_MS + 2100, clock.advance(100));
        clock.setTimeScale(2.5);
        assertEquals(Simulation.START_EPOCH_MS + 2350, clock.advance(100));
    }

    @Test
    void rejectedScaleAndOverflowLeaveClockUsable() {
        SimClock clock = new SimClock(Long.MAX_VALUE - 200, 1);
        assertThrows(IllegalArgumentException.class, () -> clock.setTimeScale(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> clock.advance(300));
        assertEquals(Long.MAX_VALUE - 100, clock.advance(100));
    }
}
