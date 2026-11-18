package io.aetherstream.tests.bench;

import com.fasterxml.jackson.databind.JsonNode;
import io.aetherstream.common.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

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
        Iterator<Map.Entry<String, JsonNode>> sections = data.fields();
        while (sections.hasNext()) {
            Map.Entry<String, JsonNode> section = sections.next();
            markdown.append("## ").append(label(section.getKey())).append("\n\n");
            render(markdown, section.getValue(), 0);
            markdown.append('\n');
        }
        markdown.append("## Measurement scope and limitations\n\n")
                .append("- This is a single-CI-runner benchmark, not a multi-host scalability claim. ")
                .append("Coordinator and worker process roles run in the same JVM; infrastructure shares the runner.\n")
                .append("- Baseline throughput includes checkpoint waiting, but excludes preproduction ")
                .append("and reference comparison. Recovery is measured separately.\n")
                .append("- Latency is baseline only: PostgreSQL `written_at` minus the local producer send ")
                .append("timestamp. PostgreSQL uses a transaction timestamp, not a commit timestamp. ")
                .append("This assumes clocks on the same host; startup and backlog are included.\n")
                .append("- Percentiles are nearest-rank (NN-rank) values supplied by the caller; ")
                .append("the report writer does not recompute them. Sample counts and percentile values ")
                .append("appear only when supplied. Latency and checkpoint-duration values are in milliseconds.\n")
                .append("- The recovery workload intentionally delays the sink by 1 ms before the crash; ")
                .append("that delay is removed afterward. Recovery figures must not be treated as baseline throughput.\n");
        Files.writeString(directory.resolve("report.md"), markdown, StandardCharsets.UTF_8);
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
