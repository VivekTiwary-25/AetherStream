package io.aetherstream.tests.bench;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Locale;

/** Writes the supplied measurements without inventing values for unfinished phases. */
final class BenchReport {
    private BenchReport() {}

    static void write(Map<String, Object> report) throws IOException {
        Path directory = Path.of(System.getProperty(
                "aether.benchmark.reportDir", "build/reports/benchmark"));
        Files.createDirectories(directory);
        String json = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.writeString(directory.resolve("report.json"), json + "\n", StandardCharsets.UTF_8);

        JsonNode data = Json.MAPPER.valueToTree(report);
        StringBuilder markdown = new StringBuilder("# Engine benchmark report\n\n");
        markdown.append("Only supplied values are reported. Missing fields indicate unavailable measurements, ")
                .append("including phases not completed after a failure; they are not zero.\n\n");
        markdown.append("| Workers | Run | Events/s | Checkpoint p50 ms | Checkpoint p95 ms | CP samples | Kill → RUNNING ms | Workers after kill | Reference match |\n")
                .append("| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (JsonNode run : data.path("runs")) {
            for (String phase : new String[] {"baseline", "recovery"}) {
                JsonNode metrics = run.path(phase);
                JsonNode checkpoints = metrics.path("checkpointDurationMs");
                markdown.append("| ").append(number(run.path("workers"))).append(" | ").append(phase)
                        .append(" | ").append(number(metrics.path("recordsPerSecond")))
                        .append(" | ").append(number(checkpoints.path("p50")))
                        .append(" | ").append(number(checkpoints.path("p95")))
                        .append(" | ").append(number(checkpoints.path("samples")))
                        .append(" | ").append(number(metrics.path("recoveryMs")))
                        .append(" | ").append(number(metrics.path("workersAfterKill")))
                        .append(" | ").append(match(metrics)).append(" |\n");
            }
        }
        markdown.append('\n');
        Iterator<Map.Entry<String, JsonNode>> sections = data.fields();
        while (sections.hasNext()) {
            Map.Entry<String, JsonNode> section = sections.next();
            markdown.append("## ").append(label(section.getKey())).append("\n\n");
            render(markdown, section.getValue(), 0);
            markdown.append('\n');
        }
        markdown.append("## Measurement scope and limitations\n\n")
                .append("- This is a single-CI-runner benchmark, not a multi-host scalability claim. ")
                .append("Coordinator runs in the test JVM; workers run in separate 512 MiB JVMs; Docker services share the runner.\n")
                .append("- Baseline events/s includes execution startup, durable sink writes, checkpoint completion ")
                .append("and up to 100 ms observation delay. Input is preloaded; production and reference replay are excluded.\n")
                .append("- Checkpoint percentiles are nearest-rank persisted durations from completed positive-ID ")
                .append("checkpoints, separated by execution. Sample counts are shown; small samples are not stable tail estimates.\n")
                .append("- Recovery uses a separate execution with 1 ms sink delay to retain backlog. ")
                .append("Time starts immediately before Process.destroyForcibly() and ends on observed RUNNING in a newer generation; ")
                .append("it includes heartbeat failure detection, restore, reassignment and observation, but not backlog drain. ")
                .append("The delay is removed after the kill.\n")
                .append("- One worker requires a replacement, launched only after the coordinator declares the victim DEAD; ")
                .append("that JVM startup is included. Two/three workers recover with one/two survivors, without replacement.\n")
                .append("- Both executions at each worker count must match reference windows/counts, alert payloads ")
                .append("and dead letters. Missing or unfinished measurements are unavailable, never zero.\n")
                .append("- Worker counts run in order 1, 2, 3 with no dedicated warmup or repetitions; JVM/JIT, ")
                .append("shared service caches, runner contention and checkpoint cadence affect comparisons.\n");
        Files.writeString(directory.resolve("report.md"), markdown, StandardCharsets.UTF_8);
    }

    private static String number(JsonNode value) {
        if (!value.isNumber()) return "unavailable";
        return value.isIntegralNumber() ? value.asText() : String.format(Locale.ROOT, "%.3f", value.asDouble());
    }

    private static String match(JsonNode metrics) {
        JsonNode comparison = metrics.path("comparison").path("match");
        JsonNode deadLetters = metrics.path("deadLettersMatch");
        JsonNode alerts = metrics.path("alertPayloadsMatch");
        if (!comparison.isBoolean() || !deadLetters.isBoolean() || !alerts.isBoolean()) return "unavailable";
        return comparison.asBoolean() && deadLetters.asBoolean() && alerts.asBoolean() ? "PASS" : "FAIL";
    }

    private static void render(StringBuilder markdown, JsonNode value, int depth) {
        if (value.isObject()) {
            if (value.isEmpty()) {
                bullet(markdown, depth).append("(empty object)\n");
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                bullet(markdown, depth).append(label(field.getKey())).append(':');
                if (field.getValue().isContainerNode()) {
                    markdown.append('\n');
                    render(markdown, field.getValue(), depth + 1);
                } else {
                    markdown.append(' ').append(scalar(field.getValue())).append('\n');
                }
            }
        } else if (value.isArray()) {
            if (value.isEmpty()) {
                bullet(markdown, depth).append("(empty list)\n");
            }
            for (JsonNode item : value) {
                if (item.isContainerNode()) {
                    bullet(markdown, depth).append("Item:\n");
                    render(markdown, item, depth + 1);
                } else {
                    bullet(markdown, depth).append(scalar(item)).append('\n');
                }
            }
        } else {
            bullet(markdown, depth).append(scalar(value)).append('\n');
        }
    }

    private static StringBuilder bullet(StringBuilder markdown, int depth) {
        return markdown.append("  ".repeat(depth)).append("- ");
    }

    private static String scalar(JsonNode value) {
        return value.isNull() ? "unavailable (null)" : escape(value.asText());
    }

    private static String label(String key) {
        return escape(key.replaceAll("([a-z0-9])([A-Z])", "$1 $2"));
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\r", "")
                .replace("\n", "<br>");
    }
}
