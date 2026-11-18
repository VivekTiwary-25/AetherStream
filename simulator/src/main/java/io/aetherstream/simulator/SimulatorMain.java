package io.aetherstream.simulator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain Java entry point; run with {@code :simulator:run} or the installed application script.
 * Environment defaults: {@code SIM_SEED=42}, {@code SIM_TIME_SCALE=10},
 * {@code SIM_INITIAL_TRUCKS=8}, {@code SIM_MALFORMED_RATE=0.0025},
 * {@code SIM_STUCK_TRUCK_DEMO=true}, {@code KAFKA_BOOTSTRAP=kafka:9092},
 * {@code KAFKA_TOPIC=truck-events}.
 * HTTP on 8070: {@code GET /health}, {@code GET /state}, {@code POST /trucks},
 * {@code DELETE /trucks/{truckId}}, {@code POST /control},
 * {@code POST /chaos/late}, {@code POST /chaos/duplicates},
 * {@code POST /chaos/malformed}, and {@code POST /scenario}.
 * Scenarios use simulation time: {@code rush-hour} slows centre traffic by 40% for 10 minutes
 * (centre means both x and y in 600..1400 inclusive); {@code breakdown} stops a truck for
 * 12 minutes (default {@code truck-005}); {@code surge} adds six trucks over one minute.
 * {@code reset} restores the initial fleet and normal traffic without rewinding the clock
 * or reusing event IDs. Send {@code POST /scenario {"name":"breakdown","truckId":"truck-005"}};
 * {@code truckId} is optional, but when supplied must be textual and nonblank.
 * Each request replaces the active scenario; reset also cancels pending surge joins and the
 * automatic stuck-truck demo. Surge joins occur every 10 sim-seconds; {@code GET /state}
 * reports {@code scenario} ({@code none} after reset or a timed scenario expires).
 * Kafka outages leave HTTP and simulation ticks active; unsent records are buffered in memory,
 * not durably, and can exhaust the heap during a prolonged outage.
 */
public final class SimulatorMain implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SimulatorMain.class);
    private static final long TICK_MS = 100;

    private final Simulation simulation;
    private final SimClock clock;
    private final KafkaEmitter emitter;
    private final HttpApi http;
    private final ScheduledExecutorService ticker;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private boolean started;
    private boolean closed;

    public SimulatorMain(SimulatorConfig config) throws IOException {
        simulation = new Simulation(config.seed(), config.initialTrucks(),
                config.malformedRate(), config.stuckTruckDemo());
        clock = new SimClock(config.timeScale());
        emitter = new KafkaEmitter(config);
        http = new HttpApi(new InetSocketAddress(8070), simulation, clock, emitter::submit);
        ticker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("simulator-ticker").factory());
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Simulator is closed");
        }
        if (started) {
            return;
        }
        // No KafkaProducer is constructed until after the HTTP server is accepting requests.
        http.start();
        emitter.start();
        ticker.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        started = true;
        LOG.info("Simulator HTTP listening on port {}", http.port());
    }

    private void tick() {
        try {
            synchronized (simulation) {
                if (clock.running()) {
                    long now = clock.advance(TICK_MS);
                    emitter.submit(simulation.step(now));
                }
            }
        } catch (RuntimeException e) {
            // Scheduled executors otherwise silently cancel every subsequent tick after a failure.
            LOG.error("Simulation tick failed", e);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            ticker.shutdownNow();
        }
        try {
            http.close();
        } finally {
            try {
                // Finish any tick already inside the monitor before closing emission submission.
                synchronized (simulation) {
                    clock.setRunning(false);
                }
                emitter.close();
            } finally {
                stopped.countDown();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        SimulatorMain simulator = new SimulatorMain(SimulatorConfig.fromEnvironment());
        Thread shutdown = Thread.ofPlatform().name("simulator-shutdown").unstarted(simulator::close);
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            simulator.start();
            simulator.stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            simulator.close();
        }
    }
}
