package io.aetherstream.api.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Last 200 real coordinator observations / accepted API requests, retained across browser reloads. */
@Component
public class RecoveryHistory {
    private static final int LIMIT = 200;
    private final ArrayDeque<Recorded> entries = new ArrayDeque<>(LIMIT);
    private final List<Consumer<Recorded>> listeners = new CopyOnWriteArrayList<>();
    private long sequence;
    private long lastAuditId;

    public record Recorded(String orgId, Map<String, Object> entry) {}
    public record AuditFact(long id, long at, String actor, String action, JsonNode detail, String orgId) {}

    public synchronized List<Map<String, Object>> history(String orgId) {
        return entries.stream().filter(entry -> orgId.equals(entry.orgId())).map(Recorded::entry).toList();
    }

    public void subscribe(Consumer<Recorded> listener) { listeners.add(listener); }
    public void unsubscribe(Consumer<Recorded> listener) { listeners.remove(listener); }

    public synchronized void killRequested(String orgId, String executionId, String workerId, String actor, long at) {
        Map<String, Object> entry = base("KILL_REQUESTED", at, workerId, executionId, null, null);
        entry.put("actor", actor);
        entry.put("reason", "Kill command accepted; worker death is not yet confirmed");
        append(orgId, entry);
    }

    /** Audit ids are an ingestion cursor, independent of the bounded public event ids. */
    public synchronized void observe(AuditFact fact) {
        if (fact.id() <= lastAuditId) return;
        lastAuditId = fact.id();
        JsonNode detail = fact.detail();
        String event = switch (fact.action()) {
            case "worker.dead" -> "WORKER_DEAD";
            case "worker.restarted" -> "WORKER_RESTARTED";
            case "execution.recoveryStarted" -> "RECOVERING";
            case "execution.recovered" -> "RECOVERED";
            case "execution.assignment" -> "REBALANCED";
            case "checkpoint.completed" -> "CHECKPOINT_COMPLETED";
            default -> null;
        };
        if (event == null || fact.orgId() == null) return;
        List<Map<String, Object>> changes = List.of();
        if ("REBALANCED".equals(event)) {
            // A first assignment is not a reassignment. Compare assigned owners, not sampled worker status.
            if (detail.path("oldAssignment").isEmpty()) return;
            changes = partitionChanges(detail.path("oldAssignment"), detail.path("assignment"));
            if (changes.isEmpty()) return;
        }
        // The pause boundary comes from the coordinator, even if recovery completes between polls.
        long at = number(detail, "atMs") == null ? fact.at() : detail.path("atMs").asLong();
        String executionId = text(detail, "executionId");
        String workerId = text(detail, "workerId");
        if (workerId == null && ("RECOVERING".equals(event) || "RECOVERED".equals(event))
                && !"rebalance".equals(text(detail, "reason"))) {
            workerId = recoveryWorker(fact.orgId(), executionId, "RECOVERED".equals(event));
        }
        Long restored = number(detail, "restoredFromCheckpoint");
        if (restored == null) restored = number(detail, "checkpointId");
        Map<String, Object> entry = base(event, at, workerId, executionId,
                number(detail, "generation"), "CHECKPOINT_COMPLETED".equals(event) ? null : restored);
        entry.put("actor", fact.actor());
        if (text(detail, "reason") != null) entry.put("reason", text(detail, "reason"));
        Long duration = number(detail, "recoveryDurationMs");
        if (duration == null) duration = number(detail, "durationMs");
        // Recovery duration is coordinator-measured RECOVERING→all assigned workers RUNNING, never kill→join.
        if (duration != null) entry.put("durationMs", duration);
        if ("CHECKPOINT_COMPLETED".equals(event)) entry.put("checkpointId", number(detail, "checkpointId"));
        if (!changes.isEmpty()) entry.put("partitionChanges", changes);
        append(fact.orgId(), entry);
    }

    /** Execution recovery audits omit workerId; use preceding facts in this org/execution only. */
    private String recoveryWorker(String orgId, String executionId, boolean completing) {
        String observed = null;
        String requested = null;
        var previous = entries.descendingIterator();
        while (previous.hasNext()) {
            Recorded recorded = previous.next();
            Map<String, Object> entry = recorded.entry();
            if (!orgId.equals(recorded.orgId()) || !java.util.Objects.equals(executionId, entry.get("executionId"))) continue;
            String event = (String) entry.get("event");
            // Never carry a worker from a completed recovery into a later interval.
            if ("RECOVERED".equals(event)) break;
            String workerId = (String) entry.get("workerId");
            // Completion belongs to the worker identified at the pause boundary, not a later restart.
            if ("RECOVERING".equals(event)) {
                if (completing) return workerId;
                break;
            }
            if (workerId == null) continue;
            if (observed == null && ("WORKER_DEAD".equals(event) || "WORKER_RESTARTED".equals(event))) {
                observed = workerId;
            }
            if (requested == null && "KILL_REQUESTED".equals(event)) requested = workerId;
        }
        // A request can identify the target, but still never produces a death/restart event.
        return observed == null ? requested : observed;
    }

    private Map<String, Object> base(String event, long at, String workerId, String executionId,
                                     Long generation, Long restored) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", ++sequence);
        entry.put("event", event);
        entry.put("at", at);
        entry.put("workerId", workerId);
        entry.put("executionId", executionId);
        entry.put("generation", generation);
        entry.put("restoredFromCheckpoint", restored);
        return entry;
    }

    private void append(String orgId, Map<String, Object> entry) {
        Recorded recorded = new Recorded(orgId, Collections.unmodifiableMap(entry));
        if (entries.size() == LIMIT) entries.removeFirst();
        entries.addLast(recorded);
        for (Consumer<Recorded> listener : listeners) listener.accept(recorded);
    }

    private static List<Map<String, Object>> partitionChanges(JsonNode oldAssignment, JsonNode assignment) {
        Map<Integer, String> oldOwners = owners(oldAssignment);
        Map<Integer, String> newOwners = owners(assignment);
        Map<Integer, String> partitions = new TreeMap<>(oldOwners);
        partitions.putAll(newOwners);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int partition : partitions.keySet()) {
            String oldOwner = oldOwners.get(partition);
            String newOwner = newOwners.get(partition);
            if (java.util.Objects.equals(oldOwner, newOwner)) continue;
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("partition", partition);
            change.put("oldOwner", oldOwner);
            change.put("newOwner", newOwner);
            changes.add(Collections.unmodifiableMap(change));
        }
        return List.copyOf(changes);
    }

    private static Map<Integer, String> owners(JsonNode assignment) {
        Map<Integer, String> owners = new TreeMap<>();
        assignment.fields().forEachRemaining(worker -> worker.getValue().forEach(partition ->
                owners.put(partition.asInt(), worker.getKey())));
        return owners;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asLong();
    }
}
