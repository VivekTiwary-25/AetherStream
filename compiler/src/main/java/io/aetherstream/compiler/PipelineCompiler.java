package io.aetherstream.compiler;

import io.aetherstream.common.Json;
import io.aetherstream.common.pipeline.Edge;
import io.aetherstream.common.pipeline.ExecutionPlan;
import io.aetherstream.common.pipeline.Node;
import io.aetherstream.common.pipeline.PipelineDefinition;
import io.aetherstream.common.pipeline.PlannedOperator;
import io.aetherstream.common.pipeline.ValidationError;
import io.aetherstream.common.pipeline.ValidationResult;
import io.aetherstream.compiler.validation.NodeConfigValidator;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Validates contract 02 and builds a deterministic, partition-local execution plan. */
public final class PipelineCompiler {
    private static final Set<String> NODE_TYPES = Set.of("source", "filter", "map", "keyBy", "window", "rule", "sink");

    /** Collects all validation errors; valid definitions include a six-partition plan. */
    public ValidationResult validate(PipelineDefinition def) {
        return compile(def, 6);
    }

    /** Builds a plan with the requested partition count, or throws with all validation errors. */
    public ExecutionPlan plan(PipelineDefinition def, int partitions) {
        ValidationResult result = compile(def, partitions);
        if (!result.valid()) {
            throw new IllegalArgumentException(result.errors().stream()
                    .map(error -> error.code() + ": " + error.message())
                    .collect(Collectors.joining("\n")));
        }
        return result.plan();
    }

    /** Loads a fresh copy of the exact demo definition from contract 02 section 1. */
    public static PipelineDefinition demoPipeline() {
        try (InputStream stream = PipelineCompiler.class.getResourceAsStream("/pipelines/logistics-demo.json")) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled pipeline /pipelines/logistics-demo.json");
            }
            return Json.MAPPER.readValue(stream, PipelineDefinition.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled logistics demo pipeline", e);
        }
    }

    private ValidationResult compile(PipelineDefinition def, int partitions) {
        List<ValidationError> errors = new ArrayList<>();
        if (partitions < 1) {
            errors.add(new ValidationError(null, "INVALID_CONFIG", "Pipeline partitions must be positive. Set partitions to at least 1."));
        }
        if (def == null) {
            errors.add(new ValidationError(null, "INVALID_CONFIG", "Pipeline definition is missing. Supply a pipeline with nodes and edges."));
            return invalid(errors);
        }

        Map<String, Node> nodes = new TreeMap<>();
        if (def.nodes() == null) {
            errors.add(new ValidationError(null, "INVALID_CONFIG", "Pipeline nodes are missing. Supply a list of blocks."));
        } else {
            for (Node node : def.nodes()) {
                if (node == null) {
                    errors.add(new ValidationError(null, "INVALID_CONFIG", "Pipeline nodes contains a null block. Replace it with a block with an id, type and config."));
                    continue;
                }
                if (node.id() == null || node.id().isBlank()) {
                    error(errors, node, "INVALID_CONFIG", "needs a non-empty id. Give this block a unique id.");
                } else if (nodes.putIfAbsent(node.id(), node) != null) {
                    error(errors, node, "DUPLICATE_NODE_ID", "reuses an id. Give each block a unique id and update its edges.");
                }
                if (node.type() == null || !NODE_TYPES.contains(node.type())) {
                    error(errors, node, "UNKNOWN_NODE_TYPE", "has an unknown type. Use source, filter, map, keyBy, window, rule or sink.");
                } else {
                    NodeConfigValidator.validate(node, errors);
                }
            }
        }

        List<Node> sources = nodes.values().stream().filter(node -> "source".equals(node.type())).toList();
        if (sources.isEmpty()) {
            errors.add(new ValidationError(null, "NO_SOURCE", "Pipeline needs a Source. Add one Source block and connect it to the pipeline."));
        } else if (sources.size() > 1) {
            errors.add(new ValidationError(null, "MULTIPLE_SOURCES", "Pipeline has multiple Sources: "
                    + sources.stream().map(NodeConfigValidator::describe).collect(Collectors.joining(", "))
                    + ". Keep exactly one Source block."));
        }
        if (nodes.values().stream().noneMatch(node -> "sink".equals(node.type()))) {
            errors.add(new ValidationError(null, "NO_SINK", "Pipeline needs a Sink. Connect a postgres.windows or postgres.alerts Sink to the appropriate output."));
        }

        Map<String, List<String>> inputs = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();
        for (String id : nodes.keySet()) {
            inputs.put(id, new ArrayList<>());
            children.put(id, new ArrayList<>());
        }
        if (def.edges() != null) {
            for (Edge edge : def.edges()) {
                if (edge == null) {
                    errors.add(new ValidationError(null, "EDGE_UNKNOWN_NODE", "Pipeline contains a null edge. Replace it with an edge naming existing from and to node ids."));
                } else if (edge.from() == null || edge.to() == null || !nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to())) {
                    String missing = edge.from() == null || !nodes.containsKey(edge.from()) ? edge.from() : edge.to();
                    errors.add(new ValidationError(missing, "EDGE_UNKNOWN_NODE", "Edge '" + edge.from() + "' -> '" + edge.to()
                            + "' names a missing node. Add the missing block or change the edge to existing node ids."));
                } else {
                    inputs.get(edge.to()).add(edge.from());
                    children.get(edge.from()).add(edge.to());
                }
            }
        }
        inputs.values().forEach(list -> list.sort(String::compareTo));
        children.values().forEach(list -> list.sort(String::compareTo));

        for (Node node : nodes.values()) {
            List<String> parents = inputs.get(node.id());
            if (!"source".equals(node.type()) && parents.size() != 1) {
                error(errors, node, "MULTIPLE_INPUTS", "needs exactly one input, but has " + parents.size()
                        + ". Connect one upstream block; remove extra incoming edges.");
            }
            if ("source".equals(node.type()) && !parents.isEmpty()) {
                error(errors, node, "TYPE_MISMATCH", "cannot receive input. Remove incoming edges from the Source.");
            }
            if (!"sink".equals(node.type()) && children.get(node.id()).isEmpty()) {
                error(errors, node, "DANGLING_NODE", "has no output connection. Connect it to a downstream block ending in a Sink.");
            }
            for (String parentId : parents) {
                checkType(node, nodes.get(parentId), errors);
            }
            if ("rule".equals(node.type()) && parents.size() == 1) {
                Node upstream = nodes.get(parents.getFirst());
                if ("window".equals(upstream.type())) {
                    NodeConfigValidator.validateRuleInput(node, upstream, errors);
                }
            }
        }

        // Reachability is independent of topological sorting, so a connected cycle is not called unreachable.
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (Node source : sources) {
            if (reachable.add(source.id())) {
                pending.add(source.id());
            }
        }
        while (!pending.isEmpty()) {
            for (String child : children.get(pending.removeFirst())) {
                if (reachable.add(child)) {
                    pending.addLast(child);
                }
            }
        }
        for (Node node : nodes.values()) {
            if (!reachable.contains(node.id())) {
                error(errors, node, "UNREACHABLE_NODE", "cannot be reached from a Source. Connect it to the Source's downstream graph.");
            }
        }

        Map<String, Integer> remainingInputs = new HashMap<>();
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (String id : nodes.keySet()) {
            int count = inputs.get(id).size();
            remainingInputs.put(id, count);
            if (count == 0) {
                ready.add(id);
            }
        }
        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String id = ready.remove();
            order.add(id);
            for (String child : children.get(id)) {
                if (remainingInputs.compute(child, (key, count) -> count - 1) == 0) {
                    ready.add(child);
                }
            }
        }
        if (order.size() != nodes.size()) {
            String blocked = nodes.values().stream().filter(node -> remainingInputs.get(node.id()) > 0)
                    .map(NodeConfigValidator::describe).collect(Collectors.joining(", "));
            errors.add(new ValidationError(null, "CYCLE", "Pipeline contains a cycle blocking " + blocked
                    + ". Remove backward connections so every path flows from the Source toward a Sink."));
        }
        if (!errors.isEmpty()) {
            return invalid(errors);
        }

        Node source = sources.getFirst();
        List<PlannedOperator> operators = new ArrayList<>(order.size());
        for (String id : order) {
            Node node = nodes.get(id);
            operators.add(new PlannedOperator(id, node.type(), node.config(), List.copyOf(inputs.get(id)), outputType(node.type())));
        }
        ExecutionPlan plan = new ExecutionPlan(def.id(), def.version(), source.config().path("topic").textValue(), partitions,
                source.config().path("startFrom").asText("latest"), List.copyOf(operators));
        return new ValidationResult(true, List.of(), plan);
    }

    private static ValidationResult invalid(List<ValidationError> errors) {
        return new ValidationResult(false, List.copyOf(errors), null);
    }

    private static void checkType(Node node, Node upstream, List<ValidationError> errors) {
        String expected = switch (node.type() == null ? "" : node.type()) {
            case "filter", "map", "keyBy" -> "EVENT";
            case "window" -> "KEYED";
            case "rule" -> "WINDOW_RESULT";
            case "sink" -> node.config() == null ? null : switch (node.config().path("kind").asText()) {
                case "postgres.windows" -> "WINDOW_RESULT";
                case "postgres.alerts" -> "ALERT";
                default -> null;
            };
            default -> null;
        };
        String actual = outputType(upstream.type());
        if (expected == null || actual == null || expected.equals(actual)) {
            return;
        }
        String fix = switch (expected) {
            case "EVENT" -> "needs event input. Connect it after the Source, a Filter or a Map, before Key-by.";
            case "KEYED" -> "needs a Key-by before it. Add a Key-by block between " + NodeConfigValidator.describe(upstream) + " and this Window.";
            case "WINDOW_RESULT" -> "needs a Window before it. Connect it to a Window's output.";
            case "ALERT" -> "of kind postgres.alerts needs a Rule before it. Add a Rule after a Window and connect this Sink to the Rule.";
            default -> throw new IllegalStateException("Unknown input type " + expected);
        };
        error(errors, node, "TYPE_MISMATCH", fix);
    }

    private static String outputType(String type) {
        return switch (type == null ? "" : type) {
            case "source", "filter", "map" -> "EVENT";
            case "keyBy" -> "KEYED";
            case "window" -> "WINDOW_RESULT";
            case "rule" -> "ALERT";
            case "sink" -> "NONE";
            default -> null;
        };
    }

    private static void error(List<ValidationError> errors, Node node, String code, String message) {
        errors.add(new ValidationError(node.id(), code, NodeConfigValidator.describe(node) + " " + message));
    }
}
