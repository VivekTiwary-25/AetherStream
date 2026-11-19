package io.aetherstream.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Derives execution events from successive successful upstream observations.
 *
 * <p>Events are ordered STARTED (for a replacement execution), WORKER_DEAD (in previous worker
 * order), the state/recovery event, then CHECKPOINT_COMPLETED. Independent transitions are never
 * collapsed: a worker death, recovery and checkpoint completion can share an observation. The first
 * checkpoint is a baseline, not a completion. Recovery metadata changes while RUNNING imply a
 * recovery that completed between polls. CREATED-to-RUNNING is STARTED; STOPPED and FAILED both
 * produce STOPPED. Changes to recovery metadata in other states use that state's event.
 *
 * <p>Only id, state, generation, restoredFromCheckpoint and lastCompletedCheckpoint participate in
 * execution change detection. Payloads contain the complete current execution, copied independently
 * for each event. Cached values are immutable and do not retain caller-owned JSON. Missing execution
 * observations are ignored; missing worker observations preserve the last known live worker set.
 *
 * <p>RECOVERED includes durationMs from the first observed worker death, using a monotonic server
 * clock. Timing belongs to the current execution and is cleared on recovery, termination,
 * replacement or reset; without an observed death, durationMs is omitted.
 */
public final class ExecutionEvents {
    private final ObjectMapper mapper;
    private final LongSupplier nanoTime;
    private Snapshot previous;
    private Set<String> aliveWorkers = Set.of();
    private long workerDeadAt;
    private boolean recoveryPending;

    public ExecutionEvents(ObjectMapper mapper) {
        this(mapper, System::nanoTime);
    }

    ExecutionEvents(ObjectMapper mapper, LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public synchronized List<ObjectNode> observe(JsonNode execution, JsonNode workers) {
        if (execution == null || !execution.isObject()) {
            return List.of();
        }

        Snapshot current = Snapshot.of(execution);
        boolean replacement = previous != null && !Objects.equals(previous.id(), current.id());
        boolean baseline = previous == null || replacement;
        if (baseline) recoveryPending = false;
        boolean checkpointChanged = !baseline
                && !Objects.equals(previous.checkpoint(), current.checkpoint());
        String stateEvent = stateEvent(current, baseline);
        boolean started = replacement || (previous == null
                && ("RUNNING".equals(current.state()) || "CREATED".equals(current.state())));

        Set<String> observedAlive = workers != null && workers.isArray()
                ? liveWorkers(workers) : aliveWorkers;
        boolean workerDied = !observedAlive.containsAll(aliveWorkers);
        previous = current;
        Set<String> previouslyAlive = aliveWorkers;
        aliveWorkers = observedAlive;

        if (!started && !workerDied && stateEvent == null && !checkpointChanged) {
            return List.of();
        }
        List<ObjectNode> events = new ArrayList<>();
        if (started) {
            events.add(event(execution, "STARTED"));
        }
        if (workerDied) {
            if (!recoveryPending) {
                workerDeadAt = nanoTime.getAsLong();
                recoveryPending = true;
            }
            for (String workerId : previouslyAlive) {
                if (!observedAlive.contains(workerId)) {
                    events.add(event(execution, "WORKER_DEAD").put("workerId", workerId));
                }
            }
        }
        if (stateEvent != null && !(started && "STARTED".equals(stateEvent))) {
            ObjectNode payload = event(execution, stateEvent);
            if (("RECOVERING".equals(stateEvent) || "RECOVERED".equals(stateEvent))
                    && current.restored() != null) {
                payload.put("checkpointId", current.restored());
            }
            if ("RECOVERED".equals(stateEvent)) {
                if (recoveryPending) {
                    payload.put("durationMs",
                            TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - workerDeadAt));
                }
                recoveryPending = false;
            } else if ("STOPPED".equals(stateEvent)) {
                recoveryPending = false;
            }
            events.add(payload);
        }
        if (checkpointChanged) {
            ObjectNode payload = event(execution, "CHECKPOINT_COMPLETED");
            if (current.checkpoint() == null) {
                payload.putNull("checkpointId");
            } else {
                payload.put("checkpointId", current.checkpoint());
            }
            events.add(payload);
        }
        return events;
    }

    public synchronized void reset() {
        previous = null;
        aliveWorkers = Set.of();
        recoveryPending = false;
    }

    private String stateEvent(Snapshot current, boolean baseline) {
        boolean stateChanged = baseline || !Objects.equals(previous.state(), current.state());
        boolean recoveryChanged = !baseline
                && (!Objects.equals(previous.generation(), current.generation())
                || !Objects.equals(previous.restored(), current.restored()));
        if (!stateChanged && !recoveryChanged) {
            return null;
        }
        return switch (current.state() == null ? "" : current.state()) {
            case "RECOVERING" -> "RECOVERING";
            case "RUNNING" -> !baseline && ("RECOVERING".equals(previous.state()) || recoveryChanged)
                    ? "RECOVERED" : "STARTED";
            case "STOPPED", "FAILED" -> "STOPPED";
            case "CREATED" -> "STARTED";
            default -> null;
        };
    }

    private ObjectNode event(JsonNode execution, String name) {
        ObjectNode payload = mapper.createObjectNode();
        payload.setAll(((ObjectNode) execution).deepCopy());
        payload.put("event", name);
        return payload;
    }

    private static Set<String> liveWorkers(JsonNode workers) {
        Set<String> alive = new LinkedHashSet<>();
        for (JsonNode worker : workers) {
            JsonNode id = worker.get("workerId");
            if (id != null && id.isTextual() && worker.path("alive").asBoolean(false)) {
                alive.add(id.textValue());
            }
        }
        return Collections.unmodifiableSet(alive);
    }

    private record Snapshot(String id, String state, Long generation, Long restored, Long checkpoint) {
        private static Snapshot of(JsonNode execution) {
            return new Snapshot(text(execution, "id"), text(execution, "state"),
                    number(execution, "generation"), number(execution, "restoredFromCheckpoint"),
                    number(execution, "lastCompletedCheckpoint"));
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }

        private static Long number(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.longValue();
        }
    }
}
